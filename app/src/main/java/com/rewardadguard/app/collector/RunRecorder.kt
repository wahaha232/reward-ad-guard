package com.rewardadguard.app.collector

import android.content.Context
import android.graphics.Bitmap
import com.rewardadguard.app.BuildConfig
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One collection run's on-phone record, in the layout the master prompt
 * requires: `collector_runs/run_YYYYMMDD_HHMMSS/{events.jsonl,summary.json,
 * console.log,shots/}`.
 *
 * It is written by the collector itself, not by adb, because the whole point of
 * this phase is a run that survives without a PC. The files are pulled with
 * `run-as` afterwards and uploaded to the private test-log repository.
 */
class RunRecorder(
    context: Context,
    private val profile: AppProfile,
    private val dryRun: Boolean,
    private val version: String = BuildConfig.VERSION_NAME
) : Closeable {

    private val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private val startedAt = System.currentTimeMillis()
    val runDir: File = File(context.filesDir, "collector_runs/run_$timestamp")
    private val shotsDir = File(runDir, "shots")
    private val eventsFile = File(runDir, "events.jsonl")
    private val consoleFile = File(runDir, "console.log")

    private val eventWriter: BufferedWriter
    private val consoleWriter: BufferedWriter
    private var shotCount = 0
    private var closed = false

    init {
        runDir.mkdirs()
        shotsDir.mkdirs()
        eventWriter = BufferedWriter(FileWriter(eventsFile, true))
        consoleWriter = BufferedWriter(FileWriter(consoleFile, true))
        console("[collector] ${profile.name} start dryRun=$dryRun dir=${runDir.absolutePath}")
    }

    @Synchronized
    fun console(line: String) {
        if (closed) return
        runCatching {
            consoleWriter.write("${clock()} $line")
            consoleWriter.newLine()
            consoleWriter.flush()
        }
    }

    @Synchronized
    fun event(action: String, message: String? = null, fields: Map<String, Any?> = emptyMap()) {
        if (closed) return
        val record = LinkedHashMap<String, Any?>()
        record["t"] = System.currentTimeMillis()
        record["action"] = action
        record["app"] = profile.key
        if (!message.isNullOrBlank()) record["message"] = message
        record.putAll(fields)
        runCatching {
            eventWriter.write(toJson(record))
            eventWriter.newLine()
            eventWriter.flush()
        }
    }

    /**
     * Saves a screenshot. The engine decides the policy (first/last success,
     * every failure); this method only writes.
     */
    @Synchronized
    fun shot(bitmap: Bitmap?, label: String): Boolean {
        if (closed || bitmap == null || bitmap.isRecycled) return false
        val safe = label.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
        val file = File(shotsDir, "%04d_%s.png".format(shotCount++, safe))
        val ok = runCatching {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }.getOrDefault(false)
        if (ok) event("shot", message = file.name)
        return ok
    }

    /** Writes `summary.json` and closes the writers. Safe to call once. */
    @Synchronized
    fun finish(endReason: String, stats: Map<String, Any?> = emptyMap()) {
        if (closed) return
        val endedAt = System.currentTimeMillis()
        val summary = LinkedHashMap<String, Any?>()
        summary["app"] = profile.key
        summary["name"] = profile.name
        summary["package"] = profile.packageName
        summary["dryRun"] = dryRun
        summary["startedAt"] = startedAt
        summary["endedAt"] = endedAt
        summary["durationMillis"] = endedAt - startedAt
        summary["endReason"] = endReason
        summary["shots"] = shotCount
        summary["version"] = version
        summary["commit"] = BuildConfig.GIT_HASH
        summary.putAll(stats)
        runCatching {
            File(runDir, "summary.json").writeText(toJson(summary, pretty = true), Charsets.UTF_8)
        }
        console("[collector] end reason=$endReason stats=${stats.entries.joinToString { "${it.key}=${it.value}" }}")
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { eventWriter.flush() }
        runCatching { consoleWriter.flush() }
        runCatching { eventWriter.close() }
        runCatching { consoleWriter.close() }
    }

    private fun clock(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    private fun toJson(map: Map<String, Any?>, pretty: Boolean = false): String {
        val indent = if (pretty) "  " else ""
        val separator = if (pretty) ",\n" else ","
        val body = map.entries.joinToString(separator) { (key, value) ->
            "$indent${quote(key)}:${if (pretty) " " else ""}${valueJson(value, pretty)}"
        }
        return if (pretty) "{\n$body\n}\n" else "{$body}"
    }

    private fun valueJson(value: Any?, pretty: Boolean): String = when (value) {
        null -> "null"
        // JSON has no NaN/Infinity; writing them made the whole line unparsable.
        is Double -> if (value.isFinite()) value.toString() else "null"
        is Float -> if (value.isFinite()) value.toString() else "null"
        is Boolean, is Int, is Long -> value.toString()
        is Map<*, *> -> toJson(value.entries.associate { it.key.toString() to it.value }, pretty)
        is Iterable<*> -> value.joinToString(",", "[", "]") { valueJson(it, pretty) }
        else -> quote(value.toString())
    }

    private fun quote(value: String): String {
        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else -> if (ch < ' ') builder.append("\\u%04x".format(ch.code)) else builder.append(ch)
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
