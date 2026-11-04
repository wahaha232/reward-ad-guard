package com.rewardadguard.app.detector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Field report 2026-10-30: `miui.systemui.plugin` (HyperOS dynamic island) and
 * the intent resolver must never count as the foreground app.
 */
class SystemOverlayPackagesTest {

    @Test
    fun `the dynamic island is an overlay`() {
        assertTrue(SystemOverlayPackages.isOverlay("miui.systemui.plugin"))
    }

    @Test
    fun `the intent resolver is an overlay`() {
        assertTrue(SystemOverlayPackages.isOverlay("com.android.intentresolver"))
    }

    @Test
    fun `system ui stays an overlay`() {
        assertTrue(SystemOverlayPackages.isOverlay("com.android.systemui"))
    }

    @Test
    fun `ordinary apps are not overlays`() {
        assertFalse(SystemOverlayPackages.isOverlay("com.shopee.tw"))
        assertFalse(SystemOverlayPackages.isOverlay("com.android.chrome"))
        assertFalse(SystemOverlayPackages.isOverlay("jp.paddleinc.walk"))
        assertFalse(SystemOverlayPackages.isOverlay("com.tracerofficial"))
    }

    @Test
    fun `packages merely containing plugin or resolver are not overlays`() {
        // The list is explicit on purpose: a substring rule would swallow real
        // apps whose package name happens to contain these words.
        assertFalse(SystemOverlayPackages.isOverlay("com.example.plugin"))
        assertFalse(SystemOverlayPackages.isOverlay("com.example.resolver"))
    }

    @Test
    fun `system surfaces include the overlays and the installer dialogs`() {
        for (pkg in listOf(
            "miui.systemui.plugin",
            "com.android.intentresolver",
            "com.android.systemui",
            "com.android.settings",
            "com.android.packageinstaller"
        )) {
            assertTrue("$pkg should be a system surface", SystemOverlayPackages.isSystemSurface(pkg))
        }
        assertFalse(SystemOverlayPackages.isSystemSurface("com.shopee.tw"))
    }

    /** 2026-11-04: the guard closed Google search / Assistant summoned by the user. */
    @Test
    fun `Google search, Assistant, Gemini and Lens are user summoned`() {
        listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googleassistant",
            "com.google.android.apps.bard",
            "com.google.ar.lens"
        ).forEach { org.junit.Assert.assertTrue(it, SystemOverlayPackages.isUserSummoned(it)) }
    }

    @Test
    fun `ad destinations are not user summoned`() {
        listOf("com.android.chrome", "com.android.vending", "com.shopee.tw")
            .forEach { org.junit.Assert.assertFalse(it, SystemOverlayPackages.isUserSummoned(it)) }
    }
}
