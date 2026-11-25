package com.rewardadguard.app.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BITWALK 2026-11-25: Meta's ad drawn by the Facebook app paused the run six times. */
class RemoteAdHostTest {

    private val metaAd = "com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity"

    @Test
    fun metasAdInTheFacebookAppIsAnAd() {
        assertTrue(RemoteAdHost.isAdSurface("com.facebook.katana", metaAd))
        assertTrue(RemoteAdHost.isAdSurface(" com.facebook.lite ", metaAd))
    }

    @Test
    fun facebooksOwnScreensStayTheUsers() {
        assertFalse(RemoteAdHost.isAdSurface("com.facebook.katana", "com.facebook.katana.LoginActivity"))
        assertFalse(RemoteAdHost.isAdSurface("com.facebook.katana", "com.facebook.FacebookActivity"))
        assertFalse(RemoteAdHost.isAdSurface("com.facebook.katana", null))
        assertFalse(RemoteAdHost.isAdSurface("com.facebook.katana", ""))
    }

    @Test
    fun otherAppsAreNeverAdHosts() {
        // The same activity name reported for an unrelated package is not trusted.
        assertFalse(RemoteAdHost.isAdSurface("com.shopee.tw", metaAd))
        assertFalse(RemoteAdHost.isAdSurface("com.android.chrome", metaAd))
        assertFalse(RemoteAdHost.isAdSurface(null, metaAd))
    }

    private val target = "jp.paddleinc.walk"

    @Test
    fun theAdHostMayBeTappedAndBackedOnlyWhileAuthorised() {
        val fb = "com.facebook.katana"
        assertEquals(ActionGate.Decision.DENY, ActionGate.decide(ActionGate.Action.TAP, fb, target))
        assertEquals(ActionGate.Decision.DENY, ActionGate.decide(ActionGate.Action.BACK, fb, target))
        assertEquals(
            ActionGate.Decision.ALLOW,
            ActionGate.decide(ActionGate.Action.TAP, fb, target, adHostAuthorized = fb)
        )
        assertEquals(
            ActionGate.Decision.ALLOW,
            ActionGate.decide(ActionGate.Action.BACK, fb, target, adHostAuthorized = fb)
        )
    }

    @Test
    fun anAdHostAuthorisationDoesNotOpenOtherApps() {
        val fb = "com.facebook.katana"
        assertEquals(
            ActionGate.Decision.DENY,
            ActionGate.decide(ActionGate.Action.TAP, "com.shopee.tw", target, adHostAuthorized = fb)
        )
        assertEquals(
            ActionGate.Decision.DENY,
            ActionGate.decide(ActionGate.Action.BACK, "com.miui.home", target, protectedSurface = { it == "com.miui.home" }, adHostAuthorized = fb)
        )
        assertEquals(
            ActionGate.Decision.UNKNOWN,
            ActionGate.decide(ActionGate.Action.TAP, null, target, adHostAuthorized = fb)
        )
    }
}
