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
    const val SCAN_NOTIFICATION_ID = 4205

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

    private fun ensureChannel(manager: NotificationManager) {
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "獎勵提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "獎勵重置提醒與掃描結果"
            }
        )
    }

    /** The reminder itself; public for the debug harness. */
    fun notifyReset(context: Context, entry: ScheduleEntry) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
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

    /**
     * The result of a scan run (2026-11-15): what each app still offers, with
     * 開始收取 for the apps that have something left.
     */
    fun notifyScan(context: Context, results: List<ScanResult>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)
        // A repeatable ad button counts as "something left", not as a number.
        val total = results.filter { !it.repeatable }.sumOf { it.available.coerceAtLeast(0) }
        val ads = results.any { it.repeatable && it.available > 0 }
        val lines = results.joinToString("\n") { it.text() }
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle(
                when {
                    total > 0 && ads -> "掃描完成：尚有 $total 個獎勵＋廣告獎勵待收取"
                    total > 0 -> "掃描完成：尚有 $total 個獎勵待收取"
                    ads -> "掃描完成：有廣告獎勵待收取"
                    else -> "掃描完成：沒有待收取的獎勵"
                }
            )
            .setContentText(lines.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setContentIntent(open)
            .setAutoCancel(true)
        val pendingKeys = results.filter { it.available > 0 }.map { it.appKey }
        if (pendingKeys.isNotEmpty()) {
            val collect = PendingIntent.getBroadcast(
                context, 4,
                Intent(context, ReminderAlarmReceiver::class.java)
                    .setAction(ACTION_COLLECT)
                    .putExtra(EXTRA_APPS, pendingKeys.joinToString(",")),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "開始收取", collect)
        }
        runCatching { manager.notify(SCAN_NOTIFICATION_ID, builder.build()) }
    }

    /** The notification's 開始收取: runs the reminder's apps now. */
    internal fun collectNow(context: Context, appsCsv: String?) {
        context.getSystemService(NotificationManager::class.java)?.apply {
            cancel(NOTIFICATION_ID)
            cancel(SCAN_NOTIFICATION_ID)
        }
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

/** What a scan found in one app. [available] is -1 when the scan could not count. */
data class ScanResult(
    val appKey: String,
    val appName: String,
    val available: Int,
    val endReason: String,
    /** One button that pays per ad, again and again: the count is not how many are left. */
    val repeatable: Boolean = false,
    /** What the user must do themselves (a blocker screen such as AHEALTH's sleep mode). */
    val note: String? = null
) {
    fun text(): String = when {
        endReason == "blocked_screen" && note != null -> note
        repeatable && available > 0 -> "$appName：有廣告獎勵可收（可重複看，次數不明）"
        endReason != "done" && available <= 0 -> "$appName：未掃完（$endReason）"
        endReason != "done" -> "$appName：至少 $available 個（未掃完）"
        available > 0 -> "$appName：尚有 $available 個"
        else -> "$appName：已收完"
    }
}
