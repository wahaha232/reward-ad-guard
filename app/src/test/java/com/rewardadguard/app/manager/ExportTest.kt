package com.rewardadguard.app.manager

import android.app.Application
import com.rewardadguard.app.data.EventRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * P1#6: the CSV export must survive commas, quotes and newlines, and must not
 * hand a spreadsheet a live formula; the JSON export must stay parseable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExportTest {

    private fun exporter(): LogExporter =
        LogExporter(RuntimeEnvironment.getApplication() as Application)

    private fun event(message: String?) = EventRecord(
        timestamp = 1_700_000_000_000L,
        sessionId = "S1",
        eventType = "CLOSE_ACTION",
        category = "CLOSE",
        sourcePackage = "com.example.reward",
        destinationPackage = null,
        detectionMethod = "TEXT_KEYWORD",
        action = "CLOSE_ASSIST_CLICK",
        result = "DISPATCHED",
        message = message,
        error = null
    )

    @Test
    fun `csv quotes commas, quotes and newlines into one field`() {
        val csv = exporter().render(
            listOf(event("has,comma and \"quote\" and\nnewline")),
            emptyList(),
            LogExporter.Format.CSV
        )
        assertTrue(
            "the field must be quoted with doubled inner quotes",
            csv.contains("\"has,comma and \"\"quote\"\" and\nnewline\"")
        )
    }

    @Test
    fun `csv neutralises spreadsheet formula prefixes`() {
        val csv = exporter().render(
            listOf(event("=1+1")),
            emptyList(),
            LogExporter.Format.CSV
        )
        assertTrue("a formula field must be prefixed with an apostrophe", csv.contains("'=1+1"))
        assertFalse(csv.contains(",=1+1"))
    }

    @Test
    fun `json is valid and carries the event count`() {
        val json = exporter().render(
            listOf(event("a\"b")),
            emptyList(),
            LogExporter.Format.JSON
        )
        val root = JSONObject(json)
        assertEquals(1, root.getInt("eventCount"))
        assertEquals("a\"b", root.getJSONArray("events").getJSONObject(0).getString("message"))
    }
}
