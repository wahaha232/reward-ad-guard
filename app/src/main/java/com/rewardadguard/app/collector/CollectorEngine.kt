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

    suspend fun run(): Outcome {
        engineStartedAt = SystemClock.elapsedRealtime()
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
            } else {
                ensureReady()
                dismissPopups()
                val tabs = profile.collectionTabs.ifEmpty { listOf("") }
                val iterationLimit = if (rewardLimit > 0) rewardLimit else profile.maxRewards
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
                        if (rewardLimit > 0 && rewards >= rewardLimit) {
                            reason = "limit"
                            break@loop
                        }
                        index++
                        var snapshot = readPlain("collect_${tab.ifEmpty { "home" }}_$index")
                        var candidate = CollectorRules.pickReward(snapshot, profile, attempts)
                        var retries = 0
                        while (candidate == null && retries < NO_CANDIDATE_RETRIES) {
                            retries++
                            if (!recoverMainScreen(snapshot)) break
                            snapshot = readPlain("collect_${tab.ifEmpty { "home" }}_${index}_retry$retries")
                            candidate = CollectorRules.pickReward(snapshot, profile, attempts)
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
        } catch (t: Throwable) {
            if (t is CancellationException) {
                reason = "cancelled"
                throw t
            }
            errors++
            reason = "error"
            recorder.event("engine_error", message = "${t::class.java.simpleName}: ${t.message}")
        } finally {
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
        recorder.event("launch", fields = mapOf("package" to profile.packageName))
        if (!actor.launch(profile.packageName)) {
            recorder.event("launch_failed")
            return false
        }
        val ok = actor.waitFor(profile.launchWaitMillis) {
            foreground().packageName == profile.packageName
        }
        recorder.event("launched", fields = mapOf("ok" to ok))
        return ok
    }

    /** Up to four tries to reach a usable app screen (PC `ensure_ready`). */
    private suspend fun ensureReady() {
        repeat(READY_ATTEMPTS) { index ->
            if (!awaitRunning()) return
            val snapshot = readPlain("ready_$index")
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
            if (hasChrome || profile.pageMarkers.isEmpty()) return
            delay(2_000L)
        }
    }

    private suspend fun dismissPopups() {
        repeat(POPUP_ATTEMPTS) { index ->
            if (!awaitRunning()) return
            val snapshot = readPlain("popup_$index")
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
                return handleAd(assumeAd = false, label = label)
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
                return handleAd(assumeAd = true, label = label)
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
            return if (beforeText.isNotEmpty() && beforeText == afterText) "no_change" else "changed"
        } finally {
            frame.close()
        }
    }

    // -------------------------------------------------------------------- ads

    private suspend fun handleAd(assumeAd: Boolean, label: String): String {
        val start = SystemClock.elapsedRealtime()
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
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline) {
                if (!adSeen || backPresses >= profile.maxBackPresses) break
                backPresses++
                recorder.event("ad_back", fields = mapOf("count" to backPresses))
                actor.back()
                delay(2_000L)
                deadline = SystemClock.elapsedRealtime() + profile.endCardTimeoutMillis
                segmentStart = SystemClock.elapsedRealtime() - maxOf(profile.adMinWatchMillis, 4_000L)
                triedX.clear()
            }
            val elapsed = SystemClock.elapsedRealtime() - segmentStart
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
                    recoverToApp()
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
                        segmentStart = SystemClock.elapsedRealtime()
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
            actor.back()
            delay(1_500L)
            recoverToApp()
            return "ad_timeout"
        }
        return "no_ad"
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
                "ocrMillis" to snapshot.ocrMillis
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
        if (engineStartedAt > 0L &&
            SystemClock.elapsedRealtime() - engineStartedAt >= MAX_RUN_MILLIS
        ) {
            recorder.event("run_timeout")
            environment.stop()
            return false
        }
        while (environment.state == CollectorState.PAUSED) {
            if (pausedSince == 0L) pausedSince = SystemClock.elapsedRealtime()
            if (SystemClock.elapsedRealtime() - pausedSince >= MAX_PAUSE_MILLIS) {
                // A locked phone or a stray touch must not leave the collector
                // waiting forever on an unattended run; the user can start again.
                recorder.event("paused_too_long")
                environment.stop()
                return false
            }
            delay(WAIT_POLL_MILLIS)
        }
        pausedSince = 0L
        return environment.state == CollectorState.RUNNING
    }

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

        private val INSTALL_LABELS = setOf("install", "lnstall", "安裝", "立即安裝")
    }
}
