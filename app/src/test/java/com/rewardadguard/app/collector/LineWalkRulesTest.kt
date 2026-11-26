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

    @Test
    fun lotteryTicketsLowBelowFiveEndsTheDraw() {
        val sheet = LineWalkRules.lotterySheet(fixture("line_lottery_all_three.ocr.json"))
        assertNotNull(sheet)
        assertFalse(LineWalkRules.lotteryTicketsLow(sheet))
        assertFalse(LineWalkRules.lotteryTicketsLow(sheet!!.copy(counts = mapOf("normal" to 5))))
        assertTrue(LineWalkRules.lotteryTicketsLow(sheet.copy(counts = mapOf("normal" to 4))))
        assertTrue(LineWalkRules.lotteryTicketsLow(sheet.copy(counts = mapOf("normal" to 0))))
        assertFalse("no badge read", LineWalkRules.lotteryTicketsLow(sheet.copy(counts = emptyMap())))
        assertFalse("no sheet", LineWalkRules.lotteryTicketsLow(null))
    }

    @Test
    fun lotteryReadsTheBannerTicketBadgeWhenTheButtonBadgeIsMissing() {
        // 2026-11-22 04:31: the normal button still read "5張抽1次", the only
        // balance was the blue banner badge "1" at the top-right, and OCR gave
        // no per-button badge. Two taps then went nowhere.
        val snapshot = Snapshot(
            width = 1220,
            height = 2712,
            capturedAt = 0L,
            foregroundPackage = "com.linecorp.LGWKTW",
            foregroundActivity = "jp.naver.line.android.activity.main.MainActivity",
            boxes = listOf(
                TextBox("抽抽樂", Box(430, 250, 640, 330), TextSource.OCR),
                TextBox("50張\n抽1次", Box(180, 1100, 420, 1220), TextSource.OCR),
                TextBox("5張\n抽1次", Box(800, 1100, 1040, 1220), TextSource.OCR),
                TextBox("1", Box(880, 220, 930, 280), TextSource.NODE)
            )
        )
        val sheet = LineWalkRules.lotterySheet(snapshot)
        assertNotNull(sheet)
        assertEquals(1, sheet!!.counts["normal"])
        assertTrue(LineWalkRules.lotteryTicketsLow(sheet))
    }

    @Test
    fun lotteryKeepsThePerButtonBadgeOverTheBannerBadge() {
        val base = fixture("line_lottery_all_three.ocr.json")
        val withBanner = base.copy(
            boxes = base.boxes + TextBox("1", Box(880, 220, 930, 280), TextSource.NODE)
        )
        val sheet = LineWalkRules.lotterySheet(withBanner)
        assertNotNull(sheet)
        assertEquals(479, sheet!!.counts["normal"])
        assertEquals(479, sheet.badgeCounts["normal"])
    }

    @Test
    fun lotteryNormalFailedWithSufficientDrawsIsNotTicketsLow() {
        // 2026-11-26 09:10 & 09:11: 546 tickets, red badge 109 (= 546 / 5),
        // normal draw failed twice -> must be lottery_normal_failed (not lottery_tickets_low).
        val base = fixture("line_lottery_all_three.ocr.json")
        val sheet = LineWalkRules.lotterySheet(base)!!.copy(
            counts = mapOf("big" to 10, "normal" to 109),
            badgeCounts = mapOf("big" to 10, "normal" to 109)
        )
        assertEquals(109, LineWalkRules.normalDrawsAvailable(sheet))
        assertTrue(LineWalkRules.hasSufficientNormalDraws(sheet))
        assertFalse(LineWalkRules.isReallyTicketsLow(sheet))
        assertEquals(
            "lottery_normal_failed",
            LineWalkRules.lotteryEndReason(sheet, normalFailures = 2, maxLotteryFailures = 2)
        )
    }

    @Test
    fun lotteryReallyTicketsLowWithZeroBadgeOrBannerBelowFiveOrNotice() {
        val base = LineWalkRules.lotterySheet(fixture("line_lottery_all_three.ocr.json"))!!
        // Banner-only raw tickets = 1 -> 0 draws -> lottery_tickets_low
        val bannerOne = base.copy(counts = mapOf("normal" to 1), badgeCounts = emptyMap())
        assertEquals(0, LineWalkRules.normalDrawsAvailable(bannerOne))
        assertTrue(LineWalkRules.isReallyTicketsLow(bannerOne))
        assertEquals(
            "lottery_tickets_low",
            LineWalkRules.lotteryEndReason(bannerOne, normalFailures = 0, maxLotteryFailures = 2)
        )
        // Red badge = 3 (3 draws = 15 tickets) -> sufficient draws, NOT low
        val badgeThree = base.copy(counts = mapOf("normal" to 3), badgeCounts = mapOf("normal" to 3))
        assertEquals(3, LineWalkRules.normalDrawsAvailable(badgeThree))
        assertFalse(LineWalkRules.isReallyTicketsLow(badgeThree))
        assertEquals(
            "lottery_normal_failed",
            LineWalkRules.lotteryEndReason(badgeThree, normalFailures = 2, maxLotteryFailures = 2)
        )
        // Explicit "票券不足" notice on screen -> really low even if badge existed
        val notice = Snapshot(
            1220, 2712, 0L, "com.linecorp.LGWKTW", null,
            boxes = listOf(TextBox("票券不足", Box(300, 1200, 900, 1300), TextSource.OCR))
        )
        assertTrue(LineWalkRules.isReallyTicketsLow(badgeThree, seenAfterFail = notice))
        // No count read at all + 2 failures -> conservative lottery_tickets_low (2026-11-22)
        val noCounts = base.copy(counts = emptyMap(), badgeCounts = emptyMap())
        assertEquals(
            "lottery_tickets_low",
            LineWalkRules.lotteryEndReason(noCounts, normalFailures = 2, maxLotteryFailures = 2)
        )
    }

    @Test
    fun lotterySkipsBigAndFreeOnCooldownButNeverNormal() {
        // 2026-11-26 14:08 & 14:11: BIG showed "回復還需 50張 00:02:21" on the left
        // while the normal draw on the right also had "回復還需 00:59:07" below it.
        val snapshot = Snapshot(
            width = 1220,
            height = 2712,
            capturedAt = 0L,
            foregroundPackage = "com.linecorp.LGWKTW",
            foregroundActivity = null,
            boxes = listOf(
                TextBox("抽抽樂", Box(538, 360, 681, 406), TextSource.OCR),
                TextBox("回復還需 50張 00:02:21", Box(180, 1230, 520, 1280), TextSource.OCR),
                TextBox("50張 抽1次", Box(250, 1285, 480, 1340), TextSource.OCR),
                TextBox("109", Box(1042, 1124, 1117, 1156), TextSource.OCR),
                TextBox("5張 抽1次", Box(839, 1221, 963, 1266), TextSource.OCR),
                TextBox("回復還需 00:59:07", Box(721, 1382, 1051, 1416), TextSource.OCR)
            )
        )
        val sheet = LineWalkRules.lotterySheet(snapshot)
        assertNotNull(sheet)
        assertEquals("00:02:21", sheet!!.cooldowns["big"])
        assertEquals("00:59:07", sheet.cooldowns["free"])
        assertTrue(LineWalkRules.isOnCooldown(sheet, "big"))
        assertTrue(LineWalkRules.isOnCooldown(sheet, "free"))
        assertFalse("normal draw is never gated by cooldown", LineWalkRules.isOnCooldown(sheet, "normal"))

        // When BIG and free are on cooldown and normal is unavailable -> lottery_all_cooldown.
        val allCooldown = sheet.copy(normal = null, counts = emptyMap(), badgeCounts = emptyMap())
        assertEquals(
            "lottery_all_cooldown",
            LineWalkRules.lotteryEndReason(
                sheet = allCooldown,
                normalFailures = 0,
                maxLotteryFailures = 2,
                hadCooldownSkip = true
            )
        )
    }

    @Test
    fun bonusTrackerDisablesBonusForRunAfterTwoInvitePromoOrUnseenRewardFailures() {
        // 2026-11-26 12:09: bonus -> 邀請好友 -> coin balance unchanged, twice in a row.
        val sheet = LineWalkRules.choiceSheet(fixture("line_sheet_bonus15.ocr.json"))!!
        val tracker = LineWalkRules.BonusTracker(maxFailures = 2)

        // Ticket 1: bonus allowed, then invite promo + no coin increase -> failure #1.
        assertTrue(tracker.bonusAllowed)
        assertNotNull(sheet.bonus)
        val disabled1 = tracker.recordFailure()
        assertFalse("not disabled after 1st failure", disabled1)
        assertTrue(tracker.bonusAllowed)

        // Ticket 2: bonus still allowed, then invite promo + no coin increase -> failure #2.
        val disabled2 = tracker.recordFailure()
        assertTrue("disabled event fires once on 2nd failure", disabled2)
        assertFalse("remaining tickets must pick normal directly", tracker.bonusAllowed)

        // Subsequent tickets: bonus stays disabled, disabled event does not fire again.
        assertFalse(tracker.bonusAllowed)
        assertFalse(tracker.recordFailure())
        // Even if a normal reward is confirmed later, bonus stays disabled for the run.
        tracker.recordConfirmed()
        assertFalse(tracker.bonusAllowed)
    }

    @Test
    fun bonusTrackerResetsOnlyWhenBonusRewardIsConfirmed() {
        val tracker = LineWalkRules.BonusTracker(maxFailures = 2)
        assertFalse(tracker.recordFailure())
        assertEquals(1, tracker.failures)
        // A confirmed bonus reward resets consecutive failures back to 0.
        tracker.recordConfirmed()
        assertEquals(0, tracker.failures)
        assertTrue(tracker.bonusAllowed)
        // One more failure after reset still allows bonus.
        assertFalse(tracker.recordFailure())
        assertTrue(tracker.bonusAllowed)
    }
}
