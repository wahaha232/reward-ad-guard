package com.rewardadguard.app.collector

/**
 * One rectangle on screen, in screenshot pixels.
 *
 * Integer pixel bounds (not float) because every source of truth here - the
 * accessibility node tree, ML Kit and the recorded pc-collector fixtures - is
 * integer, and a click needs an exact centre pixel.
 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = width * height
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun union(other: Box): Box = Box(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom)
    )

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    override fun toString(): String = "[$left,$top][$right,$bottom]"

    companion object {
        fun from(left: Int, top: Int, width: Int, height: Int): Box =
            Box(left, top, left + (width.coerceAtLeast(0)), top + (height.coerceAtLeast(0)))
    }
}

/**
 * Where a [TextBox] came from.
 *
 * The distinction matters downstream: a node box is authoritative for tapping
 * (it is the real view), while an OCR box only proves that pixels looked like
 * text. Phase 2 taps nodes whenever one exists.
 */
enum class TextSource { NODE, OCR }

/** A piece of text and the rectangle it occupies. */
data class TextBox(val text: String, val bounds: Box, val source: TextSource)

/** A match returned by [Snapshot.matches], ordered by confidence. */
data class TextBoxMatch(
    val text: String,
    val bounds: Box,
    val score: Int,
    val source: TextSource
) {
    val centerX: Int get() = bounds.centerX
    val centerY: Int get() = bounds.centerY
}

/**
 * Everything the collector knows about one moment on screen.
 *
 * The single model for both sources is deliberate (integration plan §3): the
 * 33 recorded `*.ocr.json` screens become JVM fixtures, so per-app rules can be
 * tested on a laptop before they ever touch a phone. [Source] on each box keeps
 * the provenance without a second parallel list.
 */
data class Snapshot(
    val width: Int,
    val height: Int,
    val capturedAt: Long,
    val foregroundPackage: String?,
    val foregroundActivity: String?,
    /** False on Android 10 and below, where `takeScreenshot` does not exist. */
    val ocrSupported: Boolean = true,
    val boxes: List<TextBox>,
    /** Wall time the OCR pass took, 0 when OCR did not run. */
    val ocrMillis: Long = 0L,
    /** Why OCR produced nothing, when it was expected to run. */
    val ocrError: String? = null
) {

    val text: String get() = boxes.joinToString("\n") { it.text }

    fun boxesFrom(source: TextSource): List<TextBox> = boxes.filter { it.source == source }

    fun nodeCount(): Int = boxes.count { it.source == TextSource.NODE }

    fun ocrCount(): Int = boxes.count { it.source == TextSource.OCR }

    /**
     * Every box whose text matches [terms], best first - the port of
     * `OcrResult.matches`, including the `avoid` veto and the exact-only terms.
     * Duplicate matches on identical rectangles are collapsed, because the node
     * tree and OCR usually both see the same label.
     */
    fun matches(
        terms: Iterable<String>,
        avoid: Iterable<String> = emptyList(),
        exactTerms: Iterable<String> = emptyList(),
        minScore: Int = 1
    ): List<TextBoxMatch> {
        val avoided = avoid.map(::safeNormalize).filter { it.isNotEmpty() }
        val result = ArrayList<TextBoxMatch>()
        for (box in boxes) {
            val value = TextMatch.normalizeLoose(box.text)
            if (value.isEmpty()) continue
            if (avoided.any { it in value }) continue
            val score = TextMatch.bestTermsMatch(box.text, terms, emptyList(), exactTerms)
            if (score < minScore) continue
            result.add(TextBoxMatch(box.text, box.bounds, score, box.source))
        }
        result.sortWith(compareByDescending<TextBoxMatch> { it.score }.thenByDescending { it.bounds.area })
        val deduped = ArrayList<TextBoxMatch>(result.size)
        for (match in result) {
            if (deduped.any { it.bounds == match.bounds && it.text == match.text }) continue
            deduped.add(match)
        }
        return deduped
    }

    /** The single best match, or null. */
    fun find(
        terms: Iterable<String>,
        avoid: Iterable<String> = emptyList(),
        exactTerms: Iterable<String> = emptyList()
    ): TextBoxMatch? = matches(terms, avoid, exactTerms).firstOrNull()

    /** First box containing [text] after normalisation. */
    fun findLine(text: String, exact: Boolean = false): TextBox? {
        val wanted = TextMatch.normalizeLoose(text)
        if (wanted.isEmpty()) return null
        return boxes.firstOrNull {
            val value = TextMatch.normalizeLoose(it.text)
            if (exact) value == wanted else wanted in value
        }
    }

    fun contains(text: String): Boolean {
        val wanted = TextMatch.normalizeLoose(text)
        return wanted.isNotEmpty() && boxes.any { wanted in TextMatch.normalizeLoose(it.text) }
    }

    private fun safeNormalize(value: String): String = TextMatch.normalizeLoose(value)
}

/**
 * Reads the format written by `reward_collector/ocr.py` (`parse_ocr_json`).
 *
 * Only line-level boxes are kept: pc-collector's per-word rectangles are useful
 * for tapping a keyword inside a long line, and that refinement can be ported
 * when phase 2 actually taps. Keeping the fixtures loadable now is what makes
 * them regression tests for the text rules.
 */
object OcrJson {

    fun parse(payload: String, capturedAt: Long = 0L): Snapshot {
        val root = MiniJson.parse(payload) as? Map<*, *>
            ?: throw IllegalArgumentException("OCR fixture is not a JSON object")
        val error = root["error"]?.toString()
        if (!error.isNullOrBlank()) throw IllegalArgumentException("OCR fixture reports error: $error")

        val width = intValue(root["width"])
        val height = intValue(root["height"])
        val rawLines = root["lines"] as? List<*> ?: emptyList<Any?>()
        val boxes = rawLines.mapNotNull { raw ->
            val line = raw as? Map<*, *> ?: return@mapNotNull null
            val text = line["text"]?.toString().orEmpty()
            val box = Box.from(
                left = intValue(line["x"]),
                top = intValue(line["y"]),
                width = intValue(line["w"]),
                height = intValue(line["h"])
            )
            TextBox(text = text, bounds = box, source = TextSource.OCR)
        }
        return Snapshot(
            width = width,
            height = height,
            capturedAt = capturedAt,
            foregroundPackage = null,
            foregroundActivity = null,
            boxes = boxes
        )
    }

    private fun intValue(value: Any?): Int = when (value) {
        is Long -> value.toInt()
        is Double -> value.toInt()
        is Number -> value.toInt()
        is String -> value.toIntOrNull() ?: 0
        else -> 0
    }
}
