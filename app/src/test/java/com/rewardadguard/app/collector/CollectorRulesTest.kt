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
        // The home page is never scrolled down to the sensor exercises.
        assertTrue(profile.tabScrolls.values.all { it.horizontal })
        assertEquals(listOf("首頁", "步行"), profile.tabPaths["步行"])
    }

    @Test
    fun ahealthSleepPageRealReadingStillOffersTheHourCoins() {
        // The guard's own reading of 首頁 > 睡眠, 2026-11-25 11:15 (1.2.50):
        // the page also carries a hidden MapleStory ad (安裝, store link).
        val profile = CollectorProfiles.AHEALTH
        val sleep = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = listOf(
            TextBox("反映問題", Box(1040, 2390, 1172, 2522), TextSource.NODE),
            TextBox("返回", Box(48, 168, 174, 294), TextSource.NODE),
            TextBox("睡眠", Box(556, 195, 664, 267), TextSource.NODE),
            TextBox("上一個睡眠日", Box(98, 428, 194, 524), TextSource.NODE),
            TextBox("11月24日 週二 – 11月25日 週三", Box(316, 446, 904, 506), TextSource.NODE),
            TextBox("下一個睡眠日", Box(1026, 428, 1122, 524), TextSource.NODE),
            TextBox("睡眠時間", Box(98, 596, 872, 644), TextSource.NODE),
            TextBox("新增睡眠", Box(894, 596, 1122, 644), TextSource.NODE),
            TextBox("1:30 AM – 9:30 AM", Box(98, 782, 1122, 842), TextSource.NODE),
            TextBox("下午 6 點", Box(98, 1276, 355, 1318), TextSource.NODE),
            TextBox("凌晨 12 點", Box(354, 1276, 611, 1318), TextSource.NODE),
            TextBox("上午6點", Box(610, 1276, 867, 1318), TextSource.NODE),
            TextBox("中午12點", Box(866, 1276, 1122, 1318), TextSource.NODE),
            TextBox("觀看廣告後即可獲得點數", Box(412, 1590, 808, 1643), TextSource.NODE),
            TextBox("每睡 12 分鐘得 1 點 · 每天最多 40 點", Box(310, 1652, 910, 1705), TextSource.NODE),
            TextBox("獎勵點數", Box(98, 1868, 1124, 1938), TextSource.NODE),
            TextBox("每睡滿 2 小時可獲得隨機點數", Box(98, 1935, 1124, 1995), TextSource.NODE),
            TextBox("每週睡眠報告", Box(48, 2536, 1172, 2570), TextSource.NODE),
            TextBox("新增睡眠", Box(954, 596, 1122, 644), TextSource.NODE),
            TextBox("8 小時 00 分鐘", Box(98, 644, 733, 764), TextSource.NODE),
            TextBox("獲得更多積分", Box(502, 1459, 790, 1525), TextSource.NODE),
            TextBox("2 小時", Box(197, 2307, 298, 2354), TextSource.NODE),
            TextBox("4 小時", Box(533, 2307, 634, 2354), TextSource.NODE),
            TextBox("6 小時", Box(869, 2307, 970, 2354), TextSource.NODE),
            TextBox("領取", Box(236, 2064, 320, 2124), TextSource.NODE),
            TextBox("領取", Box(572, 2064, 656, 2124), TextSource.NODE),
            TextBox("領取", Box(908, 2064, 992, 2124), TextSource.NODE),
            TextBox("安裝", Box(250, 1683, 970, 1791), TextSource.NODE),
            TextBox("d%3Fid%3Dcom.nexon.ma%26inline%3Dtrue%26enifd%3DABT2UUhthxNfp-npa4ibvSHmydlrJzTv0VKtzxkLN6MLyxy8e3j3G-nsPhkhIoJ8DDMrz55FiH7Du58Ty67hJvPF8fRHN2BirjSlI8M", Box(712, 1143, 862, 1296), TextSource.NODE),
            TextBox("MapleStory", Box(712, 1326, 1033, 1404), TextSource.NODE),
            TextBox("4.5", Box(712, 1437, 763, 1476), TextSource.NODE),
            TextBox("d%3Fid%3Dcom.nexon.ma%26inline%3Dtrue%26enifd%3DABT2UUhthxNfp-npa4ibvSHmydlrJzTv0VKtzxkLN6MLyxy8e3j3G-nsPhkhIoJ8DDMrz55FiH7Du58Ty67hJvPF8fRHN2BirjSlI8", Box(805, 1431, 1027, 1479), TextSource.NODE),
            TextBox("安裝", Box(559, 1713, 658, 1761), TextSource.NODE),
            TextBox("d%3Fid%3Dcom.nexon.ma%26inline%3Dtrue%26enifd%3DABT2UUhthxNfp-npa4ibvSHmydlrJzTv0VKtzxkLN6MLyxy8e3j3G-nsPhkhIoJ8DDMrz55FiH7Du58Ty67hJvPF8fRHN2BirjSlI8M", Box(259, 1275, 961, 1428), TextSource.NODE),
            TextBox("安裝", Box(259, 1560, 961, 1671), TextSource.NODE),
            TextBox("MapleStory", Box(487, 1449, 733, 1512), TextSource.NODE),
            TextBox("安裝", Box(559, 1590, 658, 1641), TextSource.NODE),
            TextBox("个", Box(83, 206, 139, 250), TextSource.OCR),
            TextBox("人", Box(128, 452, 163, 498), TextSource.OCR),
            TextBox("睡眠時間", Box(101, 606, 239, 639), TextSource.OCR),
            TextBox("1:30 AM -9:30 AM", Box(102, 795, 452, 835), TextSource.OCR),
            TextBox("下午6點", Box(100, 1286, 239, 1324), TextSource.OCR),
            TextBox("8小時 00分鐘", Box(102, 658, 727, 745), TextSource.OCR),
            TextBox("獎勵點数", Box(100, 1883, 289, 1928), TextSource.OCR),
            TextBox("11月24日週二- 11月25日 週三", Box(303, 457, 901, 501), TextSource.OCR),
            TextBox("睡眠", Box(560, 210, 664, 260), TextSource.OCR),
            TextBox("2小時", Box(198, 2314, 294, 2351), TextSource.OCR),
            TextBox("a包*以,l,l令86。", Box(649, 30, 1191, 76), TextSource.OCR),
            TextBox("領取", Box(236, 2076, 318, 2116), TextSource.OCR),
            TextBox("|凌晨12點 上午6點", Box(334, 1282, 736, 1327), TextSource.OCR),
            TextBox("B獲得更多積分", Box(434, 1468, 788, 1523), TextSource.OCR),
            TextBox("每睡滿2小時可獲得随機點數", Box(100, 1948, 643, 1988), TextSource.OCR),
            TextBox("觀看廣告後即可獲得點數", Box(413, 1603, 807, 1636), TextSource.OCR),
            TextBox("每睡 12分鐘得1點·每天最多 40點", Box(299, 1662, 922, 1701), TextSource.OCR),
            TextBox("領取", Box(572, 2076, 654, 2116), TextSource.OCR),
            TextBox("4小時", Box(534, 2316, 632, 2350), TextSource.OCR),
            TextBox("+新增睡眠", Box(899, 599, 1121, 639), TextSource.OCR),
            TextBox("中午12點", Box(869, 1289, 1013, 1322), TextSource.OCR),
            TextBox("(P領取", Box(830, 2071, 1001, 2119), TextSource.OCR),
            TextBox("6小時", Box(870, 2315, 967, 2351), TextSource.OCR),
        ))
        assertTrue(CollectorRules.onSubPage(sleep, profile))
        // The page's scroll container is "not enabled" (seen 11:20); it must
        // not hide the buttons. A greyed-out button still does.
        val container = sleep.copy(disabled = listOf(Box(0, 318, 1220, 2570)))
        assertEquals(
            listOf("領取", "領取", "領取", "獲得更多積分"),
            CollectorRules.eligibleRewards(container, profile).map { it.text }.toList()
        )
        val greyed = container.copy(disabled = container.disabled + Box(200, 2050, 360, 2140))
        assertEquals(3, CollectorRules.eligibleRewards(greyed, profile).count())
    }

    @Test
    fun ahealthSleepPageIsTheAppAndOnlyTheHourCoinsAreRewards() {
        // 首頁 > 睡眠 from the user's phone, 2026-11-25 10:58: no tab bar.
        val profile = CollectorProfiles.AHEALTH
        val sleep = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = listOf(
            box("睡眠", 588, 231), box("11月24日 週二 – 11月25日 週三", 610, 475),
            box("睡眠時間", 135, 625), box("新增睡眠", 1010, 620), box("8 小時 00 分鐘", 410, 705),
            box("獲得更多積分", 610, 1490), box("觀看廣告後即可獲得點數", 610, 1617),
            box("每睡 12 分鐘得 1 點・每天最多 40 點", 610, 1680),
            box("獎勵點數", 195, 1905), box("每睡滿 2 小時可獲得隨機點數", 370, 1965),
            box("領取", 275, 2095), box("領取", 610, 2095), box("領取", 945, 2095),
            box("2 小時", 245, 2325), box("4 小時", 580, 2325), box("6 小時", 915, 2325)
        ))
        assertTrue(CollectorRules.hasAppChrome(sleep, profile))
        assertTrue(CollectorRules.onSubPage(sleep, profile))
        // 獲得更多積分 is an ad for sleep points; the user counts it a reward.
        assertEquals(
            listOf("領取", "領取", "領取", "獲得更多積分"),
            CollectorRules.rewardCandidates(sleep, profile).map { it.text }
        )
        assertEquals(listOf("首頁", "步行", "睡眠"), profile.collectionTabs)
        assertEquals(listOf("首頁", "睡眠"), profile.tabPaths["睡眠"])
        assertEquals(TabScroll(pages = 3, horizontal = true, atY = 0.77), profile.tabScrolls["睡眠"])
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
    fun ahealthHealthShortsAreNeverAReward() {
        val profile = CollectorProfiles.AHEALTH
        val page = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = listOf(
            box("健康短片, 觀看 1 部健康短影音並領取點數", 610, 900, TextSource.NODE),
            box("看影片", 230, 1320), box("領取", 1000, 1830)
        ))
        assertEquals(listOf("領取"), CollectorRules.rewardCandidates(page, profile).map { it.text })
    }

    @Test
    fun receivedSheetAfterAnUnconfirmedAdCountsIt() {
        // 獲得更多積分 2026-11-25 11:32: the sheet came after the checks.
        assertEquals("ad_confirmed_late", CollectorRules.receivedSheetRecount("ad_unconfirmed"))
        assertEquals("claimed", CollectorRules.receivedSheetRecount("changed"))
        // Already counted: never twice.
        assertNull(CollectorRules.receivedSheetRecount("ad_closed"))
        assertNull(CollectorRules.receivedSheetRecount("claimed"))
        assertNull(CollectorRules.receivedSheetRecount("confirmed"))
    }

    @Test
    fun receivedSheetAndLeaveConfirm() {
        val sheet = Snapshot(1220, 2712, 0L, "com.tracerofficial", null, boxes = listOf(
            box("2p 已領取!", 610, 2000), box("完成", 610, 2450)
        ))
        assertTrue(CollectorRules.isRewardReceivedSheet(sheet))
        assertFalse(CollectorRules.isRewardReceivedSheet(sheet.copy(boxes = listOf(box("已領取", 610, 2000)))))

        val leave = Snapshot(1220, 2712, 0L, "com.tracerofficial", null, boxes = listOf(
            box("再看一下就能拿到點數。確定要停止觀看健康短影音嗎？", 610, 1280),
            box("否", 360, 1496), box("是", 864, 1496)
        ))
        assertEquals("是", CollectorRules.findLeaveConfirm(leave)?.text)
        assertNull(CollectorRules.findLeaveConfirm(sheet))
    }

    @Test
    fun unityOpenStoreAtTheTopIsTheWayOn() {
        // BITWALK 2026-11-14 22:38: "▶| 開啟商店" top-left, no X.
        val ad = Snapshot(1220, 2712, 0L, "jp.paddleinc.walk", null, boxes = listOf(
            box("▶| 開啟商店", 200, 160), box("玩遊戲", 610, 1390), box("馬上體驗", 610, 1990)
        ))
        assertEquals("▶| 開啟商店", CollectorRules.findOpenStoreSkip(ad)?.text)
        // Lower on the screen, or other store words, never count.
        val low = ad.copy(boxes = listOf(box("開啟商店", 610, 1990)))
        assertNull(CollectorRules.findOpenStoreSkip(low))
        val play = ad.copy(boxes = listOf(box("» Skip to Google Play", 1000, 120), box("Install", 1000, 200)))
        assertNull(CollectorRules.findOpenStoreSkip(play))
    }

    @Test
    fun bitwalkGiftCardBannerIsNeverAReward() {
        // 2026-11-15 12:12: tapped 100 times, each opened Chrome.
        val profile = CollectorProfiles.BITWALK
        val bar = listOf("步行", "賺取", "內容").mapIndexed { i, t -> box(t, 250 + i * 350, 2570, TextSource.NODE) }
        val page = Snapshot(1220, 2712, 0L, profile.packageName, null, boxes = bar + listOf(
            box("立即領取禮物卡", 610, 2247, TextSource.NODE),
            box("收看廣告影片，獲得5倍的BTC", 610, 1680, TextSource.NODE)
        ))
        assertEquals(listOf("收看廣告影片，獲得5倍的BTC"), CollectorRules.rewardCandidates(page, profile).map { it.text })
        // The same words read one pixel apart are one button.
        assertEquals(CollectorRules.dropKey("立即領取禮物卡"), CollectorRules.dropKey("立即領取 禮物卡"))
        assertEquals(2, CollectorRules.LEAD_OUTS_TO_DROP)
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

