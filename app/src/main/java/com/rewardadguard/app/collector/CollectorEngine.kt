package com.rewardadguard.app.collector

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.guard.SelfClickTracker
import com.rewardadguard.app.manager.EventLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The automatic collection loop, ported from pc-collector's `BaseApp`.
 *
 * It is deliberately an orchestrator: every decision lives in
 * [CollectorRules] and every touch goes through [Actor], so a wrong decision can
 * be replayed from a fixture and a tap can never bypass the self-click marker or
 * the pause/stop gate.
 *
 * The engine is driven by the debug entry point today and by the UI later. It
 * refuses to run unless it could take the environment from IDLE - two engines on
 * one screen is the failure this whole integration is designed to avoid.
 */
class CollectorEngine(
    private val service: AccessibilityService,
    private val environment: CollectorEnvironment,
    private val foreground: () -> Foreground,
    private val logger: EventLogger,
    private val selfClicks: SelfClickTracker,
    private val profile: AppProfile,
    private val dryRun: Boolean,
    private val rewardLimit: Int,
    /**
     * Scan only (2026-11-15): open the app and its tabs, close popups and
     * startup ads, count the rewards still offered, never tap one.
     */
    private val scanOnly: Boolean = false
) {

    data class Outcome(
        val app: String,
        val endReason: String,
        val rewards: Int,
        val ads: Int,
        val taps: Int,
        val timeouts: Int,
        val redirects: Int,
        val errors: Int,
        val runDir: String,
        /** Ads that ended without proof of a reward; never counted as rewards. */
        val unconfirmed: Int = 0,
        /** Rewards whose ad could not be closed twice; left for the user. */
        val manual: List<String> = emptyList(),
        /** Scan only: rewards still offered; -1 when this was not a scan. */
        val available: Int = -1,
        /** Scan only: the app's reward button can be used again and again. */
        val repeatable: Boolean = false
    )

    /** Live progress for the UI; the engine never reads UI state. */
    data class Progress(
        val message: String,
        val rewards: Int,
        val ads: Int,
        val taps: Int
    )

    var progressListener: ((Progress) -> Unit)? = null

    private val reader = ScreenReader(service)
    private val recorder = RunRecorder(service, profile, dryRun)
    private val actor = Actor(
        service = service,
        logger = logger,
        selfClicks = selfClicks,
        sessionId = { null },
        stateProvider = { environment.state },
        interruptionCheck = { environment.checkInterruptions() },
        targetPackage = profile.packageName,
        // The active window, read at the moment of acting - never the
        // event-derived guess, which can be stale (review 2026-11-12, P0).
        foregroundNow = { service.rootInActiveWindow?.packageName?.toString() },
        protectedSurface = { CollectorRules.isSystemPackage(it) || isHomeScreen(it) }
    )

    /** The phone's launcher, resolved once (the known list misses some). */
    private val homePackage: String? by lazy {
        runCatching {
            service.packageManager.resolveActivity(
                android.content.Intent(android.content.Intent.ACTION_MAIN)
                    .addCategory(android.content.Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
        }.getOrNull()
    }

    private fun isHomeScreen(pkg: String?): Boolean {
        val value = pkg?.trim().orEmpty()
        return value.isNotEmpty() && (value == homePackage || CollectorRules.isLauncher(value))
    }

    private var rewards = 0
    private var available = 0
    /** A scan that could not open every tab; its count is a lower bound. */
    private var scanIncomplete = false
    private var ads = 0
    private var taps = 0
    private var timeouts = 0
    private var redirects = 0
    private var errors = 0
    private var pausedSince = 0L
    private var engineStartedAt = 0L
    private val bonusTracker = LineWalkRules.BonusTracker(profile.maxBonusFailures)
    private var inBonusChoice = false
    private var bonusSawInvitePromo = false
    private var loadFailures = 0
    private var restarts = 0
    private var stopCause: String? = null
    private var unconfirmed = 0
    private var noFill = 0
    private var consecutiveNoFill = 0
    /** An ad that neither the run nor Back can leave; this app's run ends. */
    private var adTrapped = false
    /** Buttons that led out of the app, by their words; never tapped again this run. */
    private val droppedButtons = HashSet<String>()
    /** Times in a row a button's tap left the app, by its words. */
    private val leadOuts = HashMap<String, Int>()
    /** Where dropped buttons sit ([CollectorRules.dropSpot]). */
    private val droppedSpots = HashSet<String>()
    private val spotLeadOuts = HashMap<String, Int>()
    /** Per-run no-reward spots for profiles with [AppProfile.skipNoRewardRepeat] (HAHAGO). */
    private val deadTargets = CollectorRules.NoRewardTargetTracker()
    private var cleanRestarts = 0
    /** Restarts because no tab could be opened in a whole sweep (2026-11-16). */
    private var tabRestarts = 0
    private var deferredRounds = 0
    /** When an ad frame was last read (active time); 0 before any ad. */
    private var lastAdFrameAt = 0L
    /** The lottery sheet could not be found again; the run did not finish. */
    private var lotteryLost = false
    /** Specific end reason set by [collectLottery] (e.g. lottery_tickets_low / lottery_normal_failed). */
    private var lotteryEndReason: String? = null
    /** The user-only screen that stopped this run ([AppProfile.blockerScreens]). */
    private var blockedBy: String? = null

    /** A reward button whose ad got stuck; retried once after everything else. */
    private data class Deferred(val id: String, val tab: String, val text: String)
    private val deferred = LinkedHashMap<String, Deferred>()
    private val manual = ArrayList<String>()
    /** Set when a reward dialog was seen during the current ad. */
    private var rewardEvidence = false
    private val activeClock = ActiveClock()

    suspend fun run(): Outcome {
        engineStartedAt = activeNow()
        environment.targetPackage = profile.packageName
        environment.start()
        publish("啟動 ${profile.name}")
        if (environment.state != CollectorState.RUNNING) {
            recorder.console("[collector] refused: another run owns the screen")
            recorder.finish("not_started")
            return outcome("not_started")
        }
        logger.log(
            eventType = EventType.COLLECTOR,
            sessionId = null,
            sourcePackage = profile.packageName,
            detectionMethod = "CollectorEngine",
            action = "RUN_START",
            result = "SUCCESS",
            message = "app=${profile.key} dryRun=$dryRun limit=$rewardLimit dir=${recorder.runDir.name}"
        )

        var reason = "done"
        var fatal: Error? = null
        try {
            if (!launchApp()) {
                reason = "launch_failed"
            } else if (scanOnly) {
                scanApp()
                if (blockedBy != null) reason = "blocked_screen"
                else if (scanIncomplete) reason = "scan_incomplete"
            } else if (dryRun) {
                // A dry run must not tap anything, including tabs or popups. It
                // reads the screen the app launched on and reports one decision.
                dryRunRead()
            } else {
                ensureReady()
                dismissPopups()
                if (blockedBy != null) {
                    reason = "blocked_screen"
                    manual += blockedBy!!
                } else if (profile.cashzineMode) {
                    collectCashzine()
                } else if (profile.lotteryMode) {
                    collectLottery()
                    val lotteryReason = lotteryEndReason
                    if (lotteryReason != null) {
                        reason = lotteryReason
                    } else if (lotteryLost && reason == "done") {
                        reason = "lottery_lost"
                    }
                } else {
                val tabs = profile.collectionTabs.ifEmpty { listOf("") }
                val iterationLimit = CollectorLimits.iterations(rewardLimit, profile.maxRewards)
                // Collect this app until a whole sweep over its tabs brings
                // nothing (asked by the user, 2026-11-13: BITWALK moved on
                // after 2-7 rewards while 27 more were waiting).
                var sweep = 0
                sweeps@ while (true) {
                sweep++
                val rewardsBeforeSweep = rewards
                if (sweep > 1) recorder.event("sweep_again", fields = mapOf("sweep" to sweep))
                var tabsOpened = 0
                loop@ for (tab in tabs) {
                    if (!awaitRunning()) {
                        // ad_trapped / run_timeout / paused_too_long say why;
                        // "stopped" hid a trapped ad on 2026-11-16 11:07.
                        reason = stopCause ?: "stopped"
                        break
                    }
                    if (tab.isNotEmpty() && !openTab(tab)) continue
                    tabsOpened++
                    awaitTabSettled(tab)
                    dismissPopups()
                    val scroll = profile.tabScrolls[tab]
                    if (scroll != null && !scroll.horizontal) scrollToTop()
                    var scrolled = 0
                    var lostSubPage = 0
                    val attempts = HashMap<String, Int>()
                    val infoHits = HashMap<String, Int>()
                    val stuckStreak = HashMap<String, Int>()
                    var index = 0
                    while (awaitRunning() && index < iterationLimit) {
                        if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) {
                            reason = "limit"
                            break@loop
                        }
                        index++
                        var snapshot = readPlain("collect_${tab.ifEmpty { "home" }}_$index")
                        var shadeWaits = 0
                        while (CollectorRules.isUserShade(snapshot.foregroundPackage) &&
                            shadeWaits < SHADE_WAIT_POLLS && awaitRunning()
                        ) {
                            // The user pulled the shade down; never close it by
                            // relaunching the app under their finger.
                            if (shadeWaits == 0) recorder.event("shade_open_wait")
                            shadeWaits++
                            delay(1_500L)
                            snapshot = readPlain("collect_${tab.ifEmpty { "home" }}_${index}_shade")
                        }
                        if (isWrongPackage(snapshot) && isUsersApp(snapshot.foregroundPackage)) {
                            if (!yieldToUser(snapshot.foregroundPackage)) break
                            snapshot = readPlain("collect_${tab.ifEmpty { "home" }}_${index}_after_user")
                        }
                        if (isWrongPackage(snapshot)) {
                            // Another app resumed over ours (WALK EARN did this
                            // on 11-12). Never decide on, or tap, a screen that
                            // belongs to a different package.
                            recorder.event(
                                "wrong_package",
                                fields = mapOf("package" to snapshot.foregroundPackage)
                            )
                            recoverToApp()
                            snapshot = readPlain("collect_${tab.ifEmpty { "home" }}_${index}_recover")
                            if (isWrongPackage(snapshot)) {
                                recorder.event("wrong_package_persisted")
                                break
                            }
                        }
                        if (profile.tabPaths[tab] != null && profile.altChromeTerms.isNotEmpty() &&
                            !CollectorRules.onSubPage(snapshot, profile)
                        ) {
                            // The sub-page is gone (an ad's Back landed on
                            // another page, 2026-11-14 21:00): go back in
                            // rather than tap whatever this page offers.
                            lostSubPage++
                            recorder.event("sub_page_lost", fields = mapOf("tab" to tab, "count" to lostSubPage))
                            if (lostSubPage > MAX_SUB_PAGE_REOPENS || !openTab(tab)) break
                            delay(1_200L)
                            snapshot = readPlain("collect_${tab}_${index}_reopen")
                            if (!CollectorRules.onSubPage(snapshot, profile)) break
                        }
                        var candidate = pickReward(snapshot, attempts, tab)
                        var retries = 0
                        while (candidate == null && retries < NO_CANDIDATE_RETRIES) {
                            retries++
                            if (!recoverMainScreen(snapshot)) break
                            // A trapped ad ends the run here: nothing more is
                            // tapped (11:07:32 a banner was still tapped).
                            if (adTrapped) break
                            snapshot = readPlain("collect_${tab.ifEmpty { "home" }}_${index}_retry$retries")
                            candidate = pickReward(snapshot, attempts, tab)
                        }
                        if (adTrapped) break
                        if (candidate == null && scroll != null && scrolled < scroll.pages) {
                            // More of the page below (or beside) this screen:
                            // AHEALTH's 更多健康習慣 and its 步行 coin strip.
                            scrolled++
                            recorder.event("scroll_more", fields = mapOf("tab" to tab, "page" to scrolled))
                            if (scrollPage(scroll, snapshot)) {
                                delay(1_200L)
                                continue
                            }
                        }
                        if (candidate == null) {
                            recorder.event("no_reward", fields = mapOf("tab" to tab))
                            recorder.console("[collector] no reward candidate on tab=$tab")
                            break
                        }
                        val key = CollectorRules.attemptKey(candidate)
                        attempts[key] = (attempts[key] ?: 0) + 1
                        taps++
                        rewardEvidence = false
                        publish("點擊 ${candidate.text}")
                        recorder.console(
                            "[collector] tap '${candidate.text}' @ (${candidate.centerX},${candidate.centerY})"
                        )
                        recorder.event(
                            "tap_reward",
                            fields = mapOf(
                                "tab" to tab,
                                "text" to candidate.text,
                                "x" to candidate.centerX,
                                "y" to candidate.centerY,
                                "source" to candidate.source
                            )
                        )
                        if (!actor.tap(candidate.centerX, candidate.centerY)) {
                            errors++
                            recorder.event("tap_failed", fields = mapOf("text" to candidate.text))
                            break
                        }
                        val expectAd = expectsAd(candidate)
                        val outcome = afterReward(snapshot, candidate, expectAd, "${tab.ifEmpty { "home" }}_$index")
                        when (outcome) {
                            "ad_unconfirmed", "ad_closed", "ad_ended", "claimed", "confirmed" -> {
                                consecutiveNoFill = 0
                                stuckStreak.remove(key)
                                leadOuts.remove(CollectorRules.dropKey(candidate.text))
                                countOutcome(outcome)
                            }

                            "ad_no_fill" -> {
                                consecutiveNoFill++
                                publish("廣告未載入，已略過（連續 $consecutiveNoFill 次）")
                                if (consecutiveNoFill >= MAX_CONSECUTIVE_NO_FILL) {
                                    reason = "ad_no_fill"
                                    recorder.event(
                                        "ad_no_fill_stop",
                                        fields = mapOf("consecutive" to consecutiveNoFill, "noFill" to noFill)
                                    )
                                    break@loop
                                }
                            }

                            "ad_stuck", "ad_timeout", "ad_not_loaded" -> {
                                // BITWALK's one button and LINE's 獲得票券 give
                                // many rewards; one bad ad must not park them
                                // (2026-11-13 14:17 / 14:31). Skip just that ad
                                // and tap the same button again; only a second
                                // bad ad in a row defers it to the end.
                                val streak = (stuckStreak[key] ?: 0) + 1
                                stuckStreak[key] = streak
                                if (AdStuck.shouldDefer(streak)) {
                                    attempts[key] = profile.maxRepeatPerTarget
                                    deferReward(tab, candidate, outcome)
                                } else {
                                    recorder.event("ad_skipped_once", fields = mapOf("text" to candidate.text, "outcome" to outcome))
                                    publish("這則廣告無法完成，換下一則")
                                }
                            }

                            "info_dialog" -> {
                                val hits = (infoHits[key] ?: 0) + 1
                                infoHits[key] = hits
                                if (hits > INFO_DIALOG_RETRIES) {
                                    attempts[key] = profile.maxRepeatPerTarget
                                    recorder.event("ad_unavailable", fields = mapOf("text" to candidate.text))
                                } else {
                                    delay(INFO_RETRY_WAIT_MILLIS)
                                }
                            }

                            "blocked_redirect", "blocked_web_page", "page_back" -> {
                                val dropKey = CollectorRules.dropKey(candidate.text)
                                val spot = CollectorRules.dropSpot(candidate)
                                val outs = maxOf(
                                    (leadOuts[dropKey] ?: 0) + 1,
                                    (spotLeadOuts[spot] ?: 0) + 1
                                )
                                leadOuts[dropKey] = (leadOuts[dropKey] ?: 0) + 1
                                spotLeadOuts[spot] = (spotLeadOuts[spot] ?: 0) + 1
                                if (!expectAd && outs >= CollectorRules.LEAD_OUTS_TO_DROP) {
                                    // A button that keeps leading out of the app
                                    // is an ad, not a reward: BITWALK's banner
                                    // "立即領取禮物卡" opened Chrome 100 times on
                                    // 2026-11-15 12:12-12:37. Twice in a row, so one
                                    // ad behind an AHEALTH 領取 coin does not stop
                                    // the coins. Never again this run, whatever
                                    // position it is read at.
                                    droppedButtons.add(dropKey)
                                    droppedSpots.add(spot)
                                    attempts[key] = profile.maxRepeatPerTarget
                                    recorder.event("button_dropped", fields = mapOf("text" to candidate.text, "outcome" to outcome))
                                } else {
                                    recorder.event("after_outcome", fields = mapOf("outcome" to outcome))
                                }
                            }

                            "refused" -> {
                                // The app said no ("今日已上傳過相同的步數").
                                // Nothing was earned; do not tap it again.
                                attempts[key] = profile.maxRepeatPerTarget
                            }

                            "no_change" -> {
                                recorder.event("no_change", fields = mapOf("text" to candidate.text))
                                // A greyed-out 領取 (AHEALTH's 140/160 分鐘): on a
                                // page that scrolls, skip it and look further
                                // instead of leaving the page.
                                if (profile.skipNoRewardRepeat) {
                                    deadTargets.record(tab, candidate)
                                    attempts[key] = profile.maxRepeatPerTarget
                                    recorder.event(
                                        "dead_target_skipped",
                                        fields = mapOf(
                                            "tab" to tab,
                                            "text" to candidate.text,
                                            "x" to candidate.centerX,
                                            "y" to candidate.centerY,
                                            "outcome" to outcome
                                        )
                                    )
                                } else {
                                    if (scroll == null) break
                                    attempts[key] = profile.maxRepeatPerTarget
                                }
                            }

                            else -> recorder.event("after_outcome", fields = mapOf("outcome" to outcome))
                        }
                        val rewardsBefore = rewards
                        if (closeDoneSheet()) {
                            // The app's own "2p 已領取!" sheet is the proof
                            // the outcome check could not see.
                            when (CollectorRules.receivedSheetRecount(outcome)) {
                                "claimed" -> countOutcome("claimed")
                                "ad_confirmed_late" -> {
                                    // AHEALTH 睡眠's 獲得更多積分 (2026-11-25
                                    // 11:32): "40p 已領取" came after the three
                                    // checks; the ad was counted unconfirmed.
                                    unconfirmed--
                                    rewards++
                                    publish("已收 $rewards 個獎勵")
                                    recorder.event(
                                        "reward_collected",
                                        fields = mapOf("kind" to "ad_sheet_late", "count" to rewards)
                                    )
                                }
                            }
                        }
                        if (profile.skipNoRewardRepeat &&
                            rewards == rewardsBefore &&
                            outcome == "changed"
                        ) {
                            deadTargets.record(tab, candidate)
                            attempts[key] = profile.maxRepeatPerTarget
                            recorder.event(
                                "dead_target_skipped",
                                fields = mapOf(
                                    "tab" to tab,
                                    "text" to candidate.text,
                                    "x" to candidate.centerX,
                                    "y" to candidate.centerY,
                                    "outcome" to outcome
                                )
                            )
                        }
                        if (rewards > rewardsBefore || outcome == "claimed" || outcome == "confirmed") scrolled = 0
                        delay(AFTER_REWARD_DELAY_MILLIS)
                    }
                }
                if (reason != "done" || adTrapped || !awaitRunning()) break@sweeps
                if (tabsOpened == 0 && rewards == rewardsBeforeSweep) {
                    // Not one tab could be opened: the app is stuck on an ad
                    // or a page it cannot leave (BITWALK, 2026-11-16 08:34,
                    // ended "done" with the reward button still there).
                    // Restart it rather than call the run finished.
                    if (tabRestarts >= MAX_TAB_RESTARTS) {
                        reason = "stuck_no_tabs"
                        recorder.event("stuck_no_tabs", fields = mapOf("restarts" to tabRestarts))
                        break@sweeps
                    }
                    tabRestarts++
                    restartAppClean("no_tab_found")
                    if (blockedBy != null) {
                        reason = "blocked_screen"
                        manual += blockedBy!!
                        break@sweeps
                    }
                    continue@sweeps
                }
                if (rewards == rewardsBeforeSweep || sweep >= MAX_SWEEPS) {
                    // Nothing new this sweep: try the buttons whose ad did not
                    // load. When one pays again, go back to collecting rather
                    // than stop (BITWALK 2026-11-16 10:13: its only button was
                    // deferred once, retried for one reward, and the run ended).
                    if (deferred.isNotEmpty() && deferredRounds < MAX_DEFERRED_ROUNDS) {
                        deferredRounds++
                        val beforeRetry = rewards
                        retryDeferred()
                        if (rewards > beforeRetry && sweep < MAX_SWEEPS && awaitRunning()) continue@sweeps
                    }
                    break@sweeps
                }
                }
                if (reason == "done" && deferred.isNotEmpty()) retryDeferred()
                if ((reason == "done" || reason == "limit") && awaitRunning()) returnToFirstTab()
                }
            }
        } catch (t: Throwable) {
            if (t is CancellationException) {
                reason = "cancelled"
                throw t
            }
            errors++
            reason = "error"
            recorder.event("engine_error", message = "${t::class.java.simpleName}: ${t.message}")
            // A VM Error (OutOfMemoryError...) is not an ordinary engine
            // failure: clean up in finally, then let it surface.
            if (t is Error) fatal = t
        } finally {
            if (reason == "done" && stopCause != null) reason = stopCause!!
            // Stopped from outside while a step that never polls the state
            // (a dry-run read) was running: the run did not finish, it was
            // stopped. A normal finish still has the environment RUNNING here.
            if (reason == "done" && environment.state == CollectorState.IDLE) reason = "stopped"
            environment.stop()
            reader.close()
            recorder.finish(
                endReason = reason,
                stats = linkedMapOf(
                    "rewards" to rewards,
                    "ads" to ads,
                    "taps" to taps,
                    "timeouts" to timeouts,
                    "unconfirmed" to unconfirmed,
                    "noFill" to noFill,
                    "manual" to manual.size,
                    "scan" to scanOnly,
                    "available" to if (scanOnly) available else null,
                    "repeatable" to if (scanOnly) profile.repeatableRewards else null,
                    "redirects" to redirects,
                    "errors" to errors
                )
            )
            // Old records go after seven days or past 100 MB; this run goes to the user's
            // Telegram when they turned that on. Neither delays the next app.
            TelegramReporter.runFinished(service)
            logger.log(
                eventType = EventType.COLLECTOR,
                sessionId = null,
                sourcePackage = profile.packageName,
                detectionMethod = "CollectorEngine",
                action = "RUN_END",
                result = if (reason == "error") "FAILED" else "SUCCESS",
                message = "app=${profile.key} reason=$reason rewards=$rewards ads=$ads taps=$taps " +
                    "timeouts=$timeouts redirects=$redirects errors=$errors"
            )
        }
        fatal?.let { throw it }
        return outcome(reason)
    }

    // ------------------------------------------------------------------ setup

    private suspend fun launchApp(): Boolean {
        repeat(LAUNCH_ATTEMPTS) { attempt ->
            recorder.event("launch", fields = mapOf("package" to profile.packageName, "attempt" to attempt + 1))
            if (attempt > 0) {
                // Home first: an app that keeps resuming (HAHAGO did this on
                // 11-12) loses the foreground before we relaunch the target.
                actor.home()
                delay(800L)
            }
            if (!actor.launch(profile.packageName)) {
                recorder.event("launch_failed", fields = mapOf("attempt" to attempt + 1))
                return@repeat
            }
            val ok = actor.waitFor(profile.launchWaitMillis) {
                foreground().packageName == profile.packageName
            }
            recorder.event("launched", fields = mapOf("ok" to ok, "attempt" to attempt + 1))
            if (!ok) return@repeat
            // A pending task from another app can resume right after the launch
            // and steal the foreground. Require one stable sample.
            delay(1_200L)
            if (foreground().packageName == profile.packageName) return true
            recorder.event(
                "launch_unstable",
                fields = mapOf("package" to foreground().packageName, "attempt" to attempt + 1)
            )
        }
        return false
    }

    /** Up to four tries to reach a usable app screen (PC `ensure_ready`). */
    private suspend fun ensureReady() {
        repeat(READY_ATTEMPTS) { index ->
            if (!awaitRunning()) return
            val snapshot = readPlain("ready_$index")
            if (blocked(snapshot)) return
            if (profile.lineRules && clearLineOverlays(snapshot)) return@repeat
            if (CollectorRules.looksLikeAd(snapshot, profile)) {
                recorder.event("startup_ad")
                handleAd(assumeAd = false, label = "startup")
                return@repeat
            }
            val hasChrome = CollectorRules.hasAppChrome(snapshot, profile)
            if (!hasChrome && profile.pageMarkers.isNotEmpty() &&
                profile.pageMarkers.any { marker ->
                    TextMatch.normalizeLoose(marker) in snapshot.looseText
                }
            ) {
                recorder.event("recover_from_page")
                actor.back()
                delay(1_200L)
                return@repeat
            }
            // No bottom bar yet means the app is still on its splash screen.
            // LINE has no page markers, so the old "markers empty -> ready"
            // shortcut judged its splash (node 0 / OCR 3, 2026-11-12 20:00)
            // and found none of its tabs. Wait for the bar instead.
            if (hasChrome) return
            if (index >= 1 && !profile.lineRules && !isWrongPackage(snapshot) && CollectorRules.hasAdBadge(snapshot)) {
                // Still no tab bar a few seconds in, and an "Ad" label: an
                // app-open ad sits over the app (WALK EARN, 2026-11-13 23:56,
                // ended the run with no tab found).
                recorder.event("startup_ad", message = "ad_badge")
                handleAd(assumeAd = true, label = "startup")
                return@repeat
            }
            recorder.event("not_ready", fields = mapOf("attempt" to index + 1))
            delay(2_000L)
        }
    }

    /** True (and the run stops touching anything) on a [AppProfile.blockerScreens] screen. */
    private fun blocked(snapshot: Snapshot): Boolean {
        if (blockedBy != null) return true
        if (isWrongPackage(snapshot)) return false
        val text = snapshot.looseText
        val hit = profile.blockerScreens.entries.firstOrNull { (term, _) ->
            TextMatch.normalizeLoose(term).let { it.isNotEmpty() && it in text }
        } ?: return false
        blockedBy = hit.value
        recorder.event("blocker_screen", fields = mapOf("term" to hit.key))
        publish(hit.value)
        return true
    }

    private suspend fun dismissPopups() {
        if (blockedBy != null) return
        repeat(POPUP_ATTEMPTS) { index ->
            if (!awaitRunning()) return
            val snapshot = readPlain("popup_$index")
            if (profile.lineRules && clearLineOverlays(snapshot)) return@repeat
            val target = findPopupTarget(snapshot) ?: return
            recorder.event(
                "dismiss_popup",
                fields = mapOf("text" to target.text, "x" to target.centerX, "y" to target.centerY)
            )
            actor.tap(target.centerX, target.centerY)
            delay(1_500L)
        }
    }

    private fun findPopupTarget(snapshot: Snapshot): Target? {
        CollectorRules.findLeaveConfirm(snapshot)?.let { return it }
        val info = CollectorRules.findInfoDialogClose(snapshot, profile)
        if (info != null) return info
        val safe = snapshot.copy(boxes = snapshot.boxesFrom(TextSource.NODE))
            .find(CollectorRules.POPUP_SAFE_TERMS)
            ?: snapshot.copy(boxes = snapshot.boxesFrom(TextSource.OCR)).find(CollectorRules.POPUP_SAFE_TERMS)
        if (safe != null) return Target(safe.text, safe.bounds, safe.score, safe.source.name.lowercase())
        if (!CollectorRules.isDialog(snapshot, profile)) return null
        CollectorRules.findDialogConfirm(snapshot, profile)?.let { return it }
        val generic = snapshot.find(CollectorRules.POPUP_CONTEXT_TERMS + CollectorRules.POPUP_GENERIC_TERMS)
            ?: return null
        return Target(generic.text, generic.bounds, generic.score, generic.source.name.lowercase())
    }

    /**
     * Opens [tab], walking through [AppProfile.tabPaths] first when the tab is
     * a page inside another one (HAHAGO's 任務 sits on 個人, 2026-11-14).
     */
    private suspend fun openTab(tab: String): Boolean {
        if (profile.altChromeTerms.isNotEmpty()) {
            // Leave a sub-page first: it has no tab bar to tap.
            val where = readPlain("tab_${tab}_where")
            if (CollectorRules.onSubPage(where, profile)) {
                recorder.event("leave_sub_page", fields = mapOf("tab" to tab))
                actor.back()
                delay(1_500L)
            }
        }
        val path = profile.tabPaths[tab] ?: run {
            if (openTabStep(tab)) return true
            // LINE left on another bottom tab (精選 > 問卷調查, 2026-11-16/17):
            // its 首頁/步數/時間 live under 集點. Scans found none of them twice.
            if (profile.lineRules && tapLinePointsTab()) return openTabStep(tab)
            return false
        }
        for (step in path) {
            if (!awaitRunning() || !openTabStep(step)) return false
        }
        return true
    }

    private suspend fun openTabStep(tab: String): Boolean {
        if (profile.lineRules) {
            val pre = readPlain("tab_${tab}_overlay")
            if (clearLineOverlays(pre)) delay(800L)
        }
        var snapshot = readPlain("tab_${tab}_before")
        var match = snapshot.find(listOf(tab))
        if (match == null && tab !in profile.tabPoints && profile.tabPaths.values.any { path -> path.indexOf(tab) > 0 }) {
            // A card further down the page (AHEALTH's 步行): from the top,
            // scroll until it shows.
            scrollToTop()
            snapshot = readPlain("tab_${tab}_top")
            match = snapshot.find(listOf(tab))
            var tries = 0
            while (match == null && tries < TAB_SEARCH_SCROLLS && awaitRunning()) {
                tries++
                recorder.event("tab_search_scroll", fields = mapOf("tab" to tab, "page" to tries))
                if (!scrollPage(TabScroll(pages = 1), snapshot)) break
                delay(1_000L)
                snapshot = readPlain("tab_${tab}_search$tries")
                match = snapshot.find(listOf(tab))
            }
        }
        if (match != null) {
            if (!CollectorRules.validTapBounds(match.bounds, snapshot.width, snapshot.height, 0.2)) {
                recorder.event("tab_invalid_bounds", fields = mapOf("tab" to tab))
                return false
            }
            recorder.event(
                "open_tab",
                fields = mapOf(
                    "tab" to tab,
                    "x" to match.centerX,
                    "y" to match.centerY,
                    "source" to match.source
                )
            )
            actor.tap(match.centerX, match.centerY)
        } else {
            if (!CollectorRules.hasAppChrome(snapshot, profile)) {
                recorder.event("tab_not_found_no_chrome", fields = mapOf("tab" to tab))
                return false
            }
            val point = profile.scaledTabPoint(tab, snapshot.width, snapshot.height) ?: run {
                recorder.event("tab_not_found", fields = mapOf("tab" to tab))
                return false
            }
            recorder.event(
                "open_tab_fallback",
                fields = mapOf("tab" to tab, "x" to point.first, "y" to point.second)
            )
            actor.tap(point.first, point.second)
        }
        delay(1_800L)
        if (foreground().packageName != profile.packageName) recoverToApp()
        if (profile.lineRules) {
            val check = readPlain("tab_${tab}_check")
            if (!LineWalkRules.onTab(check, tab)) {
                recorder.event("tab_not_switched", fields = mapOf("tab" to tab))
                clearLineOverlays(check)
                profile.scaledTabPoint(tab, check.width, check.height)?.let { point ->
                    actor.tap(point.first, point.second)
                    delay(1_800L)
                }
            }
        }
        return true
    }

    /**
     * Gives a tab time to render its list.
     *
     * The 2026-11-12 dry run switched to BITWALK's 賺取 tab and read it before
     * anything had loaded (12 nodes, 0 OCR), so the tab looked empty. Re-reading
     * only while the tab still looks empty keeps the cost small on a ready tab.
     */
    private suspend fun awaitTabSettled(tab: String) {
        repeat(TAB_SETTLE_POLLS) { index ->
            if (!awaitRunning()) return
            val snapshot = readPlain("tab_${tab}_settle$index")
            if (CollectorRules.pickReward(snapshot, profile, emptyMap()) != null) return
            if (CollectorRules.hasAppChrome(snapshot, profile) && snapshot.boxes.size >= TAB_SETTLE_MIN_BOXES) return
            delay(1_200L)
        }
    }

    /**
     * Brings the reward app back. Never over an app the user opened: that is
     * a pause until 繼續 instead (2026-11-23 review: Home pressed mid-run used
     * to be answered by relaunching the reward app). [userApproved] is true
     * only once the user pressed 繼續.
     */
    private suspend fun recoverToApp(userApproved: Boolean = false) {
        val fg = foreground()
        if (!userApproved && isUsersApp(fg.packageName)) {
            yieldToUser(fg.packageName)
            return
        }
        val stuckOnRemoteAd = RemoteAdHost.isAdSurface(fg.packageName, fg.activity)
        recorder.event(
            "recover_to_app",
            fields = mapOf(
                "package" to fg.packageName,
                "activity" to fg.activity,
                "fresh" to stuckOnRemoteAd
            )
        )
        if (stuckOnRemoteAd) {
            // Meta's AudienceNetworkRemoteActivity sits on top of the reward
            // app's task; a plain launch brings the stuck ad right back
            // (BITWALK 2026-11-28 21:22). Home + CLEAR_TASK clears the ad.
            actor.home()
            delay(800L)
            actor.launch(profile.packageName, fresh = true)
            actor.waitFor(RECOVER_WAIT_MILLIS) { foreground().packageName == profile.packageName }
            delay(1_500L)
            return
        }
        actor.launch(profile.packageName)
        val returned = actor.waitFor(RECOVER_WAIT_MILLIS) { foreground().packageName == profile.packageName }
        if (!returned) {
            val after = foreground()
            if (RemoteAdHost.isAdSurface(after.packageName, after.activity)) {
                actor.home()
                delay(800L)
                actor.launch(profile.packageName, fresh = true)
                actor.waitFor(RECOVER_WAIT_MILLIS) { foreground().packageName == profile.packageName }
            }
        }
        delay(1_500L)
    }

    // -------------------------------------------------------------- post tap

    private suspend fun afterReward(
        before: Snapshot,
        candidate: Target,
        expectAd: Boolean,
        label: String
    ): String {
        delay(profile.settleWaitMillis)
        val fg = foreground()
        val frame = reader.analyze(fg.packageName, fg.activity) { installRegion(it) }
        try {
            val snapshot = frame.snapshot
            recorder.event(
                "after_tap",
                fields = mapOf(
                    "text" to candidate.text,
                    "package" to snapshot.foregroundPackage,
                    "activity" to snapshot.foregroundActivity,
                    "chrome" to CollectorRules.hasAppChrome(snapshot, profile),
                    "expectAd" to expectAd
                )
            )
            val pkg = snapshot.foregroundPackage?.trim().orEmpty()
            if (profile.lineRules) {
                LineWalkRules.choiceSheet(snapshot)?.let { sheet ->
                    return confirmLineIfNeeded(
                        handleChoiceSheet(sheet, before, label),
                        before,
                        label
                    )
                }
            }
            if (pkg.isNotEmpty() && pkg != profile.packageName && isRemoteAd(pkg, snapshot.foregroundActivity)) {
                // Meta's ad drawn by the Facebook app (BITWALK 2026-11-25): it
                // is this tap's ad, not a redirect and not the user's app.
                actor.authorizeAdHost(pkg)
                recorder.event("ad_detected", message = "remote_ad_host", fields = mapOf("package" to pkg))
                recorder.shot(frame.bitmap, "${label}_ad")
                frame.close()
                return confirmLineIfNeeded(
                    handleAd(assumeAd = false, label = label),
                    before,
                    label
                )
            }
            if (pkg.isNotEmpty() && pkg != profile.packageName) {
                if (isUsersApp(pkg)) {
                    frame.close()
                    yieldToUser(pkg)
                    return "user_left"
                }
                if (CollectorRules.isSystemPackage(pkg)) {
                    recorder.event("system_surface", fields = mapOf("package" to pkg))
                    return "system_surface"
                }
                redirects++
                recorder.event("foreground_other", fields = mapOf("package" to pkg))
                recorder.shot(frame.bitmap, "${label}_redirect")
                if (!awaitRunning()) return "stopped"
                // Judged the ad's redirect just above: Back may go to this app only.
                actor.authorizeBack(pkg)
                actor.back()
                delay(1_500L)
                recoverToApp()
                return "blocked_redirect"
            }

            CollectorRules.refusalText(snapshot)?.let { refusal ->
                // HAHAGO 2026-11-14 11:46: "今日已上傳過相同的步數！" covered
                // 上傳步數, the button seemed gone and was counted as claimed.
                recorder.event("claim_refused", fields = mapOf("text" to candidate.text, "message" to refusal))
                recorder.shot(frame.bitmap, "${label}_refused")
                val close = CollectorRules.findDialogConfirm(snapshot, profile)
                    ?: snapshot.find(CollectorRules.REFUSAL_CLOSE_TERMS)?.let { Target(it.text, it.bounds, it.score, it.source.name.lowercase()) }
                if (close != null) {
                    actor.tap(close.centerX, close.centerY)
                    delay(1_000L)
                }
                return "refused"
            }

            val (isAd, reason) = CollectorRules.adEvidence(snapshot, profile)
            if (isAd) {
                recorder.event("ad_detected", message = reason)
                recorder.shot(frame.bitmap, "${label}_ad")
                // Release the screenshot before the ad loop starts: that loop
                // captures many more frames and holding this one would double
                // the peak bitmap memory for the whole ad.
                frame.close()
                return confirmLineIfNeeded(
                    handleAd(assumeAd = false, label = label),
                    before,
                    label
                )
            }

            val info = CollectorRules.findInfoDialogClose(snapshot, profile)
            if (info != null) {
                recorder.event("info_dialog", fields = mapOf("x" to info.centerX, "y" to info.centerY))
                actor.tap(info.centerX, info.centerY)
                delay(1_200L)
                return "info_dialog"
            }

            if (expectAd && !CollectorRules.hasAppChrome(snapshot, profile) &&
                (pkg.isEmpty() || pkg == profile.packageName)
            ) {
                recorder.event("ad_assumed")
                frame.close()
                return confirmLineIfNeeded(handleAd(assumeAd = true, label = label), before, label)
            }

            if (!CollectorRules.hasAppChrome(snapshot, profile)) {
                val text = snapshot.looseText
                if (profile.pageMarkers.any {
                        val marker = TextMatch.normalizeLoose(it)
                        marker.isNotEmpty() && marker in text
                    }
                ) {
                    recorder.event("content_page_back")
                    actor.back()
                    delay(1_200L)
                    recoverToApp()
                    return "page_back"
                }
                if (CollectorRules.isUnsafeRewardText(snapshot.text)) {
                    recorder.event("blocked_web_page")
                    recorder.shot(frame.bitmap, "${label}_web")
                    actor.back()
                    delay(1_200L)
                    recoverToApp()
                    return "blocked_web_page"
                }
            }

            if (CollectorRules.isDialog(snapshot, profile)) {
                val confirm = CollectorRules.findDialogConfirm(snapshot, profile)
                if (confirm != null) {
                    recorder.event(
                        "confirm_reward",
                        fields = mapOf("text" to confirm.text, "x" to confirm.centerX, "y" to confirm.centerY)
                    )
                    actor.tap(confirm.centerX, confirm.centerY)
                    delay(1_000L)
                    return "confirmed"
                }
            }

            if (expectAd) {
                // A "watch an ad" button on a slow network: the ad is still
                // loading and the app's own screen is up. Tapping again was
                // how BITWALK got five taps for one ad (2026-11-13 14:13).
                frame.close()
                return awaitAdStart(before, label)
            }

            val beforeText = before.looseText
            val afterText = snapshot.looseText
            if (beforeText.isNotEmpty() && beforeText == afterText) return "no_change"
            if (CollectorRules.isDirectClaim(candidate, snapshot, profile, expectAd)) {
                recorder.event("direct_claim", fields = mapOf("text" to candidate.text))
                recorder.shot(frame.bitmap, "${label}_claimed")
                return "claimed"
            }
            // No proof either way (HAHAGO 簽到, AHEALTH 領取 on 2026-11-14):
            // keep the screen so the next analysis can see what the tap did.
            recorder.shot(frame.bitmap, "${label}_changed")
            return "changed"
        } finally {
            frame.close()
        }
    }

    // ------------------------------------------------------------ LINE walk

    /** A button that asks for an ad ("收看廣告影片"): never dropped by its position. */
    private fun expectsAd(target: Target): Boolean =
        profile.adButtonWords.any { TextMatch.normalizeLoose(it) in TextMatch.normalizeLoose(target.text) }

    private fun pickReward(snapshot: Snapshot, attempts: Map<String, Int>, tab: String = ""): Target? =
        CollectorRules.pickReward(snapshot, profile, attempts) { target, screen ->
            CollectorRules.dropKey(target.text) !in droppedButtons &&
                (expectsAd(target) || CollectorRules.dropSpot(target) !in droppedSpots) &&
                (!profile.skipNoRewardRepeat || !deadTargets.isSkipped(tab, target)) &&
                (!profile.lineRules || LineWalkRules.candidateAllowed(target, screen))
        }

    /**
     * True unless the screen is positively the target app. An unknown
     * foreground (no active window, empty package) counts as wrong: deciding
     * on a screen nobody can identify is how other apps get tapped.
     */
    private fun isWrongPackage(snapshot: Snapshot): Boolean =
        snapshot.foregroundPackage?.trim() != profile.packageName

    /** LINE only counts a reward when its dialog appears or the coin counter rises. */
    /**
     * Waits up to [AD_START_WAIT_MILLIS] for the ad a button asked for. The ad
     * appearing (or the app's screen giving way to anything else) goes to the
     * ad loop; the app's "no ad right now" dialog and a reward dialog are
     * handled as before; nothing at all is "ad_not_loaded", which the run
     * retries at the end instead of tapping the button again now.
     */
    private suspend fun awaitAdStart(before: Snapshot, label: String): String {
        val started = activeNow()
        var index = 0
        while (awaitRunning() && activeNow() - started < AD_START_WAIT_MILLIS) {
            delay(AD_START_POLL_MILLIS)
            val snapshot = readPlain("${label}_await_ad_${index++}")
            if (CollectorRules.isUserShade(snapshot.foregroundPackage)) continue
            if (isWrongPackage(snapshot) || CollectorRules.looksLikeAd(snapshot, profile) ||
                !CollectorRules.hasAppChrome(snapshot, profile)
            ) {
                recorder.event("ad_started_late", fields = mapOf("waitedMillis" to activeNow() - started))
                return confirmLineIfNeeded(handleAd(assumeAd = true, label = label), before, label)
            }
            CollectorRules.findInfoDialogClose(snapshot, profile)?.let { info ->
                recorder.event("info_dialog", fields = mapOf("x" to info.centerX, "y" to info.centerY))
                actor.tap(info.centerX, info.centerY)
                delay(1_200L)
                return "info_dialog"
            }
            if (CollectorRules.isDialog(snapshot, profile)) {
                CollectorRules.findDialogConfirm(snapshot, profile)?.let { confirm ->
                    recorder.event("confirm_reward", fields = mapOf("text" to confirm.text))
                    actor.tap(confirm.centerX, confirm.centerY)
                    delay(1_000L)
                    return "confirmed"
                }
            }
        }
        if (!awaitRunning()) return "stopped"
        recorder.event("ad_not_loaded", fields = mapOf("waitedMillis" to activeNow() - started))
        publish("廣告 ${AD_START_WAIT_MILLIS / 1000} 秒內沒有載入，先跳過")
        return "ad_not_loaded"
    }

    /** Counts a finished reward attempt; the other outcomes are handled by the caller. */
    private fun countOutcome(outcome: String) {
        when (outcome) {
            "ad_unconfirmed" -> {
                ads++
                unconfirmed++
                publish("廣告看完，但沒看到獎勵入帳（未計入）")
                recorder.event("reward_unconfirmed", fields = mapOf("count" to unconfirmed))
            }
            "ad_closed", "ad_ended" -> {
                ads++
                rewards++
                publish("已收 $rewards 個獎勵")
                recorder.event("reward_collected", fields = mapOf("kind" to "ad", "count" to rewards))
            }
            "claimed" -> {
                rewards++
                publish("已收 $rewards 個獎勵（直接領取）")
                recorder.event("reward_collected", fields = mapOf("kind" to "direct", "count" to rewards))
            }
            "confirmed" -> {
                rewards++
                publish("已收 $rewards 個獎勵（對話框）")
                recorder.event("reward_collected", fields = mapOf("kind" to "confirmed", "count" to rewards))
            }
        }
    }

    private fun deferReward(tab: String, candidate: Target, outcome: String) {
        val id = AdStuck.deferId(tab, candidate)
        if (deferred.containsKey(id)) return
        deferred[id] = Deferred(id, tab, candidate.text)
        publish("先跳過「${AdStuck.label(candidate.text)}」，最後再試")
        recorder.event("reward_deferred", fields = mapOf("tab" to tab, "text" to candidate.text, "outcome" to outcome))
    }

    /**
     * The second pass over rewards whose ad got stuck: one more try each,
     * after everything else was collected. Still stuck means the user
     * collects it by hand; it is listed instead of retried forever.
     */
    private suspend fun retryDeferred() {
        recorder.event("deferred_retry", fields = mapOf("count" to deferred.size))
        for (item in deferred.values.toList()) {
            if (!awaitRunning()) return
            if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) return
            if (item.tab.isNotEmpty() && !openTab(item.tab)) continue
            awaitTabSettled(item.tab)
            dismissPopups()
            val snapshot = readPlain("deferred_${item.tab.ifEmpty { "home" }}")
            if (isWrongPackage(snapshot)) {
                recoverToApp()
                continue
            }
            val candidate = CollectorRules.pickReward(snapshot, profile, emptyMap()) { target, shot ->
                AdStuck.deferId(item.tab, target) == item.id &&
                    (!profile.lineRules || LineWalkRules.candidateAllowed(target, shot))
            }
            // Handled now either way: it pays again (and is collected with
            // the normal sweeps), or it is gone, or it went to the user.
            deferred.remove(item.id)
            if (candidate == null) {
                // Gone (collected some other way, or no longer offered).
                recorder.event("deferred_gone", fields = mapOf("text" to item.text))
                continue
            }
            retryOne(item, snapshot, candidate)
        }
    }

    /**
     * Retries one deferred button. When it works again it keeps collecting it
     * (a repeatable button may have dozens left); a failure before any success
     * leaves it to the user.
     */
    private suspend fun retryOne(item: Deferred, firstSnapshot: Snapshot, firstCandidate: Target) {
        var snapshot = firstSnapshot
        var candidate: Target? = firstCandidate
        var collected = 0
        var tries = 0
        while (candidate != null && awaitRunning() && tries < profile.maxRepeatPerTarget) {
            if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) return
            tries++
            taps++
            rewardEvidence = false
            val target = candidate
            publish("再試一次 ${AdStuck.label(target.text)}")
            recorder.event("deferred_tap", fields = mapOf("tab" to item.tab, "text" to target.text, "try" to tries))
            if (!actor.tap(target.centerX, target.centerY)) return
            val expectAd = expectsAd(target)
            when (val outcome = afterReward(snapshot, target, expectAd, "deferred_${item.tab.ifEmpty { "home" }}")) {
                "ad_unconfirmed", "ad_closed", "ad_ended", "claimed", "confirmed" -> {
                    countOutcome(outcome)
                    collected++
                }
                "ad_stuck", "ad_timeout", "ad_not_loaded" -> {
                    if (collected == 0) {
                        val line = "${profile.name}：${item.tab.ifEmpty { "首頁" }}「${AdStuck.label(item.text)}」"
                        manual.add(line)
                        publish("無法關閉廣告，留給你手動收：$line")
                        recorder.event("manual_needed", message = line)
                    } else {
                        recorder.event("deferred_stopped", fields = mapOf("collected" to collected))
                    }
                    return
                }
                else -> {
                    recorder.event("deferred_outcome", fields = mapOf("outcome" to outcome))
                    return
                }
            }
            delay(AFTER_REWARD_DELAY_MILLIS)
            // The reward dialog still covers the button after an ad (10:13:58
            // the button could not be found under it and the run ended).
            dismissPopups()
            snapshot = readPlain("deferred_again_${item.tab.ifEmpty { "home" }}")
            if (isWrongPackage(snapshot)) return
            candidate = CollectorRules.pickReward(snapshot, profile, emptyMap()) { t, shot ->
                AdStuck.deferId(item.tab, t) == item.id &&
                    (!profile.lineRules || LineWalkRules.candidateAllowed(t, shot))
            }
        }
    }

    private suspend fun confirmLineIfNeeded(outcome: String, before: Snapshot, label: String): String {
        if (outcome != "ad_closed" && outcome != "ad_ended") return outcome
        if (profile.lineRules) {
            val confirmed = confirmLineReward(before, label)
            if (inBonusChoice) {
                val sawPromo = bonusSawInvitePromo
                inBonusChoice = false
                bonusSawInvitePromo = false
                if (confirmed && !sawPromo) {
                    bonusTracker.recordConfirmed()
                } else {
                    recordBonusFailure(if (sawPromo) "ad_invite_promo" else "ad_reward_not_seen")
                }
            }
            return if (confirmed) outcome else "info_dialog"
        }
        // Every other app: an ended ad is a reward only with proof (review
        // 2026-11-12, P1) - the reward dialog, or the balance going up.
        return if (confirmAppReward(before, label)) outcome else "ad_unconfirmed"
    }

    private fun recordBonusFailure(reason: String) {
        val justDisabled = bonusTracker.recordFailure()
        recorder.event(
            "bonus_unavailable",
            fields = mapOf("failures" to bonusTracker.failures, "reason" to reason)
        )
        if (justDisabled) {
            recorder.event(
                "bonus_disabled_for_run",
                fields = mapOf("failures" to bonusTracker.failures, "reason" to reason)
            )
            recorder.console(
                "[collector] bonus disabled for run after ${bonusTracker.failures} failures ($reason)"
            )
        }
    }

    private suspend fun confirmAppReward(before: Snapshot, label: String): Boolean {
        if (rewardEvidence) {
            recorder.event("reward_confirmed", message = "dialog seen during the ad")
            return true
        }
        repeat(3) { index ->
            if (!awaitRunning()) return false
            val snapshot = readPlain("${label}_confirm_$index")
            if (RewardConfirmation.hasRewardDialog(snapshot, profile)) {
                recorder.event("reward_confirmed", message = "reward dialog")
                CollectorRules.findDialogConfirm(snapshot, profile)?.let { confirm ->
                    actor.tap(confirm.centerX, confirm.centerY)
                    delay(1_200L)
                }
                return true
            }
            if (RewardConfirmation.confirmed(before, snapshot, profile)) {
                recorder.event(
                    "reward_confirmed",
                    message = "balance ${RewardConfirmation.balance(before, profile)} -> " +
                        "${RewardConfirmation.balance(snapshot, profile)}"
                )
                return true
            }
            delay(2_000L)
        }
        recorder.event("reward_not_confirmed", fields = mapOf("balance" to RewardConfirmation.balance(before, profile)))
        return false
    }

    private suspend fun confirmLineReward(before: Snapshot, label: String): Boolean {
        val beforeCoins = LineWalkRules.coinBalance(before)
        // The "獲得15張票券！" dialog was seen inside the ad loop, but its 關閉
        // sat under an ad that was still loading, so the three reads below
        // found no button and the reward was not counted (2026-11-13 00:15).
        // The dialog itself is the proof; its button is only how to leave it.
        var dialogSeen = rewardEvidence
        repeat(LINE_CONFIRM_READS) { index ->
            if (!awaitRunning()) return false
            val snapshot = readPlain("${label}_reward_$index")
            if (RewardConfirmation.hasRewardDialog(snapshot, profile)) dialogSeen = true
            CollectorRules.findDialogConfirm(snapshot, profile)?.let { confirm ->
                recorder.event(
                    "reward_dialog",
                    fields = mapOf("text" to confirm.text, "x" to confirm.centerX, "y" to confirm.centerY)
                )
                actor.tap(confirm.centerX, confirm.centerY)
                delay(1_200L)
                return true
            }
            val coins = LineWalkRules.coinBalance(snapshot)
            if (beforeCoins != null && coins != null && coins > beforeCoins) {
                recorder.event("coin_increase", fields = mapOf("before" to beforeCoins, "after" to coins))
                return true
            }
            delay(2_000L)
        }
        if (dialogSeen) {
            // Counted; the dialog's 關閉 is pressed later by the overlay check.
            recorder.event("reward_dialog_no_button", fields = mapOf("coins" to beforeCoins))
            return true
        }
        recorder.event("ad_reward_not_seen", fields = mapOf("coins" to beforeCoins))
        return false
    }

    /**
     * LINE's bottom sheet: try 紅利 (ad, 4x) first, fall back to the normal
     * reward after repeated failures, never tap 跳過廣告 (it needs an ad-skip
     * pass) and never wait for a cooldown.
     */
    private suspend fun handleChoiceSheet(
        sheet: LineWalkRules.ChoiceSheet,
        before: Snapshot,
        label: String
    ): String {
        var current = sheet
        if (current.bonus != null && bonusTracker.bonusAllowed) {
            recorder.event(
                "choice_bonus",
                fields = mapOf("text" to current.bonus!!.text, "x" to current.bonus!!.centerX, "y" to current.bonus!!.centerY)
            )
            actor.tap(current.bonus!!.centerX, current.bonus!!.centerY)
            delay(profile.settleWaitMillis + 1_000L)
            val after = readPlain("${label}_bonus")
            val still = LineWalkRules.choiceSheet(after)
            if (still == null) {
                // Do NOT reset bonus failures just because the sheet closed:
                // wait until a reward is actually confirmed (2026-11-26).
                inBonusChoice = true
                bonusSawInvitePromo = false
                val choiceOutcome = followChoice(after, label, "bonus_choice", "changed")
                when (choiceOutcome) {
                    "confirmed" -> {
                        inBonusChoice = false
                        bonusSawInvitePromo = false
                        bonusTracker.recordConfirmed()
                    }
                    "ad_closed", "ad_ended" -> {
                        // confirmLineIfNeeded() will inspect reward confirmation and update bonusTracker.
                    }
                    else -> {
                        inBonusChoice = false
                        bonusSawInvitePromo = false
                        recordBonusFailure(choiceOutcome)
                    }
                }
                return choiceOutcome
            }
            recordBonusFailure("sheet_stayed")
            current = still
        }

        val normal = current.normal
        if (normal != null) {
            recorder.event(
                "choice_normal",
                fields = mapOf("text" to normal.text, "x" to normal.centerX, "y" to normal.centerY)
            )
            actor.tap(normal.centerX, normal.centerY)
            delay(profile.settleWaitMillis)
            val after = readPlain("${label}_normal")
            if (LineWalkRules.choiceSheet(after) == null) {
                return followChoice(after, label, "normal_choice", "confirmed")
            }
            return "confirmed"
        }

        current.cancel?.let { cancel ->
            actor.tap(cancel.centerX, cancel.centerY)
            delay(1_200L)
        }
        recorder.event("choice_nothing_usable")
        return "info_dialog"
    }

    /** Watches up to 20 s for the ad that can start 6+ s after an option tap. */
    private suspend fun followChoice(
        after: Snapshot,
        label: String,
        reason: String,
        default: String
    ): String {
        var snapshot = after
        val deadline = activeNow() + CHOICE_AD_START_TIMEOUT_MILLIS
        var index = 0
        // Every wait loop must honour pause/stop; this one did not (review
        // 2026-11-12 #4): a pause kept it reading the screen for 20 s.
        while (awaitRunning()) {
            if (CollectorRules.looksLikeAd(snapshot, profile) ||
                !CollectorRules.hasAppChrome(snapshot, profile)
            ) {
                recorder.event("ad_detected", message = reason)
                return handleAd(assumeAd = true, label = label)
            }
            // "無法載入廣告 / 請稍後再試": no ad behind this choice right now.
            // Close it at once instead of waiting out the 20 s.
            CollectorRules.findInfoDialogClose(snapshot, profile)?.let { info ->
                recorder.event("choice_no_ad_dialog", fields = mapOf("x" to info.centerX, "y" to info.centerY))
                actor.tap(info.centerX, info.centerY)
                delay(1_200L)
                return "info_dialog"
            }
            val confirm = CollectorRules.findDialogConfirm(snapshot, profile)
            if (confirm != null) {
                recorder.event("choice_dialog_confirm", fields = mapOf("text" to confirm.text))
                actor.tap(confirm.centerX, confirm.centerY)
                delay(1_000L)
                return "confirmed"
            }
            if (activeNow() >= deadline) {
                recorder.event("choice_no_ad_started", message = reason)
                return default
            }
            delay(2_500L)
            snapshot = readPlain("${label}_wait_${index++}")
        }
        return "stopped"
    }

    /**
     * Closes overlays LINE leaves on top of the tabs: challenge, reward dialog,
     * lottery result/sheet and the reward choice sheet. Returns true when it
     * acted, so the caller re-reads instead of deciding on a covered screen.
     */
    private suspend fun clearLineOverlays(snapshot: Snapshot): Boolean {
        if (!profile.lineRules) return false
        if (LineWalkRules.isInvitePromo(snapshot)) {
            // Back only: the promotion's one button sends invitations.
            recorder.event("overlay_invite_promo")
            actor.back()
            delay(1_500L)
            return true
        }
        if (LineWalkRules.isChallenge(snapshot)) {
            handleChallenge()
            return true
        }
        CollectorRules.findDialogConfirm(snapshot, profile)?.let { confirm ->
            recorder.event("overlay_reward_dialog", fields = mapOf("text" to confirm.text))
            actor.tap(confirm.centerX, confirm.centerY)
            delay(1_200L)
            return true
        }
        LineWalkRules.lotteryResultClose(snapshot)?.let { close ->
            recorder.event("overlay_lottery_result")
            actor.tap(close.centerX, close.centerY)
            delay(1_500L)
            return true
        }
        if (!profile.lotteryMode && LineWalkRules.lotterySheet(snapshot) != null) {
            recorder.event("overlay_lottery_sheet")
            actor.back()
            delay(1_500L)
            return true
        }
        LineWalkRules.choiceSheet(snapshot)?.cancel?.let { cancel ->
            recorder.event("overlay_choice_sheet")
            actor.tap(cancel.centerX, cancel.centerY)
            delay(1_200L)
            return true
        }
        return false
    }

    /** 突擊挑戰: claim only after FINISH, close after 60 s. */
    private suspend fun handleChallenge() {
        recorder.event("challenge_seen")
        val deadline = activeNow() + CHALLENGE_TIMEOUT_MILLIS
        while (awaitRunning()) {
            val snapshot = readPlain("challenge_wait")
            if (!LineWalkRules.isChallenge(snapshot)) {
                recorder.event("challenge_gone")
                return
            }
            if (LineWalkRules.challengeFinished(snapshot)) {
                val button = LineWalkRules.challengeButton(snapshot)
                if (button != null) {
                    recorder.event(
                        "challenge_claim",
                        fields = mapOf("x" to button.centerX, "y" to button.centerY)
                    )
                    actor.tap(button.centerX, button.centerY)
                    delay(1_500L)
                    val outcome = afterReward(snapshot, button, expectAd = true, label = "challenge")
                    if (outcome == "ad_closed" || outcome == "ad_ended") {
                        ads++
                        rewards++
                        publish("已收 $rewards 個獎勵（突擊挑戰）")
                    }
                    return
                }
            }
            if (activeNow() >= deadline) {
                recorder.event("challenge_timeout")
                actor.back()
                delay(1_500L)
                return
            }
            delay(3_000L)
        }
    }

    // -------------------------------------------------------------------- ads

    private suspend fun handleAd(assumeAd: Boolean, label: String): String {
        val start = activeNow()
        var deadline = start + adTimeoutNow()
        var segmentStart = start
        var adSeen = assumeAd
        var closed = false
        var index = 0
        val triedX = CollectorRules.TriedCloseSpots()
        var nextClicks = 0
        var cornerSkips = 0
        var backPresses = 0
        var installBacks = 0
        var sequenceTotal: Int? = null
        var lastSequenceCurrent: Int? = null
        var sawRewardCountdown = false
        // "No way out" detection: the screen has not changed, shows no
        // countdown, and nothing we tapped moved it. A playable ad ("開始演奏")
        // or a frozen "Ad 1 of 2" used to hold the run for three minutes.
        var lastProgressAt = start
        var lastScreen = ""
        var lastSignature: IntArray? = null
        var playEndCardSince = -1L
        var openStoreSince = -1L
        var openStoreTaps = 0
        var stuck = false
        var remoteHostLogged = false
        var loadingSince = -1L

        while (true) {
            if (!awaitRunning()) return "stopped"
            val now = activeNow()
            if (now >= deadline) {
                if (!adSeen || backPresses >= profile.maxBackPresses) break
                backPresses++
                recorder.event("ad_back", fields = mapOf("count" to backPresses))
                actor.back()
                delay(2_000L)
                deadline = activeNow() + profile.endCardTimeoutMillis
                segmentStart = activeNow() - maxOf(profile.adMinWatchMillis, 4_000L)
                triedX.clearNonRedirect()
            }
            var elapsed = activeNow() - segmentStart
            val fg = foreground()
            val frame = reader.analyze(fg.packageName, fg.activity) { installRegion(it) }
            try {
                val snapshot = frame.snapshot
                if (index == 0) recorder.shot(frame.bitmap, "${label}_ad_first")
                index++
                lastAdFrameAt = activeNow()
                recorder.event(
                    "ad_frame",
                    fields = mapOf(
                        "index" to index,
                        "elapsed" to elapsed,
                        "package" to snapshot.foregroundPackage,
                        "activity" to snapshot.foregroundActivity
                    )
                )

                val pkg = snapshot.foregroundPackage?.trim().orEmpty()
                val remoteAd = pkg.isNotEmpty() && pkg != profile.packageName &&
                    isRemoteAd(pkg, snapshot.foregroundActivity)
                if (remoteAd) {
                    // Another app draws this ad (Meta in Facebook): handle it
                    // like an in-app ad - wait, find its X, Back - instead of
                    // pressing Back on it as a redirect (BITWALK 2026-11-25).
                    actor.authorizeAdHost(pkg)
                    if (!remoteHostLogged) {
                        remoteHostLogged = true
                        recorder.event("ad_remote_host", fields = mapOf("package" to pkg, "activity" to snapshot.foregroundActivity))
                    }
                }
                if (!remoteAd && pkg.isNotEmpty() && pkg != profile.packageName) {
                    if (CollectorRules.isUserShade(pkg)) {
                        // The notification shade / quick settings: the user is
                        // reaching for 暫停 or reading something. Relaunching
                        // the app closed the shade under the user's finger
                        // (2026-11-12 22:29); wait for it to go away instead.
                        recorder.event("shade_open_wait")
                        delay(1_500L)
                        continue
                    }
                    if (isUsersApp(pkg)) {
                        // The user went to another app or home: step aside.
                        if (!yieldToUser(pkg)) return "stopped"
                        continue
                    }
                    if (CollectorRules.isSystemPackage(pkg)) {
                        recorder.event("ad_system_surface", fields = mapOf("package" to pkg))
                        actor.launch(profile.packageName)
                        delay(1_200L)
                        continue
                    }
                    redirects++
                    recorder.event("ad_redirect_blocked", fields = mapOf("package" to pkg))
                    recorder.shot(frame.bitmap, "${label}_ad_redirect")
                    actor.authorizeBack(pkg)
                    actor.back()
                    delay(1_500L)
                    // An ad's end card can open Google Play's install sheet by
                    // itself (2026-11-12, LINE: no tap in 27 s, then
                    // com.android.vending). One Back closes the sheet and the ad
                    // is still underneath; relaunching the app on top of it
                    // dropped the ad and its reward five times in one run. Only
                    // relaunch when Back did not bring the app back.
                    val afterBack = readPlain("${label}_ad_redirect_back")
                    if (!CollectorRunPolicy.backReturnedToApp(afterBack.foregroundPackage, profile.packageName)) {
                        recoverToApp()
                    } else {
                        recorder.event("ad_redirect_closed", fields = mapOf("package" to pkg))
                    }
                    adSeen = true
                    continue
                }

                if (profile.lineRules && LineWalkRules.isLoadingDialog(snapshot)) {
                    val loadingNow = activeNow()
                    if (loadingSince < 0L) {
                        loadingSince = loadingNow
                        recorder.event("line_loading_wait", fields = mapOf("state" to "start"))
                    }
                    val waited = loadingNow - loadingSince
                    if (waited >= profile.lineLoadingWaitMillis) {
                        recorder.event(
                            "line_loading_wait",
                            fields = mapOf("state" to "timeout", "waitedMs" to waited)
                        )
                        loadingSince = -1L
                        cancelLoadingDialog(snapshot, "line_loading_timeout")
                        return "ad_loading_timeout"
                    }
                    // Waiting on LINE's 「載入中…」 dialog: do not tap anything, do not
                    // press Back, and do not count this time toward adMinWatchMillis.
                    segmentStart = loadingNow
                    lastProgressAt = loadingNow
                    deadline = maxOf(deadline, loadingNow + adTimeoutNow())
                    delay(LINE_LOADING_POLL_MILLIS)
                    continue
                }
                if (loadingSince >= 0L) {
                    val leftLoadingAt = activeNow()
                    val waited = leftLoadingAt - loadingSince
                    recorder.event(
                        "line_loading_wait",
                        fields = mapOf("state" to "end", "waitedMs" to waited)
                    )
                    loadingSince = -1L
                    // Reset segmentStart when leaving the loading overlay so the real
                    // ad gets a full adMinWatchMillis watch window.
                    segmentStart = leftLoadingAt
                    elapsed = 0L
                    lastProgressAt = leftLoadingAt
                    deadline = maxOf(deadline, leftLoadingAt + adTimeoutNow())
                }

                if (profile.lineRules && LineWalkRules.isInvitePromo(snapshot)) {
                    // LINE can follow an ad with its full-screen 邀請好友 page
                    // (2026-11-13 20:05); the ad is over. Back only - the page's
                    // one button sends invitations.
                    recorder.event("ad_invite_promo")
                    if (inBonusChoice) bonusSawInvitePromo = true
                    actor.back()
                    delay(1_500L)
                    recoverToApp()
                    return "ad_ended"
                }
                val adNow = CollectorRules.looksLikeAd(snapshot, profile)
                if (adNow) adSeen = true
                if (!sawRewardCountdown &&
                    (CollectorRules.hasMintegralRewardCountdown(snapshot) || CollectorRules.hasAdCountdown(snapshot))
                ) {
                    sawRewardCountdown = true
                }
                // The reward dialog can appear and be closed inside this loop
                // (its 關閉 is also a close target); remember that it was seen.
                if (!adNow && RewardConfirmation.hasRewardDialog(snapshot, profile)) {
                    if (!rewardEvidence) recorder.event("reward_dialog_seen")
                    rewardEvidence = true
                }

                if (adSeen && elapsed >= AD_END_GRACE_MILLIS &&
                    CollectorRules.hasAppChrome(snapshot, profile) && !adNow
                ) {
                    recorder.event("ad_ended", fields = mapOf("elapsed" to elapsed))
                    recorder.shot(frame.bitmap, "${label}_ad_end")
                    return "ad_ended"
                }

                if (adSeen && !sawRewardCountdown && elapsed >= MINTEGRAL_PLACEHOLDER_WAIT_MILLIS &&
                    CollectorRules.isMintegralPlaceholder(snapshot, sawRewardCountdown)
                ) {
                    // 2026-11-25 21:30 BITWALK: grey Mintegral placeholder with no video
                    // or reward countdown; close early and record ad_no_fill instead of
                    // waiting 25-30 s and counting as unconfirmed.
                    noFill++
                    recorder.event(
                        "ad_no_fill",
                        fields = mapOf(
                            "elapsed" to elapsed,
                            "activity" to snapshot.foregroundActivity,
                            "noFill" to noFill
                        )
                    )
                    recorder.shot(frame.bitmap, "${label}_ad_no_fill")
                    val closeTargets = CollectorRules.findCloseTargets(snapshot, profile, frame.corners)
                    var leftAd = false
                    for (target in closeTargets.take(2)) {
                        actor.tap(target.centerX, target.centerY)
                        delay(1_200L)
                        val check = readPlain("${label}_no_fill_x_check")
                        if (check.foregroundPackage == profile.packageName &&
                            !CollectorRules.looksLikeAd(check, profile)
                        ) {
                            leftAd = true
                            break
                        }
                    }
                    if (!leftAd) {
                        actor.back()
                        delay(1_200L)
                        recoverToApp()
                    }
                    return "ad_no_fill"
                }

                val installBack = frame.backArrow
                if (installBack != null && installBacks < profile.maxInstallCardBacks) {
                    installBacks++
                    recorder.event(
                        "install_card_back",
                        fields = mapOf(
                            "x" to installBack.bounds.centerX,
                            "y" to installBack.bounds.centerY,
                            "score" to installBack.score
                        )
                    )
                    actor.tap(installBack.bounds.centerX, installBack.bounds.centerY)
                    delay(1_500L)
                    continue
                }

                val (current, total) = CollectorRules.adSequence(snapshot)
                if (total != null) sequenceTotal = total
                if (current != null && lastSequenceCurrent != null && current != lastSequenceCurrent) {
                    // Multi-ad sequence moved to the next ad (e.g. Meta "Ad 1 of 2"
                    // -> "Ad 2 of 2"): unblock the corner spot so the next ad's
                    // close button at the same coordinate can be tapped.
                    triedX.clearNonRedirect()
                    val seqNow = activeNow()
                    segmentStart = seqNow
                    elapsed = 0L
                    lastProgressAt = seqNow
                    deadline = maxOf(deadline, seqNow + adTimeoutNow())
                    recorder.event(
                        "ad_sequence_advanced",
                        fields = mapOf("from" to lastSequenceCurrent, "to" to current, "total" to sequenceTotal)
                    )
                }
                if (current != null) lastSequenceCurrent = current
                val finalAd = sequenceTotal != null && current != null && current >= sequenceTotal!!

                if (profile.lineRules && adSeen && elapsed >= profile.adMinWatchMillis) {
                    val skip = LineWalkRules.findSkip(snapshot)
                    if (skip != null) {
                        recorder.event("ad_skip", fields = mapOf("x" to skip.centerX, "y" to skip.centerY))
                        actor.tap(skip.centerX, skip.centerY)
                        delay(1_500L)
                        continue
                    }
                }

                if (adSeen && elapsed >= profile.adMinWatchMillis) {
                    val tryNow = activeNow()
                    val targets = CollectorRules.findCloseTargets(snapshot, profile, frame.corners)
                        .filter { !triedX.isBlocked(it.centerX, it.centerY, tryNow) }
                    var tappedAny = false
                    for (target in targets.take(2)) {
                        tappedAny = true
                        recorder.event(
                            "close_ad_x",
                            fields = mapOf(
                                "x" to target.centerX,
                                "y" to target.centerY,
                                "source" to target.source,
                                "score" to target.score
                            )
                        )
                        actor.tap(target.centerX, target.centerY)
                        delay(1_500L)
                        val check = readPlain("${label}_ad_x_check")
                        val checkPkg = check.foregroundPackage?.trim().orEmpty()
                        val causedRedirect = checkPkg.isNotEmpty() &&
                            checkPkg != pkg &&
                            checkPkg != profile.packageName &&
                            !CollectorRules.isSystemPackage(checkPkg)
                        triedX.record(target.centerX, target.centerY, activeNow(), causedRedirect = causedRedirect)
                        if (check.foregroundPackage == profile.packageName &&
                            !CollectorRules.looksLikeAd(check, profile) &&
                            CollectorRules.findCloseTargets(check, profile, emptyList()).isEmpty()
                        ) {
                            closed = true
                            break
                        }
                    }
                    if (closed) break
                    if (tappedAny) continue
                }

                // On Cashzine's own pages the banner's download icon is not a
                // ">>" (2026-11-24: Teams and Google Play were opened from it).
                val cashzinePage = profile.cashzineMode &&
                    CashzineRules.isOwnPage(snapshot.foregroundPackage, snapshot.foregroundActivity)
                if (!cashzinePage && adSeen && nextClicks < profile.maxNextAds && elapsed >= profile.adMinWatchMillis) {
                    val next = CollectorRules.findNextTargets(snapshot, profile, frame.corners)
                    if (next.isNotEmpty()) {
                        val target = next.first()
                        nextClicks++
                        recorder.event(
                            "next_ad",
                            fields = mapOf(
                                "text" to target.text,
                                "x" to target.centerX,
                                "y" to target.centerY,
                                "count" to nextClicks
                            )
                        )
                        actor.tap(target.centerX, target.centerY)
                        delay(1_500L)
                        segmentStart = activeNow()
                        deadline = maxOf(deadline, segmentStart + adTimeoutNow())
                        continue
                    }
                }

                val skipWait = profile.adMinWatchMillis +
                    if (finalAd) 0L else profile.cornerSkipGraceMillis
                if (!cashzinePage && adSeen && elapsed >= skipWait && cornerSkips < profile.maxCornerSkips) {
                    val skip = frame.corners.firstOrNull { it.kind == "next" }
                    if (skip != null) {
                        cornerSkips++
                        recorder.event(
                            "corner_skip",
                            fields = mapOf(
                                "x" to skip.bounds.centerX,
                                "y" to skip.bounds.centerY,
                                "score" to skip.score,
                                "finalAd" to finalAd
                            )
                        )
                        actor.tap(skip.bounds.centerX, skip.bounds.centerY)
                        delay(1_500L)
                        val check = readPlain("${label}_ad_skip_check")
                        if (check.foregroundPackage == profile.packageName &&
                            !CollectorRules.looksLikeAd(check, profile)
                        ) {
                            closed = true
                            break
                        }
                        continue
                    }
                }

                val openStore = CollectorRules.findOpenStoreSkip(snapshot)
                if (openStore != null && openStoreTaps < MAX_OPEN_STORE_TAPS) {
                    if (openStoreSince < 0L) {
                        openStoreSince = activeNow()
                        recorder.event("ad_open_store_seen")
                    } else if (activeNow() - openStoreSince >= OPEN_STORE_GRACE_MILLIS) {
                        // No X came: 開啟商店 is the way on. The store that
                        // opens is closed with Back by the redirect handling
                        // above, which keeps the ad and its reward.
                        openStoreTaps++
                        openStoreSince = -1L
                        recorder.event("ad_open_store_tap", fields = mapOf("count" to openStoreTaps))
                        recorder.shot(frame.bitmap, "${label}_open_store")
                        actor.tap(openStore.bounds.centerX, openStore.bounds.centerY)
                        delay(2_500L)
                        lastProgressAt = activeNow()
                        continue
                    }
                } else if (openStore == null) {
                    openStoreSince = -1L
                }

                if (CollectorRules.isPlayStoreEndCard(snapshot)) {
                    // Only "Skip to Google Play" is left: give an X a few
                    // seconds to appear, then leave instead of waiting two
                    // minutes for nothing (2026-11-14 10:41-10:43).
                    if (playEndCardSince < 0L) {
                        playEndCardSince = activeNow()
                        recorder.event("ad_play_endcard")
                        recorder.shot(frame.bitmap, "${label}_play_endcard")
                    } else if (activeNow() - playEndCardSince >= PLAY_ENDCARD_GRACE_MILLIS) {
                        stuck = true
                        break
                    }
                } else {
                    playEndCardSince = -1L
                }

                val screen = snapshot.looseText
                val signature = frame.bitmap?.let { AdStuck.signature(it) }
                val moving = elapsed < AdStuck.MOTION_PROGRESS_MAX_MILLIS && AdStuck.imageMoved(lastSignature, signature)
                if (signature != null) lastSignature = signature
                if (screen != lastScreen || CollectorRules.hasAdCountdown(snapshot) || moving) {
                    // A playing video counts as progress even with no text
                    // changing (2026-11-14: a countdown-less video was called
                    // stuck); a frozen or idle ad still is not.
                    lastProgressAt = activeNow()
                    lastScreen = screen
                }
                if (AdStuck.isStuck(adSeen, elapsed, profile.adMinWatchMillis, activeNow() - lastProgressAt)) {
                    stuck = true
                    break
                }
                if (!adSeen && elapsed >= NO_AD_BREAK_MILLIS) break
            } finally {
                frame.close()
            }
            delay(profile.pollMillis)
        }

        if (closed) {
            delay(1_200L)
            recoverToApp()
            return "ad_closed"
        }
        if (stuck) {
            timeouts++
            recorder.event(
                "ad_stuck",
                message = if (playEndCardSince >= 0L) "only Skip to Google Play left"
                else "no countdown, no control, no change for ${AdStuck.NO_PROGRESS_MILLIS} ms"
            )
            actor.back()
            delay(1_500L)
            recoverToApp()
            return "ad_stuck"
        }
        if (adSeen) {
            timeouts++
            recorder.event("ad_timeout")
            if (profile.lineRules) handleLineLoadFailure()
            actor.back()
            delay(1_500L)
            recoverToApp()
            return "ad_timeout"
        }
        return "no_ad"
    }

    /**
     * Leaves the app where the user starts: its first tab. A run that ended
     * on a sub-page (AHEALTH's 步行, 2026-11-16 13:39) left the user there;
     * [openTab] presses Back out of a sub-page first.
     */
    private suspend fun returnToFirstTab() {
        val first = profile.collectionTabs.firstOrNull() ?: return
        if (profile.tabPaths[first] != null) return
        val snapshot = readPlain("return_home_check")
        if (isWrongPackage(snapshot)) return
        val onSub = profile.altChromeTerms.isNotEmpty() && CollectorRules.onSubPage(snapshot, profile)
        if (!onSub && profile.collectionTabs.size <= 1) return
        recorder.event("return_to_first_tab", fields = mapOf("tab" to first, "fromSubPage" to onSub))
        openTab(first)
        delay(800L)
    }

    // ----------------------------------------------------------------- scan

    /**
     * Counts what is left to collect (2026-11-15). Reaching the pages takes
     * the same navigation as a run (startup ads, popups, tab taps, scrolls);
     * the rewards themselves are only counted.
     */
    private suspend fun scanApp() {
        ensureReady()
        dismissPopups()
        if (blockedBy != null) {
            manual += blockedBy!!
            available = -1
            return
        }
        if (profile.lotteryMode) {
            val opened = openLottery() ?: if (goToLotteryHome()) openLottery() else null
            val sheet = opened?.let { LineWalkRules.lotterySheet(it) }
            available = sheet?.let { CollectorRules.lotteryAvailable(it) } ?: 0
            // Not found is not "nothing left" (04:09 reported 0 draws).
            if (sheet == null) scanIncomplete = true
            recorder.event("scan_lottery", fields = mapOf("found" to (sheet != null), "available" to available, "counts" to sheet?.counts))
            publish("${profile.name} 掃描：可抽 $available 次")
            return
        }
        val tabs = profile.collectionTabs.ifEmpty { listOf("") }
        for (tab in tabs) {
            if (!awaitRunning()) return
            if (foreground().packageName != profile.packageName) {
                // Another app came up over ours (BITWALK during the AHEALTH
                // scan, 2026-11-16): never look for tabs on its screen.
                recorder.event("wrong_package", fields = mapOf("package" to foreground().packageName))
                recoverToApp()
            }
            if (tab.isNotEmpty() && !openTab(tab)) {
                recorder.event("scan_tab_missing", fields = mapOf("tab" to tab))
                scanIncomplete = true
                continue
            }
            awaitTabSettled(tab)
            dismissPopups()
            val scroll = profile.tabScrolls[tab]
            if (scroll != null && !scroll.horizontal) scrollToTop()
            val pages = ArrayList<List<Target>>()
            while (awaitRunning()) {
                val label = "scan_${tab.ifEmpty { "home" }}_${pages.size}"
                var snapshot = readPlain(label)
                if (isWrongPackage(snapshot)) {
                    recoverToApp()
                    snapshot = readPlain("${label}_recover")
                    if (isWrongPackage(snapshot)) break
                }
                pages += CollectorRules.eligibleRewards(snapshot, profile) { target, screen ->
                    CollectorRules.dropKey(target.text) !in droppedButtons &&
                        (!profile.lineRules || LineWalkRules.candidateAllowed(target, screen))
                }.toList()
                if (scroll == null || pages.size > scroll.pages) break
                if (!scrollPage(scroll, snapshot)) break
                delay(1_200L)
            }
            val count = CollectorRules.scanCount(pages)
            available += count
            recorder.event(
                "scan_tab",
                fields = mapOf("tab" to tab, "available" to count, "labels" to pages.flatten().map { it.text }.distinct())
            )
            publish("${profile.name} 掃描${if (tab.isEmpty()) "" else "「$tab」"}：$count 個")
        }
    }

    // -------------------------------------------------------------- lottery

    /** Dry run: read, wait for the app to settle, decide; never touch the screen. */
    private suspend fun dryRunRead() {
        var snapshot = readPlain("dry_home_0")
        var candidate = pickReward(snapshot, emptyMap())
        var attempt = 1
        // Launching returns when the app's package is in front, which can still
        // be a splash or an empty web view (LINE 散步趣 took >600 ms to render
        // on 2026-11-12). Wait without tapping so the dry run sees the real page.
        while (candidate == null && attempt < DRY_RUN_SETTLE_ATTEMPTS && awaitRunning()) {
            delay(DRY_RUN_SETTLE_WAIT_MILLIS)
            snapshot = readPlain("dry_home_$attempt")
            if (CollectorRules.isUserShade(snapshot.foregroundPackage)) {
                // A dry run never closes the shade the user just pulled down.
                recorder.event("shade_open_wait")
                attempt++
                continue
            }
            if (isWrongPackage(snapshot)) {
                recorder.event("wrong_package", fields = mapOf("package" to snapshot.foregroundPackage))
                recoverToApp()
                snapshot = readPlain("dry_home_${attempt}_recover")
            }
            candidate = pickReward(snapshot, emptyMap())
            attempt++
        }
        if (candidate != null) {
            recorder.console(
                "[DRY-RUN] ${profile.name} would tap '${candidate.text}' " +
                    "@ (${candidate.centerX},${candidate.centerY})"
            )
            recorder.event(
                "dry_run_candidate",
                fields = mapOf(
                    "text" to candidate.text,
                    "x" to candidate.centerX,
                    "y" to candidate.centerY,
                    "source" to candidate.source
                )
            )
        } else {
            recorder.event("no_reward", fields = mapOf("tab" to "dry_home"))
            recorder.console("[DRY-RUN] ${profile.name}: no reward candidate on the launch screen")
        }
        if (profile.lotteryMode) {
            val sheet = LineWalkRules.lotterySheet(snapshot)
            val entry = LineWalkRules.lotteryEntry(snapshot)
            recorder.event(
                "dry_lottery",
                fields = mapOf(
                    "sheet" to (sheet != null),
                    "entry" to (entry != null),
                    "big" to sheet?.big?.text,
                    "free" to sheet?.free?.text,
                    "normal" to sheet?.normal?.text,
                    "counts" to sheet?.counts
                )
            )
        }
        saveCurrentScreen("dry_home")
    }

    /**
     * LINE 抽抽樂: BIG (50 張) → 免費抽 → 一般 (5 張), never waiting for a
     * cooldown and stopping when the normal draw has fewer than 5 tickets.
     */
    private suspend fun collectLottery() {
        // LINE may be left on another page (精選 > 問卷調查 on 2026-11-16 17:05,
        // when the run ended "done" without a draw): find the way back first.
        var snapshot = openLottery() ?: reopenLottery() ?: return
        var draws = 0
        val failures = hashMapOf("big" to 0, "free" to 0, "normal" to 0)
        var loadingTimeouts = 0
        val cooldownSkipped = HashSet<String>()
        var lastNormalFailTexts = ""
        while (awaitRunning() && draws < profile.maxLotteryDraws) {
            if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) {
                recorder.event("lottery_limit", fields = mapOf("rewards" to rewards))
                break
            }
            val sheet = LineWalkRules.lotterySheet(snapshot)
            if (sheet == null) {
                snapshot = reopenLottery() ?: break
                continue
            }
            val buttons = linkedMapOf("big" to sheet.big, "free" to sheet.free, "normal" to sheet.normal)
            for (cdKind in listOf("big", "free")) {
                if (LineWalkRules.isOnCooldown(sheet, cdKind)) {
                    buttons.remove(cdKind)
                    if (cooldownSkipped.add(cdKind)) {
                        val remaining = sheet.cooldowns[cdKind].orEmpty()
                        recorder.event(
                            "lottery_cooldown_skip",
                            fields = mapOf("kind" to cdKind, "remaining" to remaining)
                        )
                        recorder.console("[collector] lottery skip $cdKind (cooldown $remaining)")
                    }
                }
            }
            val lowTickets = LineWalkRules.isReallyTicketsLow(sheet)
            for ((kind, count) in sheet.counts) {
                if (count <= 0 || (kind == "normal" && lowTickets)) buttons.remove(kind)
            }
            val kind = LOTTERY_ORDER.firstOrNull {
                buttons[it] != null && (failures[it] ?: 0) < profile.maxLotteryFailures
            }
            if (kind == null) {
                val normalFailures = failures["normal"] ?: 0
                when (
                    LineWalkRules.lotteryEndReason(
                        sheet = sheet,
                        normalFailures = normalFailures,
                        maxLotteryFailures = profile.maxLotteryFailures,
                        hadCooldownSkip = cooldownSkipped.isNotEmpty()
                    )
                ) {
                    "lottery_tickets_low" -> finishLotteryLow(draws, sheet.counts, normalFailures)
                    "lottery_normal_failed" -> finishLotteryNormalFailed(draws, sheet, normalFailures, lastNormalFailTexts)
                    "lottery_all_cooldown" -> finishLotteryAllCooldown(draws, sheet)
                    else -> {
                        recorder.event(
                            "lottery_done",
                            fields = mapOf("draws" to draws, "counts" to sheet.counts)
                        )
                        recorder.console("[collector] lottery done draws=$draws counts=${sheet.counts}")
                    }
                }
                break
            }
            val target = buttons[kind] ?: break
            if (isWrongPackage(snapshot)) {
                // Never draw on a screen that is not LINE's.
                val pkg = snapshot.foregroundPackage
                if (isUsersApp(pkg)) {
                    if (!yieldToUser(pkg)) break
                } else {
                    recoverToApp()
                }
                snapshot = readPlain("lottery_after_wrong_app")
                continue
            }
            recorder.event(
                "lottery_draw",
                fields = mapOf(
                    "kind" to kind,
                    "text" to target.text,
                    "x" to target.centerX,
                    "y" to target.centerY
                )
            )
            recorder.console("[collector] lottery $kind '${target.text}' @ (${target.centerX},${target.centerY})")
            actor.tap(target.centerX, target.centerY)
            taps++
            draws++

            val waitResult = waitLotteryResult(draws, kind)
            if (waitResult is LotteryWaitResult.LoadingTimeout) {
                loadingTimeouts++
                recorder.event(
                    "lottery_ad_loading_timeout",
                    fields = mapOf("kind" to kind, "count" to loadingTimeouts)
                )
                if (loadingTimeouts >= MAX_LOTTERY_LOADING_TIMEOUTS) {
                    val currentSheet = LineWalkRules.lotterySheet(readPlain("lottery_ad_unavail")) ?: sheet
                    finishLotteryAdUnavailable(draws, currentSheet, loadingTimeouts)
                    break
                }
                // First loading timeout: follow LINE's dialog hint ("請嘗試重新啟動應用程式")
                // via Home + relaunch, then reopen the lottery sheet and retry.
                handleLineLoadFailure(forceRestart = true)
                snapshot = reopenLottery() ?: break
                continue
            }
            if (waitResult !is LotteryWaitResult.Prize) {
                // A BIG or 免費 button that gives nothing is counting down: one
                // try is enough (22:43, two wasted 26 s tries each). Only the
                // repeatable 5 張 draw gets a second chance.
                failures[kind] = if (LineWalkRules.isOncePerCooldown(kind)) {
                    profile.maxLotteryFailures
                } else {
                    (failures[kind] ?: 0) + 1
                }
                recorder.event("lottery_no_result", fields = mapOf("kind" to kind, "failures" to failures[kind]))
                // What the screen said when the draw was given up, so the next
                // unrecognised prize or sheet can be diagnosed from the record.
                val seen = readPlain("lottery_fail_screen")
                val failTexts = seen.boxes.filter { it.source == TextSource.OCR }
                    .joinToString(" | ") { it.text.replace('\n', ' ') }
                    .take(600)
                if (kind == "normal") lastNormalFailTexts = failTexts
                recorder.event("lottery_fail_texts", message = failTexts)
                saveCurrentScreen("lottery_fail")
                val after = readPlain("lottery_after_fail")
                val afterSheet = LineWalkRules.lotterySheet(after)
                if (kind == "normal" && LineWalkRules.isReallyTicketsLow(afterSheet, seen)) {
                    // The badge was hard to read (or the tap was already made):
                    // the sheet itself says the 5 tickets are not there.
                    finishLotteryLow(draws, afterSheet?.counts ?: emptyMap(), failures["normal"] ?: 0)
                    break
                }
                snapshot = if (afterSheet != null) {
                    after
                } else {
                    reopenLottery() ?: break
                }
                continue
            }
            val result = waitResult.snapshot
            loadingTimeouts = 0

            // BIG and 免費 are one draw per countdown; after a prize the button
            // stays on screen but does nothing, and two more taps were wasted
            // as "failures" on 2026-11-12. Never wait for the countdown:
            // move on to the next kind at once.
            failures[kind] = if (LineWalkRules.isOncePerCooldown(kind)) profile.maxLotteryFailures else 0
            val prize = result.boxes.firstOrNull {
                LineWalkRules.isPrizeText(it.text)
            }?.text.orEmpty()
            recorder.event("lottery_prize", fields = mapOf("kind" to kind, "prize" to prize))
            rewards++
            publish("抽抽樂：$prize（已收 $rewards）")
            val close = LineWalkRules.lotteryResultClose(result)
            if (close != null) {
                actor.tap(close.centerX, close.centerY)
            } else {
                // Prize without a 關閉 button: Back leaves the result sheet.
                actor.back()
            }
            delay(1_500L)
            val after = readPlain("lottery_sheet_after")
            snapshot = if (LineWalkRules.lotterySheet(after) != null) {
                after
            } else {
                openLottery() ?: break
            }
        }
    }

    /**
     * Fewer than 5 tickets (or two normal draws with no result): LINE cannot
     * draw again. The caller goes home and stops the guard.
     */
    private fun finishLotteryLow(draws: Int, counts: Map<String, Int>, normalFailures: Int) {
        lotteryEndReason = "lottery_tickets_low"
        val tickets = counts["normal"]
        recorder.event(
            "lottery_tickets_low",
            fields = mapOf(
                "draws" to draws,
                "tickets" to tickets,
                "counts" to counts,
                "normalFailures" to normalFailures
            )
        )
        recorder.console(
            "[collector] lottery tickets low: normal=$tickets draws=$draws failures=$normalFailures"
        )
        val detail = when {
            tickets != null ->
                "一般券剩 $tickets 張（不足 ${LineWalkRules.LOTTERY_NORMAL_TICKETS} 張）"
            normalFailures >= 2 ->
                "連續 $normalFailures 次抽取無結果（票券可能不足 " +
                    "${LineWalkRules.LOTTERY_NORMAL_TICKETS} 張）"
            else -> "抽獎券不足 ${LineWalkRules.LOTTERY_NORMAL_TICKETS} 張"
        }
        publish("抽抽樂：$detail，結束")
    }

    /**
     * Normal draw failed repeatedly even though plenty of draws remain (2026-11-26):
     * end this lottery run without stopping the guard.
     */
    private fun finishLotteryNormalFailed(
        draws: Int,
        sheet: LineWalkRules.LotterySheet,
        normalFailures: Int,
        failTexts: String
    ) {
        lotteryEndReason = "lottery_normal_failed"
        val availableDraws = LineWalkRules.normalDrawsAvailable(sheet)
        recorder.event(
            "lottery_normal_failed",
            message = failTexts.ifBlank { null },
            fields = mapOf(
                "draws" to draws,
                "availableDraws" to availableDraws,
                "tickets" to sheet.counts["normal"],
                "counts" to sheet.counts,
                "normalFailures" to normalFailures
            )
        )
        recorder.console(
            "[collector] lottery normal failed: availableDraws=$availableDraws draws=$draws failures=$normalFailures"
        )
        publish("抽抽樂：一般抽連續失敗 $normalFailures 次（尚可抽 ${availableDraws ?: "?"} 次），結束")
    }

    /**
     * BIG and 免費 are both cooling down and normal cannot draw: end without
     * stopping the guard (2026-11-26).
     */
    private fun finishLotteryAllCooldown(draws: Int, sheet: LineWalkRules.LotterySheet) {
        lotteryEndReason = "lottery_all_cooldown"
        recorder.event(
            "lottery_all_cooldown",
            fields = mapOf(
                "draws" to draws,
                "cooldowns" to sheet.cooldowns,
                "counts" to sheet.counts
            )
        )
        recorder.console(
            "[collector] lottery all cooldown: draws=$draws cooldowns=${sheet.cooldowns}"
        )
        publish("抽抽樂：BIG／免費冷卻中，結束")
    }

    /**
     * Consecutive ad-loading timeouts on LINE's 「載入中…」 dialog (2026-11-27):
     * end this lottery run without stopping the guard so the user can retry later.
     */
    private fun finishLotteryAdUnavailable(
        draws: Int,
        sheet: LineWalkRules.LotterySheet,
        loadingTimeouts: Int
    ) {
        lotteryEndReason = "lottery_ad_unavailable"
        val availableDraws = LineWalkRules.normalDrawsAvailable(sheet)
        recorder.event(
            "lottery_ad_unavailable",
            fields = mapOf(
                "draws" to draws,
                "availableDraws" to availableDraws,
                "tickets" to sheet.counts["normal"],
                "counts" to sheet.counts,
                "loadingTimeouts" to loadingTimeouts
            )
        )
        recorder.console(
            "[collector] lottery ad unavailable: availableDraws=$availableDraws draws=$draws loadingTimeouts=$loadingTimeouts"
        )
        publish("抽抽樂：LINE 廣告載入不到，稍後再試（尚可抽 ${availableDraws ?: "?"} 次）")
    }

    private suspend fun openLottery(): Snapshot? {
        var snapshot = readPlain("lottery_open_before")
        if (LineWalkRules.lotterySheet(snapshot) != null) return snapshot
        val entry = LineWalkRules.lotteryEntry(snapshot) ?: run {
            recorder.event("lottery_entry_not_found")
            return null
        }
        recorder.event(
            "lottery_open",
            fields = mapOf("x" to entry.centerX, "y" to (entry.centerY - LOTTERY_ENTRY_OFFSET_PX))
        )
        actor.tap(entry.centerX, (entry.centerY - LOTTERY_ENTRY_OFFSET_PX).coerceAtLeast(0))
        taps++
        delay(2_000L)
        snapshot = readPlain("lottery_open")
        repeat(2) { index ->
            if (LineWalkRules.lotterySheet(snapshot) != null) return snapshot
            delay(3_000L)
            snapshot = readPlain("lottery_open_retry${index + 1}")
        }
        return snapshot.takeIf { LineWalkRules.lotterySheet(it) != null }
    }

    /**
     * Back to the lottery sheet after a draw lost it (2026-11-16 15:29: an
     * InMobi ad with no text and a lone X sat over LINE, the entry could not
     * be found, and the run ended "done" at 32 of 50 draws). An ad on screen
     * is closed like any ad; failing that, LINE is opened again once.
     */
    private suspend fun reopenLottery(): Snapshot? {
        // Stopped (or trapped) mid-draw: that is not a lost lottery.
        if (!awaitRunning()) return null
        val now = readPlain("lottery_reopen_check")
        if (CollectorRules.looksLikeAd(now, profile)) {
            recorder.event("lottery_leftover_ad")
            handleAd(assumeAd = true, label = "lottery_leftover")
        }
        openLottery()?.let { return it }
        if (goToLotteryHome()) openLottery()?.let { return it }
        if (cleanRestarts < MAX_CLEAN_RESTARTS && awaitRunning()) {
            restartAppClean("lottery_lost")
            openLottery()?.let { return it }
        }
        if (!awaitRunning()) return null
        lotteryLost = true
        recorder.event("lottery_lost")
        return null
    }

    /** LINE's 集點 (bottom bar) and then its 首頁 tab, where the lottery entry is. */
    private suspend fun goToLotteryHome(): Boolean {
        val tapped = tapLinePointsTab()
        recorder.event("lottery_go_home_tab")
        val ok = openTab("首頁")
        delay(1_500L)
        return ok || tapped
    }

    /**
     * InMobi's ads are the ones that hang (2026-11-17: 109 of the run's ad
     * frames, one stuck for two minutes before the app was restarted): give
     * them 60 s instead of the profile's 120 s.
     */
    private fun adTimeoutNow(): Long {
        val activity = foreground().activity.orEmpty()
        return if (activity.contains("inmobi", ignoreCase = true)) {
            minOf(profile.adTimeoutMillis, INMOBI_AD_TIMEOUT_MILLIS)
        } else {
            profile.adTimeoutMillis
        }
    }

    /** LINE 散步趣's 集點 in the bottom bar, where its own tabs and the lottery are. */
    private suspend fun tapLinePointsTab(): Boolean {
        val snapshot = readPlain("line_points_check")
        if (isWrongPackage(snapshot)) return false
        val bottom = (snapshot.height * 0.85).toInt()
        val points = snapshot.boxes.firstOrNull {
            it.bounds.top >= bottom && TextMatch.normalizeLoose(it.text) == "集點"
        } ?: return false
        recorder.event("line_go_points_tab")
        actor.tap(points.bounds.centerX, points.bounds.centerY)
        taps++
        delay(1_800L)
        return true
    }

    private sealed class LotteryWaitResult {
        data class Prize(val snapshot: Snapshot) : LotteryWaitResult()
        data object LoadingTimeout : LotteryWaitResult()
        data object NoResult : LotteryWaitResult()
    }

    /** Watches through ads, SKIP and the draw animation for the prize sheet. */
    private suspend fun waitLotteryResult(draws: Int, kind: String): LotteryWaitResult {
        val start = activeNow()
        var index = 0
        var prizeSeenAt = -1L
        var sheetSince = -1L
        while (awaitRunning() &&
            activeNow() - start < LOTTERY_RESULT_TIMEOUT_MILLIS
        ) {
            delay(2_500L)
            val snapshot = readPlain("lottery_${kind}_${draws}_${index++}")
            val fg = snapshot.foregroundPackage?.trim().orEmpty()
            if (isUsersApp(fg)) {
                // The user opened another app (ours included) mid-draw: its
                // text is not LINE's prize sheet.
                if (!yieldToUser(fg)) return LotteryWaitResult.NoResult
                continue
            }
            val onLine = fg.isEmpty() || fg == profile.packageName
            if (onLine && LineWalkRules.lotteryResultClose(snapshot) != null) return LotteryWaitResult.Prize(snapshot)
            if (onLine && LineWalkRules.isLotteryPrize(snapshot)) {
                // The prize is on screen but its 關閉 is not (yet). Give the
                // button a moment, then take the prize anyway (review P2-8).
                if (prizeSeenAt < 0L) prizeSeenAt = activeNow()
                if (activeNow() - prizeSeenAt >= LOTTERY_PRIZE_CLOSE_WAIT_MILLIS) {
                    recorder.event("lottery_prize_without_close")
                    return LotteryWaitResult.Prize(snapshot)
                }
                continue
            }

            val pkg = snapshot.foregroundPackage?.trim().orEmpty()
            if ((pkg.isNotEmpty() && pkg != profile.packageName) ||
                CollectorRules.looksLikeAd(snapshot, profile)
            ) {
                val adOutcome = handleAd(assumeAd = false, label = "lottery_${kind}_$draws")
                if (adOutcome == "ad_loading_timeout") return LotteryWaitResult.LoadingTimeout
                continue
            }
            val skip = LineWalkRules.findSkip(snapshot)
            if (skip != null) {
                recorder.event("lottery_skip")
                actor.tap(skip.centerX, skip.centerY)
                continue
            }

            val elapsed = activeNow() - start
            val onSheet = LineWalkRules.lotterySheet(snapshot) != null
            // "Stuck on the sheet" is measured from when the sheet came back,
            // not from the tap: an ad before the draw used to use up the 20 s
            // and the animation still running was called a failure (22:34).
            sheetSince = if (onSheet) (if (sheetSince < 0L) activeNow() else sheetSince) else -1L
            val isResult = LineWalkRules.isPrizeText(snapshot.text) || LineWalkRules.lotteryResultClose(snapshot) != null
            if (!isResult) {
                // A draw can open an in-app Google Play page (momo 購物 on
                // 2026-11-12). It keeps the LINE package, so the foreground
                // check cannot catch it; close it and go back to the sheet.
                val looksStore = LineWalkRules.looksLikeStorePage(snapshot)
                if (looksStore || (elapsed >= LOTTERY_SHEET_STUCK_MILLIS &&
                        !onSheet && !CollectorRules.hasAppChrome(snapshot, profile))
                ) {
                    recorder.event(
                        "lottery_store_page",
                        message = if (looksStore) "store_marker" else "no_chrome_no_sheet"
                    )
                    saveCurrentScreen("lottery_store_$draws")
                    val close = CollectorRules.findCloseTargets(snapshot, profile, emptyList()).firstOrNull()
                    if (close != null) {
                        recorder.event("lottery_store_close", fields = mapOf("source" to close.source))
                        actor.tap(close.centerX, close.centerY)
                    } else {
                        actor.back()
                    }
                    delay(1_500L)
                    continue
                }
            }
            if (onSheet && activeNow() - sheetSince >= LOTTERY_SHEET_STUCK_MILLIS) {
                cancelLoadingDialog(snapshot, "lottery_loading_cancel")
                return LotteryWaitResult.NoResult
            }
        }
        return LotteryWaitResult.NoResult
    }

    /** LINE's stuck-ad recovery: cancel 載入中, then Home + relaunch (no force-stop). */
    /**
     * Another app in front that the user opened, not an ad: anything but the
     * store, a browser, or an app that came up right after our own action.
     * The shade is handled elsewhere (the run waits for it).
     */
    private fun isUsersApp(foreground: String?): Boolean {
        val pkg = foreground?.trim().orEmpty()
        if (pkg.isEmpty() || pkg == profile.packageName || CollectorRules.isUserShade(pkg)) return false
        // Our own screen is always the user looking at the run (2026-11-15
        // 03:15: its recent-messages list was read as a lottery prize).
        if (pkg == service.packageName) return true
        // The home screen or Settings is the user leaving, unless the run
        // pressed Home itself a moment ago (2026-11-23 review: Home during an
        // ad used to relaunch the reward app over the user's launcher).
        if (isHomeScreen(pkg) || pkg == CollectorRules.SETTINGS_PACKAGE) {
            return !actor.pressedHomeWithin(OWN_HOME_GRACE_MILLIS)
        }
        // A finger after our last action makes the app the user's, even right
        // after an ad (review 2026-11-23: switching apps from the shade or
        // Recents during an ad was taken for the ad's redirect and got Back).
        if (userTouchedSinceAction()) return true
        // Meta's ad in the Facebook app is the ad, not the user's Facebook.
        if (RemoteAdHost.isAdSurface(pkg, foreground().activity)) return false
        // An ad was on screen a moment ago: the app that replaced it is the
        // ad's own redirect (BITWALK 2026-11-17 08:17: ShopBack opened by an
        // ad paused the run as if the user had switched apps).
        if (lastAdFrameAt > 0L && activeNow() - lastAdFrameAt < AD_REDIRECT_GRACE_MILLIS) return false
        return !RedirectJudge.isAdRedirect(pkg, actor.sinceLastActionMillis())
    }

    /** A real finger touched the screen after the run's last action. */
    private fun userTouchedSinceAction(): Boolean =
        actor.lastActionWallAt > 0L && environment.lastUserInteractionAt() > actor.lastActionWallAt

    /** Another app drawing this run's ad ([RemoteAdHost]), with no finger since our tap. */
    private fun isRemoteAd(pkg: String?, activity: String?): Boolean =
        RemoteAdHost.isAdSurface(pkg, activity) && !userTouchedSinceAction()

    /**
     * Pauses the run the way 暫停 does - no Back, no relaunch - until the
     * user presses 繼續; then reopens the reward app. False when the run was
     * stopped instead.
     */
    private suspend fun yieldToUser(foreground: String?): Boolean {
        recorder.event("user_switched_app", fields = mapOf("package" to foreground))
        publish("你正在使用其他 App，已暫停；按「繼續」再回到 ${profile.name}")
        environment.pause(CollectorInterruption.REASON_USER_LEFT)
        if (!awaitRunning()) return false
        recoverToApp(userApproved = true)
        return true
    }

    private suspend fun restartAppClean(why: String) {
        // Same rule as recoverToApp: never Home + relaunch over the user's app.
        val fg = foreground().packageName
        if (isUsersApp(fg)) {
            if (!yieldToUser(fg)) return
        }
        cleanRestarts++
        recorder.event("app_restart_clean", fields = mapOf("count" to cleanRestarts, "why" to why))
        publish("廣告退不出去，重新開啟 ${profile.name}（第 $cleanRestarts 次）")
        actor.home()
        delay(800L)
        actor.launch(profile.packageName, fresh = true)
        actor.waitFor(profile.launchWaitMillis) { foreground().packageName == profile.packageName }
        delay(2_500L)
        ensureReady()
        dismissPopups()
    }

    /** One swipe that brings the next part of the page into view. */
    private suspend fun scrollPage(spec: TabScroll, snapshot: Snapshot): Boolean {
        val w = snapshot.width
        val h = snapshot.height
        return if (spec.horizontal) {
            val y = (h * spec.atY).toInt()
            actor.swipe((w * 0.85).toInt(), y, (w * 0.15).toInt(), y)
        } else {
            // Right of centre: the floating panel sits on the left edge.
            val x = (w * 0.7).toInt()
            actor.swipe(x, (h * 0.72).toInt(), x, (h * 0.32).toInt())
        }
    }

    /** Back to the top of a scrolling page before collecting it. */
    private suspend fun scrollToTop() {
        val w = foregroundSize()
        repeat(SCROLL_TOP_SWIPES) {
            if (!awaitRunning()) return
            val x = (w.first * 0.7).toInt()
            actor.swipe(x, (w.second * 0.3).toInt(), x, (w.second * 0.8).toInt(), 300L)
            delay(350L)
        }
        delay(800L)
    }

    private fun foregroundSize(): Pair<Int, Int> {
        val metrics = service.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    /**
     * AHEALTH's "2p 已領取!" sheet offers 2倍點數 (an ad) and 完成. Press 完成
     * so the next button is not hidden behind it; never the ad offer.
     */
    private suspend fun closeDoneSheet(): Boolean {
        if (profile.doneTerms.isEmpty() || !awaitRunning()) return false
        val snapshot = readPlain("done_check")
        val done = CollectorRules.findDone(snapshot, profile) ?: return false
        val received = CollectorRules.isRewardReceivedSheet(snapshot)
        recorder.event("done_sheet", fields = mapOf("text" to done.text, "received" to received))
        actor.tap(done.bounds.centerX, done.bounds.centerY)
        delay(1_000L)
        return received
    }

    /** LINE's 「載入中」 dialog: press its 取消 (logged as [event]); true when pressed. */
    private suspend fun cancelLoadingDialog(snapshot: Snapshot, event: String): Boolean {
        if ("載入中" !in snapshot.looseText) return false
        val cancel = snapshot.boxes.firstOrNull { TextMatch.normalizeLoose(it.text) == "取消" } ?: return false
        recorder.event(event)
        actor.tap(cancel.bounds.centerX, cancel.bounds.centerY)
        delay(1_500L)
        return true
    }

    private suspend fun handleLineLoadFailure(forceRestart: Boolean = false) {
        cancelLoadingDialog(readPlain("load_fail"), "loading_cancel")
        loadFailures++
        if ((!forceRestart && loadFailures < 2) || restarts >= 2) return
        restarts++
        loadFailures = 0
        recorder.event("app_restart", fields = mapOf("count" to restarts))
        actor.home()
        delay(1_000L)
        actor.launch(profile.packageName)
        delay(profile.launchWaitMillis)
        ensureReady()
    }

    // ------------------------------------------------------------ Cashzine

    /** Wall time when the hourly +60 ad can be watched again; 0 = unknown. */
    private var czWatchDueAt = 0L

    /** Reading coins claimed today, kept across runs (cap 1000 a day). */
    private fun czReadingPrefs() = service.getSharedPreferences("cashzine", android.content.Context.MODE_PRIVATE)
    private fun czReadingKey() = "reading_" + java.time.LocalDate.now().toString()
    private fun czReadingToday(): Int = czReadingPrefs().getInt(czReadingKey(), 0)
    private fun czAddReading(amount: Int) {
        val prefs = czReadingPrefs()
        val key = czReadingKey()
        val total = prefs.getInt(key, 0) + amount
        // Old days are dropped so the file never grows.
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("reading_") && it != key }.forEach { edit.remove(it) }
        edit.putInt(key, total).apply()
        recorder.event("cz_reading_total", fields = mapOf("added" to amount, "today" to total))
    }

    /** "Video is not available" dialogs this run; from 2 on, no more ad items. */
    private var czNoVideo = 0
    private fun czVideosOut(): Boolean = czNoVideo >= NO_VIDEO_LIMIT

    /** Presses Confirm on Cashzine's "Video is not available"; true when it was up. */
    private suspend fun czCloseNoVideo(s: Snapshot, label: String): Boolean {
        val confirm = CashzineRules.videoUnavailable(s) ?: return false
        czNoVideo++
        recorder.event("cz_no_video", fields = mapOf("label" to label, "count" to czNoVideo))
        publish("CASHZINE：目前沒有廣告可看（第 $czNoVideo 次）")
        if (confirm.source == "no_video_back") actor.back() else czTap(confirm, "cz_no_video_confirm")
        delay(1_200L)
        return true
    }

    /**
     * Cashzine's own run (2026-11-23): check-in, the hourly +60 ad, Lucky Draw
     * (in its four sessions), the floating home coins with their double ad,
     * then reading, then +60 again if its hour passed while reading.
     * One step failing never stops the others; each ends on the home tab.
     */
    private suspend fun collectCashzine() {
        val steps: List<Pair<String, suspend () -> Unit>> = listOf(
            "checkin" to { czCheckIn() },
            "watch60" to { czWatch60() },
            "lucky" to { czLuckyDraw() },
            "coins" to { czCoins() },
            "reading" to { czReading() },
            "shorts" to { czShorts() },
            "extra" to { czExtraRewards() },
            "watch60_again" to { czWatch60() }
        )
        val only = CashzineRunOptions.steps
        if (only != null) recorder.event("cz_steps_only", fields = mapOf("steps" to only.joinToString(",")))
        for ((name, step) in steps) {
            if (only != null && name !in only && name.removeSuffix("_again") !in only) continue
            if (!awaitRunning() || adTrapped) break
            if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) {
                recorder.event("cz_limit", fields = mapOf("rewards" to rewards))
                break
            }
            recorder.event("cz_step", fields = mapOf("step" to name, "rewards" to rewards))
            try {
                step()
            } catch (t: Throwable) {
                if (t is CancellationException || t is Error) throw t
                errors++
                recorder.event("cz_step_error", message = "$name ${t::class.java.simpleName}: ${t.message}")
                saveCurrentScreen("cz_${name}_error")
            }
            if (!awaitRunning() || adTrapped) break
            czHome("cz_after_$name")
        }
    }

    /**
     * A real ad on screen: an ad SDK's Activity, or another app in front.
     * Text evidence alone is not enough on Cashzine: its pages carry "AD"
     * banners, and its dialogs have an X.
     */
    private fun czRealAd(s: Snapshot): Boolean =
        (isWrongPackage(s) && !CollectorRules.isUserShade(s.foregroundPackage)) ||
            com.rewardadguard.app.detector.AdActivityClassifier.classify(s.foregroundActivity, profile.packageName) ==
            com.rewardadguard.app.detector.AdActivityClassifier.Kind.AD

    /** A random wait, so the taps do not come at a machine's even pace. */
    private suspend fun czPause(minMillis: Long, maxMillis: Long) {
        delay(kotlin.random.Random.nextLong(minMillis, maxMillis + 1))
    }

    /** The screen, made sure to be Cashzine's; null when it could not be. */
    private suspend fun czScreen(label: String, ocr: Boolean = true): Snapshot? {
        if (!awaitRunning()) return null
        var snapshot = czRead(label, ocr)
        if (isWrongPackage(snapshot) && CollectorRules.isUserShade(snapshot.foregroundPackage)) {
            delay(1_500L)
            snapshot = czRead("${label}_shade", ocr)
        }
        if (isWrongPackage(snapshot)) {
            val pkg = snapshot.foregroundPackage
            if (isUsersApp(pkg)) {
                if (!yieldToUser(pkg)) return null
            } else {
                recorder.event("cz_wrong_package", fields = mapOf("package" to pkg))
                recoverToApp()
            }
            snapshot = czRead("${label}_back", ocr)
            if (isWrongPackage(snapshot)) return null
        }
        return snapshot
    }

    private suspend fun czRead(label: String, ocr: Boolean): Snapshot {
        if (ocr) return readPlain(label)
        // The reading page is FLAG_SECURE: its screenshot is black, so only the
        // node tree is read there (and no OCR time is spent on every swipe).
        val fg = foreground()
        val snapshot = reader.read(fg.packageName, fg.activity, includeOcr = false)
        recorder.event(
            "snapshot",
            fields = mapOf("label" to label, "package" to snapshot.foregroundPackage, "node" to snapshot.nodeCount())
        )
        return snapshot
    }

    private suspend fun czTap(target: Target, event: String): Boolean {
        recorder.event(event, fields = mapOf("text" to target.text, "x" to target.centerX, "y" to target.centerY))
        taps++
        val ok = actor.tap(target.centerX, target.centerY)
        if (!ok) recorder.event("tap_failed", fields = mapOf("text" to target.text))
        return ok
    }

    /**
     * Closes an "Awarded" dialog: "OK" when it has one (check-in), else Back
     * once its 1 s countdown has turned into the X. Extra-ad offers in it
     * ("Win 51 coins", "Watch videos + 26 coins") are never taken.
     */
    private suspend fun czCloseAwarded(label: String): Boolean {
        repeat(4) { attempt ->
            val snapshot = czScreen("${label}_close_$attempt") ?: return false
            if (!CashzineRules.awarded(snapshot)) return true
            if (attempt == 0) delay(1_500L)
            val ok = CashzineRules.okButton(snapshot)
            if (ok != null) {
                czTap(ok, "cz_award_ok")
            } else {
                recorder.event("cz_award_back", fields = mapOf("attempt" to attempt))
                actor.back()
            }
            delay(1_500L)
        }
        return false
    }

    /**
     * An Awarded dialog is up: take its one-more-ad offer ("Win 51 coins",
     * "Watch videos + 26 coins", "Win double rewards"; user, 2026-11-24),
     * else close it.
     */
    private suspend fun czAfterAward(label: String) {
        val dialog = czScreen("${label}_award") ?: return
        val offer = CashzineRules.extraAdOffer(dialog)?.takeIf { !czVideosOut() }
        if (offer == null || !CashzineRules.awarded(dialog)) {
            czCloseAwarded(label)
            return
        }
        publish("CASHZINE：再看一支廣告（${offer.text}）")
        if (czTap(offer, "cz_extra_offer")) czAdReward("${label}_extra", dialog, depth = 1)
        // Whatever the extra ad did, leave no dialog behind.
        czCloseAwarded("${label}_extra_end")
    }

    /** Back to the home tab, out of a dialog, panel, book or web page. */
    private suspend fun czHome(label: String): Boolean {
        repeat(5) { attempt ->
            val snapshot = czScreen("${label}_$attempt") ?: return false
            if (CashzineRules.awarded(snapshot)) {
                czCloseAwarded("${label}_$attempt")
                return@repeat
            }
            if (!CashzineRules.hasBottomBar(snapshot) && czRealAd(snapshot)) {
                recorder.event("cz_home_ad", fields = mapOf("activity" to snapshot.foregroundActivity))
                handleAd(assumeAd = true, label = "${label}_ad_$attempt")
                if (adTrapped) return false
                return@repeat
            }
            val home = CashzineRules.bottomTab(snapshot, "Home")
            if (home != null && CashzineRules.hasBottomBar(snapshot)) {
                czTap(home, "cz_tab_home")
                delay(1_500L)
                return true
            }
            recorder.event("cz_home_back", fields = mapOf("attempt" to attempt))
            actor.back()
            delay(1_500L)
        }
        return false
    }

    /** The Invite page (either top tab). */
    private suspend fun czOpenInvite(label: String): Snapshot? {
        if (!czHome("${label}_home")) return null
        val home = czScreen("${label}_bar") ?: return null
        val invite = CashzineRules.bottomTab(home, "Invite")
        if (invite == null) {
            recorder.event("cz_invite_tab_missing")
            return null
        }
        czTap(invite, "cz_tab_invite")
        delay(2_000L)
        val page = czScreen("${label}_invite") ?: return null
        if (!CashzineRules.onInvitePage(page)) {
            recorder.event("cz_invite_not_open")
            saveCurrentScreen("${label}_invite_unknown")
            return null
        }
        return page
    }

    /** Invite > Offer. */
    private suspend fun czOpenOffer(label: String): Snapshot? {
        val invite = czOpenInvite(label) ?: return null
        val offer = CashzineRules.topTab(invite, "Offer") ?: return null
        czTap(offer, "cz_tab_offer")
        delay(2_000L)
        // The page keeps its scroll between visits (Lucky Draw left it at the
        // bottom and reading then found no "Read now", 2026-11-24 13:28).
        scrollToTop()
        val page = czScreen("${label}_offer") ?: return null
        if (!CashzineRules.onInvitePage(page)) {
            recorder.event("cz_offer_not_open")
            saveCurrentScreen("${label}_offer_unknown")
            return null
        }
        return page
    }

    /** Scrolls the current page down until [finder] sees what it wants. */
    private suspend fun <T : Any> czScrollFind(label: String, pages: Int, finder: (Snapshot) -> T?): Pair<Snapshot, T>? {
        for (page in 0..pages) {
            val snapshot = czScreen("${label}_$page") ?: return null
            finder(snapshot)?.let { return snapshot to it }
            if (page == pages) break
            val (w, h) = foregroundSize()
            val x = (w * kotlin.random.Random.nextDouble(0.55, 0.75)).toInt()
            actor.swipe(x, (h * 0.70).toInt(), x, (h * 0.40).toInt(), 450L)
            czPause(1_200L, 1_800L)
        }
        recorder.event("cz_not_found", fields = mapOf("label" to label))
        saveCurrentScreen("${label}_not_found")
        return null
    }

    /**
     * After a tap that asks for an ad: lets the shared ad loop play it, then
     * reads the "Awarded" dialog. Counts one reward when the dialog (or the
     * ad loop's own sighting of it) proves it.
     */
    private suspend fun czAdReward(label: String, before: Snapshot, depth: Int = 0): Boolean {
        rewardEvidence = false
        delay(profile.settleWaitMillis)
        // The coin's "Win double rewards" sits in an Awarded dialog: that one
        // is not the double (2026-11-24 13:27 it was taken for it three times
        // and Back closed it before the ad came). Only a dialog that was not
        // there before the tap is a reward without an ad.
        val alreadyAwarded = CashzineRules.awarded(before)
        val first = czScreen("${label}_start")
        if (first != null && czCloseNoVideo(first, label)) {
            recorder.event("cz_ad_outcome", fields = mapOf("label" to label, "outcome" to "no_video"))
            if (CashzineRules.awarded(before)) czCloseAwarded("${label}_no_video")
            return false
        }
        val outcome = if (!alreadyAwarded && first != null && CashzineRules.awarded(first)) {
            "awarded_at_once"
        } else if (alreadyAwarded && !czDialogGivesWay(label)) {
            // The ad never came; the coin's own dialog is still up.
            "ad_not_loaded"
        } else {
            awaitAdStart(before, label)
        }
        recorder.event("cz_ad_outcome", fields = mapOf("label" to label, "outcome" to outcome))
        val after = czScreen("${label}_after")
        val awardedNow = after != null && CashzineRules.awarded(after) &&
            (outcome in AD_PLAYED_OUTCOMES || !alreadyAwarded)
        val amount = after?.let { CashzineRules.awardedAmount(it) }
        // "ad_closed" / "ad_ended" come back only after confirmAppReward saw
        // the reward dialog (and pressed the check-in's OK itself, 13:24).
        val ok = awardedNow || rewardEvidence || outcome == "confirmed" || outcome == "awarded_at_once" ||
            outcome == "ad_closed" || outcome == "ad_ended"
        if (outcome in AD_PLAYED_OUTCOMES) ads++
        if (ok) {
            rewards++
            recorder.event("reward_collected", fields = mapOf("kind" to label, "amount" to amount, "count" to rewards))
            publish("CASHZINE：${amount?.let { "+$it" } ?: "已領取"}（已收 $rewards）")
        } else {
            if (outcome in AD_PLAYED_OUTCOMES) unconfirmed++
            recorder.event("cz_reward_unconfirmed", fields = mapOf("label" to label, "outcome" to outcome))
        }
        if (awardedNow) {
            // One more ad from this dialog ("Win 51 coins" after +60); never a
            // chain of them.
            if (depth == 0) czAfterAward(label) else czCloseAwarded(label)
        }
        return ok
    }

    /**
     * After "Win double rewards": waits for the coin's Awarded dialog to give
     * way to the ad, so the shared ad wait never takes the dialog itself for
     * an ad and closes it with its X.
     */
    private suspend fun czDialogGivesWay(label: String): Boolean {
        val started = activeNow()
        var index = 0
        var retapped = false
        var last: Snapshot? = null
        while (awaitRunning() && activeNow() - started < DOUBLE_AD_WAIT_MILLIS) {
            val s = czRead("${label}_wait_ad_${index++}", ocr = true)
            last = s
            if (czCloseNoVideo(s, label)) return false
            if (czRealAd(s) || !CashzineRules.awarded(s)) return true
            // The coin's and the reading tier's offer did nothing six times out
            // of six on 2026-11-25 (+60's and the wheel's worked): the first tap
            // may land during the dialog's 1 s countdown. Press it once more.
            if (!retapped && activeNow() - started >= DOUBLE_RETAP_MILLIS) {
                retapped = true
                CashzineRules.extraAdOffer(s)?.let { czTap(it, "cz_double_retap") }
            }
            delay(1_000L)
        }
        recorder.event("cz_double_no_ad", fields = mapOf("waitedMillis" to activeNow() - started, "retapped" to retapped))
        last?.let { czLogScreen("cz_double_no_ad_screen", it) }
        saveCurrentScreen("${label}_no_ad")
        return false
    }

    // -- daily check-in (an ad) ------------------------------------------------

    private suspend fun czCheckIn() {
        if (czOpenOffer("cz_checkin") == null) return
        val found = czScrollFind("cz_checkin_find", 2) { s ->
            CashzineRules.checkIn(s).takeIf { it != CashzineRules.CheckIn.NotFound }
        } ?: return
        val (page, state) = found
        if (state is CashzineRules.CheckIn.Ready) {
            publish("CASHZINE：每日簽到（看廣告）")
            if (czTap(state.button, "cz_checkin_tap")) czAdReward("cz_checkin", page)
        } else {
            recorder.event("cz_checkin_done_today")
        }
    }

    // -- +60 every hour (an ad) ------------------------------------------------

    private suspend fun czWatch60() {
        if (czVideosOut()) {
            recorder.event("cz_skip_no_video", fields = mapOf("step" to "watch60"))
            return
        }
        val now = System.currentTimeMillis()
        if (czWatchDueAt > 0L && now < czWatchDueAt) {
            recorder.event("cz_watch_cooldown", fields = mapOf("dueInSeconds" to (czWatchDueAt - now) / 1000))
            return
        }
        val page = czOpenInvite("cz_watch") ?: return
        when (val state = CashzineRules.watchState(page)) {
            is CashzineRules.WatchState.Cooldown -> {
                czWatchDueAt = System.currentTimeMillis() + state.millis
                recorder.event("cz_watch_cooldown", fields = mapOf("dueInSeconds" to state.millis / 1000))
            }
            CashzineRules.WatchState.Unknown -> {
                recorder.event("cz_watch_pill_missing")
                saveCurrentScreen("cz_watch_unknown")
            }
            is CashzineRules.WatchState.Ready -> {
                publish("CASHZINE：每小時 +60（看廣告）")
                if (!czTap(state.button, "cz_watch_pill")) return
                delay(1_800L)
                val sheet = czScreen("cz_watch_sheet") ?: return
                if (czCloseNoVideo(sheet, "cz_watch60")) return
                val button = CashzineRules.watchVideosButton(sheet)
                if (button == null) {
                    recorder.event("cz_watch_button_missing")
                    saveCurrentScreen("cz_watch_sheet_unknown")
                    actor.back()
                    delay(1_200L)
                    return
                }
                if (!czTap(button, "cz_watch_tap")) return
                if (czAdReward("cz_watch60", sheet)) {
                    czWatchDueAt = System.currentTimeMillis() + CashzineRules.WATCH_COOLDOWN_MILLIS
                }
            }
        }
    }

    // -- Lucky Draw (no ad; four sessions a day, ten draws each) ------------------

    private suspend fun czLuckyDraw() {
        val hour = java.time.LocalTime.now().hour
        if (!CashzineRules.luckySessionOpen(hour)) {
            recorder.event("cz_lucky_closed", fields = mapOf("hour" to hour))
            return
        }
        if (czOpenOffer("cz_lucky") == null) return
        publish("CASHZINE：Lucky Draw")
        var snapshot: Snapshot = czOpenWheel("cz_lucky") ?: return
        var reopens = 0
        var draws = 0
        var misses = 0
        var savedWin = false
        // "Try Again" on an empty result already started the next draw.
        var spunByTryAgain = false
        var chancesLeft: Int? = null
        while (awaitRunning() && draws < LUCKY_MAX_DRAWS) {
            if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) break
            if (!spunByTryAgain) {
                val chances = CashzineRules.remainingChances(snapshot)
                if (chances != null) chancesLeft = chances
                recorder.event("cz_lucky_chances", fields = mapOf("chances" to chances, "draws" to draws))
                if (chances == 0) break
                val go = CashzineRules.goButton(snapshot)
                if (go == null) {
                    recorder.event("cz_lucky_go_missing")
                    saveCurrentScreen("cz_lucky_no_go")
                    break
                }
                czPause(1_500L, 4_000L)
                if (!czTap(go, "cz_lucky_go")) break
            }
            spunByTryAgain = false
            draws++
            chancesLeft = chancesLeft?.minus(1)
            var result: CashzineRules.DrawResult.Shown? = null
            val started = activeNow()
            while (awaitRunning() && activeNow() - started < LUCKY_RESULT_WAIT_MILLIS) {
                delay(1_500L)
                val s = czScreen("cz_lucky_wait_$draws") ?: return
                if (!CashzineRules.onWheel(s) && czRealAd(s)) {
                    recorder.event("cz_lucky_ad", fields = mapOf("activity" to s.foregroundActivity))
                    handleAd(assumeAd = true, label = "cz_lucky_ad_$draws")
                    continue
                }
                val r = CashzineRules.drawResult(s)
                if (r is CashzineRules.DrawResult.Shown) {
                    result = r
                    break
                }
            }
            if (result == null) {
                misses++
                recorder.event("cz_lucky_no_result", fields = mapOf("misses" to misses))
                saveCurrentScreen("cz_lucky_no_result_$draws")
                if (misses >= 2) break
                snapshot = czScreen("cz_lucky_after_miss") ?: return
                continue
            }
            misses = 0
            recorder.event(
                "cz_lucky_result",
                fields = mapOf("won" to result.won, "text" to result.text, "close" to result.close?.text)
            )
            if (result.won) {
                rewards++
                publish("CASHZINE Lucky Draw：${result.text}（已收 $rewards）")
                recorder.event("reward_collected", fields = mapOf("kind" to "cz_lucky", "count" to rewards))
                // The prize screen was never seen in the evaluation: keep one.
                if (!savedWin) {
                    saveCurrentScreen("cz_lucky_win")
                    savedWin = true
                }
            }
            val close = result.close
            if (result.won && close == null) {
                // The Awarded dialog: Back closes it and stays on the wheel
                // (13:49:47); its "Win double rewards" ad is not taken.
                czAfterAward("cz_lucky_award_$draws")
            } else if (close != null && TextMatch.normalizeLoose(close.text) == "tryagain") {
                // Nothing won: Back here leaves the whole wheel page (2026-11-24
                // 16:47, 1 of 9 draws used). "Try Again" is the next draw while
                // chances are left; after the last one, Back leaves the page.
                if ((chancesLeft == null || chancesLeft!! > 0) && draws < LUCKY_MAX_DRAWS) {
                    czPause(1_500L, 3_500L)
                    if (czTap(close, "cz_lucky_try_again")) {
                        spunByTryAgain = true
                        continue
                    }
                }
                recorder.event("cz_lucky_last_back", fields = mapOf("chancesLeft" to chancesLeft))
                actor.back()
                break
            } else if (close != null) {
                czTap(close, "cz_lucky_close")
            } else {
                recorder.event("cz_lucky_result_back")
                actor.back()
            }
            delay(1_500L)
            snapshot = czScreen("cz_lucky_after_$draws") ?: return
            if (!CashzineRules.onWheel(snapshot)) {
                recorder.event("cz_lucky_left_wheel", fields = mapOf("chancesLeft" to chancesLeft))
                saveCurrentScreen("cz_lucky_left_$draws")
                // The prize's double ad ended on Offer, not the wheel (2026-11-25
                // 13:25, five draws left): go back in while draws are left.
                if (reopens >= LUCKY_REOPENS || chancesLeft == 0) break
                reopens++
                if (czOpenOffer("cz_lucky_reopen_$reopens") == null) break
                snapshot = czOpenWheel("cz_lucky_reopen_$reopens") ?: break
            }
        }
        recorder.event("cz_lucky_done", fields = mapOf("draws" to draws, "reopens" to reopens))
    }

    /** From Offer: scroll to "Spin to win lucky draw", tap its button, wait for the wheel. */
    private suspend fun czOpenWheel(label: String): Snapshot? {
        val entry = czScrollFind("${label}_find", 8) { CashzineRules.luckyEntry(it) }?.second ?: return null
        if (!czTap(entry, "cz_lucky_entry")) return null
        for (i in 0 until 6) {
            delay(1_500L)
            val s = czScreen("${label}_open_$i") ?: return null
            if (CashzineRules.onWheel(s)) return s
        }
        recorder.event("cz_lucky_not_open")
        saveCurrentScreen("${label}_unknown")
        return null
    }

    // -- floating home coins + double ad -----------------------------------------

    private suspend fun czCoins() {
        if (!czHome("cz_coins")) return
        val tried = ArrayList<Box>()
        var found = 0
        var misses = 0
        while (awaitRunning() && found < COINS_MAX) {
            if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) break
            val fg = foreground()
            val reading = reader.readDetailed(fg.packageName, fg.activity)
            val snapshot = reading.snapshot
            val coins = try {
                val bitmap = reading.bitmap
                if (bitmap == null || isWrongPackage(snapshot) || CashzineRules.awarded(snapshot)) {
                    null
                } else {
                    val pixels = IntArray(bitmap.width * bitmap.height)
                    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    CashzineRules.findCoins(pixels, bitmap.width, bitmap.height)
                }
            } finally {
                reading.bitmap?.recycle()
            }
            if (coins == null) {
                // Not the plain home page (a dialog, another app, no screenshot).
                misses++
                if (misses >= 3 || !czHome("cz_coins_reset_$found")) break
                continue
            }
            val coin = coins.firstOrNull { c ->
                tried.none { kotlin.math.abs(it.centerX - c.centerX) < 80 && kotlin.math.abs(it.centerY - c.centerY) < 80 }
            }
            recorder.event("cz_coins_seen", fields = mapOf("count" to coins.size, "next" to coin?.toString()))
            if (coin == null) break
            tried.add(coin)
            czPause(1_000L, 2_500L)
            publish("CASHZINE：點首頁金幣")
            if (!czTap(Target("[coin]", coin, 100, "coin"), "cz_coin_tap")) break
            delay(2_000L)
            val award = czScreen("cz_coin_award_$found") ?: break
            if (!CashzineRules.awarded(award)) {
                recorder.event("cz_coin_no_award")
                saveCurrentScreen("cz_coin_no_award_$found")
                misses++
                if (misses >= 3) break
                czHome("cz_coin_back_$found")
                continue
            }
            found++
            misses = 0
            czCountAward(award, "cz_coin", "金幣")
            val double = CashzineRules.doubleButton(award)?.takeIf { !czVideosOut() }
            if (double != null) {
                // The user wants the double ad (2026-11-18).
                if (czTap(double, "cz_coin_double")) czAdReward("cz_coin_double_$found", award, depth = 1)
            } else {
                czCloseAwarded("cz_coin_$found")
            }
            czHome("cz_coin_after_$found")
        }
        recorder.event("cz_coins_done", fields = mapOf("collected" to found))
    }

    // -- reading (no ad; 1000 for reading + 800 extra a day) ---------------------

    private suspend fun czReading() {
        val today = czReadingToday()
        if (CashzineRules.readingCapReached(today)) {
            recorder.event("cz_reading_cap_reached", fields = mapOf("today" to today))
            publish("CASHZINE：今天看小說已領 $today（上限 ${CashzineRules.READING_DAILY_CAP}），跳過")
            return
        }
        if (czOpenOffer("cz_read") == null) return
        val readNow = czScrollFind("cz_read_find", 6) { CashzineRules.readNowButton(it) }?.second ?: return
        publish("CASHZINE：看小說")
        if (!czTap(readNow, "cz_read_now")) return
        delay(3_000L)
        var snapshot = czScreen("cz_read_open", ocr = false) ?: return
        if (!czOnReader(snapshot) && CashzineRules.startReadingButton(snapshot) == null) {
            // No book open: "Read now" goes to the home page (2026-11-24 16:33).
            val home = czScreen("cz_read_home") ?: return
            val book = CashzineRules.firstBookCover(home)
            if (book == null) {
                recorder.event("cz_read_no_book")
                saveCurrentScreen("cz_read_no_book")
                return
            }
            czTap(book, "cz_read_book")
            delay(3_000L)
            snapshot = czScreen("cz_read_detail") ?: return
        }
        val start = CashzineRules.startReadingButton(snapshot)
        if (start != null && !czOnReader(snapshot)) {
            czTap(start, "cz_start_reading")
            delay(3_000L)
            snapshot = czScreen("cz_read_open2", ocr = false) ?: return
        }
        if (!czOnReader(snapshot)) {
            recorder.event("cz_reading_not_open", fields = mapOf("activity" to snapshot.foregroundActivity))
            saveCurrentScreen("cz_read_unknown")
            return
        }
        val started = activeNow()
        var lastClaimAt = started
        var lastCounter: String? = CashzineRules.readerCounter(snapshot)
        var lastCounterAt = started
        var lastCoins: Int? = null
        var up = true
        var swipes = 0
        var lostReader = 0
        while (awaitRunning()) {
            if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) break
            val now = activeNow()
            if (now - started >= (CashzineRunOptions.readingMaxMillis ?: READING_MAX_MILLIS)) {
                recorder.event("cz_reading_time_cap", fields = mapOf("swipes" to swipes))
                break
            }
            // Progress is a Claim, the corner's coin count growing or a "+N"
            // popup (lastClaimAt covers all three). Tiers came 1.5, 3.5 and 6.5
            // minutes apart (2026-11-25), so allow a long wait.
            if (now - lastClaimAt >= READING_STALL_MILLIS) {
                // Nothing to claim and the counter has not moved: every tier is
                // taken today, or reading does not count right now.
                recorder.event("cz_reading_stalled", fields = mapOf("swipes" to swipes, "counter" to lastCounter))
                break
            }
            val (w, h) = foregroundSize()
            val x = (w * kotlin.random.Random.nextDouble(0.35, 0.65)).toInt()
            val low = (h * kotlin.random.Random.nextDouble(0.58, 0.66)).toInt()
            val high = (h * kotlin.random.Random.nextDouble(0.34, 0.42)).toInt()
            // Up and down by turns: it counts as reading (user, 2026-11-18) but
            // stays near the same page instead of running to the chapter's end.
            val duration = kotlin.random.Random.nextLong(280L, 520L)
            if (up) actor.swipe(x, low, x, high, duration) else actor.swipe(x, high, x, low, duration)
            up = !up
            swipes++
            czPause(3_000L, 5_000L)
            val s = czScreen("cz_read_$swipes", ocr = false) ?: return
            if (!czOnReader(s)) {
                lostReader++
                recorder.event("cz_reading_lost", fields = mapOf("count" to lostReader, "activity" to s.foregroundActivity))
                when {
                    CashzineRules.awarded(s) -> czCloseAwarded("cz_read_stray")
                    CashzineRules.earnPanel(s) -> actor.back()
                    czRealAd(s) -> handleAd(assumeAd = true, label = "cz_read_ad")
                    else -> saveCurrentScreen("cz_read_lost_$lostReader")
                }
                delay(1_500L)
                if (lostReader >= 3) break
                continue
            }
            lostReader = 0
            val counter = CashzineRules.readerCounter(s)
            if (counter != null && counter != lastCounter) {
                lastCounter = counter
                lastCounterAt = activeNow()
            }
            // The corner's coin count grows with "+N" popups while scrolling
            // (user, 2026-11-25): either one is progress; 1000 is the day's cap.
            val coins = CashzineRules.readerCoins(s)
            val plus = CashzineRules.readerPlus(s)
            if (plus != null || (coins != null && coins != lastCoins)) {
                if (coins != lastCoins || plus != null) {
                    recorder.event("cz_read_corner", fields = mapOf("coins" to coins, "plus" to plus, "swipes" to swipes))
                }
                lastCoins = coins ?: lastCoins
                lastClaimAt = activeNow()
            }
            if (coins != null && CashzineRules.readingCapReached(coins)) {
                recorder.event("cz_reading_cap_reached", fields = mapOf("corner" to coins))
                publish("CASHZINE：看小說已到 $coins，達每日上限")
                // Claim what is still waiting, then stop.
                CashzineRules.readerClaim(s)?.let { czClaimTiers(it) }
                break
            }
            val claim = CashzineRules.readerClaim(s) ?: continue
            lastClaimAt = activeNow()
            czClaimTiers(claim)
            val total = czReadingToday()
            if (CashzineRules.readingCapReached(total)) {
                recorder.event("cz_reading_cap_reached", fields = mapOf("today" to total))
                publish("CASHZINE：看小說今天已領 $total，達上限")
                break
            }
        }
        recorder.event("cz_reading_done", fields = mapOf("swipes" to swipes, "minutes" to (activeNow() - started) / 60_000))
    }

    // -- shorts (no ad; +910 a day in tiers 30/60/90/180/250) -------------------

    private suspend fun czShorts() {
        if (czOpenOffer("cz_shorts") == null) return
        val entry = czScrollFind("cz_shorts_find", 6) { CashzineRules.shortsEntry(it) }?.second ?: return
        publish("CASHZINE：看 Shorts 短影音")
        if (!czTap(entry, "cz_shorts_entry")) return
        delay(3_000L)
        if (!czShortsPlay("cz_shorts_open")) return
        val started = activeNow()
        val limit = CashzineRunOptions.shortsMaxMillis ?: SHORTS_MAX_MILLIS
        var videos = 0
        var lost = 0
        while (awaitRunning()) {
            if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) break
            if (activeNow() - started >= limit) {
                recorder.event("cz_shorts_time_cap", fields = mapOf("videos" to videos))
                break
            }
            // Watch this one for a while, as a person would.
            czPause(SHORTS_WATCH_MIN_MILLIS, SHORTS_WATCH_MAX_MILLIS)
            val s = czScreen("cz_shorts_$videos") ?: return
            if (CashzineRules.awarded(s)) {
                czCountAward(s, "cz_shorts", "Shorts")
                czAfterAward("cz_shorts_award_$videos")
                continue
            }
            if (czCloseNoVideo(s, "cz_shorts")) continue
            val claim = CashzineRules.shortsClaim(s)
            if (claim != null) {
                czLogScreen("cz_shorts_claim_screen", s)
                if (czTap(claim, "cz_shorts_claim")) {
                    delay(2_000L)
                    val after = czScreen("cz_shorts_claim_after") ?: return
                    if (CashzineRules.awarded(after)) {
                        czCountAward(after, "cz_shorts", "Shorts")
                        czAfterAward("cz_shorts_claim_award_$videos")
                    } else {
                        czLogScreen("cz_shorts_claim_no_award", after)
                        saveCurrentScreen("cz_shorts_claim_no_award_$videos")
                        actor.back()
                        delay(1_500L)
                    }
                }
                continue
            }
            if (czRealAd(s) && !CashzineRules.isOwnPage(s.foregroundPackage, s.foregroundActivity)) {
                handleAd(assumeAd = true, label = "cz_shorts_ad_$videos")
                continue
            }
            if (CashzineRules.onShortsList(s) || CashzineRules.onInvitePage(s)) {
                // Back on the drama list or Offer (an episode ended, an ad's
                // Back): press Continue again.
                lost++
                recorder.event("cz_shorts_lost", fields = mapOf("count" to lost))
                saveCurrentScreen("cz_shorts_lost_$lost")
                if (lost > SHORTS_REPLAYS) break
                if (CashzineRules.onInvitePage(s)) break
                if (!czShortsPlay("cz_shorts_replay_$lost")) break
                continue
            }
            if (videos % 10 == 0) czLogScreen("cz_shorts_screen", s)
            val (w, h) = foregroundSize()
            val x = (w * kotlin.random.Random.nextDouble(0.40, 0.60)).toInt()
            actor.swipe(x, (h * kotlin.random.Random.nextDouble(0.70, 0.78)).toInt(), x,
                (h * kotlin.random.Random.nextDouble(0.22, 0.30)).toInt(), kotlin.random.Random.nextLong(250L, 450L))
            videos++
        }
        recorder.event("cz_shorts_done", fields = mapOf("videos" to videos, "minutes" to (activeNow() - started) / 60_000))
    }

    /**
     * On the Shorts tab's drama list: press "Continue" (or the first poster)
     * and check the player is up. The player screen is logged in full the
     * first time, since it was never read before.
     */
    private suspend fun czShortsPlay(label: String): Boolean {
        val list = czScreen(label) ?: return false
        val play = CashzineRules.shortsPlay(list)
        if (play == null) {
            czLogScreen("cz_shorts_list_screen", list)
            saveCurrentScreen("${label}_no_play")
            return false
        }
        czTap(play, "cz_shorts_play")
        delay(4_000L)
        val player = czScreen("${label}_player") ?: return false
        czLogScreen("cz_shorts_player_screen", player)
        saveCurrentScreen("${label}_player")
        if (CashzineRules.onShortsList(player)) {
            recorder.event("cz_shorts_not_playing")
            return false
        }
        return true
    }

    /** Every text on a screen never seen before, for the next version. */
    private fun czLogScreen(event: String, s: Snapshot) {
        recorder.event(
            event,
            fields = mapOf("activity" to s.foregroundActivity),
            message = s.boxes.joinToString(" | ") { "${it.text.replace('\n', ' ')}@${it.bounds.centerX},${it.bounds.centerY}" }.take(1500)
        )
    }

    // -- +600 saved by reading / shorts, claimed by watching videos ------------

    private suspend fun czExtraRewards() {
        if (czVideosOut()) {
            recorder.event("cz_skip_no_video", fields = mapOf("step" to "extra"))
            return
        }
        if (czOpenOffer("cz_extra") == null) return
        val seen = HashSet<String>()
        for (page in 0..6) {
            val s = czScreen("cz_extra_$page") ?: return
            for (card in CashzineRules.extraCards(s)) {
                if (!seen.add(card.kind)) continue
                recorder.event(
                    "cz_extra_card",
                    fields = mapOf(
                        "kind" to card.kind, "saved" to card.saved, "total" to card.total,
                        "button" to card.button?.text, "row" to card.rowTexts.joinToString(" | ")
                    )
                )
                if (card.saved <= 0) continue
                val button = card.button
                if (button == null) {
                    // Coins saved but no button known yet: keep the screen so
                    // the next version knows what the claim looks like.
                    saveCurrentScreen("cz_extra_${card.kind}_no_button")
                    continue
                }
                publish("CASHZINE：看影片領 ${card.kind} 額外 ${card.saved}")
                if (czTap(button, "cz_extra_claim")) czAdReward("cz_extra_${card.kind}", s, depth = 1)
                czOpenOffer("cz_extra_back") ?: return
                return czExtraRewardsDone(seen)
            }
            if (seen.containsAll(listOf("reading", "shorts"))) break
            val (w, h) = foregroundSize()
            val x = (w * kotlin.random.Random.nextDouble(0.55, 0.75)).toInt()
            actor.swipe(x, (h * 0.70).toInt(), x, (h * 0.40).toInt(), 450L)
            czPause(1_200L, 1_800L)
        }
        czExtraRewardsDone(seen)
    }

    private fun czExtraRewardsDone(seen: Set<String>) {
        recorder.event("cz_extra_done", fields = mapOf("cards" to seen.joinToString(",")))
    }

    private fun czOnReader(s: Snapshot): Boolean =
        CashzineRules.isReadingActivity(s.foregroundActivity) ||
            CashzineRules.readerCounter(s) != null || CashzineRules.readerClaim(s) != null

    /** The reader's Claim opens "Earn coins daily": claim each ready tier, then close it. */
    private suspend fun czClaimTiers(claim: Target) {
        if (!czTap(claim, "cz_reader_claim")) return
        delay(2_000L)
        for (i in 0 until READING_TIERS_PER_OPEN) {
            val panel = czScreen("cz_panel_$i") ?: return
            if (CashzineRules.awarded(panel)) {
                czCountAward(panel, "cz_read_tier", "閱讀")
                CashzineRules.awardedAmount(panel)?.let { czAddReading(it) }
                czAfterAward("cz_panel_award_$i")
                continue
            }
            if (!CashzineRules.earnPanel(panel)) {
                if (i == 0) {
                    recorder.event("cz_panel_missing")
                    saveCurrentScreen("cz_panel_unknown")
                }
                return
            }
            val tier = CashzineRules.tierClaim(panel)
            if (tier == null) {
                recorder.event("cz_panel_no_tier")
                break
            }
            czPause(800L, 1_800L)
            if (!czTap(tier, "cz_tier_claim")) return
            delay(2_000L)
            val after = czScreen("cz_tier_after_$i") ?: return
            if (CashzineRules.awarded(after)) {
                czCountAward(after, "cz_read_tier", "閱讀")
                CashzineRules.awardedAmount(after)?.let { czAddReading(it) }
                czAfterAward("cz_tier_award_$i")
            } else {
                recorder.event("cz_tier_no_award", fields = mapOf("text" to tier.text))
                saveCurrentScreen("cz_tier_no_award_$i")
                break
            }
        }
        // Close the panel and read on.
        val end = czScreen("cz_panel_end", ocr = false) ?: return
        if (CashzineRules.earnPanel(end)) {
            actor.back()
            delay(1_500L)
        }
    }

    private fun czCountAward(snapshot: Snapshot, kind: String, label: String) {
        rewards++
        val amount = CashzineRules.awardedAmount(snapshot)
        recorder.event("reward_collected", fields = mapOf("kind" to kind, "amount" to amount, "count" to rewards))
        publish("CASHZINE $label：${amount?.let { "+$it" } ?: "已領取"}（已收 $rewards）")
    }

    // ------------------------------------------------------------------ misc

    private suspend fun readPlain(label: String): Snapshot {
        val fg = foreground()
        recorder.event("capture_begin", fields = mapOf("label" to label))
        val snapshot = reader.read(fg.packageName, fg.activity)
        recorder.event(
            "snapshot",
            fields = mapOf(
                "label" to label,
                "package" to snapshot.foregroundPackage,
                "node" to snapshot.nodeCount(),
                "ocr" to snapshot.ocrCount(),
                "ocrMillis" to snapshot.ocrMillis,
                "ocrError" to snapshot.ocrError,
                "disabled" to snapshot.disabled.size
            )
        )
        return snapshot
    }

    private suspend fun saveCurrentScreen(label: String) {
        val fg = foreground()
        reader.analyze(fg.packageName, fg.activity).use { frame ->
            recorder.shot(frame.bitmap, label)
        }
    }

    private suspend fun awaitRunning(): Boolean {
        if (adTrapped) {
            if (stopCause == null) stopCause = "ad_trapped"
            return false
        }
        if (CollectorRunPolicy.runCapApplies(rewardLimit) && engineStartedAt > 0L &&
            activeNow() - engineStartedAt >= MAX_RUN_MILLIS
        ) {
            recorder.event("run_timeout")
            stopCause = "run_timeout"
            environment.stop()
            return false
        }
        while (environment.state == CollectorState.PAUSED) {
            val now = SystemClock.elapsedRealtime()
            activeClock.pauseStarted(now)
            if (pausedSince == 0L) {
                pausedSince = now
                recorder.event("paused", fields = mapOf("reason" to environment.pauseReason))
            }
            // Lets a pause the collector took by itself end once the phone is
            // unlocked and nobody touches it (review P1-3).
            environment.checkInterruptions()
            if (environment.state != CollectorState.PAUSED) break
            if (CollectorRunPolicy.pauseTimesOut(environment.pauseReason) &&
                now - pausedSince >= MAX_PAUSE_MILLIS
            ) {
                // A locked phone or a stray touch must not leave the collector
                // waiting forever on an unattended run; the user can start again.
                recorder.event("paused_too_long")
                stopCause = "paused_too_long"
                environment.stop()
                break
            }
            delay(WAIT_POLL_MILLIS)
        }
        if (pausedSince != 0L) {
            activeClock.pauseEnded(SystemClock.elapsedRealtime())
            recorder.event(
                "resumed",
                fields = mapOf(
                    "pausedMillis" to SystemClock.elapsedRealtime() - pausedSince,
                    "state" to environment.state.name
                )
            )
        }
        pausedSince = 0L
        if (environment.state == CollectorState.IDLE) {
            if (stopCause == null) stopCause = "stopped"
            return false
        }
        return true
    }

    /** Running time only: deadlines measured with it ignore pauses (review P1-5). */
    private fun activeNow(): Long = activeClock.activeAt(SystemClock.elapsedRealtime())

    /**
     * The "←" region of an in-ad install card: left 40%, from below
     * "Skip to Google Play" to just above "Install" (PC `_install_card_back`).
     */
    private fun installRegion(snapshot: Snapshot): Box? {
        if (!snapshot.contains("skipto")) return null
        val skip = snapshot.findLine("skipto") ?: return null
        val install = snapshot.boxes.firstOrNull {
            TextMatch.normalizeLoose(it.text) in INSTALL_LABELS
        }
        val bottom = if (install != null && install.bounds.top > skip.bounds.bottom) {
            install.bounds.top
        } else {
            (snapshot.height * 0.6).toInt()
        }
        if (bottom <= skip.bounds.bottom) return null
        return Box(0, skip.bounds.bottom, (snapshot.width * 0.4).toInt(), bottom)
    }

    private suspend fun recoverMainScreen(snapshot: Snapshot): Boolean {
        if (profile.lineRules && clearLineOverlays(snapshot)) return true
        if (CollectorRules.looksLikeAd(snapshot, profile)) {
            recorder.event("leftover_ad")
            val outcome = handleAd(assumeAd = false, label = "leftover")
            if ((outcome == "ad_timeout" || outcome == "ad_stuck") && cleanRestarts < MAX_CLEAN_RESTARTS) {
                // Back cannot leave this ad. Open the app again with its task
                // cleared: the ad goes with it and collection continues,
                // instead of the app being given up while rewards remain.
                restartAppClean("leftover_$outcome")
                return true
            }
            if (outcome == "ad_timeout" || outcome == "ad_stuck") {
                // The ad already timed out once and Back did not leave it:
                // nothing the run can do will (a playable "WI-FI 獎勵" ad held
                // BITWALK for nine minutes on 2026-11-13). Hand it to the user
                // and let the next app run.
                adTrapped = true
                val line = "${profile.name}：廣告無法關閉，請手動關閉後再收"
                manual.add(line)
                publish(line)
                recorder.event("ad_trapped", message = line)
                return true
            }
            dismissPopups()
            return true
        }
        if (CollectorRules.isDialog(snapshot, profile) ||
            !CollectorRules.hasAppChrome(snapshot, profile)
        ) {
            recorder.event("recover_main_screen")
            dismissPopups()
            val check = readPlain("recover_check")
            if (!CollectorRules.hasAppChrome(check, profile)) {
                actor.back()
                delay(1_500L)
                recoverToApp()
            }
            return true
        }
        return false
    }

    private fun publish(message: String) {
        runCatching { progressListener?.invoke(Progress(message, rewards, ads, taps)) }
    }

    private fun outcome(reason: String): Outcome = Outcome(        app = profile.key,
        endReason = reason,
        rewards = rewards,
        ads = ads,
        taps = taps,
        timeouts = timeouts,
        unconfirmed = unconfirmed,
        manual = manual.toList(),
        redirects = redirects,
        errors = errors,
        runDir = recorder.runDir.absolutePath,
        available = if (scanOnly) available else -1,
        repeatable = scanOnly && profile.repeatableRewards
    )

    companion object {
        private const val READY_ATTEMPTS = 4
        private const val MAX_SWEEPS = 5
        private const val MAX_TAB_RESTARTS = 2
        private const val MAX_DEFERRED_ROUNDS = 3
        private const val AD_REDIRECT_GRACE_MILLIS = 10_000L

        /** Home on screen this soon after the run's own Home press is the run's. */
        private const val OWN_HOME_GRACE_MILLIS = 5_000L
        private const val INMOBI_AD_TIMEOUT_MILLIS = 60_000L
        private const val TAB_SEARCH_SCROLLS = 4
        private const val MAX_SUB_PAGE_REOPENS = 2
        private const val SCROLL_TOP_SWIPES = 6
        private const val PLAY_ENDCARD_GRACE_MILLIS = 8_000L
        private const val OPEN_STORE_GRACE_MILLIS = 6_000L
        private const val MAX_OPEN_STORE_TAPS = 2
        // 3 ran out after 30 minutes of BITWALK's stuck MBridge ads (2026-11-16).
        private const val MAX_CLEAN_RESTARTS = 5
        private const val AD_START_WAIT_MILLIS = 20_000L
        /** Cashzine: outcomes of [awaitAdStart] in which an ad really played. */
        private val AD_PLAYED_OUTCOMES = setOf("ad_closed", "ad_ended", "ad_stuck", "ad_timeout", "ad_unconfirmed")
        private const val LUCKY_MAX_DRAWS = 12
        /** Cashzine's "Video is not available" twice: no more ad items this run. */
        private const val NO_VIDEO_LIMIT = 2
        private const val SHORTS_MAX_MILLIS = 30L * 60_000L
        private const val SHORTS_REPLAYS = 3
        private const val SHORTS_WATCH_MIN_MILLIS = 12_000L
        private const val SHORTS_WATCH_MAX_MILLIS = 25_000L
        /** How long the coin's dialog may stay up after "Win double rewards". */
        private const val DOUBLE_AD_WAIT_MILLIS = 20_000L
        private const val DOUBLE_RETAP_MILLIS = 6_000L
        private const val LUCKY_RESULT_WAIT_MILLIS = 15_000L
        private const val COINS_MAX = 12
        /** One reading stretch per run, at most 45 minutes. */
        private const val READING_MAX_MILLIS = 45L * 60_000L
        /** No Claim and no counter change for 6 minutes: reading is done for now. */
        private const val READING_STALL_MILLIS = 15L * 60_000L
        private const val LUCKY_REOPENS = 2
        private const val READING_TIERS_PER_OPEN = 6
        private const val AD_START_POLL_MILLIS = 2_000L
        private const val LINE_CONFIRM_READS = 5
        private const val SHADE_WAIT_POLLS = 20
        private const val LAUNCH_ATTEMPTS = 3
        private const val TAB_SETTLE_POLLS = 4
        private const val TAB_SETTLE_MIN_BOXES = 15
        private const val POPUP_ATTEMPTS = 3
        private const val NO_CANDIDATE_RETRIES = 2
        private const val INFO_DIALOG_RETRIES = 1
        private const val INFO_RETRY_WAIT_MILLIS = 8_000L
        private const val AFTER_REWARD_DELAY_MILLIS = 1_200L
        private const val AD_END_GRACE_MILLIS = 4_000L
        private const val MINTEGRAL_PLACEHOLDER_WAIT_MILLIS = 5_000L
        private const val MAX_CONSECUTIVE_NO_FILL = 3
        private const val NO_AD_BREAK_MILLIS = 6_000L
        private const val RECOVER_WAIT_MILLIS = 8_000L
        private const val WAIT_POLL_MILLIS = 400L
        private const val MAX_PAUSE_MILLIS = 10 * 60 * 1000L
        private const val MAX_RUN_MILLIS = 60 * 60 * 1000L
        private const val CHOICE_AD_START_TIMEOUT_MILLIS = 20_000L
        private const val CHALLENGE_TIMEOUT_MILLIS = 60_000L
        private const val LOTTERY_RESULT_TIMEOUT_MILLIS = 240_000L
        private const val LOTTERY_SHEET_STUCK_MILLIS = 20_000L
        private const val LOTTERY_ENTRY_OFFSET_PX = 70
        private const val DRY_RUN_SETTLE_ATTEMPTS = 5
        private const val DRY_RUN_SETTLE_WAIT_MILLIS = 2_000L
        private val LOTTERY_ORDER = listOf("big", "free", "normal")
        private const val LOTTERY_PRIZE_CLOSE_WAIT_MILLIS = 8_000L
        private const val LINE_LOADING_POLL_MILLIS = 1_200L
        private const val MAX_LOTTERY_LOADING_TIMEOUTS = 2

        private val INSTALL_LABELS = setOf("install", "lnstall", "安裝", "立即安裝")
    }
}
