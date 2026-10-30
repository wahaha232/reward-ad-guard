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
}
