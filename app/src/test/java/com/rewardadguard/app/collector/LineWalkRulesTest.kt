package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LINE 散步趣's recorded screens: the choice sheet, the challenge, the ad
 * banner that must never be tapped, and the lottery sheet.
 */
class LineWalkRulesTest {

    private fun fixture(name: String): Snapshot {
        val url = requireNotNull(javaClass.classLoader?.getResource("collector-fixtures/$name")) {
            "missing fixture $name"
        }
        return OcrJson.parse(File(url.toURI()).readText())
    }

    @Test
    fun choiceSheetPrefersBonusAndKeepsNormalSeparate() {
        val sheet = LineWalkRules.choiceSheet(fixture("line_sheet_bonus15.ocr.json"))
        assertNotNull(sheet)
        assertNotNull(sheet!!.bonus)
        assertNotNull(sheet.normal)
        assertNotNull(sheet.cancel)
        assertEquals("choice_bonus", sheet.bonus!!.source)
        assertEquals("choice_normal", sheet.normal!!.source)
        assertTrue(sheet.bonus.centerY < sheet.normal.centerY)
    }

    @Test
    fun cooledDownBonusFallsBackToNormal() {
        val sheet = LineWalkRules.choiceSheet(fixture("line_sheet_bonus_cooldown.ocr.json"))
        assertNotNull(sheet)
        assertNull("the bonus is a countdown, not a button", sheet!!.bonus)
        assertNotNull(sheet.normal)
    }

    @Test
    fun skipAmountIsNotMistakenForNormalReward() {
        val sheet = LineWalkRules.choiceSheet(fixture("line_sheet_x1.ocr.json"))
        assertNotNull(sheet)
        // The only amount belongs to 跳過廣告 (which needs an ad-skip pass), so
        // there is no normal reward to take, only 取消.
        assertNull(sheet!!.normal)
        assertNotNull(sheet.cancel)
    }

    @Test
    fun timeTabHasItsOwnSingleRewardSheet() {
        val sheet = LineWalkRules.choiceSheet(fixture("line_time_ticket_sheet.ocr.json"))
        assertNotNull(sheet)
        assertNotNull(sheet!!.normal)
    }

    @Test
    fun adBannerNextToSponsoredIsNeverTappable() {
        val snapshot = Snapshot(
            width = 1220,
            height = 2712,
            capturedAt = 0L,
            foregroundPackage = "com.linecorp.LGWKTW",
            foregroundActivity = null,
            boxes = listOf(
                TextBox("獲得金幣", Box(950, 1500, 1150, 1560), TextSource.OCR),
                TextBox("AD", Box(1060, 1500, 1110, 1530), TextSource.OCR)
            )
        )
        val banner = Target("獲得金幣", Box(950, 1500, 1150, 1560), 100, "ocr")
        assertFalse(LineWalkRules.candidateAllowed(banner, snapshot))
        // A centred wide button is fine.
        val real = Target("獲得金幣", Box(350, 800, 870, 880), 100, "ocr")
        assertTrue(LineWalkRules.candidateAllowed(real, snapshot))
    }

    @Test
    fun challengeBlocksRewardTapsUntilFinished() {
        val counting = fixture("line_challenge_counting.ocr.json")
        assertTrue(LineWalkRules.isChallenge(counting))
        assertFalse(LineWalkRules.challengeFinished(counting))
        assertFalse(
            LineWalkRules.candidateAllowed(
                Target("獲得金幣", Box(475, 2384, 744, 2444), 100, "ocr"),
                counting
            )
        )
        val finish = fixture("line_challenge_finish.ocr.json")
        assertTrue(LineWalkRules.challengeFinished(finish))
        assertNotNull(LineWalkRules.challengeButton(finish))
    }

    @Test
    fun coinBalanceReadsTheTopLeftCounter() {
        assertEquals(620, LineWalkRules.coinBalance(fixture("line_coin_dialog.ocr.json")))
        assertEquals(800, LineWalkRules.coinBalance(fixture("line_sheet_bonus15.ocr.json")))
        assertNull(LineWalkRules.coinBalance(fixture("line_lottery_sheet.ocr.json")))
    }

    @Test
    fun tabMarkersDistinguishTimeFromSteps() {
        val home = fixture("line_home_on_steps.ocr.json")
        assertTrue(LineWalkRules.onTab(home, "首頁"))
        assertFalse(LineWalkRules.onTab(home, "時間"))
        assertFalse(LineWalkRules.onTab(home, "步數"))
        assertTrue(LineWalkRules.onTab(fixture("line_sheet_bonus15.ocr.json"), "時間"))
    }

    @Test
    fun lotterySheetFindsAllThreeDrawsAndCounts() {
        val sheet = LineWalkRules.lotterySheet(fixture("line_lottery_all_three.ocr.json"))
        assertNotNull(sheet)
        assertNotNull(sheet!!.big)
        assertNotNull(sheet.free)
        assertNotNull(sheet.normal)
        assertEquals(47, sheet.counts["big"])
        assertEquals(479, sheet.counts["normal"])
        // BIG is the left button, normal is the right one.
        assertTrue(sheet.big!!.centerX < sheet.normal!!.centerX)
    }

    @Test
    fun lotteryCooldownReplacesTheFreeDraw() {
        val sheet = LineWalkRules.lotterySheet(fixture("line_lottery_cooldown.ocr.json"))
        assertNotNull(sheet)
        assertNull(sheet!!.free)
        assertEquals("00:59:29", sheet.cooldowns["free"])
    }

    @Test
    fun lotteryResultCloseIsOnlyForWinningScreens() {
        val close = LineWalkRules.lotteryResultClose(fixture("line_lottery_result.ocr.json"))
        assertNotNull(close)
        assertEquals("lottery_close", close!!.source)
        assertNull(LineWalkRules.lotteryResultClose(fixture("line_lottery_sheet.ocr.json")))
    }
}
