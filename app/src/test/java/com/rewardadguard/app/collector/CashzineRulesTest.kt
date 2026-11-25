package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Cashzine screens as read on 2026-11-18 (1220x2712). */
class CashzineRulesTest {

    private fun b(text: String, l: Int, t: Int, r: Int, bt: Int, source: TextSource = TextSource.NODE) =
        TextBox(text, Box(l, t, r, bt), source)

    private fun snap(vararg boxes: TextBox, activity: String? = null, disabled: List<Box> = emptyList()) = Snapshot(
        width = 1220, height = 2712, capturedAt = 0L,
        foregroundPackage = CashzineRules.PACKAGE, foregroundActivity = activity,
        boxes = boxes.toList(), disabled = disabled
    )

    private val bottomBar = arrayOf(
        b("Home", 60, 2440, 180, 2490), b("Shorts", 300, 2440, 420, 2490),
        b("Invite", 540, 2440, 680, 2490), b("Follow", 770, 2440, 900, 2490), b("Me", 1030, 2440, 1100, 2490)
    )

    @Test
    fun bottomBarAndTabs() {
        val s = snap(*bottomBar)
        assertTrue(CashzineRules.hasBottomBar(s))
        assertEquals(610, CashzineRules.bottomTab(s, "Invite")!!.centerX)
        // "Home" inside a book title higher up is not the tab.
        assertNull(CashzineRules.bottomTab(snap(b("Home", 60, 1200, 180, 1250)), "Home"))
    }

    @Test
    fun theProfileChromeMatchesTheBottomBar() {
        val s = snap(*bottomBar)
        assertTrue(CollectorRules.hasAppChrome(s, CollectorProfiles.CASHZINE))
        assertFalse(CollectorRules.hasAppChrome(snap(b("Home", 60, 2440, 180, 2490)), CollectorProfiles.CASHZINE))
    }

    @Test
    fun cashzineRunsFirst() {
        assertEquals("cashzine", CollectorProfiles.builtIn.first().key)
        assertTrue(CollectorProfiles.CASHZINE.cashzineMode)
        assertEquals(CollectorProfiles.CASHZINE, CollectorProfiles.byKey("cz"))
    }

    @Test
    fun invitePageWatchPill() {
        val top = arrayOf(b("Invitation", 480, 160, 640, 220), b("Offer", 700, 160, 820, 220))
        val ready = snap(*top, b("+60", 1040, 160, 1130, 220))
        assertTrue(CashzineRules.onInvitePage(ready))
        val state = CashzineRules.watchState(ready)
        assertTrue(state is CashzineRules.WatchState.Ready)
        val cool = CashzineRules.watchState(snap(*top, b("48:29", 1030, 160, 1140, 220)))
        assertEquals(CashzineRules.WatchState.Cooldown((48 * 60 + 29) * 1000L), cool)
        assertEquals(CashzineRules.WatchState.Unknown, CashzineRules.watchState(snap(*top)))
    }

    @Test
    fun watchVideosButtonInTheSheet() {
        val s = snap(b("Watch videos to get rewards", 200, 900, 1000, 960), b("Watch videos + 60 coins", 300, 1060, 920, 1120))
        assertEquals("Watch videos + 60 coins", CashzineRules.watchVideosButton(s)!!.text)
    }

    @Test
    fun checkInReadyAndDone() {
        val ready = snap(b("Checked in for 0 days", 100, 600, 600, 640), b("Check-in", 500, 1110, 720, 1170))
        val state = CashzineRules.checkIn(ready)
        assertTrue(state is CashzineRules.CheckIn.Ready)
        assertEquals("Check-in", (state as CashzineRules.CheckIn.Ready).button.text)
        val done = snap(b("Checked in for 1 days", 100, 600, 600, 640), b("Receive another 40 coins for free tomorrow", 200, 1110, 1000, 1170))
        assertEquals(CashzineRules.CheckIn.Done, CashzineRules.checkIn(done))
        assertEquals(CashzineRules.CheckIn.NotFound, CashzineRules.checkIn(snap(*bottomBar)))
    }

    @Test
    fun awardedDialog() {
        val s = snap(b("Awarded", 450, 700, 770, 760), b("+12 coins", 480, 820, 740, 900), b("Win double rewards", 300, 1050, 920, 1120))
        assertTrue(CashzineRules.awarded(s))
        assertEquals(12, CashzineRules.awardedAmount(s))
        assertNotNull(CashzineRules.doubleButton(s))
        assertFalse(CashzineRules.awarded(snap(*bottomBar)))
    }

    @Test
    fun extraAdOffersInAnAwardedDialog() {
        val award = b("Awarded", 450, 700, 770, 760)
        assertEquals("Win 51 coins", CashzineRules.extraAdOffer(snap(award, b("Win 51 coins", 400, 1000, 820, 1060)))!!.text)
        assertNotNull(CashzineRules.extraAdOffer(snap(award, b("Watch videos + 26 coins", 300, 1000, 920, 1060))))
        assertNotNull(CashzineRules.extraAdOffer(snap(award, b("Win double rewards", 300, 1000, 920, 1060))))
        // Not inside an Awarded dialog: the +60 sheet's own button is not an extra.
        assertNull(CashzineRules.extraAdOffer(snap(b("Watch videos + 60 coins", 300, 1000, 920, 1060))))
    }

    @Test
    fun extraCardsFromTheOfferPage() {
        // 2026-11-24 16:14, as the guard read it.
        val s = snap(
            b("+600", 227, 450, 1108, 457),
            b("Complete reading tasks to earn extra rewards, redeem them once collected.", 157, 477, 1108, 584),
            b("0/600", 234, 610, 356, 677),
            b("Read now", 852, 604, 1108, 684)
        )
        val cards = CashzineRules.extraCards(s)
        assertEquals(1, cards.size)
        assertEquals("reading", cards[0].kind)
        assertEquals(0, cards[0].saved)
        assertNull(cards[0].button)
        val saved = snap(
            b("Get extra rewards by watching shorts, save up to claim all at once", 157, 900, 1108, 1000),
            b("120/600", 234, 1040, 400, 1100),
            b("Claim", 900, 1030, 1100, 1110)
        )
        val c = CashzineRules.extraCards(saved).single()
        assertEquals("shorts", c.kind)
        assertEquals(120, c.saved)
        assertEquals("Claim", c.button!!.text)
    }

    @Test
    fun ownPagesAreNotAds() {
        assertTrue(CashzineRules.isOwnPage(CashzineRules.PACKAGE, "com.novelah.page.h5.H5Activity"))
        assertTrue(CashzineRules.isOwnPage(CashzineRules.PACKAGE, "com.novelah.page.home.HomeActivity"))
        assertFalse(CashzineRules.isOwnPage(CashzineRules.PACKAGE, "com.google.android.gms.ads.AdActivity"))
        assertFalse(
            CashzineRules.isOwnPage(CashzineRules.PACKAGE, "com.bytedance.sdk.openadsdk.activity.single.TTAppOpenAdActivity")
        )
        assertFalse(CashzineRules.isOwnPage(CashzineRules.PACKAGE, null))
        assertFalse(CashzineRules.isOwnPage("com.android.vending", "com.novelah.page.h5.H5Activity"))
    }

    @Test
    fun videoNotAvailableDialog() {
        // 2026-11-24 16:29, after tapping +60.
        val s = snap(
            b("Video is not available currently", 200, 980, 900, 1100),
            b("The number of videos is limited, please make sure your internet connection is good, and try for 2 more times.", 200, 1180, 1000, 1500),
            b("Confirm", 400, 1660, 820, 1760)
        )
        assertEquals("Confirm", CashzineRules.videoUnavailable(s)!!.text)
        assertNull(CashzineRules.videoUnavailable(snap(*bottomBar)))
    }

    @Test
    fun firstBookUnderEditorsChoice() {
        // 2026-11-24 16:33 home page (1220 wide).
        val s = snap(
            b("Editor's Choice", 40, 1010, 400, 1080),
            b("Mo Family's Daughter-i...", 40, 1560, 330, 1650),
            b("fangfei", 70, 1700, 200, 1740),
            b("THE RICH SON-IN-LA...", 390, 1560, 690, 1650)
        )
        val t = CashzineRules.firstBookCover(s)!!
        assertTrue(t.centerY in 1400..1500)
        assertTrue(t.centerX < 300)
    }

    @Test
    fun shortsListIsPlayedWithContinue() {
        // 2026-11-25 13:46: the Shorts tab after "Watch Now".
        val list = snap(
            *bottomBar,
            b("For You", 100, 320, 200, 370),
            b("Hot drama recommendations", 100, 1270, 750, 1330),
            b("After My Fiancée Dumped Me, I Proposed to the CEO", 300, 2000, 800, 2100),
            b("You watched up to Episode 93 last time.", 300, 2210, 800, 2260),
            b("Continue", 870, 2090, 1050, 2170)
        )
        assertTrue(CashzineRules.onShortsList(list))
        assertEquals("Continue", CashzineRules.shortsPlay(list)!!.text)
        val noResume = snap(*bottomBar, b("Hot drama recommendations", 100, 1270, 750, 1330), b("I Became CEO's Wife", 40, 1700, 330, 1760))
        assertEquals("shorts_poster", CashzineRules.shortsPlay(noResume)!!.source)
        // The player has no bottom bar.
        assertFalse(CashzineRules.onShortsList(snap(b("Episode 94", 100, 200, 400, 260))))
    }

    @Test
    fun shortsEntryAndClaim() {
        val offer = snap(
            b("Earned by Watching Shorts", 100, 1800, 800, 1870),
            b("Watch Now", 820, 1900, 1120, 1960)
        )
        assertEquals("Watch Now", CashzineRules.shortsEntry(offer)!!.text)
        assertNotNull(CashzineRules.shortsClaim(snap(b("Claim", 900, 300, 1100, 360))))
        assertNull(CashzineRules.shortsClaim(snap(b("Claimed", 900, 300, 1100, 360))))
    }

    @Test
    fun readingStopsAtTheDailyCap() {
        assertFalse(CashzineRules.readingCapReached(0))
        // 2026-11-25 13:29-13:39: 30 + 60 + 120.
        assertFalse(CashzineRules.readingCapReached(210))
        assertFalse(CashzineRules.readingCapReached(999))
        assertTrue(CashzineRules.readingCapReached(1000))
        assertTrue(CashzineRules.readingCapReached(1030))
    }

    @Test
    fun luckySessions() {
        assertTrue(CashzineRules.luckySessionOpen(5))
        assertTrue(CashzineRules.luckySessionOpen(9))
        assertFalse(CashzineRules.luckySessionOpen(10))
        assertTrue(CashzineRules.luckySessionOpen(14))
        assertFalse(CashzineRules.luckySessionOpen(15))
        assertTrue(CashzineRules.luckySessionOpen(18))
        assertFalse(CashzineRules.luckySessionOpen(19))
        assertTrue(CashzineRules.luckySessionOpen(23))
        assertFalse(CashzineRules.luckySessionOpen(0))
        assertFalse(CashzineRules.luckySessionOpen(4))
    }

    @Test
    fun wheelChancesAndGo() {
        val s = snap(
            b("Spinning Wheel", 450, 130, 800, 200),
            b("Remaining Chances for Doubling: 0", 200, 700, 900, 740),
            b("Remaining Chances: 10", 200, 650, 700, 690),
            b("GO", 560, 1590, 660, 1680)
        )
        assertTrue(CashzineRules.onWheel(s))
        assertEquals(10, CashzineRules.remainingChances(s))
        assertNotNull(CashzineRules.goButton(s))
        val split = snap(b("Remaining Chances:", 200, 650, 600, 690), b("0", 620, 650, 660, 690))
        assertEquals(0, CashzineRules.remainingChances(split))
    }

    @Test
    fun drawResultEmptyAndUnknown() {
        val empty = snap(
            b("Unfortunately, the gift package is empty", 200, 1000, 1000, 1060),
            b("Try Again", 400, 1500, 820, 1580)
        )
        val r = CashzineRules.drawResult(empty)
        assertTrue(r is CashzineRules.DrawResult.Shown)
        r as CashzineRules.DrawResult.Shown
        assertFalse(r.won)
        assertEquals("Try Again", r.close!!.text)
        assertEquals(CashzineRules.DrawResult.None, CashzineRules.drawResult(snap(b("GO", 560, 1590, 660, 1680))))
    }

    @Test
    fun aWheelPrizeIsTheAwardedDialog() {
        val s = snap(
            b("Spinning Wheel", 450, 130, 800, 200),
            b("Awarded", 520, 720, 700, 770),
            b("+25 coins", 480, 860, 740, 940),
            b("Win double rewards", 330, 1050, 900, 1120)
        )
        val r = CashzineRules.drawResult(s) as CashzineRules.DrawResult.Shown
        assertTrue(r.won)
        assertEquals("+25 coins", r.text)
        assertNull(r.close)
    }

    @Test
    fun readingCornerAndPanel() {
        val counting = snap(b("30", 955, 63, 1130, 130), activity = "com.novelah.page.read.ReadActivity")
        assertEquals("30", CashzineRules.readerCounter(counting))
        assertNull(CashzineRules.readerClaim(counting))
        assertTrue(CashzineRules.isReadingActivity(counting.foregroundActivity))
        val claim = snap(b("Claim", 890, 63, 1133, 130))
        assertNotNull(CashzineRules.readerClaim(claim))
        val panel = snap(
            b("Earn coins daily", 400, 1000, 820, 1060),
            b("Earned by Reading", 60, 1180, 500, 1230),
            b("Claim now", 1000, 1190, 1180, 1240),
            b("Claim", 130, 1860, 230, 1890)
        )
        assertTrue(CashzineRules.earnPanel(panel))
        assertEquals("tier_claim", CashzineRules.tierClaim(panel)!!.source)
        val onlyNow = snap(b("Earn coins daily", 400, 1000, 820, 1060), b("Claim now", 1000, 1190, 1180, 1240))
        assertEquals("claim_now", CashzineRules.tierClaim(onlyNow)!!.source)
    }

    @Test
    fun readNowIsTheHighestVisibleButton() {
        // 2026-11-24 13:26: the "Earned by Reading" title was under the banner.
        val s = snap(
            b("+1800", 100, 480, 300, 540),
            b("Read now", 860, 470, 1140, 545),
            b("Earn extra rewards by reading", 150, 1080, 850, 1140),
            b("Read now", 860, 1380, 1100, 1450)
        )
        assertEquals(507, CashzineRules.readNowButton(s)!!.centerY)
        // Under the top banner or behind the bottom bar it is not tappable.
        assertNull(CashzineRules.readNowButton(snap(b("Read now", 860, 250, 1140, 300))))
    }

    @Test
    fun luckyEntryIsTheRowsButtonNotItsTitle() {
        val row = snap(
            b("Spin to win lucky draw", 80, 1210, 700, 1270),
            b("Go get it now", 820, 1310, 1180, 1390),
            b("Invite friends", 80, 1560, 500, 1620),
            b("Go get it now", 820, 1650, 1180, 1730)
        )
        assertEquals(1350, CashzineRules.luckyEntry(row)!!.centerY)
        // The 13:26 screen: only the title, at the bottom edge, no button yet.
        assertNull(CashzineRules.luckyEntry(snap(b("Spin to win lucky draw", 80, 2320, 700, 2376))))
        assertNull(CashzineRules.luckyEntry(snap(b("Spin to win lucky draw", 80, 1210, 700, 1270))))
    }

    @Test
    fun goldPixelThresholds() {
        assertTrue(CashzineRules.isGold(0xFFFFC21E.toInt())) // coin yellow
        assertFalse(CashzineRules.isGold(0xFFFFFFFF.toInt()))
        assertFalse(CashzineRules.isGold(0xFFE53935.toInt())) // red download button
        assertFalse(CashzineRules.isGold(0xFF806010.toInt())) // too dark (dimmed by a dialog)
    }

    @Test
    fun syntheticCoinIsFoundAndBarIsNot() {
        val w = 1220
        val h = 2712
        val img = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        fun disc(cx: Int, cy: Int, r: Int) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) img[y * w + x] = 0xFFFFC21E.toInt()
            }
        }
        disc(840, 1738, 58) // a floating coin
        disc(610, 2470, 58) // the bottom bar's Invite coin
        disc(60, 90, 58) // the header's money bag
        val coins = CashzineRules.findCoins(img, w, h)
        assertEquals(1, coins.size)
        assertTrue(kotlin.math.abs(coins[0].centerX - 840) < 12)
        assertTrue(kotlin.math.abs(coins[0].centerY - 1738) < 12)
    }

    /**
     * The real screenshots of 2026-11-18 (not in the repo: they are the
     * user's screen). Runs only on the machine that has them.
     */
    @Test
    fun realScreenshotsWhenPresent() {
        val dir = File("D:/eddie-wu/Downloads/pkl/cashzine")
        assumeTrue(dir.isDirectory)
        // name -> coins seen by eye on 2026-11-18
        // s11 has the Awarded dialog open: the dimmed coins must not count.
        val expected = mapOf(
            "home1" to 3, "home2" to 3, "s0" to 2, "s11" to 0, "s12" to 1, "h3" to 1, "gift0" to 1
        )
        for ((name, count) in expected) {
            val file = File(dir, "$name.png")
            if (!file.isFile) continue
            // javax.imageio is in the JDK but not on the Android compile path.
            val image = Class.forName("javax.imageio.ImageIO")
                .getMethod("read", File::class.java).invoke(null, file)
            val cls = image.javaClass
            val w = cls.getMethod("getWidth").invoke(image) as Int
            val h = cls.getMethod("getHeight").invoke(image) as Int
            val pixels = cls.getMethod(
                "getRGB", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, IntArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            ).invoke(image, 0, 0, w, h, null, 0, w) as IntArray
            val coins = CashzineRules.findCoins(pixels, w, h)
            assertEquals("$name: $coins", count, coins.size)
        }
    }
}
