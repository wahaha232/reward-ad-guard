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
 * Records and screenshots are kept seven days (five until 2026-11-23), then
 * deleted, and all of them together never take more than [MAX_BYTES]: past
 * that the oldest go first and the recent ones stay (asked by the user
 * 2026-11-23). Applies to every run directory, the debug snapshots and the
 * zips made for sharing or Telegram.
 */
object RunRetention {

    const val KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** All records together; beyond this the oldest entries are deleted. */
    const val MAX_BYTES = 100L * 1024 * 1024

    fun isExpired(lastModified: Long, now: Long, keepMillis: Long = KEEP_MILLIS): Boolean =
        lastModified > 0L && now - lastModified > keepMillis

    /** One deletable entry: a run directory, a snapshot or a zip. */
    data class Entry(val file: File, val newest: Long, val bytes: Long, val inUse: Boolean)

    /**
     * Which entries to delete so the rest fit in [maxBytes]: oldest first, never
     * one that is [Entry.inUse] (a run still being written).
     */
    fun overSize(entries: List<Entry>, maxBytes: Long = MAX_BYTES): List<Entry> {
        var total = entries.sumOf { it.bytes }
        if (total <= maxBytes) return emptyList()
        val victims = mutableListOf<Entry>()
        for (entry in entries.filterNot { it.inUse }.sortedBy { it.newest }) {
            if (total <= maxBytes) break
            victims += entry
            total -= entry.bytes
        }
        return victims
    }

    /**
     * Deletes everything older than [KEEP_MILLIS], then the oldest entries
     * until all fit in [MAX_BYTES]; returns how many entries went.
     */
    fun prune(context: Context, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val runsRoot = File(context.filesDir, "collector_runs")
        val roots = listOf(
            runsRoot,
            File(context.filesDir, "collector-snapshots"),
            File(context.cacheDir, "exports"),
            File(context.cacheDir, TelegramReporter.CACHE_DIR)
        )
        val kept = mutableListOf<Entry>()
        for (root in roots) {
            val entries = root.listFiles() ?: continue
            for (entry in entries) {
                val newest = newest(entry)
                if (isExpired(newest, now)) {
                    if (runCatching { entry.deleteRecursively() }.getOrDefault(false)) removed++
                    continue
                }
                // A run without summary.json is still being written (the next
                // app of a sequence starts while this prune runs).
                val inUse = root == runsRoot && entry.isDirectory && !File(entry, "summary.json").exists()
                kept += Entry(entry, newest, bytes(entry), inUse)
            }
        }
        for (victim in overSize(kept)) {
            if (runCatching { victim.file.deleteRecursively() }.getOrDefault(false)) removed++
        }
        return removed
    }

    private fun bytes(entry: File): Long =
        if (entry.isDirectory) entry.walkTopDown().filter { it.isFile }.sumOf { it.length() } else entry.length()

    /** A run directory counts from its newest file, not from when it was created. */
    private fun newest(entry: File): Long =
        if (entry.isDirectory) {
            entry.walkTopDown().filter { it.isFile }.maxOfOrNull { it.lastModified() } ?: entry.lastModified()
        } else {
            entry.lastModified()
        }
}

/**
 * Sends finished runs to Telegram and pins the message: at most once every
 * [SEND_INTERVAL_MILLIS], all runs since the last send in one zip.
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

    /** At most this many runs in one zip; older unsent ones beyond it are skipped. */
    const val MAX_PENDING = 20

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
     * A run that was not sent yet is still sent for this long. Without it, the
     * dozens of runs recorded before the user turned Telegram on would all be
     * sent (2026-11-13). Longer than [SEND_INTERVAL_MILLIS], so every run made
     * between two sends goes in the next one.
     */
    const val RETRY_WINDOW_MILLIS = 24L * 60 * 60 * 1000

    /**
     * At most one Telegram message this often (2026-11-23, the user asked for
     * fewer): the runs finished since the last one go together in one zip.
     */
    const val SEND_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

    /** Screenshots per run when several runs share one zip. */
    const val MIN_SHOTS_PER_RUN = 4

    private const val KEY_LAST_SENT = "last_sent_at"

    /** True when nothing was sent yet, or the last send is [SEND_INTERVAL_MILLIS] old. */
    fun isSendDue(lastSentAt: Long, now: Long, intervalMillis: Long = SEND_INTERVAL_MILLIS): Boolean =
        lastSentAt <= 0L || lastSentAt > now || now - lastSentAt >= intervalMillis

    /** Screenshots per run so a bundle of [runs] stays about [MAX_SHOTS] per run at most. */
    fun shotsPerRun(runs: Int): Int =
        if (runs <= 1) MAX_SHOTS else maxOf(MIN_SHOTS_PER_RUN, MAX_SHOTS * 2 / runs)

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
        val prefs = context.getSharedPreferences(TELEGRAM_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!isSendDue(prefs.getLong(KEY_LAST_SENT, 0L), now)) return
        val runs = pendingRuns(File(context.filesDir, "collector_runs"), now = now)
        if (runs.isEmpty()) return
        val zip = zipRuns(context, runs) ?: return
        val messageId = sendDocument(config, zip, caption(runs))
        runCatching { zip.delete() }
        if (messageId == null) return // network or token problem: retry after the next run
        for (run in runs) runCatching { File(run, SENT_MARK).writeText(messageId.toString()) }
        prefs.edit().putLong(KEY_LAST_SENT, now).apply()
        pin(config, messageId)
    }

    private const val TELEGRAM_PREFS = "telegram_report"

    /** The caption of one zip: one run as before, several as one line each. */
    fun caption(runs: List<File>): String {
        if (runs.size == 1) return caption(runs.first())
        val header = "獎勵廣告守衛 自動收取紀錄｜${runs.size} 輪｜${runs.first().name}～${runs.last().name}"
        val lines = runs.map { run ->
            val f = summaryFields(run)
            "${f("name")} ${f("endReason")} 獎勵${f("rewards").ifEmpty { "0" }}" +
                " 手動${f("manual").ifEmpty { "0" }}"
        }
        // Telegram captions stop at 1024 characters.
        val text = (listOf(header) + lines).joinToString("\n")
        return if (text.length <= 1000) text else text.take(990) + "\n…"
    }

    private fun summaryFields(run: File): (String) -> String {
        val summary = runCatching { File(run, "summary.json").readText() }.getOrDefault("")
        return { name ->
            Regex("\"$name\"\\s*:\\s*\"?([^\",\\n}]*)").find(summary)?.groupValues?.get(1)?.trim().orEmpty()
        }
    }

    /** One line the user can read in the chat list. */
    fun caption(run: File): String {
        val field = summaryFields(run)
        val manual = field("manual").ifEmpty { "0" }
        return "獎勵廣告守衛 自動收取紀錄｜${field("name")}｜結束：${field("endReason")}｜" +
            "獎勵 ${field("rewards").ifEmpty { "0" }}｜錯誤 ${field("errors").ifEmpty { "0" }}｜" +
            "需手動 $manual｜${run.name}｜commit ${field("commit")}"
    }

    /** console.log, events.jsonl and summary.json whole; screenshots shrunk to JPEG. */
    private fun zipRuns(context: Context, runs: List<File>): File? {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val name = if (runs.size == 1) runs.first().name else "${runs.first().name}_${runs.size}runs"
        val zip = File(dir, "$name.zip")
        val maxShots = shotsPerRun(runs.size)
        return runCatching {
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                for (run in runs) {
                    for (file in listOf("summary.json", "console.log", "events.jsonl")) {
                        val src = File(run, file)
                        if (!src.isFile) continue
                        out.putNextEntry(ZipEntry("${run.name}/$file"))
                        src.inputStream().buffered().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                    for (shot in pickShots(File(run, "shots").listFiles()?.toList().orEmpty(), maxShots)) {
                        val jpeg = shrink(shot) ?: continue
                        out.putNextEntry(ZipEntry("${run.name}/shots/${shot.nameWithoutExtension}.jpg"))
                        out.write(jpeg)
                        out.closeEntry()
                    }
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
