package com.rewardadguard.app.collector

import android.content.Context

/**
 * Phrase fixes for ML Kit's Traditional Chinese misreads (2026-11-16), from
 * `assets/ocr_corrections.tsv` (wrong<TAB>right). Applied to OCR text only;
 * node text is exact. Phrases rather than characters: 搜 is right in 搜尋 and
 * wrong only in 搜物卡. New pairs come from `TOOLS/ocr_calibrate.py`, which
 * compares calibration captures with Windows OCR (zh-Hant-TW).
 */
object OcrCorrections {

    const val ASSET = "ocr_corrections.tsv"

    @Volatile
    private var pairs: List<Pair<String, String>>? = null

    fun load(context: Context): List<Pair<String, String>> = pairs ?: synchronized(this) {
        pairs ?: runCatching {
            context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { parse(it.readText()) }
        }.getOrDefault(emptyList()).also { pairs = it }
    }

    fun parse(text: String): List<Pair<String, String>> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val parts = line.split('\t')
            val wrong = parts.getOrNull(0)?.trim().orEmpty()
            val right = parts.getOrNull(1)?.trim().orEmpty()
            if (wrong.isEmpty() || right.isEmpty() || wrong == right) null else wrong to right
        }
        // Longest first, so a longer phrase wins over a shorter one inside it.
        .sortedByDescending { it.first.length }

    fun apply(text: String, pairs: List<Pair<String, String>>): String {
        var value = text
        for ((wrong, right) in pairs) {
            if (wrong in value) value = value.replace(wrong, right)
        }
        return value
    }
}
