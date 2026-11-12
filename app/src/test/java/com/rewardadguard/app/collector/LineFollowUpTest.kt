package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two wasted-tap patterns from the 2026-11-12 evening runs. */
class LineFollowUpTest {

    private fun screen(vararg boxes: TextBox) =
        Snapshot(1220, 2712, 0L, "com.linecorp.LGWKTW", null, boxes = boxes.toList())

    @Test
    fun theHomeMeterStillFillingUpIsNotAReward() {
        val meter = Target("56 \n%\n獲得金幣", Box(400, 780, 820, 890), 100, "node")
        val button = Target("獲得金幣\n2", Box(400, 1040, 820, 1150), 100, "node")
        val snapshot = screen()
        assertFalse(LineWalkRules.candidateAllowed(meter, snapshot))
        assertTrue(LineWalkRules.candidateAllowed(button, snapshot))
    }

    @Test
    fun bigAndFreeAreOneDrawPerCountdown() {
        assertTrue(LineWalkRules.isOncePerCooldown("big"))
        assertTrue(LineWalkRules.isOncePerCooldown("free"))
        assertFalse(LineWalkRules.isOncePerCooldown("normal"))
    }

    @Test
    fun theDrawAnimationsSkipIsFoundWithItsGlyphs() {
        for (label in listOf("SKIP", "SKIP ▶|", "SKIP>I", "SKIPI")) {
            val shot = screen(TextBox(label, Box(900, 830, 1160, 930), TextSource.OCR))
            assertTrue(label, LineWalkRules.findSkip(shot) != null)
        }
        val store = screen(TextBox("Skip to Google Play", Box(100, 400, 900, 460), TextSource.OCR))
        assertFalse(LineWalkRules.findSkip(store) != null)
    }

    @Test
    fun onlyTheStatusBarShadeMakesTheCollectorWait() {
        assertTrue(CollectorRules.isUserShade("com.android.systemui"))
        assertFalse(CollectorRules.isUserShade("com.android.vending"))
        assertFalse(CollectorRules.isUserShade(null))
    }
}
