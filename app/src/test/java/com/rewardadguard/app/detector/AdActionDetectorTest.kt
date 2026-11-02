package com.rewardadguard.app.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `[close]` / `[>>]` / `[->]` detector must be *exact*: only a whole label
 * spelled as one of the tokens may be clicked. Every test that asserts "no
 * match" is a normal app's text that must never be automated.
 */
class AdActionDetectorTest {

    private fun node(
        text: String? = null,
        description: String? = null,
        left: Int = 900,
        top: Int = 120,
        right: Int = 980,
        bottom: Int = 200,
        visible: Boolean = true
    ) = NodeSnapshot(
        text = text,
        contentDescription = description,
        viewIdResourceName = null,
        className = "android.widget.TextView",
        clickable = false,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        visible = visible
    )

    @Test
    fun `the close label is recognised case-insensitively`() {
        assertEquals(AdActionKind.CLOSE, AdActionDetector.classify("[close]"))
        assertEquals(AdActionKind.CLOSE, AdActionDetector.classify("[CLOSE]"))
        assertEquals(AdActionKind.CLOSE, AdActionDetector.classify("  [Close]  "))
    }

    @Test
    fun `both next-step labels are recognised`() {
        assertEquals(AdActionKind.NEXT, AdActionDetector.classify("[>>]"))
        assertEquals(AdActionKind.NEXT, AdActionDetector.classify("[->]"))
    }

    @Test
    fun `ordinary text is never a control`() {
        assertNull(AdActionDetector.classify("Close"))
        assertNull(AdActionDetector.classify("close"))
        assertNull(AdActionDetector.classify(">>"))
        assertNull(AdActionDetector.classify("->"))
        assertNull(AdActionDetector.classify("Tap [close] to dismiss"))
        assertNull(AdActionDetector.classify("[close] (3s)"))
        assertNull(AdActionDetector.classify("Google Play >>"))
        assertNull(AdActionDetector.classify("click [->] next"))
        assertNull(AdActionDetector.classify(null))
    }

    @Test
    fun `close wins over next when the screen shows both`() {
        val candidates = listOf(
            node(text = "[>>]"),
            node(text = "[close]", left = 20, top = 20, right = 90, bottom = 90)
        )
        val match = AdActionDetector.bestMatch(candidates, 1080, 2400)
        assertEquals(AdActionKind.CLOSE, match?.kind)
        assertEquals("[close]", match?.token)
    }

    @Test
    fun `next-step label is matched when it is the only control`() {
        val match = AdActionDetector.bestMatch(listOf(node(text = "[->]")), 1080, 2400)
        assertEquals(AdActionKind.NEXT, match?.kind)
        assertEquals("[->]", match?.token)
    }

    @Test
    fun `the smallest labelled node is chosen over its container`() {
        val container = node(text = "[>>]", left = 800, top = 100, right = 1000, bottom = 300)
        val leaf = node(text = "[>>]", left = 900, top = 180, right = 960, bottom = 220)
        val match = AdActionDetector.bestMatch(listOf(container, leaf), 1080, 2400)
        assertEquals(180, match?.node?.top)
        assertEquals(40, match?.node?.height)
    }

    @Test
    fun `content description is accepted when the text is absent`() {
        val match = AdActionDetector.bestMatch(listOf(node(description = "[close]")), 1080, 2400)
        assertEquals(AdActionKind.CLOSE, match?.kind)
    }

    @Test
    fun `invisible and zero-sized nodes are rejected`() {
        assertNull(AdActionDetector.bestMatch(listOf(node(text = "[close]", visible = false)), 1080, 2400))
        assertNull(
            AdActionDetector.bestMatch(
                listOf(node(text = "[close]", left = 10, top = 10, right = 10, bottom = 10)),
                1080,
                2400
            )
        )
    }

    @Test
    fun `a full-screen labelled container is never clicked`() {
        val fullScreen = node(text = "[>>]", left = 0, top = 0, right = 1080, bottom = 2400)
        assertNull(AdActionDetector.bestMatch(listOf(fullScreen), 1080, 2400))
    }

    @Test
    fun `isLabelled agrees with the detector`() {
        assertTrue(AdActionDetector.isLabelled(node(text = "[close]"), AdActionKind.CLOSE))
        assertTrue(AdActionDetector.isLabelled(node(text = "[->]"), AdActionKind.NEXT))
        assertTrue(!AdActionDetector.isLabelled(node(text = "[->]"), AdActionKind.CLOSE))
    }

    @Test
    fun `a button-sized target under the label is a safe tap target`() {
        assertTrue(AdActionDetector.isSafeTapTarget(120, 120, true, 1220, 2712))
    }

    /** Review 2026-11-02: a full-screen clickable ancestor is the ad itself. */
    @Test
    fun `a page-sized clickable ancestor is never tapped`() {
        assertTrue(!AdActionDetector.isSafeTapTarget(1220, 2712, true, 1220, 2712))
        val sixth = 1220 * 2712 / 6
        assertTrue(!AdActionDetector.isSafeTapTarget(1220, sixth / 1220 + 2, true, 1220, 2712))
    }

    @Test
    fun `a target that does not cover the label is not tapped`() {
        assertTrue(!AdActionDetector.isSafeTapTarget(120, 120, false, 1220, 2712))
    }

    @Test
    fun `an empty target is not tapped`() {
        assertTrue(!AdActionDetector.isSafeTapTarget(0, 120, true, 1220, 2712))
    }
}
