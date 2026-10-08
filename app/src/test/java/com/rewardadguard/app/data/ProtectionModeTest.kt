package com.rewardadguard.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** H3: neither a global nor a per-app LOG_ONLY may be overruled by the other. */
class ProtectionModeTest {

    @Test
    fun `no override keeps the global mode`() {
        assertEquals(ProtectionMode.LOG_ONLY, ProtectionMode.effective(ProtectionMode.LOG_ONLY, null))
        assertEquals(ProtectionMode.BLOCK, ProtectionMode.effective(ProtectionMode.BLOCK, null))
    }

    @Test
    fun `a per-app LOG_ONLY wins over a global BLOCK`() {
        assertEquals(
            ProtectionMode.LOG_ONLY,
            ProtectionMode.effective(ProtectionMode.BLOCK, ProtectionMode.LOG_ONLY)
        )
    }

    @Test
    fun `a per-app BLOCK does not lift a global LOG_ONLY`() {
        assertEquals(
            ProtectionMode.LOG_ONLY,
            ProtectionMode.effective(ProtectionMode.LOG_ONLY, ProtectionMode.BLOCK)
        )
    }

    @Test
    fun `OFF on either side turns protection off`() {
        assertEquals(ProtectionMode.OFF, ProtectionMode.effective(ProtectionMode.OFF, ProtectionMode.BLOCK))
        assertEquals(ProtectionMode.OFF, ProtectionMode.effective(ProtectionMode.BLOCK, ProtectionMode.OFF))
    }
}
