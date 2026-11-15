package com.rewardadguard.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.collector.CollectorProfiles
import com.rewardadguard.app.collector.CustomApp
import com.rewardadguard.app.collector.CustomApps
import com.rewardadguard.app.manager.RewardAppInfo

/**
 * Edits the collector's app list (2026-11-15): hide or restore the built-in
 * apps, add, edit or remove apps collected with the generic rules.
 */
@Composable
fun AppListDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val hidden by CustomApps.hidden.collectAsState()
    val custom by CustomApps.custom.collectAsState()
    var editing by remember { mutableStateOf<CustomApp?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("編輯收取的 App") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("內建 App（取消勾選＝從清單移除）", style = MaterialTheme.typography.titleSmall)
                CollectorProfiles.builtIn.forEach { profile ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = profile.key !in hidden,
                            onCheckedChange = { CustomApps.setHidden(context, profile.key, hide = !it) }
                        )
                        Text(profile.name)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("自己新增的 App（通用規則）", style = MaterialTheme.typography.titleSmall)
                if (custom.isEmpty()) {
                    Text("尚未新增", style = MaterialTheme.typography.bodySmall)
                }
                custom.forEach { app ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = app }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(app.name)
                        Text(
                            "按：${app.rewardTerms.joinToString("、")}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                OutlinedButton(
                    onClick = {
                        editing = CustomApp(System.currentTimeMillis(), "", "", CustomApp.DEFAULT_REWARD)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("新增 App") }
                Text(
                    "新增的 App 沒有專屬規則：程式只按你填的文字、避開危險字（購買、安裝、關閉…），收不收得到要實際跑跑看。",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } }
    )

    editing?.let { app ->
        CustomAppDialog(
            initial = app,
            isNew = custom.none { it.id == app.id },
            onDismiss = { editing = null },
            onSave = {
                CustomApps.upsert(context, it)
                editing = null
            },
            onDelete = {
                CustomApps.remove(context, app.id)
                editing = null
            }
        )
    }
}

@Composable
private fun CustomAppDialog(
    initial: CustomApp,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (CustomApp) -> Unit,
    onDelete: () -> Unit
) {
    var pkg by remember { mutableStateOf(initial.packageName) }
    var name by remember { mutableStateOf(initial.name) }
    var reward by remember { mutableStateOf(initial.rewardTerms.joinToString("、")) }
    var avoid by remember { mutableStateOf(initial.avoidTerms.joinToString("、")) }
    var search by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<RewardAppInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        apps = runCatching { RewardAdGuardApp.Container.appManager.launcherApps() }.getOrDefault(emptyList())
            .sortedBy { it.label }
        loading = false
    }
    val rewardTerms = CustomApps.splitTerms(reward)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增 App" else "編輯 App") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    if (pkg.isEmpty()) "請在下方選一個 App" else "已選：$name\n$pkg",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (isNew) {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        label = { Text("搜尋已安裝的 App") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val shown = apps.filter {
                        search.isBlank() || it.label.contains(search, ignoreCase = true) ||
                            it.packageName.contains(search, ignoreCase = true)
                    }.take(30)
                    if (loading) Text("讀取已安裝的 App…", style = MaterialTheme.typography.bodySmall)
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        shown.forEach { info ->
                            Text(
                                info.label,
                                color = if (info.packageName == pkg) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        pkg = info.packageName
                                        name = info.label
                                    }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("顯示名稱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = reward,
                    onValueChange = { reward = it },
                    label = { Text("要按的獎勵按鈕文字（用、分隔）") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = avoid,
                    onValueChange = { avoid = it },
                    label = { Text("不要按的文字（選填）") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (!isNew) {
                    TextButton(onClick = onDelete) {
                        Text("從清單刪除這個 App", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = pkg.isNotEmpty() && rewardTerms.isNotEmpty(),
                onClick = {
                    onSave(
                        initial.copy(
                            name = CustomApps.clean(name).ifEmpty { pkg },
                            packageName = pkg,
                            rewardTerms = rewardTerms,
                            avoidTerms = CustomApps.splitTerms(avoid)
                        )
                    )
                }
            ) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}
