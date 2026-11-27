package com.rewardadguard.app.collector

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DailyRewardSummaryTest {

    private val zone = ZoneId.of("Asia/Taipei")
    private fun at(day: Int, h: Int) = ZonedDateTime.of(2026, 11, day, h, 0, 0, 0, zone).toInstant().toEpochMilli()
    private fun run(key: String, day: Int, h: Int, rewards: Int, manual: Int = 0, end: String = "done", dry: Boolean = false) =
        RunSummaryRow(key, key.uppercase(), at(day, h), rewards, manual, end, dry)

    @Test
    fun sumsPerDayAndApp_lastRunDecidesWhatIsLeft() {
        val result = DailyRewardSummary.summarize(
            listOf(
                run("bitwalk", 15, 8, 10, manual = 2, end = "stopped"),
                run("bitwalk", 15, 20, 13, manual = 1),
                run("hahago", 15, 9, 5),
                run("bitwalk", 14, 9, 7, dry = true),
                run("bitwalk", 14, 10, 4, end = "paused_too_long")
            ),
            zone
        )
        assertEquals(3, result.size)
        assertEquals(DailyAppSummary(LocalDate.of(2026, 11, 15), "bitwalk", "BITWALK", 23, 1, true), result[0])
        assertEquals("[2026/11/15] BITWALK 已收穫 23 個獎勵，尚有 1 個獎勵待收取", result[0].text())
        assertEquals("[2026/11/15] HAHAGO 已收穫 5 個獎勵，已全部收完", result[1].text())
        assertEquals("[2026/11/14] BITWALK 已收穫 4 個獎勵，上次未跑完，可能還有獎勵", result[2].text())
    }

    @Test
    fun parse_readsSummaryJson() {
        val row = DailyRewardSummary.parse(
            """{"app":"linelottery","name":"LINE 散步趣［抽抽樂］","dryRun":false,"startedAt":1794727075684,"endReason":"done","rewards":73,"manual":0}"""
        )
        assertEquals(RunSummaryRow("linelottery", "LINE 散步趣［抽抽樂］", 1794727075684, 73, 0, "done", false), row)
        assertNull(DailyRewardSummary.parse("{}"))
    }

    @Test
    fun lotteryAdUnavailableAndNormalFailedShowDedicatedNotes() {
        val result = DailyRewardSummary.summarize(
            listOf(
                run("linelottery", 27, 10, 6, end = "lottery_ad_unavailable"),
                run("linewalk", 27, 11, 2, end = "lottery_normal_failed")
            ),
            zone
        )
        assertEquals(2, result.size)
        assertEquals(
            "[2026/11/27] LINELOTTERY 已收穫 6 個獎勵，LINE 廣告載入不到，稍後再試",
            result[0].text()
        )
        assertEquals(
            "[2026/11/27] LINEWALK 已收穫 2 個獎勵，一般抽連續失敗，請稍後再試",
            result[1].text()
        )
    }
}
