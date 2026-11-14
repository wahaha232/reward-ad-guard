package com.rewardadguard.app.collector

/**
 * One reward app's rules, ported from pc-collector's per-app profile modules.
 *
 * The PC profile classes are Python class attributes; here they are plain data
 * so the engine has no subclassing and the rules can be exercised against the
 * recorded fixtures on the JVM.
 *
 * Times are milliseconds (the PC values are seconds) and tab points are in the
 * PC's 1220x2712 reference frame; [scaledTabPoint] rescales them to the phone.
 */
/**
 * How to scroll a page for rewards below or beside the first screen.
 * [pages] swipes at most; [horizontal] drags right-to-left along [atY]
 * (a fraction of the screen height) instead of bottom-to-top.
 */
data class TabScroll(val pages: Int, val horizontal: Boolean = false, val atY: Double = 0.5)

data class AppProfile(
    val key: String,
    val name: String,
    val packageName: String,
    val collectionTabs: List<String> = emptyList(),
    val tabPoints: Map<String, Pair<Int, Int>> = emptyMap(),
    /** A collection tab reached through other tabs first, e.g. 任務 = 個人 then 任務. */
    val tabPaths: Map<String, List<String>> = emptyMap(),
    /** Pages that go on past the first screen: how to scroll them for more. */
    val tabScrolls: Map<String, TabScroll> = emptyMap(),
    /**
     * Text that proves the app's own page where there is no tab bar
     * (AHEALTH's 步行 page: "領取步數點數").
     */
    val altChromeTerms: List<String> = emptyList(),
    /** The button that closes the app's "reward received" sheet (AHEALTH: 完成). */
    val doneTerms: List<String> = emptyList(),
    val rewardTerms: List<String>,
    val avoidTerms: List<String>,
    val exactTerms: List<String> = emptyList(),
    val chromeTerms: List<String>,
    val pageMarkers: List<String> = emptyList(),
    /** Title words of the app's own "no ad available" help dialog. */
    val infoDialogTerms: List<String> = emptyList(),
    /**
     * The app's balance on screen; group 1 is the number. A rise after an ad
     * proves the reward was credited (BITWALK: "0.000269 mBTC").
     */
    val balancePattern: Regex? = null,
    /** Extra words that identify a reward dialog (LINE: 張票券／枚金幣). */
    val dialogTermsExtra: List<String> = emptyList(),
    /** Extra phrases that prove an ad is on screen (LINE: 載入中). */
    val adHardMarkersExtra: List<String> = emptyList(),
    /** LINE 散步趣 only: choice sheet, challenge and banner rules apply. */
    val lineRules: Boolean = false,
    /** LINE 抽抽樂 mode: open the sheet and draw instead of collecting tabs. */
    val lotteryMode: Boolean = false,
    val maxBonusFailures: Int = 2,
    val maxLotteryDraws: Int = 600,
    val maxLotteryFailures: Int = 2,
    /** Words of a "watch an ad" button; a vanished tab bar after tapping one means an ad. */
    val adButtonWords: List<String> = listOf("廣告", "影片", "watch", "video"),
    /** (term, page marker): the term is unavailable while the marker is on screen. */
    val unavailablePairs: List<Pair<String, String>> = emptyList(),
    val statusTerms: List<String> = CollectorRules.STATUS_TERMS,
    val adMinWatchMillis: Long,
    val adTimeoutMillis: Long,
    val maxRewards: Int,
    val maxRepeatPerTarget: Int,
    val launchWaitMillis: Long = 6_000L,
    val settleWaitMillis: Long = 2_000L,
    val pollMillis: Long = 1_500L,
    val maxNextAds: Int = 8,
    val cornerSkipGraceMillis: Long = 15_000L,
    val maxCornerSkips: Int = 3,
    val maxBackPresses: Int = 2,
    val endCardTimeoutMillis: Long = 40_000L,
    val maxInstallCardBacks: Int = 3,
    /**
     * Prefer accessibility nodes over OCR. pc-collector set this false because
     * `uiautomator dump` was unusable on BitWalk; on the phone the node tree is
     * the opposite - the primary source - so the default is true.
     */
    val useNodes: Boolean = true,
    val includeInAll: Boolean = true
) {

    fun scaledTabPoint(tab: String, screenWidth: Int, screenHeight: Int): Pair<Int, Int>? {
        val raw = tabPoints[tab] ?: return null
        val x = (raw.first * screenWidth / REFERENCE_WIDTH.toDouble()).toInt()
        val y = (raw.second * screenHeight / REFERENCE_HEIGHT.toDouble()).toInt()
        return if (x in 0 until screenWidth && y in 0 until screenHeight) x to y else null
    }

    companion object {
        const val REFERENCE_WIDTH = 1220
        const val REFERENCE_HEIGHT = 2712
    }
}

/**
 * The profiles implemented so far. Ordering is the order `all` runs them.
 */
object CollectorProfiles {

    val BITWALK = AppProfile(
        key = "bitwalk",
        name = "BITWALK",
        packageName = "jp.paddleinc.walk",
        collectionTabs = listOf("步行", "賺取"),
        tabPoints = mapOf("賺取" to (750 to 2570), "步行" to (450 to 2570)),
        rewardTerms = listOf(
            "收看廣告影片", "收看廣告", "觀看廣告", "領取", "完成任務",
            "寶箱", "簽到", "GET", "CLAIM"
        ),
        avoidTerms = listOf(
            "步驟歷史紀錄", "步驟歷史記錄", "步行", "賺取", "內容", "已兌換", "已領取",
            "無法", "剩餘", "印章重置", "明天", "今天也要", "可兌換", "兌換", "步驟",
            "歷史", "錢包", "購買", "取消", "關閉", "返回", "首頁", "預估發放",
            "換取", "獲得多量", "明天恢復", "價格預測", "變更預測", "邀請朋友",
            "2倍獎勵", "有機會獲得", "美元", "遊戲", "立即遊玩", "贏取大獎"
        ),
        exactTerms = listOf("GET", "CLAIM"),
        chromeTerms = listOf("步行", "賺取", "內容", "步驟歷史紀錄", "步驟歷史記錄"),
        infoDialogTerms = listOf("什麼時候可以收到"),
        // OCR reads the decimal point as "·" ("0 · 000269 mBTC").
        balancePattern = Regex("(\\d+\\s*[.·．]\\s*\\d+)\\s*mBTC", RegexOption.IGNORE_CASE),
        adMinWatchMillis = 20_000L,
        adTimeoutMillis = 120_000L,
        maxRewards = 60,
        maxRepeatPerTarget = 50
    )

    val AHEALTH = AppProfile(
        key = "ahealth",
        name = "AHEALTH",
        packageName = "com.tracerofficial",
        // 2026-11-14 (user): more 領取 inside the 步行 card (one coin per 500
        // steps). The 領取 under 更多健康習慣 (深蹲, 喝水 ...) start an
        // exercise tracked by the phone's sensors (深蹲 0/10, tested 21:19),
        // so the home page is not scrolled down to them; only its 健康年齡獎勵
        // strip (0/20/40... 分鐘) is slid sideways.
        collectionTabs = listOf("首頁", "步行"),
        tabPoints = mapOf("首頁" to (150 to 2550), "福利" to (720 to 2550)),
        tabPaths = mapOf("步行" to listOf("首頁", "步行")),
        tabScrolls = mapOf(
            "首頁" to TabScroll(pages = 6, horizontal = true, atY = 0.75),
            // The coins sit in a sideways strip at about 36% of the height.
            "步行" to TabScroll(pages = 6, horizontal = true, atY = 0.36)
        ),
        altChromeTerms = listOf("領取步數點數", "每500步數獲得隨機點數"),
        doneTerms = listOf("完成"),
        rewardTerms = listOf("領取", "簽到", "每日簽到"),
        avoidTerms = listOf(
            "已領取", "已完成", "健康年齡", "禮物商店", "福利", "首頁", "庫存",
            "Stock: 0", "剩餘", "取消", "返回", "兌換", "購買", "更多", "設定",
            "分享", "邀請", "成就", "遊戲", "瀏覽遊戲", "付款記錄", "廣告也不長",
            "所以我會", "評論", "賺錢遊戲", "好友邀請", "培育植物", "立即下載", "安裝",
            // Titles and the double-points ad offer, never buttons to press.
            "領取步數點數", "步數點數", "2倍點數", "倍點數", "觀看廣告", "獲得更多積分",
            // 健康短片 ("觀看 1 部健康短影音並領取點數") opens a video feed;
            // tapped at 21:00 on 2026-11-14, the run then swiped the videos.
            "短片", "短影音", "影音", "觀看", "看影片"
        ),
        chromeTerms = listOf("首頁", "健康年齡", "福利", "禮物商店"),
        pageMarkers = listOf("付款記錄", "瀏覽遊戲", "玩遊戲賺取點數"),
        adMinWatchMillis = 5_000L,
        adTimeoutMillis = 60_000L,
        maxRewards = 60,
        maxRepeatPerTarget = 2
    )

    val WALK_EARN = AppProfile(
        key = "walkearn",
        name = "WALK EARN",
        packageName = "com.walkearn.stepscash",
        collectionTabs = listOf("Home", "Mine"),
        tabPoints = mapOf("Home" to (120 to 2570), "Mine" to (1080 to 2570)),
        rewardTerms = listOf(
            "Lucky box", "GO", "CLAIM", "WATCH", "COLLECT", "DAILY", "BONUS",
            "CHECK IN", "CHECK-IN", "GET", "Get Gift", "領取"
        ),
        avoidTerms = listOf(
            "Home", "Report", "Cash out", "Mine", "Cash for you", "Your coins",
            "Stock: 0", "Log out", "Settings", "Privacy", "Terms", "Redeem",
            "Withdraw", "History", "Invite", "About", "Walk Earn", "Walk everyday",
            "This may contain ads", "Your Goal", "withdrew", "mins ago",
            // Descriptive lines that merely contain 領取 ("…即可領取點數") are
            // not buttons; tapping them opened a sleep-record flow on 11-12.
            "即可領取", "睡眠", "昨晚", "錄咋晚", "點數"
        ),
        exactTerms = listOf("GO", "GET"),
        chromeTerms = listOf("Home", "Report", "Cash out", "Mine"),
        adMinWatchMillis = 10_000L,
        adTimeoutMillis = 90_000L,
        maxRewards = 8,
        maxRepeatPerTarget = 3
    )

    val HAHAGO = AppProfile(
        key = "hahago",
        name = "HAHAGO",
        packageName = "com.gimmatek.hahago.aha",
        // Where the rewards are, from the user's screenshots (2026-11-14):
        // the step milestones on 首頁 (2k ... 10k and the big 20 H bubble),
        // 上傳步數 on 365, and the 簽到 buttons on 個人 > 任務 (每月任務).
        collectionTabs = listOf("首頁", "365", "任務"),
        tabPoints = mapOf("365" to (328 to 2480), "首頁" to (140 to 2480), "個人" to (1078 to 2480)),
        tabPaths = mapOf("任務" to listOf("個人", "任務")),
        rewardTerms = listOf(
            "上傳步數", "簽到", "領取", "領取獎勵", "補簽", "觀看廣告", "觀看影片",
            "2k", "4k", "6k", "8k", "10k", "20"
        ),
        // Bare labels only: "每日簽到" / "每日7500步簽到" are titles, and the
        // milestone numbers must not match "12 / 7分" or a date.
        // 上傳步數 too: "今日上傳步數：9,977 步" is a statistics line and was
        // tapped six times on 2026-11-14 11:46.
        exactTerms = listOf("上傳步數", "簽到", "2k", "4k", "6k", "8k", "10k", "20"),
        avoidTerms = listOf(
            "已完成", "已領取", "已補領", "已簽到", "補領", "Premium", "一鍵領取",
            "當日哈幣", "HAHAGO Premium", "去除廣告", "不影響挖礦", "挖礦", "兌換",
            "取消", "返回", "首頁", "365", "訓練", "揪團", "個人", "更多", "規則",
            "說明", "分享", "邀請", "設定", "登出", "商店", "購買", "儲值", "活動辦法",
            "上傳紀錄", "還沒上傳", "前往", "得獎名單", "總次數",
            "今日上傳步數", "今日獲得積分", "更新時間", "總步數", "總積分"
        ),
        chromeTerms = listOf("首頁", "365", "兌換", "訓練", "揪團", "個人"),
        pageMarkers = listOf("熱門主打", "我想了解更多", "了解更多", "分享"),
        adMinWatchMillis = 15_000L,
        adTimeoutMillis = 100_000L,
        maxRewards = 12,
        maxRepeatPerTarget = 2
    )

    val LINE_WALK = AppProfile(
        key = "linewalk",
        name = "LINE 散步趣",
        packageName = "com.linecorp.LGWKTW",
        collectionTabs = listOf("首頁", "步數", "時間"),
        tabPoints = mapOf("首頁" to (152 to 347), "步數" to (458 to 347), "時間" to (762 to 347)),
        rewardTerms = listOf("獲得金幣", "獲得票券"),
        avoidTerms = listOf(
            "開始遊玩", "輕鬆賺", "即可獲得", "雙重", "POINTS", "完成任務", "挑戰", "Sponsored",
            // The 邀請好友 promotion ("雙方都可獲得金幣!") is never a reward;
            // its words were tapped three times on 2026-11-13 15:02.
            "可獲得", "雙方都", "邀請", "最高可獲", "快來領取",
            // Help text, not a button: "獲得金幣的流程" was tapped 10+ times
            // (2026-11-13 20:09).
            "的流程", "說明", "如何"
        ),
        chromeTerms = listOf("集點", "好友", "使用", "精選"),
        // "無法載入廣告 / 請稍後再試 / 關閉": LINE has no ad right now. Not
        // recognised, the run tapped the button behind it 20+ times
        // (2026-11-13 14:33).
        infoDialogTerms = listOf("無法載入廣告", "請稍後再試"),
        adHardMarkersExtra = listOf("載入中"),
        dialogTermsExtra = listOf("張票券", "枚金幣"),
        adMinWatchMillis = 10_000L,
        adTimeoutMillis = 100_000L,
        maxRewards = 40,
        maxRepeatPerTarget = 40,
        launchWaitMillis = 8_000L,
        lineRules = true
    )

    val LINE_LOTTERY = AppProfile(
        key = "linelottery",
        name = "LINE 散步趣［抽抽樂］",
        packageName = "com.linecorp.LGWKTW",
        collectionTabs = emptyList(),
        tabPoints = mapOf("首頁" to (152 to 347), "步數" to (458 to 347), "時間" to (762 to 347)),
        rewardTerms = listOf("獲得金幣", "獲得票券"),
        avoidTerms = emptyList(),
        chromeTerms = listOf("集點", "好友", "使用", "精選"),
        adHardMarkersExtra = listOf("載入中"),
        dialogTermsExtra = listOf("張票券", "枚金幣"),
        adMinWatchMillis = 10_000L,
        adTimeoutMillis = 100_000L,
        maxRewards = 0,
        maxRepeatPerTarget = 1,
        launchWaitMillis = 8_000L,
        lineRules = true,
        lotteryMode = true,
        includeInAll = false
    )

    val all: List<AppProfile> = listOf(BITWALK, AHEALTH, WALK_EARN, HAHAGO, LINE_WALK, LINE_LOTTERY)

    /** What "all" means: special options (LINE lottery) are excluded. */
    val default: List<AppProfile> = all.filter { it.includeInAll }

    fun byKey(raw: String?): AppProfile? {
        val key = raw?.trim()?.lowercase() ?: return null
        return when (key) {
            "bitwalk", "bit" -> BITWALK
            "ahealth", "a-health" -> AHEALTH
            "walkearn", "walk-earn", "walk" -> WALK_EARN
            "hahago", "ha" -> HAHAGO
            "linewalk", "line", "line-walk" -> LINE_WALK
            "linelottery", "lottery", "line-lottery" -> LINE_LOTTERY
            else -> all.firstOrNull { it.key == key }
        }
    }

    /** Resolves a comma separated CLI value; empty or "all" means the default set. */
    fun resolve(raw: String?): List<AppProfile> {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.equals("all", ignoreCase = true)) return default
        val result = LinkedHashMap<String, AppProfile>()
        for (part in value.split(',')) {
            if (part.isBlank()) continue
            if (part.trim().equals("all", ignoreCase = true)) return default
            val profile = byKey(part) ?: continue
            result[profile.key] = profile
        }
        return result.values.toList()
    }
}
