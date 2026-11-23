package com.rewardadguard.app.collector

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One daily collection time (asked by the user, 2026-11-15): at [hour]:[minute]
 * every day, run [appKeys] in order. Several entries may exist, each with its
 * own apps and its own on/off switch.
 */
data class ScheduleEntry(
    val id: Long,
    val hour: Int,
    val minute: Int,
    val appKeys: List<String>,
    val enabled: Boolean = true
) {
    val timeText: String get() = "%02d:%02d".format(hour, minute)
}

/** A schedule that came due and has not started yet (phone locked, or in use). */
data class PendingRun(
    val entryId: Long,
    val dueAt: Long,
    val appKeys: List<String>,
    /** A scan (count only) rather than a collection. */
    val scan: Boolean = false,
    /** Dropped this long after [dueAt] if it never got to start. */
    val graceMillis: Long = ScheduleMath.GRACE_MILLIS
)

/**
 * When to scan the apps for rewards still waiting (2026-11-15): every
 * [intervalHours] on the hour, or at fixed [times]; [MODE_OFF] scans never.
 */
data class ScanConfig(
    val mode: Int = MODE_OFF,
    val intervalHours: Int = 3,
    val appKeys: List<String> = emptyList(),
    val times: List<Pair<Int, Int>> = emptyList(),
    /** False until the user picks apps: an empty list then means "all". */
    val appsChosen: Boolean = false
) {
    /** The scan times as entries the schedule math understands. */
    fun entries(): List<ScheduleEntry> = when (mode) {
        MODE_INTERVAL -> (0 until 24 step intervalHours.coerceIn(1, 24)).map { h ->
            ScheduleEntry(SCAN_ID_BASE - h, h, 0, appKeys)
        }
        MODE_FIXED -> times.map { (h, m) -> ScheduleEntry(SCAN_ID_BASE - 100 - h * 60 - m, h, m, appKeys) }
        else -> emptyList()
    }

    /** An interval scan is replaced by the next one; a fixed one waits the usual time. */
    fun graceMillis(): Long =
        if (mode == MODE_INTERVAL) minOf(ScheduleMath.GRACE_MILLIS, intervalHours * 3_600_000L) else ScheduleMath.GRACE_MILLIS

    companion object {
        const val MODE_OFF = 0
        const val MODE_INTERVAL = 1
        const val MODE_FIXED = 2
        val INTERVALS = listOf(1, 2, 3, 4, 6, 8, 12)
        private const val SCAN_ID_BASE = -1_000L

        fun encode(c: ScanConfig): String =
            "${c.mode}|${c.intervalHours}|${c.appKeys.joinToString(",")}|" +
                c.times.joinToString(",") { "%02d:%02d".format(it.first, it.second) } +
                "|${if (c.appsChosen) 1 else 0}"

        fun decode(raw: String?): ScanConfig {
            val parts = raw?.split("|") ?: return ScanConfig()
            if (parts.size != 4 && parts.size != 5) return ScanConfig()
            val times = parts[3].split(",").mapNotNull { t ->
                val hm = t.trim().split(":")
                val h = hm.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 }
                val m = hm.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 }
                if (h == null || m == null) null else h to m
            }.distinct().sortedWith(compareBy({ it.first }, { it.second }))
            val appKeys = parts[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
            return ScanConfig(
                mode = parts[0].toIntOrNull()?.takeIf { it in 0..2 } ?: MODE_OFF,
                intervalHours = parts[1].toIntOrNull()?.takeIf { it in 1..24 } ?: 3,
                appKeys = appKeys,
                times = times,
                // Old four-part records had no flag: a non-empty list was a
                // choice, an empty one still meant the default apps.
                appsChosen = if (parts.size == 5) parts[4] == "1" else appKeys.isNotEmpty()
            )
        }
    }
}

/**
 * Pure rules of the schedule, kept apart from AlarmManager and the service so
 * every decision has a JVM test.
 *
 * The collector can only act on a lit, unlocked screen with the reward app in
 * front (its taps go to the top window and OCR reads what is shown), and an
 * accessibility service cannot unlock a phone with a PIN. So a due schedule
 * does not start by itself on a locked phone: it waits, as the user chose,
 * until the phone is unlocked and nobody has used it for [IDLE_MILLIS], and is
 * dropped for the day after [GRACE_MILLIS].
 */
object ScheduleMath {

    /** A due schedule still starts this long after its time; later it is skipped. */
    const val GRACE_MILLIS = 3L * 60 * 60 * 1000

    /** Quiet time (no taps, scrolls or app switches) before a due schedule starts. */
    const val IDLE_MILLIS = 3L * 60 * 1000

    /** The next time [entry] fires strictly after [now]. */
    fun nextOccurrence(entry: ScheduleEntry, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val nowAt = Instant.ofEpochMilli(now).atZone(zone)
        var at = ZonedDateTime.of(nowAt.toLocalDate(), LocalTime.of(entry.hour, entry.minute), zone)
        if (!at.toInstant().isAfter(nowAt.toInstant())) {
            at = ZonedDateTime.of(nowAt.toLocalDate().plusDays(1), LocalTime.of(entry.hour, entry.minute), zone)
        }
        return at.toInstant().toEpochMilli()
    }

    /** The enabled entry that fires first after [now], with its time; null when none. */
    fun nextDue(entries: List<ScheduleEntry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<ScheduleEntry, Long>? =
        entries.filter { it.enabled && it.appKeys.isNotEmpty() }
            .map { it to nextOccurrence(it, now, zone) }
            .minByOrNull { it.second }

    enum class Decision {
        /** Start the run now. */
        START,
        /** The phone is locked (or the screen is off): wait for the user to unlock. */
        WAIT_UNLOCK,
        /** Unlocked, but someone used the phone within [IDLE_MILLIS]. */
        WAIT_IDLE,
        /** Another collection is already running; start after it. */
        WAIT_BUSY,
        /** Past [GRACE_MILLIS]: skip it for today. */
        EXPIRED
    }

    /**
     * What a pending run does now.
     *
     * @param idleSince latest of: the last real tap/scroll/app switch, the
     *   moment the phone was unlocked, and the due time (a schedule never starts
     *   on activity older than itself).
     */
    fun decide(
        pending: PendingRun,
        now: Long,
        locked: Boolean,
        screenOn: Boolean,
        collectorRunning: Boolean,
        idleSince: Long
    ): Decision = when {
        now - pending.dueAt > pending.graceMillis -> Decision.EXPIRED
        collectorRunning -> Decision.WAIT_BUSY
        locked || !screenOn -> Decision.WAIT_UNLOCK
        now - maxOf(idleSince, pending.dueAt) < IDLE_MILLIS -> Decision.WAIT_IDLE
        else -> Decision.START
    }

    /** Milliseconds left in a [Decision.WAIT_IDLE]. */
    fun idleRemaining(pending: PendingRun, now: Long, idleSince: Long): Long =
        (IDLE_MILLIS - (now - maxOf(idleSince, pending.dueAt))).coerceAtLeast(0L)

    /** "BITWALK、HAHAGO" for the apps of an entry (unknown keys are skipped). */
    fun appsLabel(appKeys: List<String>): String {
        val names = appKeys.mapNotNull { CollectorProfiles.byKey(it)?.name }
        return if (names.isEmpty()) "（沒有 App）" else names.joinToString("、")
    }

    // ---- storage format: one entry per line, "id|hour|minute|enabled|key,key" ----
    // Plain text instead of org.json: org.json is a stub in the JVM unit tests.

    fun encode(entries: List<ScheduleEntry>): String = entries.joinToString("\n") {
        "${it.id}|${it.hour}|${it.minute}|${if (it.enabled) 1 else 0}|${it.appKeys.joinToString(",")}"
    }

    fun decode(raw: String?): List<ScheduleEntry> = raw.orEmpty().lines().mapNotNull { line ->
        val parts = line.trim().split("|")
        if (parts.size != 5) return@mapNotNull null
        val id = parts[0].toLongOrNull() ?: return@mapNotNull null
        val hour = parts[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return@mapNotNull null
        val minute = parts[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return@mapNotNull null
        val keys = parts[4].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        ScheduleEntry(id, hour, minute, keys, parts[3] == "1")
    }.let(::sorted)

    /** Entries in time-of-day order. */
    fun sorted(entries: List<ScheduleEntry>): List<ScheduleEntry> =
        entries.sortedWith(compareBy({ it.hour }, { it.minute }))

    fun encodePending(p: PendingRun): String =
        "${p.entryId}|${p.dueAt}|${p.appKeys.joinToString(",")}|${if (p.scan) 1 else 0}|${p.graceMillis}"

    fun decodePending(raw: String?): PendingRun? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 3 && parts.size != 5) return null
        val id = parts[0].toLongOrNull() ?: return null
        val due = parts[1].toLongOrNull() ?: return null
        val keys = parts[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 3) return PendingRun(id, due, keys)
        return PendingRun(id, due, keys, parts[3] == "1", parts[4].toLongOrNull() ?: GRACE_MILLIS)
    }
}
