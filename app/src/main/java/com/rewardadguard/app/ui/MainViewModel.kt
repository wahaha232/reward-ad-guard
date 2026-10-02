package com.rewardadguard.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AppStatsRecord
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.DailyStats
import com.rewardadguard.app.data.EventRecord
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.data.RedirectPolicy
import com.rewardadguard.app.data.SessionRecord
import com.rewardadguard.app.manager.LogExporter
import com.rewardadguard.app.manager.MonitoringSnapshot
import com.rewardadguard.app.manager.MonitoringState
import com.rewardadguard.app.manager.RewardAppInfo
import com.rewardadguard.app.manager.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Log category filter as shown in the log screen. */
enum class LogFilter(val label: String, val eventTypePrefix: String?) {
    ALL("All", null),
    REDIRECTS("Redirects", "REDIRECT"),
    BLOCKS("Blocks", "BLOCK"),
    CLOSE("Close", "CLOSE"),
    RETURNS("Returns", "RETURN"),
    SESSIONS("Sessions", "SESSION"),
    ERRORS("Errors", "ERROR")
}

/** One row in the per-app statistics table. */
data class AppStatsRow(
    val packageName: String,
    val label: String,
    val sessions: Int,
    val redirects: Int,
    val blocked: Int,
    val xDetected: Int,
    val xAssisted: Int,
    val returnSuccess: Int,
    val returnFailed: Int,
    val errors: Int
)

/** Result of an export action, consumed by the UI to show a message. */
data class ExportReport(val success: Boolean, val message: String, val fileName: String? = null)

/**
 * Single ViewModel for the whole (small) UI.
 *
 * Everything it exposes is either an in-memory [MonitoringState] snapshot or a
 * Room query performed on [Dispatchers.IO]; the UI never queries the database
 * itself and never polls.
 *
 * The class carries **two** constructors on purpose:
 *
 *  * `(Application, SavedStateHandle)` is the one `ViewModelProvider` picks when
 *    the host has a `SavedStateRegistry` — the standard ViewModel factory
 *    reflects over `Constructor.getParameterTypes()` and therefore compares
 *    against the *full* signature, ignoring any Kotlin default value. It is what
 *    makes the installed-app search term survive process death.
 *  * `(Application)` is only reachable from tests and from previews. `savedState`
 *    is then a detached handle whose contents live no longer than the instance,
 *    which is exactly the old behaviour — so nothing here can crash.
 */
class MainViewModel(
    app: Application,
    /** Backing store that survives process death. */
    val savedState: SavedStateHandle
) : AndroidViewModel(app) {

    /** Convenience target so `MainViewModel(application)` also compiles. */
    constructor(app: Application) : this(app, SavedStateHandle())

    private val rewardAdGuardApp = app as RewardAdGuardApp
    private val container = RewardAdGuardApp.Container
    private val logger = container.logger

    val snapshot: StateFlow<MonitoringSnapshot> = MonitoringState.snapshot
    val settings: StateFlow<AppSettings> = MonitoringState.settings
    val rewardApps: StateFlow<List<RewardAppInfo>> = MonitoringState.rewardApps
    val pendingNewApp = MonitoringState.pendingNewApp

    private val _events = MutableStateFlow<List<EventRecord>>(emptyList())
    val events: StateFlow<List<EventRecord>> = _events.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionRecord>>(emptyList())
    val sessions: StateFlow<List<SessionRecord>> = _sessions.asStateFlow()

    private val _dailyStats = MutableStateFlow(DailyStats())
    val dailyStats: StateFlow<DailyStats> = _dailyStats.asStateFlow()

    private val _appStats = MutableStateFlow<List<AppStatsRow>>(emptyList())
    val appStats: StateFlow<List<AppStatsRow>> = _appStats.asStateFlow()

    private val _candidates = MutableStateFlow<List<RewardAppInfo>>(emptyList())
    val candidates: StateFlow<List<RewardAppInfo>> = _candidates.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Search term for the "installed apps" picker.
     *
     * This must be Compose state that outlives recomposition. Previously the
     * text field was bound to a hard-coded `""`, so every keystroke was reported
     * and then immediately discarded: the box stayed empty no matter what was
     * typed.
     */
    private val _appQuery = MutableStateFlow("")
    val appQuery: StateFlow<String> = _appQuery.asStateFlow()

    private var candidateJob: Job? = null

    private val _logFilter = MutableStateFlow(LogFilter.ALL)
    val logFilter: StateFlow<LogFilter> = _logFilter.asStateFlow()

    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    private val _exportReport = MutableStateFlow<ExportReport?>(null)
    val exportReport: StateFlow<ExportReport?> = _exportReport.asStateFlow()

    /** Re-reads everything. Called when a screen becomes visible or on refresh. */
    fun refreshAll() {
        viewModelScope.launch {
            _loading.value = true
            refreshStats()
            refreshLogs()
            _loading.value = false
        }
    }

    fun refreshLogs() {
        viewModelScope.launch { refreshLogsBlocking() }
    }

    private suspend fun refreshLogsBlocking() {
        val filter = _logFilter.value
        val query = _search.value
        val rows = logger.searchEvents(
            sessionId = null,
            packageName = query.takeIf { it.isNotBlank() },
            eventType = filter.eventTypePrefix,
            from = null,
            to = null,
            limit = LOG_LIMIT
        )
        val sessions = logger.recentSessions(SESSION_LIMIT)
        withContext(Dispatchers.Main) {
            _events.value = rows
            _sessions.value = sessions
        }
    }

    private suspend fun refreshStats() {
        val daily = logger.statsToday()
        val perApp = logger.statsPerApp()
        val labels = MonitoringState.rewardApps.value
            .associate { it.packageName to it.label }
        val rows = perApp.map { record -> record.toRow(labels[record.packageName]) }
        withContext(Dispatchers.Main) {
            _dailyStats.value = daily
            _appStats.value = rows
        }
    }

    fun setLogFilter(filter: LogFilter) {
        _logFilter.value = filter
        refreshLogs()
    }

    fun setSearch(query: String) {
        _search.value = query
    }

    fun clearExportReport() {
        _exportReport.value = null
    }

    // -------------------------------------------------------------- reward apps

    /**
     * Loads installed launcher apps for the "add reward app" picker.
     *
     * The query is stored so the caller's text field can be driven from state,
     * and loading is debounced because [AppManager.launcherApps] walks the whole
     * `PackageManager` — filtering on every keystroke would stutter on a device
     * with a few hundred packages.
     */
    fun setAppQuery(query: String) {
        _appQuery.value = query
        candidateJob?.cancel()
        candidateJob = viewModelScope.launch {
            delay(CANDIDATE_DEBOUNCE_MS)
            loadCandidates(query)
        }
    }

    /** Forces an immediate reload, e.g. when the apps tab becomes visible. */
    fun reloadCandidates() {
        candidateJob?.cancel()
        candidateJob = viewModelScope.launch { loadCandidates(_appQuery.value) }
    }

    /**
     * Mirrors the current search term into the saved state so it survives the
     * Activity being recreated (rotation, theme switch) and process death.
     *
     * The text field is driven by [appQuery], so without this the box would
     * silently snap back to "all installed apps" after a configuration change
     * while the list underneath kept showing the filtered result.
     */
    fun saveAppQueryTo(handle: SavedStateHandle) {
        handle[KEY_APP_QUERY] = _appQuery.value
    }

    /** Restores the term saved by [saveAppQueryTo], if any. */
    fun restoreAppQueryFrom(handle: SavedStateHandle) {
        val restored = handle.get<String>(KEY_APP_QUERY) ?: return
        if (restored == _appQuery.value) return
        _appQuery.value = restored
        candidateJob?.cancel()
        candidateJob = viewModelScope.launch { loadCandidates(restored) }
    }

    private suspend fun loadCandidates(query: String?) {
        _loading.value = true
        try {
            val result = container.rewardAppsRepository.candidates(query)
            _candidates.value = result
        } finally {
            _loading.value = false
        }
    }

    fun addRewardApp(info: RewardAppInfo) {
        addRewardAppByPackage(info.packageName, info.label)
    }

    fun addRewardAppByPackage(packageName: String, label: String? = null) {
        if (packageName.isBlank()) return
        container.rewardAppsRepository.add(
            packageName = packageName.trim(),
            label = label,
            enabled = true,
            mode = safeDefaultMode()
        )
        refreshAll()
    }

    fun setRewardAppEnabled(packageName: String, enabled: Boolean) {
        container.rewardAppsRepository.setEnabled(packageName, enabled)
    }

    fun setRewardAppMode(packageName: String, mode: ProtectionMode?) {
        container.rewardAppsRepository.setMode(packageName, mode)
    }

    fun removeRewardApp(packageName: String) {
        container.rewardAppsRepository.remove(packageName)
        refreshAll()
    }

    /**
     * A newly added app is never promoted to BLOCK automatically (spec 37/38):
     * New App Safe Mode forces LOG_ONLY, and when it is off the global mode is
     * inherited instead of silently escalating.
     */
    private fun safeDefaultMode(): ProtectionMode {
        val current = settings.value
        return if (current.newAppSafeModeEnabled) {
            ProtectionMode.LOG_ONLY
        } else {
            current.protectionMode
        }
    }

    fun confirmPendingNewApp(mode: ProtectionMode) {
        container.rewardAppsRepository.confirmPendingNewApp(mode)
        refreshAll()
    }

    fun dismissPendingNewApp() {
        container.rewardAppsRepository.dismissPendingNewApp()
    }

    // ------------------------------------------------------------------ settings

    fun setProtectionMode(mode: ProtectionMode) = persist { it.setProtectionMode(mode) }

    fun setExternalRedirectProtection(enabled: Boolean) =
        persist { it.setExternalRedirectProtection(enabled) }

    fun setCloseButtonAssistance(enabled: Boolean) =
        persist { it.setCloseButtonAssistance(enabled) }

    fun setAssistAction(action: AssistAction) = persist { it.setAssistAction(action) }

    fun setXClickAreaMultiplier(value: Int) = persist { it.setXClickAreaMultiplier(value) }

    fun setSmartRedirect(enabled: Boolean) = persist { it.setSmartRedirect(enabled) }

    fun setRedirectPolicy(policy: RedirectPolicy) = persist { it.setRedirectPolicy(policy) }

    fun setBlockGraceMillis(value: Long) = persist { it.setBlockGraceMillis(value) }

    fun setNewAppSafeMode(enabled: Boolean) = persist { it.setNewAppSafeMode(enabled) }

    fun setLoggingEnabled(enabled: Boolean) = persist { it.setLoggingEnabled(enabled) }

    fun setMaxLogEvents(value: Int) = persist { it.setMaxLogEvents(value) }

    fun setStatusNotification(enabled: Boolean) = persist { it.setStatusNotification(enabled) }

    fun setTestMode(enabled: Boolean) = persist { it.setTestMode(enabled) }

    fun setAutoReturn(enabled: Boolean) = persist { it.setAutoReturn(enabled) }

    fun setAutoLaunchRewardApp(enabled: Boolean) = persist { it.setAutoLaunchRewardApp(enabled) }

    fun setVerifyReturn(enabled: Boolean) = persist { it.setVerifyReturn(enabled) }

    fun setMaxReturnAttempts(value: Int) = persist { it.setMaxReturnAttempts(value) }

    private fun persist(block: suspend (SettingsRepository) -> Unit) {
        viewModelScope.launch {
            runCatching { block(container.settingsRepository) }
        }
    }

    // ------------------------------------------------------------ export / clear

    /**
     * Writes the log to `cacheDir/exports` and returns a share intent.
     *
     * The file write itself happens on [Dispatchers.IO]; only the ready-made
     * intent is handed back to the UI thread.
     */
    fun exportLog(format: LogExporter.Format, onReady: (LogExporter.ExportResult) -> Unit) {
        viewModelScope.launch {
            _loading.value = true
            val result: Result<LogExporter.ExportResult> = runCatching {
                withContext(Dispatchers.IO) {
                    container.logExporter.export(
                        events = logger.recentEvents(LOG_LIMIT),
                        sessions = logger.recentSessions(SESSION_LIMIT),
                        format = format
                    )
                }
            }
            result.fold(
                onSuccess = { export ->
                    logger.log(
                        eventType = EventType.LOG_EXPORTED,
                        sessionId = null,
                        action = "EXPORT_${format.name}",
                        result = "SUCCESS",
                        message = export.file.name
                    )
                    _exportReport.value =
                        ExportReport(true, "Log exported: ${export.file.name}", export.file.name)
                    onReady(export)
                },
                onFailure = { error ->
                    logger.log(
                        eventType = EventType.ERROR,
                        sessionId = null,
                        action = "EXPORT_${format.name}",
                        result = "FAILED",
                        error = error.message
                    )
                    _exportReport.value =
                        ExportReport(false, error.message ?: "Export failed")
                }
            )
            _loading.value = false
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            logger.clearAll()
            refreshAll()
        }
    }

    private fun AppStatsRecord.toRow(label: String?): AppStatsRow = AppStatsRow(
        packageName = packageName,
        label = label ?: this.label,
        sessions = sessions,
        redirects = redirects,
        blocked = blocked,
        xDetected = xDetected,
        xAssisted = xAssisted,
        returnSuccess = returnSuccess,
        returnFailed = returnFailed,
        errors = errors
    )
    companion object {
        /** Newest N events shown in the log screen. */
        private const val LOG_LIMIT = 500

        /** Sessions listed next to the log. */
        private const val SESSION_LIMIT = 100

        /**
         * Keystroke settle time before re-filtering the installed-app list.
         * Long enough to avoid a `PackageManager` scan per character, short
         * enough that the list still feels live.
         */
        private const val CANDIDATE_DEBOUNCE_MS = 200L

        /** Key under which the installed-app search term is saved. */
        const val KEY_APP_QUERY = "installed_app_query"
    }
}
