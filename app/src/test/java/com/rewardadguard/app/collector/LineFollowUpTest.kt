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
}
