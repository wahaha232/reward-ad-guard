package com.rewardadguard.app.detector

/**
 * Pure-Kotlin node snapshot so the close-button heuristics can be unit tested on
 * the JVM without an Android device.
 */
data class NodeSnapshot(
    val text: String?,
    val contentDescription: String?,
    val viewIdResourceName: String?,
    val className: String?,
    val clickable: Boolean,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val visible: Boolean = true
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val area: Int get() = width * height
}

/**
 * Result of matching a node against the close-button rules.
 */
data class CloseMatch(
    val matched: Boolean,
    val score: Int,
    val method: String,
    val node: NodeSnapshot?,
    val suggestedExpandedBounds: IntArray? = null
) {
    /** Human readable label such as "72x72", used by the log. */
    fun boundsLabel(): String {
        val b = suggestedExpandedBounds ?: return "-"
        return "${b[2] - b[0]}x${b[3] - b[1]}"
    }
}

/**
 * Detects an ad close ("X") control inside the current window tree
 * (spec sections 24-27).
 *
 * This object contains only the scoring / geometry logic; traversing the live
 * accessibility tree happens in the accessibility service. Keeping the decision
 * logic pure makes it unit-testable and reviewable.
 */
object CloseButtonDetector {

    /** Strong textual signals found in `text`, `contentDescription` or the view id. */
    private val STRONG_TEXT = setOf(
        "close", "close ad", "closead", "dismiss", "skip", "skip ad", "skipad",
        "關閉", "关闭", "跳過", "跳过", "取消", "不感兴趣"
    )

    /** Single glyphs that are almost always the ad X. */
    private val SYMBOL_TEXT = setOf(
        "x", "X", "×", "✕", "✖", "╳", "⨯", "\u2715", "\u2716", "\u00D7"
    )

    private val WEAK_TEXT_TOKENS = listOf("close", "dismiss", "skip", "cancel", "exit")

    private val VIEW_ID_HINTS = listOf(
        "close", "dismiss", "skip", "ad_close", "adclose", "btn_close", "iv_close",
        "image_close", "close_btn", "closebutton", "ad_skip", "skip_view"
    )

    /**
     * Landing-page call-to-action phrases.
     *
     * These belong to the ad's *store* button, never to its X. Moloco ads draw a
     * "Google Play >>" CTA in the same top-right corner as the X, so without this
     * list the detector matches the CTA, the guard taps it, and the guard itself
     * produces the redirect it exists to prevent (field report 2026-10-16,
     * root cause 4.1). Matching is done on the normalised (lower-cased) text,
     * content description and view id, so it never fires on "Close"/"跳過".
     */
    private val STORE_CTA_TEXT = setOf(
        "install", "install now", "download", "download now", "get it on",
        "get it on google play", "google play", "play store", "learn more",
        "shop now", "buy now", "visit site", "visit website", "open in app",
        "下載", "下载", "立即下載", "立即下载", "安裝", "安装", "立即安裝", "立即安装",
        "前往", "立即前往", "前往商店", "購買", "购买", "了解更多", "取得", "領取",
        "獲取", "获取", "免費取得"
    )

    /** View-id fragments that mark a store CTA rather than an X. */
    private val STORE_CTA_ID_HINTS = listOf(
        "cta", "install", "download", "google_play", "playstore", "play_store",
        "get_it_on", "learn_more", "shop_now", "buy_now", "visit_site"
    )

    private val CLOSE_CLASSES = setOf(
        "android.widget.ImageButton",
        "android.widget.ImageView",
        "android.widget.Button",
        "android.widget.TextView",
        "androidx.appcompat.widget.AppCompatImageButton",
        "androidx.appcompat.widget.AppCompatImageView"
    )

    /**
     * Scores a single node snapshot.
     *
     * @param screenWidth / [screenHeight] are used to reject nodes that cannot be
     *        the small corner X of an ad (a full-screen "close" CTA is not it).
     */
    fun score(
        node: NodeSnapshot,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): CloseMatch {
        if (!node.visible) return CloseMatch(false, 0, "INVISIBLE", node)
        if (node.width <= 0 || node.height <= 0) return CloseMatch(false, 0, "ZERO_BOUNDS", node)

        val text = node.text?.trim()
        val desc = node.contentDescription?.trim()
        val normalizedText = text?.lowercase()
        val normalizedDesc = desc?.lowercase()
        val normalizedId = node.viewIdResourceName?.lowercase().orEmpty()

        // 0. A store / install call to action is never the ad's X, whatever its
        //    geometry or view id says. Rejected before scoring so it can never
        //    accumulate the corner + class + id points that used to make a
        //    "Google Play >>" button look like a close control.
        if (isStoreCta(normalizedText, normalizedDesc, normalizedId)) {
            return CloseMatch(false, 0, "REJECTED_CTA", node)
        }

        var score = 0
        var method = "NONE"

        // 1. Exact glyph ("x", "×", "✕") -> strongest signal.
        if (text in SYMBOL_TEXT || desc in SYMBOL_TEXT) {
            score += 60
            method = "SYMBOL_TEXT"
        }

        // 2. Exact strong keyword.
        if (normalizedText != null && STRONG_TEXT.contains(normalizedText)) {
            score += 55
            if (method == "NONE") method = "TEXT_KEYWORD"
        }
        if (normalizedDesc != null && STRONG_TEXT.contains(normalizedDesc)) {
            score += 55
            if (method == "NONE") method = "DESCRIPTION_KEYWORD"
        }

        // 3. Weak tokens inside longer strings ("Tap to close the ad").
        val weakHit = WEAK_TEXT_TOKENS.any { token ->
            normalizedText?.contains(token) == true || normalizedDesc?.contains(token) == true
        }
        if (weakHit && method == "NONE") {
            score += 25
            method = "TEXT_HINT"
        }

        // 4. View id hints.
        if (VIEW_ID_HINTS.any { normalizedId.contains(it) }) {
            score += 40
            if (method == "NONE") method = "VIEW_ID"
        }

        // 5. Geometry: ad close controls live in a corner and are small.
        val cornerBonus = cornerScore(node, screenWidth, screenHeight)
        if (cornerBonus > 0) {
            score += cornerBonus
            if (method == "NONE") method = "CORNER_GEOMETRY"
        }

        // 6. Class sanity.
        if (node.className in CLOSE_CLASSES) score += 5

        // 7. Penalty: a huge clickable area is normally a CTA, not the X.
        if (node.area > (screenWidth * screenHeight) / 6) score -= 25

        val matched = score >= MATCH_THRESHOLD
        return CloseMatch(
            matched = matched,
            score = score,
            method = if (matched) method else "REJECTED_SCORE_$score",
            node = node,
            suggestedExpandedBounds = if (matched) expandedBounds(node, DEFAULT_SCALE) else null
        )
    }

    /** Picks the best match out of a candidate list, or null when none matched. */
    fun bestMatch(
        candidates: List<NodeSnapshot>,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): CloseMatch? = candidates
        .map { score(it, screenWidth, screenHeight) }
        .filter { it.matched }
        .maxByOrNull { it.score }

    /**
     * Computes the click target the guard *suggests* for a matched node.
     *
     * IMPORTANT (spec section 26): a non-root app cannot resize a third-party
     * view. This method therefore only computes geometry that the service may
     * use for an assisted/expanded accessibility action. It never modifies the
     * other app's layout.
     */
    fun expandedBounds(node: NodeSnapshot, scale: Float): IntArray {
        val safeScale = scale.coerceIn(1f, 5f)
        if (safeScale == 1f) return intArrayOf(node.left, node.top, node.right, node.bottom)

        val cx = (node.left + node.right) / 2
        val cy = (node.top + node.bottom) / 2
        val halfWidth = (node.width * safeScale / 2f).toInt().coerceAtLeast(node.width / 2)
        val halfHeight = (node.height * safeScale / 2f).toInt().coerceAtLeast(node.height / 2)
        return intArrayOf(
            (cx - halfWidth).coerceAtLeast(0),
            (cy - halfHeight).coerceAtLeast(0),
            cx + halfWidth,
            cy + halfHeight
        )
    }

    /** Corner proximity + smallness bonus. */
    private fun cornerScore(node: NodeSnapshot, screenWidth: Int, screenHeight: Int): Int {
        val cx = (node.left + node.right) / 2
        val cy = (node.top + node.bottom) / 2
        val horizontalEdge = cx < screenWidth / 4 || cx > screenWidth * 3 / 4
        val verticalEdge = cy < screenHeight / 3 || cy > screenHeight * 2 / 3
        val small = node.width <= screenWidth / 6 && node.height <= screenHeight / 12
        var score = 0
        if (horizontalEdge) score += 12
        if (verticalEdge) score += 8
        if (small) score += 10
        return score
    }

    /**
     * True when any of the node's own labels describe a store / install CTA.
     *
     * Text and description are substring-matched (an ad renders "Google Play >>",
     * which no exact-match list would ever contain); the view id is
     * substring-matched too. The phrases are CTA-specific, so a genuine
     * Close/Skip control never trips this.
     */
    private fun isStoreCta(
        normalizedText: String?,
        normalizedDesc: String?,
        normalizedId: String
    ): Boolean {
        if (normalizedText != null && STORE_CTA_TEXT.any { normalizedText.contains(it) }) return true
        if (normalizedDesc != null && STORE_CTA_TEXT.any { normalizedDesc.contains(it) }) return true
        return STORE_CTA_ID_HINTS.any { normalizedId.contains(it) }
    }

    /** Scores at or above this value are treated as an ad close control. */
    const val MATCH_THRESHOLD = 45

    /** Default expansion used when no user preference is available. */
    const val DEFAULT_SCALE = 3f
}
