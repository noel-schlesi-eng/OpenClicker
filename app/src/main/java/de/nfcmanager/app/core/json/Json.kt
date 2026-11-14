package de.nfcmanager.app.core.json

class JsonException(message: String) : Exception(message)

/**
 * Minimaler JSON-Codec ohne Abhängigkeiten (damit Export/Import auf der JVM testbar ist).
 * Werte: Map<String, Any?>, List<Any?>, String, Long, Double, Boolean, null.
 */
object Json {
    private const val MAX_DEPTH = 64

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value(0)
        p.skipWs()
        if (p.pos != text.length) throw JsonException("Unerwartete Zeichen am Ende (Position ${p.pos})")
        return v
    }

    fun write(value: Any?, pretty: Boolean = false): String {
        val sb = StringBuilder()
        writeValue(sb, value, pretty, 0)
        return sb.toString()
    }

    private fun writeValue(sb: StringBuilder, v: Any?, pretty: Boolean, level: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Double -> {
                if (v.isNaN() || v.isInfinite()) throw JsonException("Ungültige Zahl")
                sb.append(v.toString())
            }
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, level + 1)
                    writeString(sb, k as? String ?: throw JsonException("Schlüssel muss Text sein"))
                    sb.append(if (pretty) ": " else ":")
                    writeValue(sb, value, pretty, level + 1)
                }
                if (pretty) newline(sb, level)
                sb.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (item in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) newline(sb, level + 1)
                    writeValue(sb, item, pretty, level + 1)
                }
                if (pretty) newline(sb, level)
                sb.append(']')
            }
            else -> throw JsonException("Nicht unterstützter Typ: ${v.javaClass.simpleName}")
        }
    }

    private fun newline(sb: StringBuilder, level: Int) {
        sb.append('\n')
        repeat(level) { sb.append("  ") }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\n' || s[pos] == '\r' || s[pos] == '\t')) pos++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw JsonException("Zu tief verschachtelt")
            if (pos >= s.length) throw JsonException("Unerwartetes Ende")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw JsonException("Unerwartetes Zeichen '$c' (Position $pos)")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw JsonException("Ungültiger Wert (Position $pos)")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw JsonException("Schlüssel erwartet (Position $pos)")
                val k = str()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw JsonException("':' erwartet (Position $pos)")
                pos++
                skipWs()
                m[k] = value(depth + 1)
                skipWs()
                if (pos >= s.length) throw JsonException("Unerwartetes Ende")
                if (s[pos] == ',') { pos++; continue }
                if (s[pos] == '}') { pos++; return m }
                throw JsonException("',' oder '}' erwartet (Position $pos)")
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                skipWs()
                l.add(value(depth + 1))
                skipWs()
                if (pos >= s.length) throw JsonException("Unerwartetes Ende")
                if (s[pos] == ',') { pos++; continue }
                if (s[pos] == ']') { pos++; return l }
                throw JsonException("',' oder ']' erwartet (Position $pos)")
            }
        }

        private fun str(): String {
            pos++
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw JsonException("Text nicht abgeschlossen")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw JsonException("Ungültige Escape-Sequenz")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Ungültiges \\u")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: throw JsonException("Ungültiges \\u")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Ungültige Escape-Sequenz '\\$e'")
                        }
                    }
                    c.code < 0x20 -> throw JsonException("Steuerzeichen im Text")
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            var isDouble = false
            if (pos < s.length && s[pos] == '.') {
                isDouble = true; pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                isDouble = true; pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val t = s.substring(start, pos)
            return if (isDouble) t.toDoubleOrNull() ?: throw JsonException("Ungültige Zahl '$t'")
            else t.toLongOrNull() ?: throw JsonException("Ungültige Zahl '$t'")
        }
    }
}
