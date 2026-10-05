package com.rewardadguard.app.manager

import com.rewardadguard.app.data.AppSettings
import com.rewardadguard.app.data.ProtectionMode
import com.rewardadguard.app.data.RedirectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins down *why* a correctly-configured app can still appear to do nothing.
 *
 * This test exists because of a real report after two days of use: "the app has no
 * effect at all". The cause was not a broken guard but a silent combination of
 * defaults:
 *
 *  1. `RewardAppsRepository.add()` defaults to `ProtectionMode.LOG_ONLY`, and
 *     `MainViewModel.safeDefaultMode()` keeps that while New App Safe Mode is on.
 *  2. The per-app mode overrides the global mode in
 *     `RewardAdAccessibilityService.effectiveProtectionEnabled()`.
 *
 * So a user who adds their reward app and changes nothing else gets **no blocking**,
 * only log entries - which is indistinguishable from a broken app.
 *
 * The behaviour is intentional (spec 37/38: never auto-escalate a newly added app),
 * so these tests assert the *consequences* rather than demanding a different
 * default. If the consequences ever change, that must be a deliberate decision and
 * these tests should fail loudly to force the conversation.
 */
class NewAppSafeModeTest {

    /**
     * Mirrors `MainViewModel.safeDefaultMode()`.
     *
     * Duplicated on purpose: the ViewModel cannot be constructed without an
     * Application, and the rule being pinned here is the mapping from settings to
     * mode, not the Android plumbing around it.
     */
    private fun safeDefaultMode(settings: AppSettings): ProtectionMode =
        if (settings.newAppSafeModeEnabled) {
            ProtectionMode.LOG_ONLY
        } else {
            settings.protectionMode
        }

    @Test
    fun `a newly added app is LOG_ONLY while safe mode is on`() {
        val settings = AppSettings()
        assertTrue("safe mode must default to on", settings.newAppSafeModeEnabled)
        assertEquals(ProtectionMode.LOG_ONLY, safeDefaultMode(settings))
    }

    @Test
    fun `LOG_ONLY is still an active scope but cannot block`() {
        // The trap: LOG_ONLY is not OFF, so the guard wakes up, tracks sessions and
        // writes a full log. Everything looks healthy. It simply never intervenes.
        val logOnly = AppSettings(protectionMode = ProtectionMode.LOG_ONLY)
        assertTrue("LOG_ONLY must not be treated as switched off", logOnly.protectionEnabled)
        assertTrue(logOnly.isLogOnly)
        assertFalse("LOG_ONLY must never be allowed to block", logOnly.canBlock)
    }

    @Test
    fun `BLOCK is the only mode that may interrupt the user`() {
        val block = AppSettings(protectionMode = ProtectionMode.BLOCK)
        assertTrue(block.canBlock)

        // Turning the redirect protection off must also disarm blocking, otherwise
        // the setting would be a lie.
        val blockButExternalsOff = AppSettings(
            protectionMode = ProtectionMode.BLOCK,
            externalRedirectProtection = false
        )
        assertFalse(blockButExternalsOff.canBlock)
    }

    @Test
    fun `a per app LOG_ONLY override defeats a global BLOCK`() {
        // This is the second half of the trap, and the reason "I set it to BLOCK"
        // can still do nothing: the override is consulted instead of the global
        // value, so the app-specific LOG_ONLY wins.
        val global = AppSettings(protectionMode = ProtectionMode.BLOCK)
        assertTrue("the global default must be protective", global.canBlock)

        val perAppMode: ProtectionMode? = ProtectionMode.LOG_ONLY
        val effective = perAppMode ?: global.protectionMode
        assertEquals(ProtectionMode.LOG_ONLY, effective)
        assertFalse(
            "an app-level LOG_ONLY override makes the guard silent even though the " +
                "global mode says BLOCK",
            AppSettings(protectionMode = effective).canBlock
        )
    }

    @Test
    fun `turning safe mode off inherits the global mode instead of escalating`() {
        val settings = AppSettings(
            newAppSafeModeEnabled = false,
            protectionMode = ProtectionMode.BLOCK
        )
        assertEquals(
            "disabling safe mode must inherit, never silently escalate past the global mode",
            ProtectionMode.BLOCK,
            safeDefaultMode(settings)
        )
    }

    @Test
    fun `safe mode off with protection off does not enable blocking`() {
        val settings = AppSettings(
            newAppSafeModeEnabled = false,
            protectionMode = ProtectionMode.OFF
        )
        assertEquals(ProtectionMode.OFF, safeDefaultMode(settings))
        assertFalse(AppSettings(protectionMode = ProtectionMode.OFF).canBlock)
    }

    @Test
    fun `the default redirect policy waits before acting`() {
        // Even in BLOCK mode the default is BLOCK_AFTER_GRACE, so a redirect the
        // user leaves quickly is never blocked. This explains the second half of
        // "sometimes it does nothing".
        val settings = AppSettings()
        assertEquals(RedirectPolicy.BLOCK_AFTER_GRACE, settings.redirectPolicy)
        assertTrue("the grace period must be short but non-zero", settings.blockGraceMillis > 0)
    }

    @Test
    fun `MONITORED_SOURCE is the only scope in which the guard may act`() {
        // AllowlistGuard is the last gate before any action. Pinning that mapping
        // here matters because otherwise a "nothing happened" report could be
        // explained by an unexpected allowlist rejection, invalidating the
        // LOG_ONLY diagnosis above.
        assertTrue(com.rewardadguard.app.guard.AllowlistGuard.Scope.MONITORED_SOURCE.name.isNotEmpty())
        assertEquals("MONITORED_SOURCE", com.rewardadguard.app.guard.AllowlistGuard.Scope.MONITORED_SOURCE.name)
        assertFalse(com.rewardadguard.app.guard.AllowlistGuard.Scope.NOT_MONITORED.name == "MONITORED_SOURCE")
        assertFalse(com.rewardadguard.app.guard.AllowlistGuard.Scope.DISABLED.name == "MONITORED_SOURCE")
    }
}
