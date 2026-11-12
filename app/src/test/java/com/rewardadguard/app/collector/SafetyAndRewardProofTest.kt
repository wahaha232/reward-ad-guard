package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Review 2026-11-12:
 *  P0 - never act on a screen that is not positively the target app;
 *  P1 - an ended ad is not a reward without proof.
 */
class SafetyAndRewardProofTest {

    private val line = "com.linecorp.LGWKTW"

    // ---------------------------------------------------------------- P0

    @Test
    fun aTapNeedsTheTargetAppInFront() {
        assertEquals(ActionGate.Decision.ALLOW, ActionGate.decide(ActionGate.Action.TAP, line, line))
        assertEquals(
            ActionGate.Decision.DENY,
            ActionGate.decide(ActionGate.Action.TAP, "com.gimmatek.hahago.aha", line)
        )
    }

    @Test
    fun anUnknownForegroundIsNeverTappedOrBackedOutOf() {
        for (fg in listOf(null, "", "  ")) {
            assertEquals(ActionGate.Decision.UNKNOWN, ActionGate.decide(ActionGate.Action.TAP, fg, line))
            assertEquals(ActionGate.Decision.UNKNOWN, ActionGate.decide(ActionGate.Action.BACK, fg, line))
        }
    }

    @Test
    fun backMayLeaveAKnownForeignAppAndHomeOrLaunchAreAlwaysSafe() {
        assertEquals(
            ActionGate.Decision.ALLOW,
            ActionGate.decide(ActionGate.Action.BACK, "com.android.vending", line)
        )
        assertEquals(ActionGate.Decision.ALLOW, ActionGate.decide(ActionGate.Action.HOME, null, line))
        assertEquals(ActionGate.Decision.ALLOW, ActionGate.decide(ActionGate.Action.LAUNCH, null, line))
    }

    // ---------------------------------------------------------------- P1

    private fun fixture(name: String): Snapshot {
        val url = requireNotNull(javaClass.classLoader?.getResource("collector-fixtures/$name"))
        return OcrJson.parse(File(url.toURI()).readText())
    }

    private fun bitwalk(vararg lines: String): Snapshot = Snapshot(
        width = 1080,
        height = 2400,
        capturedAt = 0L,
        foregroundPackage = "jp.paddleinc.walk",
        foregroundActivity = null,
        boxes = lines.mapIndexed { i, text ->
            TextBox(text, Box(100, 200 + i * 100, 900, 260 + i * 100), TextSource.OCR)
        }
    )

    @Test
    fun bitwalksRewardDialogIsProof() {
        val dialog = fixture("app_bitwalk_reward_dialog.ocr.json")
        assertTrue(RewardConfirmation.hasRewardDialog(dialog, CollectorProfiles.BITWALK))
    }

    @Test
    fun theMbtcBalanceIsReadWithOcrsDot() {
        val p = CollectorProfiles.BITWALK
        assertEquals(0.000269, RewardConfirmation.balance(bitwalk("0 · 000269 mBTC"), p)!!, 1e-12)
        assertEquals(0.000312, RewardConfirmation.balance(bitwalk("0.000312 mBTC"), p)!!, 1e-12)
        assertNull("steps are not a balance", RewardConfirmation.balance(bitwalk("204 , 528"), p))
    }

    @Test
    fun aRisingBalanceIsProofAndAnUnchangedOneIsNot() {
        val p = CollectorProfiles.BITWALK
        val before = bitwalk("0 · 000269 mBTC", "步行", "賺取")
        assertTrue(RewardConfirmation.confirmed(before, bitwalk("0 · 000281 mBTC", "步行", "賺取"), p))
        assertFalse(RewardConfirmation.confirmed(before, bitwalk("0 · 000269 mBTC", "步行", "賺取"), p))
        assertFalse("no balance on screen, no proof", RewardConfirmation.confirmed(before, bitwalk("步行", "賺取"), p))
    }

    @Test
    fun appsWithoutABalanceNeedTheirDialog() {
        val p = CollectorProfiles.HAHAGO
        val plain = bitwalk("首頁", "365")
        assertNull(RewardConfirmation.balance(plain, p))
        assertFalse(RewardConfirmation.confirmed(plain, plain, p))
    }
}

/** 2026-11-13 00:15: LINE's ticket dialog is proof even before its 關閉 renders. */
class LineTicketDialogProofTest {

    @Test
    fun theTicketDialogIsARewardDialogForLine() {
        val boxes = listOf(
            TextBox("集點", Box(60, 2480, 240, 2560), TextSource.OCR),
            TextBox("好友", Box(360, 2480, 560, 2560), TextSource.OCR),
            TextBox("使用", Box(660, 2480, 860, 2560), TextSource.OCR),
            TextBox("精選", Box(960, 2480, 1160, 2560), TextSource.OCR),
            TextBox("獲得15張票券！", Box(400, 860, 820, 920), TextSource.OCR)
        )
        val dialog = Snapshot(1220, 2712, 0L, "com.linecorp.LGWKTW", null, boxes = boxes)
        org.junit.Assert.assertTrue(RewardConfirmation.hasRewardDialog(dialog, CollectorProfiles.LINE_WALK))
        val home = dialog.copy(boxes = boxes.dropLast(1) + TextBox("獲得金幣\n2", Box(400, 1040, 820, 1150), TextSource.NODE))
        org.junit.Assert.assertFalse(RewardConfirmation.hasRewardDialog(home, CollectorProfiles.LINE_WALK))
    }
}
