package com.rewardadguard.app.collector

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rewardadguard.app.service.RewardAdAccessibilityService
import com.rewardadguard.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fixed-time "rewards have reset" reminders (asked by the user, 2026-11-15).
 *
 * Each entry is a daily time and the apps whose rewards reset then (most
 * reset at midnight). When it fires, one notification names those apps, with
 * a 開始收取 button that starts a run of exactly them (the user unlocked the
 * phone to press it, so the run may start at once).
 *
 * Detecting the reset from each app's screen was considered and set aside
 * for now: the run records keep only tapped texts, so every app's countdown
 * wording would first have to be studied on the phone.
 *
 * Shares [ScheduleEntry] and [ScheduleMath] with the run schedules; it has its
 * own entries, alarm and notification so the two never interfere.
 */
object RewardReminders {

    private const val TAG = "RewardReminders"
    private const val PREFS = "collector_reminders"
    private const val KEY_ENTRIES = "entries"
    const val ACTION_ALARM = "com.rewardadguard.app.collector.REMINDER_ALARM"
    const val ACTION_COLLECT = "com.rewardadguard.app.collector.REMINDER_COLLECT"
    private const val EXTRA_ID = "entry_id"
    private const val EXTRA_APPS = "apps"

    const val CHANNEL_ID = "collector_reminder"
    const val NOTIFICATION_ID = 4204

    private val _entries = MutableStateFlow<List<ScheduleEntry>>(emptyList())
    val entries: StateFlow<List<ScheduleEntry>> = _entries.asStateFlow()

    @Volatile
    private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _entries.value = ScheduleMath.decode(prefs(context).getString(KEY_ENTRIES, null))
            loaded = true
        }
    }

    fun upsert(context: Context, entry: ScheduleEntry) {
        load(context)
        save(context, _entries.value.filterNot { it.id == entry.id } + entry)
    }

    fun remove(context: Context, id: Long) {
        load(context)
        save(context, _entries.value.filterNot { it.id == id })
    }

    private fun save(context: Context, entries: List<ScheduleEntry>) {
        val sorted = ScheduleMath.decode(ScheduleMath.encode(entries))
        prefs(context).edit().putString(KEY_ENTRIES, ScheduleMath.encode(sorted)).apply()
        _entries.value = sorted
        reschedule(context)
    }

    /** Sets (or cancels) the single alarm for the earliest enabled reminder. */
    fun reschedule(context: Context) {
        load(context)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderAlarmReceiver::class.java).setAction(ACTION_ALARM)
        val next = ScheduleMath.nextDue(_entries.value, System.currentTimeMillis())
        if (next == null) {
            alarms.cancel(PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return
        }
        val (entry, at) = next
        intent.putExtra(EXTRA_ID, entry.id)
        val operation = PendingIntent.getBroadcast(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            }
        }.onFailure { Log.w(TAG, "reminder alarm not set", it) }
    }

    internal fun onAlarm(context: Context, entryId: Long) {
        load(context)
        val entry = _entries.value.firstOrNull { it.id == entryId && it.enabled }
        if (entry != null) notifyReset(context, entry)
        reschedule(context)
    }

    /** The reminder itself; public for the debug harness. */
    fun notifyReset(context: Context, entry: ScheduleEntry) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "獎勵重置提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "設定的時間到了，提醒去收取已重置的獎勵"
                }
            )
        }
        val apps = ScheduleMath.appsLabel(entry.appKeys)
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val collect = PendingIntent.getBroadcast(
            context, 2,
            Intent(context, ReminderAlarmReceiver::class.java)
                .setAction(ACTION_COLLECT)
                .putExtra(EXTRA_APPS, entry.appKeys.joinToString(",")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("獎勵已重置（${entry.timeText}）")
            .setContentText("可以收取了：$apps")
            .setStyle(NotificationCompat.BigTextStyle().bigText("可以收取了：$apps"))
            .setContentIntent(open)
            .addAction(0, "開始收取", collect)
            .setAutoCancel(true)
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    /** The notification's 開始收取: runs the reminder's apps now. */
    internal fun collectNow(context: Context, appsCsv: String?) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        val profiles = appsCsv.orEmpty().split(",").mapNotNull { CollectorProfiles.byKey(it) }.distinct()
        val service = RewardAdAccessibilityService.instance
        val error = if (service == null) {
            "無障礙服務尚未就緒"
        } else {
            CollectorController.start(service, profiles, CollectorLimits.UNLIMITED, dryRun = false)
        }
        if (error != null) {
            android.widget.Toast.makeText(context, "無法開始收取：$error", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    internal fun entryId(intent: Intent) = intent.getLongExtra(EXTRA_ID, -1L)
    internal fun apps(intent: Intent): String? = intent.getStringExtra(EXTRA_APPS)
}

/** The reminder's alarm and its notification button. */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            RewardReminders.ACTION_ALARM -> RewardReminders.onAlarm(app, RewardReminders.entryId(intent))
            RewardReminders.ACTION_COLLECT -> RewardReminders.collectNow(app, RewardReminders.apps(intent))
        }
    }
}
