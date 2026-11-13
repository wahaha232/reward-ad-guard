package com.rewardadguard.app.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.roundToInt

/**
 * Loads pc-collector's three corner templates from `assets/templates` and runs
 * [CornerGlyphMatcher] on a screenshot.
 *
 * The images are the same files the PC script uses (`close_circle.png`,
 * `next_circle.png`, `back_arrow.png`), copied verbatim. Keeping one source of
 * truth means a template improved on the PC can be dropped in here without
 * retuning the threshold.
 *
 * Android-only because decoding a PNG needs `BitmapFactory`; the matching
 * itself stays in the pure Kotlin class so the tests do not need a device.
 */
class CornerGlyphDetector(private val context: Context) {

    private val templates: List<GlyphTemplate> by lazy { loadTemplates() }

    fun find(screenshot: Bitmap): List<GlyphHit> {
        if (screenshot.width <= 0 || screenshot.height <= 0) return emptyList()
        return CornerGlyphMatcher(templates).find(grayImage(screenshot))
    }

    /**
     * Per corner region, the best score of every template, plus where it was.
     * The debug command reports this so a field run shows *why* nothing matched
     * (e.g. "best 0.61, below 0.78") instead of an empty list.
     */
    fun bestPerRegion(screenshot: Bitmap): List<RegionBest> {
        val gray = grayImage(screenshot)
        val matcher = CornerGlyphMatcher(templates, threshold = 0.0)
        val hits = matcher.find(gray)
        val regions = CornerGeometry.cornerRegions(gray.width, gray.height)
        return regions.mapIndexed { index, region ->
            val best = hits.filter { it.regionIndex == index }.maxByOrNull { it.score }
            RegionBest(regionIndex = index, region = region, hit = best)
        }
    }

    /** The install card's "←", searched only in the region above "Install". */
    fun findBackArrow(screenshot: Bitmap, region: Box): GlyphHit? {
        val template = templates.firstOrNull { it.kind == "back" } ?: return null
        return CornerGlyphMatcher(templates).findInRegion(grayImage(screenshot), template, region, 0.85)
    }

    private fun loadTemplates(): List<GlyphTemplate> = TEMPLATE_FILES.mapNotNull { (kind, path) ->
        runCatching {
            context.assets.open(path).use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream) ?: return@use null
                GlyphTemplate(kind, grayImage(bitmap))
            }
        }.getOrNull()
    }

    private fun grayImage(bitmap: Bitmap): GrayImage {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val gray = ByteArray(width * height)
        for (index in pixels.indices) {
            val color = pixels[index]
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            // ITU-R BT.601 weights, the same luminance OpenCV's IMREAD_GRAYSCALE
            // uses, so scores stay comparable with the PC recordings.
            val luma = (0.299 * r + 0.587 * g + 0.114 * b).roundToInt().coerceIn(0, 255)
            gray[index] = luma.toByte()
        }
        return GrayImage(width, height, gray)
    }

    companion object {
        private val TEMPLATE_FILES = listOf(
            "next" to "templates/next_circle.png",
            // The bare "▶|" skip of InMobi / Fyber ads, cut from a BITWALK
            // screen (2026-11-13 14:55) where nothing else could close it.
            "next" to "templates/skip_bar.png",
            "close" to "templates/close_circle.png",
            "back" to "templates/back_arrow.png"
        )
    }
}

/** Best template match in one corner region; [hit] is null when none scored. */
data class RegionBest(val regionIndex: Int, val region: Box, val hit: GlyphHit?)
