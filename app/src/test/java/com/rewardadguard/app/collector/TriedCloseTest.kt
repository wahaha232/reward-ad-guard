package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review 2026-11-12 #7: an X that moved 1-3 px between frames is the same X. */
class TriedCloseTest {

    @Test
    fun aJitteredXIsAlreadyTried() {
        val tried = listOf(1012 to 98)
        assertTrue(CollectorRules.alreadyTried(tried, 1013, 98))
        assertTrue(CollectorRules.alreadyTried(tried, 1009, 101))
        assertTrue(CollectorRules.alreadyTried(tried, 1028, 114))
    }

    @Test
    fun aDifferentButtonIsNot() {
        val tried = listOf(1012 to 98)
        assertFalse(CollectorRules.alreadyTried(tried, 72, 192))
        assertFalse(CollectorRules.alreadyTried(tried, 1012, 140))
        assertFalse(CollectorRules.alreadyTried(emptyList(), 1012, 98))
    }
}

/** 2026-11-12 23:22: "50 枚金幣中獎囉！" was on screen and not recognised. */
class PrizeTextTest {

    @Test
    fun ocrVariantsOfThePrizeStillCount() {
        for (text in listOf("50 枚金幣中獎囉！", "10校金幣中獎囉!", "50枚金幣中奬囉", "50枚金币中奖啰", "50 枚 金 幣 中 獎 囉")) {
            assertTrue(text, LineWalkRules.isPrizeText(text))
        }
        assertFalse(LineWalkRules.isPrizeText("查看獎項一覽"))
        assertFalse(LineWalkRules.isPrizeText("5張 抽1次"))
    }
}
