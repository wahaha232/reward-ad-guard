package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrFuzzyAvoidTest {

    private fun n(s: String) = TextMatch.normalizeLoose(s)
    private val rewards = listOf("收看廣告影片", "領取", "簽到").map(::n)

    @Test
    fun oneMisreadCharacterIsStillAvoided() {
        assertTrue(TextMatch.fuzzyAvoidHit(n("立即領取澧物卡"), n("禮物卡"), rewards))
        assertTrue(TextMatch.fuzzyAvoidHit(n("立即領取搜物卡"), n("禮物卡"), rewards))
    }

    @Test
    fun aRewardOverlappingTheWindowIsNotVetoed() {
        // 已領取 vs 可領取 differ by one character, but 可領取 is the reward.
        assertFalse(TextMatch.fuzzyAvoidHit(n("可領取"), n("已領取"), rewards))
        assertFalse(TextMatch.fuzzyAvoidHit(n("收看廣告影片，獲得5倍的BTC"), n("2倍獎勵"), rewards))
    }

    @Test
    fun shortTermsAndTwoMisreadsAreNotFuzzy() {
        assertFalse(TextMatch.fuzzyAvoidHit(n("取得"), n("取消"), rewards))
        assertFalse(TextMatch.fuzzyAvoidHit(n("立即領取搜澧卡"), n("禮物卡"), rewards))
        // A digit is not a misread Han character (AHEALTH 領取 10 點數 vs 倍點數).
        assertFalse(TextMatch.fuzzyAvoidHit(n("領取10點數"), n("倍點數"), listOf(n("領取"))))
    }

    private fun snap(text: String, source: TextSource) = Snapshot(
        width = 1220, height = 2712, capturedAt = 0L,
        foregroundPackage = "jp.paddleinc.walk", foregroundActivity = null,
        boxes = listOf(TextBox(text, Box(400, 2200, 820, 2290), source))
    )

    @Test
    fun onlyOcrTextIsMatchedFuzzily() {
        val p = CollectorProfiles.BITWALK
        assertEquals(0, snap("立即領取澧物卡", TextSource.OCR).matches(p.rewardTerms, listOf("禮物卡"), p.exactTerms).size)
        assertEquals(1, snap("立即領取澧物卡", TextSource.NODE).matches(p.rewardTerms, listOf("禮物卡"), p.exactTerms).size)
    }
}
