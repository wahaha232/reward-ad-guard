package com.rewardadguard.app.collector

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One line of the home page's daily summary (asked by the user, 2026-11-15):
 * "[2026/11/15] BITWALK 已收穫 23 個獎勵，尚有 2 個獎勵待收取".
 *
 * [collected] sums every real (not dry) run of that app that day. What is
 * still waiting is only known as far as the collector knows it: [manual] is
 * what the day's last run left for the user, and [finished] is false when
 * that run did not end with "done" (stopped, paused too long, ...), so more
 * may be waiting than [manual] says.
 */
data class DailyAppSummary(
    val day: LocalDate,
    val appKey: String,
    val appName: String,
    val collected: Int,
    val manual: Int,
    val finished: Boolean
) {
    fun text(): String {
        val date = "%04d/%02d/%02d".format(day.year, day.monthValue, day.dayOfMonth)
        val rest = when {
            !finished && manual > 0 -> "尚有 $manual 個獎勵待收取，且上次未跑完"
            !finished -> "上次未跑完，可能還有獎勵"
            manual > 0 -> "尚有 $manual 個獎勵待收取"
            else -> "已全部收完"
        }
        return "[$date] $appName 已收穫 $collected 個獎勵，$rest"
    }
}

/** One run's summary.json, reduced to what the daily summary needs. */
data class RunSummaryRow(
    val appKey: String,
    val appName: String,
    val startedAt: Long,
    val rewards: Int,
    val manual: Int,
    val endReason: String,
    val dryRun: Boolean
)

object DailyRewardSummary {

    /** Groups runs per day and app; newest day first, apps in run order. */
    fun summarize(runs: List<RunSummaryRow>, zone: ZoneId = ZoneId.systemDefault()): List<DailyAppSummary> {
        val real = runs.filter { !it.dryRun && it.endReason != "not_started" }.sortedBy { it.startedAt }
        val groups = LinkedHashMap<Pair<LocalDate, String>, MutableList<RunSummaryRow>>()
        for (run in real) {
            val day = Instant.ofEpochMilli(run.startedAt).atZone(zone).toLocalDate()
            groups.getOrPut(day to run.appKey) { mutableListOf() }.add(run)
        }
        return groups.map { (key, list) ->
            val last = list.last()
            DailyAppSummary(
                day = key.first,
                appKey = key.second,
                appName = last.appName,
                collected = list.sumOf { it.rewards },
                manual = last.manual,
                finished = last.endReason == "done"
            )
        }.sortedWith(compareByDescending<DailyAppSummary> { it.day })
    }

    fun parse(json: String): RunSummaryRow? {
        val map = runCatching { MiniJson.parse(json) }.getOrNull() as? Map<*, *> ?: return null
        val key = map["app"] as? String ?: return null
        return RunSummaryRow(
            appKey = key,
            appName = map["name"] as? String ?: key,
            startedAt = (map["startedAt"] as? Number)?.toLong() ?: return null,
            rewards = (map["rewards"] as? Number)?.toInt() ?: 0,
            manual = (map["manual"] as? Number)?.toInt() ?: 0,
            endReason = map["endReason"] as? String ?: "",
            dryRun = map["dryRun"] == true
        )
    }

    /** Reads the runs of the last [days] days from `files/collector_runs`. */
    fun load(filesDir: File, days: Long = 2, zone: ZoneId = ZoneId.systemDefault()): List<DailyAppSummary> {
        val since = LocalDate.now(zone).minusDays(days - 1)
        val dirs = File(filesDir, "collector_runs").listFiles { f -> f.isDirectory } ?: return emptyList()
        val rows = dirs.mapNotNull { dir ->
            val file = File(dir, "summary.json")
            if (!file.isFile) null else parse(runCatching { file.readText() }.getOrDefault(""))
        }.filter { !Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate().isBefore(since) }
        return summarize(rows, zone)
    }
}
