package com.rewardadguard.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One persisted log line.
 *
 * Privacy: only package names, event metadata and timestamps are stored.
 * UI text of third party apps is never persisted (see spec section 50).
 */
@Entity(tableName = "events")
data class EventRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long,
    val sessionId: String,
    val eventType: String,
    val category: String,
    val sourcePackage: String?,
    val destinationPackage: String?,
    val detectionMethod: String?,
    val action: String?,
    val result: String?,
    val message: String?,
    val error: String?
)
