// Ported from irlserver/srtla_send v4.1.0 (MIT)
// Source: src/toml_config.rs
// JVM: minimal TOML subset parser (zero deps)

package dev.abdulkadirozyurt.srtla.config

import dev.abdulkadirozyurt.srtla.core.SchedulingMode
import java.nio.file.Files
import java.nio.file.Path

class TomlConfigException(message: String) : Exception(message)

data class TomlConfig(
    val mode: SchedulingMode? = null,
    val noQuality: Boolean? = null,
    val noStallDeselect: Boolean? = null,
    val stallMinInFlight: Int? = null,
    val stallAckStaleMs: Long? = null,
    val connTimeoutMs: Long? = null
) {
    companion object {
        @Throws(TomlConfigException::class)
        fun parse(text: String): TomlConfig {
            val parser = TomlParser(text)
            return parser.parse()
        }

        @Throws(TomlConfigException::class)
        fun load(path: Path): TomlConfig {
            return try {
                val content = Files.readString(path)
                try {
                    parse(content)
                } catch (e: TomlConfigException) {
                    throw TomlConfigException("failed to parse config file $path: ${e.message}")
                }
            } catch (e: Exception) {
                if (e is TomlConfigException) throw e
                throw TomlConfigException("failed to read config file $path: ${e.message}")
            }
        }
    }
}

private class TomlParser(private val text: String) {
    private var pos = 0
    private val fields = mutableMapOf<String, Any?>()
    private val seenKeys = mutableSetOf<String>()

    fun parse(): TomlConfig {
        while (pos < text.length) {
            skipWhitespaceAndComments()
            if (pos >= text.length) break

            if (text[pos] == '[') {
                throw TomlConfigException("unsupported TOML syntax at line ${countLines(pos)}")
            }

            if (text[pos].isLetter() || text[pos] == '_') {
                parseKeyValue()
            } else if (text[pos] != '\n') {
                throw TomlConfigException("unexpected character at line ${countLines(pos)}")
            }

            skipWhitespaceAndComments()
        }

        return buildConfig()
    }

    private fun parseKeyValue() {
        val keyStart = pos
        val key = parseKey()

        skipWhitespace()

        if (pos >= text.length || text[pos] != '=') {
            throw TomlConfigException("expected '=' after key '$key' at line ${countLines(pos)}")
        }
        pos++ // consume '='

        skipWhitespace()

        val value = parseValue()
        skipWhitespaceExcludingNewline()

        // Allow trailing comment
        if (pos < text.length && text[pos] == '#') {
            skipToEndOfLine()
        }

        // Validate key
        val canonicalKey = when (key) {
            "mode" -> "mode"
            "no_quality" -> "noQuality"
            "no_stall_deselect" -> "noStallDeselect"
            "stall_min_in_flight" -> "stallMinInFlight"
            "stall_ack_stale_ms" -> "stallAckStaleMs"
            "conn_timeout_ms" -> "connTimeoutMs"
            else -> throw TomlConfigException("unknown field `$key`")
        }

        // Check for duplicate
        if (seenKeys.contains(canonicalKey)) {
            throw TomlConfigException("duplicate field `$key`")
        }
        seenKeys.add(canonicalKey)

        // Validate type
        when (canonicalKey) {
            "mode" -> {
                if (value !is String) {
                    throw TomlConfigException("field `$key` must be a string")
                }
                val mode = SchedulingMode.parseOrNull(value)
                    ?: throw TomlConfigException("invalid mode '$value': use classic or enhanced")
                fields[canonicalKey] = mode
            }
            "noQuality", "noStallDeselect" -> {
                if (value !is Boolean) {
                    throw TomlConfigException("field `$key` must be a boolean")
                }
                fields[canonicalKey] = value
            }
            "stallMinInFlight" -> {
                if (value !is Long) {
                    throw TomlConfigException("field `$key` must be an integer")
                }
                if (value < Int.MIN_VALUE || value > Int.MAX_VALUE) {
                    throw TomlConfigException("field `$key` value out of range for i32")
                }
                fields[canonicalKey] = value.toInt()
            }
            "stallAckStaleMs", "connTimeoutMs" -> {
                if (value !is Long) {
                    throw TomlConfigException("field `$key` must be an integer")
                }
                if (value < 0) {
                    throw TomlConfigException("field `$key` must be non-negative")
                }
                fields[canonicalKey] = value
            }
        }
    }

    private fun parseKey(): String {
        val start = pos
        while (pos < text.length && (text[pos].isLetterOrDigit() || text[pos] == '_')) {
            pos++
        }
        if (pos == start) {
            throw TomlConfigException("expected key at line ${countLines(pos)}")
        }
        return text.substring(start, pos)
    }

    private fun parseValue(): Any? {
        if (pos >= text.length) {
            throw TomlConfigException("expected value at line ${countLines(pos)}")
        }

        return when {
            text[pos] == '"' -> parseDoubleQuotedString()
            text[pos] == '\'' -> parseSingleQuotedString()
            text.startsWith("true", pos) -> {
                pos += 4
                true
            }
            text.startsWith("false", pos) -> {
                pos += 5
                false
            }
            text[pos].isDigit() || text[pos] == '-' || text[pos] == '+' -> parseInteger()
            else -> throw TomlConfigException("unexpected value at line ${countLines(pos)}")
        }
    }

    private fun parseDoubleQuotedString(): String {
        pos++ // consume opening quote
        val sb = StringBuilder()
        while (pos < text.length && text[pos] != '"') {
            if (text[pos] == '\\') {
                pos++
                if (pos >= text.length) {
                    throw TomlConfigException("unterminated string escape at line ${countLines(pos)}")
                }
                when (text[pos]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> throw TomlConfigException("invalid escape sequence '\\${text[pos]}' at line ${countLines(pos)}")
                }
                pos++
            } else {
                sb.append(text[pos])
                pos++
            }
        }
        if (pos >= text.length) {
            throw TomlConfigException("unterminated string at line ${countLines(pos)}")
        }
        pos++ // consume closing quote
        return sb.toString()
    }

    private fun parseSingleQuotedString(): String {
        pos++ // consume opening quote
        val sb = StringBuilder()
        while (pos < text.length && text[pos] != '\'') {
            sb.append(text[pos])
            pos++
        }
        if (pos >= text.length) {
            throw TomlConfigException("unterminated string at line ${countLines(pos)}")
        }
        pos++ // consume closing quote
        return sb.toString()
    }

    private fun parseInteger(): Long {
        val start = pos
        if (text[pos] == '+' || text[pos] == '-') {
            pos++
        }
        if (pos >= text.length || !text[pos].isDigit()) {
            throw TomlConfigException("invalid integer at line ${countLines(pos)}")
        }
        while (pos < text.length && (text[pos].isDigit() || text[pos] == '_')) {
            pos++
        }
        val numStr = text.substring(start, pos).replace("_", "")
        return try {
            numStr.toLong()
        } catch (e: NumberFormatException) {
            throw TomlConfigException("integer overflow at line ${countLines(start)}")
        }
    }

    private fun skipWhitespace() {
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n' || text[pos] == '\r')) {
            pos++
        }
    }

    private fun skipWhitespaceExcludingNewline() {
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t')) {
            pos++
        }
    }

    private fun skipWhitespaceAndComments() {
        while (pos < text.length) {
            when {
                text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n' || text[pos] == '\r' -> pos++
                text[pos] == '#' -> skipToEndOfLine()
                else -> break
            }
        }
    }

    private fun skipToEndOfLine() {
        while (pos < text.length && text[pos] != '\n') {
            pos++
        }
        if (pos < text.length && text[pos] == '\n') {
            pos++
        }
    }

    private fun countLines(position: Int): Int {
        return text.substring(0, minOf(position, text.length)).count { it == '\n' } + 1
    }

    private fun buildConfig(): TomlConfig {
        return TomlConfig(
            mode = fields["mode"] as? SchedulingMode,
            noQuality = fields["noQuality"] as? Boolean,
            noStallDeselect = fields["noStallDeselect"] as? Boolean,
            stallMinInFlight = fields["stallMinInFlight"] as? Int,
            stallAckStaleMs = fields["stallAckStaleMs"] as? Long,
            connTimeoutMs = fields["connTimeoutMs"] as? Long
        )
    }
}
