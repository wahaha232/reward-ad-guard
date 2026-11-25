package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * 2026-11-13 14:54: an InMobi ad in BITWALK ended in landscape with only a
 * bare "▶|" at the top right. Neither the X nor the circled ">>" template
 * matched it, so the run called the ad stuck again and again.
 *
 * Fixtures are the top-right corner of real screenshots stored as gzipped
 * gray bytes (the JVM test runtime has no image decoder): screen width and
 * height, the crop's origin and size, then the pixels.
 */
class SkipBarGlyphTest {

    private fun resource(path: String): File =
        File(requireNotNull(javaClass.classLoader?.getResource(path)) { "missing $path" }.toURI())

    /** Rebuilds the full screen: mid-gray everywhere, the real corner pasted in. */
    private fun screen(path: String): GrayImage =
        DataInputStream(GZIPInputStream(resource(path).inputStream())).use { input ->
            val width = input.readInt()
            val height = input.readInt()
            val left = input.readInt()
            val top = input.readInt()
            val cropWidth = input.readInt()
            val cropHeight = input.readInt()
            val crop = ByteArray(cropWidth * cropHeight)
            input.readFully(crop)
            val image = GrayImage.filled(width, height, 128)
            for (y in 0 until cropHeight) {
                for (x in 0 until cropWidth) {
                    image.pixels[(top + y) * width + (left + x)] = crop[y * cropWidth + x]
                }
            }
            image
        }

    /** The app's asset, decoded from its PNG (8-bit gray, as written by the build tooling). */
    private val skipTemplate: GlyphTemplate by lazy {
        val asset = listOf(
            File("src/main/assets/templates/skip_bar.png"),
            File("app/src/main/assets/templates/skip_bar.png")
        ).first { it.isFile }
        GlyphTemplate("next", decodeGrayPng(asset.readBytes()))
    }

    @Test
    fun theBareSkipIconIsFoundTopRight() {
        val hits = CornerGlyphMatcher(listOf(skipTemplate)).find(screen("glyph-fixtures/inmobi_skip_landscape.gray.gz"))
        val hit = hits.firstOrNull { it.kind == "next" }
        assertTrue("hits=$hits", hit != null)
        // The icon's centre is at about (2540, 92) on the 2712x1220 screen.
        assertTrue("$hit", kotlin.math.abs(hit!!.bounds.centerX - 2540) < 30)
        assertTrue("$hit", kotlin.math.abs(hit.bounds.centerY - 92) < 30)
    }

    private fun template(name: String): GlyphTemplate {
        val asset = listOf(
            File("src/main/assets/templates/$name"),
            File("app/src/main/assets/templates/$name")
        ).first { it.isFile }
        return GlyphTemplate(
            if (name.contains("close")) "close" else "next",
            decodeGrayPng(asset.readBytes()),
            fromScreenTop = name in setOf("chevron_circle.png", "thin_chevron.png", "thin_close.png")
        )
    }

    @Test
    fun mintegralsThinChevronIsFoundTopRight() {
        // 2026-11-16 11:28: a bare ">" at about (1132, 88), level with the
        // status bar; the ad waited for it until it timed out.
        val hits = CornerGlyphMatcher(listOf(template("thin_chevron.png")))
            .find(screen("glyph-fixtures/mintegral_thin_chevron_portrait.gray.gz"))
        val hit = hits.firstOrNull { it.kind == "next" }
        assertTrue("hits=$hits", hit != null)
        assertTrue("$hit", kotlin.math.abs(hit!!.bounds.centerX - 1132) < 30)
        assertTrue("$hit", kotlin.math.abs(hit.bounds.centerY - 88) < 30)
    }

    @Test
    fun mintegralsThinCloseIsFoundTopRight() {
        // The same ad's end card: a bare "X" at about (1130, 90).
        val hits = CornerGlyphMatcher(listOf(template("thin_close.png")))
            .find(screen("glyph-fixtures/mintegral_thin_close_portrait.gray.gz"))
        val hit = hits.firstOrNull { it.kind == "close" }
        assertTrue("hits=$hits", hit != null)
        assertTrue("$hit", kotlin.math.abs(hit!!.bounds.centerX - 1130) < 30)
        assertTrue("$hit", kotlin.math.abs(hit.bounds.centerY - 90) < 30)
    }

    @Test
    fun thinTemplatesLeaveOtherScreensAlone() {
        val matcher = CornerGlyphMatcher(listOf(template("thin_chevron.png"), template("thin_close.png")))
        for (fixture in listOf(
            "glyph-fixtures/inmobi_skip_to_play_landscape.gray.gz",
            "glyph-fixtures/line_splash_portrait.gray.gz"
        )) {
            val hits = matcher.find(screen(fixture))
            assertFalse("$fixture hits=$hits", hits.isNotEmpty())
        }
    }

    @Test
    fun mintegralsChevronIsFoundTopRight() {
        // 2026-11-13 16:58: the "WI-FI 獎勵" playable ad; its ">" circle at
        // about (1128, 89) was the only way out.
        val hits = CornerGlyphMatcher(listOf(template("chevron_circle.png")))
            .find(screen("glyph-fixtures/mintegral_chevron_portrait.gray.gz"))
        val hit = hits.firstOrNull { it.kind == "next" }
        assertTrue("hits=$hits", hit != null)
        assertTrue("$hit", kotlin.math.abs(hit!!.bounds.centerX - 1128) < 40)
        assertTrue("$hit", kotlin.math.abs(hit.bounds.centerY - 89) < 40)
    }

    @Test
    fun skipToGooglePlayIsNeverASkipButton() {
        // 16:11: the only control was "» Skip to Google Play", which opens the
        // store. Neither new template may take it for a skip.
        val screen = screen("glyph-fixtures/inmobi_skip_to_play_landscape.gray.gz")
        val hits = CornerGlyphMatcher(listOf(skipTemplate, template("chevron_circle.png"))).find(screen)
        assertFalse("hits=$hits", hits.any { it.kind == "next" })
    }

    @Test
    fun anOrdinaryAppScreenHasNoSkipIcon() {
        // Includes the status bar: the chevron searches from the screen top.
        val matcher = CornerGlyphMatcher(listOf(skipTemplate, template("chevron_circle.png")))
        val hits = matcher.find(screen("glyph-fixtures/line_splash_portrait.gray.gz"))
        assertFalse("hits=$hits", hits.any { it.kind == "next" })
    }

    /** Minimal PNG reader for 8-bit grayscale, non-interlaced images. */
    private fun decodeGrayPng(bytes: ByteArray): GrayImage {
        var pos = 8
        var width = 0
        var height = 0
        val idat = java.io.ByteArrayOutputStream()
        fun int(at: Int) = ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)
        while (pos < bytes.size) {
            val length = int(pos)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val data = pos + 8
            when (type) {
                "IHDR" -> {
                    width = int(data)
                    height = int(data + 4)
                    check(bytes[data + 8].toInt() == 8 && bytes[data + 9].toInt() == 0) { "expected 8-bit gray" }
                }
                "IDAT" -> idat.write(bytes, data, length)
                "IEND" -> break
            }
            pos = data + length + 4
        }
        val inflater = Inflater().apply { setInput(idat.toByteArray()) }
        val raw = ByteArray((width + 1) * height)
        var read = 0
        while (read < raw.size) read += inflater.inflate(raw, read, raw.size - read)
        val out = ByteArray(width * height)
        val prev = IntArray(width)
        for (y in 0 until height) {
            val filter = raw[y * (width + 1)].toInt()
            for (x in 0 until width) {
                val v = raw[y * (width + 1) + 1 + x].toInt() and 0xFF
                val a = if (x > 0) out[y * width + x - 1].toInt() and 0xFF else 0
                val b = prev[x]
                val c = if (x > 0) prev[x - 1] else 0
                val value = when (filter) {
                    0 -> v
                    1 -> v + a
                    2 -> v + b
                    3 -> v + (a + b) / 2
                    4 -> {
                        val p = a + b - c
                        val pa = kotlin.math.abs(p - a)
                        val pb = kotlin.math.abs(p - b)
                        val pc = kotlin.math.abs(p - c)
                        v + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> error("filter $filter")
                } and 0xFF
                out[y * width + x] = value.toByte()
            }
            for (x in 0 until width) prev[x] = out[y * width + x].toInt() and 0xFF
        }
        return GrayImage(width, height, out)
    }

    @Test
    fun inmobisOutlinedCloseIsFoundTopLeft() {
        // 2026-11-17 09:20: a dark X in a thin ring on a white card at about (86, 496).
        val hits = CornerGlyphMatcher(listOf(template("outline_close.png")))
            .find(screen("glyph-fixtures/inmobi_outline_close_portrait.gray.gz"))
        val hit = hits.firstOrNull { it.kind == "close" }
        assertTrue("hits=$hits", hit != null)
        assertTrue("$hit", kotlin.math.abs(hit!!.bounds.centerX - 86) < 30)
        assertTrue("$hit", kotlin.math.abs(hit.bounds.centerY - 496) < 30)
    }

    @Test
    fun outlinedCloseLeavesOtherScreensAlone() {
        val matcher = CornerGlyphMatcher(listOf(template("outline_close.png")))
        for (fixture in listOf(
            "glyph-fixtures/inmobi_skip_to_play_landscape.gray.gz",
            "glyph-fixtures/line_splash_portrait.gray.gz",
            "glyph-fixtures/mintegral_thin_close_portrait.gray.gz"
        )) {
            val hits = matcher.find(screen(fixture))
            assertFalse("$fixture hits=$hits", hits.isNotEmpty())
        }
    }

    @Test
    fun fybersRingCloseIsFoundBelowTheBlackBar() {
        // BITWALK 2026-11-25 08:11 and 08:23: a Fyber image ad with a black bar
        // on top and a bold X in a thin ring at about (1132, 421). No template
        // matched it; the run tapped elsewhere and waited 60 s twice. 07:58: the
        // same X on another Fyber ad; tapping elsewhere opened Shopee.
        val matcher = CornerGlyphMatcher(listOf(template("ring_close.png")))
        for ((fixture, y) in listOf(
            "glyph-fixtures/fyber_ring_close_portrait.gray.gz" to 421,
            "glyph-fixtures/fyber_ring_close_shopee_portrait.gray.gz" to 356
        )) {
            val hit = matcher.find(screen(fixture)).firstOrNull { it.kind == "close" }
            assertTrue("$fixture: no hit", hit != null)
            assertTrue("$fixture $hit", kotlin.math.abs(hit!!.bounds.centerX - 1132) < 30)
            assertTrue("$fixture $hit", kotlin.math.abs(hit.bounds.centerY - y) < 30)
        }
    }

    @Test
    fun ringCloseLeavesOtherScreensAlone() {
        val matcher = CornerGlyphMatcher(listOf(template("ring_close.png")))
        for (fixture in listOf(
            "glyph-fixtures/inmobi_skip_to_play_landscape.gray.gz",
            "glyph-fixtures/line_splash_portrait.gray.gz",
            "glyph-fixtures/mintegral_thin_close_portrait.gray.gz",
            "glyph-fixtures/mintegral_chevron_portrait.gray.gz",
            "glyph-fixtures/inmobi_shopee_video_portrait.gray.gz"
        )) {
            val hits = matcher.find(screen(fixture))
            assertFalse("$fixture hits=$hits", hits.isNotEmpty())
        }
    }
}
