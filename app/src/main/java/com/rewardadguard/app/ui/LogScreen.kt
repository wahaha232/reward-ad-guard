package com.rewardadguard.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rewardadguard.app.R
import com.rewardadguard.app.data.DailyStats
import com.rewardadguard.app.data.EventRecord
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.data.SessionRecord

/** Log + statistics screen: filter, search, export, clear. */
@Composable
fun LogScreen(
    events: List<EventRecord>,
    sessions: List<SessionRecord>,
    appStats: List<AppStatsRow>,
    daily: DailyStats,
    filter: LogFilter,
    search: String,
    onFilter: (LogFilter) -> Unit,
    onSearch: (String) -> Unit,
    onExport: () -> Unit,
    onClear: () -> Unit,
    padding: PaddingValues
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item { LogStatsCard(daily) }
        item { PerAppStatsCard(appStats) }
        item {
            SectionCard(
                title = stringResource(R.string.log_actions_title),
                subtitle = stringResource(R.string.log_actions_subtitle)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onExport, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_export))
                    }
                    OutlinedButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_clear_log))
                    }
                }
            }
        }
        item { LogFilterCard(filter, search, onFilter, onSearch) }
        item {
            Text(
                text = stringResource(R.string.log_count, events.size, sessions.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        items(events) { event -> EventRow(event) }
    }
}

@Composable
private fun LogStatsCard(daily: DailyStats) {
    SectionCard(
        title = stringResource(R.string.log_stats_title),
        subtitle = stringResource(R.string.log_stats_subtitle)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.stat_sessions),
                daily.sessions.toString(),
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stat_redirects),
                daily.externalRedirects.toString(),
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stat_blocked),
                daily.blocked.toString(),
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.stat_x_detected),
                daily.xProtection.toString(),
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stat_x_assisted),
                daily.xAssisted.toString(),
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stat_returns_ok),
                daily.returnSuccess.toString(),
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(
                stringResource(R.string.stat_returns_failed),
                daily.returnFailed.toString(),
                Modifier.weight(1f)
            )
            StatTile(
                stringResource(R.string.stat_errors),
                daily.errors.toString(),
                Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PerAppStatsCard(appStats: List<AppStatsRow>) {
    SectionCard(
        title = stringResource(R.string.log_per_app_title),
        subtitle = stringResource(R.string.log_per_app_subtitle)
    ) {
        if (appStats.isEmpty()) {
            Text(
                text = stringResource(R.string.log_per_app_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            appStats.forEach { row ->
                Column(Modifier.padding(vertical = 6.dp)) {
                    Text(row.label, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = row.packageName,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    KeyValueRow(
                        stringResource(R.string.log_per_app_sessions),
                        "${row.sessions} / ${row.redirects}"
                    )
                    KeyValueRow(
                        stringResource(R.string.log_per_app_blocked),
                        "${row.blocked} / ${row.xAssisted}"
                    )
                    KeyValueRow(
                        stringResource(R.string.log_per_app_returns),
                        "${row.returnSuccess} / ${row.returnFailed}"
                    )
                }
            }
        }
    }
}
@Composable
private fun LogFilterCard(
    filter: LogFilter,
    search: String,
    onFilter: (LogFilter) -> Unit,
    onSearch: (String) -> Unit
) {
    SectionCard(title = stringResource(R.string.log_filter_title)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            LogFilter.entries.forEach { entry ->
                FilterChip(
                    selected = entry == filter,
                    onClick = { onFilter(entry) },
                    label = { Text(entry.label()) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            singleLine = true,
            label = { Text(stringResource(R.string.log_filter_by_package)) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EventRow(event: EventRecord) {
    val color = EventType.entries
        .firstOrNull { it.name == event.eventType }
        .severityColor()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
    ) {
        Column(
            modifier = Modifier
                .background(color.copy(alpha = 0.14f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = event.eventType,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
        Spacer(Modifier.height(0.dp))
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                text = "${Format.time(event.timestamp)}  ${event.destinationPackage ?: event.sourcePackage ?: "-"}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Text(
                text = listOfNotNull(
                    event.action?.takeIf { it.isNotBlank() },
                    event.result?.takeIf { it.isNotBlank() },
                    event.message?.takeIf { it.isNotBlank() },
                    event.error?.takeIf { it.isNotBlank() }
                ).joinToString(" 繚 ").ifEmpty { "-" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.log_session_prefix, Format.shortSession(event.sessionId)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Palette used for the event type badge. */
fun com.rewardadguard.app.data.EventType?.severityColor(): Color = when (this) {
    null -> Color(0xFF6B6B6B)
    com.rewardadguard.app.data.EventType.ERROR -> Color(0xFFB00020)
    com.rewardadguard.app.data.EventType.BLOCK,
    com.rewardadguard.app.data.EventType.REDIRECT_DETECTED,
    com.rewardadguard.app.data.EventType.REDIRECT_RISK -> Color(0xFFB26A00)
    com.rewardadguard.app.data.EventType.CLOSE_DETECT,
    com.rewardadguard.app.data.EventType.CLOSE_ACTION,
    com.rewardadguard.app.data.EventType.CLOSE_RESULT -> Color(0xFF1B7F3B)
    com.rewardadguard.app.data.EventType.RETURN,
    com.rewardadguard.app.data.EventType.RETURNED_TO_SOURCE -> Color(0xFF0057B8)
    com.rewardadguard.app.data.EventType.RETURN_FAILED -> Color(0xFF8B0000)
    else -> Color(0xFF44546A)
}
