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
    fun extraAdOffersAreRecognisedSoTheyAreNotTaken() {
        assertNotNull(CashzineRules.extraAdOffer(snap(b("Win 51 coins", 400, 1000, 820, 1060))))
        assertNotNull(CashzineRules.extraAdOffer(snap(b("Watch videos + 26 coins", 300, 1000, 920, 1060))))
        assertNull(CashzineRules.extraAdOffer(snap(b("Win double rewards", 300, 1000, 920, 1060))))
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
