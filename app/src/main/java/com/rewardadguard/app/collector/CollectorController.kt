package com.rewardadguard.app.collector

import android.content.Context
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.service.RewardAdAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-wide owner of the automatic collection run.
 *
 * The run must outlive the Activity (and the debug entry), so it lives here
 * rather than in a ViewModel. There is exactly one job and one
 * [CollectorEnvironment] (owned by the accessibility service), which is what
 * makes "start" idempotent and "two engines on one screen" impossible.
 */
object CollectorController {

    data class State(
        val running: Boolean = false,
        val paused: Boolean = false,
        val appName: String? = null,
        val dryRun: Boolean = false,
        val rewards: Int = 0,
        val ads: Int = 0,
        val taps: Int = 0,
        val startedAt: Long = 0L,
        val message: String = "尚未開始",
        val recent: List<String> = emptyList()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val recent = ArrayDeque<String>()
    private var job: Job? = null
    private var notificationContext: Context? = null

    /**
     * Starts [profiles] in order. Returns an error message, or null when the
     * run was accepted.
     */
    fun start(
        service: RewardAdAccessibilityService,
        profiles: List<AppProfile>,
        limit: Int,
        dryRun: Boolean
    ): String? {
        if (job?.isActive == true) return "已有收取正在執行"
        val environment = service.collectorRuntime() ?: return "無障礙服務尚未就緒"
        if (profiles.isEmpty()) return "沒有選擇可收取的 App"

        val selected = profiles.toList()
        recent.clear()
        notificationContext = service
        _state.value = State(
            running = true,
            dryRun = dryRun,
            appName = selected.first().name,
            startedAt = System.currentTimeMillis(),
            message = "準備啟動",
            recent = emptyList()
        )
        CollectorNotification.update(service, _state.value)
        job = scope.launch {
            try {
                for (profile in selected) {
                    setState {
                        it.copy(
                            appName = profile.name,
                            rewards = 0,
                            ads = 0,
                            taps = 0,
                            startedAt = System.currentTimeMillis(),
                            message = "啟動 ${profile.name}"
                        )
                    }
                    val engine = CollectorEngine(
                        service = service,
                        environment = environment,
                        foreground = service::collectorForeground,
                        logger = RewardAdGuardApp.Container.logger,
                        selfClicks = service.collectorSelfClicks(),
                        profile = profile,
                        dryRun = dryRun,
                        rewardLimit = limit
                    )
                    engine.progressListener = { progress ->
                        setState {
                            it.copy(
                                message = progress.message,
                                rewards = progress.rewards,
                                ads = progress.ads,
                                taps = progress.taps
                            )
                        }
                        addRecent(progress.message)
                    }
                    val outcome = engine.run()
                    setState { it.copy(rewards = outcome.rewards) }
                    addRecent(
                        "${profile.name} 結束（${outcome.endReason}）：獎勵 ${outcome.rewards}、" +
                            "廣告 ${outcome.ads}、點擊 ${outcome.taps}、逾時 ${outcome.timeouts}、" +
                            "導流 ${outcome.redirects}、錯誤 ${outcome.errors}"
                    )
                    if (environment.state != CollectorState.IDLE) environment.stop()
                }
                setState { it.copy(message = "已結束") }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                addRecent("錯誤：${t::class.java.simpleName}: ${t.message}")
                setState { it.copy(message = "錯誤：${t.message ?: "未知"}") }
            } finally {
                job = null
                setState { it.copy(running = false, paused = false, appName = null) }
            }
        }
        return null
    }

    fun pause() {
        RewardAdAccessibilityService.instance?.collectorRuntime()?.pause("user_requested")
        setState { it.copy(paused = true, message = "已暫停（真人操作或使用者要求）") }
        addRecent("暫停")
    }

    fun resume() {
        RewardAdAccessibilityService.instance?.collectorRuntime()?.resume()
        setState { it.copy(paused = false, message = "繼續收取") }
        addRecent("繼續")
    }

    fun stop() {
        RewardAdAccessibilityService.instance?.collectorRuntime()?.stop()
        setState { it.copy(message = "停止中…") }
        addRecent("使用者要求停止")
    }

    private fun addRecent(line: String) {
        synchronized(recent) {
            recent.addFirst(line)
            while (recent.size > 50) recent.removeLast()
            setState { it.copy(recent = recent.toList()) }
        }
    }

    /** One place that updates the flow and the ongoing notification together. */
    private fun setState(transform: (State) -> State) {
        _state.update(transform)
        notificationContext?.let { CollectorNotification.update(it, _state.value) }
    }
}

