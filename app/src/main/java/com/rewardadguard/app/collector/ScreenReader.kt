package com.rewardadguard.app.collector

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** A screenshot plus how long taking it took, or why it failed. */
data class ScreenCapture(val bitmap: Bitmap?, val elapsedMillis: Long, val error: String?)

/** A [Snapshot] together with the pixels it came from (debug entry point only). */
data class ScreenReading(val snapshot: Snapshot, val bitmap: Bitmap?)

/**
 * A reading with the corner template results the ad handler needs. The caller
 * owns [bitmap] and must close this object, which recycles it.
 */
class ScreenAnalysis(
    val snapshot: Snapshot,
    val bitmap: Bitmap?,
    val corners: List<GlyphHit>,
    val backArrow: GlyphHit?
) : java.io.Closeable {
    override fun close() {
        bitmap?.recycle()
    }
}

/**
 * Reads the current screen into a [Snapshot].
 *
 * Two sources, in this order:
 *  1. the accessibility node tree - BitWalk is Flutter and exposes semantics, so
 *     "可兌換 50 個印章" is readable without waiting for the screen to settle;
 *  2. ML Kit Chinese OCR on a screenshot, as a fallback for ad SDKs that draw
 *     their text into a canvas/WebView where no node exists.
 *
 * Heavy work (a screenshot binder call plus OCR) happens only when the
 * collector asks for it. Nothing here is wired into `onAccessibilityEvent`; the
 * plan's "cheap callbacks" rule still holds.
 *
 * OCR is Android 11+ only (`AccessibilityService.takeScreenshot`). On older
 * devices the node tree is used alone and [isOcrSupported] stays false, which
 * the UI is expected to surface as "此裝置不支援 OCR".
 */
class ScreenReader(private val service: AccessibilityService) {

    private val recognizer: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    private var recognizerCreated = false

    /**
     * Delivers screenshot callbacks off the main looper: `onSuccess` copies a
     * full-screen hardware buffer into an ARGB bitmap (~10+ MB), which is far
     * too heavy to do on the service's main thread on every ad frame.
     */
    private val screenshotPool: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "collector-screenshot").apply { isDaemon = true }
        }
    }
    private var screenshotPoolCreated = false

    /** Loaded once: decoding the three template PNGs per ad frame would be waste. */
    private val cornerDetector by lazy { CornerGlyphDetector(service) }

    fun isOcrSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /**
     * Reads the screen. The caller owns the returned bitmap in [ScreenReading];
     * [read] is the convenience wrapper that recycles it.
     */
    suspend fun readDetailed(
        foregroundPackage: String? = null,
        foregroundActivity: String? = null,
        includeOcr: Boolean = true
    ): ScreenReading {
        val capture = if (includeOcr && isOcrSupported()) captureScreen() else null
        val bitmap = capture?.bitmap

        val nodes = withContext(Dispatchers.Default) { collectNodeBoxes() }

        var ocrBoxes: List<TextBox> = emptyList()
        var ocrMillis = 0L
        var ocrError = capture?.error
        if (includeOcr && !isOcrSupported()) {
            // The UI shows this verbatim, hence the zh-TW string: "this device
            // does not support OCR". The node tree still works.
            ocrError = "此裝置不支援 OCR（需要 Android 11 以上）"
        }
        if (bitmap != null) {
            val started = SystemClock.elapsedRealtime()
            try {
                ocrBoxes = withContext(Dispatchers.Default) { recognize(bitmap) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                ocrError = "mlkit: ${t::class.java.simpleName}: ${t.message}"
            }
            ocrMillis = SystemClock.elapsedRealtime() - started
        }

        val metrics = service.resources.displayMetrics
        val width = bitmap?.width ?: metrics.widthPixels
        val height = bitmap?.height ?: metrics.heightPixels
        val packageName = foregroundPackage
            ?: runCatching { service.rootInActiveWindow?.packageName?.toString() }.getOrNull()

        val snapshot = Snapshot(
            width = width,
            height = height,
            capturedAt = System.currentTimeMillis(),
            foregroundPackage = packageName,
            foregroundActivity = foregroundActivity,
            ocrSupported = isOcrSupported(),
            boxes = nodes + ocrBoxes,
            ocrMillis = ocrMillis,
            ocrError = ocrError
        )
        return ScreenReading(snapshot, bitmap)
    }

    /** [readDetailed] and frees the screenshot; use this on the production path. */
    suspend fun read(
        foregroundPackage: String? = null,
        foregroundActivity: String? = null,
        includeOcr: Boolean = true
    ): Snapshot {
        val reading = readDetailed(foregroundPackage, foregroundActivity, includeOcr)
        reading.bitmap?.recycle()
        return reading.snapshot
    }

    /**
     * A reading plus the corner template matches, for the ad handler.
     *
     * [backArrowRegion] is evaluated on the snapshot and, when it returns a box,
     * the install-card "←" template is searched there. The caller must close the
     * result; that recycles the screenshot.
     */
    suspend fun analyze(
        foregroundPackage: String? = null,
        foregroundActivity: String? = null,
        backArrowRegion: ((Snapshot) -> Box?)? = null
    ): ScreenAnalysis {
        val reading = readDetailed(foregroundPackage, foregroundActivity)
        val bitmap = reading.bitmap ?: return ScreenAnalysis(reading.snapshot, null, emptyList(), null)
        try {
            val corners = cornerDetector.find(bitmap)
            val region = backArrowRegion?.invoke(reading.snapshot)
            val back = region?.let { cornerDetector.findBackArrow(bitmap, it) }
            return ScreenAnalysis(reading.snapshot, bitmap, corners, back)
        } catch (t: Throwable) {
            bitmap.recycle()
            throw t
        }
    }

    /**
     * Takes one screenshot. Every failure path returns a capture with a null
     * bitmap and a readable [ScreenCapture.error] instead of throwing: an ad
     * screen is exactly where the system is allowed to say "not now" (rate
     * limit) and the collector must fall back to the node tree, not crash.
     */
    suspend fun captureScreen(): ScreenCapture {
        if (!isOcrSupported()) {
            return ScreenCapture(null, 0L, "takeScreenshot needs Android 11 (API 30)")
        }
        return withContext(Dispatchers.Main) {
            takeScreenshotCompat()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshotCompat(): ScreenCapture {
        val started = SystemClock.elapsedRealtime()
        screenshotPoolCreated = true
        val resumed = AtomicBoolean(false)
        return suspendCancellableCoroutine { continuation ->
            fun finish(capture: ScreenCapture) {
                if (resumed.compareAndSet(false, true)) continuation.resume(capture)
            }
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotPool,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val bitmap = try {
                                Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                                    ?.copy(Bitmap.Config.ARGB_8888, false)
                            } catch (t: Throwable) {
                                null
                            } finally {
                                runCatching { result.hardwareBuffer.close() }
                            }
                            finish(
                                ScreenCapture(
                                    bitmap = bitmap,
                                    elapsedMillis = SystemClock.elapsedRealtime() - started,
                                    error = if (bitmap == null) "screenshot could not be copied to a bitmap" else null
                                )
                            )
                        }

                        override fun onFailure(errorCode: Int) {
                            finish(
                                ScreenCapture(
                                    bitmap = null,
                                    elapsedMillis = SystemClock.elapsedRealtime() - started,
                                    error = describeScreenshotError(errorCode)
                                )
                            )
                        }
                    }
                )
            } catch (t: Throwable) {
                finish(
                    ScreenCapture(
                        bitmap = null,
                        elapsedMillis = SystemClock.elapsedRealtime() - started,
                        error = "takeScreenshot failed: ${t::class.java.simpleName}: ${t.message}"
                    )
                )
            }
        }
    }

    private fun describeScreenshotError(errorCode: Int): String = when (errorCode) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT (system rate limit)"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            "ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY"
        else -> "ERROR_TAKE_SCREENSHOT code=$errorCode"
    }

    private suspend fun recognize(bitmap: Bitmap): List<TextBox> {
        recognizerCreated = true
        val image = InputImage.fromBitmap(bitmap, 0)
        val text = suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { error -> continuation.resumeWith(Result.failure(error)) }
        }
        return text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val rect = line.boundingBox ?: return@mapNotNull null
                val value = line.text?.trim().orEmpty()
                if (value.isEmpty()) return@mapNotNull null
                TextBox(value, Box(rect.left, rect.top, rect.right, rect.bottom), TextSource.OCR)
            }
        }
    }

    /** Walks the active window and turns visible labelled nodes into boxes. */
    private fun collectNodeBoxes(): List<TextBox> {
        val root = runCatching { service.rootInActiveWindow }.getOrNull() ?: return emptyList()
        val metrics = service.resources.displayMetrics
        val screenArea = metrics.widthPixels.toLong() * metrics.heightPixels
        val result = ArrayList<TextBox>(64)
        val seen = HashSet<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        val rect = Rect()

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            try {
                val visible = node.isVisibleToUser
                if (visible) {
                    val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                        ?: node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    if (!text.isNullOrEmpty()) {
                        node.getBoundsInScreen(rect)
                        val box = Box(rect.left, rect.top, rect.right, rect.bottom)
                        val area = box.area.toLong()
                        // Zero-size nodes are placeholders; near-full-screen
                        // nodes are containers whose text is already covered by
                        // their children, and their centre is not a tap target.
                        val usable = box.width > 0 && box.height > 0 &&
                            area <= screenArea * MAX_NODE_AREA_RATIO
                        if (usable && seen.add("$text|$box")) {
                            result.add(TextBox(text, box, TextSource.NODE))
                        }
                    }
                }
                for (index in 0 until node.childCount) {
                    runCatching { node.getChild(index) }.getOrNull()?.let { queue.addLast(it) }
                }
            } catch (t: Throwable) {
                // A window can disappear mid-walk; keep whatever was collected.
                continue
            }
        }
        return result
    }

    fun close() {
        if (recognizerCreated) runCatching { recognizer.close() }
        recognizerCreated = false
        if (screenshotPoolCreated) runCatching { screenshotPool.shutdownNow() }
        screenshotPoolCreated = false
    }

    companion object {
        /** Stops a hostile or enormous tree from taking seconds. */
        const val MAX_NODES = 1_500

        /** Containers covering most of the window are not text boxes. */
        const val MAX_NODE_AREA_RATIO = 0.9
    }
}
