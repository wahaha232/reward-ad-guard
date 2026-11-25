package com.rewardadguard.app.collector

/**
 * An ad that the reward app asked for but another app draws.
 *
 * Meta Audience Network can render its ad inside the Facebook app's process:
 * the window in front is `com.facebook.katana`, activity
 * `com.facebook.ads.internal.ipc.AudienceNetworkRemoteActivity` ("Ads served
 * by Meta"). BITWALK 2026-11-25: six of these were taken for the user switching
 * to Facebook and paused the run until 繼續, and Back / relaunching BITWALK only
 * brought the ad back.
 *
 * Only the ad activity counts. Facebook's own screens (feed, Messenger) stay
 * the user's app, and the engine also checks that no finger touched the screen
 * since its own tap before treating the window as its ad.
 *
 * Pure Kotlin, unit tested directly.
 */
object RemoteAdHost {

    /** Apps known to host another app's Audience Network ad. */
    private val HOST_PACKAGES = setOf(
        "com.facebook.katana",
        "com.facebook.lite",
        "com.facebook.orca"
    )

    /** Audience Network's own activities; no Facebook screen lives here. */
    private const val AD_ACTIVITY_PREFIX = "com.facebook.ads."

    fun isAdSurface(packageName: String?, activity: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        val cls = activity?.trim().orEmpty()
        return pkg in HOST_PACKAGES && cls.startsWith(AD_ACTIVITY_PREFIX)
    }
}
