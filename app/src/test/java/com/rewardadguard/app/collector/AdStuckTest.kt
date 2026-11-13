package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-13: playable / frozen ads held the run ~3 min each; give up early, retry later. */
class AdStuckTest {

    @Test
    fun aFrozenAdIsStuckAfterAMinuteWithoutProgress() {
        assertTrue(AdStuck.isStuck(adSeen = true, elapsedMillis = 70_000, minWatchMillis = 10_000, sinceProgressMillis = 60_000))
    }

    @Test
    fun aMovingAdOrOneStillInItsMinimumWatchIsNot() {
        assertFalse("progress 5 s ago", AdStuck.isStuck(true, 70_000, 10_000, 5_000))
        assertFalse("minimum watch not over", AdStuck.isStuck(true, 8_000, 10_000, 60_000))
        assertFalse("no ad seen", AdStuck.isStuck(false, 90_000, 10_000, 90_000))
    }

    private fun target(text: String, x: Int, y: Int) = Target(text, Box(x - 100, y - 40, x + 100, y + 40), 100, "node")

    @Test
    fun theSameButtonKeepsItsIdWhenItsCountChanges() {
        assertEquals(
            AdStuck.deferId("步數", target("獲得票券\n4", 610, 890)),
            AdStuck.deferId("步數", target("獲得票券\n5", 612, 892))
        )
    }

    @Test
    fun differentTabsOrPlacesAreDifferentRewards() {
        val a = AdStuck.deferId("步數", target("獲得票券\n4", 610, 890))
        assertNotEquals(a, AdStuck.deferId("時間", target("獲得票券\n4", 610, 890)))
        assertNotEquals(a, AdStuck.deferId("步數", target("獲得票券\n4", 610, 1500)))
    }

    @Test
    fun theUserSeesTheButtonWordsWithoutCounts() {
        assertEquals("獲得票券", AdStuck.label("獲得票券\n4"))
        assertEquals("收看廣告影片，獲得5倍的BTC", AdStuck.label("收看廣告影片，獲得5倍的BTC"))
        assertEquals("獲得金幣", AdStuck.label("獲得金幣\n2"))
    }

    @Test
    fun oneBadAdSkipsOnlyThatAdTwoInARowDeferTheButton() {
        assertFalse(AdStuck.shouldDefer(1))
        assertTrue(AdStuck.shouldDefer(2))
    }
}

/** 2026-11-13 14:33: LINE's "無法載入廣告" dialog was not recognised and the button behind it was tapped 20+ times. */
class LineNoAdDialogTest {

    @Test
    fun lineNoAdDialogIsClosedWithItsButton() {
        val boxes = listOf(
            TextBox("無法載入廣告", Box(460, 860, 760, 920), TextSource.OCR),
            TextBox("請稍後再試。", Box(460, 1520, 760, 1580), TextSource.OCR),
            TextBox("關閉", Box(560, 1780, 660, 1840), TextSource.OCR)
        )
        val snapshot = Snapshot(1220, 2712, 0L, "com.linecorp.LGWKTW", null, boxes = boxes)
        val close = CollectorRules.findInfoDialogClose(snapshot, CollectorProfiles.LINE_WALK)
        org.junit.Assert.assertNotNull(close)
        assertEquals("關閉", close!!.text)
    }
}
