package com.rewardadguard.app.collector

/**
 * Whether another app in front is an ad's redirect or the user using the phone.
 *
 * Only the first is the run's business (asked by the user, 2026-11-13: "其他
 * APP不能誤判為導流"). At 17:47 the user swiped to Twitter during a BITWALK run;
 * a gesture makes no click event, so nothing paused the run, Twitter was taken
 * for a redirect, Back was pressed on it and BITWALK was relaunched over it.
 *
 * A redirect is what an ad does: the store or a browser, or any app that comes
 * up within a few seconds of the collector's own tap or Back. Anything else is
 * the user's, and the run steps aside.
 */
object RedirectJudge {

    /** An app opening this soon after our own action is that action's result. */
    const val OWN_ACTION_WINDOW_MILLIS = 8_000L

    private val STORE_OR_BROWSER = setOf(
        "com.android.vending",
        "com.android.chrome", "com.chrome.beta",
        "com.mi.globalbrowser", "com.android.browser",
        "org.mozilla.firefox", "com.microsoft.emmx", "com.opera.browser",
        "com.sec.android.app.sbrowser", "com.brave.browser", "com.duckduckgo.mobile.android"
    )

    fun isAdRedirect(foreground: String?, sinceOwnActionMillis: Long): Boolean {
        val pkg = foreground?.trim().orEmpty()
        if (pkg.isEmpty()) return true // unknown: keep the old, careful handling
        if (pkg in STORE_OR_BROWSER) return true
        return sinceOwnActionMillis in 0..OWN_ACTION_WINDOW_MILLIS
    }
}
