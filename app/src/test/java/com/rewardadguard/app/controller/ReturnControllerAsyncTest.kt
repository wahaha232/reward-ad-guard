package com.rewardadguard.app.controller

import android.app.Application
import android.os.Looper
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.session.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * ISS-01 regression: the scheduled return must not hold the main looper.
 *
 * The old [ReturnController.scheduleReturn] ran the whole attempt synchronously
 * and `Thread.sleep`d on the main looper between the BACK press and the
 * verification probe. These tests drive the main looper by hand and assert that
 * the probe is a *separate, deferred* step: it runs only after the settle
 * interval, never inline while the looper is held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReturnControllerAsyncTest {

    private val source = "com.example.reward"
    private val destination = "com.external.ad"

    private fun newController(
        onBack: () -> Unit = {},
        launch: (String) -> Boolean = { false }
    ): ReturnController {
        val app = RuntimeEnvironment.getApplication() as Application
        val logger = EventLogger(app, { AppSettings() })
        val sessionManager = SessionManager(logger)
        return ReturnController(
            logger = logger,
            sessionManager = sessionManager,
            backAction = { onBack(); true },
            launchSourceAction = launch
        )
    }

    private fun idle(millis: Long) =
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))

    @Test
    fun `the BACK press is deferred and the probe runs only after the settle interval`() {
        var backs = 0
        var foreground: String? = destination
        val controller = newController(onBack = { backs++ })
        controller.foregroundPackageProvider = { foreground }

        var result: ReturnController.ReturnResult? = null
        controller.scheduleReturn(destination, source, AppSettings(), delayMillis = 0L) { result = it }

        // Nothing has run on the caller's thread.
        assertEquals(0, backs)

        // Only the BACK press runs; the probe is still pending.
        idle(100)
        assertEquals(1, backs)
        assertNull("the verification must be deferred, not run inline", result)

        // The reward app came back before the probe fires.
        foreground = source
        idle(500)

        assertEquals("exactly one BACK", 1, backs)
        assertNotNull(result)
        assertTrue(result!!.success)
        assertEquals("GLOBAL_ACTION_BACK", result!!.method)
    }

    @Test
    fun `a second BACK is sent only when the source is still not in front`() {
        var backs = 0
        val controller = newController(onBack = { backs++ })
        controller.foregroundPackageProvider = { destination } // never returns

        var result: ReturnController.ReturnResult? = null
        val settings = AppSettings(maxReturnAttempts = 2, autoLaunchRewardApp = false)
        controller.scheduleReturn(destination, source, settings, delayMillis = 0L) { result = it }

        idle(5_000)

        assertEquals("two attempts then stop", 2, backs)
        assertNotNull(result)
        assertFalse(result!!.success)
        assertEquals(2, result!!.attempts)
    }

    @Test
    fun `the source being back before the first probe needs no second BACK`() {
        var backs = 0
        val controller = newController(onBack = { backs++ })
        controller.foregroundPackageProvider = { source } // already back

        var result: ReturnController.ReturnResult? = null
        controller.scheduleReturn(destination, source, AppSettings(), delayMillis = 0L) { result = it }

        idle(5_000)

        assertEquals(1, backs)
        assertNotNull(result)
        assertTrue(result!!.success)
    }

    @Test
    fun `cancelPending stops a queued return before it presses BACK`() {
        var backs = 0
        val controller = newController(onBack = { backs++ })
        controller.foregroundPackageProvider = { destination }

        controller.scheduleReturn(destination, source, AppSettings(), delayMillis = 1_000L)
        controller.cancelPending()
        idle(5_000)

        assertEquals("a cancelled return must not press BACK", 0, backs)
    }
}
