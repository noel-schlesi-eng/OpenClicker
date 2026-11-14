package de.nfcmanager.app.core.ndef

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

data class LabeledValue(val label: String, val value: String)

/** Ausgewertete Felder einer vCard (nur die für Anzeige und Bearbeitung relevanten). */
data class VCardInfo(
    val version: String,
    val formattedName: String,
    val familyName: String,
    val givenName: String,
    val organization: String,
    val jobTitle: String,
    val phones: List<LabeledValue>,
    val emails: List<LabeledValue>,
    val urls: List<String>,
    /** Alle Adressen als einzeilige Texte. */
    val addresses: List<String>,
    /** Teile der ersten Adresse: Postfach, Zusatz, Straße, Ort, Region, PLZ, Land. */
    val firstAddressParts: List<String>,
    val note: String,
    val birthday: String,
    /** Namen aller vorkommenden Eigenschaften (großgeschrieben), ohne BEGIN/END. */
    val propertyNames: List<String>,
) {
    val displayName: String
        get() = when {
            formattedName.isNotBlank() -> formattedName
            else -> listOf(givenName, familyName).filter { it.isNotBlank() }.joinToString(" ")
        }
}

/** Parser und Generator für vCards (Versionen 2.1, 3.0 und 4.0, soweit für NFC-Visitenkarten üblich). */
object VCardParser {

    /** Gibt null zurück, wenn der Text keine vCard enthält. */
    fun parse(text: String): VCardInfo? {
        val lines = unfold(text)
        var inCard = false
        var sawCard = false
        var version = ""
        var fn = ""
        var family = ""
        var given = ""
        var org = ""
        var title = ""
        var note = ""
        var bday = ""
        val phones = ArrayList<LabeledValue>()
        val emails = ArrayList<LabeledValue>()
        val urls = ArrayList<String>()
        val addresses = ArrayList<String>()
        var firstAddress: List<String> = emptyList()
        val names = ArrayList<String>()

        for (line in lines) {
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val head = line.substring(0, colon)
            val rawValue = line.substring(colon + 1)
            val headParts = head.split(';')
            val name = headParts[0].substringAfterLast('.').trim().uppercase()

            if (name == "BEGIN" && rawValue.trim().equals("VCARD", ignoreCase = true)) {
                if (sawCard) break
                inCard = true
                sawCard = true
                continue
            }
            if (name == "END" && rawValue.trim().equals("VCARD", ignoreCase = true)) {
                break
            }
            if (!inCard) continue

            val types = ArrayList<String>()
            var quotedPrintable = false
            var charset = "UTF-8"
            for (p in headParts.drop(1)) {
                val eq = p.indexOf('=')
                if (eq < 0) {
                    val up = p.trim().uppercase()
                    if (up == "QUOTED-PRINTABLE") quotedPrintable = true else if (up.isNotEmpty()) types.add(up)
                } else {
                    val key = p.substring(0, eq).trim().uppercase()
                    val v = p.substring(eq + 1).trim()
                    when (key) {
                        "TYPE" -> types.addAll(v.split(',').map { it.trim().trim('"').uppercase() }.filter { it.isNotEmpty() })
                        "ENCODING" -> if (v.equals("QUOTED-PRINTABLE", ignoreCase = true)) quotedPrintable = true
                        "CHARSET" -> charset = v
                    }
                }
            }
            val value = if (quotedPrintable) decodeQuotedPrintable(rawValue, charset) else rawValue
            if (name !in setOf("VERSION")) names.add(name)

            when (name) {
                "VERSION" -> version = value.trim()
                "FN" -> fn = unescape(value)
                "N" -> {
                    val parts = splitUnescaped(value, ';').map { unescape(it) }
                    family = parts.getOrElse(0) { "" }
                    given = parts.getOrElse(1) { "" }
                }
                "ORG" -> org = splitUnescaped(value, ';').map { unescape(it) }.filter { it.isNotBlank() }.joinToString(", ")
                "TITLE" -> title = unescape(value)
                "TEL" -> phones.add(LabeledValue(typeLabel(types), unescape(value).removePrefix("tel:").trim()))
                "EMAIL" -> emails.add(LabeledValue(typeLabel(types), unescape(value).removePrefix("mailto:").trim()))
                "URL" -> urls.add(unescape(value))
                "ADR" -> {
                    val parts = splitUnescaped(value, ';').map { unescape(it) }
                    val padded = (parts + List(7) { "" }).take(7)
                    if (firstAddress.isEmpty()) firstAddress = padded
                    val street = padded[2]
                    val cityLine = listOf(padded[5], padded[3]).filter { it.isNotBlank() }.joinToString(" ")
                    addresses.add(listOf(street, cityLine, padded[4], padded[6]).filter { it.isNotBlank() }.joinToString(", "))
                }
                "NOTE" -> note = unescape(value)
                "BDAY" -> bday = value.trim()
            }
        }
        if (!sawCard) return null
        return VCardInfo(
            version = version,
            formattedName = fn,
            familyName = family,
            givenName = given,
            organization = org,
            jobTitle = title,
            phones = phones,
            emails = emails,
            urls = urls,
            addresses = addresses,
            firstAddressParts = firstAddress,
            note = note,
            birthday = bday,
            propertyNames = names,
        )
    }

    /** True, wenn die vCard nur Eigenschaften enthält, die das Kontaktformular verlustfrei abbilden kann. */
    fun isSimple(info: VCardInfo): Boolean {
        val supported = setOf("N", "FN", "ORG", "TITLE", "TEL", "EMAIL", "URL", "ADR", "NOTE")
        if (!supported.containsAll(info.propertyNames)) return false
        return info.phones.size <= 1 && info.emails.size <= 1 && info.urls.size <= 1 && info.addresses.size <= 1
    }

    /** Erzeugt eine vCard 3.0 (CRLF-Zeilenenden). Leere Felder werden weggelassen. */
    fun build(
        givenName: String,
        familyName: String,
        organization: String = "",
        jobTitle: String = "",
        phone: String = "",
        email: String = "",
        url: String = "",
        street: String = "",
        postalCode: String = "",
        city: String = "",
        country: String = "",
        note: String = "",
    ): String {
        val sb = StringBuilder()
        sb.append("BEGIN:VCARD\r\n")
        sb.append("VERSION:3.0\r\n")
        sb.append("N:").append(escape(familyName)).append(';').append(escape(givenName)).append(";;;\r\n")
        val full = listOf(givenName, familyName).filter { it.isNotBlank() }.joinToString(" ")
        sb.append("FN:").append(escape(if (full.isBlank()) organization else full)).append("\r\n")
        if (organization.isNotBlank()) sb.append("ORG:").append(escape(organization)).append("\r\n")
        if (jobTitle.isNotBlank()) sb.append("TITLE:").append(escape(jobTitle)).append("\r\n")
        if (phone.isNotBlank()) sb.append("TEL;TYPE=CELL:").append(escape(phone)).append("\r\n")
        if (email.isNotBlank()) sb.append("EMAIL;TYPE=INTERNET:").append(escape(email)).append("\r\n")
        if (url.isNotBlank()) sb.append("URL:").append(escape(url)).append("\r\n")
        if (street.isNotBlank() || postalCode.isNotBlank() || city.isNotBlank() || country.isNotBlank()) {
            sb.append("ADR;TYPE=WORK:;;")
                .append(escape(street)).append(';')
                .append(escape(city)).append(";;")
                .append(escape(postalCode)).append(';')
                .append(escape(country)).append("\r\n")
        }
        if (note.isNotBlank()) sb.append("NOTE:").append(escape(note)).append("\r\n")
        sb.append("END:VCARD")
        return sb.toString()
    }

    fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (c in value) {
            when (c) {
                '\\' -> sb.append("\\\\")
                ';' -> sb.append("\\;")
                ',' -> sb.append("\\,")
                '\n' -> sb.append("\\n")
                '\r' -> {}
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    fun unescape(value: String): String {
        if (value.indexOf('\\') < 0) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    'n', 'N' -> sb.append('\n')
                    else -> sb.append(n)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Teilt am Trennzeichen, ignoriert aber mit Backslash maskierte Vorkommen. */
    fun splitUnescaped(value: String, separator: Char): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                current.append(c).append(value[i + 1])
                i += 2
            } else if (c == separator) {
                parts.add(current.toString())
                current.setLength(0)
                i++
            } else {
                current.append(c)
                i++
            }
        }
        parts.add(current.toString())
        return parts
    }

    private fun typeLabel(types: List<String>): String {
        val mapped = types.mapNotNull {
            when (it) {
                "CELL" -> "Mobil"
                "WORK" -> "Arbeit"
                "HOME" -> "Privat"
                "FAX" -> "Fax"
                "PAGER" -> "Pager"
                "MAIN" -> "Haupt"
                else -> null
            }
        }
        return mapped.distinct().joinToString(", ")
    }

    private fun isQuotedPrintable(line: String): Boolean = line.uppercase().contains("QUOTED-PRINTABLE")

    private fun unfold(text: String): List<String> {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            var line = raw[i]
            i++
            while (line.endsWith("=") && isQuotedPrintable(line) && i < raw.size) {
                line = line.dropLast(1) + raw[i]
                i++
            }
            while (i < raw.size && raw[i].isNotEmpty() && (raw[i][0] == ' ' || raw[i][0] == '\t')) {
                line += raw[i].substring(1)
                i++
            }
            if (line.isNotEmpty()) out.add(line)
        }
        return out
    }

    private fun decodeQuotedPrintable(value: String, charsetName: String): String {
        val charset = try {
            Charset.forName(charsetName)
        } catch (e: Exception) {
            Charsets.UTF_8
        }
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '=' && i + 2 < value.length) {
                val hi = Character.digit(value[i + 1], 16)
                val lo = Character.digit(value[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo)
                    i += 3
                    continue
                }
            }
            val bytes = c.toString().toByteArray(charset)
            out.write(bytes, 0, bytes.size)
            i++
        }
        return String(out.toByteArray(), charset)
    }
}
