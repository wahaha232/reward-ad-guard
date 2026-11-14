package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays pc-collector's recorded screens through the ported rules.
 *
 * These are the decisions that cost a real rewarded ad to discover on the
 * phone, so they are pinned here first: which button is a reward, when a screen
 * is an ad, and which close/confirm target belongs to a dialog.
 */
class CollectorRulesTest {

    private fun fixture(name: String): Snapshot {
        val url = requireNotNull(javaClass.classLoader?.getResource("collector-fixtures/$name")) {
            "missing fixture $name"
        }
        return OcrJson.parse(File(url.toURI()).readText())
    }

    @Test
    fun bitwalkHomePicksTheWatchAdButtonNotTheStampExchangeText() {
        val snapshot = fixture("app_bitwalk_get_stamp.ocr.json")
        val profile = CollectorProfiles.BITWALK
        assertTrue(CollectorRules.hasAppChrome(snapshot, profile))

        val candidate = CollectorRules.pickReward(snapshot, profile, emptyMap())
        assertNotNull(candidate)
        assertTrue(candidate!!.text.contains("廣 告 影 片"))
        // "可兌換 49 個印章" must never be a reward target.
        val labels = CollectorRules.rewardCandidates(snapshot, profile).map { it.text }
        assertFalse(labels.any { it.contains("可 兌 換") })
    }

    @Test
    fun inAppAdIsRecognisedByItsRewardCountdown() {
        val snapshot = fixture("ad_inapp_rewarded.ocr.json")
        val (isAd, reason) = CollectorRules.adEvidence(snapshot, CollectorProfiles.BITWALK)
        assertTrue("expected an ad, got $reason", isAd)
        assertEquals("hard_ad_marker", reason)
    }

    @Test
    fun walkEarnStoreCardInterstitialIsAnAd() {
        // 2026-11-13 19:58: what Lucky box opened - a store card over a video,
        // no countdown or X yet, the app's tab bar gone.
        val boxes = listOf(
            TextBox("瑪奇 Mobile", Box(360, 350, 710, 420), TextSource.OCR),
            TextBox("4.0", Box(360, 480, 420, 530), TextSource.OCR),
            TextBox("Google Play", Box(540, 480, 700, 530), TextSource.OCR),
            TextBox("安裝", Box(1020, 400, 1110, 470), TextSource.OCR)
        )
        val profile = CollectorProfiles.WALK_EARN
        val ad = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = boxes)
        assertEquals("store_card_ad", CollectorRules.adEvidence(ad, profile).second)

        // The same card with the app's tab bar showing is the app, not an ad.
        val bar = listOf("Home", "Report", "Cash out", "Mine").mapIndexed { i, t ->
            TextBox(t, Box(60 + i * 300, 2540, 200 + i * 300, 2600), TextSource.NODE)
        }
        val app = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = boxes + bar)
        assertFalse(CollectorRules.adEvidence(app, profile).first)

        // LINE keeps its own store-page handling (Back).
        val line = Snapshot(1220, 2712, 0L, CollectorProfiles.LINE_WALK.packageName, null, boxes = boxes)
        assertFalse(CollectorRules.adEvidence(line, CollectorProfiles.LINE_WALK).second == "store_card_ad")
    }

    private fun box(text: String, x: Int, y: Int, source: TextSource = TextSource.OCR) =
        TextBox(text, Box(x - 60, y - 30, x + 60, y + 30), source)

    private val hahagoBar = listOf("首頁", "365", "兌換", "訓練", "揪團", "個人").mapIndexed { i, t ->
        box(t, 140 + i * 188, 2506, TextSource.NODE)
    }

    @Test
    fun hahagoTaskPageTapsTheSignInButtonsNotTheTitles() {
        // 個人 > 任務 (每月任務), from the user's screenshot of 2026-11-14.
        val profile = CollectorProfiles.HAHAGO
        val page = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = listOf(
            box("每月任務", 250, 300), box("每日簽到", 430, 1105), box("每日簽到", 430, 760),
            box("簽到", 960, 1340), box("每日7500步簽到", 550, 1630), box("簽到", 960, 1850),
            box("每日上傳步數到【365】", 650, 2140), box("前往", 960, 2360), box("1/7", 160, 1380)
        ))
        val picks = CollectorRules.rewardCandidates(page, profile).map { it.text }
        assertEquals(listOf("簽到", "簽到"), picks)
    }

    @Test
    fun hahagoHomePicksTheStepMilestonesAnd365PicksUpload() {
        val profile = CollectorProfiles.HAHAGO
        val home = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = hahagoBar + listOf(
            box("58086", 560, 700), box("2k", 165, 1295), box("4k", 310, 1295), box("10k", 745, 1295),
            box("20", 935, 1295), box("2026.11.13", 150, 325), box("12 / 7分", 450, 1470)
        ))
        val homePicks = CollectorRules.rewardCandidates(home, profile).map { it.text }.toSet()
        assertEquals(setOf("2k", "4k", "10k", "20"), homePicks)

        val tab365 = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = hahagoBar + listOf(
            box("上傳步數", 960, 1470), box("上傳紀錄", 1030, 1865), box("今天還沒上傳步數喔！", 780, 2010),
            box("運動會說明", 990, 520)
        ))
        assertEquals("上傳步數", CollectorRules.pickReward(tab365, profile, emptyMap())?.text)
        assertEquals(listOf("個人", "任務"), profile.tabPaths["任務"])
    }

    @Test
    fun hahagoStatisticsLinesAreNotUploadButtons() {
        // 2026-11-14 11:46: these lines were tapped as if they were 上傳步數.
        val profile = CollectorProfiles.HAHAGO
        val page = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = hahagoBar + listOf(
            box("今日上傳步數： 9,977 步\n今日獲得積分 3 分\n總步數\n289,014\n總積分\n18", 610, 2282),
            box("今日上傳步數： 9,977\n更新時間： 2026/11/14 11:46", 610, 1322),
            box("今日上傳步數: 9,977步", 610, 1700)
        ))
        assertTrue(CollectorRules.rewardCandidates(page, profile).isEmpty())
        val withButton = page.copy(boxes = page.boxes + box("上傳步數", 971, 1636))
        assertEquals(listOf("上傳步數"), CollectorRules.rewardCandidates(withButton, profile).map { it.text })
    }

    @Test
    fun alreadyUploadedNoticeIsARefusalNotAClaim() {
        val profile = CollectorProfiles.HAHAGO
        val after = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = hahagoBar + listOf(
            box("提示", 610, 1170), box("今日已上傳過相同的步數！", 610, 1305),
            box("快起身走一走，增加步數後再上傳喔！", 610, 1372), box("確認", 610, 1535)
        ))
        assertEquals("已上傳過", CollectorRules.refusalText(after))
        val tapped = Target("上傳步數", Box(911, 1606, 1031, 1666), 100, "node")
        assertFalse(CollectorRules.isDirectClaim(tapped, after, profile, expectAd = false))
        // An ordinary screen is not a refusal.
        val plain = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = hahagoBar)
        assertNull(CollectorRules.refusalText(plain))
    }

    @Test
    fun skipToGooglePlayIsThePlayStoreEndCard() {
        val card = Snapshot(2712, 1220, 0L, CollectorProfiles.BITWALK.packageName, null, boxes = listOf(
            box("» Skip to Google Play", 2340, 95), box("立即購買", 1680, 1130)
        ))
        assertTrue(CollectorRules.isPlayStoreEndCard(card))
        val video = Snapshot(1220, 2712, 0L, CollectorProfiles.BITWALK.packageName, null, boxes = listOf(
            box("Install", 980, 2490), box("iPhone 18 Pro Max", 380, 500)
        ))
        assertFalse(CollectorRules.isPlayStoreEndCard(video))
    }

    @Test
    fun aPlayingVideoMovesButAStillScreenDoesNot() {
        val still = IntArray(144) { 100 }
        val oneBlink = still.copyOf().also { it[5] = 200 }
        val video = IntArray(144) { if (it % 3 == 0) 30 else 100 }
        assertFalse(AdStuck.imageMoved(still, oneBlink))
        assertTrue(AdStuck.imageMoved(still, video))
        assertFalse(AdStuck.imageMoved(null, video))
    }

    @Test
    fun ahealthWalkPageIsTheAppAndOnlyTheCoinsAreRewards() {
        // 步行 page from the user's phone, 2026-11-14 20:38: no tab bar.
        val profile = CollectorProfiles.AHEALTH
        val walk = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = listOf(
            box("步行", 610, 235), box("健康年齡", 230, 470), box("領取步數點數", 315, 580),
            box("每 500 步數獲得隨機點數", 365, 660), box("領取", 395, 970), box("領取", 920, 970),
            box("0步數", 362, 1243), box("500 步", 890, 1243), box("18,803", 330, 1475)
        ))
        assertTrue(CollectorRules.hasAppChrome(walk, profile))
        assertTrue(CollectorRules.onSubPage(walk, profile))
        assertEquals(listOf("領取", "領取"), CollectorRules.rewardCandidates(walk, profile).map { it.text })

        val bar = listOf("首頁", "健康年齡", "福利", "禮物商店").mapIndexed { i, t ->
            box(t, 230 + i * 250, 2510, TextSource.NODE)
        }
        val home = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = bar + box("領取", 830, 2028))
        assertFalse(CollectorRules.onSubPage(home, profile))
        assertEquals(TabScroll(pages = 6, horizontal = true, atY = 0.36), profile.tabScrolls["步行"])
        assertEquals(listOf("首頁", "步行"), profile.tabPaths["步行"])
    }

    @Test
    fun ahealthRewardSheetClosesWithDoneNeverTheDoubleOffer() {
        val profile = CollectorProfiles.AHEALTH
        val sheet = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = listOf(
            box("2p 已領取!", 610, 2000), box("觀看廣告後即可獲得點數", 640, 2130),
            box("2倍點數", 640, 2275), box("完成", 610, 2450), box("已完成", 300, 1200)
        ))
        assertEquals("完成", CollectorRules.findDone(sheet, profile)?.text)
        assertTrue(CollectorRules.rewardCandidates(sheet, profile).none { "倍" in it.text })
        assertNull(CollectorRules.findDone(sheet, CollectorProfiles.BITWALK))
    }

    @Test
    fun anAdLabelAloneIsAnAdBadge() {
        val ad = Snapshot(1220, 2712, 0L, CollectorProfiles.WALK_EARN.packageName, null, boxes = listOf(
            box("Ad", 75, 392), box("蝦皮購物丨花得更少買得更好", 610, 1400), box("获取", 610, 1730)
        ))
        assertTrue(CollectorRules.hasAdBadge(ad))
        val app = Snapshot(1220, 2712, 0L, CollectorProfiles.WALK_EARN.packageName, null, boxes = listOf(
            box("This may contain ads", 610, 900), box("Lucky box", 610, 210)
        ))
        assertFalse(CollectorRules.hasAdBadge(app))
    }

    @Test
    fun appHomeIsNotAnAd() {
        val snapshot = fixture("app_bitwalk_get_stamp.ocr.json")
        val (isAd, reason) = CollectorRules.adEvidence(snapshot, CollectorProfiles.BITWALK)
        assertFalse(isAd)
        assertEquals("app_chrome", reason)
    }

    @Test
    fun rewardDialogConfirmIsTheCloseButtonBelowTheText() {
        val snapshot = fixture("app_bitwalk_reward_dialog.ocr.json")
        val profile = CollectorProfiles.BITWALK
        assertTrue(CollectorRules.isDialog(snapshot, profile))
        val confirm = CollectorRules.findDialogConfirm(snapshot, profile)
        assertNotNull(confirm)
        assertEquals("dialog_confirm", confirm!!.source)
        assertTrue(TextMatch.normalizeLoose(confirm.text) in setOf("關閉", "確定", "知道了"))
        // The tap must stay inside the dialog area, not wander onto ad content.
        assertTrue(confirm.centerY > snapshot.height * 0.5)
    }

    @Test
    fun infoDialogCloseIsFoundForBitwalkHelpDialog() {
        val snapshot = fixture("app_bitwalk_help_dialog.ocr.json")
        val close = CollectorRules.findInfoDialogClose(snapshot, CollectorProfiles.BITWALK)
        assertNotNull(close)
        assertEquals("info_dialog_close", close!!.source)
        assertTrue(close.centerY > 750)
    }

    @Test
    fun installCardScreenIsNotTreatedAsAReward() {
        val snapshot = fixture("ad_install_card.ocr.json")
        val profile = CollectorProfiles.BITWALK
        // "Skip to Goog Play" must never be a reward or a next-ad label.
        assertTrue(CollectorRules.isUnsafeRewardText("Skip to Goog Play"))
        assertFalse(CollectorRules.isNextAdLabel("Skip to Goog Play"))
        assertNull(CollectorRules.pickReward(snapshot, profile, emptyMap()))
    }

    @Test
    fun nodeLabelsWinOverDuplicateOcrText() {
        val base = Snapshot(
            width = 1220,
            height = 2712,
            capturedAt = 0L,
            foregroundPackage = "jp.paddleinc.walk",
            foregroundActivity = null,
            boxes = listOf(
                TextBox("收看廣告影片", Box(300, 1600, 900, 1700), TextSource.OCR),
                TextBox("收看廣告影片", Box(300, 1600, 900, 1700), TextSource.NODE)
            )
        )
        val candidate = CollectorRules.pickReward(base, CollectorProfiles.BITWALK, emptyMap())
        assertNotNull(candidate)
        assertEquals("node", candidate!!.source)
    }

    @Test
    fun repeatLimitIsKeyedByLabelAndCoarseGrid() {
        val snapshot = fixture("app_bitwalk_get_stamp.ocr.json")
        val first = CollectorRules.pickReward(snapshot, CollectorProfiles.BITWALK, emptyMap())
        assertNotNull(first)
        val attempts = mapOf(CollectorRules.attemptKey(first!!) to 50)
        val again = CollectorRules.pickReward(snapshot, CollectorProfiles.BITWALK, attempts)
        assertTrue("capped button must not be picked again", again == null || again.text != first.text)
    }

    @Test
    fun adSequenceIsParsedWithoutSpaces() {
        val snapshot = Snapshot(1220, 2712, 0L, "jp.paddleinc.walk", null, boxes = listOf(TextBox("第 2 則廣告，共 5 則", Box(10, 10, 600, 60), TextSource.OCR))
        )
        assertEquals(2 to 5, CollectorRules.adSequence(snapshot))
    }

    @Test
    fun nextAdLabelsAreShortAndNeverStoreCtas() {
        assertTrue(CollectorRules.isNextAdLabel("NEXT AD"))
        assertTrue(CollectorRules.isNextAdLabel(">>"))
        assertTrue(CollectorRules.isNextAdLabel("下一則"))
        assertFalse(CollectorRules.isNextAdLabel("距離下一個獎勵還有 20 分鐘，請稍後再來"))
        assertFalse(CollectorRules.isNextAdLabel("Skip to Google Play"))
        assertFalse(CollectorRules.isNextAdLabel("立即下載"))
    }

    @Test
    fun countdownMustSitInTheTopRight() {
        val topRight = Snapshot(1220, 2712, 0L, "jp.paddleinc.walk", null, boxes = listOf(TextBox("29s", Box(1000, 100, 1100, 160), TextSource.OCR))
        )
        val middle = Snapshot(1220, 2712, 0L, "jp.paddleinc.walk", null, boxes = listOf(TextBox("29s", Box(500, 1200, 600, 1260), TextSource.OCR))
        )
        assertTrue(CollectorRules.hasAdCountdown(topRight))
        assertFalse(CollectorRules.hasAdCountdown(middle))
    }

    @Test
    fun storeCtaNeverBecomesACloseTarget() {
        val snapshot = Snapshot(1220, 2712, 0L, "jp.paddleinc.walk", null, boxes = listOf(
                TextBox("立即安裝", Box(900, 100, 1100, 180), TextSource.OCR),
                TextBox("關閉", Box(1050, 100, 1150, 180), TextSource.OCR)
            )
        )
        val targets = CollectorRules.findCloseTargets(snapshot, CollectorProfiles.BITWALK, emptyList())
        assertTrue(targets.none { it.text.contains("安 裝") || it.text.contains("安裝") })
    }
}

