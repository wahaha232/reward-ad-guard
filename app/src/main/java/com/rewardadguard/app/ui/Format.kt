package com.rewardadguard.app.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Small, allocation-light formatting helpers shared by the Compose screens. */
object Format {

    // The formatters are compiled once (they are not thread safe, hence the
    // @Synchronized call sites) but they must NOT capture the default time zone:
    // a long-lived process can see the zone change (manual change, NITZ update,
    // travel) and an already compiled SimpleDateFormat would keep formatting in
    // the stale zone. Re-reading the zone per call keeps the output truthful.
    private fun timeFormatter() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun dateTimeFormatter() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun dayFormatter() = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    @Synchronized
    fun time(millis: Long): String = timeFormatter().format(Date(millis))

    @Synchronized
    fun dateTime(millis: Long): String = dateTimeFormatter().format(Date(millis))

    @Synchronized
    fun date(millis: Long): String = dayFormatter().format(Date(millis))

    /** Compact "3m 20s" / "1h 05m" duration, used for session length and uptime. */
    fun duration(millis: Long): String {
        if (millis <= 0L) return "0s"
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return when {
            hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
            else -> "${seconds}s"
        }
    }

    /** Shortens a package id for compact list rows. */
    fun shortPackage(packageName: String?, max: Int = 34): String {
        if (packageName.isNullOrBlank()) return "-"
        return if (packageName.length <= max) packageName else "${packageName.take(max - 1)}…"
    }

    /** Last 6 characters of a session id (or "-" for the no-session id). */
    fun shortSession(sessionId: String?): String {
        if (sessionId.isNullOrBlank()) return "-"
        return if (sessionId.length <= 6) sessionId else "…${sessionId.takeLast(6)}"
    }

    /**
     * `BLOCK_IMMEDIATELY` -> "Block immediately".
     * Enum constants are the storage format, never the UI string.
     *
     * NOTE: the UI no longer calls this - every user-visible enum label is
     * resolved through `ui/Strings.kt` so it can be translated. It is kept as a
     * tested utility for diagnostics and raw enum dumps; do not use it for
     * anything that ends up on screen.
     */
    fun humanize(enumName: String): String = enumName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.titlecase(Locale.US) }
        }
}
