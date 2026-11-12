package com.rewardadguard.app.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Where a run's record may go besides the phone: the user's own Telegram bot
 * (2026-11-13, requested by the user so failures can be read without a cable).
 *
 * The token and chat id are typed by the user on the phone and kept only in
 * the app's private preferences - never in the repo, never in a log line.
 */
object TelegramReportSettings {

    data class Config(val enabled: Boolean, val token: String, val chatId: String) {
        val usable: Boolean get() = enabled && token.isNotBlank() && chatId.isNotBlank()
    }

    private const val PREFS = "telegram_report"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TOKEN = "token"
    private const val KEY_CHAT = "chat_id"

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            token = prefs.getString(KEY_TOKEN, "").orEmpty(),
            chatId = prefs.getString(KEY_CHAT, "").orEmpty()
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_TOKEN, config.token.trim())
            .putString(KEY_CHAT, config.chatId.trim())
            .apply()
    }
}

/**
 * Records and screenshots are kept five days, then deleted (asked by the user
 * 2026-11-13). Applies to every run directory, the debug snapshots and the
 * zips made for sharing or Telegram.
 */
object RunRetention {

    const val KEEP_MILLIS = 5L * 24 * 60 * 60 * 1000

    fun isExpired(lastModified: Long, now: Long, keepMillis: Long = KEEP_MILLIS): Boolean =
        lastModified > 0L && now - lastModified > keepMillis

    /** Deletes everything older than [KEEP_MILLIS]; returns how many entries went. */
    fun prune(context: Context, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val roots = listOf(
            File(context.filesDir, "collector_runs"),
            File(context.filesDir, "collector-snapshots"),
            File(context.cacheDir, "exports"),
            File(context.cacheDir, TelegramReporter.CACHE_DIR)
        )
        for (root in roots) {
            val entries = root.listFiles() ?: continue
            for (entry in entries) {
                if (!isExpired(newest(entry), now)) continue
                if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) removed++
            }
        }
        return removed
    }

    /** A run directory counts from its newest file, not from when it was created. */
    private fun newest(entry: File): Long =
        if (entry.isDirectory) {
            entry.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: entry.lastModified()
        } else {
            entry.lastModified()
        }
}

/**
 * Sends each finished run to Telegram as one zip and pins it.
 *
 * A bot never receives its own messages, so Claude cannot read what the app
 * sent through getUpdates; the pin makes the latest record reachable through
 * getChat. Sending happens after the run, off the run's thread, and a failure
 * (no network, bad token) leaves the run unsent so the next run retries it.
 * Collection itself never waits for Telegram.
 */
object TelegramReporter {

    const val CACHE_DIR = "telegram"
    const val SENT_MARK = ".sent_to_telegram"

    /** Older unsent runs beyond this are not sent (they are pruned within five days anyway). */
    const val MAX_PENDING = 5

    /** Screenshots per zip; the first and last ones tell most of a run. */
    const val MAX_SHOTS = 24
    private const val SHOT_WIDTH = 540
    private const val SHOT_QUALITY = 60

    /** Telegram's bot upload limit is 50 MB; stay far below it. */
    private const val MAX_ZIP_BYTES = 45L * 1024 * 1024

    private const val TAG = "TelegramReporter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    /** Called when a run ended: prune old records, then send what is unsent. */
    fun runFinished(context: Context) {
        val app = context.applicationContext
        scope.launch {
            lock.withLock {
                runCatching { RunRetention.prune(app) }
                runCatching { sendPending(app) }.onFailure { Log.w(TAG, "send failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /**
     * A run that could not be sent is retried only for this long. Without it,
     * the dozens of runs recorded before the user turned Telegram on would be
     * sent five at a time after every later run (2026-11-13).
     */
    const val RETRY_WINDOW_MILLIS = 6L * 60 * 60 * 1000

    /** Runs that finished (have a summary) recently and were not sent yet, oldest first. */
    fun pendingRuns(
        runsRoot: File,
        limit: Int = MAX_PENDING,
        now: Long = System.currentTimeMillis()
    ): List<File> =
        (runsRoot.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.startsWith("run_") }
            .filter { !File(it, SENT_MARK).exists() }
            .filter {
                val summary = File(it, "summary.json")
                summary.isFile && now - summary.lastModified() <= RETRY_WINDOW_MILLIS
            }
            .sortedBy { it.name }
            .takeLast(limit)

    private fun sendPending(context: Context) {
        val config = TelegramReportSettings.load(context)
        if (!config.usable) return
        for (run in pendingRuns(File(context.filesDir, "collector_runs"))) {
            val zip = zipRun(context, run) ?: continue
            val messageId = sendDocument(config, zip, caption(run))
            runCatching { zip.delete() }
            if (messageId == null) return // network or token problem: retry after the next run
            runCatching { File(run, SENT_MARK).writeText(messageId.toString()) }
            pin(config, messageId)
        }
    }

    /** One line the user can read in the chat list. */
    fun caption(run: File): String {
        val summary = runCatching { File(run, "summary.json").readText() }.getOrDefault("")
        fun field(name: String): String =
            Regex("\"$name\"\\s*:\\s*\"?([^\",\\n}]*)").find(summary)?.groupValues?.get(1)?.trim().orEmpty()
        val manual = field("manual").ifEmpty { "0" }
        return "獎勵廣告守衛 自動收取紀錄｜${field("name")}｜結束：${field("endReason")}｜" +
            "獎勵 ${field("rewards").ifEmpty { "0" }}｜錯誤 ${field("errors").ifEmpty { "0" }}｜" +
            "需手動 $manual｜${run.name}｜commit ${field("commit")}"
    }

    /** console.log, events.jsonl and summary.json whole; screenshots shrunk to JPEG. */
    private fun zipRun(context: Context, run: File): File? {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val zip = File(dir, "${run.name}.zip")
        return runCatching {
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                for (name in listOf("summary.json", "console.log", "events.jsonl")) {
                    val file = File(run, name)
                    if (!file.isFile) continue
                    out.putNextEntry(ZipEntry("${run.name}/$name"))
                    file.inputStream().buffered().use { it.copyTo(out) }
                    out.closeEntry()
                }
                for (shot in pickShots(File(run, "shots").listFiles()?.toList().orEmpty())) {
                    val jpeg = shrink(shot) ?: continue
                    out.putNextEntry(ZipEntry("${run.name}/shots/${shot.nameWithoutExtension}.jpg"))
                    out.write(jpeg)
                    out.closeEntry()
                }
            }
            if (zip.length() > MAX_ZIP_BYTES) null else zip
        }.getOrNull()
    }

    /** The first and last screenshots when there are too many. */
    fun pickShots(shots: List<File>, max: Int = MAX_SHOTS): List<File> {
        val sorted = shots.filter { it.isFile }.sortedBy { it.name }
        if (sorted.size <= max) return sorted
        val half = max / 2
        return sorted.take(half) + sorted.takeLast(max - half)
    }

    private fun shrink(file: File): ByteArray? {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return try {
            val height = (bitmap.height * SHOT_WIDTH.toFloat() / bitmap.width).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(bitmap, SHOT_WIDTH, height, true)
            ByteArrayOutputStream().use { bytes ->
                scaled.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, bytes)
                if (scaled !== bitmap) scaled.recycle()
                bytes.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** A short message to check the settings; returns null on success, else why. */
    fun sendTest(config: TelegramReportSettings.Config): String? {
        if (config.token.isBlank() || config.chatId.isBlank()) return "請填 Bot Token 與 Chat ID"
        val body = "chat_id=" + enc(config.chatId) + "&text=" + enc("獎勵廣告守衛：Telegram 紀錄傳送測試成功")
        val (code, response) = post(config, "sendMessage", "application/x-www-form-urlencoded", body.toByteArray())
        return if (code == 200 && response.contains("\"ok\":true")) null else describe(code, response)
    }

    private fun sendDocument(config: TelegramReportSettings.Config, file: File, caption: String): Long? {
        val boundary = "----rag${System.currentTimeMillis()}"
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { out ->
            fun part(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.write(value.toByteArray(Charsets.UTF_8))
                out.writeBytes("\r\n")
            }
            part("chat_id", config.chatId)
            part("caption", caption.take(1000))
            out.writeBytes(
                "--$boundary\r\nContent-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"\r\n" +
                    "Content-Type: application/zip\r\n\r\n"
            )
            file.inputStream().use { it.copyTo(out) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val (code, response) = post(config, "sendDocument", "multipart/form-data; boundary=$boundary", payload.toByteArray())
        if (code != 200 || !response.contains("\"ok\":true")) {
            Log.w(TAG, "sendDocument failed: ${describe(code, response)}")
            return null
        }
        return Regex("\"message_id\"\\s*:\\s*(\\d+)").find(response)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun pin(config: TelegramReportSettings.Config, messageId: Long) {
        val body = "chat_id=" + enc(config.chatId) + "&message_id=$messageId&disable_notification=true"
        val (code, response) = post(config, "pinChatMessage", "application/x-www-form-urlencoded", body.toByteArray())
        if (code != 200) Log.w(TAG, "pin failed: ${describe(code, response)}")
    }

    private fun post(
        config: TelegramReportSettings.Config,
        method: String,
        contentType: String,
        body: ByteArray
    ): Pair<Int, String> = runCatching {
        val connection = URL("https://api.telegram.org/bot${config.token}/$method").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            code to (stream?.bufferedReader()?.use { it.readText() }.orEmpty())
        } finally {
            connection.disconnect()
        }
    }.getOrElse { -1 to (it.javaClass.simpleName) }

    /** Never includes the token: only the HTTP code and Telegram's description. */
    private fun describe(code: Int, response: String): String {
        val description = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"").find(response)?.groupValues?.get(1)
        return if (code < 0) "連線失敗（$response）" else "HTTP $code ${description.orEmpty()}".trim()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
