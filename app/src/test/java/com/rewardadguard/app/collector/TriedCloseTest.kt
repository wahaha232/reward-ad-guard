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

    @Test
    fun triedCloseSpotsCoolsDownNonRedirectAndBansRedirect() {
        val spots = CollectorRules.TriedCloseSpots(cooldownMillis = 15_000L, maxSameSpotTaps = 3)
        // First tap on Meta "Ad 1 of 2" next/close at (1124, 192) did not redirect.
        spots.record(1124, 192, nowMillis = 20_000L, causedRedirect = false)
        assertTrue(spots.isBlocked(1124, 192, nowMillis = 25_000L))
        assertTrue(spots.isBlocked(1130, 196, nowMillis = 34_999L))
        // After cooldown, the same top-right spot can be tapped again for "Ad 2 of 2".
        assertFalse(spots.isBlocked(1124, 192, nowMillis = 35_000L))

        // Second & third taps still cool down, then hit maxSameSpotTaps=3 cap.
        spots.record(1124, 192, nowMillis = 35_000L, causedRedirect = false)
        assertFalse(spots.isBlocked(1124, 192, nowMillis = 50_000L))
        spots.record(1124, 192, nowMillis = 50_000L, causedRedirect = false)
        assertTrue(spots.isBlocked(1124, 192, nowMillis = 999_000L))

        // Sequence advance clears non-redirect spots, but keeps redirect-causing fake X banned.
        spots.record(1000, 115, nowMillis = 55_000L, causedRedirect = true)
        spots.clearNonRedirect()
        assertFalse(spots.isBlocked(1124, 192, nowMillis = 56_000L))
        assertTrue(spots.isBlocked(1000, 115, nowMillis = 999_000L))
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
