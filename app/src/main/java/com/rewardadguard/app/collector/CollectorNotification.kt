package com.rewardadguard.app.collector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.rewardadguard.app.R

/**
 * Persistent notification for a running collection (phase 5).
 *
 * It carries pause/resume/stop so the user never has to reopen the app while a
 * run is driving the phone. The actions are broadcasts to
 * [CollectorActionReceiver]; the controller owns the state, so the buttons do
 * not duplicate any logic.
 */
object CollectorNotification {

    const val CHANNEL_ID = "collector_run"
    const val NOTIFICATION_ID = 4201

    const val ACTION_PAUSE = "com.rewardadguard.app.collector.PAUSE"
    const val ACTION_RESUME = "com.rewardadguard.app.collector.RESUME"
    const val ACTION_STOP = "com.rewardadguard.app.collector.STOP"

    fun update(context: Context, state: CollectorController.State) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!state.running) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(manager, context)

        val title = state.appName?.let { "自動收取：$it" }
            ?: context.getString(R.string.collector_title)
        val text = state.message +
            if (state.rewards > 0) "（已收 ${state.rewards}）" else ""

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.paused) {
            builder.addAction(0, context.getString(R.string.collector_resume), broadcast(context, ACTION_RESUME, 1))
        } else {
            builder.addAction(0, context.getString(R.string.collector_pause), broadcast(context, ACTION_PAUSE, 2))
        }
        builder.addAction(0, context.getString(R.string.collector_stop), broadcast(context, ACTION_STOP, 3))

        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CollectorActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(manager: NotificationManager, context: Context) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.collector_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.collector_notification_channel_description)
                setShowBadge(false)
            }
        )
    }
}
