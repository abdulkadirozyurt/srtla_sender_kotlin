// Kotlin port of irlserver/srtla_send v4.1.0 (MIT)
//
// Minimal JSON reader/writer. Upstream uses serde_json; this port keeps its
// zero-dependency rule, so the control protocol and stats export use this.
//
// Value model: null, Boolean, String, Long (integers that fit), Double (other
// numbers), List<Any?>, Map<String, Any?> (insertion-ordered).
package dev.abdulkadirozyurt.srtla.json

class JsonParseException(message: String) : Exception(message)

object Json {
    /** Parse one JSON document; trailing non-whitespace is an error. */
    @Throws(JsonParseException::class)
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonParseException("trailing characters at column ${p.pos + 1}")
        return v
    }

    /** Serialize a value of the model above (other Numbers and enums allowed). */
    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    fun writeTo(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (value) "true" else "false")
            is String -> writeString(sb, value)
            is Double -> writeDouble(sb, value)
            is Float -> writeDouble(sb, value.toDouble())
            is Number -> sb.append(value.toLong())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(sb, v)
                }
                sb.append(']')
            }
            is Array<*> -> writeTo(sb, value.asList())
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeDouble(sb: StringBuilder, d: Double) {
        // JSON has no NaN/Infinity; serde_json writes null for them.
        if (!d.isFinite()) {
            sb.append("null")
            return
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            // serde_json renders an integral f64 with a trailing ".0".
            sb.append(d.toLong()).append(".0")
        } else {
            sb.append(d.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }

    private const val MAX_DEPTH = 64

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        private fun fail(msg: String): Nothing = throw JsonParseException("$msg at column ${pos + 1}")

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nesting too deep")
            if (atEnd()) fail("EOF while parsing a value")
            return when (val c = s[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("expected value")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) fail("expected value")
            pos += word.length
            return v
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') fail("key must be a string")
                val k = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') fail("expected ':'")
                pos++
                skipWs()
                out[k] = readValue(depth + 1)
                skipWs()
                if (atEnd()) fail("EOF while parsing an object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                out.add(readValue(depth + 1))
                skipWs()
                if (atEnd()) fail("EOF while parsing a list")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("EOF while parsing a string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("EOF while parsing a string")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("invalid unicode escape")
                                val hex = s.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16) ?: fail("invalid unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> fail("invalid escape '\\$e'")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            if (atEnd()) fail("invalid number")
            if (s[pos] == '0') {
                pos++
            } else if (s[pos] in '1'..'9') {
                while (!atEnd() && s[pos] in '0'..'9') pos++
            } else {
                fail("invalid number")
            }
            var isFloat = false
            if (!atEnd() && s[pos] == '.') {
                isFloat = true
                pos++
                if (atEnd() || s[pos] !in '0'..'9') fail("invalid number")
                while (!atEnd() && s[pos] in '0'..'9') pos++
            }
            if (!atEnd() && (s[pos] == 'e' || s[pos] == 'E')) {
                isFloat = true
                pos++
                if (!atEnd() && (s[pos] == '+' || s[pos] == '-')) pos++
                if (atEnd() || s[pos] !in '0'..'9') fail("invalid number")
                while (!atEnd() && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (!isFloat) text.toLongOrNull()?.let { return it }
            return text.toDouble()
        }
    }
}

// ── Typed accessors, mirroring serde_json's Value::as_* ───────────────────────

fun Any?.jsonObject(): Map<String, Any?>? = (this as? Map<*, *>)?.let {
    @Suppress("UNCHECKED_CAST")
    it as Map<String, Any?>
}

fun Any?.jsonString(): String? = this as? String

fun Any?.jsonBool(): Boolean? = this as? Boolean

/** Non-negative integer (serde_json `as_u64`). */
fun Any?.jsonU64(): Long? = (this as? Long)?.takeIf { it >= 0 }
