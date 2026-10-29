package com.rewardadguard.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputMethodManager
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.data.RedirectPolicy
import com.rewardadguard.app.detector.AdActivityClassifier
import com.rewardadguard.app.detector.CloseButtonDetector
import com.rewardadguard.app.detector.CloseMatch
import com.rewardadguard.app.detector.ForegroundAppDetector
import com.rewardadguard.app.detector.PackageClassifier
import com.rewardadguard.app.detector.RedirectDetector
import com.rewardadguard.app.guard.AllowlistGuard
import com.rewardadguard.app.guard.AssistDecision
import com.rewardadguard.app.guard.CloseButtonGuard
import com.rewardadguard.app.guard.RedirectDecision
import com.rewardadguard.app.guard.RedirectGuard
import com.rewardadguard.app.guard.SelfClickTracker
import com.rewardadguard.app.guard.SmartRedirectEngine
import com.rewardadguard.app.controller.ReturnController
import com.rewardadguard.app.manager.EventLogger
import com.rewardadguard.app.manager.MonitoringState
import com.rewardadguard.app.manager.RewardAppStore
import com.rewardadguard.app.session.SessionEndReason
import com.rewardadguard.app.session.SessionManager
import com.rewardadguard.app.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The only entry point that observes the system (spec sections 10-12).
 *
 * Design constraints honoured here:
 *  * **Event driven.** No polling loop and no `Handler.postDelayed` polling; the
 *    only delayed work is the single grace timer of a pending redirect and the
 *    return attempt timer of [ReturnController].
 *  * **Cheap callbacks.** `onAccessibilityEvent` performs no disk or database
 *    I/O; settings and the monitored app list are cached in memory and refreshed
 *    by a DataStore flow on [Dispatchers.Default].
 *  * **Fail-soft.** Every stage is wrapped so that an unexpected node tree or a
 *    vendor ROM quirk can never crash the service (spec section 32/33).
 */
class RewardAdAccessibilityService : AccessibilityService() {

    private lateinit var logger: EventLogger
    private lateinit var sessionManager: SessionManager
    private lateinit var foregroundDetector: ForegroundAppDetector
    private lateinit var classifier: PackageClassifier
    private lateinit var redirectDetector: RedirectDetector
    private lateinit var redirectGuard: RedirectGuard
    private lateinit var closeGuard: CloseButtonGuard
    private lateinit var allowlistGuard: AllowlistGuard
    private lateinit var returnController: ReturnController
    private lateinit var rewardAppStore: RewardAppStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cached settings; refreshed by a flow so the hot path never hits disk. */
    @Volatile
    private var settings: AppSettings = AppSettings()

    /** Latest known foreground package (kept for the return controller probe). */
    @Volatile
    private var lastForegroundPackage: String? = null

    @Volatile
    private var lastCloseDetectionAt: Long? = null

    @Volatile
    private var lastUserInteractionAt: Long = 0L

    /**
     * Distinguishes the guard's own assisted clicks from a real finger, so the
     * `TYPE_VIEW_CLICKED` events they generate do not look like user input
     * (field report 2026-10-16, root cause 4.3).
     */
    private val selfClicks = SelfClickTracker()

    @Volatile
    private var pendingGraceJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var lastEventAt: Long = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Input methods installed on the device. Their windows announce themselves
     * with TYPE_WINDOW_STATE_CHANGED like an app would, and treating the keyboard
     * as the foreground app ended the session every time the user typed in the
     * reward app and made the guard's own app list jump away from the focused
     * text field (field report 2026-10-29).
     */
    private var imePackages: Set<String> = emptySet()

    /** `package/class` -> whether that class is an activity of that package. */
    private val activityCache = HashMap<String, Boolean>()

    /** True while the session source shows an ad SDK activity. */
    @Volatile
    private var adActivityInFront: Boolean = false

    /** Last activity class the session source brought to the front. */
    @Volatile
    private var lastSourceActivity: String? = null

    /** A coalesced close-button scan is already queued. */
    private var closeScanQueued = false

    /** The event profile currently installed with [setServiceInfo]. */
    private var installedProfile: EventProfile? = null

    /** In-app landing-page escapes already spent on the current page. */
    private var landingBackAttempts = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            bootstrap()
        } catch (t: Throwable) {
            Log.e(TAG, "onServiceConnected failed", t)
        }
    }

    /** Wires every module exactly once. */
    private fun bootstrap() {
        val container = RewardAdGuardApp.Container
        logger = container.logger
        // Share the container's single store instance: the UI writes through the
        // same object, so a reward app added or removed while this service is
        // running is honoured immediately instead of only after a reconnect.
        rewardAppStore = container.rewardAppStore
        sessionManager = SessionManager(logger)

        classifier = PackageClassifier(applicationContext)
        foregroundDetector = ForegroundAppDetector(applicationContext)
        redirectDetector = RedirectDetector(applicationContext, rewardAppStore, classifier)
        redirectGuard = RedirectGuard(classifier, redirectDetector, logger, sessionManager)
        closeGuard = CloseButtonGuard(logger, sessionManager)
        allowlistGuard = AllowlistGuard(rewardAppStore, logger, sessionManager)

        returnController = ReturnController(
            logger = logger,
            sessionManager = sessionManager,
            backAction = { performGlobalAction(GLOBAL_ACTION_BACK) },
            launchSourceAction = { pkg -> launchRewardApp(pkg) }
        ).also { controller ->
            controller.foregroundPackageProvider = { foregroundDetector.currentPackage }
        }

        // Settings are pushed in, never pulled, so the event path stays I/O free.
        // The one blocking-ish read below happens exactly once per connection.
        scope.launch {
            // Re-throw cancellation instead of letting runCatching swallow it, so a
            // teardown actually stops this coroutine (P1#7).
            try {
                val loaded = container.settingsRepository.settingsNow()
                settings = loaded
                MonitoringState.updateSettings(loaded)
                publishSettingsSummary(loaded)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w(TAG, "settings bootstrap fallback to defaults", t)
            }
        }
        MonitoringState.updateSettings(settings)
        publishSettingsSummary(settings)
        container.settingsRepository.settings
            .onEach { newSettings ->
                settings = newSettings
                MonitoringState.updateSettings(newSettings)
                publishSettingsSummary(newSettings)
            }
            .launchIn(scope)

        // The monitored app list is cached in memory by the store itself.
        rewardAppStore.refresh()
        MonitoringState.updateRewardApps(
            rewardAppStore.all().map { entry ->
                com.rewardadguard.app.manager.RewardAppInfo(
                    packageName = entry.packageName,
                    label = entry.label,
                    enabled = true,
                    protectionMode = entry.mode
                )
            }
        )

        // onServiceConnected starts with an empty foreground state, so a
        // reconnect during an ad used to leave the guard blind: RedirectDetector
        // rejects every transition until the next window event, i.e. the jump
        // that follows is never caught (field report 2026-10-16, root cause 4.2).
        // Seed it from the live window list before announcing the connection.
        imePackages = loadImePackages()
        seedForegroundFromWindows()
        updateEventProfile()

        MonitoringState.setServiceConnected(true)
        MonitoringState.setMonitoring(true)
        publishInstance(this)
        logger.log(
            eventType = EventType.SERVICE_CONNECTED,
            sessionId = NO_SESSION,
            action = "SERVICE_CONNECTED",
            result = "SUCCESS",
            message = "package=${packageName}"
        )
        showStatusNotification()
    }

    /**
     * Drives a synthetic foreground transition through the normal event path.
     *
     * This exists solely for the debug-only synthetic test entry point
     * ([com.rewardadguard.app.debug.DebugTestActivity]), so that the
     * redirect / return / close-button logic can be exercised on demand without
     * spending the once-per-day rewarded ad. It deliberately builds a real
     * [AccessibilityEvent] and hands it to [handleWindowEvent], so the code under
     * test is exactly the production code - only the trigger is synthetic.
     *
     * The event carries the package name and is marked as coming from a window
     * state change, which is what a real app switch produces.
     */
    fun simulateForeground(packageName: String) {
        try {
            // AccessibilityEvent.obtain(int) is deprecated but is the ONLY
            // constructor that exists below API 30 — the public
            // AccessibilityEvent(int) constructor was added in API 30 while
            // minSdk is 26, so using it would crash with NoSuchMethodError on
            // Android 8/9/10. Suppressed deliberately: minSdk 26 is a hard
            // product requirement and this is the supported way to build an
            // event on those versions.
            @Suppress("DEPRECATION")
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
                this.packageName = packageName
                className = "android.app.Activity"
            }
            handleWindowEvent(event)
            // obtain() hands out pooled instances; failing to recycle them
            // leaks the pool. recycle() is deprecated on API 33+ (where the
            // pool was removed) but is still required on older versions.
            @Suppress("DEPRECATION")
            event.recycle()
            Log.i(TAG, "simulateForeground($packageName) delivered")
        } catch (t: Throwable) {
            reportError("simulateForeground", "synthetic transition failed for $packageName", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        MonitoringState.countEventProcessed()
        try {
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> handleWindowEvent(e)

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> queueCloseScan(e)

                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED -> {
                    // Ignore the click events the guard's own assisted action
                    // generated. They are not user activity, and treating them as
                    // such pushed isUserInteracting() true right after our own
                    // click, which suppressed the next assist and downgraded real
                    // redirects to "first low-risk interaction" (field report
                    // 2026-10-16, root cause 4.3).
                    val now = System.currentTimeMillis()
                    if (!selfClicks.isOwnClick(now)) lastUserInteractionAt = now
                }

                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    // A real finger is on the screen: never auto-click alongside it.
                    lastUserInteractionAt = System.currentTimeMillis()
                }

                else -> Unit
            }
        } catch (t: Throwable) {
            reportError("onAccessibilityEvent", "event handling failed", t)
        }
    }

    override fun onInterrupt() {
        logger.log(
            eventType = EventType.SERVICE_INTERRUPTED,
            sessionId = sessionManager.currentSessionId ?: NO_SESSION,
            action = "SERVICE_INTERRUPTED",
            result = "FAILED",
            message = "the system interrupted the accessibility service"
        )
        MonitoringState.setServiceConnected(false)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        runCatching {
            pendingGraceJob?.cancel()
            mainHandler.removeCallbacksAndMessages(null)
            returnController.cancelPending()
            selfClicks.reset()
            sessionManager.endSession(SessionEndReason.SERVICE_DESTROYED)
            logger.log(
                eventType = EventType.SERVICE_DISCONNECTED,
                sessionId = NO_SESSION,
                action = "SERVICE_DISCONNECTED",
                result = "SUCCESS",
                message = "service stopped by the user or the system"
            )
            MonitoringState.setServiceConnected(false)
            MonitoringState.setMonitoring(false)
            // Withdraw the live reference first so a debug test can never drive
            // a service that is on its way out.
            if (instance === this) publishInstance(null)
            scope.cancel()
        }.onFailure { Log.w(TAG, "teardown failed", it) }
    }

    // ---------------------------------------------------------------- window

    /**
     * Restores the foreground package - and, when a monitored app already owns
     * the screen, the session - at connection time.
     *
     * A reconnect produces a fresh instance with an empty foreground state. With
     * an empty `currentPackage`, [RedirectDetector] rejects every transition
     * until an unrelated window event arrives, so the jump that happens right
     * after a reconnect is never seen. Reading the live window list here makes
     * the very next transition detectable instead (field report 2026-10-16,
     * root cause 4.2). Mirrors the "a monitored reward app took the foreground"
     * branch of [handleWindowEvent].
     */
    private fun seedForegroundFromWindows() {
        try {
            val active = foregroundDetector.activeWindowPackage(windows)
            val change = foregroundDetector.resolve(null, active)
            val current = change.currentPackage ?: return
            val now = System.currentTimeMillis()
            lastForegroundPackage = current
            MonitoringState.update { snapshot ->
                snapshot.copy(
                    currentForegroundPackage = current,
                    previousForegroundPackage = null,
                    lastEventAt = now
                )
            }
            if (rewardAppStore.isMonitored(current) && effectiveProtectionEnabled()) {
                sessionManager.startSession(current, now)
            }
        } catch (t: Throwable) {
            reportError("seedForeground", "unable to seed the foreground package", t)
        }
    }

    /**
     * Handles a foreground transition: the heart of the app.
     *
     * Order matters and encodes the permission/ethics policy:
     *  1. resolve the foreground package,
     *  2. if it is a monitored reward app -> session start/continue,
     *  3. if we left a reward app while a session was active -> redirect check,
     *  4. if we came back to the session source -> close the loop,
     *  5. otherwise stay completely passive.
     */
    private fun handleWindowEvent(event: AccessibilityEvent) {
        val eventPackage = event.packageName?.toString()
        // The keyboard and the status bar float above the app the user is in;
        // they are never "the foreground app".
        if (eventPackage != null && isOverlayPackage(eventPackage)) return
        // Querying the window list costs binder round trips on every event; only
        // do it when the event itself does not name its package.
        val activeWindow = if (eventPackage.isNullOrBlank()) {
            foregroundDetector.activeWindowPackage(windows)?.takeUnless { isOverlayPackage(it) }
        } else {
            null
        }
        val change = foregroundDetector.resolve(eventPackage, activeWindow)
        val current = change.currentPackage ?: return
        val previous = change.previousPackage
        val now = System.currentTimeMillis()

        if (change.changed) {
            lastForegroundPackage = current
            MonitoringState.update { snapshot ->
                snapshot.copy(
                    currentForegroundPackage = current,
                    previousForegroundPackage = previous,
                    lastEventAt = now
                )
            }
        }

        val sessionActive = sessionManager.currentState().isActive()
        val sessionSource = sessionManager.activeSession()?.sourcePackage

        // (4) came back to the reward app that owns the session
        if (sessionSource != null && current == sessionSource) {
            handleReturnToSource(current, previous, now)
            onSourceWindow(event, current, now)
            updateEventProfile()
            return
        }

        // (2) a monitored reward app took the foreground
        if (rewardAppStore.isMonitored(current)) {
            val scopeKind = allowlistGuard.evaluate(
                foregroundPackage = current,
                protectionEnabled = effectiveProtectionEnabled(),
                sourcePackage = current
            )
            if (!allowlistGuard.canAct(scopeKind)) {
                allowlistGuard.logPassive(scopeKind, current, "session start suppressed by policy")
                return
            }
            sessionManager.startSession(current, now)
            redirectGuard.clearPending()
            resetAdTracking()
            onSourceWindow(event, current, now)
            updateEventProfile()
            return
        }

        // (3) we left the session source while the session was running.
        //     A jump is only ever the *ad's* fault while the ad is actually on
        //     screen, so only chase the departure in AD_SESSION_ACTIVE. In any
        //     other state the user simply switched apps; end the session instead
        //     of treating a normal app switch as a redirect and dragging them back
        //     (field complaint: "it keeps force-pulling other apps back").
        if (sessionActive && sessionSource != null && previous == sessionSource && current != sessionSource) {
            if (sessionManager.currentState() == SessionState.AD_SESSION_ACTIVE) {
                handlePossibleRedirect(previous, current, event, now)
            } else {
                sessionManager.endSession(SessionEndReason.LEFT_REWARD_APP)
                resetAdTracking()
            }
            updateEventProfile()
            return
        }

        // (5) an unrelated app is in front: snapshot only, never act on it.
        if (sessionActive && sessionSource != null) {
            logger.log(
                eventType = EventType.EXTRA_INFO,
                sessionId = sessionManager.currentSessionId ?: NO_SESSION,
                sourcePackage = sessionSource,
                destinationPackage = current,
                detectionMethod = "ALLOWLIST",
                action = "PASSIVE",
                result = "SKIPPED",
                message = "app not monitored"
            )
        }
    }

    /** A foreground app that is not monitored but was left by a session. */
    private fun handlePossibleRedirect(
        previous: String?,
        current: String,
        event: AccessibilityEvent,
        now: Long
    ) {
        val uriScheme = event.contentDescription?.toString()?.substringBefore("://")
        val decision = redirectGuard.onForegroundChanged(
            previousPackage = previous,
            currentPackage = current,
            settings = settings,
            sessionActive = true,
            sessionSourcePackage = sessionManager.activeSession()?.sourcePackage,
            adSessionActive = sessionManager.currentState() == SessionState.AD_SESSION_ACTIVE,
            lastAction = redirectGuard.pending?.reason,
            lastCloseDetectionAt = lastCloseDetectionAt,
            userInteractionDetected = isUserInteracting(now),
            uriScheme = uriScheme,
            now = now
        ) ?: return

        // The guard already logged REDIRECT_DETECTED / REDIRECT_RISK.
        if (decision.block) {
            executeBlock(decision, current, now)
        } else if (settings.redirectPolicy == RedirectPolicy.BLOCK_IMMEDIATELY) {
            // Policy says "act now"; the engine already decided, stay passive.
            logger.log(
                eventType = EventType.EXTRA_INFO,
                sessionId = sessionManager.currentSessionId ?: NO_SESSION,
                sourcePackage = previous,
                destinationPackage = current,
                detectionMethod = "POLICY",
                action = "PASSIVE",
                result = "SKIPPED",
                message = "policy=BLOCK_IMMEDIATE risk=${decision.riskScore}"
            )
        } else {
            sessionManager.enterState(SessionState.EXTERNAL_APP_DETECTED, now, decision.reason)
            if (settings.smartRedirectEnabled) scheduleGraceCheck(current, now)
        }
    }

    /** Escalates a pending redirect once the grace period elapsed. */
    private fun scheduleGraceCheck(destination: String, now: Long) {
        pendingGraceJob?.cancel()
        val source = sessionManager.activeSession()?.sourcePackage ?: return
        pendingGraceJob = scope.launch {
            delay(settings.blockGraceMillis.coerceAtLeast(MIN_GRACE_MILLIS))
            withContext(Dispatchers.Main) {
                val current = lastForegroundPackage ?: return@withContext
                if (current != destination) return@withContext
                val decision = redirectGuard.onGraceElapsed(settings) ?: return@withContext
                if (decision.block) executeBlock(decision, destination, System.currentTimeMillis())
            }
        }
        logger.log(
            eventType = EventType.POSSIBLE_REDIRECT,
            sessionId = sessionManager.currentSessionId ?: NO_SESSION,
            sourcePackage = source,
            destinationPackage = destination,
            detectionMethod = "GRACE_TIMER",
            action = "GRACE_SCHEDULED",
            result = "ATTEMPTED",
            message = "grace=${settings.blockGraceMillis}ms"
        )
    }

    /**
     * Executes a block: logs the decision, then tries to bring the reward app
     * back. Batching is not implemented because Android gives no supported way
     * to suppress a third-party activity from a non-root app (spec limit 2);
     * the only honest implementation is "return the user quickly".
     */
    private fun executeBlock(decision: RedirectDecision, destination: String, now: Long) {
        val session = sessionManager.activeSession() ?: return
        sessionManager.enterState(SessionState.BLOCKING, now, decision.reason)
        logger.log(
            eventType = EventType.BLOCK,
            sessionId = session.sessionId,
            sourcePackage = session.sourcePackage,
            destinationPackage = destination,
            detectionMethod = "RedirectGuard",
            action = "BLOCK_REDIRECT",
            result = "SUCCESS",
            message = "risk=${decision.riskScore} reason=${decision.reason}"
        )
        sessionManager.recordBlock()
        MonitoringState.update { it.copy(todayBlocked = it.todayBlocked + 1) }

        val useReturn = settings.externalRedirectProtection
        if (!useReturn) {
            logger.log(
                eventType = EventType.BLOCK_SKIPPED,
                sessionId = session.sessionId,
                sourcePackage = session.sourcePackage,
                destinationPackage = destination,
                detectionMethod = "SETTINGS",
                action = "BLOCK_SKIPPED",
                result = "SKIPPED",
                message = "external redirect protection disabled"
            )
            sessionManager.enterState(SessionState.REWARD_APP_ACTIVE, now, "protection_off")
            return
        }

        sessionManager.enterState(SessionState.RETURNING, now, "block_follow_up")
        returnController.scheduleReturn(
            destinationPackage = destination,
            sourcePackage = session.sourcePackage,
            settings = settings,
            delayMillis = RETURN_DELAY_MILLIS
        )
    }

    /** The reward app that owns the running session is in front again. */
    private fun handleReturnToSource(source: String, previous: String?, now: Long) {
        if (previous != null && previous != source) {
            redirectGuard.onReturnedToSource(success = true, now = now)
            logger.log(
                eventType = EventType.RETURNED_TO_SOURCE,
                sessionId = sessionManager.currentSessionId ?: NO_SESSION,
                sourcePackage = source,
                destinationPackage = previous,
                detectionMethod = "FOREGROUND_RETURN",
                action = "RETURNED_TO_SOURCE",
                result = "SUCCESS"
            )
        }
        if (sessionManager.currentState() == SessionState.BLOCKING ||
            sessionManager.currentState() == SessionState.RETURNING
        ) {
            // Back on the ad the jump came from: it can jump again, so stay armed.
            // The window event that brings us back is often the ad's popup or
            // surface rather than its activity, so the last known activity decides.
            val next = if (adActivityInFront) SessionState.AD_SESSION_ACTIVE else SessionState.REWARD_APP_ACTIVE
            sessionManager.enterState(next, now, "returned")
        }
    }

    /**
     * Tracks which activity the session source shows and derives the ad state
     * from it ([AdActivityClassifier]).
     *
     * Only activity transitions count: popups, dialogs and the ad's own surface
     * views also raise TYPE_WINDOW_STATE_CHANGED, and treating those as "the ad
     * closed" would disarm the guard in the middle of an ad.
     */
    private fun onSourceWindow(event: AccessibilityEvent, source: String, now: Long) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val cls = event.className?.toString() ?: return
        if (!isActivityOf(source, cls)) return
        val previousActivity = lastSourceActivity
        lastSourceActivity = cls
        when (AdActivityClassifier.classify(cls, source)) {
            AdActivityClassifier.Kind.AD -> {
                landingBackAttempts = 0
                adActivityInFront = true
                sessionManager.markAdSessionActive("AD_ACTIVITY:${cls.substringAfterLast('.')}", now)
                updateEventProfile()
                observeCloseButton(source, now)
            }
            AdActivityClassifier.Kind.AD_LANDING -> {
                // Only an escape when it was opened from an ad; the same SDK page
                // reached any other way is left alone.
                val fromAd = adActivityInFront || (previousActivity != null &&
                    AdActivityClassifier.classify(previousActivity, source) == AdActivityClassifier.Kind.AD)
                if (fromAd) handleInAppLanding(source, cls, now)
            }
            AdActivityClassifier.Kind.OTHER -> {
                landingBackAttempts = 0
                if (adActivityInFront || sessionManager.currentState() == SessionState.AD_SESSION_ACTIVE) {
                    adActivityInFront = false
                    sessionManager.enterState(SessionState.REWARD_APP_ACTIVE, now, "ad_closed")
                }
                closeGuard.reset()
            }
        }
    }

    /**
     * The ad opened its SDK's in-app browser or store page. The reward app never
     * left the foreground, so there is no package transition for [RedirectGuard]
     * to see; Back is the only - and a sufficient - way to return to the ad.
     */
    private fun handleInAppLanding(source: String, landingClass: String, now: Long) {
        val session = sessionManager.activeSession() ?: return
        val destination = "$source/${landingClass.substringAfterLast('.')}"
        logger.log(
            eventType = EventType.REDIRECT_DETECTED,
            sessionId = session.sessionId,
            sourcePackage = source,
            destinationPackage = destination,
            detectionMethod = "AD_LANDING_ACTIVITY",
            action = "REDIRECT_DETECTED",
            result = "SUCCESS",
            message = "in-app landing page opened by the ad",
            timestamp = now
        )
        sessionManager.recordRedirect(SmartRedirectEngine.MAX_RISK)
        // A per-app mode wins over the global one (spec section 8).
        val mode = rewardAppStore.modeOverride(source) ?: settings.protectionMode
        if (mode != ProtectionMode.BLOCK || settings.redirectPolicy == RedirectPolicy.LOG_ONLY ||
            !settings.externalRedirectProtection
        ) {
            logger.log(
                eventType = EventType.BLOCK_SKIPPED,
                sessionId = session.sessionId,
                sourcePackage = source,
                destinationPackage = destination,
                detectionMethod = "SETTINGS",
                action = "BLOCK_SKIPPED",
                result = "SKIPPED",
                message = "mode=$mode policy=${settings.redirectPolicy}",
                timestamp = now
            )
            return
        }
        // A page that survives three Back presses is not going away; stop rather
        // than keep pressing Back into the reward app itself.
        if (landingBackAttempts >= MAX_LANDING_BACKS) return
        landingBackAttempts++
        sessionManager.recordBlock()
        MonitoringState.update { it.copy(todayBlocked = it.todayBlocked + 1) }
        logger.log(
            eventType = EventType.BLOCK,
            sessionId = session.sessionId,
            sourcePackage = source,
            destinationPackage = destination,
            detectionMethod = "AD_LANDING_ACTIVITY",
            action = "BLOCK_REDIRECT",
            result = "SUCCESS",
            message = "back out of in-app landing page attempt=$landingBackAttempts",
            timestamp = now
        )
        mainHandler.postDelayed({
            val ok = performGlobalAction(GLOBAL_ACTION_BACK)
            logger.log(
                eventType = EventType.RETURN,
                sessionId = sessionManager.currentSessionId ?: NO_SESSION,
                sourcePackage = source,
                destinationPackage = destination,
                detectionMethod = "GLOBAL_ACTION_BACK",
                action = "RETURN_ATTEMPT",
                result = if (ok) "SUCCESS" else "FAILED",
                message = "in-app landing page"
            )
        }, RETURN_DELAY_MILLIS)
    }

    // --------------------------------------------------------- close button

    /**
     * Content changes are only interesting while an ad activity is in front.
     *
     * An animating ad raises dozens of content changes per second, and each scan
     * walks up to [CloseButtonGuard.MAX_NODES] nodes over binder. Scanning on
     * every one of them (10,202 scans in one BitWalk session) is what made the
     * phone stutter, so a burst is coalesced into one scan per
     * [CLOSE_SCAN_INTERVAL_MILLIS].
     */
    private fun queueCloseScan(event: AccessibilityEvent) {
        if (!adActivityInFront || closeScanQueued) return
        val source = sessionManager.activeSession()?.sourcePackage ?: return
        if (event.packageName?.toString() != source) return
        closeScanQueued = true
        mainHandler.postDelayed({
            closeScanQueued = false
            val current = sessionManager.activeSession()?.sourcePackage
            if (current != null && adActivityInFront) observeCloseButton(current, System.currentTimeMillis())
        }, CLOSE_SCAN_INTERVAL_MILLIS)
    }

    /**
     * Close-button detection + (optional) assistance.
     *
     * The detector is only run when the reward app owns the screen, so an X on a
     * third-party app's own UI is never clicked.
     */
    private fun observeCloseButton(ownerPackage: String, now: Long) {
        if (!settings.closeButtonAssistance) {
            closeGuard.reset()
            return
        }
        if (!rewardAppStore.isMonitored(ownerPackage)) return
        if (!adActivityInFront) return

        val root: AccessibilityNodeInfo = try {
            rootInActiveWindow?.takeIf { it.packageName?.toString() == ownerPackage } ?: return
        } catch (t: Throwable) {
            reportError("CloseButtonGuard", "rootInActiveWindow failed", t)
            return
        }

        val metrics = resources.displayMetrics
        val match: CloseMatch? = try {
            closeGuard.findCloseCandidate(root, metrics.widthPixels, metrics.heightPixels)
        } catch (t: Throwable) {
            reportError("CloseButtonGuard", "tree traversal failed", t)
            null
        }

        if (match == null || !match.matched) return

        // Detection rows are written by CloseButtonGuard.onDetectedOnly(), which
        // de-duplicates them. Writing two raw rows here on every scan put 9,250
        // identical CLOSE_DETECT/CLOSE_BOUNDS rows into the 10,000-row log in three
        // hours. A detected X no longer marks an ad session either: on ordinary
        // app screens it kept the guard "in an ad" for hours. The ad activity
        // decides (see onSourceWindow), and only then does this scan run at all.
        lastCloseDetectionAt = now

        val shouldAssist = closeGuard.decide(
            match = match,
            settings = settings,
            isAdWindow = adActivityInFront,
            now = now,
            userInteracting = isUserInteracting(now)
        )

        if (shouldAssist != AssistDecision.ALLOW) {
            // The old code logged a single fabricated "assist action disabled or
            // throttled" line for all seven outcomes, which made a deliberate
            // setting indistinguishable from a throttle or a safety cap. The
            // decision is now logged by name; deliberate choices are not logged
            // at all because onDetectedOnly() already covers them.
            closeGuard.onDetectedOnly(match, ownerPackage, now)
            closeGuard.onDecisionLogged(shouldAssist, match, ownerPackage, now)
            return
        }

        val clicked = performAssistedClose(match)
        closeGuard.onAssisted(match, clicked, ownerPackage, now)
        if (clicked) scheduleCloseVerification(match, ownerPackage)
    }

    /**
     * Confirms, one settle interval after a dispatched click, whether the ad's X
     * is actually gone (P0#2).
     *
     * A dispatched click is only "DISPATCHED"; it becomes VERIFIED only when the
     * matched node no longer matches on the follow-up scan, otherwise the row is
     * UNKNOWN. This stops the log from reporting a sent click as a closed ad.
     */
    private fun scheduleCloseVerification(match: CloseMatch, ownerPackage: String) {
        scope.launch {
            delay(CLOSE_VERIFY_DELAY_MILLIS)
            val stillThere = withContext(Dispatchers.Main) {
                val root = runCatching { rootInActiveWindow }.getOrNull() ?: return@withContext false
                val metrics = resources.displayMetrics
                val current = runCatching {
                    closeGuard.findCloseCandidate(root, metrics.widthPixels, metrics.heightPixels)
                }.getOrNull()
                current != null && current.matched && sameTarget(current, match)
            }
            closeGuard.onVerified(match, verified = !stillThere, destinationPackage = ownerPackage)
        }
    }

    /** True when two matches point at the same on-screen rectangle. */
    private fun sameTarget(a: CloseMatch, b: CloseMatch): Boolean {
        val an = a.node ?: return false
        val bn = b.node ?: return false
        return an.left == bn.left && an.top == bn.top &&
            an.right == bn.right && an.bottom == bn.bottom
    }

    /**
     * Performs the assisted close for a detected X.
     *
     * Primary strategy: a single synthetic tap ([dispatchGesture]) at the X's own
     * centre. `ACTION_CLICK` on a node reports success without dismissing the ad
     * whenever the clickable node is a wrapper around the X (measured: 32
     * successful clicks over ~9 ads and the ad stayed open), whereas a tap at the
     * detected centre lands on the real control. The tap is only ever issued from
     * here, inside the close-button guard, after the detector matched an X and the
     * policy allowed the assist, and only at the X's bounds.
     *
     * Fallback when gesture dispatch is unavailable: `ACTION_CLICK` on the exact
     * node, then on the smallest clickable control sitting on the X centre.
     */
    private fun performAssistedClose(match: CloseMatch): Boolean {
        val snapshot = match.node ?: return false
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        val centerX = (snapshot.left + snapshot.right) / 2
        val centerY = (snapshot.top + snapshot.bottom) / 2

        // Every click below is dispatched by the guard itself. Mark the moment so
        // the TYPE_VIEW_CLICKED it generates is attributed to us and not to a
        // finger (field report 2026-10-16, root cause 4.3).
        selfClicks.markOwnClick(System.currentTimeMillis())

        // 0) Tap the X centre directly -- the most reliable dismiss.
        if (tapPoint(centerX, centerY)) return true

        // Walk the live tree once, remembering each node's parent, and collect:
        //  - byBounds/byId/byDescription: the same node the detector saw, re-found
        //    after a possible re-layout (bounds move, id/description usually do not)
        //  - clickableAtCenter: the SMALLEST clickable control whose bounds contain
        //    the X centre -- the actual dismiss target. The previous version climbed
        //    to the nearest clickable *ancestor* instead, which for an X nested in a
        //    large ad container resolved to the container: ACTION_CLICK then
        //    "succeeded" without closing the ad, exactly the measured field failure.
        val parents = HashMap<AccessibilityNodeInfo, AccessibilityNodeInfo?>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        parents[root] = null
        var visited = 0
        var byBounds: AccessibilityNodeInfo? = null
        var byId: AccessibilityNodeInfo? = null
        var byDescription: AccessibilityNodeInfo? = null
        var clickableAtCenter: AccessibilityNodeInfo? = null
        var smallestArea = Int.MAX_VALUE
        val rect = android.graphics.Rect()
        while (queue.isNotEmpty() && visited < CloseButtonGuard.MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            node.getBoundsInScreen(rect)
            if (byBounds == null && matches(node, snapshot)) byBounds = node
            if (byId == null && !snapshot.viewIdResourceName.isNullOrEmpty() &&
                node.viewIdResourceName == snapshot.viewIdResourceName
            ) byId = node
            if (byDescription == null && !snapshot.contentDescription.isNullOrEmpty() &&
                node.contentDescription?.toString() == snapshot.contentDescription
            ) byDescription = node
            if (node.isClickable && rect.contains(centerX, centerY)) {
                val area = rect.width() * rect.height()
                if (area in 1 until smallestArea) {
                    smallestArea = area
                    clickableAtCenter = node
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child -> parents[child] = node; queue.addLast(child) }
            }
        }

        // 1) The exact node, if it is itself clickable.
        byBounds?.takeIf { it.isClickable }?.let { return clickNode(it) }
        // 2) The most specific clickable control sitting on the X.
        clickableAtCenter?.let { return clickNode(it) }
        // 3) Climb from the matched node, then from the id/description matches.
        val target = byBounds ?: byId ?: byDescription ?: return false
        var candidate: AccessibilityNodeInfo? = target
        while (candidate != null) {
            val current = candidate
            if (current.isClickable) return clickNode(current)
            candidate = parents[current]
        }
        // 4) Last resort: some ad X views answer ACTION_CLICK without advertising
        //    isClickable.
        return clickNode(target)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean = try {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    } catch (t: Throwable) {
        reportError("CloseButtonGuard", "assisted click failed", t)
        false
    }

    /**
     * Dispatches a one-shot synthetic tap at (x, y).
     *
     * Requires `android:canPerformGestures="true"`. It is deliberately only used
     * for the centre of a *detected* close control, so it can never wander onto a
     * CTA elsewhere on screen.
     */
    private fun tapPoint(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    private fun matches(node: AccessibilityNodeInfo, snapshot: com.rewardadguard.app.detector.NodeSnapshot): Boolean {
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        return rect.left == snapshot.left && rect.top == snapshot.top &&
            rect.right == snapshot.right && rect.bottom == snapshot.bottom
    }

    // ---------------------------------------------------------------- helpers

    /** A tap within [INTERACTION_GRACE_MILLIS] suppresses assisted actions. */
    private fun isUserInteracting(now: Long): Boolean =
        now - lastUserInteractionAt < INTERACTION_GRACE_MILLIS

    /** The keyboard, the status bar and other windows that float over apps. */
    private fun isOverlayPackage(pkg: String): Boolean =
        pkg in imePackages || pkg == SYSTEM_UI_PACKAGE ||
            pkg.contains("inputmethod", ignoreCase = true)

    private fun loadImePackages(): Set<String> = runCatching {
        getSystemService(InputMethodManager::class.java)
            ?.inputMethodList
            ?.map { it.packageName }
            ?.toSet()
    }.getOrNull().orEmpty()

    /**
     * True when [cls] is an activity of [pkg]. Window-state events are also
     * raised for dialogs, popups and plain views (`android.widget.FrameLayout`),
     * which must not move the ad state. Cached: the answer does not change while
     * the app stays installed.
     */
    private fun isActivityOf(pkg: String, cls: String): Boolean {
        if ('.' !in cls || cls.startsWith("android.widget.") || cls.startsWith("android.view.")) return false
        val key = "$pkg/$cls"
        activityCache[key]?.let { return it }
        val result = runCatching {
            packageManager.getActivityInfo(ComponentName(pkg, cls), 0)
            true
        }.getOrDefault(false)
        if (activityCache.size >= ACTIVITY_CACHE_LIMIT) activityCache.clear()
        activityCache[key] = result
        return result
    }

    private fun resetAdTracking() {
        adActivityInFront = false
        lastSourceActivity = null
        landingBackAttempts = 0
        closeGuard.reset()
    }

    /**
     * How much the system has to send us, and how much of every app's node tree
     * it has to expose.
     *
     * Every subscribed event type is delivered from *every* app on the phone, and
     * `flagIncludeNotImportantViews` enlarges every app's tree. Holding the full
     * set all the time is what made the phone stutter with the service enabled.
     * The guard only needs:
     *  - IDLE: window-state changes, to notice a reward app coming to the front;
     *  - SESSION: plus clicks (user-interaction signal) and window-list changes;
     *  - AD: plus content changes and unimportant views, for the close button.
     */
    private enum class EventProfile { IDLE, SESSION, AD }

    private fun updateEventProfile() {
        val wanted = when {
            sessionManager.activeSession() == null -> EventProfile.IDLE
            adActivityInFront && settings.closeButtonAssistance -> EventProfile.AD
            else -> EventProfile.SESSION
        }
        if (wanted == installedProfile) return
        val info = serviceInfo ?: return
        var types = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        var flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (wanted != EventProfile.IDLE) {
            types = types or AccessibilityEvent.TYPE_WINDOWS_CHANGED or AccessibilityEvent.TYPE_VIEW_CLICKED
        }
        if (wanted == EventProfile.AD) {
            types = types or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        info.eventTypes = types
        info.flags = flags
        runCatching { serviceInfo = info }
            .onSuccess { installedProfile = wanted }
            .onFailure { Log.w(TAG, "setServiceInfo failed", it) }
    }

    /** Mirrors assist settings into the dashboard snapshot (P0#1). */
    private fun publishSettingsSummary(s: AppSettings) {
        MonitoringState.updateSettingsSummary(s.assistAction, s.closeButtonAssistance)
    }

    /**
     * Global protection state. A per-app override stored in the reward app list
     * wins over the global mode (spec section 8).
     */
    private fun effectiveProtectionEnabled(): Boolean {        if (settings.protectionMode == ProtectionMode.OFF) return false
        val override = rewardAppStore.modeOverride(lastForegroundPackage)
        return override != ProtectionMode.OFF
    }

    private fun launchRewardApp(packageName: String): Boolean = try {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) {
            false
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            startActivity(intent)
            true
        }
    } catch (t: Throwable) {
        reportError("ReturnController", "unable to launch $packageName", t)
        false
    }

    /** Records a module failure without ever re-throwing. */
    private fun reportError(module: String, message: String, error: Throwable) {
        Log.e(TAG, "$module: $message", error)
        runCatching {
            sessionManager.recordError(module, message, error.message)
            MonitoringState.update { it.copy(errors = it.errors + 1) }
        }
    }

    /**
     * Optional low-priority status notification (spec section 34).
     *
     * It exists so the user can always see that monitoring is running and can
     * jump straight to the log export. It carries no ad content and no personal
     * data.
     */
    private fun showStatusNotification() {
        if (!settings.statusNotificationEnabled) return
        // Android 13+ (API 33) requires POST_NOTIFICATIONS to be granted at
        // runtime; without it NotificationManager.notify() is silently dropped
        // and the debug log fills with "status notification unavailable"
        // even though nothing is actually broken. Check first so the absence
        // of the notification has an explicit, non-scary explanation.
        val canPost = canPostNotifications()
        // Publish the answer so the dashboard can say *why* there is no status
        // notification instead of leaving the user guessing (ISS-15).
        MonitoringState.update { it.copy(notificationPermissionGranted = canPost) }
        if (!canPost) {
            Log.i(TAG, "status notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        try {
            val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
            val channel = android.app.NotificationChannel(
                LogExportReceiver.CHANNEL_ID,
                getString(com.rewardadguard.app.R.string.app_name),
                android.app.NotificationManager.IMPORTANCE_MIN
            ).apply { description = getString(com.rewardadguard.app.R.string.notification_channel_description) }
            manager.createNotificationChannel(channel)

            val openApp = android.app.PendingIntent.getActivity(
                this,
                0,
                Intent(this, com.rewardadguard.app.ui.MainActivity::class.java),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val export = android.app.PendingIntent.getBroadcast(
                this,
                1,
                Intent(LogExportReceiver.ACTION_EXPORT).setPackage(packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = androidx.core.app.NotificationCompat
                .Builder(this, LogExportReceiver.CHANNEL_ID)
                .setSmallIcon(com.rewardadguard.app.R.drawable.ic_launcher_foreground)
                .setContentTitle(getString(com.rewardadguard.app.R.string.notification_title))
                .setContentText(getString(com.rewardadguard.app.R.string.notification_text))
                .setOngoing(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MIN)
                .setContentIntent(openApp)
                .addAction(0, getString(com.rewardadguard.app.R.string.action_export_log), export)
                .build()
            manager.notify(LogExportReceiver.NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Log.w(TAG, "status notification unavailable", t)
        }
    }

    /**
     * True when this process may actually post notifications.
     *
     * Below API 33 no runtime permission exists, so the answer is always true.
     * On API 33+ it delegates to [NotificationManager.areNotificationsEnabled],
     * which also covers the user having switched the channel off in Settings.
     * Kept tolerant: any failure means "cannot post", never a crash.
     */
    private fun canPostNotifications(): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return true
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        getSystemService(android.app.NotificationManager::class.java)
            ?.areNotificationsEnabled() ?: false
    }.getOrDefault(false)

    companion object {
        private const val TAG = "RewardAdGuardService"

        /** Session id used for events that belong to no session. */
        const val NO_SESSION = "-"

        /** Never escalate faster than this, even if the user set a lower value. */
        const val MIN_GRACE_MILLIS = 200L

        /** Small pause before pressing Back so the activity is fully laid out. */
        const val RETURN_DELAY_MILLIS = 120L

        /** A tap this recent disables assisted clicking. */
        const val INTERACTION_GRACE_MILLIS = 1_200L

        /** Delay before re-scanning to verify that an assisted close worked (P0#2). */
        const val CLOSE_VERIFY_DELAY_MILLIS = 600L

        /** At most one close-button scan per this interval while an ad animates. */
        const val CLOSE_SCAN_INTERVAL_MILLIS = 500L

        /** Back presses spent on one in-app landing page before giving up. */
        const val MAX_LANDING_BACKS = 3

        private const val ACTIVITY_CACHE_LIMIT = 256

        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"

        /**
         * Live service instance, or null when the service is not bound.
         *
         * Written only while connected and cleared on teardown, so it can never
         * hold a stale reference to a destroyed instance. Used by the debug-only
         * [com.rewardadguard.app.debug.DebugTestActivity] to drive synthetic
         * foreground transitions; nothing in the production path reads it.
         */
        @Volatile
        var instance: RewardAdAccessibilityService? = null
            private set

        /** Called by the service itself to publish/withdraw [instance]. */
        internal fun publishInstance(service: RewardAdAccessibilityService?) {
            instance = service
        }
    }
}
