package com.rewardadguard.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rewardadguard.app.R
import com.rewardadguard.app.manager.LogExporter
import com.rewardadguard.app.service.ServiceAccess

private const val TAB_DASHBOARD = 0
private const val TAB_APPS = 1
private const val TAB_LOG = 2
private const val TAB_SETTINGS = 3

/** Tab labels are resource ids, resolved inside the composition. */
private val TAB_LABELS = listOf(
    R.string.tab_dashboard,
    R.string.tab_apps,
    R.string.tab_log,
    R.string.tab_settings
)

/**
 * Single-activity host.
 *
 * There is no navigation library: four tabs and one dialog are small enough to
 * keep in one place, which also avoids a NavHost that would have to survive
 * every configuration change of an always-on utility.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RewardAdGuardTheme {
                // `viewModel.savedState` is the handle the ViewModel was built
                // with, so there is exactly one place that owns the key and no
                // way for the reader and the writer to disagree.
                RewardAdGuardAppScreen(viewModel = viewModel, onOpenExport = ::shareExport)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The accessibility state can only change while we are not in front.
        viewModel.refreshAll()
    }

    /** Hands the exported file to the system share sheet. */
    private fun shareExport(result: LogExporter.ExportResult) {
        val sender = Intent(Intent.ACTION_SEND).apply {
            type = result.shareIntent.type ?: LogExporter.Format.TXT.mimeType
            putExtra(Intent.EXTRA_STREAM, result.uri)
            putExtra(Intent.EXTRA_SUBJECT, result.file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(sender, getString(R.string.log_share_chooser_title)))
    }
}

/** Root composable: tabs, the export dialog and one-shot effects. */
@Composable
fun RewardAdGuardAppScreen(
    viewModel: MainViewModel,
    onOpenExport: (LogExporter.ExportResult) -> Unit
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val snapshot by viewModel.snapshot.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val rewardApps by viewModel.rewardApps.collectAsState()
    val events by viewModel.events.collectAsState()
    val sessions by viewModel.sessions.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()
    val appStats by viewModel.appStats.collectAsState()
    val candidates by viewModel.candidates.collectAsState()
    val loading by viewModel.loading.collectAsState()
    val filter by viewModel.logFilter.collectAsState()
    val search by viewModel.search.collectAsState()
    val appQuery by viewModel.appQuery.collectAsState()
    val exportReport by viewModel.exportReport.collectAsState()
    val notice by viewModel.notice.collectAsState()

    // Reading the enabled-accessibility list is a ContentResolver call, so it is
    // done once per composition rather than once per screen that needs it. It is
    // recomputed on resume (MainActivity.onResume) and on every recomposition
    // triggered by a state change, which is the granularity the UI already has.
    val serviceEnabled = ServiceAccess.isServiceEnabled(context)

    // Same reasoning as the search term: `rememberSaveable` keeps the selected
    // tab across rotation and process death instead of jumping back to the
    // dashboard while the user was reading the log.
    var tab by rememberSaveable { mutableStateOf(TAB_DASHBOARD) }
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshAll() }

    // The search term lives in the ViewModel, which outlives recomposition but
    // not the process. Saving it on stop and restoring it here keeps the text
    // box and the filtered list consistent across rotation and process death.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.saveAppQueryTo(viewModel.savedState)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) { viewModel.restoreAppQueryFrom(viewModel.savedState) }

    LaunchedEffect(exportReport) {
        val report = exportReport ?: return@LaunchedEffect
        snackbar.showSnackbar(report.message)
        viewModel.clearExportReport()
    }

    LaunchedEffect(notice) {
        val message = notice ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        viewModel.clearNotice()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                TAB_LABELS.forEachIndexed { index, labelRes ->
                    NavigationBarItem(
                        selected = tab == index,
                        onClick = {
                            tab = index
                            if (index == TAB_LOG) viewModel.refreshLogs()
                            if (index == TAB_APPS) viewModel.reloadCandidates()
                        },
                        icon = {},
                        label = { Text(stringResource(labelRes)) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            TAB_APPS -> RewardAppsScreen(
                rewardApps = rewardApps,
                candidates = candidates,
                loading = loading,
                appQuery = appQuery,
                foregroundPackage = snapshot.currentForegroundPackage,
                // The snapshot can only be as fresh as the service that pushes
                // it, so the warning is gated on the live binding state.
                serviceEnabled = serviceEnabled,
                onSearch = { viewModel.setAppQuery(it) },
                onAdd = { viewModel.addRewardApp(it) },
                onAddByPackage = { viewModel.addRewardAppByPackage(it) },
                onToggle = { pkg, enabled -> viewModel.setRewardAppEnabled(pkg, enabled) },
                onMode = { pkg, mode -> viewModel.setRewardAppMode(pkg, mode) },
                onRemove = { viewModel.removeRewardApp(it) },
                padding = padding
            )

            TAB_LOG -> LogScreen(
                events = events,
                sessions = sessions,
                appStats = appStats,
                daily = dailyStats,
                filter = filter,
                search = search,
                onFilter = { viewModel.setLogFilter(it) },
                onSearch = { viewModel.setSearch(it) },
                onExport = { showExportDialog = true },
                onClear = { viewModel.clearLog() },
                padding = padding
            )

            TAB_SETTINGS -> SettingsScreen(
                settings = settings,
                onProtectionMode = { viewModel.setProtectionMode(it) },
                onExternalRedirect = { viewModel.setExternalRedirectProtection(it) },
                onCloseAssist = { viewModel.setCloseButtonAssistance(it) },
                onAssistAction = { viewModel.setAssistAction(it) },
                onXArea = { viewModel.setXClickAreaMultiplier(it) },
                onSmartRedirect = { viewModel.setSmartRedirect(it) },
                onRedirectPolicy = { viewModel.setRedirectPolicy(it) },
                onGrace = { viewModel.setBlockGraceMillis(it) },
                onNewAppSafeMode = { viewModel.setNewAppSafeMode(it) },
                onAutoReturn = { viewModel.setAutoReturn(it) },
                onVerifyReturn = { viewModel.setVerifyReturn(it) },
                onMaxReturnAttempts = { viewModel.setMaxReturnAttempts(it) },
                onAutoLaunch = { viewModel.setAutoLaunchRewardApp(it) },
                onLogging = { viewModel.setLoggingEnabled(it) },
                onMaxLogEvents = { viewModel.setMaxLogEvents(it) },
                onStatusNotification = { viewModel.setStatusNotification(it) },
                onTestMode = { viewModel.setTestMode(it) },
                padding = padding
            )

            else -> DashboardScreen(
                snapshot = snapshot,
                serviceEnabled = serviceEnabled,
                onOpenAccessibilitySettings = { ServiceAccess.openAccessibilitySettings(context) },
                onOpenRewardApps = { tab = TAB_APPS },
                onOpenLog = { tab = TAB_LOG },
                padding = padding
            )
        }
    }

    if (showExportDialog) {
        ExportDialog(
            onDismiss = { showExportDialog = false },
            onPick = { format ->
                showExportDialog = false
                viewModel.exportLog(format) { export -> onOpenExport(export) }
            }
        )
    }
}

/** Format picker for the log export. */
@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onPick: (LogExporter.Format) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.log_export_dialog_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.log_export_dialog_text),
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                LogExporter.Format.entries.forEach { format ->
                    Button(
                        onClick = { onPick(format) },
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Text(format.label())
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
