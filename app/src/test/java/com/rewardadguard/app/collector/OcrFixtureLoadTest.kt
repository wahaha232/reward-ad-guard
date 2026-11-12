package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every recorded pc-collector screen must load into a [Snapshot].
 *
 * These 33 `*.ocr.json` files are the whole regression corpus for the per-app
 * rules (integration plan §3). Proving they load with the right text and pixel
 * coordinates is what lets phase 2 test BitWalk decisions on the JVM instead of
 * spending a rewarded ad per attempt.
 */
class OcrFixtureLoadTest {

    private fun fixtureDirectory(): File {
        val url = requireNotNull(javaClass.classLoader?.getResource("collector-fixtures")) {
            "collector-fixtures is missing from test resources"
        }
        return File(url.toURI())
    }

    private fun fixtures(): List<File> {
        val files = fixtureDirectory().listFiles { file -> file.name.endsWith(".ocr.json") }
            ?.sortedBy { it.name }
            .orEmpty()
        assertTrue("expected the recorded OCR corpus, found ${files.size} files", files.size >= 30)
        return files
    }

    @Test
    fun everyFixtureLoadsWithTextAndCoordinates() {
        val files = fixtures()
        for (file in files) {
            val snapshot = OcrJson.parse(file.readText(), capturedAt = 123L)
            assertTrue("${file.name}: width", snapshot.width > 0)
            assertTrue("${file.name}: height", snapshot.height > 0)
            assertEquals(123L, snapshot.capturedAt)
            // `line_ad_topleft_x` is an ad made only of artwork - it exists to
            // prove corner matching, so an empty OCR result is correct there.
            if (file.name != "line_ad_topleft_x.ocr.json") {
                assertTrue("${file.name}: no text boxes", snapshot.boxes.isNotEmpty())
            }
            assertTrue("${file.name}: wrong source", snapshot.boxes.all { it.source == TextSource.OCR })
            for (box in snapshot.boxes) {
                assertTrue(
                    "${file.name}: negative origin ${box.bounds}",
                    box.bounds.left >= 0 && box.bounds.top >= 0
                )
                assertTrue(
                    "${file.name}: outside ${snapshot.width}x${snapshot.height}: ${box.bounds}",
                    box.bounds.right <= snapshot.width && box.bounds.bottom <= snapshot.height
                )
            }
        }
    }

    @Test
    fun bitwalkHomeFixtureCarriesTheRewardText() {
        val file = File(fixtureDirectory(), "app_bitwalk_get_stamp.ocr.json")
        assertTrue("fixture missing", file.isFile)
        val snapshot = OcrJson.parse(file.readText())

        assertEquals(1220, snapshot.width)
        assertEquals(2712, snapshot.height)
        assertTrue(snapshot.contains("可兌換 49 個印章"))
        assertTrue(snapshot.contains("收看廣告影片"))
        assertTrue(snapshot.contains("步行"))

        val reward = snapshot.findLine("可 兌 換 49 個 印 章")
        assertNotNull(reward)
        assertEquals(Box(418, 1436, 802, 1481), reward!!.bounds)

        // The clock line proves line-level coordinates survive the JSON round trip.
        val clock = snapshot.findLine("13 : 16")
        assertNotNull(clock)
        assertEquals(Box(83, 38, 375, 71), clock!!.bounds)
    }

    @Test
    fun matchesUseScoreThenArea() {
        val file = File(fixtureDirectory(), "app_bitwalk_get_stamp.ocr.json")
        val snapshot = OcrJson.parse(file.readText())
        val matches = snapshot.matches(listOf("印章"))
        assertTrue(matches.size >= 2)
        // A label that *starts* with the term (70) outranks one that merely
        // contains it (50), exactly like pc-collector's best_terms_match.
        assertTrue(matches.first().text.startsWith("印 章 重 置"))
        assertTrue(matches.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertEquals(matches.size, matches.distinctBy { it.bounds to it.text }.size)
    }
}
