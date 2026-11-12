package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The matcher must be provably "the same shape?" before it is allowed to
 * decide where to tap an ad. These tests use a synthetic screen with the same
 * 1220 px width as the pc-collector recordings, so the scale logic is exercised
 * exactly as it will run on the phone.
 */
class CornerGlyphMatcherTest {

    private val width = 1220
    private val height = 2712

    private fun template(): GrayImage {
        val size = 56
        return GrayImage(size, size, ByteArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val dx = x - 28
            val dy = y - 28
            val distance = dx * dx + dy * dy
            val value = when {
                distance in 900..1200 -> 240
                distance <= 800 -> 25
                else -> 190
            }
            value.toByte()
        })
    }

    private fun screenWith(gray: GrayImage, left: Int, top: Int): GrayImage {
        val image = GrayImage.filled(width, height, 128)
        for (y in 0 until gray.height) {
            for (x in 0 until gray.width) {
                image.pixels[(top + y) * width + (left + x)] = gray.pixels[y * gray.width + x]
            }
        }
        return image
    }

    @Test
    fun templateMatchesItselfNearPerfectly() {
        val template = template()
        val matcher = CornerGlyphMatcher(listOf(GlyphTemplate("close", template)))
        val hits = matcher.find(screenWith(template, 1000, 120))
        assertEquals(1, hits.size)
        assertTrue("score was ${hits[0].score}", hits[0].score > 0.98)
        assertEquals("close", hits[0].kind)
        assertEquals(Box(1000, 120, 1056, 176), hits[0].bounds)
    }

    @Test
    fun shiftedTemplateIsStillFound() {
        val template = template()
        val matcher = CornerGlyphMatcher(listOf(GlyphTemplate("next", template)))
        val hits = matcher.find(screenWith(template, 1010, 130))
        assertEquals(1, hits.size)
        // The coarse pass is 4x downscaled and refined, so a couple of pixels of
        // slack are expected; more than that would misplace a tap.
        assertTrue("left=${hits[0].bounds.left}", abs(hits[0].bounds.left - 1010) <= 2)
        assertTrue("top=${hits[0].bounds.top}", abs(hits[0].bounds.top - 130) <= 2)
    }

    @Test
    fun blankScreenFindsNothing() {
        val matcher = CornerGlyphMatcher(listOf(GlyphTemplate("close", template())))
        assertTrue(matcher.find(GrayImage.filled(width, height, 128)).isEmpty())
        // A flat white screen has no shape either; division by zero must not
        // turn into a perfect score.
        assertTrue(matcher.find(GrayImage.filled(width, height, 255)).isEmpty())
    }

    @Test
    fun contentInTheMiddleOfTheScreenIsNeverMatched() {
        val template = template()
        val matcher = CornerGlyphMatcher(listOf(GlyphTemplate("close", template)))
        // A decorative circle in ad artwork must not become a tap target; this
        // is the 11-09/11-11 "clicked the ad and opened the store" failure.
        assertTrue(matcher.find(screenWith(template, 600, 1300)).isEmpty())
    }

    @Test
    fun cornerRegionsFollowThePcCollectorProportions() {
        val regions = CornerGeometry.cornerRegions(width, height)
        assertEquals(2, regions.size)
        // Top-right starts below the status bar; top-left starts at y=0.
        assertEquals(Box(732, 94, 1220, 596), regions[0])
        assertEquals(Box(0, 0, 488, 596), regions[1])
    }
}
