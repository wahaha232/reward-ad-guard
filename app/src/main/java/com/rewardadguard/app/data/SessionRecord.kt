package com.rewardadguard.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A completed monitoring session, used for the session list / statistics.
 * A session starts when a selected reward app becomes the foreground app and
 * ends when it leaves the foreground for good (or the service is destroyed).
 */
@Entity(tableName = "sessions")
data class SessionRecord(
    @PrimaryKey
    val sessionId: String,
    val sourcePackage: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val endReason: String? = null,
    val redirectCount: Int = 0,
    val blockCount: Int = 0,
    val returnSuccessCount: Int = 0,
    val returnFailedCount: Int = 0,
    val closeDetectCount: Int = 0,
    val possibleAdSessions: Int = 0,
    val errorCount: Int = 0,
    /** Highest redirect risk observed during this session (see SmartRedirectEngine). */
    val maxRedirectRisk: Int = 0
)
