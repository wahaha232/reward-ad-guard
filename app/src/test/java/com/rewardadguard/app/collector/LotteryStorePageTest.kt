package com.rewardadguard.app.collector

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
        assertTrue(LineWalkRules.looksLikeStorePage(screen("Google Play")))
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
