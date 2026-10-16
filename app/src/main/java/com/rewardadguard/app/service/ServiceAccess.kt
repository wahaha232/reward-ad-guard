package com.rewardadguard.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.TextUtils

/**
 * Helpers for the system Accessibility settings.
 *
 * The app never enables itself: an accessibility service can only be switched on
 * by the user, on purpose, in the system UI. These helpers open the right screen
 * and only *read* the current state.
 */
object ServiceAccess {

    /** Component name of [RewardAdAccessibilityService] for this app. */
    fun component(context: Context): ComponentName =
        ComponentName(context, RewardAdAccessibilityService::class.java)

    /** Opens the Accessibility settings screen, falling back to app details. */
    fun openAccessibilitySettings(context: Context) {
        val direct = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return

        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /**
     * Opens this app's notification settings (ISS-15).
     *
     * Used when the status notification is missing on API 33+ because
     * POST_NOTIFICATIONS was denied; sending the user straight to the right toggle
     * is the only way for them to fix it. Falls back to the app-details screen.
     */
    fun openNotificationSettings(context: Context) {
        val intent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            } else {
                appDetailsIntent(context)
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
        runCatching {
            context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))

    /**
     * Reads the enabled-service list.
     *
     * This is a read of a system setting the app is allowed to see; it is used to
     * display a status badge only. [enabled] is false when the value cannot be
     * read (some OEM builds restrict it), which is why the UI always offers a
     * manual "open settings" button as well.
     */
    fun isServiceEnabled(context: Context): Boolean {
        val expected = component(context).flattenToString()
        val expectedShort = component(context).flattenToShortString()
        val enabled = readEnabledServices(context)
        return enabled.any { it.equals(expected, ignoreCase = true) || it.equals(expectedShort, ignoreCase = true) }
    }

    private fun readEnabledServices(context: Context): List<String> {
        val raw = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull() ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(raw)
        return splitter.mapNotNull { it?.trim()?.takeIf { value -> value.isNotEmpty() } }
    }
}
