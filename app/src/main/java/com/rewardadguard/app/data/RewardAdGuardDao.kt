package com.rewardadguard.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface EventDao {

    @Insert
    suspend fun insert(event: EventRecord): Long

    @Query("SELECT * FROM events ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EventRecord>

    @Query(
        """
        SELECT * FROM events
        WHERE category = :category
        ORDER BY timestamp DESC, id DESC LIMIT :limit
        """
    )
    suspend fun recentByCategory(category: String, limit: Int): List<EventRecord>

    @Query(
        """
        SELECT * FROM events
        WHERE (:sessionId IS NULL OR sessionId LIKE '%' || :sessionId || '%')
          AND (:packageName IS NULL OR sourcePackage LIKE '%' || :packageName || '%'
               OR destinationPackage LIKE '%' || :packageName || '%')
          AND (:eventType IS NULL OR eventType LIKE '%' || :eventType || '%')
          AND (:from IS NULL OR timestamp >= :from)
          AND (:to IS NULL OR timestamp <= :to)
        ORDER BY timestamp DESC, id DESC LIMIT :limit
        """
    )
    suspend fun search(
        sessionId: String?,
        packageName: String?,
        eventType: String?,
        from: Long?,
        to: Long?,
        limit: Int
    ): List<EventRecord>

    @Query("SELECT COUNT(*) FROM events")
    suspend fun count(): Int

    /**
     * FIFO trim: keep the newest [keep] rows, delete everything older.
     * Called after inserts so the log can never grow without bound.
     */
    @Query(
        """
        DELETE FROM events WHERE id NOT IN (
            SELECT id FROM events ORDER BY timestamp DESC, id DESC LIMIT :keep
        )
        """
    )
    suspend fun trimToNewest(keep: Int): Int

    @Query("DELETE FROM events")
    suspend fun deleteAll()

    /** Statistics primitives. */
    @Query(
        """
        SELECT COUNT(*) FROM events
        WHERE eventType = :eventType AND timestamp >= :since
        """
    )
    suspend fun countByTypeSince(eventType: String, since: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM events
        WHERE category = :category AND timestamp >= :since
        """
    )
    suspend fun countByCategorySince(category: String, since: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM events
        WHERE eventType = :eventType AND timestamp >= :since
          AND (sourcePackage = :packageName OR destinationPackage = :packageName)
        """
    )
    suspend fun countByTypeForPackage(eventType: String, packageName: String, since: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM events
        WHERE eventType = 'SESSION_START'
          AND sourcePackage = :packageName
          AND timestamp >= :since
        """
    )
    suspend fun countSessionsSince(packageName: String, since: Long): Int

    /** Distinct source packages seen at least once (used for per-app aggregation). */
    @Query("SELECT DISTINCT sourcePackage FROM events WHERE eventType = 'SESSION_START'")
    suspend fun distinctSessionSources(): List<String>
}

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionRecord)

    @Update
    suspend fun update(session: SessionRecord)

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<SessionRecord>

    @Query("SELECT * FROM sessions WHERE sessionId = :sessionId")
    suspend fun byId(sessionId: String): SessionRecord?

    @Query("SELECT COUNT(*) FROM sessions WHERE startedAt >= :since")
    suspend fun countSince(since: Long): Int

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    /** Keep only the newest [keep] sessions (FIFO), returns rows removed. */
    @Query(
        """
        DELETE FROM sessions WHERE sessionId NOT IN (
            SELECT sessionId FROM sessions ORDER BY startedAt DESC LIMIT :keep
        )
        """
    )
    suspend fun trimToNewest(keep: Int): Int
}

@Dao
interface AppStatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stats: AppStatsRecord)

    @Query("SELECT * FROM app_stats ORDER BY packageName ASC")
    suspend fun all(): List<AppStatsRecord>

    @Query("SELECT * FROM app_stats WHERE packageName = :packageName")
    suspend fun byPackage(packageName: String): AppStatsRecord?

    @Query("DELETE FROM app_stats")
    suspend fun deleteAll()
}
