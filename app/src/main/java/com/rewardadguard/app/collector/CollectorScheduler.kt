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
 * Daily collection schedules (asked by the user, 2026-11-15).
 *
 * Entries and the one pending run live in SharedPreferences. One AlarmManager
 * alarm is kept for the earliest enabled entry; when it fires the run becomes
 * [pending] and the accessibility service's [ScheduledRunner] starts it once
 * the phone is unlocked and idle (see [ScheduleMath]). The alarm is set again
 * after every change, every alarm and every service (re)connect, so a reboot
 * or an app update re-arms it as soon as the accessibility service is back.
 */
object CollectorScheduler {

    private const val TAG = "CollectorScheduler"
    private const val PREFS = "collector_schedule"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_PENDING = "pending"
    private const val KEY_SCAN = "scan"
    private const val EXTRA_SCAN = "scan"
    const val ACTION_ALARM = "com.rewardadguard.app.collector.SCHEDULE_ALARM"
    private const val EXTRA_ID = "entry_id"
    private const val EXTRA_DUE = "due_at"

    const val CHANNEL_ID = "collector_schedule"
    const val NOTIFICATION_ID = 4203

    private val _entries = MutableStateFlow<List<ScheduleEntry>>(emptyList())
    val entries: StateFlow<List<ScheduleEntry>> = _entries.asStateFlow()

    private val _scan = MutableStateFlow(ScanConfig())
    val scan: StateFlow<ScanConfig> = _scan.asStateFlow()

    private val _pending = MutableStateFlow<PendingRun?>(null)
    val pending: StateFlow<PendingRun?> = _pending.asStateFlow()

    /** What the waiting run is waiting for, for the UI ("等待解鎖" ...). */
    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    @Volatile
    private var loaded = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val p = prefs(context)
            _entries.value = ScheduleMath.decode(p.getString(KEY_ENTRIES, null))
            _pending.value = ScheduleMath.decodePending(p.getString(KEY_PENDING, null))
            _scan.value = ScanConfig.decode(p.getString(KEY_SCAN, null))
            loaded = true
        }
    }

    fun save(context: Context, entries: List<ScheduleEntry>) {
        load(context)
        val sorted = ScheduleMath.sorted(entries)
        prefs(context).edit().putString(KEY_ENTRIES, ScheduleMath.encode(sorted)).apply()
        _entries.value = sorted
        reschedule(context)
    }

    fun upsert(context: Context, entry: ScheduleEntry) {
        load(context)
        save(context, _entries.value.filterNot { it.id == entry.id } + entry)
    }

    fun remove(context: Context, id: Long) {
        load(context)
        save(context, _entries.value.filterNot { it.id == id })
        if (_pending.value?.entryId == id) clearPending(context, null)
    }

    fun saveScan(context: Context, config: ScanConfig) {
        load(context)
        prefs(context).edit().putString(KEY_SCAN, ScanConfig.encode(config)).apply()
        _scan.value = config
        reschedule(context)
    }

    internal fun setPending(context: Context, run: PendingRun) {
        prefs(context).edit().putString(KEY_PENDING, ScheduleMath.encodePending(run)).apply()
        _pending.value = run
    }

    /** Drops the pending run; [message] leaves a closing notification when set. */
    fun clearPending(context: Context, message: String?) {
        prefs(context).edit().remove(KEY_PENDING).apply()
        _pending.value = null
        _status.value = null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (message == null) {
            manager.cancel(NOTIFICATION_ID)
        } else {
            notify(context, "排程收取", message, ongoing = false)
        }
    }

    internal fun setStatus(context: Context, text: String?) {
        if (_status.value == text) return
        _status.value = text
        val run = _pending.value ?: return
        if (text != null) {
            val what = if (run.scan) "排程掃描" else "排程收取"
            notify(context, "${what}等待中（${timeOf(run)}）", "$text・${ScheduleMath.appsLabel(run.appKeys)}", ongoing = true)
        }
    }

    private fun timeOf(run: PendingRun): String =
        (_entries.value + _scan.value.entries()).firstOrNull { it.id == run.entryId }?.timeText ?: "排程"

    /** Sets (or cancels) the alarms: one for the collection schedules, one for the scans. */
    fun reschedule(context: Context) {
        load(context)
        arm(context, requestCode = 0, entries = _entries.value, scan = false)
        arm(context, requestCode = 1, entries = _scan.value.entries(), scan = true)
    }

    private fun arm(context: Context, requestCode: Int, entries: List<ScheduleEntry>, scan: Boolean) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val next = ScheduleMath.nextDue(entries, System.currentTimeMillis())
        val intent = Intent(context, ScheduleAlarmReceiver::class.java).setAction(ACTION_ALARM)
        if (next == null) {
            alarms.cancel(PendingIntent.getBroadcast(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            return
        }
        val (entry, at) = next
        intent.putExtra(EXTRA_ID, entry.id).putExtra(EXTRA_DUE, at).putExtra(EXTRA_SCAN, scan)
        val operation = PendingIntent.getBroadcast(
            context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            if (canExact(alarms)) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            } else {
                // Without the exact-alarm grant Doze may deliver this some
                // minutes late; the run waits for an idle phone anyway.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
            }
        }.onFailure { Log.w(TAG, "alarm not set", it) }
    }

    fun canExact(context: Context): Boolean =
        context.getSystemService(AlarmManager::class.java)?.let { canExact(it) } ?: false

    private fun canExact(alarms: AlarmManager): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    internal fun onAlarm(context: Context, entryId: Long, dueAt: Long, scan: Boolean) {
        load(context)
        val entry = (if (scan) _scan.value.entries() else _entries.value).firstOrNull { it.id == entryId && it.enabled }
        val waiting = _pending.value
        // A collection still waiting is never pushed aside by a scan; a newer
        // due run otherwise replaces the older one.
        if (entry != null && !(scan && waiting != null && !waiting.scan)) {
            setPending(
                context,
                PendingRun(entry.id, dueAt, entry.appKeys, scan, if (scan) _scan.value.graceMillis() else ScheduleMath.GRACE_MILLIS)
            )
            setStatus(context, null)
            setStatus(context, "時間到了，準備開始")
            RewardAdAccessibilityService.instance?.collectorScheduledRunner()?.kick()
        }
        reschedule(context)
    }

    private fun notify(context: Context, title: String, text: String, ongoing: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "排程收取", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "排程時間到了、等待解鎖或閒置、略過時通知"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setAutoCancel(!ongoing)
        runCatching { manager.notify(NOTIFICATION_ID, builder.build()) }
    }

    internal fun entryId(intent: Intent) = intent.getLongExtra(EXTRA_ID, -1L)
    internal fun dueAt(intent: Intent) = intent.getLongExtra(EXTRA_DUE, System.currentTimeMillis())
    internal fun isScan(intent: Intent) = intent.getBooleanExtra(EXTRA_SCAN, false)
}

/** The schedule's alarm. */
class ScheduleAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CollectorScheduler.ACTION_ALARM) return
        CollectorScheduler.onAlarm(
            context.applicationContext,
            CollectorScheduler.entryId(intent),
            CollectorScheduler.dueAt(intent),
            CollectorScheduler.isScan(intent)
        )
    }
}
