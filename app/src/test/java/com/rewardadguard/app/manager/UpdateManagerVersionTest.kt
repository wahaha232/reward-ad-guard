package com.rewardadguard.app.manager

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerVersionTest {

    @Test
    fun `a higher release tag is newer`() {
        assertTrue(UpdateManager.isNewer("1.1.0", "v1.2.0"))
        assertTrue(UpdateManager.isNewer("1.1.0", "v1.1.1"))
        assertTrue(UpdateManager.isNewer("1.9.0", "v1.10.0"))
    }

    @Test
    fun `the same or an older tag is not newer`() {
        assertFalse(UpdateManager.isNewer("1.1.0", "v1.1.0"))
        assertFalse(UpdateManager.isNewer("1.1.0", "v1.0.9"))
        assertFalse(UpdateManager.isNewer("1.10.0", "v1.9.9"))
    }

    @Test
    fun `missing parts count as zero`() {
        assertFalse(UpdateManager.isNewer("1.1", "v1.1.0"))
        assertTrue(UpdateManager.isNewer("1.1", "v1.1.1"))
    }
}
