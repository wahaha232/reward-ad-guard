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

