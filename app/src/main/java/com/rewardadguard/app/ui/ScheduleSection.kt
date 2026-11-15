package com.rewardadguard.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.rewardadguard.app.collector.CollectorProfiles
import com.rewardadguard.app.collector.CollectorScheduler
import com.rewardadguard.app.collector.RewardReminders
import com.rewardadguard.app.collector.ScheduleEntry
import com.rewardadguard.app.collector.ScheduleMath

/**
 * 排程收取 (2026-11-15): daily times, each with its own apps. A due schedule
 * waits for an unlocked phone nobody has used for three minutes (the phone has
 * a PIN, which the app cannot unlock), and is skipped after three hours.
 */
@Composable
fun ScheduleSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CollectorScheduler.load(context) }
    val entries by CollectorScheduler.entries.collectAsState()
    val pending by CollectorScheduler.pending.collectAsState()
    val status by CollectorScheduler.status.collectAsState()
    var editing by remember { mutableStateOf<ScheduleEntry?>(null) }
    // Re-read on every composition: the grant is changed in system settings.
    val exact = CollectorScheduler.canExact(context)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("排程收取", style = MaterialTheme.typography.titleSmall)
            Text(
                "每天到點後，等手機解鎖且 3 分鐘沒人使用才開始；3 小時內都沒機會就略過當天。",
                style = MaterialTheme.typography.bodySmall
            )
            pending?.let { run ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "⏳ 等待中：${status ?: "準備開始"}（${ScheduleMath.appsLabel(run.appKeys)}）",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = { CollectorScheduler.clearPending(context, null) }) { Text("取消這次") }
            }
            if (entries.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("尚未設定排程", style = MaterialTheme.typography.bodySmall)
            }
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = entry }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(entry.timeText, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        ScheduleMath.appsLabel(entry.appKeys),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { CollectorScheduler.upsert(context, entry.copy(enabled = it)) }
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    editing = ScheduleEntry(
                        id = System.currentTimeMillis(),
                        hour = 7,
                        minute = 30,
                        appKeys = CollectorProfiles.default.map { it.key }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("新增排程") }
            if (!exact && entries.any { it.enabled }) {
                Text(
                    "未允許「鬧鐘與提醒」權限：排程可能晚幾分鐘才觸發。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    TextButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                    Uri.parse("package:${context.packageName}")
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }) { Text("前往允許") }
                }
            }
        }
    }

    editing?.let { entry ->
        ScheduleDialog(
            initial = entry,
            isNew = entries.none { it.id == entry.id },
            noun = "排程",
            appsTitle = "要收取的 App（依序執行）",
            onDismiss = { editing = null },
            onSave = {
                CollectorScheduler.upsert(context, it)
                editing = null
            },
            onDelete = {
                CollectorScheduler.remove(context, entry.id)
                editing = null
            }
        )
    }
}

/**
 * 獎勵重置提醒 (2026-11-15): at a fixed daily time, one notification names the
 * apps whose rewards reset then, with a 開始收取 button that runs them.
 */
@Composable
fun ReminderSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { RewardReminders.load(context) }
    val entries by RewardReminders.entries.collectAsState()
    var editing by remember { mutableStateOf<ScheduleEntry?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("獎勵重置提醒", style = MaterialTheme.typography.titleSmall)
            Text(
                "到了設定的時間就推播通知，按通知上的「開始收取」直接收這些 App。多數 App 在午夜 00:00 重置。",
                style = MaterialTheme.typography.bodySmall
            )
            if (entries.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text("尚未設定提醒", style = MaterialTheme.typography.bodySmall)
            }
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = entry }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(entry.timeText, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        ScheduleMath.appsLabel(entry.appKeys),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = entry.enabled,
                        onCheckedChange = { RewardReminders.upsert(context, entry.copy(enabled = it)) }
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    editing = ScheduleEntry(
                        id = System.currentTimeMillis(),
                        hour = 0,
                        minute = 0,
                        appKeys = CollectorProfiles.default.map { it.key }
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("新增提醒") }
        }
    }

    editing?.let { entry ->
        ScheduleDialog(
            initial = entry,
            isNew = entries.none { it.id == entry.id },
            noun = "提醒",
            appsTitle = "這個時間重置的 App",
            onDismiss = { editing = null },
            onSave = {
                RewardReminders.upsert(context, it)
                editing = null
            },
            onDelete = {
                RewardReminders.remove(context, entry.id)
                editing = null
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleDialog(
    initial: ScheduleEntry,
    isNew: Boolean,
    noun: String,
    appsTitle: String,
    onDismiss: () -> Unit,
    onSave: (ScheduleEntry) -> Unit,
    onDelete: () -> Unit
) {
    val time = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    var keys by remember { mutableStateOf(initial.appKeys) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增$noun" else "編輯$noun") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TimeInput(state = time)
                Text(appsTitle, style = MaterialTheme.typography.titleSmall)
                CollectorProfiles.all.forEach { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = profile.key in keys,
                            onCheckedChange = { on ->
                                // Keep the profile order, so runs go app by app as on the main list.
                                val set = if (on) keys.toSet() + profile.key else keys.toSet() - profile.key
                                keys = CollectorProfiles.all.map { it.key }.filter { it in set }
                            }
                        )
                        Text(profile.name)
                    }
                }
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text("刪除這個$noun", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = keys.isNotEmpty(),
                onClick = { onSave(initial.copy(hour = time.hour, minute = time.minute, appKeys = keys)) }
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
