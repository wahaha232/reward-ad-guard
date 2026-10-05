package com.rewardadguard.app.guard

import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.detector.CloseMatch
import com.rewardadguard.app.detector.NodeSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the close-button guard's *reasoning*, not just its verdict.
 *
 * This test exists because of a real report: "the app has no effect at all". The
 * device had `assistAction = NONE`, so the guard correctly refused to click - but
 * `shouldAssist()` returned a bare `Boolean` and every `false` collapsed into the
 * single message `"assist action disabled or throttled"`. A deliberate setting, a
 * throttle and a live finger all produced byte-identical log rows, which made a
 * working guard indistinguishable from a broken one.
 *
 * Every case below is one of the reasons that used to be indistinguishable, so
 * the inequality assertions here are the regression guard: if two different
 * situations ever map to the same decision again, these tests fail.
 */
class AssistDecisionTest {

    private val node = NodeSnapshot(
        text = "Close",
        contentDescription = null,
        viewIdResourceName = "com.ad.sdk:id/close",
        className = "android.widget.ImageView",
        clickable = true,
        left = 900,
        top = 80,
        right = 972,
        bottom = 152,
        visible = true
    )

    private val match = CloseMatch(
        matched = true,
        score = 75,
        method = "VIEW_ID",
        node = node
    )

    private val unmatched = CloseMatch(
        matched = false,
        score = 10,
        method = "NONE",
        node = null
    )

    private val allowSettings = AppSettings(
        closeButtonAssistance = true,
        assistAction = AssistAction.ASSIST_CLICK
    )

    /**
     * A guard whose decision logic needs neither a logger nor a session.
     *
     * [CloseButtonGuard.decide] is pure: it reads its own throttle/cap counters and
     * the passed settings. Passing null dependencies is only safe because of that,
     * and keeping this test Android-free is what lets it run on the JVM.
     */
    private fun guard(): CloseButtonGuard = CloseButtonGuard(
        logger = null,
        sessionManager = null
    )

    @After
    fun tearDown() {
        // maxAssistPerSession is a static override; leaking it would corrupt the
        // next test in this class (Gradle runs them all in one JVM).
        CloseButtonGuard.maxAssistPerSession = CloseButtonGuard.DEFAULT_MAX_ASSIST_PER_SESSION
    }

    // ------------------------------------------------------------ the fix

    @Test
    fun `a configured guard allows the click`() {
        assertEquals(
            AssistDecision.ALLOW,
            guard().decide(match, allowSettings, isAdWindow = true)
        )
    }

    @Test
    fun `NONE is reported as ACTION_NONE and not as a throttle`() {
        // THE bug. The device was configured exactly like this, and the log said
        // "assist action disabled or throttled" for 2,408 consecutive rows.
        val settings = AppSettings(
            closeButtonAssistance = true,
            assistAction = AssistAction.NONE
        )
        val decision = guard().decide(match, settings, isAdWindow = true)

        assertEquals(AssistDecision.ACTION_NONE, decision)
        assertNotEquals(
            "NONE must not be confused with a throttle",
            AssistDecision.THROTTLED,
            decision
        )
        assertTrue("NONE is a deliberate user choice", decision.isDeliberate)
        assertTrue(
            "the message must name the setting, not a generic failure",
            decision.describe().contains("NONE")
        )
    }

    @Test
    fun `the feature switch is reported separately from NONE`() {
        val off = guard().decide(
            match,
            AppSettings(closeButtonAssistance = false, assistAction = AssistAction.ASSIST_CLICK),
            isAdWindow = true
        )
        val none = guard().decide(
            match,
            AppSettings(closeButtonAssistance = true, assistAction = AssistAction.NONE),
            isAdWindow = true
        )

        assertEquals(AssistDecision.DISABLED, off)
        assertEquals(AssistDecision.ACTION_NONE, none)
        assertNotEquals(
            "switching the feature off and choosing detect-only are different states",
            off,
            none
        )
    }

    @Test
    fun `a match outside an ad session is reported as NOT_AD_WINDOW`() {
        val decision = guard().decide(match, allowSettings, isAdWindow = false)
        assertEquals(AssistDecision.NOT_AD_WINDOW, decision)
        assertFalse(
            "declining outside a session is a safety rule, not a user choice",
            decision.isDeliberate
        )
    }

    @Test
    fun `a live finger is reported as USER_INTERACTING`() {
        assertEquals(
            AssistDecision.USER_INTERACTING,
            guard().decide(match, allowSettings, isAdWindow = true, userInteracting = true)
        )
    }

    @Test
    fun `no candidate is reported as NO_MATCH rather than a silent false`() {
        assertEquals(
            AssistDecision.NO_MATCH,
            guard().decide(null, AppSettings(), isAdWindow = true)
        )
        assertEquals(
            AssistDecision.NO_MATCH,
            guard().decide(unmatched, AppSettings(), isAdWindow = true)
        )
    }

    @Test
    fun `all eight outcomes are distinct`() {
        // The whole point of the refactor: six different situations produced one
        // identical boolean and one identical log line before.
        assertEquals(8, AssistDecision.entries.toSet().size)
    }

    // ------------------------------------------------------------ throttling

    @Test
    fun `the throttle window is reported as THROTTLED`() {
        val guard = guard()
        val now = 1_000_000L

        guard.onAssisted(match, clicked = true, destinationPackage = PKG, now = now)

        assertEquals(
            AssistDecision.THROTTLED,
            guard.decide(match, allowSettings, isAdWindow = true, now = now + 100)
        )
    }

    @Test
    fun `the throttle expires and allows the next click`() {
        val guard = guard()
        val now = 1_000_000L

        guard.onAssisted(match, clicked = true, destinationPackage = PKG, now = now)

        assertEquals(
            AssistDecision.ALLOW,
            guard.decide(
                match,
                allowSettings,
                isAdWindow = true,
                now = now + CloseButtonGuard.THROTTLE_MILLIS
            )
        )
    }

    @Test
    fun `hitting the per session cap is reported as SESSION_CAP`() {
        val guard = guard()
        CloseButtonGuard.maxAssistPerSession = 2

        var now = 1_000_000L
        repeat(2) {
            guard.onAssisted(match, clicked = true, destinationPackage = PKG, now = now)
            now += CloseButtonGuard.THROTTLE_MILLIS
        }

        assertEquals(
            AssistDecision.SESSION_CAP,
            guard.decide(match, allowSettings, isAdWindow = true, now = now)
        )
        assertNotEquals(
            "a full session is not the same as a live throttle",
            AssistDecision.THROTTLED,
            guard.decide(match, allowSettings, isAdWindow = true, now = now)
        )
    }

    @Test
    fun `reset clears the throttle and the session counter`() {
        val guard = guard()
        val now = 1_000_000L

        guard.onAssisted(match, clicked = true, destinationPackage = PKG, now = now)
        guard.reset()

        assertEquals(
            "a new session must start with a clean slate",
            AssistDecision.ALLOW,
            guard.decide(match, allowSettings, isAdWindow = true, now = now + 1)
        )
    }

    // ------------------------------------------------------- backward compat

    @Test
    fun `shouldAssist still answers the boolean question`() {
        val guard = guard()

        assertTrue(guard.shouldAssist(match, allowSettings, isAdWindow = true))
        assertFalse(
            guard.shouldAssist(
                match,
                AppSettings(assistAction = AssistAction.NONE),
                isAdWindow = true
            )
        )
    }

    // ------------------------------------------------------------ messages

    @Test
    fun `every decision describes itself without being blank`() {
        AssistDecision.entries.forEach { decision ->
            val text = decision.describe()
            assertNotNull(text)
            assertTrue("${decision.name} has no message", text.isNotBlank())
        }
    }

    @Test
    fun `two decisions never share a message`() {
        // A shared message was the original defect, so make it impossible.
        val messages = AssistDecision.entries.map { it.describe() }
        assertEquals(
            "each decision needs its own wording",
            messages.size,
            messages.toSet().size
        )
    }

    @Test
    fun `decision names survive a round trip through the event log`() {
        // AssistDecision.fromName backs the CLOSE_DECISION row, so a rename here
        // silently breaks forensics for rows that were already written.
        AssistDecision.entries.forEach { decision ->
            assertEquals(decision, AssistDecision.fromName(decision.name))
        }
        assertEquals(null, AssistDecision.fromName("NOT_A_DECISION"))
        assertEquals(null, AssistDecision.fromName(null))
    }

    private companion object {
        const val PKG = "com.example.reward"
    }
}
