package com.mapmyroad.detector

/**
 * Minimal JSON reader for the fixture labels files (TESTING_STRATEGY.md section 3.1), so the tests need no JSON
 * library. Returns Map<String, Any?>, List<Any?>, String, Double, Boolean or null. Throws IllegalArgumentException
 * with the offset on malformed input.
 */
internal class MiniJson private constructor(
    private val text: String,
) {
    private var index = 0

    private fun parseDocument(): Any? {
        val value = parseValue()
        skipWhitespace()
        require(index == text.length) { "unexpected trailing content at $index" }
        return value
    }

    private fun parseValue(): Any? {
        skipWhitespace()
        require(index < text.length) { "unexpected end of input" }
        return when (val c = text[index]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> parseLiteral("true", true)
            'f' -> parseLiteral("false", false)
            'n' -> parseLiteral("null", null)
            else -> if (c == '-' || c.isDigit()) parseNumber() else throw IllegalArgumentException("unexpected '$c' at $index")
        }
    }

    private fun parseObject(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>()
        index++
        skipWhitespace()
        if (peek() == '}') {
            index++
            return result
        }
        while (true) {
            skipWhitespace()
            require(peek() == '"') { "expected a key at $index" }
            val key = parseString()
            skipWhitespace()
            expect(':')
            result[key] = parseValue()
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

    private fun parseArray(): List<Any?> {
        val result = mutableListOf<Any?>()
        index++
        skipWhitespace()
        if (peek() == ']') {
            index++
            return result
        }
        while (true) {
            result += parseValue()
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

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            require(index < text.length) { "unterminated string" }
            val c = text[index++]
            when (c) {
                '"' -> return builder.toString()
                '\\' -> {
                    require(index < text.length) { "unterminated escape" }
                    when (val e = text[index++]) {
                        '"', '\\', '/' -> builder.append(e)
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            require(index + 4 <= text.length) { "bad unicode escape at $index" }
                            builder.append(text.substring(index, index + 4).toInt(16).toChar())
                            index += 4
                        }
                        else -> throw IllegalArgumentException("bad escape '$e' at $index")
                    }
                }
                else -> builder.append(c)
            }
        }
    }

    private fun parseNumber(): Double {
        val start = index
        while (index < text.length && (text[index].isDigit() || text[index] in "+-.eE")) index++
        return text.substring(start, index).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
    }

    private fun parseLiteral(
        literal: String,
        value: Any?,
    ): Any? {
        require(text.startsWith(literal, index)) { "unexpected token at $index" }
        index += literal.length
        return value
    }

    private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

    private fun expect(c: Char) {
        require(peek() == c) { "expected '$c' at $index" }
        index++
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    companion object {
        fun parse(text: String): Any? = MiniJson(text).parseDocument()
    }
}
