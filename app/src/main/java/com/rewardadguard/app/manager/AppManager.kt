package com.rewardadguard.app.manager

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Knows which apps are "reward apps" (sources) and how to label them.
 *
 * Package visibility: no QUERY_ALL_PACKAGES is requested. Candidate apps come
 * from [PackageManager.queryIntentActivities] for the launcher intent plus the
 * currently running/foreground packages the user added manually. This keeps the
 * visible, installed-app list minimal and excludes everything else by design.
 *
 * The reward-app list itself is stored by [RewardAppStore] because the
 * accessibility service must be able to read it synchronously.
 */
class AppManager(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    /** Launcher apps visible to this app, sorted by label. */
    suspend fun launcherApps(): List<RewardAppInfo> = withContext(Dispatchers.IO) {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            @Suppress("DEPRECATION")
            val resolved = packageManager.queryIntentActivities(intent, 0)
            resolved.asSequence()
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .map { pkg ->
                    RewardAppInfo(
                        packageName = pkg,
                        label = appLabel(pkg),
                        enabled = false,
                        isSystemApp = isSystemApp(pkg)
                    )
                }
                .sortedBy { it.label.lowercase() }
                .toList()
        } catch (t: Throwable) {
            Log.e(TAG, "launcherApps failed", t)
            emptyList()
        }
    }

    /** Friendly label, falling back to the package name. */
    fun appLabel(packageName: String): String = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(info).toString()
    } catch (t: Throwable) {
        Log.w(TAG, "appLabel fallback for $packageName: ${t.message}")
        packageName
    }

    fun isSystemApp(packageName: String): Boolean = try {
        val info = packageManager.getApplicationInfo(packageName, 0)
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    } catch (t: Throwable) {
        Log.w(TAG, "isSystemApp fallback for $packageName: ${t.message}")
        true
    }

    /**
     * True when [packageName] resolves to a real, installed, non-self app.
     */
    fun isRealApp(packageName: String): Boolean = try {
        packageManager.getApplicationInfo(packageName, 0)
        packageName != context.packageName
    } catch (t: Throwable) {
        Log.w(TAG, "isRealApp fallback for $packageName: ${t.message}")
        false
    }

    /**
     * True when [packageName] is an input method (soft keyboard).
     *
     * Keyboards were reachable through the reward-app picker and one (Gboard) was
     * actually added as a reward app in the field. That is nonsensical: an IME is
     * a system input surface that appears over *every* app, so treating it as a
     * "reward app" both pollutes the session model and makes the app list useless
     * as documentation of intent.
     *
     * Detection is by service declaration, not by package-name guessing:
     * `ApplicationInfo` has no IME flag, so the authoritative source is whether any
     * component in this package serves [android.view.inputmethod.InputMethod].
     * [KNOWN_IMES] stays as a fallback for the case where package visibility hides
     * the service query.
     */
    fun isInputMethod(packageName: String): Boolean {
        if (packageName in KNOWN_IMES) return true
        return try {
            val info = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SERVICES or PackageManager.MATCH_DISABLED_COMPONENTS
            )
            info.services?.any { service ->
                service.permission == android.Manifest.permission.BIND_INPUT_METHOD
            } == true
        } catch (t: Throwable) {
            // Visibility restrictions legitimately throw; fall back to the list.
            Log.w(TAG, "isInputMethod fallback for $packageName: ${t.message}")
            false
        }
    }

    /**
     * Package names that cannot be a reward app: the guard itself, the system
     * UI, launchers, permission controllers, IMEs and the Android framework.
     */
    fun isSystemInfrastructure(packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (packageName == "android") return true
        if (packageName.startsWith("com.android.systemui")) return true
        if (packageName in KNOWN_INFRA) return true
        if (isInputMethod(packageName)) return true
        return isSystemApp(packageName) && packageName.startsWith("com.android.")
    }

    private companion object {
        const val TAG = "AppManager"
        val KNOWN_INFRA = setOf(
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.miui.securitycenter",
            "com.miui.home",
            "com.android.launcher3"
        )

        /** Well-known third-party keyboards that the system-flag check can miss. */
        val KNOWN_IMES = setOf(
            "com.google.android.inputmethod.latin",
            "com.google.android.apps.inputmethod.hindi",
            "com.google.android.apps.inputmethod.zhuyin",
            "com.samsung.android.honeyboard",
            "com.swiftkey.swiftkeyconfigurator",
            "com.touchtype.swiftkey",
            "com.baidu.input",
            "com.sohu.inputmethod.sogou",
            "com.iflytek.inputmethod"
        )
    }
}
