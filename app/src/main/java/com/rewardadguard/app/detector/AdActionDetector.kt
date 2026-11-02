package com.rewardadguard.app.detector

/**
 * The two literal controls an ad page is allowed to expose to the guard.
 *
 * Deliberately an exhaustive list: only text rendered exactly as `[close]` or
 * `[>>]` is recognised, so ordinary words such as "Close", the ad's "Google
 * Play >>" call to action, or an article that merely mentions a close button
 * can never be mistaken for one of these controls.
 */
enum class AdActionKind {
    /** `[close]`: dismiss the ad and go back to the reward collection flow. */
    CLOSE,

    /** `[>>]`: advance the ad to its next step. */
    NEXT
}

/**
 * One matched `[close]` / `[>>]` control.
 *
 * [node] is the snapshot of the *labelled* node. Whether it is actually
 * operable is decided against the live tree at click time, because a labelled
 * `TextView` is often wrapped in the clickable button that really receives the
 * tap.
 */
data class AdActionMatch(
    val kind: AdActionKind,
    val node: NodeSnapshot,
    val method: String,
    /** The exact label that matched, e.g. `[>>]` or `[->]`. */
    val token: String = AdActionDetector.tokenOf(kind)
) {
    /** `72x72`, for the log. */
    fun boundsLabel(): String = "${node.width}x${node.height}"

    /** Stable id linking DISPATCHED and VERIFIED rows of one action. */
    fun correlationId(): String =
        "cid=${node.left},${node.top},${node.right},${node.bottom}#$token"
}

/**
 * Detects the literal `[close]` and `[>>]` ad-page controls.
 *
 * Pure Kotlin over [NodeSnapshot], so the matching rules are unit tested on the
 * JVM. The service is responsible for the two things this object cannot know:
 *  - the node is inside a window of a *monitored reward app* (the caller checks
 *    the window's package before calling in),
 *  - a real clickable target exists on top of the label.
 */
object AdActionDetector {

    /** The exact close label, compared case-insensitively after normalisation. */
    const val CLOSE_TOKEN = "[close]"

    /** The exact next-step label. */
    const val NEXT_TOKEN = "[>>]"

    /** The alternate next-step label. */
    const val NEXT_ARROW_TOKEN = "[->]"

    /** Full-width bracket variants that some ad renderers emit. */
    private const val CLOSE_TOKEN_WIDE = "\uFF3Bclose\uFF3D"
    private const val NEXT_TOKEN_WIDE = "\uFF3B>>\uFF3D"
    private const val NEXT_ARROW_TOKEN_WIDE = "\uFF3B->\uFF3D"

    /** The label string of [kind], for logging. */
    fun tokenOf(kind: AdActionKind): String = when (kind) {
        AdActionKind.CLOSE -> CLOSE_TOKEN
        AdActionKind.NEXT -> NEXT_TOKEN
    }

    /**
     * Maps a node label to a [AdActionKind].
     *
     * The whole label must be the token: `[close] (3s)` or "Tap [close]" is
     * intentionally *not* matched, because a partial match is how a normal app's
     * UI text gets mistaken for an ad control.
     */
    fun classify(label: String?): AdActionKind? =
        if (matchedToken(label) != null) {
            when (normalize(label)) {
                CLOSE_TOKEN, CLOSE_TOKEN_WIDE -> AdActionKind.CLOSE
                else -> AdActionKind.NEXT
            }
        } else {
            null
        }

    /** The next-step label [node] shows, or null when it shows none. */
    internal fun tokenOf(node: NodeSnapshot): String? =
        matchedToken(node.text) ?: matchedToken(node.contentDescription)

    /** The exact token [label] spells, or null when it is not a control label. */
    fun matchedToken(label: String?): String? = when (normalize(label)) {
        CLOSE_TOKEN, CLOSE_TOKEN_WIDE -> CLOSE_TOKEN
        NEXT_TOKEN, NEXT_TOKEN_WIDE -> NEXT_TOKEN
        NEXT_ARROW_TOKEN, NEXT_ARROW_TOKEN_WIDE -> NEXT_ARROW_TOKEN
        else -> null
    }

    /**
     * True when [node] shows the label of [kind] and can plausibly be a control.
     *
     * Visibility, non-zero bounds and a size sanity check are enforced here; the
     * clickable-target check happens against the live tree in the service.
     */
    fun isLabelled(node: NodeSnapshot, kind: AdActionKind): Boolean {
        if (!node.visible) return false
        if (node.width <= 0 || node.height <= 0) return false
        return classify(tokenOf(node)) == kind
    }

    /**
     * Picks the control to act on: `[close]` wins over `[>>]`, and within one
     * kind the smallest node wins (a nested label and its container usually
     * share the same text; clicking the leaf is what a finger would do).
     *
     * Full-screen nodes are rejected: the label may legitimately sit inside a
     * page-sized container, but that container must never be the thing clicked.
     */
    fun bestMatch(
        candidates: List<NodeSnapshot>,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): AdActionMatch? {
        val screenArea = screenWidth.coerceAtLeast(1) * screenHeight.coerceAtLeast(1)
        val matches = candidates.mapNotNull { node ->
            if (!node.visible || node.width <= 0 || node.height <= 0) return@mapNotNull null
            if (node.area <= 0 || node.area > screenArea / 4) return@mapNotNull null
            val token = tokenOf(node) ?: return@mapNotNull null
            val kind = classify(token) ?: return@mapNotNull null
            AdActionMatch(kind, node, "TEXT_TOKEN_${kind.name}", token)
        }
        if (matches.isEmpty()) return null
        return matches.minWithOrNull(
            compareBy<AdActionMatch>({ if (it.kind == AdActionKind.CLOSE) 0 else 1 }, { it.node.area })
        )
    }

    /**
     * True when the control that would receive the tap is button-sized and sits
     * under the label.
     *
     * The click target is resolved at tap time and may be a clickable ancestor
     * of the label. An ancestor larger than 1/[MAX_TARGET_AREA_DIVISOR] of the
     * screen is the ad itself, and tapping it is an ad click that opens the
     * store (review 2026-11-02; the same rule as the close-button detector).
     */
    fun isSafeTapTarget(
        targetWidth: Int,
        targetHeight: Int,
        containsLabelCentre: Boolean,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (targetWidth <= 0 || targetHeight <= 0) return false
        if (!containsLabelCentre) return false
        val screenArea = screenWidth.coerceAtLeast(1).toLong() * screenHeight.coerceAtLeast(1)
        return targetWidth.toLong() * targetHeight <= screenArea / MAX_TARGET_AREA_DIVISOR
    }

    /** A tap target may cover at most 1/6 of the screen. */
    const val MAX_TARGET_AREA_DIVISOR = 6

    /** Trims, collapses inner whitespace and lower-cases a label. */
    internal fun normalize(label: String?): String? {
        val trimmed = label?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        return trimmed.replace(Regex("\\s+"), " ").lowercase()
    }
}
