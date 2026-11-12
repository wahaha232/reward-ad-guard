package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Review P2-7 / P2-8: what counts as a store page and as a prize. */
class LotteryStorePageTest {

    private fun screen(vararg lines: String): Snapshot = Snapshot(
        width = 1220,
        height = 2712,
        capturedAt = 0L,
        foregroundPackage = "com.linecorp.LGWKTW",
        foregroundActivity = null,
        boxes = lines.mapIndexed { i, text ->
            TextBox(text, Box(100, 200 + i * 120, 900, 300 + i * 120), TextSource.OCR)
        }
    )

    private fun fixture(name: String): Snapshot {
        val url = requireNotNull(javaClass.classLoader?.getResource("collector-fixtures/$name"))
        return OcrJson.parse(File(url.toURI()).readText())
    }

    @Test
    fun ordinaryWordsAreNotAStorePage() {
        assertFalse(LineWalkRules.looksLikeStorePage(screen("開啟通知", "抽抽樂", "下載獎勵明細")))
        assertFalse(LineWalkRules.looksLikeStorePage(screen("Install"))) // one word alone
    }

    @Test
    fun theInAppGooglePlayPageIsAStorePage() {
        assertTrue(LineWalkRules.looksLikeStorePage(screen("momo購物", "Install", "More info")))
        assertTrue(LineWalkRules.looksLikeStorePage(screen("I n s t a l l", "M o r e i n f o")))
    }

    @Test
    fun theLotterySheetWithAStoreBannerIsNotAStorePage() {
        // 2026-11-12 20:01: the sheet's banner ad read "Grok Bot / Google Play /
        // 安裝"; calling that a store page pressed Back and left LINE.
        val sheet = fixture("line_lottery_sheet.ocr.json")
        val banner = listOf("Google Play", "安裝", "Install", "More info", "Play Store").mapIndexed { i, text ->
            TextBox(text, Box(200, 1500 + i * 60, 700, 1550 + i * 60), TextSource.OCR)
        }
        val withBanner = sheet.copy(boxes = sheet.boxes + banner)
        assertTrue(LineWalkRules.lotterySheet(withBanner) != null)
        assertFalse(LineWalkRules.looksLikeStorePage(withBanner))
        assertFalse("Google Play alone is a banner, not a page", LineWalkRules.looksLikeStorePage(screen("Google Play", "安裝")))
    }

    @Test
    fun theWindowOnScreenDecidesThePackage() {
        assertEquals(
            "com.gimmatek.hahago.aha",
            ForegroundResolver.packageName("com.gimmatek.hahago.aha", "com.linecorp.LGWKTW")
        )
        assertEquals("com.linecorp.LGWKTW", ForegroundResolver.packageName(null, "com.linecorp.LGWKTW"))
        assertEquals("com.linecorp.LGWKTW", ForegroundResolver.packageName(" ", "com.linecorp.LGWKTW"))
    }

    @Test
    fun recordedLotteryScreensAreNotStorePages() {
        for (name in listOf(
            "line_lottery_sheet.ocr.json",
            "line_lottery_all_three.ocr.json",
            "line_lottery_result.ocr.json",
            "line_lottery_scrolled.ocr.json"
        )) {
            assertFalse(name, LineWalkRules.looksLikeStorePage(fixture(name)))
        }
    }

    @Test
    fun aPrizeCountsWithOrWithoutItsCloseButton() {
        assertTrue(LineWalkRules.isLotteryPrize(fixture("line_lottery_result.ocr.json")))
        assertTrue(LineWalkRules.isLotteryPrize(screen("20枚金幣中獎囉!")))
        assertFalse(LineWalkRules.isLotteryPrize(fixture("line_lottery_sheet.ocr.json")))
    }
}
