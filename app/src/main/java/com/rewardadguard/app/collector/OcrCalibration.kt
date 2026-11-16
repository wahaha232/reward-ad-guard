package com.rewardadguard.app.collector

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * OCR calibration mode (2026-11-16). ML Kit misreads Traditional Chinese now
 * and then (禮物卡 -> 搜物卡 / 澧物卡) where the PC script's Windows OCR
 * (zh-Hant-TW) reads it right. With this switched on, every OCR'd screenshot
 * is kept with what ML Kit read, so `TOOLS/ocr_calibrate.py` on the PC can
 * read the same images with Windows OCR and list ML Kit's look-alike
 * mistakes for [OcrCorrections].
 *
 * Files: `files/ocr_calibration/<yyyyMMdd>/<HHmmss_SSS>_<package>.jpg` and a
 * `.tsv` beside it, one line per text box: left, top, right, bottom, text.
 * At most [DAILY_LIMIT] a day, one per [MIN_GAP_MILLIS], kept [KEEP_DAYS] days.
 */
object OcrCalibration {

    private const val PREFS = "collector_debug"
    private const val KEY_ENABLED = "ocr_calibration"
    const val DIR = "ocr_calibration"
    const val DAILY_LIMIT = 150
    private const val MIN_GAP_MILLIS = 3_000L
    private const val KEEP_DAYS = 3
    private const val JPEG_QUALITY = 85

    @Volatile
    private var enabledCache: Boolean? = null
    private var lastSavedAt = 0L

    fun isEnabled(context: Context): Boolean =
        enabledCache ?: context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false).also { enabledCache = it }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        enabledCache = enabled
    }

    /** Images kept today (for the UI). */
    fun todayCount(context: Context): Int =
        dayDir(context, Date()).listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0

    /** Keeps [bitmap] and ML Kit's [boxes]; cheap no-op when switched off. */
    @Synchronized
    fun maybeSave(context: Context, bitmap: Bitmap, boxes: List<TextBox>, packageName: String?) {
        if (!isEnabled(context) || boxes.isEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (lastSavedAt != 0L && now - lastSavedAt < MIN_GAP_MILLIS) return
        val date = Date()
        val dir = dayDir(context, date)
        if (!dir.isDirectory && !dir.mkdirs()) return
        if ((dir.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0) >= DAILY_LIMIT) return
        lastSavedAt = now
        val stem = SimpleDateFormat("HHmmss_SSS", Locale.US).format(date) + "_" +
            (packageName ?: "unknown").replace(Regex("[^A-Za-z0-9._]"), "_")
        runCatching {
            File(dir, "$stem.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            File(dir, "$stem.tsv").writeText(
                boxes.joinToString("\n") { b ->
                    "${b.bounds.left}\t${b.bounds.top}\t${b.bounds.right}\t${b.bounds.bottom}\t" +
                        b.text.replace('\t', ' ').replace('\n', ' ')
                },
                Charsets.UTF_8
            )
        }
        prune(context)
    }

    private fun dayDir(context: Context, date: Date): File =
        File(File(context.filesDir, DIR), SimpleDateFormat("yyyyMMdd", Locale.US).format(date))

    private fun prune(context: Context) {
        val root = File(context.filesDir, DIR)
        val days = root.listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: return
        days.drop(KEEP_DAYS).forEach { it.deleteRecursively() }
    }
}
