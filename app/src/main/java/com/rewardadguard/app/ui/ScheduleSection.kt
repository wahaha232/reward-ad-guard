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
import com.rewardadguard.app.collector.CollectorController
import com.rewardadguard.app.collector.CollectorLimits
import com.rewardadguard.app.collector.ScanConfig
import com.rewardadguard.app.service.RewardAdAccessibilityService
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
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

/**
 * 掃描待收取 (2026-11-15): every few hours or at fixed times, open the chosen
 * apps (after the same unlock + idle wait as a schedule), count the rewards
 * still offered without tapping them, and notify the result.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ScanSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { CollectorScheduler.load(context) }
    val config by CollectorScheduler.scan.collectAsState()
    var addingTime by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val defaultKeys = CollectorProfiles.default.map { it.key }
    val keys = if (config.appsChosen) config.appKeys else defaultKeys
    // "All" is only the initial state. Once the user unchecks boxes, keep the
    // exact list even when it is empty (2026-11-21: the last checked app could
    // not be unchecked, because an empty list fell back to all of them).
    fun save(c: ScanConfig) {
        val effective = if (!c.appsChosen && c.appKeys.isEmpty()) c.copy(appKeys = defaultKeys) else c
        CollectorScheduler.saveScan(context, effective)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("掃描待收取", style = MaterialTheme.typography.titleSmall)
            Text(
                "到點後（同樣等解鎖＋閒置 3 分鐘）逐一打開 App、切分頁數還有幾個獎勵，不會按獎勵；掃完推播結果，可直接按「開始收取」。",
                style = MaterialTheme.typography.bodySmall
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ScanConfig.MODE_OFF to "關閉", ScanConfig.MODE_INTERVAL to "每隔", ScanConfig.MODE_FIXED to "固定時間").forEach { (mode, label) ->
                    FilterChip(selected = config.mode == mode, onClick = { save(config.copy(mode = mode)) }, label = { Text(label) })
                }
            }
            if (config.mode == ScanConfig.MODE_INTERVAL) {
                Text("每隔幾小時（整點掃描）", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ScanConfig.INTERVALS.forEach { h ->
                        FilterChip(
                            selected = config.intervalHours == h,
                            onClick = { save(config.copy(intervalHours = h)) },
                            label = { Text("$h 小時") }
                        )
                    }
                }
            }
            if (config.mode == ScanConfig.MODE_FIXED) {
                if (config.times.isEmpty()) Text("尚未設定時間", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    config.times.forEach { t ->
                        FilterChip(
                            selected = true,
                            onClick = { save(config.copy(times = config.times - t)) },
                            label = { Text("%02d:%02d ✕".format(t.first, t.second)) }
                        )
                    }
                    FilterChip(selected = false, onClick = { addingTime = true }, label = { Text("＋ 新增時間") })
                }
            }
            Text("掃描的 App", style = MaterialTheme.typography.bodySmall)
            CollectorProfiles.all.forEach { profile ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = profile.key in keys,
                        onCheckedChange = { on ->
                            val set = if (on) keys.toSet() + profile.key else keys.toSet() - profile.key
                            save(
                                config.copy(
                                    appKeys = CollectorProfiles.all.map { it.key }.filter { it in set },
                                    appsChosen = true
                                )
                            )
                        }
                    )
                    Text(profile.name)
                }
            }
            if (keys.isEmpty()) {
                Text(
                    "尚未選擇 App，掃描不會執行",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            OutlinedButton(
                enabled = keys.isNotEmpty(),
                onClick = {
                    val service = RewardAdAccessibilityService.instance
                    val profiles = keys.mapNotNull { CollectorProfiles.byKey(it) }
                    message = if (service == null) {
                        "無障礙服務尚未就緒"
                    } else {
                        CollectorController.start(service, profiles, CollectorLimits.UNLIMITED, dryRun = false, scan = true)
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("立即掃描") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }

    if (addingTime) {
        val time = rememberTimePickerState(initialHour = 12, initialMinute = 0, is24Hour = true)
        AlertDialog(
            onDismissRequest = { addingTime = false },
            title = { Text("新增掃描時間") },
            text = { TimeInput(state = time) },
            confirmButton = {
                TextButton(onClick = {
                    save(config.copy(times = (config.times + (time.hour to time.minute)).distinct()
                        .sortedWith(compareBy({ it.first }, { it.second }))))
                    addingTime = false
                }) { Text("新增") }
            },
            dismissButton = { TextButton(onClick = { addingTime = false }) { Text("取消") } }
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
