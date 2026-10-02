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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.rewardadguard.app.R
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.manager.RewardAppInfo

/**
 * Reward app list management.
 *
 * The list is explicit on purpose: the guard only ever watches apps the user
 * added here, it never guesses (spec section 12/31).
 */
@Composable
fun RewardAppsScreen(
    rewardApps: List<RewardAppInfo>,
    candidates: List<RewardAppInfo>,
    loading: Boolean,
    appQuery: String,
    /** Package currently in the foreground, or null when unknown. */
    foregroundPackage: String?,
    /**
     * Whether the accessibility service is actually bound. The foreground
     * package is pushed by that service, so this gates how much the warning
     * below may honestly claim.
     */
    serviceEnabled: Boolean,
    onSearch: (String) -> Unit,
    onAdd: (RewardAppInfo) -> Unit,
    onAddByPackage: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onMode: (String, ProtectionMode?) -> Unit,
    onRemove: (String) -> Unit,
    padding: PaddingValues
) {
    var manual by remember { mutableStateOf("") }

    // The single most confusing failure mode of a source-based guard: the user
    // believes they are protected while the app actually in front of them is not
    // on the list at all, so nothing is ever detected. Say so explicitly instead
    // of leaving an empty log to be misread as "no ads found".
    val monitoredSource = foregroundPackage?.takeIf { pkg ->
        rewardApps.any { it.packageName == pkg && it.enabled }
    }

    // `foregroundPackage` is a package id, and it comes from a snapshot the
    // service pushes — never resolve it in a composable, that would hit the
    // PackageManager on every recomposition. `AppManager.launcherApps()` has
    // already resolved the names, but that scan is debounced by the query, so
    // fall back to the raw id rather than trigger another one.
    val foregroundLabel = candidates.firstOrNull { it.packageName == foregroundPackage }?.label
        ?: rewardApps.firstOrNull { it.packageName == foregroundPackage }?.label
        ?: foregroundPackage.orEmpty()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        if (foregroundPackage != null && monitoredSource == null) {
            item {
                UnmonitoredForegroundWarning(
                    packageName = foregroundPackage,
                    label = foregroundLabel,
                    serviceEnabled = serviceEnabled,
                    onAdd = { onAddByPackage(foregroundPackage) }
                )
            }
        }
        item {
            SectionCard(
                title = stringResource(R.string.apps_monitored_title),
                subtitle = stringResource(R.string.apps_monitored_subtitle)
            ) {
                if (rewardApps.isEmpty()) {
                    Text(
                        text = stringResource(R.string.apps_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    rewardApps.forEach { app ->
                        ConfiguredAppRow(app, onToggle, onMode, onRemove)
                    }
                }
            }
        }
        item { AddByPackageCard(manual, { manual = it }, onAddByPackage) }
        item { InstalledAppsCard(appQuery, loading, onSearch) }
        items(candidates) { app -> CandidateRow(app, onAdd) }
    }
}

@Composable
private fun AddByPackageCard(
    manual: String,
    onManualChange: (String) -> Unit,
    onAddByPackage: (String) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.apps_add_by_package_title),
        subtitle = stringResource(R.string.apps_add_by_package_subtitle)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manual,
                onValueChange = onManualChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.apps_package_hint)) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onAddByPackage(manual.trim()) },
                enabled = manual.isNotBlank()
            ) { Text(stringResource(R.string.action_add)) }
        }
    }
}

@Composable
private fun UnmonitoredForegroundWarning(
    packageName: String,
    /** Friendly app name when the package manager could resolve one. */
    label: String,
    /** False while the accessibility service is off, i.e. the read is stale. */
    serviceEnabled: Boolean,
    onAdd: (String) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.apps_not_monitored_title),
        subtitle = stringResource(R.string.apps_not_monitored_subtitle)
    ) {
        Text(
            text = stringResource(R.string.apps_not_monitored_warning, label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        // The snapshot is whatever the service last pushed. With the service
        // stopped it can be arbitrarily old — typically this very app, which the
        // user just opened — so the warning would name the wrong package. Say so
        // instead of letting a stale package id look like a live observation.
        if (!serviceEnabled) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.apps_not_monitored_service_off),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = packageName,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Button(onClick = { onAdd(packageName) }) {
            Text(stringResource(R.string.action_add))
        }
    }
}

@Composable
private fun InstalledAppsCard(query: String, loading: Boolean, onSearch: (String) -> Unit) {
    SectionCard(
        title = stringResource(R.string.apps_installed_title),
        subtitle = stringResource(R.string.apps_installed_subtitle)
    ) {
        OutlinedTextField(
            // Bound to hoisted state, not to a literal. A hard-coded "" here
            // discards every keystroke, which is what made this field look
            // permanently disabled.
            value = query,
            onValueChange = onSearch,
            singleLine = true,
            label = { Text(stringResource(R.string.apps_search_label)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onSearch("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.action_clear)
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(
                if (loading) R.string.apps_loading else R.string.apps_search_hint
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CandidateRow(app: RewardAppInfo, onAdd: (RewardAppInfo) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = app.packageName,
                style = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        AssistChip(onClick = { onAdd(app) }, label = { Text(stringResource(R.string.action_add)) })
    }
}

@Composable
private fun ConfiguredAppRow(
    app: RewardAppInfo,
    onToggle: (String, Boolean) -> Unit,
    onMode: (String, ProtectionMode?) -> Unit,
    onRemove: (String) -> Unit
) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = app.enabled, onCheckedChange = { onToggle(app.packageName, it) })
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeChip(stringResource(R.string.apps_mode_inherit), app.protectionMode == null) {
                onMode(app.packageName, null)
            }
            ModeChip(stringResource(R.string.apps_mode_log_only), app.protectionMode == ProtectionMode.LOG_ONLY) {
                onMode(app.packageName, ProtectionMode.LOG_ONLY)
            }
            ModeChip(stringResource(R.string.apps_mode_block), app.protectionMode == ProtectionMode.BLOCK) {
                onMode(app.packageName, ProtectionMode.BLOCK)
            }
        }
        Spacer(Modifier.height(4.dp))
        AssistChip(
            onClick = { onRemove(app.packageName) },
            label = { Text(stringResource(R.string.action_remove)) }
        )
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
