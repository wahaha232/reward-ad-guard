package com.rewardadguard.app.collector

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanTest {

    private val zone = ZoneId.of("Asia/Taipei")
    private fun at(day: Int, h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun intervalBecomesWholeHours() {
        val c = ScanConfig(ScanConfig.MODE_INTERVAL, 6, listOf("bitwalk"))
        assertEquals(listOf(0, 6, 12, 18), c.entries().map { it.hour })
        assertEquals(6 * 3_600_000L, ScanConfig(ScanConfig.MODE_INTERVAL, 1).graceMillis() * 6)
        assertEquals(ScheduleMath.GRACE_MILLIS, c.graceMillis())
        assertEquals(emptyList<ScheduleEntry>(), ScanConfig().entries())
    }

    @Test
    fun fixedTimesAndNextDue() {
        val c = ScanConfig(ScanConfig.MODE_FIXED, 3, listOf("hahago"), listOf(9 to 30, 21 to 0))
        val next = ScheduleMath.nextDue(c.entries(), at(15, 12), zone)!!
        assertEquals(at(15, 21), next.second)
        assertEquals(listOf("hahago"), next.first.appKeys)
    }

    @Test
    fun configRoundTrip() {
        val c = ScanConfig(ScanConfig.MODE_FIXED, 4, listOf("bitwalk", "custom_9"), listOf(21 to 0, 9 to 5))
        assertEquals(c.copy(times = listOf(9 to 5, 21 to 0)), ScanConfig.decode(ScanConfig.encode(c)))
        assertEquals(ScanConfig(), ScanConfig.decode("junk"))
    }

    @Test
    fun pendingScanRoundTripAndOldFormat() {
        val p = PendingRun(-1003, at(15, 3), listOf("bitwalk"), scan = true, graceMillis = 3_600_000L)
        assertEquals(p, ScheduleMath.decodePending(ScheduleMath.encodePending(p)))
        assertEquals(PendingRun(1, 5, listOf("a")), ScheduleMath.decodePending("1|5|a"))
    }

    @Test
    fun scanExpiresAtItsOwnGrace() {
        val p = PendingRun(-1003, at(15, 3), listOf("bitwalk"), scan = true, graceMillis = 3_600_000L)
        assertEquals(ScheduleMath.Decision.EXPIRED, ScheduleMath.decide(p, at(15, 4, 1), true, false, false, 0))
        assertEquals(ScheduleMath.Decision.WAIT_UNLOCK, ScheduleMath.decide(p, at(15, 3, 59), true, false, false, 0))
    }

    private fun t(text: String, x: Int = 100) = Target(text, Box(x, 500, x + 100, 560), 100, "ocr")

    @Test
    fun scanCount_neverDoubleCountsOverlappingPages() {
        val pages = listOf(
            listOf(t("領取"), t("領取", 400), t("看廣告")),
            listOf(t("領取", 50), t("領取", 300), t("領取", 600)),
            emptyList()
        )
        assertEquals(4, CollectorRules.scanCount(pages))
    }

    @Test
    fun summary_scanAfterCollectionSetsWhatIsLeft() {
        val rows = listOf(
            RunSummaryRow("bitwalk", "BITWALK", at(15, 8), 10, 0, "done", false),
            RunSummaryRow("bitwalk", "BITWALK", at(15, 12), 0, 0, "done", false, scan = true, available = 4)
        )
        val s = DailyRewardSummary.summarize(rows, zone).single()
        assertEquals("[2026/11/15] BITWALK 已收穫 10 個獎勵，尚有 4 個獎勵待收取（12:00 掃描）", s.text())
    }

    @Test
    fun scanResultText() {
        assertEquals("BITWALK：尚有 3 個", ScanResult("bitwalk", "BITWALK", 3, "done").text())
        assertEquals("HAHAGO：已收完", ScanResult("hahago", "HAHAGO", 0, "done").text())
        assertEquals("LINE：未掃完（stopped）", ScanResult("linewalk", "LINE", 0, "stopped").text())
    }

    @Test
    fun lotteryBadgesAreTickets() {
        val sheet = LineWalkRules.LotterySheet(
            big = t("50張"), free = t("免費抽1次"), normal = t("5張"),
            cooldowns = emptyMap(), counts = mapOf("big" to 24, "normal" to 240)
        )
        assertEquals(49, CollectorRules.lotteryAvailable(sheet))
        assertEquals(48, CollectorRules.lotteryAvailable(sheet.copy(cooldowns = mapOf("free" to "01:00:00"))))
    }

    @Test
    fun repeatableAndBlockedTexts() {
        assertEquals("BITWALK：有廣告獎勵可收（可重複看，次數不明）", ScanResult("bitwalk", "BITWALK", 1, "done", repeatable = true).text())
        val note = "AHEALTH 睡眠模式中，請手動按「我醒了」"
        assertEquals(note, ScanResult("ahealth", "AHEALTH", -1, "blocked_screen", note = note).text())
        assertEquals("BITWALK：已收完", ScanResult("bitwalk", "BITWALK", 0, "done", repeatable = true).text())
    }

    @Test
    fun ahealthSleepScreenIsABlocker() {
        assertEquals(true, CollectorProfiles.AHEALTH.blockerScreens.keys.any { "睡眠模式" in it })
        assertEquals(true, CollectorProfiles.BITWALK.repeatableRewards)
    }

    @Test
    fun summary_repeatableScan() {
        val rows = listOf(RunSummaryRow("bitwalk", "BITWALK", at(15, 9), 0, 0, "done", false, scan = true, available = 1, repeatable = true))
        assertEquals("[2026/11/15] BITWALK 已收穫 0 個獎勵，還有廣告獎勵可收（09:00 掃描，次數不明）",
            DailyRewardSummary.summarize(rows, zone).single().text())
    }
}
