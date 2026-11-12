package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 2026-11-13: records kept five days; each finished run goes to Telegram once. */
class RecordRetentionAndTelegramTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun recordsAreKeptFiveDaysThenExpire() {
        val now = 100 * day
        assertFalse(RunRetention.isExpired(now - 4 * day, now))
        assertFalse(RunRetention.isExpired(now - 5 * day, now))
        assertTrue(RunRetention.isExpired(now - 5 * day - 1, now))
        assertFalse("unknown time is never deleted", RunRetention.isExpired(0L, now))
    }

    private fun run(root: File, name: String, finished: Boolean, sent: Boolean = false): File {
        val dir = File(root, name).apply { mkdirs() }
        File(dir, "console.log").writeText("x")
        if (finished) File(dir, "summary.json").writeText("{}")
        if (sent) File(dir, TelegramReporter.SENT_MARK).writeText("1")
        return dir
    }

    @Test
    fun onlyFinishedUnsentRunsArePendingOldestFirst() {
        val root = tmp.newFolder("collector_runs")
        run(root, "run_20261113_001000", finished = true)
        run(root, "run_20261113_000900", finished = true, sent = true)
        run(root, "run_20261113_001100", finished = false) // still running
        run(root, "run_20261113_000800", finished = true)
        File(root, "notes.txt").writeText("not a run")
        assertEquals(
            listOf("run_20261113_000800", "run_20261113_001000"),
            TelegramReporter.pendingRuns(root).map { it.name }
        )
    }

    @Test
    fun runsFromBeforeTheRetryWindowAreNeverSent() {
        val root = tmp.newFolder("old")
        val old = run(root, "run_20261112_132037", finished = true)
        File(old, "summary.json").setLastModified(System.currentTimeMillis() - 7 * 60 * 60 * 1000L)
        run(root, "run_20261113_014915", finished = true)
        assertEquals(listOf("run_20261113_014915"), TelegramReporter.pendingRuns(root).map { it.name })
    }

    @Test
    fun aBacklogIsCappedToTheNewestRuns() {
        val root = tmp.newFolder("runs")
        for (i in 10..19) run(root, "run_20261113_00${i}00", finished = true)
        val pending = TelegramReporter.pendingRuns(root, limit = 3).map { it.name }
        assertEquals(listOf("run_20261113_001700", "run_20261113_001800", "run_20261113_001900"), pending)
    }

    @Test
    fun manyScreenshotsKeepTheFirstAndLastOnes() {
        val dir = tmp.newFolder("shots")
        val shots = (0 until 40).map { File(dir, "%04d_x.png".format(it)).apply { writeText("p") } }
        val picked = TelegramReporter.pickShots(shots, max = 6).map { it.name }
        assertEquals(listOf("0000_x.png", "0001_x.png", "0002_x.png", "0037_x.png", "0038_x.png", "0039_x.png"), picked)
        assertEquals(3, TelegramReporter.pickShots(shots.take(3), max = 6).size)
    }

    @Test
    fun theCaptionSummarisesTheRun() {
        val dir = run(tmp.newFolder("r"), "run_20261113_002140", finished = true)
        File(dir, "summary.json").writeText(
            """
            {
              "app": "linewalk",
              "name": "LINE 散步趣",
              "endReason": "done",
              "rewards": 2,
              "errors": 0,
              "manual": 1,
              "commit": "1b2dab6"
            }
            """.trimIndent()
        )
        val caption = TelegramReporter.caption(dir)
        assertTrue(caption, caption.contains("LINE 散步趣"))
        assertTrue(caption, caption.contains("獎勵 2"))
        assertTrue(caption, caption.contains("需手動 1"))
        assertTrue(caption, caption.contains("run_20261113_002140"))
        assertTrue(caption, caption.contains("1b2dab6"))
    }
}
