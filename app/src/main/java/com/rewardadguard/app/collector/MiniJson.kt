package com.rewardadguard.app.collector

/**
 * A very small JSON reader, only used to load pc-collector's recorded OCR
 * fixtures.
 *
 * Why not `org.json`: the fixtures are read from JVM unit tests, where the
 * Android stub classes are intentionally replaced by defaults. Kotlin has no
 * JSON parser in its standard library either, and pulling a serialization
 * library into the production APK just to read test data would be wrong. This
 * parser lives in `main` so the same code can read a fixture that a bug report
 * attaches to the app later on.
 *
 * Supported grammar is deliberately the whole of JSON (objects, arrays,
 * strings with escapes, numbers, booleans, null); only the value type mapping
 * is simplified: objects become [Map], arrays [List], integral numbers [Long],
 * everything else [Double].
 */
internal object MiniJson {

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw IllegalArgumentException("unexpected trailing data at ${parser.position()}")
        return value
    }

    private class Parser(private val text: String) {

        private var index = 0

        fun position(): Int = index

        fun atEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd()) throw IllegalArgumentException("unexpected end of JSON")
            return when (val ch = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    if (ch == '-' || ch.isDigit()) readNumber()
                    else throw IllegalArgumentException("unexpected character '$ch' at $index")
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $index")
                }
            }
        }

        private fun readArray(): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result.add(readValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $index")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd()) throw IllegalArgumentException("unterminated string")
                val ch = text[index++]
                when (ch) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (atEnd()) throw IllegalArgumentException("unterminated escape")
                        when (val escaped = text[index++]) {
                            '"', '\\', '/' -> builder.append(escaped)
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > text.length) {
                                    throw IllegalArgumentException("truncated \\u escape")
                                }
                                val hex = text.substring(index, index + 4)
                                builder.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("invalid escape '\\$escaped'")
                        }
                    }
                    else -> builder.append(ch)
                }
            }
        }

        private fun readNumber(): Number {
            val start = index
            if (peek() == '-') index++
            while (!atEnd() && text[index].isDigit()) index++
            var isFloating = false
            if (!atEnd() && text[index] == '.') {
                isFloating = true
                index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            if (!atEnd() && (text[index] == 'e' || text[index] == 'E')) {
                isFloating = true
                index++
                if (!atEnd() && (text[index] == '+' || text[index] == '-')) index++
                while (!atEnd() && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            return if (isFloating) raw.toDouble() else raw.toLong()
        }

        private fun <T> readLiteral(literal: String, value: T): T {
            if (!text.startsWith(literal, index)) {
                throw IllegalArgumentException("invalid literal at $index")
            }
            index += literal.length
            return value
        }

        private fun peek(): Char {
            if (atEnd()) throw IllegalArgumentException("unexpected end of JSON")
            return text[index]
        }

        private fun expect(expected: Char) {
            if (peek() != expected) {
                throw IllegalArgumentException("expected '$expected' at $index")
            }
            index++
        }
    }
}
