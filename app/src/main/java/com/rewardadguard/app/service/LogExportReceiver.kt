package com.rewardadguard.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.rewardadguard.app.manager.LogExporter

/**
 * Receives the "Export log" action from the optional status notification.
 * Keeping the export in a receiver means the notification does not have to
 * start an activity to perform a simple file write.
 */
class LogExportReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXPORT) return
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel notification failed", t)
        }
        val launch = Intent(context, com.rewardadguard.app.ui.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_REQUEST_EXPORT, true)
        }
        runCatching { context.startActivity(launch) }
            .onFailure { Log.e(TAG, "Unable to open export screen", it) }
    }

    companion object {
        private const val TAG = "LogExportReceiver"
        const val ACTION_EXPORT = "com.rewardadguard.app.action.EXPORT_LOG"
        const val EXTRA_REQUEST_EXPORT = "request_export"
        const val NOTIFICATION_ID = 4711
        const val CHANNEL_ID = "reward_ad_guard_status"
    }
}
