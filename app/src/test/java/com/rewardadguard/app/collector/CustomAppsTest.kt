package com.rewardadguard.app.collector

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomAppsTest {

    private val app = CustomApp(7, "My|App", "com.example.walk", listOf("領取", "看;廣告"), listOf("抽獎"))

    @After
    fun reset() = CustomApps.setForTest(emptySet(), emptyList())

    @Test
    fun encodeDecode_stripsSeparators() {
        val back = CustomApps.decode(CustomApps.encode(listOf(app)))
        assertEquals(listOf(CustomApp(7, "My App", "com.example.walk", listOf("領取", "看 廣告"), listOf("抽獎"))), back)
        assertEquals(emptyList<CustomApp>(), CustomApps.decode("1|x||領取|\nbad"))
    }

    @Test
    fun splitTerms_acceptsChineseCommas() {
        assertEquals(listOf("領取", "看廣告", "簽到"), CustomApps.splitTerms("領取, 看廣告，簽到、領取"))
    }

    @Test
    fun profile_usesGenericRules() {
        val p = app.toProfile()
        assertEquals("custom_7", p.key)
        assertTrue(p.chromeTerms.isEmpty())
        assertTrue("安裝" in p.avoidTerms && "抽獎" in p.avoidTerms)
    }

    @Test
    fun hiddenBuiltInLeavesTheList_customJoinsIt() {
        CustomApps.setForTest(setOf("bitwalk"), listOf(app))
        val keys = CollectorProfiles.all.map { it.key }
        assertTrue("bitwalk" !in keys)
        assertEquals("custom_7", keys.last())
        assertNull(CollectorProfiles.byKey("bitwalk"))
        assertEquals("com.example.walk", CollectorProfiles.byKey("custom_7")?.packageName)
        assertTrue(CollectorProfiles.default.any { it.key == "custom_7" })
    }
}
