package com.rewardadguard.app.manager

import com.rewardadguard.app.data.AppSettings
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
    val startedAt: Long = 0L,
    val ready: Boolean = false
)

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
