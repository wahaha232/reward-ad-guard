package com.rewardadguard.app

import android.app.Application
import com.rewardadguard.app.data.RewardAdGuardDatabase
import com.rewardadguard.app.manager.AppManager
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.manager.LogExporter
import com.rewardadguard.app.manager.MonitoringState
import com.rewardadguard.app.manager.RewardAppStore
import com.rewardadguard.app.manager.RewardAppsRepository
import com.rewardadguard.app.manager.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Application entry point.
 *
 * Owns the long-lived singletons so that both the UI process and the
 * accessibility service share exactly one logger, one settings flow and one
 * monitoring snapshot.
 */
class RewardAdGuardApp : Application() {

    /** DI-lite container; deliberately no third party framework. */
    object Container {
        lateinit var app: RewardAdGuardApp
            private set

        val logger: EventLogger get() = app.logger
        val settingsRepository: SettingsRepository get() = app.settingsRepository
        val rewardAppsRepository: RewardAppsRepository get() = app.rewardAppsRepository
        val appManager: AppManager get() = app.appManager
        val logExporter: LogExporter get() = app.logExporter
        val database: RewardAdGuardDatabase get() = app.database

        fun install(application: RewardAdGuardApp) {
            app = application
        }
    }

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var rewardAppsRepository: RewardAppsRepository
        private set

    lateinit var logger: EventLogger
        private set

    lateinit var appManager: AppManager
        private set

    lateinit var logExporter: LogExporter
        private set

    /** Shared Room instance; the UI is the only place that queries it heavily. */
    val database: RewardAdGuardDatabase by lazy { RewardAdGuardDatabase.get(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        settingsRepository = SettingsRepository(this)
        appManager = AppManager(this)
        rewardAppsRepository = RewardAppsRepository(this, RewardAppStore(this), appManager)
        logExporter = LogExporter(this)

        // The logger resolves friendly labels through [AppManager], so it is built
        // once the manager exists; a single instance is shared by UI and service.
        logger = EventLogger(this, { MonitoringState.settings.value }, appManager::appLabel)

        Container.install(this)
        MonitoringState.markStart()

        // Keep the in-memory snapshot in sync with the persisted state so the UI
        // is correct even when the accessibility service is not running yet
        // (for example directly after a reboot).
        rewardAppsRepository.observeRewardApps()
            .onEach { apps -> MonitoringState.updateRewardApps(apps) }
            .launchIn(appScope)

        settingsRepository.settings
            .onEach { settings ->
                MonitoringState.updateSettings(settings)
                MonitoringState.update {
                    it.copy(
                        monitoringActive = settings.protectionEnabled,
                        protectionMode = settings.protectionMode
                    )
                }
            }
            .launchIn(appScope)
    }

    override fun onTerminate() {
        logger.close()
        appScope.cancel()
        super.onTerminate()
    }

    companion object {
        /** How long a session may stay idle before it is closed. */
        const val SESSION_TIMEOUT_MILLIS = 10 * 60 * 1000L
    }
}
