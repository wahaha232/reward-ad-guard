package com.rewardadguard.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rewardadguard.app.R
import com.rewardadguard.app.collector.AppProfile
import com.rewardadguard.app.collector.CollectorController
import com.rewardadguard.app.collector.CollectorLimits
import com.rewardadguard.app.collector.CollectorProfiles
import kotlinx.coroutines.delay

/**
 * Phase 5's "自動收取" tab.
 *
 * The screen only edits options and calls [CollectorController]; the actual run
 * lives in the controller so rotating or leaving the app cannot kill a
 * collection that is already driving the phone.
 */
@Composable
fun CollectorScreen(
    state: CollectorController.State,
    serviceEnabled: Boolean,
    onStart: (List<AppProfile>, Int, Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onExport: () -> Unit,
    padding: PaddingValues
) {
    var selected by rememberSaveable { mutableStateOf(CollectorProfiles.all.map { it.key }.toSet()) }
    var unlimited by rememberSaveable { mutableStateOf(true) }
    var limitText by rememberSaveable { mutableStateOf("30") }
    var dryRun by rememberSaveable { mutableStateOf(true) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    // Ticks only while a run is active so 經過時間 means elapsed collection time,
    // not time since the screen was opened.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.running, state.startedAt) {
        while (state.running) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val elapsedMillis = if (state.startedAt > 0L) {
        (now - state.startedAt).coerceAtLeast(0L)
    } else {
        0L
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp)
    ) {
        Text(stringResource(R.string.collector_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.collector_subtitle),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(16.dp))

        if (!serviceEnabled) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.collector_service_missing),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        Text(stringResource(R.string.collector_apps), style = MaterialTheme.typography.titleSmall)
        val allKeys = CollectorProfiles.all.map { it.key }.toSet()
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = selected.containsAll(allKeys),
                onCheckedChange = { checked ->
                    selected = if (checked) allKeys else emptySet()
                }
            )
            Text(stringResource(R.string.collector_all_apps))
        }
        for (profile in CollectorProfiles.all) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = profile.key in selected,
                    onCheckedChange = { checked ->
                        selected = if (checked) selected + profile.key else selected - profile.key
                    }
                )
                Text(profile.name)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${profile.rewardTerms.firstOrNull() ?: ""}…",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.collector_unlimited), modifier = Modifier.weight(1f))
            Switch(checked = unlimited, onCheckedChange = { unlimited = it })
        }
        if (!unlimited) {
            OutlinedTextField(
                value = limitText,
                onValueChange = { limitText = it.filter(Char::isDigit).take(4) },
                label = { Text(stringResource(R.string.collector_limit)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.collector_dry_run), modifier = Modifier.weight(1f))
            Switch(checked = dryRun, onCheckedChange = { dryRun = it })
        }

        Spacer(Modifier.height(12.dp))
        if (!state.running) {
            Button(
                onClick = {
                    val chosen = CollectorProfiles.all.filter { it.key in selected }
                    if (chosen.isEmpty()) {
                        error = "no_apps"
                        return@Button
                    }
                    error = null
                    val limit = if (unlimited) {
                        CollectorLimits.UNLIMITED
                    } else {
                        limitText.toIntOrNull()?.takeIf { it > 0 } ?: 30
                    }
                    onStart(chosen, limit, dryRun)
                },
                enabled = serviceEnabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.collector_start)) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.paused) {
                    Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.collector_resume))
                    }
                } else {
                    OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.collector_pause))
                    }
                }
                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.collector_stop))
                }
            }
        }

        if (error == "no_apps") {
            Text(
                stringResource(R.string.collector_no_apps),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${stringResource(R.string.collector_status)}：" +
                        when {
                            state.paused -> stringResource(R.string.collector_paused)
                            state.running -> stringResource(R.string.collector_running)
                            else -> stringResource(R.string.collector_idle)
                        },
                    style = MaterialTheme.typography.titleSmall
                )
                if (state.appName != null) Text(state.appName!!)
                Text(state.message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "${stringResource(R.string.collector_reward_count)}：${state.rewards}" +
                        "　廣告：${state.ads}　點擊：${state.taps}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "${stringResource(R.string.collector_elapsed)}：${formatElapsed(elapsedMillis)}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (state.dryRun) {
                    Text("乾跑模式：不會點擊畫面", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onExport,
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.collector_export_latest)) }

        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.collector_recent), style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(state.recent) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

private fun formatElapsed(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}
