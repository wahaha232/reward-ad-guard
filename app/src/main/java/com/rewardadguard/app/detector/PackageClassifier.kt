package com.rewardadguard.app.detector

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.rewardadguard.app.data.EventType

/**
 * Classifies an external destination package or URI.
 *
 * Deliberately NOT a blacklist: nothing here names "Shopee" or "Google Play" as
 * a forbidden target. Classification only describes *what kind of* destination
 * was reached, and the decision to block is made by
 * [com.rewardadguard.app.guard.RedirectGuard] from the source/session context.
 */
class PackageClassifier(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    /**
     * @param destinationPackage the package that took the foreground, if known
     * @param referrerUriScheme scheme of the URI that was being handled, if known
     */
    fun classify(destinationPackage: String?, referrerUriScheme: String? = null): DestinationKind {
        val pkg = destinationPackage?.trim().orEmpty()

        if (pkg.isEmpty()) {
            return DestinationKind.UNKNOWN
        }

        if (pkg == context.packageName) return DestinationKind.SELF

        // The home screen, system UI or an IME in the foreground is the user
        // leaving on purpose, never an ad redirect. Treating a Home press as a
        // redirect made the guard block it and then relaunch the reward app, so
        // the reward app appeared to "keep popping up" every couple of seconds.
        if (isSystemSurface(pkg)) return DestinationKind.UNKNOWN

        val launcherIntent = packageManager.getLaunchIntentForPackage(pkg)

        // Browsers: a VIEW/https handler that is also a launcher app.
        val browser = launcherIntent != null && isBrowser(pkg)
        if (browser) return DestinationKind.BROWSER

        if (isStore(pkg, referrerUriScheme)) return DestinationKind.STORE

        if (isGame(pkg)) return DestinationKind.GAME

        if (DEEP_LINK_HINTS.any { pkg.contains(it, ignoreCase = true) }) {
            return DestinationKind.DEEP_LINK
        }

        return DestinationKind.EXTERNAL_APP
    }

    /** True when the app resolves an https VIEW intent (i.e. it is a browser). */
    private fun isBrowser(packageName: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
        val handlers = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        handlers.any { it.activityInfo?.packageName == packageName } || packageName in KNOWN_BROWSERS
    } catch (t: Throwable) {
        Log.w(TAG, "isBrowser failed for $packageName", t)
        packageName in KNOWN_BROWSERS
    }

    private fun isStore(packageName: String, scheme: String?): Boolean =
        scheme == "market" || packageName in KNOWN_STORES

    private fun isGame(packageName: String): Boolean = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        (info.category == android.content.pm.ApplicationInfo.CATEGORY_GAME) ||
            GAME_HINTS.any { packageName.contains(it, ignoreCase = true) }
    } catch (t: Throwable) {
        Log.w(TAG, "isGame failed for $packageName", t)
        false
    }

    /**
     * True for the current home app, the system UI and IMEs.
     *
     * The home app is resolved from the HOME intent, so a third-party launcher is
     * recognised even without a telltale package name (mirrors AllowlistGuard).
     */
    private fun isSystemSurface(packageName: String): Boolean {
        if (packageName in SYSTEM_SURFACES) return true
        val lower = packageName.lowercase()
        if (LAUNCHER_HINTS.any { lower.contains(it) }) return true
        if (IME_HINTS.any { lower.contains(it) }) return true
        return try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == packageName
        } catch (t: Throwable) {
            false
        }
    }

    /** Maps a classification to the loggable event type. */
    fun eventTypeFor(kind: DestinationKind): EventType = when (kind) {
        DestinationKind.BROWSER -> EventType.BROWSER
        DestinationKind.STORE -> EventType.STORE
        DestinationKind.GAME -> EventType.GAME
        DestinationKind.DEEP_LINK -> EventType.DEEP_LINK
        else -> EventType.EXTERNAL_APP
    }

    companion object {
        private const val TAG = "PackageClassifier"

        private val KNOWN_BROWSERS = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "org.mozilla.firefox",
            "org.mozilla.firefox_beta",
            "com.brave.browser",
            "com.microsoft.emmx",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.sec.android.app.sbrowser",
            "com.miui.browser",
            "com.android.browser",
            "com.quark.browser",
            "com.UCMobile.intl",
            "com.heytap.browser",
            "com.vivo.browser"
        )

        private val KNOWN_STORES = setOf(
            "com.android.vending",
            "com.google.android.finsky",
            "com.huawei.appmarket",
            "com.xiaomi.market",
            "com.sec.android.app.samsungapps",
            "com.oppo.market",
            "com.heytap.market",
            "com.bbk.appstore",
            "com.amazon.venezia",
            "com.aptoide.partners"
        )

        private val LAUNCHER_HINTS = listOf("launcher", "home", "oneplus.launcher")
        private val IME_HINTS = listOf("inputmethod", "keyboard", "gboard", "swiftkey", "sogou", "ime")
        private val SYSTEM_SURFACES = setOf(
            "com.android.systemui",
            "com.android.settings",
            "android",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller"
        )

        private val GAME_HINTS = listOf("game", "play.games", "minigame")
        private val DEEP_LINK_HINTS = listOf("deeplink", "shortlink", "redirect")
    }
}

/** Destination classes reported to the log and the UI. */
enum class DestinationKind {
    SELF,
    BROWSER,
    STORE,
    GAME,
    DEEP_LINK,
    EXTERNAL_APP,
    UNKNOWN;

    val isExternal: Boolean
        get() = this != SELF && this != UNKNOWN
}
