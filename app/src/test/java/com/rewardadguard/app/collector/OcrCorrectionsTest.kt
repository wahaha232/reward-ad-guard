package com.rewardadguard.app.collector

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class OcrCorrectionsTest {

    private val asset: String by lazy {
        listOf(File("src/main/assets/ocr_corrections.tsv"), File("app/src/main/assets/ocr_corrections.tsv"))
            .first { it.isFile }.readText()
    }

    @Test
    fun shippedTableFixesTheGiftCardMisreads() {
        val pairs = OcrCorrections.parse(asset)
        assertEquals("立即領取禮物卡", OcrCorrections.apply("立即領取搜物卡", pairs))
        assertEquals("立即領取禮物卡", OcrCorrections.apply("立即領取澧物卡", pairs))
        // A correct 搜 elsewhere is left alone.
        assertEquals("搜尋", OcrCorrections.apply("搜尋", pairs))
    }

    @Test
    fun parseSkipsCommentsAndBadLines() {
        val pairs = OcrCorrections.parse("# c\n\nab\tcd\nonly\nx\tx\nabc\tzzz")
        assertEquals(listOf("abc" to "zzz", "ab" to "cd"), pairs)
    }
}
