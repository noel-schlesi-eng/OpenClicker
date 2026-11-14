package de.nfcmanager.app.core

/**
 * Hex-Hilfsfunktionen.
 *
 * Reines Kotlin ohne Android-Abhängigkeiten, damit die Logik auf der JVM testbar ist.
 */
object Hex {
    private const val DIGITS = "0123456789ABCDEF"

    /** Kodiert Bytes als Hex-Text (Großbuchstaben), optional mit Trennzeichen. */
    fun encode(bytes: ByteArray, separator: String = ""): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        for (i in bytes.indices) {
            if (i > 0 && separator.isNotEmpty()) sb.append(separator)
            val v = bytes[i].toInt() and 0xFF
            sb.append(DIGITS[v ushr 4])
            sb.append(DIGITS[v and 0x0F])
        }
        return sb.toString()
    }

    /** Wie [tryDecode], wirft aber bei ungültiger Eingabe eine [IllegalArgumentException]. */
    fun decode(text: String): ByteArray =
        tryDecode(text) ?: throw IllegalArgumentException("Ungültige Hex-Zeichenfolge")

    /**
     * Tolerantes Dekodieren: ignoriert Leerzeichen, Doppelpunkte, Bindestriche, Kommas
     * und "0x"-Präfixe. Gibt null zurück, wenn die Eingabe kein gültiger Hex-Text ist.
     */
    fun tryDecode(text: String): ByteArray? {
        val stripped = text.replace("0x", "", ignoreCase = true)
        val sb = StringBuilder(stripped.length)
        for (c in stripped) {
            if (c.isWhitespace() || c == ':' || c == '-' || c == ',') continue
            if (digit(c) < 0) return null
            sb.append(c)
        }
        if (sb.length % 2 != 0) return null
        val out = ByteArray(sb.length / 2)
        for (i in out.indices) {
            out[i] = ((digit(sb[2 * i]) shl 4) or digit(sb[2 * i + 1])).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** Klassischer Hexdump mit Offset-Spalte und ASCII-Ansicht. */
    fun dump(bytes: ByteArray, bytesPerLine: Int = 16, baseOffset: Int = 0): String {
        val sb = StringBuilder()
        var off = 0
        while (off < bytes.size) {
            val n = minOf(bytesPerLine, bytes.size - off)
            sb.append((baseOffset + off).toString(16).uppercase().padStart(4, '0')).append("  ")
            for (i in 0 until bytesPerLine) {
                if (i < n) {
                    val v = bytes[off + i].toInt() and 0xFF
                    sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0F]).append(' ')
                } else {
                    sb.append("   ")
                }
            }
            sb.append(' ')
            for (i in 0 until n) {
                val v = bytes[off + i].toInt() and 0xFF
                sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
            }
            sb.append('\n')
            off += n
        }
        return sb.toString().trimEnd('\n')
    }
}

fun ByteArray.toHex(separator: String = ""): String = Hex.encode(this, separator)
