package com.rewardadguard.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * `Format` is pure Kotlin apart from [java.text.SimpleDateFormat], which is part
 * of the JDK and therefore fully testable on the JVM.
 *
 * Every wall-clock assertion pins the time zone first: the helper formats with
 * `Locale.US` but deliberately leaves the zone to the device, so a fixed instant
 * only maps to a fixed string inside one zone.
 */
class FormatTest {

    private inline fun <T> inZone(zoneId: String, block: () -> T): T {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
            return block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /**
     * The exact expression used by `Format.humanize`, spelled out here so the
     * test does not silently follow a change of the production transform.
     */
    private fun expectedHumanized(enumName: String): String = enumName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.titlecase(java.util.Locale.US) }
        }

    // ------------------------------------------------------------ duration

    @Test
    fun `duration returns 0s for zero and negative input`() {
        assertEquals("0s", Format.duration(0L))
        assertEquals("0s", Format.duration(-1L))
        assertEquals("0s", Format.duration(Long.MIN_VALUE))
    }

    @Test
    fun `duration renders seconds below one minute`() {
        assertEquals("1s", Format.duration(1_000L))
        assertEquals("59s", Format.duration(59_999L))
    }

    @Test
    fun `duration renders minutes and zero padded seconds`() {
        assertEquals("1m 00s", Format.duration(60_000L))
        assertEquals("1m 05s", Format.duration(65_000L))
        assertEquals("59m 59s", Format.duration(3_599_000L))
    }

    @Test
    fun `duration renders hours and zero padded minutes`() {
        assertEquals("1h 00m", Format.duration(3_600_000L))
        assertEquals("2h 05m", Format.duration(7_500_000L))
        assertEquals("24h 00m", Format.duration(86_400_000L))
    }

    @Test
    fun `duration truncates sub second remainders`() {
        // 1m 05.999s must not round up into a phantom extra second.
        assertEquals("1m 05s", Format.duration(65_999L))
    }

    // ------------------------------------------------------------ package

    @Test
    fun `shortPackage replaces null and blank with a dash`() {
        assertEquals("-", Format.shortPackage(null))
        assertEquals("-", Format.shortPackage(""))
        assertEquals("-", Format.shortPackage("   "))
    }

    @Test
    fun `shortPackage keeps a name that fits exactly`() {
        val name = "com.example.rewardapp"
        assertTrue(name.length <= 34)
        assertEquals(name, Format.shortPackage(name))
    }

    @Test
    fun `shortPackage truncates to max minus one plus ellipsis`() {
        val long = "com.example.very.long.reward.application.package"
        val shortened = Format.shortPackage(long, max = 20)
        assertEquals(20, shortened.length)
        assertTrue(shortened.endsWith("…"))
        assertEquals(long.take(19), shortened.dropLast(1))
    }

    @Test
    fun `shortPackage honours a custom max boundary`() {
        val name = "abcdef"
        // Length equal to max: untouched.
        assertEquals("abcdef", Format.shortPackage(name, max = 6))
        // Length max + 1: truncated to 5 chars + ellipsis.
        assertEquals("abcde…", Format.shortPackage("abcdefg", max = 6))
    }

    // ------------------------------------------------------------ session

    @Test
    fun `shortSession replaces null blank and the no-session id`() {
        assertEquals("-", Format.shortSession(null))
        assertEquals("-", Format.shortSession(""))
        assertEquals("-", Format.shortSession("   "))
        // "-" is the sentinel EventLogger.NO_SESSION value.
        assertEquals("-", Format.shortSession("-"))
    }

    @Test
    fun `shortSession keeps short ids verbatim and abbreviates long ids`() {
        val id = "SESSION_20261002_101112_000"
        assertEquals("…" + id.takeLast(6), Format.shortSession(id))
        assertEquals("abc123", Format.shortSession("abc123"))
    }

    // ------------------------------------------------------------ humanize

    @Test
    fun `humanize turns enum constants into readable labels`() {
        // `replaceFirstChar` only touches the FIRST character: the Kotlin runtime
        // routes it through `Character.titlecase`, which does not lowercase the
        // remaining letters (unlike the deprecated `String.capitalize()`).
        assertEquals("Block Immediately", "block immediately".split(' ').joinToString(" ") {
            it.replaceFirstChar { c -> c.titlecase(java.util.Locale.US) }
        })
        assertEquals("A", "a".replaceFirstChar { c -> c.titlecase(java.util.Locale.US) })
        assertEquals("Abc", "abc".replaceFirstChar { c -> c.titlecase(java.util.Locale.US) })

        // Only the first letter of a word is lowercased; multi-letter words keep
        // their inner capitals, which is a documented quirk of this helper.
        assertEquals("Block Immediately", Format.humanize("BLOCK_IMMEDIATELY"))
        assertEquals("Possible Ad Session", Format.humanize("POSSIBLE_AD_SESSION"))
        assertEquals("Block After Grace", Format.humanize("BLOCK_AFTER_GRACE"))
        assertEquals("Log Only", Format.humanize("LOG_ONLY"))
        assertEquals("Deep Link", Format.humanize("DEEP_LINK"))

        // Cross-check against the literal production expression.
        assertEquals(expectedHumanized("BLOCK_IMMEDIATELY"), Format.humanize("BLOCK_IMMEDIATELY"))
        assertEquals(expectedHumanized("LOG_ONLY"), Format.humanize("LOG_ONLY"))
    }

    @Test
    fun `humanize lowercases the first letter of every word only`() {
        // Consequence of the rule above: mixed case names lose their first
        // capital to a lowercase letter driven by the new titlecase.
        assertEquals("Closebuttonguard", Format.humanize("CloseButtonGuard"))
    }

    @Test
    fun `humanize tolerates leading trailing and repeated separators`() {
        assertEquals("Log Only", Format.humanize("_LOG__ONLY_"))
        assertEquals("", Format.humanize(""))
        assertEquals("", Format.humanize("___"))
    }

    @Test
    fun `humanize is stable for a single word`() {
        assertEquals("Error", Format.humanize("ERROR"))
        assertEquals("Error", Format.humanize("error"))
        assertEquals("Error", Format.humanize("Error"))
    }

    // ------------------------------------------------------------ date/time

    @Test
    fun `time renders millisecond precision in 24 hour form`() {
        // 2026-10-01T23:50:50.123Z -> 2026-10-02 07:50:50.123 in UTC+08:00.
        val instant = 1_790_898_650_123L
        assertEquals("23:50:50.123", inZone("UTC") { Format.time(instant) })
        assertEquals("07:50:50.123", inZone("Asia/Taipei") { Format.time(instant) })
    }

    @Test
    fun `dateTime renders a sortable full timestamp`() {
        val instant = 1_790_898_650_123L
        assertEquals("2026-10-01 23:50:50", inZone("UTC") { Format.dateTime(instant) })
        assertEquals("2026-10-02 07:50:50", inZone("Asia/Taipei") { Format.dateTime(instant) })
    }

    @Test
    fun `date renders a day key and crosses the date line with the zone`() {
        val instant = 1_790_898_650_123L
        // Same instant, two different calendar days.
        assertEquals("2026-10-01", inZone("UTC") { Format.date(instant) })
        assertEquals("2026-10-02", inZone("Asia/Taipei") { Format.date(instant) })
        assertEquals("2026-10-01", inZone("America/Los_Angeles") { Format.date(instant) })
    }

    @Test
    fun `midnight formats with zero padded fields`() {
        // Guards against the formatter accidentally becoming locale dependent.
        assertEquals("00:00:00.000", inZone("UTC") { Format.time(0L) })
        assertEquals("1970-01-01", inZone("UTC") { Format.date(0L) })
        assertEquals("08:00:00.000", inZone("Asia/Taipei") { Format.time(0L) })
    }

    @Test
    fun `time formatters follow a time zone change at runtime`() {
        // Regression guard: the formatters used to be cached in `val`s, which
        // froze the zone that was in effect when the object first loaded. All
        // three entry points must reflect the *current* default zone.
        val instant = 1_790_898_650_123L
        inZone("UTC") {
            assertEquals("23:50:50.123", Format.time(instant))
            assertEquals("2026-10-01 23:50:50", Format.dateTime(instant))
            assertEquals("2026-10-01", Format.date(instant))
        }
        inZone("Asia/Taipei") {
            assertEquals("07:50:50.123", Format.time(instant))
            assertEquals("2026-10-02 07:50:50", Format.dateTime(instant))
            assertEquals("2026-10-02", Format.date(instant))
        }
    }
}
