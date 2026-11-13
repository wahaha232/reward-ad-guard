package com.rewardadguard.app.collector

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An 8-bit grayscale image held in a byte array, row major.
 *
 * Template matching is deliberately independent of Android and of any imaging
 * library (integration plan §2: no OpenCV, ~30 MB saved). Everything the
 * matcher needs is `width`, `height` and the pixels, so the same code runs on a
 * phone screenshot and on a synthetic array in a JVM test.
 */
class GrayImage(val width: Int, val height: Int, val pixels: ByteArray) {

    init {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(pixels.size >= width * height) { "pixel buffer is smaller than the image" }
    }

    /** Luminance of one pixel, 0..255. */
    fun at(x: Int, y: Int): Int = pixels[y * width + x].toInt() and 0xFF

    /**
     * Bilinear resize. Used for the template pyramid; screenshots are shrunk
     * with [downscaled] instead, because averaging is the right filter when
     * throwing pixels away.
     */
    fun resized(targetWidth: Int, targetHeight: Int): GrayImage {
        val w = targetWidth.coerceAtLeast(1)
        val h = targetHeight.coerceAtLeast(1)
        if (w == width && h == height) return this
        val out = ByteArray(w * h)
        val scaleX = width.toDouble() / w
        val scaleY = height.toDouble() / h
        for (y in 0 until h) {
            val sourceY = ((y + 0.5) * scaleY - 0.5).coerceIn(0.0, (height - 1).toDouble())
            val y0 = sourceY.toInt()
            val y1 = min(y0 + 1, height - 1)
            val fy = sourceY - y0
            for (x in 0 until w) {
                val sourceX = ((x + 0.5) * scaleX - 0.5).coerceIn(0.0, (width - 1).toDouble())
                val x0 = sourceX.toInt()
                val x1 = min(x0 + 1, width - 1)
                val fx = sourceX - x0
                val top = at(x0, y0) * (1 - fx) + at(x1, y0) * fx
                val bottom = at(x0, y1) * (1 - fx) + at(x1, y1) * fx
                out[y * w + x] = (top * (1 - fy) + bottom * fy).roundToInt().coerceIn(0, 255).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    /** Box-average downscale by an integer factor; the cheap pyramid level. */
    fun downscaled(factor: Int): GrayImage {
        if (factor <= 1) return this
        val w = max(1, width / factor)
        val h = max(1, height / factor)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var count = 0
                val baseY = y * factor
                val baseX = x * factor
                for (dy in 0 until factor) {
                    val sy = baseY + dy
                    if (sy >= height) break
                    val row = sy * width
                    for (dx in 0 until factor) {
                        val sx = baseX + dx
                        if (sx >= width) break
                        sum += pixels[row + sx].toInt() and 0xFF
                        count++
                    }
                }
                out[y * w + x] = (sum / max(1, count)).toByte()
            }
        }
        return GrayImage(w, h, out)
    }

    companion object {
        fun filled(width: Int, height: Int, value: Int): GrayImage =
            GrayImage(width, height, ByteArray(width * height) { value.coerceIn(0, 255).toByte() })
    }
}

/** A template and what glyph it represents ("close", "next", "back"). */
/**
 * [fromScreenTop]: search the top-right corner from y = 0 instead of below the
 * status bar. Only for large, distinctive glyphs (Mintegral's ">" circle sits
 * at y = 45..134); small ones would match status-bar icons.
 */
data class GlyphTemplate(val kind: String, val image: GrayImage, val fromScreenTop: Boolean = false)

/** One template match: where it was found and with what confidence. */
data class GlyphHit(
    val kind: String,
    val regionIndex: Int,
    val score: Double,
    val bounds: Box
)

/**
 * Corner button templates (`X`, `>>`, `<-`) ported from pc-collector.
 *
 * Why only the corners: matching the whole screen tapped ad artwork and opened
 * the store (11-09 and 11-11 field runs). pc-collector restricts the same
 * search to the top-left and top-right bands, and this class keeps those exact
 * proportions ([CornerGeometry.cornerRegions]) so a threshold tuned there
 * still means the same thing here.
 *
 * Matching is zero-mean normalised cross-correlation, the pure-Kotlin
 * equivalent of OpenCV's `TM_CCOEFF_NORMED`. It is scale invariant (unlike
 * squared difference) and answers "the same shape?" rather than "the same
 * brightness?", which is what makes one template work across ad SDKs.
 *
 * A coarse pass on a 4x downscaled image, then a local full-resolution
 * refinement, keeps a 56x56 template search fast enough to run on every ad
 * frame without a native library.
 */
class CornerGlyphMatcher(
    private val templates: List<GlyphTemplate>,
    private val threshold: Double = CornerGeometry.THRESHOLD
) {

    private data class Candidate(val x: Int, val y: Int, val score: Double)

    fun find(image: GrayImage): List<GlyphHit> {
        val regions = CornerGeometry.cornerRegions(image.width, image.height)
        if (regions.isEmpty() || templates.isEmpty()) return emptyList()

        val shortSide = min(image.width, image.height)
        val base = shortSide.toDouble() / CornerGeometry.TEMPLATE_BASE_WIDTH
        val downscale = if (shortSide >= COARSE_DOWNSCALE_MIN_SIDE) COARSE_DOWNSCALE else 1
        val small = if (downscale > 1) image.downscaled(downscale) else image

        val found = ArrayList<GlyphHit>()
        for (template in templates) {
            for (factor in SCALES) {
                val fullSize = max(12, (template.image.width * base * factor).roundToInt())
                if (fullSize >= image.width || fullSize >= image.height) continue
                val full = Prepared(template.image.resized(fullSize, fullSize))
                val coarseSize = max(8, fullSize / downscale)
                val coarse = if (downscale > 1) {
                    Prepared(full.image.resized(coarseSize, coarseSize))
                } else {
                    full
                }

                for ((regionIndex, cornerRegion) in regions.withIndex()) {
                    // `>>` skip arrows only ever sit top-right (vision.py).
                    if (template.kind == "next" && regionIndex == 1) continue
                    val region = if (template.fromScreenTop && regionIndex == 0) {
                        Box(cornerRegion.left, 0, cornerRegion.right, cornerRegion.bottom)
                    } else {
                        cornerRegion
                    }
                    val regionBox = if (downscale > 1) {
                        Box(
                            region.left / downscale,
                            region.top / downscale,
                            region.right / downscale,
                            region.bottom / downscale
                        )
                    } else {
                        region
                    }
                    val coarseHit = best(small, coarse, regionBox, if (downscale > 1) 2 else 1) ?: continue
                    val refined = if (downscale > 1) {
                        val startX = coarseHit.x * downscale
                        val startY = coarseHit.y * downscale
                        val pad = downscale * 2
                        val window = Box(
                            max(0, startX - pad),
                            max(0, startY - pad),
                            min(image.width, startX + fullSize + pad),
                            min(image.height, startY + fullSize + pad)
                        )
                        best(image, full, window, 1)
                    } else {
                        coarseHit
                    } ?: continue

                    if (refined.score < threshold) continue
                    found.add(
                        GlyphHit(
                            kind = template.kind,
                            regionIndex = regionIndex,
                            score = refined.score,
                            bounds = Box(refined.x, refined.y, refined.x + fullSize, refined.y + fullSize)
                        )
                    )
                }
            }
        }

        // One button per area: the same X is often matched by two scales.
        val result = ArrayList<GlyphHit>()
        for (hit in found.sortedByDescending { it.score }) {
            val duplicate = result.any {
                kotlin.math.abs(it.bounds.centerX - hit.bounds.centerX) < DUPLICATE_RADIUS_PX &&
                    kotlin.math.abs(it.bounds.centerY - hit.bounds.centerY) < DUPLICATE_RADIUS_PX
            }
            if (!duplicate) result.add(hit)
        }
        return result
    }

    /**
     * Best match of [template] inside an arbitrary region (the "←" of an
     * install card). pc-collector's `find_template` uses 0.85/1.0/1.15 scales
     * and a 0.85 threshold with no corner restriction; the same coarse pass and
     * local refinement keep it affordable on a phone.
     */
    fun findInRegion(
        image: GrayImage,
        template: GlyphTemplate,
        region: Box,
        threshold: Double = 0.85
    ): GlyphHit? {
        if (region.width <= 0 || region.height <= 0) return null
        val factor = when {
            region.area >= 200_000 -> 4
            region.area >= 40_000 -> 2
            else -> 1
        }
        val small = if (factor > 1) image.downscaled(factor) else image
        val smallRegion = Box(
            region.left / factor,
            region.top / factor,
            region.right / factor,
            region.bottom / factor
        )
        var bestHit: Candidate? = null
        var bestSize = 0
        for (scale in SCALES) {
            val size = max(12, (template.image.width * scale).roundToInt())
            if (size >= image.width || size >= image.height) continue
            val full = Prepared(template.image.resized(size, size))
            val coarse = if (factor > 1) {
                Prepared(full.image.resized(max(8, size / factor), max(8, size / factor)))
            } else {
                full
            }
            val coarseHit = best(small, coarse, smallRegion, 2) ?: continue
            val startX = coarseHit.x * factor
            val startY = coarseHit.y * factor
            val pad = factor * 2
            val window = Box(
                max(0, startX - pad),
                max(0, startY - pad),
                min(image.width, startX + size + pad),
                min(image.height, startY + size + pad)
            )
            val refined = best(image, full, window, 1) ?: continue
            if (bestHit == null || refined.score > bestHit.score) {
                bestHit = refined
                bestSize = size
            }
        }
        val hit = bestHit ?: return null
        if (hit.score < threshold) return null
        return GlyphHit(template.kind, -1, hit.score, Box(hit.x, hit.y, hit.x + bestSize, hit.y + bestSize))
    }

    /** Highest NCC score inside [region], or null when the template cannot fit. */
    private fun best(image: GrayImage, template: Prepared, region: Box, stride: Int): Candidate? {
        val maxX = region.right - template.width
        val maxY = region.bottom - template.height
        if (maxX < region.left || maxY < region.top) return null
        var bestX = region.left
        var bestY = region.top
        var bestScore = -1.0
        var y = region.top
        while (y <= maxY) {
            var x = region.left
            while (x <= maxX) {
                val score = template.scoreAt(image, x, y)
                if (score > bestScore) {
                    bestScore = score
                    bestX = x
                    bestY = y
                }
                x += stride
            }
            y += stride
        }
        return Candidate(bestX, bestY, bestScore)
    }

    /** A template plus the sums NCC needs, computed once per scale. */
    private class Prepared(val image: GrayImage) {

        val width: Int = image.width
        val height: Int = image.height
        private val count: Double = (width * height).toDouble()
        private val sum: Double
        private val variance: Double

        init {
            var s = 0.0
            var sq = 0.0
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val value = image.at(x, y).toDouble()
                    s += value
                    sq += value * value
                }
            }
            sum = s
            variance = sq - (s * s) / count
        }

        /**
         * Zero-mean normalised cross-correlation of this template with the
         * image window at (originX, originY). A flat window or flat template has
         * no shape and scores 0 instead of dividing by zero.
         */
        fun scoreAt(image: GrayImage, originX: Int, originY: Int): Double {
            var sumImage = 0.0
            var sumImageSq = 0.0
            var sumProduct = 0.0
            for (ty in 0 until height) {
                val row = (originY + ty) * image.width + originX
                val templateRow = ty * width
                for (tx in 0 until width) {
                    val pixel = (image.pixels[row + tx].toInt() and 0xFF).toDouble()
                    val templatePixel = (this.image.pixels[templateRow + tx].toInt() and 0xFF).toDouble()
                    sumImage += pixel
                    sumImageSq += pixel * pixel
                    sumProduct += pixel * templatePixel
                }
            }
            val imageVariance = sumImageSq - (sumImage * sumImage) / count
            if (imageVariance <= 1e-6 || variance <= 1e-6) return 0.0
            val covariance = sumProduct - (sumImage * sum) / count
            return covariance / sqrt(imageVariance * variance)
        }
    }

    companion object {
        /** Threshold from pc-collector's `CORNER_THRESHOLD`. */
        private const val DUPLICATE_RADIUS_PX = 40
        private const val COARSE_DOWNSCALE = 4
        private const val COARSE_DOWNSCALE_MIN_SIDE = 600
        private val SCALES = doubleArrayOf(0.85, 1.0, 1.15)
    }
}

/**
 * Where ad SDKs put their close / skip controls, copied from
 * `reward_collector/vision.py:corner_regions`.
 *
 * The numbers are empirical, not aesthetic: the right box starts below the
 * status bar because its icons produced false matches there, the left box
 * starts at y=0 because full-screen ads put their X at (37,37), and both reach
 * 22% down because a card interstitial had its X at (86,510).
 */
object CornerGeometry {

    const val TEMPLATE_BASE_WIDTH = 1220.0
    const val THRESHOLD = 0.78

    fun cornerRegions(width: Int, height: Int): List<Box> {
        if (width <= 0 || height <= 0) return emptyList()
        val portrait = height >= width
        val top = (height * (if (portrait) 0.035 else 0.0)).toInt()
        val bottom = (height * (if (portrait) 0.22 else 0.25)).toInt()
        val side = (width * 0.40).toInt()
        return listOf(
            Box(width - side, top, width, bottom),
            Box(0, 0, side, bottom)
        )
    }
}
