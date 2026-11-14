package de.nfcmanager.app.core.ndef

/** URI-Präfixtabelle des NFC Forum URI Record Type Definition (RTD-URI 1.0). */
object NdefUri {
    val PREFIXES: Array<String> = arrayOf(
        "",                                // 0x00
        "http://www.",                     // 0x01
        "https://www.",                    // 0x02
        "http://",                         // 0x03
        "https://",                        // 0x04
        "tel:",                            // 0x05
        "mailto:",                         // 0x06
        "ftp://anonymous:anonymous@",      // 0x07
        "ftp://ftp.",                      // 0x08
        "ftps://",                         // 0x09
        "sftp://",                         // 0x0A
        "smb://",                          // 0x0B
        "nfs://",                          // 0x0C
        "ftp://",                          // 0x0D
        "dav://",                          // 0x0E
        "news:",                           // 0x0F
        "telnet://",                       // 0x10
        "imap:",                           // 0x11
        "rtsp://",                         // 0x12
        "urn:",                            // 0x13
        "pop:",                            // 0x14
        "sip:",                            // 0x15
        "sips:",                           // 0x16
        "tftp:",                           // 0x17
        "btspp://",                        // 0x18
        "btl2cap://",                      // 0x19
        "btgoep://",                       // 0x1A
        "tcpobex://",                      // 0x1B
        "irdaobex://",                     // 0x1C
        "file://",                         // 0x1D
        "urn:epc:id:",                     // 0x1E
        "urn:epc:tag:",                    // 0x1F
        "urn:epc:pat:",                    // 0x20
        "urn:epc:raw:",                    // 0x21
        "urn:epc:",                        // 0x22
        "urn:nfc:",                        // 0x23
    )

    /** Dekodiert die Nutzlast eines URI-Records zur vollständigen URI. Null bei leerer Nutzlast. */
    fun decode(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val code = payload[0].toInt() and 0xFF
        val prefix = if (code < PREFIXES.size) PREFIXES[code] else ""
        return prefix + String(payload, 1, payload.size - 1, Charsets.UTF_8)
    }

    /** Erzeugt die Nutzlast eines URI-Records und wählt das längste passende Präfix. */
    fun encode(uri: String): ByteArray {
        var bestIndex = 0
        var bestLength = 0
        for (i in 1 until PREFIXES.size) {
            val p = PREFIXES[i]
            if (p.length > bestLength && uri.startsWith(p)) {
                bestIndex = i
                bestLength = p.length
            }
        }
        val rest = uri.substring(bestLength).toByteArray(Charsets.UTF_8)
        val out = ByteArray(1 + rest.size)
        out[0] = bestIndex.toByte()
        System.arraycopy(rest, 0, out, 1, rest.size)
        return out
    }
}
