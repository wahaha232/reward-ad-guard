package com.rewardadguard.app.ui

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rewardadguard.app.R
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.manager.MonitoringSnapshot
import com.rewardadguard.app.service.ServiceAccess
import kotlinx.coroutines.delay

/**
 * Dashboard: is the service alive, what is it doing right now, and what did it
 * do today. Deliberately read-only apart from the settings shortcut.
 */
@Composable
fun DashboardScreen(
    snapshot: MonitoringSnapshot,
    settings: AppSettings,
    onPause: (minutes: Int) -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenRewardApps: () -> Unit,
    onOpenLog: () -> Unit,
    padding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        ServiceCard(snapshot, serviceEnabled, onOpenAccessibilitySettings)
        PauseCard(settings, onPause, onStop, onResume)
        NowCard(snapshot)
        TodayCard(snapshot)
        ActionsCard(onOpenRewardApps, onOpenLog)
    }
}

/**
 * Pause / stop / resume without touching the accessibility setting
 * (2026-11-04). The service reacts through the settings flow.
 */
@Composable
private fun PauseCard(
    settings: AppSettings,
    onPause: (minutes: Int) -> Unit,
    onStop: () -> Unit,
    onResume: () -> Unit
) {
    // Re-read the clock every 30 s so a timed pause flips back to "running"
    // on screen without the user leaving the dashboard.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(settings.pausedUntil) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val paused = settings.isPaused(now)
    SectionCard(
        title = stringResource(R.string.pause_title),
        subtitle = stringResource(R.string.pause_subtitle)
    ) {
        val status = when {
            !paused -> stringResource(R.string.pause_running)
            settings.isStopped -> stringResource(R.string.pause_stopped)
            else -> stringResource(
                R.string.pause_paused_until,
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(settings.pausedUntil))
            )
        }
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = if (paused) Color(0xFFB26A00) else Color(0xFF1B7F3B)
        )
        Spacer(Modifier.height(8.dp))
        if (paused) {
            Button(onClick = onResume) { Text(stringResource(R.string.pause_resume)) }
        } else {
            // Equal widths and one line each: "停止" was squeezed into a vertical
            // column next to the two longer labels on a 1220 px screen.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    R.string.pause_15_minutes to { onPause(15) },
                    R.string.pause_1_hour to { onPause(60) },
                    R.string.pause_stop to onStop
                ).forEach { (label, action) ->
                    OutlinedButton(
                        onClick = action,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                    ) {
                        Text(stringResource(label), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Opens the system accessibility screen, guarded against missing activities. */
fun openAccessibilitySettings(context: Context) = ServiceAccess.openAccessibilitySettings(context)

/** Opens this app's notification settings (ISS-15). */
fun openNotificationSettings(context: Context) = ServiceAccess.openNotificationSettings(context)

@Composable
private fun ServiceCard(
    snapshot: MonitoringSnapshot,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit
) {
    val connected = snapshot.serviceConnected
    val context = LocalContext.current
    val statusColor = when {
        !connected -> Color(0xFFB00020)
        snapshot.monitoringActive -> Color(0xFF1B7F3B)
        else -> Color(0xFFB26A00)
    }
    val statusText = stringResource(
        when {
            !connected -> R.string.dashboard_status_not_connected
            snapshot.monitoringActive -> R.string.dashboard_status_monitoring
            else -> R.string.dashboard_status_idle
        }
    )

    SectionCard(
        title = stringResource(R.string.app_name),
        subtitle = stringResource(R.string.dashboard_subtitle)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(statusText, statusColor)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.dashboard_mode_value, snapshot.protectionMode.label()),
                style = MaterialTheme.typography.bodySmall
            )
        }
        Spacer(Modifier.height(8.dp))
        KeyValueRow(
            stringResource(R.string.dashboard_label_accessibility),
            stringResource(if (serviceEnabled) R.string.value_enabled else R.string.value_disabled)
        )
        // P0#1: say plainly whether the guard may click the ad X right now, so
        // "only detecting" can never be mistaken for "working".
        KeyValueRow(
            stringResource(R.string.dashboard_label_auto_assist),
            stringResource(
                when {
                    !snapshot.closeButtonAssistance -> R.string.dashboard_auto_assist_off
                    snapshot.assistAction == AssistAction.NONE ->
                        R.string.dashboard_auto_assist_detect_only
                    else -> R.string.dashboard_auto_assist_allowed
                }
            ),
            valueColor = if (snapshot.autoAssistAllowed) Color(0xFF1B7F3B) else null
        )
        KeyValueRow(
            stringResource(R.string.dashboard_label_reward_apps),
            snapshot.rewardAppCount.toString()
        )
        KeyValueRow(
            stringResource(R.string.dashboard_label_session),
            snapshot.sessionId ?: stringResource(R.string.value_none),
            monospace = true
        )
        KeyValueRow(stringResource(R.string.dashboard_label_state), snapshot.sessionState.name)
        KeyValueRow(
            stringResource(R.string.dashboard_label_source_app),
            Format.shortPackage(snapshot.sourcePackage)
        )
        KeyValueRow(
            stringResource(R.string.dashboard_label_foreground_app),
            Format.shortPackage(snapshot.currentForegroundPackage)
        )

        if (!connected) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dashboard_service_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        // ISS-15: the status notification is silently dropped on API 33+ until the
        // user grants POST_NOTIFICATIONS, so say so and offer the shortcut.
        if (snapshot.serviceConnected && !snapshot.notificationPermissionGranted) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.dashboard_notification_denied),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { openNotificationSettings(context) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_open_notification_settings))
            }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.action_open_accessibility_settings))
        }
    }
}

@Composable
private fun NowCard(snapshot: MonitoringSnapshot) {
    SectionCard(
        title = stringResource(R.string.dashboard_now_title),
        subtitle = stringResource(R.string.dashboard_now_subtitle)
    ) {
        KeyValueRow(
            stringResource(R.string.dashboard_label_ad_session),
            stringResource(if (snapshot.adSessionActive) R.string.value_yes else R.string.value_no),
            valueColor = if (snapshot.adSessionActive) Color(0xFF1B7F3B) else null
        )
        KeyValueRow(
            stringResource(R.string.dashboard_label_last_risk),
            stringResource(R.string.dashboard_risk_value, snapshot.redirectRisk)
        )
        KeyValueRow(
            stringResource(R.string.dashboard_label_last_event),
            snapshot.lastEventSummary ?: stringResource(R.string.value_none)
        )
        KeyValueRow(
            stringResource(R.string.dashboard_label_last_event_time),
            if (snapshot.lastEventAt > 0L) {
                Format.time(snapshot.lastEventAt)
            } else {
                stringResource(R.string.value_none)
            }
        )
        KeyValueRow(
            stringResource(R.string.dashboard_label_previous_foreground),
            Format.shortPackage(snapshot.previousForegroundPackage)
        )
        KeyValueRow(
            stringResource(R.string.dashboard_label_service_errors),
            snapshot.errors.toString()
        )
    }
}

@Composable
private fun TodayCard(snapshot: MonitoringSnapshot) {
    SectionCard(
        title = stringResource(R.string.dashboard_today_title),
        subtitle = stringResource(R.string.dashboard_today_subtitle)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.stat_redirects),
                snapshot.todayRedirects.toString(),
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stat_blocked),
                snapshot.todayBlocked.toString(),
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stat_errors),
                snapshot.errors.toString(),
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.stat_x_detected),
                snapshot.todayCloseDetected.toString(),
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stat_x_assisted),
                snapshot.todayCloseAssisted.toString(),
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stat_mode),
                snapshot.protectionMode.label(),
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ActionsCard(onOpenRewardApps: () -> Unit, onOpenLog: () -> Unit) {
    SectionCard(title = stringResource(R.string.dashboard_shortcuts_title)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenRewardApps, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dashboard_shortcut_reward_apps))
            }
            OutlinedButton(onClick = onOpenLog, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.dashboard_shortcut_log))
            }
        }
    }
}
