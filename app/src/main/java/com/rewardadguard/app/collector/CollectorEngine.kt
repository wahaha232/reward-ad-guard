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
    private val rewardLimit: Int
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
        val runDir: String
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
        interruptionCheck = { environment.checkInterruptions() }
    )

    private var rewards = 0
    private var ads = 0
    private var taps = 0
    private var timeouts = 0
    private var redirects = 0
    private var errors = 0
    private var pausedSince = 0L
    private var engineStartedAt = 0L
    private var bonusFailures = 0
    private var loadFailures = 0
    private var restarts = 0
    private var stopCause: String? = null
    private val activeClock = ActiveClock()

    suspend fun run(): Outcome {
        engineStartedAt = activeNow()
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
        try {
            if (!launchApp()) {
                reason = "launch_failed"
            } else if (dryRun) {
                // A dry run must not tap anything, including tabs or popups. It
                // reads the screen the app launched on and reports one decision.
                dryRunRead()
            } else {
                ensureReady()
                dismissPopups()
                if (profile.lotteryMode) {
                    collectLottery()
                } else {
                val tabs = profile.collectionTabs.ifEmpty { listOf("") }
                val iterationLimit = CollectorLimits.iterations(rewardLimit, profile.maxRewards)
                loop@ for (tab in tabs) {
                    if (!awaitRunning()) {
                        reason = "stopped"
                        break
                    }
                    if (tab.isNotEmpty() && !openTab(tab)) continue
                    awaitTabSettled(tab)
                    dismissPopups()
                    val attempts = HashMap<String, Int>()
                    val infoHits = HashMap<String, Int>()
                    var index = 0
                    while (awaitRunning() && index < iterationLimit) {
                        if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) {
                            reason = "limit"
                            break@loop
                        }
                        index++
                        var snapshot = readPlain("collect_${tab.ifEmpty { "home" }}_$index")
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
                        var candidate = pickReward(snapshot, attempts)
                        var retries = 0
                        while (candidate == null && retries < NO_CANDIDATE_RETRIES) {
                            retries++
                            if (!recoverMainScreen(snapshot)) break
                            snapshot = readPlain("collect_${tab.ifEmpty { "home" }}_${index}_retry$retries")
                            candidate = pickReward(snapshot, attempts)
                        }
                        if (candidate == null) {
                            recorder.event("no_reward", fields = mapOf("tab" to tab))
                            recorder.console("[collector] no reward candidate on tab=$tab")
                            break
                        }
                        val key = CollectorRules.attemptKey(candidate)
                        if (dryRun) {
                            attempts[key] = (attempts[key] ?: 0) + 1
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
                            saveCurrentScreen("dry_${tab.ifEmpty { "home" }}")
                            // One decision per tab, as on the PC: a dry run must not
                            // walk the whole reward list and look like a real run.
                            break
                        }
                        attempts[key] = (attempts[key] ?: 0) + 1
                        taps++
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
                        val expectAd = profile.adButtonWords.any {
                            TextMatch.normalizeLoose(it) in TextMatch.normalizeLoose(candidate.text)
                        }
                        when (val outcome = afterReward(snapshot, candidate, expectAd, "${tab.ifEmpty { "home" }}_$index")) {
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

                            "no_change" -> {
                                recorder.event("no_change", fields = mapOf("text" to candidate.text))
                                break
                            }

                            else -> recorder.event("after_outcome", fields = mapOf("outcome" to outcome))
                        }
                        delay(AFTER_REWARD_DELAY_MILLIS)
                    }
                }
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
                    "redirects" to redirects,
                    "errors" to errors
                )
            )
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
            if (profile.lineRules && clearLineOverlays(snapshot)) return@repeat
            if (CollectorRules.looksLikeAd(snapshot, profile)) {
                recorder.event("startup_ad")
                handleAd(assumeAd = false, label = "startup")
                return@repeat
            }
            val hasChrome = CollectorRules.hasAppChrome(snapshot, profile)
            if (!hasChrome && profile.pageMarkers.isNotEmpty() &&
                profile.pageMarkers.any { marker ->
                    TextMatch.normalizeLoose(marker) in TextMatch.normalizeLoose(snapshot.text)
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
            recorder.event("not_ready", fields = mapOf("attempt" to index + 1))
            delay(2_000L)
        }
    }

    private suspend fun dismissPopups() {
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

    private suspend fun openTab(tab: String): Boolean {
        if (profile.lineRules) {
            val pre = readPlain("tab_${tab}_overlay")
            if (clearLineOverlays(pre)) delay(800L)
        }
        val snapshot = readPlain("tab_${tab}_before")
        val match = snapshot.find(listOf(tab))
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
            val snapshot = readPlain("tab_${tab}_settle$index")
            if (CollectorRules.pickReward(snapshot, profile, emptyMap()) != null) return
            if (CollectorRules.hasAppChrome(snapshot, profile) && snapshot.boxes.size >= TAB_SETTLE_MIN_BOXES) return
            delay(1_200L)
        }
    }

    private suspend fun recoverToApp() {
        recorder.event("recover_to_app")
        actor.launch(profile.packageName)
        actor.waitFor(RECOVER_WAIT_MILLIS) { foreground().packageName == profile.packageName }
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
            if (pkg.isNotEmpty() && pkg != profile.packageName) {
                if (CollectorRules.isSystemPackage(pkg)) {
                    recorder.event("system_surface", fields = mapOf("package" to pkg))
                    return "system_surface"
                }
                redirects++
                recorder.event("foreground_other", fields = mapOf("package" to pkg))
                recorder.shot(frame.bitmap, "${label}_redirect")
                if (!awaitRunning()) return "stopped"
                actor.back()
                delay(1_500L)
                recoverToApp()
                return "blocked_redirect"
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
                val text = TextMatch.normalizeLoose(snapshot.text)
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

            val beforeText = TextMatch.normalizeLoose(before.text)
            val afterText = TextMatch.normalizeLoose(snapshot.text)
            if (beforeText.isNotEmpty() && beforeText == afterText) return "no_change"
            if (CollectorRules.isDirectClaim(candidate, snapshot, profile, expectAd)) {
                recorder.event("direct_claim", fields = mapOf("text" to candidate.text))
                recorder.shot(frame.bitmap, "${label}_claimed")
                return "claimed"
            }
            return "changed"
        } finally {
            frame.close()
        }
    }

    // ------------------------------------------------------------ LINE walk

    private fun pickReward(snapshot: Snapshot, attempts: Map<String, Int>): Target? =
        CollectorRules.pickReward(
            snapshot,
            profile,
            attempts,
            if (profile.lineRules) LineWalkRules::candidateAllowed else null
        )

    private fun isWrongPackage(snapshot: Snapshot): Boolean {
        val pkg = snapshot.foregroundPackage?.trim().orEmpty()
        return pkg.isNotEmpty() && pkg != profile.packageName
    }

    /** LINE only counts a reward when its dialog appears or the coin counter rises. */
    private suspend fun confirmLineIfNeeded(outcome: String, before: Snapshot, label: String): String {
        if (!profile.lineRules) return outcome
        if (outcome != "ad_closed" && outcome != "ad_ended") return outcome
        return if (confirmLineReward(before, label)) outcome else "info_dialog"
    }

    private suspend fun confirmLineReward(before: Snapshot, label: String): Boolean {
        val beforeCoins = LineWalkRules.coinBalance(before)
        repeat(3) { index ->
            val snapshot = readPlain("${label}_reward_$index")
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
        if (current.bonus != null && bonusFailures < profile.maxBonusFailures) {
            recorder.event(
                "choice_bonus",
                fields = mapOf("text" to current.bonus!!.text, "x" to current.bonus!!.centerX, "y" to current.bonus!!.centerY)
            )
            actor.tap(current.bonus!!.centerX, current.bonus!!.centerY)
            delay(profile.settleWaitMillis + 1_000L)
            val after = readPlain("${label}_bonus")
            val still = LineWalkRules.choiceSheet(after)
            if (still == null) {
                bonusFailures = 0
                return followChoice(after, label, "bonus_choice", "changed")
            }
            bonusFailures++
            recorder.event("bonus_unavailable", fields = mapOf("failures" to bonusFailures))
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
        while (true) {
            if (CollectorRules.looksLikeAd(snapshot, profile) ||
                !CollectorRules.hasAppChrome(snapshot, profile)
            ) {
                recorder.event("ad_detected", message = reason)
                return handleAd(assumeAd = true, label = label)
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
    }

    /**
     * Closes overlays LINE leaves on top of the tabs: challenge, reward dialog,
     * lottery result/sheet and the reward choice sheet. Returns true when it
     * acted, so the caller re-reads instead of deciding on a covered screen.
     */
    private suspend fun clearLineOverlays(snapshot: Snapshot): Boolean {
        if (!profile.lineRules) return false
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
        var deadline = start + profile.adTimeoutMillis
        var segmentStart = start
        var adSeen = assumeAd
        var closed = false
        var index = 0
        val triedX = HashSet<Pair<Int, Int>>()
        var nextClicks = 0
        var cornerSkips = 0
        var backPresses = 0
        var installBacks = 0
        var sequenceTotal: Int? = null

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
                triedX.clear()
            }
            val elapsed = activeNow() - segmentStart
            val fg = foreground()
            val frame = reader.analyze(fg.packageName, fg.activity) { installRegion(it) }
            try {
                val snapshot = frame.snapshot
                if (index == 0) recorder.shot(frame.bitmap, "${label}_ad_first")
                index++
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
                if (pkg.isNotEmpty() && pkg != profile.packageName) {
                    if (CollectorRules.isSystemPackage(pkg)) {
                        recorder.event("ad_system_surface", fields = mapOf("package" to pkg))
                        actor.launch(profile.packageName)
                        delay(1_200L)
                        continue
                    }
                    redirects++
                    recorder.event("ad_redirect_blocked", fields = mapOf("package" to pkg))
                    recorder.shot(frame.bitmap, "${label}_ad_redirect")
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

                val adNow = CollectorRules.looksLikeAd(snapshot, profile)
                if (adNow) adSeen = true

                if (adSeen && elapsed >= AD_END_GRACE_MILLIS &&
                    CollectorRules.hasAppChrome(snapshot, profile) && !adNow
                ) {
                    recorder.event("ad_ended", fields = mapOf("elapsed" to elapsed))
                    recorder.shot(frame.bitmap, "${label}_ad_end")
                    return "ad_ended"
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
                    val targets = CollectorRules.findCloseTargets(snapshot, profile, frame.corners)
                        .filter { (it.centerX to it.centerY) !in triedX }
                    var tappedAny = false
                    for (target in targets.take(2)) {
                        triedX.add(target.centerX to target.centerY)
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

                if (adSeen && nextClicks < profile.maxNextAds && elapsed >= profile.adMinWatchMillis) {
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
                        deadline = maxOf(deadline, segmentStart + profile.adTimeoutMillis)
                        continue
                    }
                }

                val skipWait = profile.adMinWatchMillis +
                    if (finalAd) 0L else profile.cornerSkipGraceMillis
                if (adSeen && elapsed >= skipWait && cornerSkips < profile.maxCornerSkips) {
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

    // -------------------------------------------------------------- lottery

    /** Dry run: read, wait for the app to settle, decide; never touch the screen. */
    private suspend fun dryRunRead() {
        var snapshot = readPlain("dry_home_0")
        var candidate = pickReward(snapshot, emptyMap())
        var attempt = 1
        // Launching returns when the app's package is in front, which can still
        // be a splash or an empty web view (LINE 散步趣 took >600 ms to render
        // on 2026-11-12). Wait without tapping so the dry run sees the real page.
        while (candidate == null && attempt < DRY_RUN_SETTLE_ATTEMPTS) {
            delay(DRY_RUN_SETTLE_WAIT_MILLIS)
            snapshot = readPlain("dry_home_$attempt")
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
        if (dryRun) return
        var snapshot = openLottery() ?: return
        var draws = 0
        val failures = hashMapOf("big" to 0, "free" to 0, "normal" to 0)
        while (awaitRunning() && draws < profile.maxLotteryDraws) {
            if (CollectorLimits.hasUserCap(rewardLimit) && rewards >= rewardLimit) {
                recorder.event("lottery_limit", fields = mapOf("rewards" to rewards))
                break
            }
            val sheet = LineWalkRules.lotterySheet(snapshot)
            if (sheet == null) {
                snapshot = openLottery() ?: break
                continue
            }
            val buttons = linkedMapOf("big" to sheet.big, "free" to sheet.free, "normal" to sheet.normal)
            for ((kind, count) in sheet.counts) {
                if (count <= 0) buttons.remove(kind)
            }
            val kind = LOTTERY_ORDER.firstOrNull {
                buttons[it] != null && (failures[it] ?: 0) < profile.maxLotteryFailures
            }
            if (kind == null) {
                recorder.event(
                    "lottery_done",
                    fields = mapOf("draws" to draws, "counts" to sheet.counts)
                )
                recorder.console("[collector] lottery done draws=$draws counts=${sheet.counts}")
                break
            }
            val target = buttons[kind] ?: break
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

            val result = waitLotteryResult(draws, kind)
            if (result == null) {
                failures[kind] = (failures[kind] ?: 0) + 1
                recorder.event("lottery_no_result", fields = mapOf("kind" to kind, "failures" to failures[kind]))
                val after = readPlain("lottery_after_fail")
                snapshot = if (LineWalkRules.lotterySheet(after) != null) {
                    after
                } else {
                    openLottery() ?: break
                }
                continue
            }

            // BIG and 免費 are one draw per countdown; after a prize the button
            // stays on screen but does nothing, and two more taps were wasted
            // as "failures" on 2026-11-12. Never wait for the countdown:
            // move on to the next kind at once.
            failures[kind] = if (LineWalkRules.isOncePerCooldown(kind)) profile.maxLotteryFailures else 0
            val prize = result.boxes.firstOrNull {
                TextMatch.normalizeLoose(it.text).contains("中獎")
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

    /** Watches through ads, SKIP and the draw animation for the prize sheet. */
    private suspend fun waitLotteryResult(draws: Int, kind: String): Snapshot? {
        val start = activeNow()
        var index = 0
        var prizeSeenAt = -1L
        while (awaitRunning() &&
            activeNow() - start < LOTTERY_RESULT_TIMEOUT_MILLIS
        ) {
            delay(2_500L)
            val snapshot = readPlain("lottery_${kind}_${draws}_${index++}")
            if (LineWalkRules.lotteryResultClose(snapshot) != null) return snapshot
            if (LineWalkRules.isLotteryPrize(snapshot)) {
                // The prize is on screen but its 關閉 is not (yet). Give the
                // button a moment, then take the prize anyway (review P2-8).
                if (prizeSeenAt < 0L) prizeSeenAt = activeNow()
                if (activeNow() - prizeSeenAt >= LOTTERY_PRIZE_CLOSE_WAIT_MILLIS) {
                    recorder.event("lottery_prize_without_close")
                    return snapshot
                }
                continue
            }

            val pkg = snapshot.foregroundPackage?.trim().orEmpty()
            if ((pkg.isNotEmpty() && pkg != profile.packageName) ||
                CollectorRules.looksLikeAd(snapshot, profile)
            ) {
                handleAd(assumeAd = false, label = "lottery_${kind}_$draws")
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
            val text = TextMatch.normalizeLoose(snapshot.text)
            val isResult = "中獎" in text || LineWalkRules.lotteryResultClose(snapshot) != null
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
            if (onSheet && elapsed >= LOTTERY_SHEET_STUCK_MILLIS) {
                val text = TextMatch.normalizeLoose(snapshot.text)
                val cancel = snapshot.boxes.firstOrNull { TextMatch.normalizeLoose(it.text) == "取消" }
                if (cancel != null && "載入中" in text) {
                    recorder.event("lottery_loading_cancel")
                    actor.tap(
                        (cancel.bounds.left + cancel.bounds.right) / 2,
                        (cancel.bounds.top + cancel.bounds.bottom) / 2
                    )
                    delay(1_500L)
                }
                return null
            }
        }
        return null
    }

    /** LINE's stuck-ad recovery: cancel 載入中, then Home + relaunch (no force-stop). */
    private suspend fun handleLineLoadFailure() {
        val snapshot = readPlain("load_fail")
        val text = TextMatch.normalizeLoose(snapshot.text)
        if ("載入中" in text) {
            snapshot.boxes.firstOrNull { TextMatch.normalizeLoose(it.text) == "取消" }?.let { cancel ->
                recorder.event("loading_cancel")
                actor.tap(
                    (cancel.bounds.left + cancel.bounds.right) / 2,
                    (cancel.bounds.top + cancel.bounds.bottom) / 2
                )
                delay(1_500L)
            }
        }
        loadFailures++
        if (loadFailures < 2 || restarts >= 2) return
        restarts++
        loadFailures = 0
        recorder.event("app_restart", fields = mapOf("count" to restarts))
        actor.home()
        delay(1_000L)
        actor.launch(profile.packageName)
        delay(profile.launchWaitMillis)
        ensureReady()
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
                "ocrError" to snapshot.ocrError
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
            handleAd(assumeAd = false, label = "leftover")
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
        redirects = redirects,
        errors = errors,
        runDir = recorder.runDir.absolutePath
    )

    companion object {
        private const val READY_ATTEMPTS = 4
        private const val LAUNCH_ATTEMPTS = 3
        private const val TAB_SETTLE_POLLS = 4
        private const val TAB_SETTLE_MIN_BOXES = 15
        private const val POPUP_ATTEMPTS = 3
        private const val NO_CANDIDATE_RETRIES = 2
        private const val INFO_DIALOG_RETRIES = 1
        private const val INFO_RETRY_WAIT_MILLIS = 8_000L
        private const val AFTER_REWARD_DELAY_MILLIS = 1_200L
        private const val AD_END_GRACE_MILLIS = 4_000L
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

        private val INSTALL_LABELS = setOf("install", "lnstall", "安裝", "立即安裝")
    }
}
