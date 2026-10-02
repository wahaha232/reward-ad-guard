package com.rewardadguard.app.manager

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.rewardadguard.app.data.EventRecord
import com.rewardadguard.app.data.SessionRecord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log export to TXT / CSV / JSON (spec section 35).
 *
 * Files are written into `cacheDir/exports` and shared through FileProvider
 * content URIs. No INTERNET permission is required: the file is only handed to
 * a locally installed viewer / share target.
 */
class LogExporter(private val context: Context) {

    enum class Format(val extension: String, val mimeType: String) {
        TXT("txt", "text/plain"),
        CSV("csv", "text/csv"),
        JSON("json", "application/json")
    }

    data class ExportResult(
        val file: File,
        val uri: Uri,
        val shareIntent: Intent
    )

    fun export(
        events: List<EventRecord>,
        sessions: List<SessionRecord>,
        format: Format
    ): ExportResult {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { if (!exists()) mkdirs() }
        val stamp = FILE_STAMP.format(Date())
        val file = File(dir, "reward_ad_guard_${format.name.lowercase()}_$stamp.${format.extension}")
        file.writeText(render(events, sessions, format))
        return ExportResult(file, uriFor(file), shareIntentFor(uriFor(file), format))
    }

    private fun uriFor(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)

    private fun shareIntentFor(uri: Uri, format: Format): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Reward Ad Guard log (${format.name})")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun render(
        events: List<EventRecord>,
        sessions: List<SessionRecord>,
        format: Format
    ): String = when (format) {
        Format.TXT -> renderTxt(events, sessions)
        Format.CSV -> renderCsv(events)
        Format.JSON -> renderJson(events, sessions)
    }

    private fun renderTxt(events: List<EventRecord>, sessions: List<SessionRecord>): String = buildString {
        appendLine("# Reward Ad Guard event log")
        appendLine("# exportedAt=${isoNow()}")
        appendLine("# events=${events.size} sessions=${sessions.size}")
        appendLine("# time format: yyyy-MM-dd HH:mm:ss.SSS")
        appendLine()
        appendLine("## SESSIONS")
        sessions.sortedBy { it.startedAt }.forEach { s ->
            appendLine(
                "sessionId=${s.sessionId} source=${s.sourcePackage} " +
                    "start=${stamp(s.startedAt)} end=${s.endedAt?.let { stamp(it) } ?: "-"} " +
                    "redirects=${s.redirectCount} blocked=${s.blockCount} " +
                    "returns=${s.returnSuccessCount}/${s.returnFailedCount} " +
                    "closeDetect=${s.closeDetectCount} risk=${s.maxRedirectRisk}"
            )
        }
        appendLine()
        appendLine("## EVENTS")
        events.sortedBy { it.timestamp }.forEach { e -> appendLine(formatLine(e)) }
    }

    private fun renderCsv(events: List<EventRecord>): String = buildString {
        appendLine(CSV_HEADER)
        events.sortedBy { it.timestamp }.forEach { e ->
            appendLine(
                listOf(
                    stamp(e.timestamp),
                    e.sessionId,
                    e.eventType,
                    e.category,
                    e.sourcePackage.orEmpty(),
                    e.destinationPackage.orEmpty(),
                    e.detectionMethod.orEmpty(),
                    e.action.orEmpty(),
                    e.result.orEmpty(),
                    e.message.orEmpty(),
                    e.error.orEmpty()
                ).joinToString(",") { escapeCsv(it) }
            )
        }
    }

    private fun renderJson(events: List<EventRecord>, sessions: List<SessionRecord>): String {
        val root = JSONObject()
        root.put("app", "Reward Ad Guard")
        root.put("exportedAt", isoNow())
        root.put("eventCount", events.size)
        root.put("sessionCount", sessions.size)

        val eventArray = JSONArray()
        events.sortedBy { it.timestamp }.forEach { e ->
            eventArray.put(
                JSONObject().apply {
                    put("timestamp", e.timestamp)
                    put("timestampIso", iso(e.timestamp))
                    put("sessionId", e.sessionId)
                    put("eventType", e.eventType)
                    put("category", e.category)
                    put("sourcePackage", e.sourcePackage ?: JSONObject.NULL)
                    put("destinationPackage", e.destinationPackage ?: JSONObject.NULL)
                    put("detectionMethod", e.detectionMethod ?: JSONObject.NULL)
                    put("action", e.action ?: JSONObject.NULL)
                    put("result", e.result ?: JSONObject.NULL)
                    put("message", e.message ?: JSONObject.NULL)
                    put("error", e.error ?: JSONObject.NULL)
                }
            )
        }
        root.put("events", eventArray)

        val sessionArray = JSONArray()
        sessions.sortedBy { it.startedAt }.forEach { s ->
            sessionArray.put(
                JSONObject().apply {
                    put("sessionId", s.sessionId)
                    put("sourcePackage", s.sourcePackage)
                    put("startedAt", s.startedAt)
                    put("endedAt", s.endedAt ?: JSONObject.NULL)
                    put("endReason", s.endReason ?: JSONObject.NULL)
                    put("redirectCount", s.redirectCount)
                    put("blockCount", s.blockCount)
                    put("returnSuccessCount", s.returnSuccessCount)
                    put("returnFailedCount", s.returnFailedCount)
                    put("closeDetectCount", s.closeDetectCount)
                    put("possibleAdSessions", s.possibleAdSessions)
                    put("errorCount", s.errorCount)
                    put("maxRedirectRisk", s.maxRedirectRisk)
                }
            )
        }
        root.put("sessions", sessionArray)
        return root.toString(2)
    }

    private fun formatLine(e: EventRecord): String = buildString {
        append("[${e.eventType}] ")
        append("${stamp(e.timestamp)} ")
        append("session=${e.sessionId} ")
        e.sourcePackage?.let { append("source=$it ") }
        e.destinationPackage?.let { append("destination=$it ") }
        e.detectionMethod?.let { append("method=$it ") }
        e.action?.let { append("action=$it ") }
        e.result?.let { append("result=$it ") }
        e.message?.let { append("message=$it ") }
        e.error?.let { append("error=$it ") }
    }.trimEnd()

    private fun escapeCsv(value: String): String {
        if (value.isEmpty()) return ""
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun stamp(millis: Long): String = LOG_STAMP().format(Date(millis))

    private fun iso(millis: Long): String = ISO_STAMP().format(Date(millis))

    private fun isoNow(): String = iso(System.currentTimeMillis())

    companion object {
        private const val TAG = "LogExporter"
        const val EXPORT_DIR = "exports"
        const val AUTHORITY_SUFFIX = ".fileprovider"

        private const val CSV_HEADER =
            "timestamp,sessionId,eventType,category,sourcePackage,destinationPackage," +
                "detectionMethod,action,result,message,error"

        // Fresh instances on purpose: an exported file must carry the time zone
        // that is actually in effect when the user taps Export. A cached
        // formatter would keep stamping the zone captured at class load time.
        private fun LOG_STAMP() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        private fun ISO_STAMP() = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
        private val FILE_STAMP = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }
}
