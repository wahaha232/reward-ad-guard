package com.rewardadguard.app.collector

/**
 * Screen rules for Cashzine (`com.sky.sea.cashzine` 4.49), from the
 * 2026-11-18 evaluation on the user's phone (D:\Claude\CASHZINE\docs).
 *
 * Kept free of Android so every rule can be tested on the JVM. The engine
 * ([CollectorEngine.collectCashzine]) does the tapping; these only say what is
 * on screen and where.
 *
 * Screens (1220x2712 reference):
 *  - bottom bar: Home / Shorts / Invite / Follow / Me
 *  - Invite page: top tabs Invitation / Offer, top right "+60" (or a mm:ss
 *    countdown once watched; one ad per 60 minutes)
 *  - Offer: daily "Check-in" (an ad), "Earned by Reading ... Read now",
 *    "Spin to win lucky draw"
 *  - Lucky Draw: WebView "Spinning Wheel", "Remaining Chances: N", "GO"
 *  - reading page (FLAG_SECURE, nodes only): top right counter, "Claim" when
 *    a tier is ready; the "Earn coins daily" panel lists the tiers
 *  - "Awarded +N coins" dialog after every reward
 */
/**
 * Test-only overrides for a Cashzine run, set by the debug entry point
 * (`collector_run --es steps coins,lucky,reading --es readmin 10`) and cleared
 * after the run. Null means the normal run.
 */
object CashzineRunOptions {
    @Volatile var steps: Set<String>? = null
    @Volatile var readingMaxMillis: Long? = null
    @Volatile var shortsMaxMillis: Long? = null

    fun clear() {
        steps = null
        readingMaxMillis = null
        shortsMaxMillis = null
    }
}

object CashzineRules {

    const val PACKAGE = "com.sky.sea.cashzine"

    /** Bottom tabs; "Me" is left out of the chrome check ("me" is inside "home"). */
    val CHROME_TERMS = listOf("Home", "Shorts", "Invite", "Follow")

    /**
     * Banner ads (foodpanda, PhilMoney, OKX ...) sit on every page with a red
     * download button; offer walls and invitations need the user.
     */
    val AVOID_TERMS = listOf(
        "Download", "Install", "foodpanda", "PhilMoney", "OKX", "Tapjoy", "Adjoe",
        "Invite friends", "Click to Invite", "Invite Now", "Copy", "Go get it now",
        "Win Win Wit", "quiz", "Coin Redemption", "Novelah", "Readward", "FunDrama"
    )

    /** Lucky Draw sessions (Event Description, rule 1): [start, end) hours. */
    val LUCKY_SESSIONS = listOf(5 to 10, 11 to 15, 16 to 19, 20 to 24)

    /**
     * One of Cashzine's own screens (home, Offer, the Lucky Draw web page,
     * the reader), as opposed to an ad SDK's Activity inside the same app.
     * Its pages carry a banner ad whose red download icon looks like ">>".
     */
    fun isOwnPage(packageName: String?, activity: String?): Boolean =
        packageName == PACKAGE && activity?.startsWith("com.novelah.") == true

    /**
     * Steps of one Cashzine run, in order. A test can run only some of them
     * ([CashzineRunOptions]); the user's runs always do all of them.
     */
    val STEPS = listOf("checkin", "watch60", "lucky", "coins", "reading", "shorts", "extra", "watch60_again")

    /** One +60 ad per hour (the button turns into a 59:59 countdown). */
    const val WATCH_COOLDOWN_MILLIS = 60L * 60L * 1000L

    // ------------------------------------------------------------ helpers

    private fun loose(text: String) = TextMatch.normalizeLoose(text)

    private fun box(snapshot: Snapshot, predicate: (String) -> Boolean): TextBox? =
        snapshot.boxes.firstOrNull { predicate(loose(it.text)) }

    private fun TextBox.target(source: String = "cashzine") =
        Target(text, bounds, 100, source)

    private fun exact(snapshot: Snapshot, word: String, area: (Box) -> Boolean = { true }): TextBox? {
        val wanted = loose(word)
        // Node boxes first: they are the real views.
        return snapshot.boxes
            .filter { loose(it.text) == wanted && area(it.bounds) }
            .sortedBy { if (it.source == TextSource.NODE) 0 else 1 }
            .firstOrNull()
    }

    private fun avoided(text: String): Boolean {
        val value = loose(text)
        return AVOID_TERMS.any { loose(it) in value }
    }

    // ------------------------------------------------------------ navigation

    /** A bottom tab ("Home", "Invite" ...) in the bottom 12% of the screen. */
    fun bottomTab(snapshot: Snapshot, name: String): Target? =
        exact(snapshot, name) { it.top >= snapshot.height * 0.88 }?.target("bottom_tab")

    /** The bottom bar is on screen (two of its tabs read). */
    fun hasBottomBar(snapshot: Snapshot): Boolean =
        CHROME_TERMS.count { bottomTab(snapshot, it) != null } >= 2

    /** "Invitation" / "Offer" at the top of the Invite page. */
    fun topTab(snapshot: Snapshot, name: String): Target? =
        exact(snapshot, name) { it.bottom <= snapshot.height * 0.10 }?.target("top_tab")

    fun onInvitePage(snapshot: Snapshot): Boolean =
        topTab(snapshot, "Invitation") != null && topTab(snapshot, "Offer") != null

    /** The Offer tab's content: the daily check-in card. */
    fun onOfferPage(snapshot: Snapshot): Boolean {
        if (!onInvitePage(snapshot)) return false
        val text = snapshot.looseText
        return "todaysbenefits" in text || "checkedinfor" in text || "earned by reading".let(::loose) in text
    }

    // ------------------------------------------------------------ +60 every hour

    sealed class WatchState {
        data class Ready(val button: Target) : WatchState()
        data class Cooldown(val millis: Long) : WatchState()
        object Unknown : WatchState()
    }

    private val PLUS_COINS = Regex("^\\+\\s*(\\d{1,3})$")
    private val COUNTDOWN = Regex("^(\\d{1,2})\\s*[:：]\\s*(\\d{2})(?:\\s*[:：]\\s*(\\d{2}))?$")

    /** The orange "+60" / "59:52" pill at the top right of the Invite page. */
    fun watchState(snapshot: Snapshot): WatchState {
        for (box in snapshot.boxes) {
            if (box.bounds.bottom > snapshot.height * 0.10) continue
            if (box.bounds.centerX < snapshot.width * 0.7) continue
            val raw = box.text.trim()
            if (PLUS_COINS.matches(raw)) return WatchState.Ready(box.target("watch_pill"))
            COUNTDOWN.matchEntire(raw)?.let { m ->
                val a = m.groupValues[1].toLong()
                val b = m.groupValues[2].toLong()
                val c = m.groupValues[3].toLongOrNull()
                val seconds = if (c != null) a * 3600 + b * 60 + c else a * 60 + b
                return WatchState.Cooldown(seconds * 1000L)
            }
        }
        return WatchState.Unknown
    }

    /** "Watch videos + 60 coins" in the sheet the pill opens. */
    fun watchVideosButton(snapshot: Snapshot): Target? =
        snapshot.boxes.firstOrNull {
            val v = loose(it.text)
            v.startsWith("watchvideos") && v.any(Char::isDigit) &&
                it.bounds.centerY in (snapshot.height * 0.2).toInt()..(snapshot.height * 0.85).toInt()
        }?.target("watch_videos")

    /**
     * Cashzine has no ad to show: "Video is not available currently ... The
     * number of videos is limited, please make sure your internet connection
     * is good, and try for 2 more times. If you cannot view it, please come
     * again tomorrow." with "Confirm" (2026-11-24 16:29, after tapping +60).
     */
    fun videoUnavailable(snapshot: Snapshot): Target? {
        val text = snapshot.looseText
        if ("videoisnotavailable" !in text && "numberofvideosislimited" !in text) return null
        return (exact(snapshot, "Confirm") ?: exact(snapshot, "OK"))?.target("no_video_confirm")
            ?: Target("[back]", Box(0, 0, 0, 0), 0, "no_video_back")
    }

    // ------------------------------------------------------------ check-in

    sealed class CheckIn {
        data class Ready(val button: Target) : CheckIn()
        object Done : CheckIn()
        object NotFound : CheckIn()
    }

    /**
     * The "Check-in" button under "Earn 650 coins by watching videos". After
     * the day's check-in it turns grey: "Receive another 40 coins for free tomorrow".
     */
    fun checkIn(snapshot: Snapshot): CheckIn {
        val text = snapshot.looseText
        if ("receiveanother" in text && "tomorrow" in text) return CheckIn.Done
        val button = snapshot.boxes.firstOrNull {
            val v = loose(it.text)
            (v == "checkin" || v == "adcheckin") && it.bounds.top > snapshot.height * 0.08 &&
                snapshot.disabled.none { d -> d.containsPoint(it.bounds.centerX, it.bounds.centerY) }
        }
        return if (button != null) CheckIn.Ready(button.target("check_in")) else CheckIn.NotFound
    }

    // ------------------------------------------------------------ reward dialogs

    /** "Awarded +N coins" (coins, check-in, +60, reading tiers). */
    fun awarded(snapshot: Snapshot): Boolean = box(snapshot) { it == "awarded" || it.startsWith("awarded") } != null

    private val AWARD_AMOUNT = Regex("\\+\\s*(\\d{1,5})\\s*coins?", RegexOption.IGNORE_CASE)

    /** The N of "+N coins" in an Awarded dialog, when readable. */
    fun awardedAmount(snapshot: Snapshot): Int? {
        if (!awarded(snapshot)) return null
        AWARD_AMOUNT.find(snapshot.text)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /** "Win double rewards x2" (the floating coin's ad offer). */
    fun doubleButton(snapshot: Snapshot): Target? =
        box(snapshot) { "windoublerewards" in it || "doublerewards" in it }?.target("double")

    /** The check-in dialog has only "OK". */
    fun okButton(snapshot: Snapshot): Target? =
        exact(snapshot, "OK") { it.centerY in (snapshot.height * 0.25).toInt()..(snapshot.height * 0.85).toInt() }
            ?.target("ok")

    /**
     * The offer for one more ad inside an Awarded dialog: "Win 51 coins"
     * (after +60), "Watch videos + 26 coins" (after a reading tier) or
     * "Win double rewards" (a coin, a wheel prize). Taken since 2026-11-24
     * (user: 領取後再看一支); only one more per reward.
     */
    fun extraAdOffer(snapshot: Snapshot): Target? {
        if (!awarded(snapshot)) return null
        return snapshot.boxes.firstOrNull {
            val v = loose(it.text)
            (v.startsWith("win") && v.endsWith("coins")) || v.startsWith("watchvideos") ||
                "windoublerewards" in v || "doublerewards" in v
        }?.takeIf { it.bounds.centerY in (snapshot.height * 0.2).toInt()..(snapshot.height * 0.85).toInt() }
            ?.target("extra_ad")
    }

    /** "+600" cards on Offer whose saved coins are claimed by watching videos. */
    data class ExtraCard(val kind: String, val saved: Int, val total: Int, val button: Target?, val rowTexts: List<String>)

    private val EXTRA_PROGRESS = Regex("^(\\d{1,4})\\s*/\\s*(\\d{2,4})$")
    private val EXTRA_SKIP = setOf("readnow", "watchnow", "gogetitnow")

    /**
     * The extra-reward cards: "Complete reading tasks to earn extra rewards"
     * (reading) and "Get extra rewards by watching shorts" (shorts), each
     * with a "N/600" progress and a button on the same row. Only "Read now"
     * was seen so far (0/600, 2026-11-24 16:14); the claim button shows once
     * coins are saved, so any other button on that row is taken as it.
     */
    fun extraCards(snapshot: Snapshot): List<ExtraCard> {
        val result = ArrayList<ExtraCard>()
        for (box in snapshot.boxes) {
            val m = EXTRA_PROGRESS.matchEntire(box.text.trim()) ?: continue
            val total = m.groupValues[2].toInt()
            if (total != 600) continue
            if (box.bounds.top < snapshot.height * 0.10 || box.bounds.bottom > snapshot.height * 0.88) continue
            val above = snapshot.boxes
                .filter { it.bounds.bottom <= box.bounds.top + 10 && box.bounds.top - it.bounds.bottom < 420 }
                .map { loose(it.text) }
            val kind = when {
                above.any { "reading" in it } -> "reading"
                above.any { "shorts" in it } -> "shorts"
                else -> continue
            }
            val row = snapshot.boxes.filter { kotlin.math.abs(it.bounds.centerY - box.bounds.centerY) < 90 }
            val button = row
                .filter { it !== box && it.bounds.left > box.bounds.right && loose(it.text) !in EXTRA_SKIP && loose(it.text).isNotEmpty() }
                .filter { val v = loose(it.text); v.any(Char::isLetter) }
                .maxByOrNull { it.bounds.left }
            result.add(ExtraCard(kind, m.groupValues[1].toInt(), total, button?.target("extra_claim"), row.map { it.text }))
        }
        return result
    }

    // ------------------------------------------------------------ Lucky Draw

    fun luckySessionOpen(hour: Int): Boolean = LUCKY_SESSIONS.any { (from, to) -> hour in from until to }

    /**
     * The "Go get it now" button of the "Spin to win lucky draw" row on Offer.
     * The title itself does nothing when tapped (run 2026-11-24 13:26), so
     * the row counts only once its button is on screen.
     */
    fun luckyEntry(snapshot: Snapshot): Target? {
        val title = box(snapshot) { "spintowinluckydraw" in it }
            ?.takeIf { it.bounds.top > snapshot.height * 0.12 && it.bounds.bottom < snapshot.height * 0.85 }
            ?: return null
        return snapshot.boxes
            .filter {
                loose(it.text) == "gogetitnow" &&
                    it.bounds.centerY in (title.bounds.top - 60)..(title.bounds.bottom + 320) &&
                    it.bounds.bottom < snapshot.height * 0.88
            }
            .minByOrNull { kotlin.math.abs(it.bounds.centerY - title.bounds.centerY) }
            ?.target("lucky_entry")
    }

    fun onWheel(snapshot: Snapshot): Boolean {
        val text = snapshot.looseText
        return "spinningwheel" in text || "remainingchances" in text
    }

    private val CHANCES = Regex("Remaining\\s*Chances\\s*[:：]?\\s*(\\d{1,2})(?!\\d)", RegexOption.IGNORE_CASE)

    /** "Remaining Chances: 10"; "Remaining Chances for Doubling: 0" is not it. */
    fun remainingChances(snapshot: Snapshot): Int? {
        for (box in snapshot.boxes) {
            val raw = box.text.replace('\n', ' ')
            if (raw.contains("doubling", ignoreCase = true)) continue
            CHANCES.find(raw)?.let { return it.groupValues[1].toIntOrNull() }
        }
        // Label and number may be two nodes: the number right of the label.
        val label = snapshot.boxes.firstOrNull {
            val v = loose(it.text)
            v.startsWith("remainingchances") && "doubling" !in v
        } ?: return null
        return snapshot.boxes
            .filter {
                it !== label && it.text.trim().all(Char::isDigit) && it.text.isNotBlank() &&
                    kotlin.math.abs(it.bounds.centerY - label.bounds.centerY) < label.bounds.height &&
                    it.bounds.left >= label.bounds.left
            }
            .minByOrNull { it.bounds.left }
            ?.text?.trim()?.toIntOrNull()
    }

    /** The wheel's centre "GO". */
    fun goButton(snapshot: Snapshot): Target? =
        exact(snapshot, "GO") { it.centerY in (snapshot.height * 0.25).toInt()..(snapshot.height * 0.85).toInt() }
            ?.target("go")

    sealed class DrawResult {
        /** "the gift package is empty" / any prize; [close] leaves the result. */
        data class Shown(val won: Boolean, val text: String, val close: Target?) : DrawResult()
        object None : DrawResult()
    }

    private val RESULT_CLOSE = listOf("Try Again", "OK", "Collect", "Claim", "Got it", "Confirm", "Continue")

    fun drawResult(snapshot: Snapshot): DrawResult {
        if (awarded(snapshot)) {
            // A prize comes as the same "Awarded +N coins" dialog as a coin
            // (2026-11-24 13:49, +25); it is closed with Back like that one.
            val amount = awardedAmount(snapshot)
            return DrawResult.Shown(true, amount?.let { "+$it coins" } ?: "Awarded", null)
        }
        val text = snapshot.looseText
        val empty = "giftpackageisempty" in text || "keeptrying" in text
        val won = !empty && ("congratulations" in text || "youwon" in text || "youhavewon" in text ||
            Regex("\\+\\s*\\d+\\s*coins", RegexOption.IGNORE_CASE).containsMatchIn(snapshot.text) && "tryagain" !in text)
        if (!empty && !won && "tryagain" !in text) return DrawResult.None
        val close = RESULT_CLOSE.firstNotNullOfOrNull { word ->
            exact(snapshot, word) { it.centerY in (snapshot.height * 0.2).toInt()..(snapshot.height * 0.9).toInt() }
        }?.target("draw_close")
        val line = snapshot.boxes.firstOrNull {
            val v = loose(it.text)
            "giftpackage" in v || "congratulations" in v || "coins" in v
        }?.text.orEmpty()
        return DrawResult.Shown(won, line, close)
    }

    // ------------------------------------------------------------ reading

    /**
     * A "Read now" on Offer (beside +1800 Earned by Reading, or beside the
     * +600 extra; both open the reader). Its title often sits under the top
     * banner, so the button alone is enough; the highest one wins.
     */
    fun readNowButton(snapshot: Snapshot): Target? =
        snapshot.boxes
            .filter {
                loose(it.text) == "readnow" &&
                    it.bounds.top > snapshot.height * 0.12 && it.bounds.bottom < snapshot.height * 0.88
            }
            .minByOrNull { it.bounds.top }
            ?.target("read_now")

    /**
     * "Read now" with no book open lands on the home page (2026-11-24 16:33):
     * the first book under "Editor's Choice" - its cover, just above its title.
     */
    fun firstBookCover(snapshot: Snapshot): Target? {
        val choice = box(snapshot) { it == "editorschoice" } ?: return null
        val title = snapshot.boxes
            .filter {
                it.bounds.top > choice.bounds.bottom + snapshot.height * 0.08 &&
                    it.bounds.centerX < snapshot.width * 0.36 &&
                    it.bounds.bottom < snapshot.height * 0.86 &&
                    loose(it.text).length >= 4 && !avoided(it.text)
            }
            .minByOrNull { it.bounds.top } ?: return null
        val y = title.bounds.top - (snapshot.height * 0.05).toInt()
        if (y <= choice.bounds.bottom) return null
        return Target(title.text, Box(title.bounds.left, y - 20, title.bounds.right, y + 20), 100, "book_cover")
    }

    // ------------------------------------------------------------ shorts

    /** "Watch Now" of "Earned by Watching Shorts" on Offer (Daily tasks). */
    fun shortsEntry(snapshot: Snapshot): Target? {
        val title = box(snapshot) { it == "earnedbywatchingshorts" }
            ?.takeIf { it.bounds.top > snapshot.height * 0.10 && it.bounds.bottom < snapshot.height * 0.85 }
            ?: return null
        return snapshot.boxes
            .filter {
                loose(it.text) == "watchnow" &&
                    it.bounds.centerY in (title.bounds.top - 40)..(title.bounds.bottom + 260) &&
                    it.bounds.bottom < snapshot.height * 0.88
            }
            .minByOrNull { kotlin.math.abs(it.bounds.centerY - title.bounds.centerY) }
            ?.target("shorts_entry")
    }

    /**
     * A reward to claim while watching shorts: "Claim" / "Claim now" /
     * "Collect" anywhere on the shorts screen (its layout is not known yet).
     */
    fun shortsClaim(snapshot: Snapshot): Target? =
        snapshot.boxes.firstOrNull {
            val v = loose(it.text)
            (v == "claim" || v == "claimnow" || v == "collect" || v == "receive") &&
                it.bounds.top > snapshot.height * 0.05 && it.bounds.bottom < snapshot.height * 0.95
        }?.target("shorts_claim")

    /** NovelDetail's "Start Reading" / "Continue Reading". */
    fun startReadingButton(snapshot: Snapshot): Target? =
        box(snapshot) { it == "startreading" || it == "continuereading" || it == "readnow" }
            ?.takeIf { it.bounds.top > snapshot.height * 0.5 }
            ?.target("start_reading")

    fun isReadingActivity(activity: String?): Boolean =
        activity?.contains("page.read.", ignoreCase = true) == true ||
            activity?.endsWith("ReadActivity") == true

    private fun readerCorner(snapshot: Snapshot, box: TextBox): Boolean =
        box.bounds.bottom <= snapshot.height * 0.08 && box.bounds.centerX >= snapshot.width * 0.6

    /** The reading page's top right turned from a counter into "Claim". */
    fun readerClaim(snapshot: Snapshot): Target? =
        snapshot.boxes.firstOrNull { loose(it.text) == "claim" && readerCorner(snapshot, it) }?.target("reader_claim")

    /** The reading page's top right counter ("30"), or null. */
    fun readerCounter(snapshot: Snapshot): String? =
        snapshot.boxes.firstOrNull {
            readerCorner(snapshot, it) && it.text.trim().isNotEmpty() &&
                it.text.trim().all { c -> c.isDigit() || c == ':' || c == 's' || c == '+' }
        }?.text?.trim()

    /** The "Earn coins daily" panel the reader's Claim opens. */
    fun earnPanel(snapshot: Snapshot): Boolean = "earncoinsdaily" in snapshot.looseText

    /**
     * The tier ready to claim: a "Claim" label inside the tier row (not the
     * reader's corner), else "Claim now" beside Earned by Reading.
     */
    fun tierClaim(snapshot: Snapshot): Target? {
        val tier = snapshot.boxes.firstOrNull {
            loose(it.text) == "claim" && !readerCorner(snapshot, it) && it.bounds.top > snapshot.height * 0.3
        }
        if (tier != null) return tier.target("tier_claim")
        return snapshot.boxes.firstOrNull {
            loose(it.text) == "claimnow" && it.bounds.top > snapshot.height * 0.3
        }?.target("claim_now")
    }

    // ------------------------------------------------------------ floating coins

    /**
     * Gold floating coins on the home page (position and count random).
     *
     * The 2026-11-18 test (3 screenshots, 8 coins, 0 false hits) used HSV
     * H 18-35, S >= 120, V >= 200, a 9x9 closing, 90-200 px square boxes,
     * aspect 0.8-1.25, fill > 0.6. Here the image is sampled every [STEP] px
     * (1220x2712 -> 305x678), which is the same size range at a quarter of the
     * work and keeps a closing of 3x3 equivalent to about 9 px.
     *
     * The bottom bar (its Invite coin filled 0.57) and the header (the money
     * bag) are excluded by position.
     */
    fun findCoins(argb: IntArray, width: Int, height: Int): List<Box> {
        if (width <= 0 || height <= 0 || argb.size < width * height) return emptyList()
        val sw = width / STEP
        val sh = height / STEP
        if (sw < 3 || sh < 3) return emptyList()
        val mask = BooleanArray(sw * sh)
        for (y in 0 until sh) {
            val row = y * STEP * width
            for (x in 0 until sw) {
                mask[y * sw + x] = isGold(argb[row + x * STEP])
            }
        }
        val closed = erode(dilate(mask, sw, sh), sw, sh)
        val seen = BooleanArray(sw * sh)
        val result = ArrayList<Box>()
        val queue = IntArray(sw * sh)
        val top = (height * COIN_TOP).toInt()
        val bottom = (height * COIN_BOTTOM).toInt()
        for (start in 0 until sw * sh) {
            if (!closed[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var minX = sw
            var minY = sh
            var maxX = -1
            var maxY = -1
            var count = 0
            while (head < tail) {
                val p = queue[head++]
                val px = p % sw
                val py = p / sw
                count++
                if (px < minX) minX = px
                if (px > maxX) maxX = px
                if (py < minY) minY = py
                if (py > maxY) maxY = py
                if (px > 0) { val q = p - 1; if (closed[q] && !seen[q]) { seen[q] = true; queue[tail++] = q } }
                if (px < sw - 1) { val q = p + 1; if (closed[q] && !seen[q]) { seen[q] = true; queue[tail++] = q } }
                if (py > 0) { val q = p - sw; if (closed[q] && !seen[q]) { seen[q] = true; queue[tail++] = q } }
                if (py < sh - 1) { val q = p + sw; if (closed[q] && !seen[q]) { seen[q] = true; queue[tail++] = q } }
            }
            val bw = (maxX - minX + 1) * STEP
            val bh = (maxY - minY + 1) * STEP
            if (bw !in COIN_MIN..COIN_MAX || bh !in COIN_MIN..COIN_MAX) continue
            val aspect = bw.toDouble() / bh
            if (aspect < 0.8 || aspect > 1.25) continue
            val fill = count.toDouble() / ((maxX - minX + 1) * (maxY - minY + 1))
            if (fill <= COIN_FILL) continue
            // A coin is solid; the gold "0" of a "$500" banner is a ring
            // (gift0, 2026-11-18 11:23): its middle is not gold.
            if (centreFill(closed, sw, minX, minY, maxX, maxY) < COIN_CENTRE_FILL) continue
            val box = Box(minX * STEP, minY * STEP, (maxX + 1) * STEP, (maxY + 1) * STEP)
            if (box.centerY < top || box.centerY > bottom) continue
            result.add(box)
        }
        return result.sortedBy { it.centerY }
    }

    /** Share of gold in the middle third of a component's box. */
    private fun centreFill(mask: BooleanArray, w: Int, minX: Int, minY: Int, maxX: Int, maxY: Int): Double {
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val x0 = minX + bw / 3
        val x1 = maxX - bw / 3
        val y0 = minY + bh / 3
        val y1 = maxY - bh / 3
        var all = 0
        var gold = 0
        for (y in y0..y1) for (x in x0..x1) {
            all++
            if (mask[y * w + x]) gold++
        }
        return if (all == 0) 0.0 else gold.toDouble() / all
    }

    private const val COIN_CENTRE_FILL = 0.5
    private const val STEP = 4
    private const val COIN_MIN = 88
    private const val COIN_MAX = 204
    private const val COIN_FILL = 0.6
    /** Below the header with the money bag, above the bottom bar. */
    private const val COIN_TOP = 0.08
    private const val COIN_BOTTOM = 0.87

    /** OpenCV HSV: H 0-180, S and V 0-255. */
    fun isGold(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < 200) return false
        val delta = max - min
        if (delta == 0) return false
        val s = delta * 255 / max
        if (s < 120) return false
        val hue = when (max) {
            r -> 60.0 * (((g - b).toDouble() / delta).let { if (it < 0) it + 6 else it })
            g -> 60.0 * ((b - r).toDouble() / delta + 2)
            else -> 60.0 * ((r - g).toDouble() / delta + 4)
        }
        val h = hue / 2.0
        return h in 18.0..35.0
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var hit = false
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h && src[ny * w + nx]) { hit = true; break@loop }
            }
            out[y * w + x] = hit
        }
        return out
    }

    private fun erode(src: BooleanArray, w: Int, h: Int): BooleanArray {
        val out = BooleanArray(src.size)
        for (y in 0 until h) for (x in 0 until w) {
            var all = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until w || ny !in 0 until h || !src[ny * w + nx]) { all = false; break@loop }
            }
            out[y * w + x] = all
        }
        return out
    }

    /** True when the text is a banner ad or a page the run must not open. */
    fun isAvoided(text: String): Boolean = avoided(text)
}
