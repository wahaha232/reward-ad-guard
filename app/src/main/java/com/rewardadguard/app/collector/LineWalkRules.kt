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
        val counts: Map<String, Int>,
        /**
         * Per-button red badge counts (available draws, not raw tickets).
         * 2026-11-26 run analysis (9:10 & 9:11 screenshots): total ticket
         * balance was 546 while the red badge above "5張 抽1次" read 109
         * (546 / 5 = 109.2). Recorded fixtures show the same 10:1 ratio
         * between normal (5 tickets) and BIG (50 tickets): 479 vs 47, 441 vs 44.
         */
        val badgeCounts: Map<String, Int> = emptyMap()
    )

    private val AMOUNT = Regex("(\\d+)獲得$")
    private val BANNER_MARKERS = listOf("ad", "sponsored", "sponsoredby", "開始遊玩")
    private const val BANNER_DISTANCE_PX = 300

    /**
     * LINE's 邀請好友 promotion ("最高可獲 10000 金幣 / 雙方都可獲得金幣! /
     * 邀請好友"). Nothing on it may be tapped - its button sends invitations -
     * so it is left with Back (2026-11-13 15:02).
     */
    fun isInvitePromo(snapshot: Snapshot): Boolean {
        val text = snapshot.looseText
        return "邀請好友" in text && ("雙方都" in text || "被邀請的好友" in text || "邀請任務" in text)
    }

    fun isChallenge(snapshot: Snapshot): Boolean =
        snapshot.boxes.any { TextMatch.normalizeLoose(it.text).contains("突擊挑戰") }

    fun challengeFinished(snapshot: Snapshot): Boolean {
        val text = snapshot.looseText
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
        // "56 % 獲得金幣" is the home meter still filling up, not a reward
        // button; tapping it did nothing three times in a row (2026-11-12).
        if (candidate.text.contains('%') || candidate.text.contains('％')) return false
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
        val text = snapshot.looseText
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
        val badges = lotteryButtonBadges(snapshot, buttons)
        val counts = HashMap(badges)
        // The 2026-11-22 screen kept the normal button at "5張抽1次" while the
        // only ticket balance was the blue badge in the banner's top-right
        // corner ("1"). Nothing sits above that button there, so read the
        // banner badge as the normal balance when no per-button badge exists.
        if (counts["normal"] == null) {
            lotteryBannerTickets(snapshot)?.let { counts["normal"] = it }
        }
        return buttons.copy(counts = counts, badgeCounts = badges)
    }

    /**
     * Remaining draws read from the red badge above each draw button.
     *
     * Evidence (2026-11-26 report): at 09:10 and 09:11 the screen showed 546
     * tickets and the red badge above the 5-ticket normal button read 109
     * (546 / 5 = 109.2), proving the red badge is available draws rather than
     * raw tickets. The banner's top-right blue badge is raw tickets.
     */
    private fun lotteryButtonBadges(snapshot: Snapshot, sheet: LotterySheet): Map<String, Int> {
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

    /** The blue ticket counter at the top-right of the 抽抽樂 banner. */
    private fun lotteryBannerTickets(snapshot: Snapshot): Int? =
        snapshot.boxes
            .filter {
                val value = TextMatch.normalizeLoose(it.text)
                if (value.length !in 1..4 || value.toIntOrNull() == null) return@filter false
                val cx = (it.bounds.left + it.bounds.right) / 2
                val cy = (it.bounds.top + it.bounds.bottom) / 2
                cx > snapshot.width * 0.65 &&
                    cy in (snapshot.height * 0.055).toInt()..(snapshot.height * 0.16).toInt()
            }
            .minByOrNull { (it.bounds.top + it.bounds.bottom) / 2 }
            ?.let { TextMatch.normalizeLoose(it.text).toIntOrNull() }

    /**
     * An in-app store page a draw opened (momo 購物 in LINE's own browser on
     * 2026-11-12). Only unambiguous markers count: "下載" or "開啟" alone show
     * up on ordinary LINE screens ("開啟通知"), and treating those as a store
     * page pressed Back or an X on the lottery itself (review P2-7).
     */
    fun looksLikeStorePage(snapshot: Snapshot): Boolean {
        val text = snapshot.looseText
        if (text.isEmpty()) return false
        // Never while the lottery sheet itself is up: its banner ad shows
        // "Google Play" and "安裝" (2026-11-12 20:01), and calling that a store
        // page pressed Back and left LINE.
        if (lotterySheet(snapshot) != null) return false
        return ("install" in text && "moreinfo" in text) ||
            "playstore" in text || "momo購物" in text
    }

    /**
     * The prize text of a draw ("20枚金幣中獎囉!"), with or without the
     * 關閉 button. A prize counts as a reward even when its close button is
     * missing; waiting 240 s and calling it a failure lost the prize (P2-8).
     */
    fun isLotteryPrize(snapshot: Snapshot): Boolean =
        lotterySheet(snapshot) == null &&
            snapshot.boxes.any { isPrizeText(it.text) }

    /** The "XX枚金幣中獎囉！/關閉" result sheet. */
    fun lotteryResultClose(snapshot: Snapshot): Target? {
        if (snapshot.boxes.none { isPrizeText(it.text) }) return null
        val close = snapshot.boxes.firstOrNull { TextMatch.normalizeLoose(it.text) in CLOSE_LABELS } ?: return null
        return Target(close.text, close.bounds, 100, "lottery_close")
    }

    private const val SKIP_MAX_LEN = 8

    /**
     * "50 枚金幣中獎囉！": OCR has read 枚 as 校 and can read 獎 as 奬 or the
     * simplified 奖; a prize the reader could not match was waited out and
     * called a failure (2026-11-12 23:22).
     */
    private val PRIZE_MARKERS = listOf("中獎", "中奬", "中奖", "獎囉", "奬囉", "奖囉", "金幣中")
    private val CLOSE_LABELS = setOf("關閉", "关闭", "閉")

    fun isPrizeText(text: String): Boolean {
        val value = TextMatch.normalizeLoose(text)
        return PRIZE_MARKERS.any { it in value }
    }

    /** BIG (50 張) and 免費 draw once and then count down; 一般 5 張 repeats. */
    fun isOncePerCooldown(kind: String): Boolean = kind == "big" || kind == "free"

    const val LOTTERY_NORMAL_TICKETS = 5

    /**
     * The normal draw needs [LOTTERY_NORMAL_TICKETS] tickets. A badge below
     * that means no draw is possible, even though LINE still shows the button
     * (2026-11-21: 4 tickets were tapped twice and waited out).
     */
    fun lotteryTicketsLow(sheet: LotterySheet?): Boolean =
        sheet?.counts?.get("normal")?.let { it < LOTTERY_NORMAL_TICKETS } == true

    /**
     * How many 5-ticket normal draws are available on [sheet], or null when no
     * counter was read.
     *
     * A per-button red badge (`badgeCounts["normal"]`) is already in draws
     * (2026-11-26 report: 546 tickets -> red badge 109 on both 09:10 and 09:11
     * screenshots). When only the top-right banner counter was read
     * (`counts["normal"]` without a button badge), that value is raw tickets
     * and is divided by [LOTTERY_NORMAL_TICKETS].
     */
    fun normalDrawsAvailable(sheet: LotterySheet?): Int? {
        if (sheet == null) return null
        sheet.badgeCounts["normal"]?.let { return it }
        return sheet.counts["normal"]?.let { it / LOTTERY_NORMAL_TICKETS }
    }

    /** True when the sheet positively shows at least 1 normal draw is available. */
    fun hasSufficientNormalDraws(sheet: LotterySheet?): Boolean =
        (normalDrawsAvailable(sheet) ?: 0) >= 1

    /**
     * True when the screen proves tickets are genuinely too low for a normal
     * draw: either LINE displayed a not-enough-tickets notice, or the counter
     * shows fewer than 1 available draw.
     */
    fun isReallyTicketsLow(sheet: LotterySheet?, seenAfterFail: Snapshot? = null): Boolean {
        if (seenAfterFail != null && lotteryNotEnoughTickets(seenAfterFail)) return true
        return normalDrawsAvailable(sheet)?.let { it < 1 } == true
    }

    /**
     * End reason when no more draw kind can be attempted on [sheet].
     *
     * Distinguishes genuinely low tickets (`lottery_tickets_low`, which stops
     * the guard) from repeated normal-draw failures while draws are clearly
     * available (`lottery_normal_failed`, which must NOT stop the guard).
     */
    fun lotteryEndReason(
        sheet: LotterySheet,
        normalFailures: Int,
        maxLotteryFailures: Int
    ): String {
        if (isReallyTicketsLow(sheet)) return "lottery_tickets_low"
        if (normalFailures >= maxLotteryFailures) {
            return if (hasSufficientNormalDraws(sheet)) {
                "lottery_normal_failed"
            } else {
                // No badge read (2026-11-22: banner "1" missed by OCR): keep
                // treating repeated normal failures with no count as low tickets.
                "lottery_tickets_low"
            }
        }
        return "done"
    }

    /** LINE's own "not enough tickets" message after a draw was refused. */
    fun lotteryNotEnoughTickets(snapshot: Snapshot): Boolean {
        val text = snapshot.looseText
        return NOT_ENOUGH_TICKETS.any { it in text }
    }

    private val NOT_ENOUGH_TICKETS = listOf("票券不足", "抽獎券不足", "代幣不足", "點數不足", "無法抽獎")

    fun lotteryEntry(snapshot: Snapshot): Target? {
        val box = snapshot.boxes.firstOrNull {
            TextMatch.normalizeLoose(it.text) == "抽抽樂" &&
                (it.bounds.top + it.bounds.bottom) / 2 < snapshot.height * 0.8
        } ?: return null
        return Target(box.text, box.bounds, 100, "lottery_entry")
    }

    fun findSkip(snapshot: Snapshot): Target? {
        // The draw animation's button reads "SKIP ▶|"; OCR keeps the glyphs
        // ("skip>i", "skipi"), and the exact match missed it (2026-11-12
        // 22:34). A short label starting with "skip" counts; "Skip to Google
        // Play" is far longer and never does.
        val box = snapshot.boxes.firstOrNull {
            val value = TextMatch.normalizeLoose(it.text)
            value.startsWith("skip") && value.length <= SKIP_MAX_LEN
        } ?: return null
        return Target(box.text, box.bounds, 100, "skip")
    }
}
