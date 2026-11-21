package com.rewardadguard.app.collector

import android.content.Context
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.data.AppSettings
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
        val pauseReason: String? = null,
        /** Rewards the collector gave up on twice; the user collects them. */
        val manual: List<String> = emptyList(),
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
    // Read from the UI thread (start/stop) and written from the run's
    // Dispatchers.Default coroutine; make every write visible at once.
    @Volatile
    private var job: Job? = null
    @Volatile
    private var notificationContext: Context? = null

    /**
     * Starts [profiles] in order. [limit] is a [CollectorLimits] value:
     * -1 unlimited, 0 the profile cap, N an explicit cap. Returns an error
     * message, or null when the run was accepted.
     */
    fun start(
        service: RewardAdAccessibilityService,
        profiles: List<AppProfile>,
        limit: Int,
        dryRun: Boolean,
        /** Count what is left instead of collecting (2026-11-15). */
        scan: Boolean = false
    ): String? {
        val running = job
        if (running?.isActive == true) {
            // A scan never holds the screen against a collection the user asked
            // for (2026-11-16): it stops, its unscanned apps go back to the
            // schedule, and the collection starts as soon as it has let go.
            if (currentScan && !scan) {
                yieldScan("collection")
                scope.launch {
                    running.join()
                    start(service, profiles, limit, dryRun, scan = false)
                }
                return null
            }
            return "已有收取正在執行"
        }
        val environment = service.collectorRuntime() ?: return "無障礙服務尚未就緒"
        if (profiles.isEmpty()) return "沒有選擇可收取的 App"

        val selected = profiles.toList()
        val scanResults = ArrayList<ScanResult>()
        // Started from a notification button (reset reminder, 2026-11-15) the
        // shade stays down over the screen the run is about to tap: close it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
            }
        }
        environment.beginSequence()
        currentScan = scan
        scanYield = null
        scanRemaining = selected.map { it.key }
        recent.clear()
        notificationContext = service
        _state.value = State(
            running = true,
            dryRun = dryRun,
            appName = selected.first().name,
            startedAt = System.currentTimeMillis(),
            message = if (scan) "準備掃描" else "準備啟動",
            recent = emptyList()
        )
        CollectorNotification.update(service, _state.value)
        job = scope.launch {
            // The UI and notification follow the environment, so a pause the
            // collector took by itself (lock screen, finger) shows as paused
            // with its reason instead of "running" (review P1-3).
            val statusWatch = launch {
                environment.status.collect { status -> onEnvironmentStatus(status) }
            }
            try {
                val reasons = CollectorRunPolicy.runSequence(
                    items = selected,
                    stopRequested = { environment.stopRequested },
                    keyguardLocked = { environment.isKeyguardLocked() }
                ) { profile ->
                    if (scan) scanRemaining = selected.dropWhile { it.key != profile.key }.map { it.key }
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
                        rewardLimit = limit,
                        scanOnly = scan
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
                    if (outcome.endReason == "lottery_tickets_low") {
                        leaveLotteryAndStopGuard(service)
                    }
                    setState { it.copy(rewards = outcome.rewards, manual = it.manual + outcome.manual) }
                    addRecent(
                        "${profile.name} 結束（${outcome.endReason}）：獎勵 ${outcome.rewards}、" +
                            "廣告 ${outcome.ads}、點擊 ${outcome.taps}、逾時 ${outcome.timeouts}、" +
                            "導流 ${outcome.redirects}、錯誤 ${outcome.errors}" +
                            if (outcome.unconfirmed > 0) "、未確認入帳 ${outcome.unconfirmed}" else ""
                    )
                    if (environment.state != CollectorState.IDLE) environment.stop()
                    if (scan) {
                        scanResults += ScanResult(
                            profile.key, profile.name, outcome.available, outcome.endReason,
                            repeatable = outcome.repeatable,
                            note = outcome.manual.firstOrNull()
                        )
                    }
                    outcome.endReason
                }
                if (reasons.size < selected.size) addRecent("已停止，不再啟動下一個 App")
                val left = _state.value.manual
                setState {
                    it.copy(message = if (left.isEmpty()) "已結束" else "已結束，${left.size} 個獎勵需要手動收取")
                }
                if (left.isNotEmpty()) CollectorNotification.showManual(service, left)
                if (scan && scanYield != null) {
                    // Gave the screen back: scan the rest later, when the phone
                    // is unlocked and idle again (the schedule's usual wait).
                    val rest = scanRemaining
                    if (rest.isNotEmpty()) {
                        CollectorScheduler.setPending(
                            service,
                            PendingRun(SCAN_RESUME_ID, System.currentTimeMillis(), rest, scan = true)
                        )
                        CollectorScheduler.setStatus(service, "掃描已讓出畫面，手機閒置後接著掃")
                        addRecent("掃描讓出畫面（${if (scanYield == "collection") "開始收取" else "手機使用中"}），稍後接著掃 ${rest.size} 個 App")
                    }
                } else if (scan && scanResults.isNotEmpty()) {
                    RewardReminders.notifyScan(service, scanResults.toList())
                }
                if (scan && scanYield == null) {
                    // A finished scan left the last app (often a reward page
                    // with animations) on screen, keeping the phone busy and
                    // draining the battery. Go to the home screen, unless the
                    // user is in this app (they stopped it from here).
                    if (service.collectorForeground().packageName != service.packageName) {
                        service.performGlobalAction(
                            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                        )
                        addRecent("掃描完成，已回到桌面")
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                addRecent("錯誤：${t::class.java.simpleName}: ${t.message}")
                setState { it.copy(message = "錯誤：${t.message ?: "未知"}") }
            } finally {
                statusWatch.cancel()
                job = null
                val resume = scan && scanYield != null
                currentScan = false
                if (resume) service.collectorScheduledRunner()?.kick()
                setState { it.copy(running = false, paused = false, pauseReason = null, appName = null) }
            }
        }
        return null
    }

    /** The running sequence is a scan (count only), not a collection. */
    @Volatile
    private var currentScan = false

    /** Why a scan gave the screen back ("user" / "collection"), null while it runs. */
    @Volatile
    private var scanYield: String? = null

    /** Apps the running scan has not finished, the current one included. */
    @Volatile
    private var scanRemaining: List<String> = emptyList()

    private const val SCAN_RESUME_ID = -999L

    /** Stops a running scan so the phone (or a collection) can have the screen. */
    private fun yieldScan(reason: String) {
        if (!currentScan || scanYield != null) return
        scanYield = reason
        RewardAdAccessibilityService.instance?.collectorRuntime()?.requestStop()
        addRecent(if (reason == "collection") "掃描讓給收取" else "手機使用中，掃描讓出畫面")
    }

    fun pause() {
        RewardAdAccessibilityService.instance?.collectorRuntime()
            ?.pause(CollectorInterruption.REASON_USER_REQUESTED)
        addRecent("暫停")
    }

    fun resume() {
        RewardAdAccessibilityService.instance?.collectorRuntime()?.resume()
        addRecent("繼續")
    }

    fun stop() {
        RewardAdAccessibilityService.instance?.collectorRuntime()?.requestStop()
        setState { it.copy(message = "停止中…") }
        addRecent("使用者要求停止")
    }

    /**
     * LINE 抽抽樂 is out of 5-ticket draws (asked 2026-11-21): leave the app
     * for the desktop, then stop the guard so it does not keep watching.
     */
    private suspend fun leaveLotteryAndStopGuard(service: RewardAdAccessibilityService) {
        val left = runCatching {
            if (service.collectorForeground().packageName != service.packageName) {
                service.performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                )
            } else {
                false
            }
        }.getOrDefault(false)
        addRecent(if (left) "抽抽樂票券不足 5 張：已回到桌面" else "抽抽樂票券不足 5 張")
        runCatching {
            RewardAdGuardApp.Container.settingsRepository
                .setPausedUntil(AppSettings.PAUSED_INDEFINITELY)
        }.onSuccess {
            addRecent("已停止獎勵守衛")
        }.onFailure {
            addRecent("停止獎勵守衛失敗：${it.message ?: it::class.java.simpleName}")
        }
    }

    private fun onEnvironmentStatus(status: CollectorEnvironment.Status) {
        val paused = status.state == CollectorState.PAUSED
        if (paused && currentScan && status.pauseReason in SCAN_YIELD_REASONS) {
            // A scan is only worth running on a phone nobody is using: a finger,
            // another app or the lock screen ends it instead of pausing it
            // (a paused scan held the screen for 7 minutes on 2026-11-16).
            yieldScan("user")
        }
        val wasPaused = _state.value.paused
        if (paused == wasPaused && status.pauseReason == _state.value.pauseReason) return
        setState {
            it.copy(
                paused = paused,
                pauseReason = status.pauseReason,
                message = when {
                    paused -> pauseMessage(status.pauseReason)
                    wasPaused -> "繼續收取"
                    else -> it.message
                }
            )
        }
        if (paused) addRecent(pauseMessage(status.pauseReason))
        else if (wasPaused) addRecent("繼續收取")
    }

    private val SCAN_YIELD_REASONS = setOf(
        CollectorInterruption.REASON_USER_INTERACTION,
        CollectorInterruption.REASON_USER_LEFT,
        CollectorInterruption.REASON_KEYGUARD
    )

    /** What the UI and the notification say for each pause reason. */
    fun pauseMessage(reason: String?): String = when (reason) {
        CollectorInterruption.REASON_KEYGUARD -> "已暫停：手機已鎖定，解鎖後自動繼續"
        CollectorInterruption.REASON_USER_INTERACTION -> "已暫停：偵測到手指操作，停手 5 秒後自動繼續"
        CollectorInterruption.REASON_USER_REQUESTED -> "已暫停：按「繼續」恢復"
        CollectorInterruption.REASON_USER_LEFT -> "已暫停：你正在使用其他 App，按「繼續」恢復"
        else -> "已暫停"
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
        RewardAdAccessibilityService.instance?.collectorFloating()?.render(_state.value)
    }
}

