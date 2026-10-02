package com.rewardadguard.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.rewardadguard.app.R
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.data.RedirectPolicy

/** All tunables in one scrollable page, grouped by concern. */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onProtectionMode: (ProtectionMode) -> Unit,
    onExternalRedirect: (Boolean) -> Unit,
    onCloseAssist: (Boolean) -> Unit,
    onAssistAction: (AssistAction) -> Unit,
    onXArea: (Int) -> Unit,
    onSmartRedirect: (Boolean) -> Unit,
    onRedirectPolicy: (RedirectPolicy) -> Unit,
    onGrace: (Long) -> Unit,
    onNewAppSafeMode: (Boolean) -> Unit,
    onAutoReturn: (Boolean) -> Unit,
    onVerifyReturn: (Boolean) -> Unit,
    onMaxReturnAttempts: (Int) -> Unit,
    onAutoLaunch: (Boolean) -> Unit,
    onLogging: (Boolean) -> Unit,
    onMaxLogEvents: (Int) -> Unit,
    onStatusNotification: (Boolean) -> Unit,
    onTestMode: (Boolean) -> Unit,
    padding: PaddingValues
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
    ) {
        ProtectionCard(settings, onProtectionMode, onExternalRedirect, onNewAppSafeMode)
        RedirectCard(settings, onSmartRedirect, onRedirectPolicy, onGrace)
        CloseAssistCard(settings, onCloseAssist, onAssistAction, onXArea)
        ReturnCard(settings, onAutoReturn, onVerifyReturn, onMaxReturnAttempts, onAutoLaunch)
        LoggingCard(settings, onLogging, onMaxLogEvents, onStatusNotification, onTestMode)
    }
}

@Composable
private fun ProtectionCard(
    settings: AppSettings,
    onProtectionMode: (ProtectionMode) -> Unit,
    onExternalRedirect: (Boolean) -> Unit,
    onNewAppSafeMode: (Boolean) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.settings_protection_title),
        subtitle = stringResource(R.string.settings_protection_subtitle)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ProtectionMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.protectionMode == mode,
                    onClick = { onProtectionMode(mode) },
                    label = { Text(mode.label()) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        SwitchRow(
            title = stringResource(R.string.settings_external_redirect_title),
            description = stringResource(R.string.settings_external_redirect_desc),
            checked = settings.externalRedirectProtection,
            onCheckedChange = onExternalRedirect
        )
        SwitchRow(
            title = stringResource(R.string.settings_new_app_safe_title),
            description = stringResource(R.string.settings_new_app_safe_desc),
            checked = settings.newAppSafeModeEnabled,
            onCheckedChange = onNewAppSafeMode
        )
    }
}

@Composable
private fun RedirectCard(
    settings: AppSettings,
    onSmartRedirect: (Boolean) -> Unit,
    onRedirectPolicy: (RedirectPolicy) -> Unit,
    onGrace: (Long) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.settings_redirect_title),
        subtitle = stringResource(R.string.settings_redirect_subtitle)
    ) {
        SwitchRow(
            title = stringResource(R.string.settings_smart_redirect_title),
            description = stringResource(R.string.settings_smart_redirect_desc),
            checked = settings.smartRedirectEnabled,
            onCheckedChange = onSmartRedirect
        )
        Text(stringResource(R.string.settings_label_policy), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RedirectPolicy.entries.forEach { policy ->
                FilterChip(
                    selected = settings.redirectPolicy == policy,
                    onClick = { onRedirectPolicy(policy) },
                    label = { Text(policy.label()) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_grace_value, settings.blockGraceMillis),
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GRACE_STEPS.forEach { value ->
                FilterChip(
                    selected = settings.blockGraceMillis == value,
                    onClick = { onGrace(value) },
                    label = { Text("${value}ms") }
                )
            }
        }
    }
}

private val GRACE_STEPS = listOf(0L, 150L, 300L, 600L, 1_000L)

@Composable
private fun CloseAssistCard(
    settings: AppSettings,
    onCloseAssist: (Boolean) -> Unit,
    onAssistAction: (AssistAction) -> Unit,
    onXArea: (Int) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.settings_close_assist_title),
        subtitle = stringResource(R.string.settings_close_assist_subtitle)
    ) {
        SwitchRow(
            title = stringResource(R.string.settings_close_button_title),
            description = stringResource(R.string.settings_close_button_desc),
            checked = settings.closeButtonAssistance,
            onCheckedChange = onCloseAssist
        )
        Text(stringResource(R.string.settings_label_action), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistAction.entries.forEach { action ->
                FilterChip(
                    selected = settings.assistAction == action,
                    onClick = { onAssistAction(action) },
                    label = { Text(action.label()) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.settings_label_click_area),
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AREA_STEPS.forEach { value ->
                FilterChip(
                    selected = settings.xClickAreaMultiplier == value,
                    onClick = { onXArea(value) },
                    label = { Text("x$value") }
                )
            }
        }
    }
}

private val AREA_STEPS = listOf(1, 2, 3, 4)

@Composable
private fun ReturnCard(
    settings: AppSettings,
    onAutoReturn: (Boolean) -> Unit,
    onVerifyReturn: (Boolean) -> Unit,
    onMaxReturnAttempts: (Int) -> Unit,
    onAutoLaunch: (Boolean) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.settings_return_title),
        subtitle = stringResource(R.string.settings_return_subtitle)
    ) {
        SwitchRow(
            title = stringResource(R.string.settings_auto_return_title),
            description = stringResource(R.string.settings_auto_return_desc),
            checked = settings.autoReturnEnabled,
            onCheckedChange = onAutoReturn
        )
        SwitchRow(
            title = stringResource(R.string.settings_verify_return_title),
            description = stringResource(R.string.settings_verify_return_desc),
            checked = settings.verifyReturn,
            onCheckedChange = onVerifyReturn
        )
        SwitchRow(
            title = stringResource(R.string.settings_auto_launch_title),
            description = stringResource(R.string.settings_auto_launch_desc),
            checked = settings.autoLaunchRewardApp,
            onCheckedChange = onAutoLaunch
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_label_max_attempts),
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ATTEMPT_STEPS.forEach { value ->
                FilterChip(
                    selected = settings.maxReturnAttempts == value,
                    onClick = { onMaxReturnAttempts(value) },
                    label = { Text(value.toString()) }
                )
            }
        }
    }
}

private val ATTEMPT_STEPS = listOf(1, 2, 3, 5)

@Composable
private fun LoggingCard(
    settings: AppSettings,
    onLogging: (Boolean) -> Unit,
    onMaxLogEvents: (Int) -> Unit,
    onStatusNotification: (Boolean) -> Unit,
    onTestMode: (Boolean) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.settings_logging_title),
        subtitle = stringResource(R.string.settings_logging_subtitle)
    ) {
        SwitchRow(
            title = stringResource(R.string.settings_event_logging_title),
            description = stringResource(R.string.settings_event_logging_desc),
            checked = settings.loggingEnabled,
            onCheckedChange = onLogging
        )
        SwitchRow(
            title = stringResource(R.string.settings_status_notification_title),
            description = stringResource(R.string.settings_status_notification_desc),
            checked = settings.statusNotificationEnabled,
            onCheckedChange = onStatusNotification
        )
        SwitchRow(
            title = stringResource(R.string.settings_test_mode_title),
            description = stringResource(R.string.settings_test_mode_desc),
            checked = settings.testModeEnabled,
            onCheckedChange = onTestMode
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.settings_label_max_events),
            style = MaterialTheme.typography.bodySmall
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LOG_LIMIT_STEPS.forEach { value ->
                FilterChip(
                    selected = settings.maxLogEvents == value,
                    onClick = { onMaxLogEvents(value) },
                    label = { Text("${value / 1000}k") }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_trim_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val LOG_LIMIT_STEPS = listOf(10_000, 20_000, 50_000, 100_000)
