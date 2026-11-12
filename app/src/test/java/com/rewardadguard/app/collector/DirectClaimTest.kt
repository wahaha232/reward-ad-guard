package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AHEALTH 2026-11-12 19:59: "領取 10 點數" worked but was never counted. */
class DirectClaimTest {

    private fun box(text: String, l: Int, t: Int, r: Int, b: Int) =
        TextBox(text, Box(l, t, r, b), TextSource.OCR)

    private val bottomBar = listOf(
        box("首頁", 160, 2480, 300, 2540),
        box("健康年齡", 400, 2480, 600, 2540),
        box("福利", 680, 2480, 800, 2540),
        box("禮物商店", 900, 2480, 1100, 2540)
    )

    private fun screen(vararg boxes: TextBox) =
        Snapshot(1220, 2712, 0L, "com.tracerofficial", null, boxes = boxes.toList() + bottomBar)

    private val claim = Target("領取 10 點數", Box(400, 1350, 900, 1430), 100, "ocr")

    @Test
    fun theButtonTurningIntoDoneIsAClaim() {
        val after = screen(box("今日點數已領完！", 400, 1350, 900, 1430))
        assertTrue(CollectorRules.isDirectClaim(claim, after, CollectorProfiles.AHEALTH, expectAd = false))
    }

    @Test
    fun theButtonStillThereIsNotAClaim() {
        val after = screen(box("領取 10 點數", 400, 1350, 900, 1430))
        assertFalse(CollectorRules.isDirectClaim(claim, after, CollectorProfiles.AHEALTH, expectAd = false))
    }

    @Test
    fun adButtonsAndLineNeverCountThisWay() {
        val after = screen(box("今日點數已領完！", 400, 1350, 900, 1430))
        assertFalse(CollectorRules.isDirectClaim(claim, after, CollectorProfiles.AHEALTH, expectAd = true))
        assertFalse(CollectorRules.isDirectClaim(claim, after, CollectorProfiles.LINE_WALK, expectAd = false))
    }

    @Test
    fun leavingTheAppScreenIsNotAClaim() {
        val after = Snapshot(1220, 2712, 0L, "com.tracerofficial", null, boxes = listOf(box("Loading", 500, 1200, 700, 1260)))
        assertFalse(CollectorRules.isDirectClaim(claim, after, CollectorProfiles.AHEALTH, expectAd = false))
    }
}
