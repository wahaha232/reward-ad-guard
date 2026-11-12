package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 2026-11-12 20:12, LINE 散步趣: "獲得60枚金幣！" with an ad (AD label,
 * StoneAge, 安裝) above the dialog's 關閉. The collector tapped an invisible
 * "確認" node inside the ad at (997,1368) and Google Play opened.
 */
class DialogAdBlockTest {

    private fun box(text: String, l: Int, t: Int, r: Int, b: Int, source: TextSource) =
        TextBox(text, Box(l, t, r, b), source)

    private fun rewardDialogWithAd(includeOcr: Boolean = true): Snapshot {
        val boxes = mutableListOf(
            box("集點", 60, 2480, 240, 2560, TextSource.OCR),
            box("好友", 360, 2480, 560, 2560, TextSource.OCR),
            box("使用", 660, 2480, 860, 2560, TextSource.OCR),
            box("精選", 960, 2480, 1160, 2560, TextSource.OCR),
            box("獲得60枚金幣！", 420, 860, 800, 920, TextSource.OCR),
            box("AD", 160, 950, 320, 1000, TextSource.OCR),
            box("StoneAge", 480, 1330, 760, 1400, TextSource.OCR),
            box("確認", 940, 1340, 1054, 1396, TextSource.NODE), // inside the ad, not on screen
            box("關閉", 540, 1850, 690, 1910, TextSource.OCR),
            box("關閉", 240, 1820, 980, 1940, TextSource.NODE)
        )
        if (!includeOcr) boxes.removeAll { it.source == TextSource.OCR && it.text != "AD" }
        return Snapshot(1220, 2712, 0L, "com.linecorp.LGWKTW", null, boxes = boxes)
    }

    @Test
    fun theDialogsOwnCloseIsChosenNotTheAdsHiddenConfirm() {
        val target = CollectorRules.findDialogConfirm(rewardDialogWithAd(), CollectorProfiles.LINE_WALK)
        assertNotNull(target)
        assertEquals("關閉", TextMatch.normalizeLoose(target!!.text))
        assertEquals(true, target.centerY > 1800)
    }

    @Test
    fun theAdBlockRunsFromItsLabelToTheClose() {
        val snapshot = rewardDialogWithAd()
        val anchor = CollectorRules.dialogAnchor(snapshot, CollectorProfiles.LINE_WALK)!!
        val block = CollectorRules.dialogAdBlock(snapshot, anchor)!!
        assertEquals(950, block.top)
        assertEquals(1820, block.bottom)
    }
}
