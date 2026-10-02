package com.rewardadguard.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Aggregated counters for one reward (source) app.
 * Kept separate from Room so the statistics screen can be rebuilt cheaply.
 */
data class AppStats(
    val packageName: String,
    val label: String,
    val sessions: Int = 0,
    val redirects: Int = 0,
    val blocked: Int = 0,
    val xDetected: Int = 0,
    val xAssisted: Int = 0,
    val returnSuccess: Int = 0,
    val returnFailed: Int = 0,
    val errors: Int = 0
)

/** Global "today" counters for the dashboard. */
data class DailyStats(
    val sessions: Int = 0,
    val externalRedirects: Int = 0,
    val blocked: Int = 0,
    val xProtection: Int = 0,
    val xAssisted: Int = 0,
    val returnSuccess: Int = 0,
    val returnFailed: Int = 0,
    val errors: Int = 0
)

/** A per-app durable counter row. */
@Entity(tableName = "app_stats")
data class AppStatsRecord(
    @PrimaryKey
    val packageName: String,
    val label: String,
    val sessions: Int = 0,
    val redirects: Int = 0,
    val blocked: Int = 0,
    val xDetected: Int = 0,
    val xAssisted: Int = 0,
    val returnSuccess: Int = 0,
    val returnFailed: Int = 0,
    val errors: Int = 0,
    val lastUpdated: Long = 0L
)
