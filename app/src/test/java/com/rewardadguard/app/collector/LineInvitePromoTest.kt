package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2026-11-13 15:02: "雙方都可獲得金幣!" on LINE's invite promotion was tapped as a reward. */
class LineInvitePromoTest {

    private fun box(text: String, y: Int) = TextBox(text, Box(250, y, 970, y + 70), TextSource.OCR)

    private val promo = Snapshot(
        1220, 2712, 0L, "com.linecorp.LGWKTW", null,
        boxes = listOf(
            box("最高可獲 10000 金幣", 500),
            box("快來 領取 吧!!", 650),
            box("被邀請的好友", 1730),
            box("完成邀請任務!", 1810),
            box("雙方都", 2010),
            box("|可獲得金幣!", 2090),
            box("邀請好友", 2400)
        )
    )

    @Test
    fun theInvitePromotionIsRecognisedAndLeftWithBack() {
        assertTrue(LineWalkRules.isInvitePromo(promo))
    }

    @Test
    fun nothingOnThePromotionIsAReward() {
        assertNull(CollectorRules.pickReward(promo, CollectorProfiles.LINE_WALK, emptyMap()))
    }

    @Test
    fun theRealRewardButtonStillIs() {
        val home = Snapshot(
            1220, 2712, 0L, "com.linecorp.LGWKTW", null,
            boxes = listOf(TextBox("獲得金幣\n2", Box(400, 1040, 820, 1150), TextSource.NODE))
        )
        assertFalse(LineWalkRules.isInvitePromo(home))
        assertTrue(CollectorRules.pickReward(home, CollectorProfiles.LINE_WALK, emptyMap()) != null)
    }

    @Test
    fun helpTextIsNotAReward() {
        val help = Snapshot(
            1220, 2712, 0L, "com.linecorp.LGWKTW", null,
            boxes = listOf(TextBox("獲得金幣的流程", Box(300, 1200, 920, 1280), TextSource.OCR))
        )
        assertNull(CollectorRules.pickReward(help, CollectorProfiles.LINE_WALK, emptyMap()))
    }
}
