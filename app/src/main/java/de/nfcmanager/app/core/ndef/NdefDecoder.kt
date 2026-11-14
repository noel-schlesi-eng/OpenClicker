package de.nfcmanager.app.core.ndef

import de.nfcmanager.app.core.Hex

enum class RecordKind {
    EMPTY, URI, TEXT, SMART_POSTER, CONTACT, WIFI, BLUETOOTH, ANDROID_APP,
    MIME, EXTERNAL, ABSOLUTE_URI, HANDOVER, UNKNOWN,
}

/** Ein beschriftetes Detailfeld; [sensitive] markiert Geheimnisse (z. B. WLAN-Passwort), die die UI verbirgt. */
data class DecodedField(val label: String, val value: String, val sensitive: Boolean = false)

/** Menschenlesbare Auswertung eines NDEF-Records. */
data class DecodedRecord(
    val kind: RecordKind,
    val title: String,
    val summary: String,
    val fields: List<DecodedField>,
    /** URI, die per Intent geöffnet werden darf (http, https, tel, mailto, sms, geo, ...), sonst null. */
    val openUri: String?,
    /** Technische Typangabe, z. B. "Well-Known · U" oder "MIME · text/vcard". */
    val typeInfo: String,
    val payloadSize: Int,
    val children: List<DecodedRecord> = emptyList(),
    /** Gesetzt bei vCards, damit die UI den Kontakt speichern kann. */
    val contact: VCardInfo? = null,
)

data class DecodedMessage(val records: List<DecodedRecord>, val error: String?)

/** Wertet NDEF-Nachrichten aus: URL, Text, Smart Poster, Kontakt, WLAN, Bluetooth, App-Record, MIME u. a. */
object NdefDecoder {

    private val OPENABLE_SCHEMES = setOf("http", "https", "tel", "mailto", "sms", "smsto", "geo", "sip", "sips", "market")

    fun decodeMessage(bytes: ByteArray): DecodedMessage {
        val records = try {
            NdefCodec.parse(bytes)
        } catch (e: NdefFormatException) {
            return DecodedMessage(emptyList(), "Die NDEF-Daten sind ungültig: ${e.message}")
        }
        return DecodedMessage(decodeRecords(records), null)
    }

    fun decodeRecords(records: List<NdefRecordData>): List<DecodedRecord> = records.map { decode(it) }

    /** Wertet einen Record aus. Bei unerwarteten Daten wird ein generischer Eintrag geliefert, nie eine Exception. */
    fun decode(record: NdefRecordData): DecodedRecord =
        try {
            decodeInternal(record)
        } catch (e: RuntimeException) {
            generic(record, RecordKind.UNKNOWN, "Datensatz", "TNF ${record.tnf}")
        }

    /** Einzeilige Zusammenfassung des gesamten Inhalts für Listen. */
    fun summarize(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return "Kein NDEF-Inhalt"
        val message = decodeMessage(bytes)
        if (message.error != null || message.records.isEmpty()) return "Ungültige NDEF-Daten"
        val first = message.records[0]
        val head = if (first.kind == RecordKind.EMPTY) first.title else "${first.title}: ${first.summary}"
        return if (message.records.size > 1) "$head (+${message.records.size - 1} weitere)" else head
    }

    fun oneLine(text: String, max: Int = 90): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return if (t.length > max) t.substring(0, max - 1) + "…" else t
    }

    private fun decodeInternal(r: NdefRecordData): DecodedRecord = when (r.tnf) {
        NdefRecordData.TNF_EMPTY ->
            DecodedRecord(RecordKind.EMPTY, "Leerer Eintrag", "", emptyList(), null, "Leer", 0)
        NdefRecordData.TNF_WELL_KNOWN -> decodeWellKnown(r)
        NdefRecordData.TNF_MIME_MEDIA -> decodeMime(r)
        NdefRecordData.TNF_ABSOLUTE_URI -> decodeAbsoluteUri(r)
        NdefRecordData.TNF_EXTERNAL_TYPE -> decodeExternal(r)
        else -> generic(r, RecordKind.UNKNOWN, "Unbekannter Datensatz", "TNF ${r.tnf}")
    }

    // ---- Well-Known ---------------------------------------------------------------------------

    private fun decodeWellKnown(r: NdefRecordData): DecodedRecord {
        val type = r.typeString
        val info = "Well-Known · $type"
        return when (type) {
            "U" -> decodeUri(r, info)
            "T" -> decodeText(r, info)
            "Sp" -> decodeSmartPoster(r, info)
            "Hs", "Hr", "Hc" -> decodeHandover(r, type, info)
            else -> generic(r, RecordKind.UNKNOWN, "Datensatz (Well-Known)", info)
        }
    }

    private fun decodeUri(r: NdefRecordData, info: String): DecodedRecord {
        val uri = NdefUri.decode(r.payload) ?: return generic(r, RecordKind.URI, "Link (leer)", info)
        val scheme = uri.substringBefore(':', "").lowercase()
        val title = when (scheme) {
            "http", "https" -> "Webadresse"
            "tel" -> "Telefonnummer"
            "mailto" -> "E-Mail"
            "sms", "smsto" -> "SMS"
            "geo" -> "Standort"
            "sip", "sips" -> "SIP-Adresse"
            "market" -> "App-Store-Link"
            else -> "Link (URI)"
        }
        val summary = when (scheme) {
            "tel" -> uri.removePrefix("tel:")
            "mailto" -> uri.removePrefix("mailto:").substringBefore('?')
            else -> uri
        }
        val fields = ArrayList<DecodedField>()
        fields.add(DecodedField("Adresse", uri))
        if (scheme == "http" || scheme == "https") {
            val host = uri.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            if (host.isNotEmpty()) fields.add(DecodedField("Host", host))
        }
        val open = if (scheme in OPENABLE_SCHEMES) uri else null
        return DecodedRecord(RecordKind.URI, title, oneLine(summary), fields, open, info, r.payload.size)
    }

    private fun decodeText(r: NdefRecordData, info: String): DecodedRecord {
        val payload = r.payload
        if (payload.isEmpty()) return generic(r, RecordKind.TEXT, "Text (leer)", info)
        val status = payload[0].toInt() and 0xFF
        val utf16 = status and 0x80 != 0
        val langLength = status and 0x3F
        if (payload.size < 1 + langLength) return generic(r, RecordKind.TEXT, "Text (beschädigt)", info)
        val lang = String(payload, 1, langLength, Charsets.US_ASCII)
        val charset = if (utf16) Charsets.UTF_16 else Charsets.UTF_8
        val text = String(payload, 1 + langLength, payload.size - 1 - langLength, charset)
        val fields = arrayListOf(DecodedField("Text", text))
        if (lang.isNotEmpty()) fields.add(DecodedField("Sprache", lang))
        fields.add(DecodedField("Kodierung", if (utf16) "UTF-16" else "UTF-8"))
        return DecodedRecord(RecordKind.TEXT, "Text", oneLine(text), fields, null, info, payload.size)
    }

    private fun decodeSmartPoster(r: NdefRecordData, info: String): DecodedRecord {
        val nested = NdefCodec.tryParse(r.payload)
            ?: return generic(r, RecordKind.SMART_POSTER, "Smart Poster (ungültig)", info)
        val fields = ArrayList<DecodedField>()
        val children = ArrayList<DecodedRecord>()
        for (n in nested) {
            val isMeta = n.tnf == NdefRecordData.TNF_WELL_KNOWN && n.typeString in setOf("act", "t", "s")
            if (!isMeta) {
                children.add(decode(n))
                continue
            }
            when (n.typeString) {
                "act" -> if (n.payload.isNotEmpty()) {
                    val action = when (n.payload[0].toInt()) {
                        0 -> "Ausführen"
                        1 -> "Zum späteren Gebrauch speichern"
                        2 -> "Zum Bearbeiten öffnen"
                        else -> "Unbekannt (${n.payload[0].toInt()})"
                    }
                    fields.add(DecodedField("Empfohlene Aktion", action))
                }
                "t" -> fields.add(DecodedField("Inhaltstyp", String(n.payload, Charsets.UTF_8)))
                "s" -> if (n.payload.size == 4) {
                    val size = ((n.payload[0].toLong() and 0xFF) shl 24) or ((n.payload[1].toLong() and 0xFF) shl 16) or
                        ((n.payload[2].toLong() and 0xFF) shl 8) or (n.payload[3].toLong() and 0xFF)
                    fields.add(DecodedField("Angegebene Größe", "$size Bytes"))
                }
            }
        }
        val titleText = children.firstOrNull { it.kind == RecordKind.TEXT }
            ?.fields?.firstOrNull { it.label == "Text" }?.value
        val uriChild = children.firstOrNull { it.kind == RecordKind.URI }
        val address = uriChild?.fields?.firstOrNull { it.label == "Adresse" }?.value
        val head = ArrayList<DecodedField>()
        if (titleText != null) head.add(DecodedField("Titel", titleText))
        if (address != null) head.add(DecodedField("Adresse", address))
        val summary = listOfNotNull(titleText, address).joinToString(" – ").ifEmpty { "Smart Poster" }
        return DecodedRecord(
            RecordKind.SMART_POSTER, "Smart Poster", oneLine(summary), head + fields,
            uriChild?.openUri, info, r.payload.size, children,
        )
    }

    private fun decodeHandover(r: NdefRecordData, type: String, info: String): DecodedRecord {
        val kindText = when (type) {
            "Hs" -> "Handover Select"
            "Hr" -> "Handover Request"
            else -> "Handover Carrier"
        }
        val fields = arrayListOf(DecodedField("Art", kindText))
        var children: List<DecodedRecord> = emptyList()
        if (type != "Hc" && r.payload.isNotEmpty()) {
            val v = r.payload[0].toInt() and 0xFF
            fields.add(DecodedField("Version", "${v shr 4}.${v and 0x0F}"))
            val nested = NdefCodec.tryParse(r.payload.copyOfRange(1, r.payload.size))
            if (nested != null) children = nested.map { decode(it) }
        }
        return DecodedRecord(
            RecordKind.HANDOVER, "Verbindungsübergabe (Handover)", kindText, fields,
            null, info, r.payload.size, children,
        )
    }

    // ---- MIME -----------------------------------------------------------------------------------

    private fun decodeMime(r: NdefRecordData): DecodedRecord {
        val mime = r.typeString.trim()
        val lower = mime.lowercase()
        val info = "MIME · $mime"

        if (lower.startsWith("text/vcard") || lower.startsWith("text/x-vcard") || lower.startsWith("text/directory")) {
            val card = VCardParser.parse(String(r.payload, Charsets.UTF_8))
            if (card != null) return contactRecord(card, r, info)
        }
        if (lower == WifiConfig.MIME_TYPE) {
            val wifi = WifiConfig.parse(r.payload)
            if (wifi != null) {
                val fields = arrayListOf(
                    DecodedField("Netzwerkname (SSID)", wifi.ssid),
                    DecodedField("Sicherheit", wifi.securityLabel),
                )
                if (wifi.password.isNotEmpty()) fields.add(DecodedField("Passwort", wifi.password, sensitive = true))
                return DecodedRecord(RecordKind.WIFI, "WLAN-Zugang", wifi.ssid, fields, null, info, r.payload.size)
            }
        }
        if (lower == BluetoothOobParser.MIME_TYPE_CLASSIC) {
            val bt = BluetoothOobParser.parse(r.payload)
            if (bt != null) {
                val fields = ArrayList<DecodedField>()
                if (bt.name != null) fields.add(DecodedField("Gerätename", bt.name))
                fields.add(DecodedField("Bluetooth-Adresse", bt.address))
                if (bt.deviceClass != null) fields.add(DecodedField("Geräteklasse", bt.deviceClass))
                return DecodedRecord(
                    RecordKind.BLUETOOTH, "Bluetooth-Kopplung", bt.name ?: bt.address,
                    fields, null, info, r.payload.size,
                )
            }
        }
        return generic(r, RecordKind.MIME, "Daten (MIME)", info, summaryHead = mime)
    }

    private fun contactRecord(card: VCardInfo, r: NdefRecordData, info: String): DecodedRecord {
        val fields = ArrayList<DecodedField>()
        if (card.displayName.isNotBlank()) fields.add(DecodedField("Name", card.displayName))
        if (card.organization.isNotBlank()) fields.add(DecodedField("Organisation", card.organization))
        if (card.jobTitle.isNotBlank()) fields.add(DecodedField("Position", card.jobTitle))
        for (p in card.phones) fields.add(DecodedField(if (p.label.isEmpty()) "Telefon" else "Telefon (${p.label})", p.value))
        for (e in card.emails) fields.add(DecodedField(if (e.label.isEmpty()) "E-Mail" else "E-Mail (${e.label})", e.value))
        for (u in card.urls) fields.add(DecodedField("Webseite", u))
        for (a in card.addresses) fields.add(DecodedField("Adresse", a))
        if (card.birthday.isNotBlank()) fields.add(DecodedField("Geburtstag", card.birthday))
        if (card.note.isNotBlank()) fields.add(DecodedField("Notiz", card.note))
        if (card.version.isNotBlank()) fields.add(DecodedField("vCard-Version", card.version))
        val summary = card.displayName.ifBlank { card.organization }.ifBlank { "Kontakt" }
        return DecodedRecord(
            RecordKind.CONTACT, "Kontakt", oneLine(summary), fields, null, info, r.payload.size, contact = card,
        )
    }

    // ---- Absolute URI und External Type ---------------------------------------------------------

    private fun decodeAbsoluteUri(r: NdefRecordData): DecodedRecord {
        val uri = r.typeString
        val scheme = uri.substringBefore(':', "").lowercase()
        val fields = arrayListOf(DecodedField("URI", uri))
        if (r.payload.isNotEmpty()) fields.add(DecodedField("Nutzlast", "${r.payload.size} Bytes"))
        val open = if (scheme in OPENABLE_SCHEMES) uri else null
        return DecodedRecord(RecordKind.ABSOLUTE_URI, "Absolute URI", oneLine(uri), fields, open, "Absolute URI", r.payload.size)
    }

    private fun decodeExternal(r: NdefRecordData): DecodedRecord {
        val type = r.typeString
        val info = "Extern · $type"
        if (type.equals("android.com:pkg", ignoreCase = true)) {
            val pkg = String(r.payload, Charsets.UTF_8)
            return DecodedRecord(
                RecordKind.ANDROID_APP, "Android-App (AAR)", pkg,
                listOf(DecodedField("Paketname", pkg)),
                if (pkg.isNotBlank()) "https://play.google.com/store/apps/details?id=$pkg" else null,
                info, r.payload.size,
            )
        }
        return generic(r, RecordKind.EXTERNAL, "Externer Typ", info, summaryHead = type)
    }

    // ---- Hilfen ---------------------------------------------------------------------------------

    private fun generic(
        r: NdefRecordData,
        kind: RecordKind,
        title: String,
        info: String,
        summaryHead: String? = null,
    ): DecodedRecord {
        val fields = ArrayList<DecodedField>()
        if (r.type.isNotEmpty()) fields.add(DecodedField("Typ", r.typeString))
        fields.add(DecodedField("Größe", "${r.payload.size} Bytes"))
        val text = textPreview(r.payload)
        if (text != null) {
            fields.add(DecodedField("Inhalt", text))
        } else if (r.payload.isNotEmpty()) {
            fields.add(DecodedField("Inhalt (Hex)", hexPreview(r.payload)))
        }
        if (r.id.isNotEmpty()) fields.add(DecodedField("Record-ID (Hex)", Hex.encode(r.id)))
        val summary = listOfNotNull(summaryHead ?: r.typeString.ifEmpty { null }, "${r.payload.size} Bytes").joinToString(" · ")
        return DecodedRecord(kind, title, oneLine(summary), fields, null, info, r.payload.size)
    }

    /** Liefert den Nutzlast-Text, wenn sie gültiges, lesbares UTF-8 ist; sonst null. */
    private fun textPreview(payload: ByteArray, max: Int = 400): String? {
        if (payload.isEmpty()) return null
        val s = String(payload, Charsets.UTF_8)
        if (s.indexOf('�') >= 0) return null
        if (s.any { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) return null
        return if (s.length > max) s.substring(0, max) + "…" else s
    }

    private fun hexPreview(payload: ByteArray, maxBytes: Int = 32): String {
        val n = minOf(payload.size, maxBytes)
        val head = Hex.encode(payload.copyOfRange(0, n), " ")
        return if (payload.size > maxBytes) "$head …" else head
    }
}
