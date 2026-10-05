package com.rewardadguard.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.rewardadguard.app.RewardAdGuardApp
import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.EventType
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.data.RedirectPolicy
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

    @Volatile
    private var pendingGraceJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var lastEventAt: Long = 0L

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
        rewardAppStore = RewardAppStore(applicationContext)
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
            runCatching { container.settingsRepository.settingsNow() }
                .onSuccess { loaded ->
                    settings = loaded
                    MonitoringState.updateSettings(loaded)
                }
                .onFailure { Log.w(TAG, "settings bootstrap fallback to defaults", it) }
        }
        MonitoringState.updateSettings(settings)
        container.settingsRepository.settings
            .onEach { newSettings ->
                settings = newSettings
                MonitoringState.updateSettings(newSettings)
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
     * This exists solely for the debug-only `DebugTestReceiver`, so that the
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
        try {
            when (e.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> handleWindowEvent(e)

                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> handleContentEvent(e)

                AccessibilityEvent.TYPE_VIEW_CLICKED,
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
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
            returnController.cancelPending()
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
        val activeWindow = foregroundDetector.activeWindowPackage(windows)
        val change = foregroundDetector.resolve(event.packageName?.toString(), activeWindow)
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
            observeCloseButton(event, current, now)
            return
        }

        // (3) we left the session source while the session is running
        if (sessionActive && sessionSource != null && previous == sessionSource && current != sessionSource) {
            handlePossibleRedirect(previous, current, event, now)
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
            sessionManager.enterState(SessionState.REWARD_APP_ACTIVE, now, "returned")
        }
        closeGuard.reset()
        observeCloseButton(null, source, now)
    }

    // --------------------------------------------------------- close button

    /** Content changes are only interesting inside an inferred ad session. */
    private fun handleContentEvent(event: AccessibilityEvent) {
        val session = sessionManager.activeSession() ?: return
        if (!rewardAppStore.isMonitored(session.sourcePackage)) return
        observeCloseButton(event, session.sourcePackage, System.currentTimeMillis())
    }

    /**
     * Close-button detection + (optional) assistance.
     *
     * The detector is only run when the reward app owns the screen, so an X on a
     * third-party app's own UI is never clicked.
     */
    private fun observeCloseButton(event: AccessibilityEvent?, ownerPackage: String, now: Long) {
        if (!settings.closeButtonAssistance) {
            closeGuard.reset()
            return
        }
        if (!rewardAppStore.isMonitored(ownerPackage)) return

        val root: AccessibilityNodeInfo = try {
            rootInActiveWindow ?: return
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

        lastCloseDetectionAt = now
        sessionManager.markAdSessionActive("CLOSE_BUTTON_NODE", now)
        sessionManager.enterState(SessionState.AD_SESSION_ACTIVE, now, match.method)

        logger.log(
            eventType = EventType.CLOSE_DETECT,
            sessionId = sessionManager.currentSessionId ?: NO_SESSION,
            sourcePackage = ownerPackage,
            detectionMethod = match.method,
            action = "CLOSE_DETECT",
            result = "SUCCESS",
            message = "score=${match.score} bounds=${match.boundsLabel()}"
        )
        logger.log(
            eventType = EventType.CLOSE_BOUNDS,
            sessionId = sessionManager.currentSessionId ?: NO_SESSION,
            sourcePackage = ownerPackage,
            detectionMethod = match.method,
            action = "CLOSE_BOUNDS",
            result = "SUCCESS",
            message = "original=${match.node?.width}x${match.node?.height} " +
                "scale=${settings.xClickAreaScale} assisted=${match.boundsLabel()}"
        )

        val shouldAssist = closeGuard.decide(
            match = match,
            settings = settings,
            isAdWindow = sessionManager.currentState() == SessionState.AD_SESSION_ACTIVE,
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
    }

    /**
     * Performs the assisted click.
     *
     * Two strategies, both non-destructive:
     *  1. `ACTION_CLICK` on the node (and on its clickable ancestor),
     *  2. `ACTION_CLICK` on the smallest clickable ancestor of the matched node.
     *
     * No gesture injection is used (the service does not request
     * `canPerformGestures`), so we can never tap coordinates belonging to a CTA.
     */
    private fun performAssistedClose(match: CloseMatch): Boolean {
        val node = match.node ?: return false
        val target = resolveClickableAncestor(node) ?: return false
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (t: Throwable) {
            reportError("CloseButtonGuard", "assisted click failed", t)
            false
        }
    }

    private fun resolveClickableAncestor(snapshot: com.rewardadguard.app.detector.NodeSnapshot): AccessibilityNodeInfo? {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < CloseButtonGuard.MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (matches(node, snapshot) && node.isClickable) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
        }
        return null
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
     * Global protection state. A per-app override stored in the reward app list
     * wins over the global mode (spec section 8).
     */
    private fun effectiveProtectionEnabled(): Boolean {
        if (settings.protectionMode == ProtectionMode.OFF) return false
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
        if (!canPostNotifications()) {
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

        /**
         * Live service instance, or null when the service is not bound.
         *
         * Written only while connected and cleared on teardown, so it can never
         * hold a stale reference to a destroyed instance. Used by the debug-only
         * `DebugTestReceiver` to drive synthetic foreground transitions; nothing
         * in the production path reads it.
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
