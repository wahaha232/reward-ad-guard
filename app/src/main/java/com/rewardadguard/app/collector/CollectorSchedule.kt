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
data class PendingRun(val entryId: Long, val dueAt: Long, val appKeys: List<String>)

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
        now - pending.dueAt > GRACE_MILLIS -> Decision.EXPIRED
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
    }.sortedWith(compareBy({ it.hour }, { it.minute }))

    fun encodePending(p: PendingRun): String = "${p.entryId}|${p.dueAt}|${p.appKeys.joinToString(",")}"

    fun decodePending(raw: String?): PendingRun? {
        val parts = raw?.split("|") ?: return null
        if (parts.size != 3) return null
        val id = parts[0].toLongOrNull() ?: return null
        val due = parts[1].toLongOrNull() ?: return null
        val keys = parts[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return PendingRun(id, due, keys)
    }
}
