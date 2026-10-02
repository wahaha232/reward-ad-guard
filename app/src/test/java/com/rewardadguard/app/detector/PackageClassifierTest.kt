package com.rewardadguard.app.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the classification data model (spec section 21).
 *
 * [PackageClassifier] itself needs an Android [android.content.Context] to ask
 * the PackageManager about installed apps, so the parts that can be verified on
 * a plain JVM are the [DestinationKind] semantics it returns. The mapping
 * itself is exercised on device by TOOLS/device_test_checklist.ps1.
 */
class PackageClassifierTest {

    @Test
    fun `self is never external`() {
        assertFalse(DestinationKind.SELF.isExternal)
    }

    @Test
    fun `unknown is never reported as external`() {
        assertFalse(DestinationKind.UNKNOWN.isExternal)
    }

    @Test
    fun `every real destination class counts as external`() {
        val external = listOf(
            DestinationKind.BROWSER,
            DestinationKind.STORE,
            DestinationKind.GAME,
            DestinationKind.DEEP_LINK,
            DestinationKind.EXTERNAL_APP
        )
        for (kind in external) {
            assertTrue("$kind should be external", kind.isExternal)
        }
    }

    @Test
    fun `only self and unknown are internal`() {
        assertEquals(
            listOf(DestinationKind.SELF, DestinationKind.UNKNOWN),
            DestinationKind.entries.filter { !it.isExternal }
        )
    }

    @Test
    fun `every destination kind is distinct`() {
        val kinds = DestinationKind.entries
        assertEquals(kinds.size, kinds.toSet().size)
        assertEquals(7, kinds.size)
    }

    @Test
    fun `name lookup round trips for the service`() {
        for (kind in DestinationKind.entries) {
            assertEquals(kind, DestinationKind.valueOf(kind.name))
        }
    }
}
