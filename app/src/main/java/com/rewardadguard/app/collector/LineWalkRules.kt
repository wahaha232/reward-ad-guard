package com.rewardadguard.app.collector

/**
 * LINE 散步趣 specific rules, ported from `apps/linewalk.py`.
 *
 * Separated from [CollectorRules] because LINE is the only app with a reward
 * *choice sheet* (紅利/普通/取消), a stand-up challenge and a lottery. Putting
 * these in the shared rules would make every other app pay for them and would
 * hide the one rule that matters most: a "獲得金幣" banner on the right side,
 * next to an AD / Sponsored label, is an ad banner and must never be tapped.
 */
object LineWalkRules {

    data class ChoiceSheet(val bonus: Target?, val normal: Target?, val cancel: Target?)

    data class LotterySheet(
        val big: Target?,
        val free: Target?,
        val normal: Target?,
        val cooldowns: Map<String, String>,
        val counts: Map<String, Int>
    )

    private val AMOUNT = Regex("(\\d+)獲得$")
    private val BANNER_MARKERS = listOf("ad", "sponsored", "sponsoredby", "開始遊玩")
    private const val BANNER_DISTANCE_PX = 300

    fun isChallenge(snapshot: Snapshot): Boolean =
        snapshot.boxes.any { TextMatch.normalizeLoose(it.text).contains("突擊挑戰") }

    fun challengeFinished(snapshot: Snapshot): Boolean {
        val text = TextMatch.normalizeLoose(snapshot.text)
        return "finish" in text || "下次再" in text
    }

    /** The "獲得金幣" button of a challenge, which only works after FINISH. */
    fun challengeButton(snapshot: Snapshot): Target? {
        val box = snapshot.boxes.firstOrNull {
            TextMatch.normalizeLoose(it.text).contains("獲得金幣") &&
                (it.bounds.top + it.bounds.bottom) / 2 > snapshot.height * 0.6
        } ?: return null
        return Target(box.text, box.bounds, 100, "challenge_claim")
    }

    /**
     * The coin counter at the top left ("510"), used to verify that an ad
     * actually paid. Returns null when it is not readable.
     */
    fun coinBalance(snapshot: Snapshot): Int? {
        for (box in snapshot.boxes) {
            if (box.bounds.top >= 260) continue
            if (box.bounds.left <= 150 || box.bounds.left >= 500) continue
            val digits = box.text.filter { it.isDigit() }
            if (digits.isNotEmpty()) return digits.toIntOrNull()
        }
        return null
    }

    /**
     * The real reward buttons are wide and centred. The adjoe banner that also
     * says "獲得金幣!!" sits right of centre next to an AD/Sponsored label, and a
     * grey challenge button is not tappable until FINISH.
     */
    fun candidateAllowed(candidate: Target, snapshot: Snapshot): Boolean {
        val cx = candidate.centerX
        if (cx < snapshot.width * 0.25 || cx > snapshot.width * 0.75) return false
        if (isChallenge(snapshot)) return false
        for (box in snapshot.boxes) {
            val value = TextMatch.normalizeLoose(box.text)
            if (BANNER_MARKERS.none { value == it || value.startsWith(it) }) continue
            val cy = (box.bounds.top + box.bounds.bottom) / 2
            if (kotlin.math.abs(cy - candidate.centerY) <= BANNER_DISTANCE_PX) return false
        }
        return true
    }

    /** Parses the bottom sheet shown after "獲得金幣/票券". */
    fun choiceSheet(snapshot: Snapshot): ChoiceSheet? {
        val amounts = ArrayList<TextBox>()
        var bonusLabel: TextBox? = null
        var skipLabel: TextBox? = null
        var cancel: TextBox? = null
        for (box in snapshot.boxes) {
            val value = TextMatch.normalizeLoose(box.text)
            when {
                value == "紅利" -> bonusLabel = box
                value.contains("跳過廣告") -> skipLabel = box
                value == "取消" -> cancel = box
            }
            val amount = AMOUNT.find(value)
            if (amount != null && value.length <= 10 &&
                (box.bounds.top + box.bounds.bottom) / 2 > snapshot.height * 0.4
            ) {
                amounts.add(box)
            }
        }
        if (amounts.isEmpty() || (bonusLabel == null && skipLabel == null && cancel == null)) return null

        fun below(label: TextBox?, limit: Double): TextBox? {
            if (label == null) return null
            val hits = amounts.filter {
                val cy = (it.bounds.top + it.bounds.bottom) / 2
                cy >= label.bounds.top - 20 && cy <= label.bounds.bottom + limit
            }
            return hits.minByOrNull { (it.bounds.top + it.bounds.bottom) / 2 }
        }

        val skip = below(skipLabel, snapshot.height * 0.06)
        var bonus = below(bonusLabel, snapshot.height * 0.08)
        if (bonus === skip) bonus = null
        val others = amounts.filter { it !== skip && it !== bonus }
        val normal = others.maxByOrNull { (it.bounds.top + it.bounds.bottom) / 2 }

        return ChoiceSheet(
            bonus = bonus?.let { Target(it.text, it.bounds, 100, "choice_bonus") },
            normal = normal?.let { Target(it.text, it.bounds, 100, "choice_normal") },
            cancel = cancel?.let { Target(it.text, it.bounds, 100, "choice_cancel") }
        )
    }

    /** Markers that prove a tab really switched (PC `TAB_MARKERS`). */
    fun onTab(snapshot: Snapshot, tab: String): Boolean {
        val text = TextMatch.normalizeLoose(snapshot.text)
        if (tab == "時間" && "今天的步數" in text) return false
        val markers = when (tab) {
            "首頁" -> listOf("速度x3", "獲得金幣")
            "步數" -> listOf("今天的步數")
            "時間" -> listOf("獲得票券")
            else -> emptyList()
        }
        return markers.isEmpty() || markers.any { TextMatch.normalizeLoose(it) in text }
    }

    /**
     * Draw buttons on the open 抽抽樂 sheet, plus cooldowns and remaining
     * counts. The BIG button is the left ticket button, the normal one right.
     */
    fun lotterySheet(snapshot: Snapshot): LotterySheet? {
        val title = snapshot.boxes.any {
            TextMatch.normalizeLoose(it.text) == "抽抽樂" &&
                (it.bounds.top + it.bounds.bottom) / 2 < snapshot.height * 0.2
        }
        val controls = snapshot.boxes.any {
            val value = TextMatch.normalizeLoose(it.text)
            value.endsWith("抽1次") || value.startsWith("回復還需")
        }
        if (!title || !controls) return null

        var big: TextBox? = null
        var free: TextBox? = null
        var normal: TextBox? = null
        for (box in snapshot.boxes) {
            val value = TextMatch.normalizeLoose(box.text)
            if (value.contains("免費抽1次")) {
                free = box
                continue
            }
            if (!value.endsWith("抽1次")) continue
            val cx = (box.bounds.left + box.bounds.right) / 2
            if (cx < snapshot.width / 2) {
                if (big == null) big = box
            } else if (normal == null) {
                normal = box
            }
        }

        val cooldowns = HashMap<String, String>()
        val time = Regex("\\d{1,2}\\s*:\\s*\\d{2}\\s*:\\s*\\d{2}")
        for (box in snapshot.boxes) {
            val value = TextMatch.normalizeLoose(box.text)
            if (!value.startsWith("回復還需")) continue
            val kind = if ((box.bounds.left + box.bounds.right) / 2 < snapshot.width / 2) "big" else "free"
            val match = time.find(box.text)
            cooldowns.putIfAbsent(kind, match?.value?.replace(" ", "") ?: "")
        }

        fun target(box: TextBox?, source: String): Target? =
            box?.let { Target(it.text, it.bounds, 100, source) }

        val buttons = LotterySheet(
            big = target(big, "lottery_big"),
            free = target(free, "lottery_free"),
            normal = target(normal, "lottery_normal"),
            cooldowns = cooldowns,
            counts = emptyMap()
        )
        return buttons.copy(counts = lotteryCounts(snapshot, buttons))
    }

    /** Remaining draws, read from the red badge above the ticket buttons. */
    private fun lotteryCounts(snapshot: Snapshot, sheet: LotterySheet): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for ((kind, target) in listOf("big" to sheet.big, "normal" to sheet.normal)) {
            if (target == null) continue
            val badge = snapshot.boxes.firstOrNull {
                val value = TextMatch.normalizeLoose(it.text)
                if (value.toIntOrNull() == null) return@firstOrNull false
                val cx = (it.bounds.left + it.bounds.right) / 2
                val cy = (it.bounds.top + it.bounds.bottom) / 2
                cy in (target.centerY - 220)..(target.centerY - 40) &&
                    cx in (target.centerX + 60)..(target.centerX + 320)
            }
            badge?.let { counts[kind] = TextMatch.normalizeLoose(it.text).toInt() }
        }
        return counts
    }

    /**
     * An in-app store page a draw opened (momo 購物 in LINE's own browser on
     * 2026-11-12). Only unambiguous markers count: "下載" or "開啟" alone show
     * up on ordinary LINE screens ("開啟通知"), and treating those as a store
     * page pressed Back or an X on the lottery itself (review P2-7).
     */
    fun looksLikeStorePage(snapshot: Snapshot): Boolean {
        val text = TextMatch.normalizeLoose(snapshot.text)
        if (text.isEmpty()) return false
        return ("install" in text && "moreinfo" in text) ||
            "playstore" in text || "googleplay" in text || "momo購物" in text
    }

    /**
     * The prize text of a draw ("20枚金幣中獎囉!"), with or without the
     * 關閉 button. A prize counts as a reward even when its close button is
     * missing; waiting 240 s and calling it a failure lost the prize (P2-8).
     */
    fun isLotteryPrize(snapshot: Snapshot): Boolean =
        lotterySheet(snapshot) == null &&
            snapshot.boxes.any { TextMatch.normalizeLoose(it.text).contains("中獎") }

    /** The "XX枚金幣中獎囉！/關閉" result sheet. */
    fun lotteryResultClose(snapshot: Snapshot): Target? {
        if (snapshot.boxes.none { TextMatch.normalizeLoose(it.text).contains("中獎") }) return null
        val close = snapshot.boxes.firstOrNull { TextMatch.normalizeLoose(it.text) == "關閉" } ?: return null
        return Target(close.text, close.bounds, 100, "lottery_close")
    }

    fun lotteryEntry(snapshot: Snapshot): Target? {
        val box = snapshot.boxes.firstOrNull {
            TextMatch.normalizeLoose(it.text) == "抽抽樂" &&
                (it.bounds.top + it.bounds.bottom) / 2 < snapshot.height * 0.8
        } ?: return null
        return Target(box.text, box.bounds, 100, "lottery_entry")
    }

    fun findSkip(snapshot: Snapshot): Target? {
        val box = snapshot.boxes.firstOrNull { TextMatch.normalizeLoose(it.text) == "skip" } ?: return null
        return Target(box.text, box.bounds, 100, "skip")
    }
}
