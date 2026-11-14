package de.nfcmanager.app.core.ndef

import de.nfcmanager.app.core.Hex
import de.nfcmanager.app.core.hce.Type4Tag
import java.net.URLDecoder
import java.net.URLEncoder

/** Eingabeart eines Formularfelds (steuert in der UI Tastatur und Darstellung). */
enum class FieldInput { TEXT, MULTILINE, PHONE, EMAIL, URL, DECIMAL, PASSWORD, HEX }

data class DraftField(
    val key: String,
    val label: String,
    val hint: String = "",
    val input: FieldInput = FieldInput.TEXT,
    val required: Boolean = false,
    /** Auswahlfelder: Paare aus gespeichertem Wert und Anzeigetext. Leer = freie Eingabe. */
    val choices: List<Pair<String, String>> = emptyList(),
    val defaultValue: String = "",
)

/** Arten von Einträgen, die der Inhalts-Editor erzeugen kann. */
enum class DraftKind(val label: String, val fields: List<DraftField>) {
    URL(
        "Webadresse / Link",
        listOf(DraftField("url", "Adresse", "https://beispiel.de", FieldInput.URL, required = true)),
    ),
    TEXT(
        "Text",
        listOf(
            DraftField("text", "Text", "", FieldInput.MULTILINE, required = true),
            DraftField("lang", "Sprachcode", "de", FieldInput.TEXT, defaultValue = "de"),
        ),
    ),
    PHONE(
        "Telefonnummer",
        listOf(DraftField("number", "Nummer", "+49 170 1234567", FieldInput.PHONE, required = true)),
    ),
    EMAIL(
        "E-Mail",
        listOf(
            DraftField("address", "E-Mail-Adresse", "name@beispiel.de", FieldInput.EMAIL, required = true),
            DraftField("subject", "Betreff (optional)"),
            DraftField("body", "Nachricht (optional)", "", FieldInput.MULTILINE),
        ),
    ),
    SMS(
        "SMS",
        listOf(
            DraftField("number", "Nummer", "+49 170 1234567", FieldInput.PHONE, required = true),
            DraftField("body", "Nachricht (optional)", "", FieldInput.MULTILINE),
        ),
    ),
    CONTACT(
        "Kontakt (vCard)",
        listOf(
            DraftField("given", "Vorname"),
            DraftField("family", "Nachname"),
            DraftField("org", "Firma / Organisation"),
            DraftField("title", "Position"),
            DraftField("phone", "Telefon", "", FieldInput.PHONE),
            DraftField("email", "E-Mail", "", FieldInput.EMAIL),
            DraftField("url", "Webseite", "", FieldInput.URL),
            DraftField("street", "Straße und Hausnummer"),
            DraftField("postal", "PLZ"),
            DraftField("city", "Ort"),
            DraftField("country", "Land"),
            DraftField("note", "Notiz", "", FieldInput.MULTILINE),
        ),
    ),
    WIFI(
        "WLAN-Zugang",
        listOf(
            DraftField("ssid", "Netzwerkname (SSID)", "", FieldInput.TEXT, required = true),
            DraftField(
                "security", "Sicherheit", "", FieldInput.TEXT,
                choices = WifiSecurity.values().map { it.name to it.label },
                defaultValue = WifiSecurity.WPA2_PSK.name,
            ),
            DraftField("password", "Passwort", "", FieldInput.PASSWORD),
        ),
    ),
    GEO(
        "Standort",
        listOf(
            DraftField("lat", "Breitengrad", "52.5200", FieldInput.DECIMAL, required = true),
            DraftField("lon", "Längengrad", "13.4050", FieldInput.DECIMAL, required = true),
        ),
    ),
    MIME_TEXT(
        "Daten als Text (MIME)",
        listOf(
            DraftField("mime", "MIME-Typ", "text/plain", FieldInput.TEXT, required = true, defaultValue = "text/plain"),
            DraftField("content", "Inhalt", "", FieldInput.MULTILINE, required = true),
        ),
    ),
    RAW(
        "Rohdaten (Experten)",
        listOf(
            DraftField(
                "tnf", "Typ-Namensformat (TNF)", "", FieldInput.TEXT, required = true,
                choices = listOf(
                    "0" to "0 – Leer",
                    "1" to "1 – Well-Known",
                    "2" to "2 – MIME",
                    "3" to "3 – Absolute URI",
                    "4" to "4 – Extern",
                    "5" to "5 – Unbekannt",
                ),
                defaultValue = "2",
            ),
            DraftField("type", "Typ (Text)"),
            DraftField("id", "Record-ID (Hex, optional)", "", FieldInput.HEX),
            DraftField("payload", "Nutzlast (Hex)", "", FieldInput.HEX),
        ),
    ),
}

/** Fehlermeldung (deutsch, für Anwender) bei ungültigen Editor-Eingaben. */
class DraftException(message: String) : Exception(message)

/** Ein Eintrag im Editor: Art plus Feldwerte. */
data class NdefDraft(val kind: DraftKind, val values: Map<String, String> = emptyMap()) {

    fun get(key: String): String =
        values[key] ?: kind.fields.firstOrNull { it.key == key }?.defaultValue ?: ""

    fun with(key: String, value: String): NdefDraft = copy(values = values + (key to value))

    companion object {
        fun empty(kind: DraftKind): NdefDraft =
            NdefDraft(kind, kind.fields.associate { it.key to it.defaultValue })
    }
}

/** Umwandlung zwischen Editor-Einträgen und NDEF-Records. */
object NdefDrafts {

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:")
    private val GEO_URI = Regex("^geo:(-?[0-9]+(?:\\.[0-9]+)?),(-?[0-9]+(?:\\.[0-9]+)?)$")

    /** Erzeugt die komplette Nachricht. Wirft [DraftException] mit verständlicher Meldung. */
    fun toMessage(drafts: List<NdefDraft>): ByteArray {
        if (drafts.isEmpty()) throw DraftException("Bitte mindestens einen Eintrag hinzufügen.")
        val bytes = NdefCodec.encode(drafts.map { toRecord(it) })
        if (bytes.size > Type4Tag.MAX_NDEF_MESSAGE_BYTES) {
            throw DraftException("Die Nachricht ist zu groß (${bytes.size} Bytes).")
        }
        return bytes
    }

    fun toRecord(draft: NdefDraft): NdefRecordData = when (draft.kind) {
        DraftKind.URL -> {
            val raw = draft.get("url").trim()
            if (raw.isEmpty()) throw DraftException("Bitte eine Adresse eingeben.")
            uriRecord(if (SCHEME.containsMatchIn(raw)) raw else "https://$raw")
        }
        DraftKind.TEXT -> {
            val text = draft.get("text")
            if (text.isBlank()) throw DraftException("Bitte einen Text eingeben.")
            val lang = draft.get("lang").trim().ifEmpty { "de" }
            if (lang.length > 63 || lang.any { it.code > 127 }) throw DraftException("Der Sprachcode ist ungültig.")
            val langBytes = lang.toByteArray(Charsets.US_ASCII)
            val payload = byteArrayOf(langBytes.size.toByte()) + langBytes + text.toByteArray(Charsets.UTF_8)
            NdefRecordData.wellKnown(NdefRecordData.RTD_TEXT, payload)
        }
        DraftKind.PHONE -> uriRecord("tel:" + cleanNumber(draft.get("number")))
        DraftKind.EMAIL -> {
            val address = draft.get("address").trim()
            if (address.isEmpty() || !address.contains('@')) {
                throw DraftException("Bitte eine gültige E-Mail-Adresse eingeben.")
            }
            uriRecord("mailto:" + address + query("subject" to draft.get("subject"), "body" to draft.get("body")))
        }
        DraftKind.SMS -> uriRecord("sms:" + cleanNumber(draft.get("number")) + query("body" to draft.get("body")))
        DraftKind.GEO -> {
            val lat = draft.get("lat").trim().replace(',', '.')
            val lon = draft.get("lon").trim().replace(',', '.')
            val latValue = lat.toDoubleOrNull()
            val lonValue = lon.toDoubleOrNull()
            if (latValue == null || lonValue == null || latValue < -90.0 || latValue > 90.0 ||
                lonValue < -180.0 || lonValue > 180.0
            ) {
                throw DraftException("Bitte gültige Koordinaten eingeben (Breite −90 bis 90, Länge −180 bis 180).")
            }
            uriRecord("geo:$lat,$lon")
        }
        DraftKind.CONTACT -> {
            val given = draft.get("given").trim()
            val family = draft.get("family").trim()
            val org = draft.get("org").trim()
            if (given.isEmpty() && family.isEmpty() && org.isEmpty()) {
                throw DraftException("Bitte mindestens einen Namen oder eine Firma eingeben.")
            }
            val card = VCardParser.build(
                givenName = given, familyName = family, organization = org,
                jobTitle = draft.get("title").trim(), phone = draft.get("phone").trim(),
                email = draft.get("email").trim(), url = draft.get("url").trim(),
                street = draft.get("street").trim(), postalCode = draft.get("postal").trim(),
                city = draft.get("city").trim(), country = draft.get("country").trim(),
                note = draft.get("note").trim(),
            )
            NdefRecordData.mime("text/vcard", card.toByteArray(Charsets.UTF_8))
        }
        DraftKind.WIFI -> {
            val ssid = draft.get("ssid")
            if (ssid.isEmpty()) throw DraftException("Bitte den Netzwerknamen (SSID) eingeben.")
            if (ssid.toByteArray(Charsets.UTF_8).size > 32) throw DraftException("Der Netzwerkname darf höchstens 32 Bytes lang sein.")
            val security = WifiSecurity.values().firstOrNull { it.name == draft.get("security") } ?: WifiSecurity.WPA2_PSK
            val password = draft.get("password")
            if (security != WifiSecurity.OPEN && password.length < 8) {
                throw DraftException("Das WLAN-Passwort muss mindestens 8 Zeichen lang sein.")
            }
            NdefRecordData.mime(WifiConfig.MIME_TYPE, WifiConfig.build(ssid, password, security))
        }
        DraftKind.MIME_TEXT -> {
            val mime = draft.get("mime").trim()
            if (!mime.contains('/') || mime.any { it.isWhitespace() } || mime.length > 255) {
                throw DraftException("Bitte einen gültigen MIME-Typ eingeben (z. B. text/plain).")
            }
            NdefRecordData.mime(mime, draft.get("content").toByteArray(Charsets.UTF_8))
        }
        DraftKind.RAW -> {
            val tnf = draft.get("tnf").trim().toIntOrNull()
            if (tnf == null || tnf < 0 || tnf > 5) throw DraftException("Bitte ein gültiges TNF (0 bis 5) wählen.")
            val type = if (tnf == NdefRecordData.TNF_EMPTY || tnf == NdefRecordData.TNF_UNKNOWN) {
                ByteArray(0)
            } else {
                draft.get("type").toByteArray(Charsets.UTF_8)
            }
            val idText = draft.get("id").trim()
            val id = if (idText.isEmpty()) ByteArray(0) else Hex.tryDecode(idText)
                ?: throw DraftException("Die Record-ID ist kein gültiger Hex-Text.")
            val payloadText = draft.get("payload").trim()
            val payload = if (payloadText.isEmpty()) ByteArray(0) else Hex.tryDecode(payloadText)
                ?: throw DraftException("Die Nutzlast ist kein gültiger Hex-Text (gerade Anzahl Zeichen 0–9, A–F).")
            if (type.size > 255 || id.size > 255) throw DraftException("Typ oder ID sind zu lang (höchstens 255 Bytes).")
            if (tnf == NdefRecordData.TNF_EMPTY && (type.isNotEmpty() || id.isNotEmpty() || payload.isNotEmpty())) {
                throw DraftException("Ein leerer Eintrag darf weder Typ, ID noch Nutzlast enthalten.")
            }
            if (tnf != NdefRecordData.TNF_EMPTY && type.isEmpty() &&
                tnf != NdefRecordData.TNF_UNKNOWN
            ) {
                throw DraftException("Bitte einen Typ angeben.")
            }
            NdefRecordData(tnf, type, id, payload)
        }
    }

    /** Übersetzt eine gespeicherte Nachricht in Editor-Einträge. Leere oder ungültige Nachrichten ergeben eine leere Liste. */
    fun fromMessage(bytes: ByteArray?): List<NdefDraft> {
        if (bytes == null || bytes.isEmpty()) return emptyList()
        val records = NdefCodec.tryParse(bytes) ?: return emptyList()
        if (NdefCodec.isEffectivelyEmpty(records)) return emptyList()
        return records.map { fromRecord(it) }
    }

    /** Erzeugt den passendsten Editor-Eintrag; was sich nicht verlustfrei abbilden lässt, wird zum Rohdaten-Eintrag. */
    fun fromRecord(r: NdefRecordData): NdefDraft {
        try {
            if (r.tnf == NdefRecordData.TNF_WELL_KNOWN) {
                when (r.typeString) {
                    "U" -> {
                        val uri = NdefUri.decode(r.payload)
                        if (uri != null) return fromUri(uri)
                    }
                    "T" -> textDraft(r)?.let { return it }
                }
            } else if (r.tnf == NdefRecordData.TNF_MIME_MEDIA) {
                mimeDraft(r)?.let { return it }
            }
        } catch (e: RuntimeException) {
            // fällt auf Rohdaten zurück
        }
        return rawDraft(r)
    }

    // ---- Rückübersetzung ------------------------------------------------------------------------

    private fun fromUri(uri: String): NdefDraft {
        val lower = uri.lowercase()
        when {
            lower.startsWith("tel:") ->
                return NdefDraft(DraftKind.PHONE, mapOf("number" to uri.substring(4)))
            lower.startsWith("mailto:") -> {
                val rest = uri.substring(7)
                val address = rest.substringBefore('?')
                val params = parseQuery(rest.substringAfter('?', ""))
                if (params != null && params.keys.all { it == "subject" || it == "body" }) {
                    return NdefDraft(
                        DraftKind.EMAIL,
                        mapOf("address" to address, "subject" to (params["subject"] ?: ""), "body" to (params["body"] ?: "")),
                    )
                }
            }
            lower.startsWith("sms:") || lower.startsWith("smsto:") -> {
                val rest = uri.substringAfter(':')
                val number = rest.substringBefore('?')
                val params = parseQuery(rest.substringAfter('?', ""))
                if (params != null && params.keys.all { it == "body" } && !number.contains(';')) {
                    return NdefDraft(DraftKind.SMS, mapOf("number" to number, "body" to (params["body"] ?: "")))
                }
            }
            lower.startsWith("geo:") -> {
                val m = GEO_URI.matchEntire(uri)
                if (m != null) {
                    return NdefDraft(DraftKind.GEO, mapOf("lat" to m.groupValues[1], "lon" to m.groupValues[2]))
                }
            }
        }
        return NdefDraft(DraftKind.URL, mapOf("url" to uri))
    }

    private fun textDraft(r: NdefRecordData): NdefDraft? {
        val payload = r.payload
        if (payload.isEmpty()) return null
        val status = payload[0].toInt() and 0xFF
        val utf16 = status and 0x80 != 0
        val langLength = status and 0x3F
        if (payload.size < 1 + langLength) return null
        val lang = String(payload, 1, langLength, Charsets.US_ASCII)
        val text = String(payload, 1 + langLength, payload.size - 1 - langLength, if (utf16) Charsets.UTF_16 else Charsets.UTF_8)
        return NdefDraft(DraftKind.TEXT, mapOf("text" to text, "lang" to lang))
    }

    private fun mimeDraft(r: NdefRecordData): NdefDraft? {
        val mime = r.typeString.trim()
        val lower = mime.lowercase()
        if (lower.startsWith("text/vcard") || lower.startsWith("text/x-vcard")) {
            val text = strictText(r.payload) ?: return null
            val card = VCardParser.parse(text)
            if (card != null && VCardParser.isSimple(card)) {
                val parts = card.firstAddressParts
                var given = card.givenName
                if (given.isBlank() && card.familyName.isBlank()) given = card.formattedName
                return NdefDraft(
                    DraftKind.CONTACT,
                    mapOf(
                        "given" to given, "family" to card.familyName, "org" to card.organization,
                        "title" to card.jobTitle,
                        "phone" to (card.phones.firstOrNull()?.value ?: ""),
                        "email" to (card.emails.firstOrNull()?.value ?: ""),
                        "url" to (card.urls.firstOrNull() ?: ""),
                        "street" to parts.getOrElse(2) { "" }, "city" to parts.getOrElse(3) { "" },
                        "postal" to parts.getOrElse(5) { "" }, "country" to parts.getOrElse(6) { "" },
                        "note" to card.note,
                    ),
                )
            }
            return textMimeDraft(mime, text)
        }
        if (lower == WifiConfig.MIME_TYPE) {
            val wifi = WifiConfig.parse(r.payload) ?: return null
            val security = when (wifi.authType) {
                0x0001 -> WifiSecurity.OPEN
                0x0020 -> WifiSecurity.WPA2_PSK
                0x0022 -> WifiSecurity.WPA_WPA2_PSK
                else -> return null
            }
            return NdefDraft(
                DraftKind.WIFI,
                mapOf("ssid" to wifi.ssid, "password" to wifi.password, "security" to security.name),
            )
        }
        val text = strictText(r.payload)
        return if (text != null) textMimeDraft(mime, text) else null
    }

    private fun textMimeDraft(mime: String, text: String): NdefDraft =
        NdefDraft(DraftKind.MIME_TEXT, mapOf("mime" to mime, "content" to text))

    private fun rawDraft(r: NdefRecordData): NdefDraft = NdefDraft(
        DraftKind.RAW,
        mapOf(
            "tnf" to r.tnf.coerceIn(0, 5).toString(),
            "type" to r.typeString,
            "id" to Hex.encode(r.id),
            "payload" to Hex.encode(r.payload, " "),
        ),
    )

    // ---- Hilfen ---------------------------------------------------------------------------------

    private fun uriRecord(uri: String): NdefRecordData =
        NdefRecordData.wellKnown(NdefRecordData.RTD_URI, NdefUri.encode(uri))

    private fun cleanNumber(raw: String): String {
        val n = raw.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        if (n.isEmpty()) throw DraftException("Bitte eine Telefonnummer eingeben.")
        return n
    }

    private fun query(vararg pairs: Pair<String, String>): String {
        val parts = pairs.filter { it.second.isNotBlank() }.map { (k, v) -> k + "=" + urlEncode(v) }
        return if (parts.isEmpty()) "" else "?" + parts.joinToString("&")
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Zerlegt "a=b&c=d" in eine Map oder liefert null bei ungültiger Kodierung. */
    private fun parseQuery(q: String): Map<String, String>? {
        if (q.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        return try {
            for (pair in q.split('&')) {
                if (pair.isEmpty()) continue
                val key = URLDecoder.decode(pair.substringBefore('='), "UTF-8").lowercase()
                val value = URLDecoder.decode(pair.substringAfter('=', "").replace("+", "%2B"), "UTF-8")
                out[key] = value
            }
            out
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Vollständiger Text, wenn die Bytes gültiges, lesbares UTF-8 sind; sonst null. */
    private fun strictText(bytes: ByteArray): String? {
        val s = String(bytes, Charsets.UTF_8)
        if (s.indexOf('�') >= 0) return null
        if (s.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
        return s
    }
}
