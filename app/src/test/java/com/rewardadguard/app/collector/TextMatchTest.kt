package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keyword rules are the part of pc-collector worth carrying over: they were
 * tuned against 33 recorded screens. These tests pin the two behaviours that
 * matter when the source of text changes from Windows OCR to a node tree.
 */
class TextMatchTest {

    @Test
    fun windowsOcrCjkSpacingIsIgnored() {
        assertEquals("關閉", TextMatch.normalize("關 閉"))
        assertEquals("收看廣告影片", TextMatch.normalize("收 看 廣 告 影 片"))
        // Punctuation survives normalisation but not the loose form.
        assertEquals("收起,獲得5倍", TextMatch.normalize("收 起 , 獲 得 5 倍"))
        assertEquals("收起獲得5倍", TextMatch.normalizeLoose("收 起 , 獲 得 5 倍"))
    }

    @Test
    fun fullWidthAndCaseAreFolded() {
        assertEquals("abc123", TextMatch.normalize("ＡＢＣ　１２３"))
        assertEquals("skipad", TextMatch.normalizeLoose("Skip Ad!"))
        assertEquals(100, TextMatch.bestTermsMatch("ＣＬＯＳＥ", listOf("close")))
    }

    @Test
    fun closeLabelMatchesDespiteSpacesAndPunctuation() {
        assertEquals(100, TextMatch.bestTermsMatch("關 閉", listOf("關閉")))
        assertEquals(100, TextMatch.bestTermsMatch("「關閉廣告」", listOf("關閉廣告")))
    }

    @Test
    fun scoringMatchesPcCollector() {
        assertEquals(100, TextMatch.bestTermsMatch("收看廣告影片", listOf("收看廣告影片")))
        assertEquals(70, TextMatch.bestTermsMatch("收看廣告影片", listOf("收看")))
        assertEquals(50, TextMatch.bestTermsMatch("今日收看廣告影片", listOf("收看")))
        assertEquals(0, TextMatch.bestTermsMatch("每日簽到", listOf("收看")))
    }

    @Test
    fun avoidVetoesAWholeLabel() {
        assertEquals(
            0,
            TextMatch.bestTermsMatch("立即下載", listOf("下載"), avoid = listOf("下載"))
        )
        assertEquals(
            0,
            TextMatch.bestTermsMatch("立即下載", listOf("下載"), avoid = listOf("立即下載"))
        )
        assertEquals(
            100,
            TextMatch.bestTermsMatch("下載", listOf("下載"), avoid = listOf("安裝"))
        )
    }

    @Test
    fun exactTermsOnlyCountAsWholeLabels() {
        // A lone "x" must not match "x5" or every label containing that letter;
        // that is how pc-collector stopped clicking ad artwork.
        assertEquals(0, TextMatch.bestTermsMatch("x5", listOf("x"), exactTerms = listOf("x")))
        assertEquals(100, TextMatch.bestTermsMatch("x", listOf("x"), exactTerms = listOf("x")))
        assertEquals(
            50,
            TextMatch.bestTermsMatch("x close", listOf("close"), exactTerms = listOf("x"))
        )
    }

    @Test
    fun snapshotFindSkipsAvoidedBoxes() {
        val snapshot = Snapshot(
            width = 1220,
            height = 2712,
            capturedAt = 0L,
            foregroundPackage = "com.bitwalk",
            foregroundActivity = null,
            boxes = listOf(
                TextBox("立即下載", Box(0, 0, 100, 50), TextSource.NODE),
                TextBox("收看廣告影片", Box(0, 100, 300, 150), TextSource.NODE)
            )
        )
        val match = snapshot.find(listOf("下載", "收看廣告影片"), avoid = listOf("下載"))
        assertTrue(match != null)
        assertEquals("收看廣告影片", match!!.text)
        assertFalse(snapshot.matches(listOf("下載"), avoid = listOf("下載")).isNotEmpty())
    }
}
