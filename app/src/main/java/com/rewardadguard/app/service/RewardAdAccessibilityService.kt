package com.rewardadguard.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.collector.CollectorEnvironment
import com.rewardadguard.app.collector.CollectorState
import com.rewardadguard.app.collector.Foreground
import com.rewardadguard.app.collector.GuardYield
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.data.RedirectPolicy
import com.rewardadguard.app.detector.AdActionDetector
import com.rewardadguard.app.detector.AdActionMatch
import com.rewardadguard.app.detector.AdActivityClassifier
import com.rewardadguard.app.detector.CloseButtonDetector
import com.rewardadguard.app.detector.CloseMatch
import com.rewardadguard.app.detector.ForegroundAppDetector
import com.rewardadguard.app.detector.PackageClassifier
import com.rewardadguard.app.detector.RedirectDetector
import com.rewardadguard.app.detector.SystemOverlayPackages
import com.rewardadguard.app.guard.AllowlistGuard
import com.rewardadguard.app.guard.AdActionDecision
import com.rewardadguard.app.guard.AdActionGuard
import com.rewardadguard.app.guard.AssistDecision
import com.rewardadguard.app.guard.CloseButtonGuard
import com.rewardadguard.app.guard.EventStaleness
import com.rewardadguard.app.guard.LandingBackGate
import com.rewardadguard.app.guard.RedirectChainPolicy
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
    private lateinit var adActionGuard: AdActionGuard
    private lateinit var allowlistGuard: AllowlistGuard
    private lateinit var returnController: ReturnController
    private lateinit var rewardAppStore: RewardAppStore

    /**
     * Owns the collector's runtime state and its keep-screen-on overlay.
     *
     * The guard must not run its own clicks while the collector drives the
     * screen (integration plan §3), so this state is read on the guard's event
     * path. It is IDLE until the collector engine starts, which keeps today's
     * behaviour exactly as it was.
     */
    private lateinit var collectorEnvironment: CollectorEnvironment

    /** What the collector is doing right now; IDLE until a run starts. */
    internal val collectorState: CollectorState
        get() = if (::collectorEnvironment.isInitialized) {
            collectorEnvironment.state
        } else {
            CollectorState.IDLE
        }

    /** Rate limit of the "guard yielded to the collector" rows. */
    private var lastCollectorYieldLogAt: Long = 0L
    private var suppressedCollectorYields: Int = 0

    /**
     * The collector's runtime handle, or null before the service is connected.
     * The engine, the debug harness and the (phase 5) UI all drive the same
     * instance, so there is one owner of the state and one wake overlay.
     */
    internal fun collectorRuntime(): CollectorEnvironment? =
        if (::collectorEnvironment.isInitialized) collectorEnvironment else null

    /** Foreground package/activity sampled from the accessibility stream. */
    internal fun collectorForeground(): Foreground =
        Foreground(lastForegroundPackage, lastSourceActivity)

    /** The service's own self-click marker; collector taps must share it. */
    internal fun collectorSelfClicks(): SelfClickTracker = selfClicks

    /**
     * Tracks whether a foreground change is still the ad's redirect chain,
     * including hops separated by system overlays (field report 2026-10-30).
     */
    private val redirectChain = RedirectChainPolicy()

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

    /** An ad-action re-scan is already queued (see scheduleAdActionRescan). */
    private var adActionRescanQueued = false

    /** The event profile currently installed with [setServiceInfo]. */
    private var installedProfile: EventProfile? = null

    /** Guarding is paused or stopped by the user (see [applyPauseState]). */
    @Volatile
    private var guardPaused = false

    /** Ends a timed pause; re-posted whenever the pause end changes. */
    private val resumeRunnable = Runnable { applyPauseState() }

    /** Rate limit of [logStaleEvent]. */
    private var lastStaleLogAt: Long = 0L
    private var suppressedStaleEvents: Int = 0

    /** Back presses for the ad's in-app landing pages (review 2026-11-02). */
    private val landingGate = LandingBackGate()

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
        adActionGuard = AdActionGuard(logger, sessionManager)
        allowlistGuard = AllowlistGuard(rewardAppStore, logger, sessionManager)

        returnController = ReturnController(
            logger = logger,
            sessionManager = sessionManager,
            backAction = { guardedBackForReturn() },
            launchSourceAction = { pkg -> guardedLaunchForReturn(pkg) }
        ).also { controller ->
            controller.foregroundPackageProvider = { foregroundDetector.currentPackage }
        }

        // The collector is IDLE here: constructing the environment only wires
        // the state and the (not yet shown) wake overlay. Its onStarted hook
        // clears work the guard queued before the collector took the screen.
        collectorEnvironment = CollectorEnvironment(
            service = this,
            logger = logger,
            userInteractionAt = { lastUserInteractionAt },
            onStarted = {
                pendingGraceJob?.cancel()
                returnController.cancelPending()
                redirectGuard.clearPending()
            }
        )

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
                mainHandler.post { applyPauseState() }
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
                mainHandler.post { applyPauseState() }
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
        // A paused guard subscribes to no events; anything already queued when
        // the pause began is dropped here.
        if (guardPaused) return
        // A running collector must stop the moment the phone is locked; the
        // check is only made while it runs, so an IDLE guard's hot path is
        // unchanged.
        if (collectorState == CollectorState.RUNNING) {
            collectorEnvironment.checkInterruptionsThrottled()
        }
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
                    if (!selfClicks.isOwnClick(now)) noteUserInteraction(now)
                }

                AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                    // A real finger is on the screen: never auto-click alongside it.
                    noteUserInteraction(System.currentTimeMillis())
                }

                else -> Unit
            }
        } catch (t: Throwable) {
            reportError("onAccessibilityEvent", "event handling failed", t)
        }
    }

    override fun onInterrupt() {
        // onInterrupt is the system asking feedback to stop, not the service
        // being disabled (that is onUnbind/onDestroy -> teardown, which stops
        // the collector). It can still arrive before onServiceConnected set
        // these up.
        if (!::logger.isInitialized || !::sessionManager.isInitialized) return
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

    @Volatile
    private var tornDown = false

    /**
     * Runs once (onUnbind is followed by onDestroy) and every step on its own:
     * one lateinit that a half-finished bootstrap never set used to skip the
     * rest, including withdrawing the instance and cancelling the scope.
     */
    private fun teardown() {
        if (tornDown) return
        tornDown = true
        fun step(name: String, block: () -> Unit) {
            runCatching(block).onFailure { Log.w(TAG, "teardown step $name failed", it) }
        }
        // Withdraw the live reference first so a debug test can never drive
        // a service that is on its way out.
        step("instance") { if (instance === this) publishInstance(null) }
        step("collector") { if (::collectorEnvironment.isInitialized) collectorEnvironment.requestStop() }
        step("grace") { pendingGraceJob?.cancel() }
        step("handler") { mainHandler.removeCallbacksAndMessages(null) }
        step("return") { if (::returnController.isInitialized) returnController.cancelPending() }
        step("selfClicks") { selfClicks.reset() }
        step("session") {
            if (::sessionManager.isInitialized) sessionManager.endSession(SessionEndReason.SERVICE_DESTROYED)
        }
        step("log") {
            if (::logger.isInitialized) {
                logger.log(
                    eventType = EventType.SERVICE_DISCONNECTED,
                    sessionId = NO_SESSION,
                    action = "SERVICE_DISCONNECTED",
                    result = "SUCCESS",
                    message = "service stopped by the user or the system"
                )
            }
        }
        step("state") {
            MonitoringState.setServiceConnected(false)
            MonitoringState.setMonitoring(false)
        }
        step("scope") { scope.cancel() }
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
            // The window list needs flagRetrieveInteractiveWindows, which the
            // event profiles no longer request; the active window's root works
            // without it.
            val active = (foregroundDetector.activeWindowPackage(windows)
                ?: runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull())
                ?.takeUnless { isOverlayPackage(it) }
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
            (foregroundDetector.activeWindowPackage(windows)
                ?: runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull())
                ?.takeUnless { isOverlayPackage(it) }
        } else {
            null
        }
        val change = foregroundDetector.resolve(eventPackage, activeWindow)
        val current = change.currentPackage ?: return
        val previous = change.previousPackage
        val now = System.currentTimeMillis()
        // When the transition really happened. A stale event (a backlog the
        // system delivered late) still updates what the guard knows, but never
        // makes it act (review 2026-11-02: the user's own LINE was closed).
        val ageMillis = EventStaleness.ageMillis(event.eventTime, SystemClock.uptimeMillis())
        val stale = EventStaleness.isStale(ageMillis)
        val happenedAt = now - ageMillis

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
            onSourceWindow(event, current, now, ageMillis)
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
            redirectChain.reset()
            resetAdTracking()
            onSourceWindow(event, current, now, ageMillis)
            updateEventProfile()
            return
        }

        // (3) the session source is no longer in front. [RedirectChainPolicy]
        //     decides whether this is still the ad's chain - even when overlays
        //     or a second hop sit between the reward app and the destination -
        //     or the user switching apps on purpose.
        //     A jump is only ever the *ad's* fault while the ad is actually on
        //     screen, so a plain app switch ends the session instead of dragging
        //     the user back (field complaint: "it keeps force-pulling other apps
        //     back"). Home always ends it too (field report 2026-10-30).
        if (sessionActive && sessionSource != null) {
            val outcome = redirectChain.onTransition(
                sourcePackage = sessionSource,
                previousPackage = previous,
                currentPackage = current,
                sessionState = sessionManager.currentState(),
                now = happenedAt,
                isOverlay = isOverlayPackage(current),
                // Google search / Assistant / Gemini / Lens are the user's own
                // choice, like Home (2026-11-04: the guard closed them).
                isHome = classifier.isHomePackage(current) || SystemOverlayPackages.isUserSummoned(current),
                isSelf = current == packageName
            )
            when (outcome) {
                RedirectChainPolicy.Outcome.REDIRECT -> {
                    if (stale) {
                        // Whatever this jump was, it is over: the app in front now
                        // may be one the user opened since. Blocking it would press
                        // Back in that app.
                        logStaleEvent(current, ageMillis, now)
                        return
                    }
                    // A chained hop has another external app as its "previous",
                    // so tell the detector the departure from the source was
                    // already established.
                    handlePossibleRedirect(
                        previous = previous,
                        current = current,
                        event = event,
                        now = now,
                        chained = previous != sessionSource
                    )
                    updateEventProfile()
                    return
                }

                RedirectChainPolicy.Outcome.END_SESSION -> {
                    // The user left on purpose (Home) or the chase cap was hit:
                    // a return that is still queued would press BACK on the
                    // launcher and, with auto-launch, reopen the reward app
                    // seconds later (review of field report 2026-10-30).
                    returnController.cancelPending()
                    redirectGuard.clearPending()
                    sessionManager.endSession(SessionEndReason.LEFT_REWARD_APP, now)
                    resetAdTracking()
                    redirectChain.reset()
                    updateEventProfile()
                    return
                }

                RedirectChainPolicy.Outcome.IGNORE -> Unit
            }
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

    /**
     * A foreground app that is not monitored but was left by a session.
     *
     * @param chained true when this is a later hop of the ad's chain, so the
     *        immediately previous package is another external app rather than
     *        the reward app (field report 2026-10-30).
     */
    private fun handlePossibleRedirect(
        previous: String?,
        current: String,
        event: AccessibilityEvent,
        now: Long,
        chained: Boolean = false
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
            chainedRedirect = chained,
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
        // A running collector owns the return decision: the guard's Back would
        // fight the collector's own navigation and could close the ad it is
        // waiting on. Detection above already logged the redirect; phase 2 hands
        // that result to the collector instead.
        if (!GuardYield.shouldGuardAct(collectorState)) {
            logCollectorYield("redirect return")
            return
        }
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
        // The reward app is back in front, so any queued Back press would land on
        // its main screen and close it (field report 2026-10-30). Cancel the
        // whole pending return chain before doing anything else.
        // The chain is cancelled before it can log its own result, so record
        // the success here; without it most successful returns left no RETURN
        // row (review 2026-11-02: 14 blocks, 2 RETURN rows).
        if (returnController.hasPendingReturn) {
            logger.log(
                eventType = EventType.RETURN,
                sessionId = sessionManager.currentSessionId ?: NO_SESSION,
                sourcePackage = source,
                destinationPackage = previous,
                detectionMethod = "RETURNED_DURING_RETURN",
                action = "RETURN_ATTEMPT",
                result = "SUCCESS",
                message = "reward app back in front before the return chain finished",
                timestamp = now
            )
        }
        returnController.cancelPending()
        // The redirect is over; a grace timer from it must not fire on the
        // next jump to the same app (review 2026-11-12 #3).
        pendingGraceJob?.cancel()
        redirectChain.onSourceReturned()
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
    private fun onSourceWindow(event: AccessibilityEvent, source: String, now: Long, ageMillis: Long = 0L) {
        val stale = EventStaleness.isStale(ageMillis)
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val cls = event.className?.toString() ?: return
        if (!isActivityOf(source, cls)) return
        val previousActivity = lastSourceActivity
        lastSourceActivity = cls
        when (AdActivityClassifier.classify(cls, source)) {
            AdActivityClassifier.Kind.AD -> {
                // The landing gate is NOT reset here: coming back from a landing
                // page raises the ad activity's own event, and resetting on it let
                // a duplicate landing event schedule a second Back (07:31 case).
                // The `[>>]` cap is per ad: a different ad activity, or an ad
                // entered from the app's own screen, starts a fresh count
                // (review 2026-11-02).
                if (!adActivityInFront || (previousActivity != cls &&
                        previousActivity?.let { AdActivityClassifier.classify(it, source) } == AdActivityClassifier.Kind.AD)
                ) {
                    adActionGuard.reset()
                    // The redirect-chase budget is per ad too (2026-11-02 test).
                    redirectChain.onNewAd()
                }
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
                if (fromAd && stale) {
                    // The page may be long gone; Back would land on the ad itself.
                    logStaleEvent("$source/${cls.substringAfterLast('.')}", ageMillis, now)
                } else if (fromAd) {
                    handleInAppLanding(source, cls, now)
                }
            }
            AdActivityClassifier.Kind.OTHER -> {
                landingGate.reset()
                if (adActivityInFront) redirectChain.onNewAd()
                if (adActivityInFront || sessionManager.currentState() == SessionState.AD_SESSION_ACTIVE) {
                    adActivityInFront = false
                    sessionManager.enterState(SessionState.REWARD_APP_ACTIVE, now, "ad_closed")
                }
                closeGuard.reset()
                adActionGuard.reset()
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
        // Same rule as executeBlock: the landing page is still *detected* (the
        // row above is written), but the automatic Back is the collector's call
        // while it runs.
        if (!GuardYield.shouldGuardAct(collectorState)) {
            logCollectorYield("in-app landing return")
            return
        }
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
        // One Back per landing page: a duplicate event for a page whose Back is
        // still on its way must not schedule another one, which would land on
        // the ad itself (review 2026-11-02). A page that survives several
        // presses is not going away either; stop rather than keep pressing Back
        // into the reward app.
        if (!landingGate.shouldScheduleBack(now)) return
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
            message = "back out of in-app landing page attempt=${landingGate.attempts}",
            timestamp = now
        )
        mainHandler.postDelayed({
            // Back is only safe while the landing page is still what the user is
            // looking at. If they switched apps in the meantime this press would
            // close that app instead, so it is skipped.
            // The page itself must still be the activity in front: if the ad is
            // already back, this press would close the ad.
            if (lastForegroundPackage != source || !adActivityInFront || lastSourceActivity != landingClass) {
                logger.log(
                    eventType = EventType.RETURN,
                    sessionId = sessionManager.currentSessionId ?: NO_SESSION,
                    sourcePackage = source,
                    destinationPackage = destination,
                    detectionMethod = "FOREGROUND_CHANGED",
                    action = "RETURN_SKIPPED",
                    result = "SKIPPED",
                    message = "landing page is no longer in front"
                )
                return@postDelayed
            }
            // The runnable fires up to RETURN_DELAY_MILLIS later. A collector
            // that started in between owns the screen now, so the Back must not
            // land on its ad.
            if (!GuardYield.shouldGuardAct(collectorState)) {
                logCollectorYield("in-app landing back")
                return@postDelayed
            }
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
        // The collector decides what to tap while it runs. Logged (rate limited)
        // rather than done silently, so a field run shows why the guard was
        // quiet even though an X was on screen.
        if (!GuardYield.shouldGuardAct(collectorState)) {
            logCollectorYield("close-button assist")
            return
        }
        if (!settings.closeButtonAssistance) {
            closeGuard.reset()
            return
        }
        if (!rewardAppStore.isMonitored(ownerPackage)) return
        if (!adActivityInFront) return
        // The monitored reward app must still be the *foreground* app. Without
        // this, a scan queued just before the user switched to another app could
        // run against whatever is on screen now (field issue: a normal app's
        // screen was treated as an ad and clicked). An unknown foreground is not
        // good enough either: nothing is clicked unless it is verified.
        if (lastForegroundPackage != ownerPackage) return

        val root: AccessibilityNodeInfo = try {
            rootInActiveWindow?.takeIf { it.packageName?.toString() == ownerPackage } ?: return
        } catch (t: Throwable) {
            reportError("CloseButtonGuard", "rootInActiveWindow failed", t)
            return
        }

        val metrics = resources.displayMetrics
        val snapshots = try {
            closeGuard.collectSnapshots(root)
        } catch (t: Throwable) {
            reportError("CloseButtonGuard", "tree traversal failed", t)
            return
        }

        // Feature 1/2: the literal `[close]` and `[>>]` / `[->]` labels win over
        // the generic X heuristic. Only a node of the verified reward-app window
        // can reach this point.
        val adAction = AdActionDetector.bestMatch(
            snapshots,
            metrics.widthPixels,
            metrics.heightPixels
        )
        if (adAction != null) {
            handleAdAction(adAction, ownerPackage, now, root.packageName?.toString() == ownerPackage)
            return
        }
        // No token on screen: whatever was clicked last is gone, so the same
        // rectangle may hold the next page's control.
        adActionGuard.onScreenCleared()

        val match: CloseMatch? = try {
            closeGuard.matchFrom(snapshots, metrics.widthPixels, metrics.heightPixels)
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
            userInteracting = isUserInteracting(now),
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels
        )

        if (shouldAssist != AssistDecision.ALLOW) {
            // The old code logged a single fabricated "assist action disabled or
            // throttled" line for all seven outcomes, which made a deliberate
            // setting indistinguishable from a throttle or a safety cap. The
            // decision is now logged by name; deliberate choices are not logged
            // at all because onDetectedOnly() already covers them.
            val written = closeGuard.onDetectedOnly(match, ownerPackage, now)
            // An unsafe target stays on screen for the whole ad and is re-scanned
            // every 500 ms; log its decision only alongside a new detection row.
            if (shouldAssist != AssistDecision.UNSAFE_TARGET || written) {
                closeGuard.onDecisionLogged(shouldAssist, match, ownerPackage, now)
            }
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

    // --------------------------------------------------------- ad page actions

    /**
     * Acts on a `[close]` / `[>>]` / `[->]` control.
     *
     * Every click is gated on the monitored reward app being the verified
     * foreground app; a normal app showing the same labels is never touched.
     */
    private fun handleAdAction(
        match: AdActionMatch,
        ownerPackage: String,
        now: Long,
        rootPackageMatches: Boolean
    ) {
        val decision = adActionGuard.decide(
            match = match,
            settings = settings,
            adWindowMonitored = adActivityInFront && rewardAppStore.isMonitored(ownerPackage),
            rootPackageMatches = rootPackageMatches,
            foregroundPackage = lastForegroundPackage,
            expectedPackage = ownerPackage,
            userInteracting = isUserInteracting(now),
            now = now
        )
        if (decision != AdActionDecision.ALLOW) {
            adActionGuard.onSkipped(decision, match, ownerPackage, now)
            // A rejected click is not the end of the ad flow: the throttle only
            // defers it. Re-scan once the throttle clears, otherwise a static
            // next page (no content-change events) would never be acted on.
            if (decision == AdActionDecision.THROTTLED) {
                scheduleAdActionRescan(ownerPackage)
            }
            return
        }
        if (!performAdAction(match, ownerPackage)) {
            adActionGuard.onSkipped(AdActionDecision.NOT_OPERABLE, match, ownerPackage, now)
            return
        }
        adActionGuard.onDispatched(match, ownerPackage, now)
        scheduleAdActionResult(match, ownerPackage)
        // Keep watching after the click: the next ad step may be a static screen
        // that raises no content-change event of its own.
        scheduleAdActionRescan(ownerPackage, AdActionGuard.THROTTLE_MILLIS + 100L)
    }

    /** Re-runs the ad-page scan while the same monitored ad is still in front. */
    private fun scheduleAdActionRescan(ownerPackage: String, delayMillis: Long = 0L) {
        // One pending re-scan at most: every throttled content change used to
        // queue another full tree walk, undoing the scan coalescing of
        // queueCloseScan (review 2026-11-02).
        if (adActionRescanQueued) return
        adActionRescanQueued = true
        val delay = if (delayMillis > 0L) {
            delayMillis
        } else {
            adActionGuard.millisUntilThrottleClears() + 50L
        }
        mainHandler.postDelayed({
            adActionRescanQueued = false
            val current = sessionManager.activeSession()?.sourcePackage ?: return@postDelayed
            if (current != ownerPackage) return@postDelayed
            if (adActivityInFront && lastForegroundPackage == ownerPackage) {
                observeCloseButton(ownerPackage, System.currentTimeMillis())
            }
        }, delay)
    }

    /**
     * Re-reads the live tree and clicks [match] on top of the *labelled* node.
     *
     * The snapshot's coordinates are only used to recognise the control; the
     * click target is resolved from the current tree and its own current bounds,
     * so a re-laid-out page never receives a tap at the previous screen's
     * coordinates. A labelled node that has no clickable control around it is
     * left alone.
     */
    private fun performAdAction(match: AdActionMatch, ownerPackage: String): Boolean {
        val snapshot = match.node
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return false
        if (root.packageName?.toString() != ownerPackage) return false
        if (lastForegroundPackage != ownerPackage) return false
        if (!rewardAppStore.isMonitored(ownerPackage)) return false

        val centerX = (snapshot.left + snapshot.right) / 2
        val centerY = (snapshot.top + snapshot.bottom) / 2

        // The click below is the guard's own; do not let it look like a finger.
        selfClicks.markOwnClick(System.currentTimeMillis())

        val parents = HashMap<AccessibilityNodeInfo, AccessibilityNodeInfo?>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        parents[root] = null
        var visited = 0
        var byBounds: AccessibilityNodeInfo? = null
        var byLabel: AccessibilityNodeInfo? = null
        var clickableAtCenter: AccessibilityNodeInfo? = null
        var smallestArea = Int.MAX_VALUE
        val rect = android.graphics.Rect()
        while (queue.isNotEmpty() && visited < CloseButtonGuard.MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            node.getBoundsInScreen(rect)
            if (byBounds == null && matches(node, snapshot)) byBounds = node
            if (byLabel == null &&
                AdActionDetector.matchedToken(node.text?.toString()) == match.token
            ) {
                byLabel = node
            }
            if (byLabel == null &&
                AdActionDetector.matchedToken(node.contentDescription?.toString()) == match.token
            ) {
                byLabel = node
            }
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

        // The label must be found in the live tree: it is both the proof that
        // the control is still there and the point that is tapped.
        val labelled = byBounds ?: byLabel ?: return false
        val labelRect = android.graphics.Rect()
        labelled.getBoundsInScreen(labelRect)
        if (labelRect.width() <= 0 || labelRect.height() <= 0) return false
        val target = when {
            labelled.isClickable -> labelled
            clickableAtCenter != null -> clickableAtCenter
            else -> {
                var candidate: AccessibilityNodeInfo? = labelled
                var clickable: AccessibilityNodeInfo? = null
                while (candidate != null) {
                    if (candidate.isClickable) {
                        clickable = candidate
                        break
                    }
                    candidate = parents[candidate]
                }
                clickable
            }
        } ?: return false

        // Review 2026-11-02: the fallback climbs to a clickable ancestor of
        // unbounded size and used to tap *its* centre. When that ancestor is the
        // whole ad, the tap is an ad click and opens the store - the 2026-10-16
        // Moloco "Google Play >>" failure. Tap the label itself, and only when
        // the control around it is button-sized.
        target.getBoundsInScreen(rect)
        val metrics = resources.displayMetrics
        if (!AdActionDetector.isSafeTapTarget(
                targetWidth = rect.width(),
                targetHeight = rect.height(),
                containsLabelCentre = rect.contains(labelRect.centerX(), labelRect.centerY()),
                screenWidth = metrics.widthPixels,
                screenHeight = metrics.heightPixels
            )
        ) {
            return false
        }
        if (tapPoint(labelRect.centerX(), labelRect.centerY())) return true
        return clickNode(target)
    }

    /** Confirms, after the settle interval, that the clicked control is gone. */
    private fun scheduleAdActionResult(match: AdActionMatch, ownerPackage: String) {
        scope.launch {
            delay(CLOSE_VERIFY_DELAY_MILLIS)
            val stillThere = withContext(Dispatchers.Main) {
                if (lastForegroundPackage != ownerPackage) return@withContext false
                if (sessionManager.activeSession()?.sourcePackage != ownerPackage) {
                    return@withContext false
                }
                val root = runCatching { rootInActiveWindow }.getOrNull()
                    ?: return@withContext false
                if (root.packageName?.toString() != ownerPackage) return@withContext false
                val metrics = resources.displayMetrics
                val candidates = runCatching { closeGuard.collectSnapshots(root) }.getOrNull()
                    ?: return@withContext false
                val current = AdActionDetector.bestMatch(
                    candidates,
                    metrics.widthPixels,
                    metrics.heightPixels
                )
                current != null && current.token == match.token &&
                    current.kind == match.kind && sameBounds(current.node, match.node)
            }
            adActionGuard.onVerified(match, verified = !stillThere, ownerPackage = ownerPackage)
        }
    }

    /** True when two ad-action snapshots point at the same rectangle. */
    private fun sameBounds(a: com.rewardadguard.app.detector.NodeSnapshot, b: com.rewardadguard.app.detector.NodeSnapshot): Boolean =
        a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom

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

    /**
     * Records a real finger. If the collector is driving, it pauses at once:
     * two actors tapping the same screen is exactly what produced the 10-16
     * interference, only worse.
     */
    private fun noteUserInteraction(now: Long) {
        lastUserInteractionAt = now
        if (collectorState == CollectorState.RUNNING) {
            collectorEnvironment.pause("user_interaction")
        }
    }

    /**
     * Records that the guard stepped aside for a running collector.
     *
     * Rate limited: an animating ad raises close scans up to twice a second,
     * and 300 identical rows would bury the collector's own decisions.
     */
    private fun logCollectorYield(what: String) {
        val now = System.currentTimeMillis()
        if (now - lastCollectorYieldLogAt < COLLECTOR_YIELD_LOG_INTERVAL_MILLIS) {
            suppressedCollectorYields++
            return
        }
        logger.log(
            eventType = EventType.COLLECTOR,
            sessionId = sessionManager.currentSessionId ?: NO_SESSION,
            sourcePackage = sessionManager.activeSession()?.sourcePackage,
            detectionMethod = "GUARD_YIELD",
            action = "GUARD_YIELD",
            result = "SKIPPED",
            message = GuardYield.yieldMessage(what) + " suppressed=$suppressedCollectorYields",
            timestamp = now
        )
        lastCollectorYieldLogAt = now
        suppressedCollectorYields = 0
    }

    /**
     * Records that a late event was handled passively.
     *
     * A backlog arrives as a burst of dozens of events, so only one row per
     * [STALE_LOG_INTERVAL_MILLIS] is written; the count of the rows folded into
     * it is reported with the next one.
     */
    private fun logStaleEvent(destination: String, ageMillis: Long, now: Long) {
        if (now - lastStaleLogAt < STALE_LOG_INTERVAL_MILLIS) {
            suppressedStaleEvents++
            return
        }
        logger.log(
            eventType = EventType.EXTRA_INFO,
            sessionId = sessionManager.currentSessionId ?: NO_SESSION,
            sourcePackage = sessionManager.activeSession()?.sourcePackage,
            destinationPackage = destination,
            detectionMethod = "EVENT_AGE",
            action = "STALE_EVENT",
            result = "SKIPPED",
            message = "age=${ageMillis}ms suppressedSinceLast=$suppressedStaleEvents",
            timestamp = now
        )
        lastStaleLogAt = now
        suppressedStaleEvents = 0
    }

    /** The keyboard, the status bar and other windows that float over apps. */
    private fun isOverlayPackage(pkg: String): Boolean =
        pkg in imePackages || SystemOverlayPackages.isOverlay(pkg) ||
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
        landingGate.reset()
        closeGuard.reset()
        adActionGuard.reset()
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
     *  - SESSION: plus clicks (user-interaction signal);
     *  - AD: plus content changes and unimportant views, for the close button.
     */
    private enum class EventProfile { PAUSED, IDLE, SESSION, AD }

    private fun updateEventProfile() {
        val wanted = when {
            guardPaused -> EventProfile.PAUSED
            sessionManager.activeSession() == null -> EventProfile.IDLE
            adActivityInFront && settings.closeButtonAssistance -> EventProfile.AD
            else -> EventProfile.SESSION
        }
        if (wanted == installedProfile) return
        val info = serviceInfo ?: return
        // 2026-11-04 (phone lag with the guard on): no profile asks for
        // flagRetrieveInteractiveWindows any more. With it the system rebuilds
        // its window list for the guard on every window change of every app,
        // and the guard only ever needed the active window, which
        // rootInActiveWindow gives without it. View ids are only needed by the
        // close-button scan, so only the AD profile requests them.
        var types = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        var flags = 0
        when (wanted) {
            // No event type at all: the system delivers nothing, so a paused
            // guard adds no work to any app on the phone.
            EventProfile.PAUSED -> types = 0
            EventProfile.IDLE -> Unit
            EventProfile.SESSION -> types = types or AccessibilityEvent.TYPE_VIEW_CLICKED
            EventProfile.AD -> {
                types = types or AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
        }
        info.eventTypes = types
        info.flags = flags
        runCatching { serviceInfo = info }
            .onSuccess { installedProfile = wanted }
            .onFailure { Log.w(TAG, "setServiceInfo failed", it) }
    }

    /**
     * Applies a pause or resume chosen in the app (2026-11-04).
     *
     * Pausing keeps the accessibility service enabled but stops everything it
     * does: queued returns, scans and timers are cancelled, the running session
     * is closed, and [updateEventProfile] unsubscribes from every event. A timed
     * pause re-arms [resumeRunnable] for its end time. Runs on the main thread.
     */
    private fun applyPauseState() {
        if (!::sessionManager.isInitialized) return
        val now = System.currentTimeMillis()
        val paused = settings.isPaused(now)
        mainHandler.removeCallbacks(resumeRunnable)
        if (paused && !settings.isStopped) {
            mainHandler.postDelayed(resumeRunnable, (settings.pausedUntil - now).coerceAtLeast(1_000L))
        }
        if (paused == guardPaused) return
        guardPaused = paused
        if (paused) {
            pendingGraceJob?.cancel()
            returnController.cancelPending()
            // Everything queued on the main handler is guard work (scans,
            // landing Backs, re-scans); the resume timer is re-posted below.
            mainHandler.removeCallbacksAndMessages(null)
            if (!settings.isStopped) {
                mainHandler.postDelayed(resumeRunnable, (settings.pausedUntil - now).coerceAtLeast(1_000L))
            }
            // The clear above can also drop an applyPauseState() a settings
            // change posted from the settings coroutine a moment ago (review
            // 2026-11-12 #1): the UI would say resumed while the guard stays
            // paused. Re-check once; it is a no-op when nothing changed.
            mainHandler.post { applyPauseState() }
            closeScanQueued = false
            adActionRescanQueued = false
            redirectGuard.clearPending()
            sessionManager.endSession(SessionEndReason.MANUAL, now)
            resetAdTracking()
            redirectChain.reset()
        }
        logger.log(
            eventType = EventType.EXTRA_INFO,
            sessionId = NO_SESSION,
            detectionMethod = "USER",
            action = if (paused) "GUARD_PAUSED" else "GUARD_RESUMED",
            result = "SUCCESS",
            message = when {
                !paused -> "guarding resumed"
                settings.isStopped -> "stopped until resumed"
                else -> "paused until ${settings.pausedUntil}"
            },
            timestamp = now
        )
        updateEventProfile()
        // Back from a pause with a reward app already in front: pick it up
        // instead of waiting for the next window change.
        if (!paused) seedForegroundFromWindows()
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

    /**
     * ReturnController actions are scheduled, not immediate, so they must not
     * trust the state that was current when they were queued. A collector that
     * started afterwards owns navigation; pressing Back would close its ad.
     */
    private fun guardedBackForReturn(): Boolean {
        if (!GuardYield.shouldGuardAct(collectorState)) {
            logCollectorYield("scheduled return")
            return false
        }
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun guardedLaunchForReturn(packageName: String): Boolean {
        if (!GuardYield.shouldGuardAct(collectorState)) {
            logCollectorYield("scheduled relaunch")
            return false
        }
        return launchRewardApp(packageName)
    }

    private fun launchRewardApp(packageName: String): Boolean = try {        val intent = packageManager.getLaunchIntentForPackage(packageName)
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

        /** At most one STALE_EVENT row per this interval. */
        const val STALE_LOG_INTERVAL_MILLIS = 5_000L

        /** At most one "guard yielded to the collector" row per this interval. */
        const val COLLECTOR_YIELD_LOG_INTERVAL_MILLIS = 5_000L

        private const val ACTIVITY_CACHE_LIMIT = 256

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
