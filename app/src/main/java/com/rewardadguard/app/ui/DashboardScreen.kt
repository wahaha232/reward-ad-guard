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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rewardadguard.app.R
import com.rewardadguard.app.manager.MonitoringSnapshot
import com.rewardadguard.app.service.ServiceAccess

/**
 * Dashboard: is the service alive, what is it doing right now, and what did it
 * do today. Deliberately read-only apart from the settings shortcut.
 */
@Composable
fun DashboardScreen(
    snapshot: MonitoringSnapshot,
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
        NowCard(snapshot)
        TodayCard(snapshot)
        ActionsCard(onOpenRewardApps, onOpenLog)
    }
}

/** Opens the system accessibility screen, guarded against missing activities. */
fun openAccessibilitySettings(context: Context) = ServiceAccess.openAccessibilitySettings(context)

@Composable
private fun ServiceCard(
    snapshot: MonitoringSnapshot,
    serviceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit
) {
    val connected = snapshot.serviceConnected
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
