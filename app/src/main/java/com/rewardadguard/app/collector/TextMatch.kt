package com.rewardadguard.app.collector

import java.text.Normalizer
import java.util.Locale

/**
 * Text comparison ported from pc-collector (`reward_collector/ui.py`).
 *
 * Why a port instead of a new matcher: the whole point of moving the collector
 * onto the phone is to reuse the keyword rules and the 33 recorded screens that
 * already proved them. Those rules were tuned against Windows OCR, which puts a
 * space between every CJK character ("關 閉"); removing *all* whitespace makes
 * the same rules work for the accessibility tree, ML Kit and the recorded
 * fixtures without three separate keyword tables.
 *
 * Pure Kotlin on purpose: every keyword decision is unit-tested on the JVM,
 * where the recorded fixtures live.
 */
object TextMatch {

    /**
     * Punctuation dropped by [normalizeLoose]. Same character set as the Python
     * implementation so a fixture that matched there also matches here.
     */
    private const val PUNCTUATION = "，。！？、,.!?:;()[]{}<>《》「」『』\"'“”‘’·|/\\-_+"

    private val whitespace = Regex("\\s+")

    /**
     * NFKC + case folding + remove all whitespace.
     *
     * NFKC is what turns full-width Latin ("ＡＢＣ") and full-width brackets into
     * their half-width forms, so an OCR pass and a node tree agree.
     */
    fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        return whitespace.replace(folded, "")
    }

    /**
     * An OCR misread of an avoid term (2026-11-16: ML Kit read 禮物卡 as
     * 搜物卡 / 澧物卡 and the banner was tapped): some window of [label] differs
     * from [avoid] by exactly one character. Only for terms of 3+ characters,
     * and never where that window overlaps a reward term found in the label,
     * so 已領取 does not veto the reward 可領取. Inputs are normalized.
     */
    fun fuzzyAvoidHit(label: String, avoid: String, rewardTerms: Collection<String>): Boolean {
        val n = avoid.length
        if (n < FUZZY_MIN_LENGTH || label.length < n) return false
        if (avoid in label) return true
        val rewardSpans = ArrayList<IntRange>()
        for (term in rewardTerms) {
            if (term.isEmpty()) continue
            var at = label.indexOf(term)
            while (at >= 0) {
                rewardSpans += at until at + term.length
                at = label.indexOf(term, at + 1)
            }
        }
        for (start in 0..label.length - n) {
            var diff = 0
            var hanSwap = true
            for (i in 0 until n) {
                if (label[start + i] != avoid[i]) {
                    diff++
                    if (diff > 1) break
                    // A look-alike Han character (禮/澧/搜), not a digit or a
                    // letter: "0點數" is not a misread of 倍點數.
                    hanSwap = isHan(label[start + i]) && isHan(avoid[i])
                }
            }
            if (diff != 1 || !hanSwap) continue
            val window = start until start + n
            if (rewardSpans.none { it.first <= window.last && window.first <= it.last }) return true
        }
        return false
    }

    const val FUZZY_MIN_LENGTH = 3

    private fun isHan(ch: Char): Boolean = Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.HAN

    /** [normalize] plus removal of decorative punctuation. */
    fun normalizeLoose(text: String): String {
        val value = normalize(text)
        if (value.isEmpty()) return value
        val builder = StringBuilder(value.length)
        for (ch in value) {
            if (PUNCTUATION.indexOf(ch) < 0) builder.append(ch)
        }
        return builder.toString()
    }

    /**
     * Best score of [terms] against [label], mirroring `best_terms_match`.
     *
     * 100 = the whole label is the term, 70 = the label starts with it, 50 = it
     * occurs somewhere. A term listed in [exactTerms] only counts as an exact
     * match, which is how short words such as "x" stop matching every label that
     * happens to contain that letter. Any [avoid] term anywhere in the label
     * vetoes the label entirely.
     */
    fun bestTermsMatch(
        label: String,
        terms: Iterable<String>,
        avoid: Iterable<String> = emptyList(),
        exactTerms: Iterable<String> = emptyList()
    ): Int {
        val value = normalizeLoose(label)
        if (value.isEmpty()) return 0
        for (term in avoid) {
            val normalized = normalizeLoose(term)
            if (normalized.isNotEmpty() && value.contains(normalized)) return 0
        }
        val exact = exactTerms.map(::normalizeLoose).filter { it.isNotEmpty() }.toSet()
        var score = 0
        for (term in terms) {
            val normalized = normalizeLoose(term)
            if (normalized.isEmpty()) continue
            when {
                value == normalized -> score = maxOf(score, 100)
                normalized in exact -> continue
                value.startsWith(normalized) -> score = maxOf(score, 70)
                value.contains(normalized) -> score = maxOf(score, 50)
            }
        }
        return score
    }
}
