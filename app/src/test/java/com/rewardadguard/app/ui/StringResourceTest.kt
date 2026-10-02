package com.rewardadguard.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the localisation contract that nothing else in the build enforces.
 *
 * Android happily ships a partially translated app: a key that exists in
 * `values/` but not in `values-en/` is not an error, it silently falls back to
 * Chinese on an English device. The same is true in reverse. On top of that, a
 * Kotlin file that inlines a user-visible literal compiles fine and simply
 * never gets translated.
 *
 * These tests run on the JVM and parse the resource XML as plain text, so they
 * stay independent of the Android resource pipeline (no Robolectric needed,
 * which the project does not depend on).
 */
class StringResourceTest {

    private val valuesDir = File("src/main/res/values")
    private val valuesEnDir = File("src/main/res/values-en")

    /**
     * Unit tests run with the module directory as the working directory, but a
     * Gradle layout change would otherwise turn every assertion below into a
     * confusing empty-list failure, so resolve defensively.
     */
    private fun requireResourceDir(dir: File): File {
        if (dir.isDirectory) return dir
        val fromRoot = File("app", dir.path)
        assertTrue(
            "Resource directory not found: ${dir.absolutePath}",
            fromRoot.isDirectory
        )
        return fromRoot
    }

    private fun parseStrings(dir: File): Map<String, String> {
        val file = File(requireResourceDir(dir), "strings.xml")
        assertTrue("Missing resource file: ${file.absolutePath}", file.isFile)
        val text = file.readText(Charsets.UTF_8)
        // Matches <string name="key">value</string>, tolerating extra attributes
        // such as translatable="false".
        val regex = Regex(
            """<string\s+name="([^"]+)"[^>]*>(.*?)</string>""",
            RegexOption.DOT_MATCHES_ALL
        )
        return regex.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun formatArgs(value: String): List<String> =
        Regex("""%(\d+)\$[sd]""").findAll(value).map { it.groupValues[1] }.sorted().toList()

    // ------------------------------------------------------------ parity

    @Test
    fun `both locales define exactly the same keys`() {
        val zh = parseStrings(valuesDir)
        val en = parseStrings(valuesEnDir)

        assertTrue("values/strings.xml looks empty", zh.size > 50)

        val onlyZh = (zh.keys - en.keys).sorted()
        val onlyEn = (en.keys - zh.keys).sorted()

        assertEquals(
            "keys missing from values-en/ (would stay Chinese): $onlyZh",
            emptyList<String>(),
            onlyZh
        )
        assertEquals(
            "keys missing from values/ (would stay English): $onlyEn",
            emptyList<String>(),
            onlyEn
        )
    }

    @Test
    fun `every formatted string uses the same placeholders in both locales`() {
        val zh = parseStrings(valuesDir)
        val en = parseStrings(valuesEnDir)

        val mismatches = zh.keys.intersect(en.keys).mapNotNull { key ->
            val zhArgs = formatArgs(zh.getValue(key))
            val enArgs = formatArgs(en.getValue(key))
            if (zhArgs == enArgs) null else "$key: zh=$zhArgs en=$enArgs"
        }

        assertEquals("placeholder mismatch: $mismatches", emptyList<String>(), mismatches)
    }

    @Test
    fun `no formatted string mixes positional and non positional args`() {
        // %1$s style args are positional and safe to reorder; %s / %d are not.
        // Mixing the two in one string is the classic crash source.
        val all = parseStrings(valuesDir) + parseStrings(valuesEnDir)

        val offenders = all.filter { (_, value) ->
            value.contains(Regex("""%(\d+)\$""")) && value.contains(Regex("""%[sd](?!\d)"""))
        }.keys.sorted()

        assertEquals("mixed positional and non-positional args: $offenders", emptyList<String>(), offenders)
    }

    // ------------------------------------------------------------ content

    @Test
    fun `the default locale is traditional chinese`() {
        val zh = parseStrings(valuesDir)

        assertEquals("獎勵廣告守衛", zh["app_name"])
        assertEquals("首頁", zh["tab_dashboard"])
        assertEquals("設定", zh["tab_settings"])
    }

    @Test
    fun `the english locale is actually english`() {
        val en = parseStrings(valuesEnDir)

        assertEquals("Reward Ad Guard", en["app_name"])
        assertEquals("Home", en["tab_dashboard"])
        assertEquals("Settings", en["tab_settings"])
    }

    @Test
    fun `the accessibility description keeps its newlines and numbered steps`() {
        // The system Accessibility dialog renders this verbatim; a lost \n
        // collapses four readable steps into one paragraph.
        for (dir in listOf(valuesDir, valuesEnDir)) {
            val description = parseStrings(dir)["accessibility_service_description"]
            assertTrue("missing accessibility_service_description in ${dir.path}", description != null)
            assertTrue("expected escaped newlines in ${dir.path}", description!!.contains("\\n"))
            assertTrue("expected step 1 in ${dir.path}", description.contains("1."))
            assertTrue("expected step 4 in ${dir.path}", description.contains("4."))
        }
    }

    @Test
    fun `the chinese locale contains no leftover english sentences`() {
        val zh = parseStrings(valuesDir)

        // Brand names, format specifiers and file formats are legitimately
        // latin; sentence-looking values are not.
        val allowed = setOf(
            "app_name",
            "accessibility_service_label",
            "tab_apps",
            "accessibility_service_description",
            "export_format_csv",
            "export_format_json"
        )

        val suspects = zh.filterKeys { it !in allowed }
            .filter { (_, value) ->
                value.contains("the ", ignoreCase = true) ||
                    value.contains(" of ", ignoreCase = true) ||
                    value.contains(" is ", ignoreCase = true)
            }.keys.sorted()

        assertEquals("untranslated english left in values/: $suspects", emptyList<String>(), suspects)
    }

    @Test
    fun `no string value is accidentally blank`() {
        val blank = (parseStrings(valuesDir).toList() + parseStrings(valuesEnDir).toList())
            .filter { (_, value) -> value.isBlank() }
            .map { it.first }
            .sorted()

        assertEquals("blank string values: $blank", emptyList<String>(), blank)
    }

    @Test
    fun `xml files carry no byte order mark`() {
        // A BOM in a Kotlin source is a compile error; in an XML resource it
        // confuses some tooling. Both are emitted by careless scripted writes.
        for (dir in listOf(valuesDir, valuesEnDir)) {
            val text = File(requireResourceDir(dir), "strings.xml").readText(Charsets.UTF_8)
            assertFalse("BOM found in ${dir.path}/strings.xml", text.startsWith("\uFEFF"))
        }
    }
}
