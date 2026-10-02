package com.rewardadguard.app.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the ad [X] heuristic (spec sections 24-27).
 *
 * [CloseButtonDetector] works on plain [NodeSnapshot] values, so no device and
 * no instrumentation are required.
 */
class CloseButtonDetectorTest {

    private fun node(
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        className: String? = "android.widget.ImageView",
        clickable: Boolean = true,
        left: Int = 940,
        top: Int = 60,
        right: Int = 1020,
        bottom: Int = 140,
        visible: Boolean = true
    ) = NodeSnapshot(
        text = text,
        contentDescription = desc,
        viewIdResourceName = viewId,
        className = className,
        clickable = clickable,
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        visible = visible
    )

    // ------------------------------------------------------------ geometry

    @Test
    fun `node dimensions and area are derived from bounds`() {
        val n = node(left = 10, top = 20, right = 110, bottom = 70)
        assertEquals(100, n.width)
        assertEquals(50, n.height)
        assertEquals(5000, n.area)
    }

    @Test
    fun `inverted bounds never produce a negative area`() {
        val n = node(left = 200, top = 200, right = 100, bottom = 100)
        assertEquals(0, n.width)
        assertEquals(0, n.height)
        assertEquals(0, n.area)
    }

    // ------------------------------------------------------------ matching

    @Test
    fun `corner glyph x is matched`() {
        val match = CloseButtonDetector.score(node(text = "x"), 1080, 2400)
        assertTrue("expected the corner X to match, got ${match.method}", match.matched)
        assertTrue(match.score >= CloseButtonDetector.MATCH_THRESHOLD)
    }

    @Test
    fun `multiplication sign glyph is matched`() {
        assertTrue(CloseButtonDetector.score(node(text = "\u00D7"), 1080, 2400).matched)
    }

    @Test
    fun `localized close labels are matched`() {
        assertTrue(CloseButtonDetector.score(node(text = "關閉"), 1080, 2400).matched)
        assertTrue(CloseButtonDetector.score(node(text = "跳過"), 1080, 2400).matched)
    }

    @Test
    fun `english close keyword is matched`() {
        assertTrue(CloseButtonDetector.score(node(text = "Close"), 1080, 2400).matched)
        assertTrue(CloseButtonDetector.score(node(text = "Skip Ad"), 1080, 2400).matched)
    }

    @Test
    fun `content description alone is enough`() {
        val match = CloseButtonDetector.score(
            node(desc = "Close ad", className = "android.widget.ImageButton"),
            1080,
            2400
        )
        assertTrue(match.matched)
        assertEquals("DESCRIPTION_KEYWORD", match.method)
    }

    @Test
    fun `close button view id contributes to the score`() {
        val withId = CloseButtonDetector.score(
            node(viewId = "com.example.ad:id/iv_close"),
            1080,
            2400
        )
        val withoutId = CloseButtonDetector.score(node(), 1080, 2400)
        assertTrue(withId.score > withoutId.score)
    }

    @Test
    fun `invisible node is rejected`() {
        val match = CloseButtonDetector.score(node(text = "x", visible = false), 1080, 2400)
        assertFalse(match.matched)
        assertEquals("INVISIBLE", match.method)
    }

    @Test
    fun `zero sized node is rejected`() {
        val match = CloseButtonDetector.score(node(text = "x", left = 5, right = 5), 1080, 2400)
        assertFalse(match.matched)
        assertEquals("ZERO_BOUNDS", match.method)
    }

    @Test
    fun `unrelated text in the middle of the screen is rejected`() {
        val match = CloseButtonDetector.score(
            node(text = "Watch this video to earn coins", left = 300, top = 1100, right = 780, bottom = 1250),
            1080,
            2400
        )
        assertFalse(match.matched)
        assertTrue(match.method.startsWith("REJECTED_SCORE_"))
    }

    @Test
    fun `full screen clickable close call to action is not the X`() {
        val match = CloseButtonDetector.score(
            node(text = "Close", left = 0, top = 0, right = 1080, bottom = 2400),
            1080,
            2400
        )
        assertFalse("a full-screen CTA must not be treated as the X", match.matched)
    }

    // ------------------------------------------------------------ expansion

    @Test
    fun `scale one keeps the original bounds untouched`() {
        val n = node(left = 900, top = 50, right = 1000, bottom = 150)
        val bounds = CloseButtonDetector.expandedBounds(n, 1f)
        assertEquals(900, bounds[0])
        assertEquals(50, bounds[1])
        assertEquals(1000, bounds[2])
        assertEquals(150, bounds[3])
    }

    @Test
    fun `expansion grows around the centre and keeps the node inside`() {
        val n = node(left = 940, top = 60, right = 1020, bottom = 140)
        val bounds = CloseButtonDetector.expandedBounds(n, 3f)

        assertTrue("expanded left must move outwards", bounds[0] <= n.left)
        assertTrue("expanded top must move outwards", bounds[1] <= n.top)
        assertTrue("expanded right must move outwards", bounds[2] >= n.right)
        assertTrue("expanded bottom must move outwards", bounds[3] >= n.bottom)
        assertTrue(bounds[2] - bounds[0] >= n.width)
        assertTrue(bounds[3] - bounds[1] >= n.height)
        // 80x80 node scaled 3x -> 240x240 box centred on (980, 100); the top is
        // pushed back to y=0 so the visible height is 220, not 240.
        assertEquals(240, bounds[2] - bounds[0])
        assertEquals(220, bounds[3] - bounds[1])
        assertEquals(0, bounds[1])
        assertEquals(980, (bounds[0] + bounds[2]) / 2)
        assertEquals(860, bounds[0])
        assertEquals(1100, bounds[2])
        assertEquals(220, bounds[3])
        assertEquals(80, n.height)
    }

    @Test
    fun `expansion is centred on the original node`() {
        val n = node(left = 100, top = 200, right = 200, bottom = 300)
        val bounds = CloseButtonDetector.expandedBounds(n, 3f)
        assertEquals((n.left + n.right) / 2, (bounds[0] + bounds[2]) / 2)
        assertEquals((n.top + n.bottom) / 2, (bounds[1] + bounds[3]) / 2)
    }

    @Test
    fun `expansion never leaves the screen on the top left`() {
        val n = node(left = 0, top = 0, right = 40, bottom = 40)
        val bounds = CloseButtonDetector.expandedBounds(n, 5f)
        assertTrue(bounds[0] >= 0)
        assertTrue(bounds[1] >= 0)
    }

    @Test
    fun `expansion scale is clamped to the supported range`() {
        val n = node(left = 100, top = 100, right = 200, bottom = 200)
        val clampedHigh = CloseButtonDetector.expandedBounds(n, 99f)
        val atFive = CloseButtonDetector.expandedBounds(n, 5f)
        assertTrue(clampedHigh.contentEquals(atFive))

        val clampedLow = CloseButtonDetector.expandedBounds(n, 0.1f)
        assertEquals(n.left, clampedLow[0])
        assertEquals(n.right, clampedLow[2])
    }

    @Test
    fun `a matched result carries the suggested click target`() {
        val match = CloseButtonDetector.score(node(text = "X"), 1080, 2400)
        assertNotNull(match.suggestedExpandedBounds)
        // 80x80 at (940,60)-(1020,140) scaled 3x, clamped to the top edge.
        assertEquals("240x220", match.boundsLabel())
    }

    @Test
    fun `an unmatched result carries no click target`() {
        val match = CloseButtonDetector.score(
            node(text = "Earn coins", left = 300, top = 1100, right = 780, bottom = 1250),
            1080,
            2400
        )
        assertNull(match.suggestedExpandedBounds)
        assertEquals("-", match.boundsLabel())
    }

    // ------------------------------------------------------------ best match

    @Test
    fun `best match picks the highest scoring candidate`() {
        val weak = node(
            viewId = "com.example.ad:id/btn_close",
            className = "android.widget.Button",
            left = 400, top = 800, right = 700, bottom = 900
        )
        val strong = node(text = "x")
        val best = CloseButtonDetector.bestMatch(listOf(weak, strong), 1080, 2400)
        assertNotNull(best)
        assertEquals(strong, best!!.node)
    }

    @Test
    fun `best match returns null when nothing looks like a close control`() {
        val a = node(text = "Earn 100 coins", left = 200, top = 900, right = 880, bottom = 1000)
        val b = node(text = "Watch", left = 200, top = 1100, right = 880, bottom = 1200)
        assertNull(CloseButtonDetector.bestMatch(listOf(a, b), 1080, 2400))
    }

    @Test
    fun `best match on an empty tree returns null`() {
        assertNull(CloseButtonDetector.bestMatch(emptyList(), 1080, 2400))
    }

    @Test
    fun `bounds label reports the label for the expanded bounds`() {
        val n = node(left = 100, top = 100, right = 200, bottom = 200)
        val b = CloseButtonDetector.expandedBounds(n, 3f)
        val match = CloseMatch(true, 60, "SYMBOL_TEXT", n, b)
        assertEquals("${b[2] - b[0]}x${b[3] - b[1]}", match.boundsLabel())
    }
}
