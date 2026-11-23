package com.rewardadguard.app.manager

import android.content.Context
import android.util.Log
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AppStatsRecord
import com.rewardadguard.app.data.DailyStats
import com.rewardadguard.app.data.EventRecord
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.data.RewardAdGuardDatabase
import com.rewardadguard.app.data.SessionRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Persists events, sessions and statistics.
 *
 * Threading: every write is posted to a single sender coroutine on
 * [Dispatchers.IO] through a bounded [Channel]; callers never block and never
 * touch SQLite from the accessibility callback thread. Growth on disk is
 * bounded by [AppSettings.maxLogEvents] via a periodic FIFO trim.
 *
 * Memory is bounded too (2026-11-23 review: the queue was unlimited). Past
 * [SOFT_LIMIT] pending rows only [EventPriority.isImportant] events are still
 * queued; past [QUEUE_CAPACITY] everything is dropped and counted.
 */
class EventLogger(
    context: Context,
    private val settingsProvider: () -> AppSettings
) {

    private val appContext = context.applicationContext
    private val db = RewardAdGuardDatabase.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<EventRecord>(capacity = QUEUE_CAPACITY)

    /** Rows accepted but not written yet. */
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    private val droppedEvents = java.util.concurrent.atomic.AtomicLong(0L)

    private var insertCount = 0

    private val writer = scope.launch {
            for (record in queue) {
                pending.decrementAndGet()
                try {
                    db.eventDao().insert(record)
                    insertCount++
                    if (insertCount % TRIM_EVERY == 0) {
                        db.eventDao().trimToNewest(settingsProvider().maxLogEvents)
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    droppedEvents.incrementAndGet()
                    Log.e(TAG, "Failed to persist event ${record.eventType}", t)
                }
            }
    }

    /** Fire-and-forget structured log call, safe to run on any thread. */
    fun log(
        eventType: EventType,
        sessionId: String?,
        sourcePackage: String? = null,
        destinationPackage: String? = null,
        detectionMethod: String? = null,
        action: String? = null,
        result: String? = null,
        message: String? = null,
        error: String? = null,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val settings = settingsProvider()
        if (!settings.loggingEnabled &&
            eventType != EventType.ERROR &&
            eventType != EventType.SETTINGS_CHANGED
        ) {
            return
        }
        val record = EventRecord(
            timestamp = timestamp,
            sessionId = if (sessionId.isNullOrBlank() || sessionId == NO_SESSION) {
                NO_SESSION
            } else {
                sessionId
            },
            eventType = eventType.name,
            category = eventType.category().name,
            sourcePackage = sourcePackage,
            destinationPackage = destinationPackage,
            detectionMethod = detectionMethod,
            action = action,
            result = result,
            message = message,
            error = error
        )
        // A flood of routine rows must not crowd out an error or a run result.
        if (!EventPriority.accept(pending.get(), EventPriority.isImportant(eventType, action), SOFT_LIMIT, QUEUE_CAPACITY)) {
            countDrop(eventType)
            return
        }
        if (queue.trySend(record).isSuccess) {
            pending.incrementAndGet()
        } else {
            countDrop(eventType)
        }
    }

    private fun countDrop(eventType: EventType) {
        val total = droppedEvents.incrementAndGet()
        // One line per hundred drops: a flood must not also flood logcat.
        if (total == 1L || total % 100 == 0L) {
            Log.w(TAG, "Event queue full, dropped ${eventType.name}; total dropped=$total")
        }
    }

    fun droppedEventCount(): Long = droppedEvents.get()

    suspend fun recentEvents(limit: Int): List<EventRecord> = withContext(Dispatchers.IO) {
        runCatching { db.eventDao().recent(limit) }.getOrElse {
            Log.e(TAG, "recentEvents failed", it)
            emptyList()
        }
    }

    suspend fun searchEvents(
        sessionId: String?,
        packageName: String?,
        eventType: String?,
        from: Long?,
        to: Long?,
        limit: Int = MAX_QUERY
    ): List<EventRecord> = withContext(Dispatchers.IO) {
        runCatching {
            db.eventDao().search(
                sessionId?.takeIf { it.isNotBlank() },
                packageName?.takeIf { it.isNotBlank() },
                eventType?.takeIf { it.isNotBlank() },
                from,
                to,
                limit
            )
        }.getOrElse {
            Log.e(TAG, "searchEvents failed", it)
            emptyList()
        }
    }

    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            runCatching {
                db.eventDao().deleteAll()
                db.sessionDao().deleteAll()
                db.appStatsDao().deleteAll()
            }.onFailure { Log.e(TAG, "clearAll failed", it) }
        }
        log(EventType.LOG_CLEARED, sessionId = NO_SESSION, action = "CLEAR_LOG", result = "SUCCESS")
    }

    suspend fun statsToday(): DailyStats = withContext(Dispatchers.IO) {
        try {
            val since = startOfToday()
            val eventDao = db.eventDao()
            DailyStats(
                sessions = eventDao.countByTypeSince(EventType.SESSION_START.name, since),
                externalRedirects = eventDao.countByTypeSince(EventType.REDIRECT_DETECTED.name, since),
                blocked = eventDao.countByTypeSince(EventType.BLOCK.name, since),
                xProtection = eventDao.countByTypeSince(EventType.CLOSE_DETECT.name, since),
                xAssisted = eventDao.countByTypeSince(EventType.CLOSE_RESULT.name, since),
                returnSuccess = eventDao.countByTypeSince(EventType.RETURN.name, since),
                returnFailed = eventDao.countByTypeSince(EventType.RETURN_FAILED.name, since),
                errors = eventDao.countByCategorySince("ERROR", since)
            )
        } catch (t: Throwable) {
            Log.e(TAG, "statsToday failed", t)
            DailyStats()
        }
    }

    /** Per-app counters reconstructed from the persisted event stream. */
    suspend fun statsPerApp(): List<AppStatsRecord> = withContext(Dispatchers.IO) {
        try {
            val since = startOfToday()
            val eventDao = db.eventDao()
            eventDao.distinctSessionSources().filterNotNull().map { pkg ->

                AppStatsRecord(
                    packageName = pkg,
                    label = pkg,
                    sessions = eventDao.countSessionsSince(pkg, since),
                    redirects = eventDao.countByTypeForPackage(EventType.REDIRECT_DETECTED.name, pkg, since),
                    blocked = eventDao.countByTypeForPackage(EventType.BLOCK.name, pkg, since),
                    xDetected = eventDao.countByTypeForPackage(EventType.CLOSE_DETECT.name, pkg, since),
                    xAssisted = eventDao.countByTypeForPackage(EventType.CLOSE_RESULT.name, pkg, since),
                    returnSuccess = eventDao.countByTypeForPackage(EventType.RETURN.name, pkg, since),
                    returnFailed = eventDao.countByTypeForPackage(EventType.RETURN_FAILED.name, pkg, since),
                    errors = eventDao.countByTypeForPackage(EventType.ERROR.name, pkg, since),
                    lastUpdated = System.currentTimeMillis()
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "statsPerApp failed", t)
            emptyList()
        }
    }

    suspend fun recentSessions(limit: Int = 50): List<SessionRecord> = withContext(Dispatchers.IO) {
        runCatching { db.sessionDao().recent(limit) }.getOrElse {
            Log.e(TAG, "recentSessions failed", it)
            emptyList()
        }
    }

    /** Called by SessionManager when a session ends. */
    fun persistSession(session: SessionRecord) {
        scope.launch {
            runCatching {
                db.sessionDao().upsert(session)
                db.sessionDao().trimToNewest(settingsProvider().maxSessions)
            }.onFailure { Log.e(TAG, "persistSession failed", it) }
        }
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * Stops taking new events, lets the writer finish what was already
     * accepted for at most [timeoutMillis], then stops it.
     *
     * Called from [com.rewardadguard.app.RewardAdGuardApp.onTerminate] only.
     * The wait is bounded because that runs on the main thread. A process the
     * system kills outright never gets here: rows still queued then are lost.
     */
    fun close(timeoutMillis: Long = CLOSE_TIMEOUT_MILLIS) {
        queue.close()
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) { writer.join() }
        }
        scope.cancel()
    }

    companion object {
        private const val TAG = "EventLogger"
        private const val TRIM_EVERY = 50

        /** Hard bound of the in-memory queue. */
        const val QUEUE_CAPACITY = 2_000

        /** Past this many pending rows only important events are queued. */
        const val SOFT_LIMIT = 1_500

        const val CLOSE_TIMEOUT_MILLIS = 1_000L
        private const val MAX_QUERY = 5_000

        /** Session id used for events that belong to no session. */
        const val NO_SESSION = "-"
    }
}
