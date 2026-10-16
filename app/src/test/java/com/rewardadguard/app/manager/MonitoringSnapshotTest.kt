package com.rewardadguard.app.manager

import com.rewardadguard.app.data.AssistAction
import com.rewardadguard.app.data.ProtectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0#1: the dashboard's "auto-click allowed" flag must reflect the real settings,
 * so "only detecting" can never look like "working".
 */
class MonitoringSnapshotTest {

    @Test
    fun `defaults allow the assist`() {
        assertTrue(MonitoringSnapshot().autoAssistAllowed)
    }

    @Test
    fun `detect-only never allows a click`() {
        assertFalse(MonitoringSnapshot(assistAction = AssistAction.NONE).autoAssistAllowed)
    }

    @Test
    fun `the feature switch disables the assist`() {
        assertFalse(MonitoringSnapshot(closeButtonAssistance = false).autoAssistAllowed)
    }

    @Test
    fun `only BLOCK mode may click`() {
        assertFalse(MonitoringSnapshot(protectionMode = ProtectionMode.LOG_ONLY).autoAssistAllowed)
        assertFalse(MonitoringSnapshot(protectionMode = ProtectionMode.OFF).autoAssistAllowed)
        assertTrue(MonitoringSnapshot(protectionMode = ProtectionMode.BLOCK).autoAssistAllowed)
    }
}
