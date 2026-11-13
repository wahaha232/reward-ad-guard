package com.rewardadguard.app.collector

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The floating 暫停/停止 panel must never be tapped by the run or read as app text. */
class FloatingControlsTest {

    @After
    fun clear() {
        FloatingControls.bounds = null
    }

    @Test
    fun tapsOnOrNextToThePanelAreRefused() {
        FloatingControls.bounds = Box(0, 1200, 150, 1500)
        assertTrue(FloatingControls.covers(70, 1300))
        assertTrue("margin", FloatingControls.covers(160, 1210))
        assertFalse(FloatingControls.covers(610, 1680))
    }

    @Test
    fun noPanelNoRestriction() {
        assertFalse(FloatingControls.covers(70, 1300))
    }

    @Test
    fun thePanelsLabelsAreNotScreenText() {
        FloatingControls.bounds = Box(0, 1200, 150, 1500)
        val boxes = listOf(
            TextBox("暫停", Box(10, 1220, 140, 1300), TextSource.OCR),
            TextBox("停止", Box(10, 1350, 140, 1430), TextSource.OCR),
            TextBox("獲得金幣", Box(400, 1040, 820, 1150), TextSource.NODE)
        )
        assertEquals(listOf("獲得金幣"), FloatingControls.withoutPanel(boxes).map { it.text })
    }
}
