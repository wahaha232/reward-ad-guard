package com.rewardadguard.app.collector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An ad's own Play Store sheet: Back closes it, the ad must survive. */
class RedirectBackTest {

    @Test
    fun backThatReturnsToTheAppKeepsTheAd() {
        assertTrue(CollectorRunPolicy.backReturnedToApp("com.linecorp.LGWKTW", "com.linecorp.LGWKTW"))
    }

    @Test
    fun stillInTheStoreOrUnknownMeansRelaunch() {
        assertFalse(CollectorRunPolicy.backReturnedToApp("com.android.vending", "com.linecorp.LGWKTW"))
        assertFalse(CollectorRunPolicy.backReturnedToApp(null, "com.linecorp.LGWKTW"))
    }
}
