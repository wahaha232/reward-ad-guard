package com.rewardadguard.app.manager

import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Reward/source app entry as shown in the UI. */
data class RewardAppInfo(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
    val protectionMode: com.rewardadguard.app.data.ProtectionMode? = null,
    val isSystemApp: Boolean = false
)

/**
 * Live snapshot of what the accessibility service is doing right now.
 * The UI observes this; nothing else reads UI-facing state.
 */
data class MonitoringSnapshot(
    val serviceConnected: Boolean = false,
    val monitoringActive: Boolean = false,
    val sessionId: String? = null,
    val sessionState: SessionState = SessionState.IDLE,
    val sourcePackage: String? = null,
    val currentForegroundPackage: String? = null,
    val previousForegroundPackage: String? = null,
    val adSessionActive: Boolean = false,
    val redirectRisk: Int = 0,
    val lastEventSummary: String? = null,
    val lastEventAt: Long = 0L,
    val todayBlocked: Int = 0,
    val todayRedirects: Int = 0,
    val todayCloseDetected: Int = 0,
    val todayCloseAssisted: Int = 0,
    val errors: Int = 0,
    val rewardAppCount: Int = 0,
    val protectionEnabled: Boolean = true,
    val protectionMode: ProtectionMode = ProtectionMode.BLOCK,
    /**
     * False when the OS refuses notifications (API 33+ permission denied, or the
     * channel switched off), so the dashboard can tell the user why no status
     * notification appears (ISS-15).
     */
    val notificationPermissionGranted: Boolean = true,
    /** Cached [AppSettings.assistAction], so the UI can show what the guard may do. */
    val assistAction: AssistAction = AssistAction.ASSIST_CLICK,
    /** Cached [AppSettings.closeButtonAssistance]. */
    val closeButtonAssistance: Boolean = true,
    /** Events handled since the service connected - a cheap load signal (P1#5). */
    val eventsProcessed: Long = 0,
    /** Full accessibility-tree scans performed since connect (P1#5). */
    val treeScans: Long = 0,
    /** Nodes visited across those scans (P1#5). */
    val nodesVisited: Long = 0,
    val startedAt: Long = 0L,
    val ready: Boolean = false
) {
    /**
     * True only when the guard may actually click the ad's X right now
     * (P0#1): protection is BLOCK, the close-button feature is on, and the assist
     * action is not the detect-only `NONE`.
     */
    val autoAssistAllowed: Boolean
        get() = protectionMode == ProtectionMode.BLOCK &&
            closeButtonAssistance &&
            assistAction != AssistAction.NONE
}

/**
 * Process-wide, in-memory bridge between the accessibility service and the UI.
 *
 * No disk or network access happens here, and there is no polling loop: the
 * service pushes updates only when something actually changes.
 */
object MonitoringState {

    private val _snapshot = MutableStateFlow(MonitoringSnapshot())
    val snapshot: StateFlow<MonitoringSnapshot> = _snapshot.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _rewardApps = MutableStateFlow<List<RewardAppInfo>>(emptyList())
    val rewardApps: StateFlow<List<RewardAppInfo>> = _rewardApps.asStateFlow()

    private val _pendingNewApp = MutableStateFlow<PendingNewApp?>(null)
    val pendingNewApp: StateFlow<PendingNewApp?> = _pendingNewApp.asStateFlow()

    @Volatile
    var serviceConnected: Boolean = false
        private set

    @Volatile
    var monitoringActive: Boolean = false
        private set

    fun updateSettings(settings: AppSettings) {
        _settings.value = settings
    }

    /**
     * Mirrors the parts of [AppSettings] the dashboard shows as "what may happen
     * right now" (P0#1). Skips the write when nothing changed so the settings
     * flow cannot churn the snapshot.
     */
    fun updateSettingsSummary(assistAction: AssistAction, closeButtonAssistance: Boolean) {
        val current = _snapshot.value
        if (current.assistAction == assistAction &&
            current.closeButtonAssistance == closeButtonAssistance
        ) {
            return
        }
        mutate { it.copy(assistAction = assistAction, closeButtonAssistance = closeButtonAssistance) }
    }

    /** P1#5 load counter: one accessibility event handled. */
    fun countEventProcessed() {
        mutate { it.copy(eventsProcessed = it.eventsProcessed + 1) }
    }

    /** P1#5 load counter: one tree scan that visited [nodes] nodes. */
    fun countTreeScan(nodes: Int) {
        mutate { it.copy(treeScans = it.treeScans + 1, nodesVisited = it.nodesVisited + nodes) }
    }

    /** Marks the process start time; used for "uptime" in the UI. */
    fun markStart(now: Long = System.currentTimeMillis()) {
        mutate { it.copy(startedAt = now) }
    }

    fun updateRewardApps(apps: List<RewardAppInfo>) {
        _rewardApps.value = apps
    }

    fun setServiceConnected(connected: Boolean) {
        serviceConnected = connected
        mutate { it.copy(serviceConnected = connected) }
    }

    fun setMonitoring(active: Boolean, sourcePackage: String? = null) {
        monitoringActive = active
        mutate { it.copy(monitoringActive = active, sourcePackage = sourcePackage) }
    }

    fun update(block: (MonitoringSnapshot) -> MonitoringSnapshot) = mutate(block)

    fun setPendingNewApp(pending: PendingNewApp?) {
        _pendingNewApp.value = pending
    }

    private fun mutate(block: (MonitoringSnapshot) -> MonitoringSnapshot) {
        _snapshot.value = block(_snapshot.value)
    }
}

/**
 * A foreground app that is not (yet) a configured reward app but is a
 * plausible candidate because it was used as a source by a running session.
 * Spec section 37/38: never auto-block unknown apps, always ask the user.
 */
data class PendingNewApp(
    val packageName: String,
    val label: String,
    val detectedAt: Long
)
