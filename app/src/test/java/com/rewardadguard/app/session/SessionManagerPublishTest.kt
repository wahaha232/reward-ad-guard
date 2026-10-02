package com.rewardadguard.app.session

import android.app.Application
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.manager.MonitoringSnapshot
import com.rewardadguard.app.manager.MonitoringState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Integration tests for [SessionManager] that need the Android framework
 * ([EventLogger] opens a real Room database), hence Robolectric.
 *
 * [MonitoringState] is a process-wide singleton, so every test here must start
 * from a known snapshot and restore it afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionManagerPublishTest {

    private lateinit var manager: SessionManager
    private lateinit var previousSnapshot: MonitoringSnapshot

    @Before
    fun setUp() {
        previousSnapshot = MonitoringState.snapshot.value

        // Seed a stale value so a regression cannot pass by accident: the test
        // would otherwise start from the default (null) and never notice that
        // publish() keeps re-publishing an old package.
        MonitoringState.update { snapshot ->
            snapshot.copy(
                sourcePackage = STALE_PACKAGE,
                sessionId = STALE_SESSION_ID,
                sessionState = SessionState.REWARD_APP_ACTIVE
            )
        }

        val app = RuntimeEnvironment.getApplication() as Application
        manager = SessionManager(EventLogger(app, { AppSettings() }))
    }

    @After
    fun tearDown() {
        MonitoringState.update { previousSnapshot }
    }

    @Test
    fun `an empty service-destroyed session is ended but not persisted`() {
        manager.startSession(REWARD_PACKAGE, now = 1_700_000_000_000L)

        val record = manager.endSession(SessionEndReason.SERVICE_DESTROYED, now = 1_700_000_001_000L)

        assertNull("a session with no observations must not produce a record", record)
        assertNull("the session must be cleared", manager.activeSession())
    }

    @Test
    fun `ending a session clears the stale source app from the snapshot`() {
        manager.startSession(REWARD_PACKAGE, now = 1_700_000_000_000L)
        manager.endSession(SessionEndReason.SERVICE_DESTROYED, now = 1_700_000_001_000L)

        val snapshot = MonitoringState.snapshot.value
        assertNull(
            "the dashboard must not keep reporting a source app after the session ended",
            snapshot.sourcePackage
        )
        assertNull(snapshot.sessionId)
        assertEquals(SessionState.IDLE, snapshot.sessionState)
    }

    @Test
    fun `a session with observations is still persisted and then cleared`() {
        manager.startSession(REWARD_PACKAGE, now = 1_700_000_000_000L)
        manager.recordBlock()

        val record = manager.endSession(SessionEndReason.SERVICE_DESTROYED, now = 1_700_000_001_000L)

        assertEquals("a session that saw a block must be kept", 1, record?.blockCount)
        assertNull(manager.activeSession())
        assertNull(MonitoringState.snapshot.value.sourcePackage)
    }

    private companion object {
        const val REWARD_PACKAGE = "com.example.reward"
        const val STALE_PACKAGE = "com.stale.previous"
        const val STALE_SESSION_ID = "SESSION_STALE"
    }
}