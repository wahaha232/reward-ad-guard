package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-23 review, P0: a run must never operate an app the user opened, and
 * must keep handling the ad's own redirects.
 */
class CollectorSafetyTest {

    private val target = "jp.paddleinc.walk"
    private val launcher = "com.mi.android.globallauncher"
    private val protected: (String) -> Boolean = { CollectorRules.isSystemPackage(it) || CollectorRules.isLauncher(it) }

    private fun back(fg: String?, authorized: String? = null) =
        ActionGate.decide(ActionGate.Action.BACK, fg, target, authorized, protected)

    // ---- Back gate

    @Test
    fun backInsideTheRewardAppIsAllowed() {
        assertEquals(ActionGate.Decision.ALLOW, back(target))
    }

    @Test
    fun backOnAnUnknownForegroundIsNeverGuessed() {
        assertEquals(ActionGate.Decision.UNKNOWN, back(null))
        assertEquals(ActionGate.Decision.UNKNOWN, back("  "))
    }

    @Test
    fun backOnAnAppTheRunDidNotJudgeIsRefused() {
        // Twitter / Chrome the user opened: no authorisation.
        assertEquals(ActionGate.Decision.DENY, back("com.twitter.android"))
        assertEquals(ActionGate.Decision.DENY, back("com.android.chrome"))
        // Authorised for another package: still refused.
        assertEquals(ActionGate.Decision.DENY, back("com.twitter.android", authorized = "com.android.vending"))
    }

    @Test
    fun backOnTheAdsJudgedRedirectStillWorks() {
        assertEquals(ActionGate.Decision.ALLOW, back("com.android.vending", authorized = "com.android.vending"))
        assertEquals(ActionGate.Decision.ALLOW, back("com.android.chrome", authorized = "com.android.chrome"))
        assertEquals(ActionGate.Decision.ALLOW, back("com.shopback.app", authorized = "com.shopback.app"))
    }

    @Test
    fun backIsNeverPressedOnHomeShadeOrSettings() {
        // Even if something authorised them by mistake.
        assertEquals(ActionGate.Decision.DENY, back(launcher, authorized = launcher))
        assertEquals(ActionGate.Decision.DENY, back("com.android.systemui", authorized = "com.android.systemui"))
        assertEquals(ActionGate.Decision.DENY, back("com.android.settings", authorized = "com.android.settings"))
    }

    @Test
    fun tapsStayInsideTheTarget() {
        assertEquals(ActionGate.Decision.ALLOW, ActionGate.decide(ActionGate.Action.TAP, target, target))
        assertEquals(ActionGate.Decision.DENY, ActionGate.decide(ActionGate.Action.TAP, "com.twitter.android", target))
        assertEquals(ActionGate.Decision.UNKNOWN, ActionGate.decide(ActionGate.Action.TAP, null, target))
    }

    // ---- Redirect judgement

    @Test
    fun aFingerAfterOurLastActionMakesTheNextAppTheUsers() {
        // Swiped to Twitter 3 s after our tap: no longer "the ad's doing".
        assertFalse(RedirectJudge.isAdRedirect("com.twitter.android", 3_000, userTouchedSinceOwnAction = true))
        // Opened Chrome themselves: not a redirect either.
        assertFalse(RedirectJudge.isAdRedirect("com.android.chrome", 3_000, userTouchedSinceOwnAction = true))
    }

    @Test
    fun withoutATouchTheAdsRedirectsAreStillCaught() {
        assertTrue(RedirectJudge.isAdRedirect("com.android.vending", 60_000, userTouchedSinceOwnAction = false))
        assertTrue(RedirectJudge.isAdRedirect("com.android.chrome", 2_000, userTouchedSinceOwnAction = false))
        assertTrue(RedirectJudge.isAdRedirect("com.coupang.mobile.lightspeed", 5_000, userTouchedSinceOwnAction = false))
    }

    @Test
    fun launchersAreKnown() {
        assertTrue(CollectorRules.isLauncher(launcher))
        assertTrue(CollectorRules.isLauncher("com.miui.home"))
        assertFalse(CollectorRules.isLauncher("com.twitter.android"))
        assertFalse(CollectorRules.isLauncher(null))
    }

    // ---- Who touched the screen

    @Test
    fun aPhysicalFingerIsTheUserEvenDuringOurGesture() {
        assertEquals(TouchOrigin.USER, TouchOrigin.classify(deviceId = 6, ownGestureInFlight = true, onFloatingPanel = false))
        assertEquals(TouchOrigin.USER, TouchOrigin.classify(deviceId = 6, ownGestureInFlight = false, onFloatingPanel = false))
    }

    @Test
    fun ourInjectedGestureIsNotTheUser() {
        assertEquals(TouchOrigin.OWN_GESTURE, TouchOrigin.classify(deviceId = -1, ownGestureInFlight = true, onFloatingPanel = false))
    }

    @Test
    fun anInjectionThatIsNotOursCountsAsAPerson() {
        assertEquals(TouchOrigin.USER, TouchOrigin.classify(deviceId = -1, ownGestureInFlight = false, onFloatingPanel = false))
    }

    @Test
    fun theFloatingPanelIsTheUserCommandingTheRun() {
        assertEquals(TouchOrigin.PANEL, TouchOrigin.classify(deviceId = 6, ownGestureInFlight = false, onFloatingPanel = true))
    }

    @Test
    fun launchersStayProtectedSystemSurfaces() {
        // One launcher list now feeds both checks (review 2026-11-23).
        assertTrue(CollectorRules.isSystemPackage(launcher))
        assertTrue(CollectorRules.isSystemPackage("com.android.systemui"))
        assertFalse(CollectorRules.isSystemPackage("com.twitter.android"))
    }

    @Test
    fun scheduleEntriesAreSortedByTimeOfDay() {
        val entries = listOf(
            ScheduleEntry(1, 18, 30, emptyList()),
            ScheduleEntry(2, 6, 5, emptyList()),
            ScheduleEntry(3, 6, 0, emptyList())
        )
        assertEquals(listOf(3L, 2L, 1L), ScheduleMath.sorted(entries).map { it.id })
        assertEquals(ScheduleMath.sorted(entries), ScheduleMath.decode(ScheduleMath.encode(entries)))
    }
}
