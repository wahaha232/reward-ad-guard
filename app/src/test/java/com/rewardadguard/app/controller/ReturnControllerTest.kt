package com.rewardadguard.app.controller

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.session.SessionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Regression tests for H1: the return sequence used to `Thread.sleep` on the
 * main thread, the same thread that delivers accessibility events. The
 * foreground package could therefore never change while it was being verified,
 * every return pressed BACK [AppSettings.maxReturnAttempts] times, and an
 * auto-launch was recorded as a success whatever happened (`|| true`).
 *
 * Foreground changes are simulated the way the device delivers them: as a
 * message posted to the main looper, not as an instant assignment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReturnControllerTest {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var foreground: String? = DESTINATION
    private var backPresses = 0
    private var launches = 0
    private var result: ReturnController.ReturnResult? = null

    /** What a BACK press does on the device; set per test. */
    private var onBack: () -> Unit = {}

    private lateinit var controller: ReturnController

    @Before
    fun setUp() {
        val app = RuntimeEnvironment.getApplication() as Application
        val logger = EventLogger(app, { AppSettings() })
        controller = ReturnController(
            logger = logger,
            sessionManager = SessionManager(logger),
            backAction = {
                backPresses++
                onBack()
                true
            },
            launchSourceAction = {
                launches++
                true
            }
        ).also { it.foregroundPackageProvider = { foreground } }
    }

    @Test
    fun `a BACK that brings the reward app back is not followed by a second BACK`() {
        onBack = { deliverForeground(SOURCE) }

        schedule(AppSettings(maxReturnAttempts = 2))

        assertEquals(1, backPresses)
        assertTrue(result!!.success)
        assertEquals("GLOBAL_ACTION_BACK", result!!.method)
    }

    @Test
    fun `an auto-launch that does not bring the reward app back is a failure`() {
        schedule(AppSettings(maxReturnAttempts = 2, autoLaunchRewardApp = true))

        assertEquals(2, backPresses)
        assertEquals(1, launches)
        assertFalse(result!!.success)
        assertEquals("AUTO_LAUNCH_REWARD_APP", result!!.method)
    }

    @Test
    fun `no BACK is pressed when the reward app is already in front`() {
        foreground = SOURCE

        schedule(AppSettings(maxReturnAttempts = 2))

        assertEquals(0, backPresses)
        assertTrue(result!!.success)
    }

    @Test
    fun `cancelPending stops the sequence between attempts`() {
        controller.scheduleReturn(DESTINATION, SOURCE, AppSettings(maxReturnAttempts = 2)) { result = it }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, backPresses)

        controller.cancelPending()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))

        assertEquals(1, backPresses)
        assertNull(result)
        assertFalse(controller.isReturnInProgress)
    }

    private fun deliverForeground(pkg: String) {
        mainHandler.post { foreground = pkg }
    }

    private fun schedule(settings: AppSettings) {
        controller.scheduleReturn(DESTINATION, SOURCE, settings) { result = it }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
    }

    private companion object {
        const val SOURCE = "com.example.reward"
        const val DESTINATION = "com.example.store"
    }
}
