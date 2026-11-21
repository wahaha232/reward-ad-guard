package com.rewardadguard.app.collector

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectorScheduleTest {

    private val zone = ZoneId.of("Asia/Taipei")
    private fun at(h: Int, m: Int, day: Int = 15) =
        ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    private val morning = ScheduleEntry(1, 7, 30, listOf("bitwalk"))
    private val night = ScheduleEntry(2, 22, 0, listOf("linelottery"))

    @Test
    fun nextOccurrence_laterToday() {
        assertEquals(at(7, 30), ScheduleMath.nextOccurrence(morning, at(6, 0), zone))
    }

    @Test
    fun nextOccurrence_passedGoesToTomorrow() {
        assertEquals(at(7, 30, 16), ScheduleMath.nextOccurrence(morning, at(7, 30), zone))
        assertEquals(at(7, 30, 16), ScheduleMath.nextOccurrence(morning, at(12, 0), zone))
    }

    @Test
    fun nextDue_picksEarliestEnabled() {
        assertEquals(night to at(22, 0), ScheduleMath.nextDue(listOf(morning, night), at(12, 0), zone))
        assertEquals(morning to at(7, 30, 16), ScheduleMath.nextDue(listOf(morning, night.copy(enabled = false)), at(12, 0), zone))
        assertNull(ScheduleMath.nextDue(listOf(morning.copy(appKeys = emptyList())), at(12, 0), zone))
    }

    private val pending = PendingRun(1, at(7, 30), listOf("bitwalk"))

    private fun decide(now: Long, locked: Boolean = false, screenOn: Boolean = true, running: Boolean = false, idleSince: Long = 0L) =
        ScheduleMath.decide(pending, now, locked, screenOn, running, idleSince)

    @Test
    fun decide_waitsWhileLockedOrScreenOff() {
        assertEquals(ScheduleMath.Decision.WAIT_UNLOCK, decide(at(7, 40), locked = true))
        assertEquals(ScheduleMath.Decision.WAIT_UNLOCK, decide(at(7, 40), screenOn = false))
    }

    @Test
    fun decide_waitsThreeIdleMinutes() {
        // Used at 7:39 -> starts 7:42 at the earliest.
        assertEquals(ScheduleMath.Decision.WAIT_IDLE, decide(at(7, 41), idleSince = at(7, 39)))
        assertEquals(ScheduleMath.Decision.START, decide(at(7, 42), idleSince = at(7, 39)))
        assertEquals(60_000L, ScheduleMath.idleRemaining(pending, at(7, 41), at(7, 39)))
    }

    @Test
    fun decide_countsIdleFromDueTimeNotEarlier() {
        // Untouched since 6:00, due 7:30: still waits until 7:33.
        assertEquals(ScheduleMath.Decision.WAIT_IDLE, decide(at(7, 31), idleSince = at(6, 0)))
        assertEquals(ScheduleMath.Decision.START, decide(at(7, 33), idleSince = at(6, 0)))
    }

    @Test
    fun decide_busyAndExpired() {
        assertEquals(ScheduleMath.Decision.WAIT_BUSY, decide(at(8, 0), running = true))
        assertEquals(ScheduleMath.Decision.EXPIRED, decide(at(10, 31), locked = true))
        assertEquals(ScheduleMath.Decision.WAIT_UNLOCK, decide(at(10, 30), locked = true))
    }

    @Test
    fun encodeDecode_roundTripAndSorted() {
        val list = listOf(night, morning.copy(enabled = false, appKeys = listOf("bitwalk", "hahago")))
        val back = ScheduleMath.decode(ScheduleMath.encode(list))
        assertEquals(listOf(list[1], list[0]), back)
        assertEquals(emptyList<ScheduleEntry>(), ScheduleMath.decode("garbage\n1|25|0|1|x"))
    }

    @Test
    fun pending_roundTrip() {
        assertEquals(pending, ScheduleMath.decodePending(ScheduleMath.encodePending(pending)))
        assertNull(ScheduleMath.decodePending("x"))
    }

    @Test
    fun scanConfig_keepsAnExplicitlyEmptyAppList() {
        val chosen = ScanConfig(
            mode = ScanConfig.MODE_INTERVAL,
            intervalHours = 6,
            appKeys = listOf("walkearn"),
            appsChosen = true
        )
        assertEquals(chosen, ScanConfig.decode(ScanConfig.encode(chosen)))

        // Unchecking the last app must stay unchecked, not fall back to all.
        val none = chosen.copy(appKeys = emptyList())
        assertEquals(none, ScanConfig.decode(ScanConfig.encode(none)))

        // Four-part records from before the flag: empty meant the default apps.
        assertFalse(ScanConfig.decode("1|6||17:00").appsChosen)
        assertTrue(ScanConfig.decode("1|6|bitwalk|17:00").appsChosen)
    }
}
