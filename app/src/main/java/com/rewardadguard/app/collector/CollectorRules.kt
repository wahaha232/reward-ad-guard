package com.rewardadguard.app.collector

import com.rewardadguard.app.detector.AdActivityClassifier
import com.rewardadguard.app.detector.CloseButtonDetector
import com.rewardadguard.app.detector.NodeSnapshot

/** What the phone says is in front, sampled right before a screenshot. */
data class Foreground(val packageName: String?, val activity: String?)

/** A rectangle the collector has decided it may tap, and why. */
data class Target(val text: String, val bounds: Box, val score: Int, val source: String) {
    val centerX: Int get() = bounds.centerX
    val centerY: Int get() = bounds.centerY
}

/**
 * The decision half of pc-collector's `apps/base.py`, kept free of Android and
 * of side effects so every rule can be replayed against the 33 recorded
 * screens.
 *
 * The engine calls these; nothing here taps, waits or reads the phone. That
 * split is what makes "would the collector click the store?" answerable in a
 * unit test instead of during a rewarded-ad run.
 */
object CollectorRules {

    val STATUS_TERMS = listOf(
        "準備中", "距離下次", "還有", "分鐘", "小時", "剩餘", "已結束", "尚未開始",
        "活動期間", "今日已", "今天還可以", "明日再", "明天恢復", "下次抽獎"
    )

    /** Text that is never a reward target because it leads to a store page. */
    val UNSAFE_REWARD_TEXT = listOf(
        "立即下載", "下载", "下載", "立即安裝", "安裝", "安装", "前往商店",
        "前往 Google Play", "google play", "goog play", "skip to goog", "play store",
        "app store", "open in app", "在應用程式中開啟", "visit website", "立即購買",
        "購買", "了解更多", "learn more", "get it on",
        // English store pages (LINE's in-app browser showed Google Play on
        // 2026-11-12); "Install" must never be a reward or a next-ad target.
        "install", "more info", "moreinfo", "download"
    )

    val CONFIRM_TERMS = listOf("確定", "確認", "知道了", "OK", "Got it", "Continue", "繼續", "領取", "關閉")
    val DIALOG_TERMS = listOf("恭喜", "成功", "已獲得", "獲得獎勵", "已領取", "您獲得了")
    val DIALOG_STRONG_TERMS = listOf("恭喜", "已獲得", "獲得獎勵", "您獲得了")
    val INFO_CLOSE_TERMS = listOf("關閉", "知道了", "確定", "OK", "Close")

    val POPUP_SAFE_TERMS = listOf(
        "以後再說", "下次再說", "今日不再提醒", "不再提醒", "No thanks", "Not now"
    )
    val POPUP_CONTEXT_TERMS = listOf("知道了", "稍後", "不同意", "Later")
    val POPUP_GENERIC_TERMS = listOf("關閉", "略過", "跳過", "Close", "Skip")

    val AD_HARD_MARKERS = listOf(
        "Sponsored", "Skip Ad", "廣告剩餘", "to be rewarded", "rewarded in",
        "秒後獲得獎勵", "秒後可獲得獎勵", "秒後可領取獎勵"
    )
    val AD_MARKERS = listOf(
        "廣告", "觀看廣告", "觀看影片", "Sponsored", "Learn more", "LEARN MORE",
        "Skip Ad", "廣告剩餘", "[close]"
    )
    val CLOSE_GLYPHS = setOf("x", "×", "✕", "✖", "╳", "⨯", "✗", "✘")

    private val NEXT_EXACT = setOf(
        "next", "[>>]", "[->]", "[→]", ">>", ">>>", "->", "→", "➜", "➔", "▶",
        "»", "»»", "下一則", "下一個"
    )
    private val NEXT_SUBSTRINGS = listOf(
        "nextad", "watchnext", "下一則廣告", "下一個廣告", "下一部廣告", "觀看下一則",
        "觀看下一個", "繼續觀看", "看下一則", "看下一個", "看下一則廣告", "看下一個廣告",
        "下一則", "下一個"
    )
    private const val NEXT_MAX_LABEL_LEN = 14

    private val SYSTEM_PACKAGES = setOf(
        "com.android.systemui", "android", "com.android.intentresolver",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.android.settings", "com.google.android.packageinstaller", "com.android.packageinstaller",
        "com.miui.home", "com.miui.securitycenter", "com.google.android.apps.nexuslauncher",
        "com.android.launcher", "com.android.launcher3", "com.sec.android.app.launcher",
        "com.huawei.android.launcher", "com.oppo.launcher", "com.bbk.launcher2",
        "com.google.android.inputmethod.latin"
    )

    private val ATTEMPT_GRID = 60

    // ------------------------------------------------------------- text basics

    fun isUnsafeRewardText(text: String): Boolean {
        val value = TextMatch.normalizeLoose(text)
        if (value.isEmpty()) return false
        return UNSAFE_REWARD_TEXT.any { TextMatch.normalizeLoose(it).let { term -> term.isNotEmpty() && term in value } }
    }

    fun isSystemPackage(packageName: String?): Boolean {
        val value = packageName?.trim().orEmpty()
        return value.isEmpty() || value in SYSTEM_PACKAGES
    }

    /** The app's bottom tab bar must be visible: only the bottom 18% counts. */
    fun hasAppChrome(snapshot: Snapshot, profile: AppProfile): Boolean {
        if (profile.chromeTerms.isEmpty()) return true
        val bandTop = (snapshot.height * CHROME_BAND).toInt()
        val bandText = snapshot.boxes
            .filter { it.bounds.top >= bandTop }
            .map { TextMatch.normalizeLoose(it.text) }
        val terms = profile.chromeTerms.map { TextMatch.normalizeLoose(it) }.filter { it.isNotEmpty() }
        val hits = terms.count { term -> bandText.any { term in it } }
        return hits >= minOf(CHROME_MIN_TERMS, terms.size)
    }

    private const val CHROME_BAND = 0.82
    private const val CHROME_MIN_TERMS = 2

    // ----------------------------------------------------------- reward picking

    /**
     * Reward candidates in tap order. Mirrors `Snapshot.candidates` +
     * `_pick_reward`: node boxes win when any exist, then the terms' own
     * priority decides, not the match score.
     */
    fun rewardCandidates(snapshot: Snapshot, profile: AppProfile): List<TextBoxMatch> {
        val nodeMatches = snapshot.copy(boxes = snapshot.boxesFrom(TextSource.NODE))
            .matches(profile.rewardTerms, profile.avoidTerms, profile.exactTerms)
        val ocrMatches = snapshot.copy(boxes = snapshot.boxesFrom(TextSource.OCR))
            .matches(profile.rewardTerms, profile.avoidTerms, profile.exactTerms)
        val chosen = when {
            !profile.useNodes -> ocrMatches
            nodeMatches.isNotEmpty() -> nodeMatches
            else -> ocrMatches
        }
        return chosen.sortedBy { termPriority(it.text, profile) }
    }

    fun termPriority(text: String, profile: AppProfile): Int {
        val label = TextMatch.normalizeLoose(text)
        val exact = profile.exactTerms.map { TextMatch.normalizeLoose(it) }.toSet()
        profile.rewardTerms.forEachIndexed { index, term ->
            val value = TextMatch.normalizeLoose(term)
            if (value.isEmpty()) return@forEachIndexed
            val matches = if (value in exact) label == value else value in label
            if (matches) return index
        }
        return profile.rewardTerms.size
    }

    /** Stable key for the per-button repeat limit; OCR jitter must not escape it. */
    fun attemptKey(target: Target): String {
        val prefix = TextMatch.normalizeLoose(target.text).take(6)
        return "$prefix@${Math.floorDiv(target.centerX, ATTEMPT_GRID)},${Math.floorDiv(target.centerY, ATTEMPT_GRID)}"
    }

    fun pickReward(
        snapshot: Snapshot,
        profile: AppProfile,
        attempts: Map<String, Int>,
        allowed: ((Target, Snapshot) -> Boolean)? = null
    ): Target? {
        val pageText = TextMatch.normalizeLoose(snapshot.text)
        val seen = HashSet<Pair<Int, Int>>()
        for (candidate in rewardCandidates(snapshot, profile)) {
            val label = TextMatch.normalizeLoose(candidate.text)
            if (label.isEmpty()) continue
            if (isUnsafeRewardText(candidate.text)) continue
            if (profile.statusTerms.any { term ->
                    val value = TextMatch.normalizeLoose(term)
                    value.isNotEmpty() && value in label
                }
            ) continue
            val unavailable = profile.unavailablePairs.any { (term, marker) ->
                val t = TextMatch.normalizeLoose(term)
                val m = TextMatch.normalizeLoose(marker)
                t.isNotEmpty() && m.isNotEmpty() && t in label && m in pageText
            }
            if (unavailable) continue
            val center = candidate.centerX to candidate.centerY
            if (!seen.add(center)) continue
            val target = Target(candidate.text, candidate.bounds, candidate.score, candidate.source.name.lowercase())
            if (allowed != null && !allowed(target, snapshot)) continue
            if ((attempts[attemptKey(target)] ?: 0) >= profile.maxRepeatPerTarget) continue
            if (candidate.bounds.top < (snapshot.height * 0.035).toInt()) continue
            if (candidate.bounds.bottom > snapshot.height * 0.99) continue
            if (!validTapBounds(candidate.bounds, snapshot.width, snapshot.height)) continue
            return target
        }
        return null
    }

    fun stillOffered(candidate: Target, after: Snapshot, profile: AppProfile): Boolean {
        val key = attemptKey(candidate)
        return rewardCandidates(after, profile).any {
            attemptKey(Target(it.text, it.bounds, it.score, it.source.name.lowercase())) == key
        }
    }

    fun validTapBounds(
        bounds: Box,
        screenWidth: Int,
        screenHeight: Int,
        maxAreaRatio: Double = 0.35
    ): Boolean =
        bounds.width > 0 && bounds.height > 0 &&
            bounds.left >= 0 && bounds.top >= 0 &&
            bounds.right <= screenWidth && bounds.bottom <= screenHeight &&
            bounds.area <= (screenWidth.toLong() * screenHeight * maxAreaRatio).toLong()

    // ------------------------------------------------------------ dialogs

    /** The central text line that proves a dialog ("恭喜", "您獲得了"). */
    fun dialogAnchor(snapshot: Snapshot, profile: AppProfile): TextBox? {
        val extra = profile.dialogTermsExtra
        val terms = if (hasAppChrome(snapshot, profile)) {
            DIALOG_STRONG_TERMS + extra
        } else {
            DIALOG_TERMS + extra
        }
        val wanted = terms.map { TextMatch.normalizeLoose(it) }.filter { it.isNotEmpty() }
        for (box in snapshot.boxes) {
            val cx = (box.bounds.left + box.bounds.right) / 2
            val cy = (box.bounds.top + box.bounds.bottom) / 2
            if (cx < snapshot.width * 0.1 || cx > snapshot.width * 0.9) continue
            if (cy < snapshot.height * 0.15 || cy > snapshot.height * 0.8) continue
            val value = TextMatch.normalizeLoose(box.text)
            if (wanted.any { it in value }) return box
        }
        return null
    }

    fun isDialog(snapshot: Snapshot, profile: AppProfile): Boolean =
        dialogAnchor(snapshot, profile) != null

    /** The short confirm button below the dialog text. */
    fun findDialogConfirm(snapshot: Snapshot, profile: AppProfile): Target? {
        val anchor = dialogAnchor(snapshot, profile) ?: return null
        val confirm = CONFIRM_TERMS.map { TextMatch.normalizeLoose(it) }.toSet()
        var best: Target? = null
        for (box in snapshot.boxes) {
            val value = TextMatch.normalizeLoose(box.text)
            if (value.isEmpty() || value.length > 8) continue
            if ("取消" in value || "cancel" in value) continue
            if (isUnsafeRewardText(box.text)) continue
            if (!confirm.any { value == it || value.startsWith(it) }) continue
            val cx = (box.bounds.left + box.bounds.right) / 2
            val cy = (box.bounds.top + box.bounds.bottom) / 2
            if (cx < snapshot.width * 0.1 || cx > snapshot.width * 0.9) continue
            if (cy < anchor.bounds.top || cy > anchor.bounds.bottom + snapshot.height * 0.45) continue
            if (!validTapBounds(box.bounds, snapshot.width, snapshot.height, 0.1)) continue
            val target = Target(box.text, box.bounds, 100, "dialog_confirm")
            if (best == null || target.centerY < best.centerY) best = target
        }
        return best
    }

    /**
     * The close button of the app's own help dialog ("什麼時候可以收到BTC？").
     * It means no ad is available right now; it is never a reward.
     */
    fun findInfoDialogClose(snapshot: Snapshot, profile: AppProfile): Target? {
        if (profile.infoDialogTerms.isEmpty()) return null
        val wanted = profile.infoDialogTerms.map { TextMatch.normalizeLoose(it) }.filter { it.isNotEmpty() }
        val anchor = snapshot.boxes.firstOrNull { box ->
            val value = TextMatch.normalizeLoose(box.text)
            wanted.any { it.isNotEmpty() && it in value }
        } ?: return null
        val closers = INFO_CLOSE_TERMS.map { TextMatch.normalizeLoose(it) }.toSet()
        for (box in snapshot.boxes) {
            val value = TextMatch.normalizeLoose(box.text)
            if (value !in closers) continue
            val cx = (box.bounds.left + box.bounds.right) / 2
            val cy = (box.bounds.top + box.bounds.bottom) / 2
            if (cx < snapshot.width * 0.2 || cx > snapshot.width * 0.8) continue
            if (cy <= anchor.bounds.top || cy > anchor.bounds.bottom + snapshot.height * 0.6) continue
            return Target(box.text, box.bounds, 100, "info_dialog_close")
        }
        return null
    }

    // ------------------------------------------------------------ ad handling

    /**
     * Positive evidence that an ad is on screen. Close words alone are not
     * enough (the app's own pages have dismiss icons), which is why every branch
     * below names the evidence.
     */
    fun adEvidence(snapshot: Snapshot, profile: AppProfile): Pair<Boolean, String> {
        if (AdActivityClassifier.classify(snapshot.foregroundActivity, profile.packageName) ==
            AdActivityClassifier.Kind.AD
        ) {
            return true to "ad_activity"
        }
        val pkg = snapshot.foregroundPackage?.trim().orEmpty()
        if (pkg.isNotEmpty() && pkg != profile.packageName && !isSystemPackage(pkg)) {
            return true to "external_package"
        }

        val loose = TextMatch.normalizeLoose(snapshot.text)
        if ((AD_HARD_MARKERS + profile.adHardMarkersExtra).any {
                val marker = TextMatch.normalizeLoose(it)
                marker.isNotEmpty() && marker in loose
            }
        ) {
            return true to "hard_ad_marker"
        }
        if (hasAppChrome(snapshot, profile)) return false to "app_chrome"

        val countdown = hasAdCountdown(snapshot)
        val closeGlyph = hasCloseGlyph(snapshot)
        val raw = snapshot.text
        val hasAdWord = Regex("\\bAD\\b", RegexOption.IGNORE_CASE).containsMatchIn(raw)
        val hasMarker = AD_MARKERS.any {
            val marker = TextMatch.normalizeLoose(it)
            marker.isNotEmpty() && marker in loose
        }
        if ((hasAdWord || hasMarker) && (countdown || closeGlyph)) {
            return true to "ad_marker_with_geometry"
        }
        if (countdown && closeGlyph) return true to "ad_timer_with_close"
        return false to "insufficient_evidence"
    }

    fun looksLikeAd(snapshot: Snapshot, profile: AppProfile): Boolean =
        adEvidence(snapshot, profile).first

    /** "第 3 則廣告，共 5 則" -> (3, 5); either part may be unreadable. */
    fun adSequence(snapshot: Snapshot): Pair<Int?, Int?> {
        val pattern = Regex("(?:第(\\d+))?則廣告共(\\d+)則")
        for (box in snapshot.boxes) {
            val value = TextMatch.normalizeLoose(box.text)
            val match = pattern.find(value) ?: continue
            return match.groupValues[1].toIntOrNull() to match.groupValues[2].toIntOrNull()
        }
        return null to null
    }

    fun hasAdCountdown(snapshot: Snapshot): Boolean {
        val pattern = Regex("\\d{1,2}(?:s|sec|秒)?")
        for (box in snapshot.boxes) {
            if (!pattern.matches(TextMatch.normalizeLoose(box.text))) continue
            if (box.bounds.left >= snapshot.width * 0.6 && box.bounds.top <= snapshot.height * 0.35) {
                return true
            }
        }
        return false
    }

    fun hasCloseGlyph(snapshot: Snapshot): Boolean =
        snapshot.boxes.any { TextMatch.normalizeLoose(it.text) in CLOSE_GLYPHS }

    fun isNextAdLabel(text: String, contentDescription: String = ""): Boolean {
        // Symbols such as ">>" lose their meaning under the loose normalisation
        // (punctuation is dropped), so the exact list uses the compact form.
        val compact = listOf(TextMatch.normalize(text), TextMatch.normalize(contentDescription))
        if (compact.any { it in NEXT_EXACT }) return true
        if (isUnsafeRewardText(text) || isUnsafeRewardText(contentDescription)) return false
        val normalized = listOf(TextMatch.normalizeLoose(text), TextMatch.normalizeLoose(contentDescription))
        return normalized.any { value ->
            value.isNotEmpty() && value.length <= NEXT_MAX_LABEL_LEN &&
                NEXT_SUBSTRINGS.any { it in value }
        }
    }

    // ------------------------------------------------------------ close / next

    /**
     * Close controls from text plus the corner templates.
     *
     * The text side uses the guard's own detector, so the collector inherits the
     * store-CTA rejection and the edge/centre rules the guard already proved.
     */
    fun findCloseTargets(
        snapshot: Snapshot,
        profile: AppProfile,
        cornerHits: List<GlyphHit>
    ): List<Target> {
        val targets = ArrayList<Target>()
        for (box in snapshot.boxes) {
            val node = NodeSnapshot(
                text = box.text,
                contentDescription = null,
                viewIdResourceName = null,
                className = null,
                clickable = false,
                left = box.bounds.left,
                top = box.bounds.top,
                right = box.bounds.right,
                bottom = box.bounds.bottom
            )
            val score = CloseButtonDetector.score(node, snapshot.width, snapshot.height)
            if (!score.matched) continue
            targets.add(Target(box.text, box.bounds, score.score, "close_text:${score.method}"))
        }
        for (hit in cornerHits) {
            if (hit.kind != "close") continue
            targets.add(Target("[X]", hit.bounds, 100 + (hit.score * 10).toInt(), "corner_close"))
        }
        // With the app's own bar visible, image matches low in the band are
        // usually app artwork (BITWALK's banner), so only the top 15% is trusted.
        val filtered = if (hasAppChrome(snapshot, profile)) {
            targets.filter {
                it.source != "corner_close" || it.centerY <= snapshot.height * 0.15
            }
        } else {
            targets
        }
        return dedupeByScore(filtered)
    }

    fun findNextTargets(
        snapshot: Snapshot,
        profile: AppProfile,
        cornerHits: List<GlyphHit>
    ): List<Target> {
        val targets = ArrayList<Target>()
        for (box in snapshot.boxes) {
            if (!isNextAdLabel(box.text)) continue
            targets.add(Target(box.text, box.bounds, 100, "next_text"))
        }
        if (!hasAppChrome(snapshot, profile)) {
            for (hit in cornerHits) {
                if (hit.kind != "next") continue
                // An icon-only >> is trusted only top-right; a chevron in ad
                // artwork opened Chrome on 2026-11-11.
                if (hit.bounds.centerX < snapshot.width * 0.75 || hit.bounds.centerY > snapshot.height * 0.2) continue
                targets.add(Target("[>>]", hit.bounds, (hit.score * 100).toInt(), "corner_next"))
            }
        }
        return dedupeByScore(targets)
    }

    private fun dedupeByScore(targets: List<Target>): List<Target> {
        val result = ArrayList<Target>()
        for (target in targets.sortedByDescending { it.score }) {
            val duplicate = result.any {
                kotlin.math.abs(it.centerX - target.centerX) < 40 &&
                    kotlin.math.abs(it.centerY - target.centerY) < 40
            }
            if (!duplicate) result.add(target)
        }
        return result
    }
}
