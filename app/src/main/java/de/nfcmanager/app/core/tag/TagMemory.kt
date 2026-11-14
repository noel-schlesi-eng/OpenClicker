package de.nfcmanager.app.core.tag

/** Capability Container eines Type-2- oder Type-5-Tags. */
data class CapabilityContainer(
    val magic: Int,
    val versionMajor: Int,
    val versionMinor: Int,
    /** Größe des NDEF-Datenbereichs in Byte (MLEN × 8). */
    val dataAreaBytes: Int,
    val readAccess: Int,
    val writeAccess: Int,
    /** Länge des CC in Byte (Type 2: 4, Type 5: 4 oder 8). */
    val length: Int,
) {
    val versionText: String get() = "$versionMajor.$versionMinor"
}

/** Ergebnis der TLV-Auswertung. */
class TlvScan(
    /** Wert des ersten NDEF-Message-TLV (kann leer sein) oder null, wenn keiner gefunden wurde. */
    val ndef: ByteArray?,
    /** True, wenn der NDEF-TLV länger angegeben war, als Daten gelesen werden konnten. */
    val truncated: Boolean,
    val lockControlTlvs: Int,
    val memoryControlTlvs: Int,
)

/** Auswertung von Rohspeicher-Abbildern (Type 2 und Type 5). */
object TagMemory {

    /** Type 2: Der Capability Container liegt in Seite 3 (Byte 12 bis 15). */
    fun parseType2Cc(dump: ByteArray): CapabilityContainer? {
        if (dump.size < 16) return null
        return parseCc(dump, 12)
    }

    /** Type 5: Der Capability Container beginnt bei Block 0 (4 oder 8 Byte). */
    fun parseType5Cc(dump: ByteArray): CapabilityContainer? {
        if (dump.size < 4) return null
        val magic = dump[0].toInt() and 0xFF
        if (magic != 0xE1 && magic != 0xE2) return null
        val base = parseCc(dump, 0) ?: return null
        if (magic == 0xE2) {
            if (dump.size < 8) return null
            val extended = ((dump[6].toInt() and 0xFF) shl 8) or (dump[7].toInt() and 0xFF)
            val size = if (base.dataAreaBytes == 0) extended * 8 else base.dataAreaBytes
            return base.copy(dataAreaBytes = size, length = 8)
        }
        return base
    }

    private fun parseCc(data: ByteArray, offset: Int): CapabilityContainer? {
        if (offset + 4 > data.size) return null
        val magic = data[offset].toInt() and 0xFF
        if (magic != 0xE1 && magic != 0xE2) return null
        val vs = data[offset + 1].toInt() and 0xFF
        val mlen = data[offset + 2].toInt() and 0xFF
        val access = data[offset + 3].toInt() and 0xFF
        return CapabilityContainer(
            magic = magic,
            versionMajor = vs shr 4,
            versionMinor = vs and 0x0F,
            dataAreaBytes = mlen * 8,
            readAccess = access shr 4,
            writeAccess = access and 0x0F,
            length = 4,
        )
    }

    /**
     * Durchsucht den TLV-Bereich ([start] bis [end]) nach dem NDEF-Message-TLV (Tag 0x03).
     * Unterstützt NULL-TLV (0x00), Lock Control (0x01), Memory Control (0x02), Terminator (0xFE)
     * und die dreibyteige Längenkodierung (0xFF + 2 Byte).
     */
    fun scanTlvs(data: ByteArray, start: Int, end: Int = data.size): TlvScan {
        val limit = minOf(end, data.size)
        var p = start
        var ndef: ByteArray? = null
        var truncated = false
        var lockTlvs = 0
        var memTlvs = 0

        scan@ while (p < limit) {
            val tag = data[p].toInt() and 0xFF
            p++
            if (tag == 0x00) continue@scan
            if (tag == 0xFE) break@scan
            if (p >= limit) break@scan
            var length = data[p].toInt() and 0xFF
            p++
            if (length == 0xFF) {
                if (p + 1 >= limit) break@scan
                length = ((data[p].toInt() and 0xFF) shl 8) or (data[p + 1].toInt() and 0xFF)
                p += 2
            }
            val available = limit - p
            if (tag == 0x03 && ndef == null) {
                if (length <= available) {
                    ndef = data.copyOfRange(p, p + length)
                } else {
                    truncated = true
                    ndef = data.copyOfRange(p, limit)
                }
            }
            if (tag == 0x01) lockTlvs++
            if (tag == 0x02) memTlvs++
            if (length > available) break@scan
            p += length
            if (ndef != null && tag == 0x03) break@scan
        }
        return TlvScan(ndef, truncated, lockTlvs, memTlvs)
    }

    /** Beschreibt Lese-/Schreibzugriff-Nibble des Capability Containers. */
    fun describeAccess(nibble: Int): String = when (nibble) {
        0x0 -> "frei"
        0xF -> "gesperrt"
        else -> "eingeschränkt (0x%X)".format(nibble)
    }

    /**
     * Wertet die Konfigurationsseiten eines NTAG21x / Ultralight EV1 aus.
     * @param dump gelesener Speicher ab Seite 0
     * @param configPage Seite mit CFG0
     * @return Zeilen für die Anzeige oder leere Liste, wenn die Seiten nicht im Abbild liegen
     */
    fun describeNtagConfig(dump: ByteArray, configPage: Int, totalPages: Int): List<DetailRow> {
        val cfg0 = configPage * 4
        val cfg1 = cfg0 + 4
        if (cfg1 + 4 > dump.size) return emptyList()
        val auth0 = dump[cfg0 + 3].toInt() and 0xFF
        val access = dump[cfg1].toInt() and 0xFF
        val protRead = access and 0x80 != 0
        val cfgLocked = access and 0x40 != 0
        val counterEnabled = access and 0x10 != 0
        val authLimit = access and 0x07
        val rows = ArrayList<DetailRow>()
        if (auth0 >= totalPages) {
            rows.add(DetailRow("Passwortschutz", "nicht aktiv (AUTH0 = 0x%02X)".format(auth0)))
        } else {
            val scope = if (protRead) "Lesen und Schreiben" else "Schreiben"
            rows.add(DetailRow("Passwortschutz", "aktiv ab Seite 0x%02X (%s)".format(auth0, scope)))
        }
        rows.add(DetailRow("Konfiguration gesperrt", if (cfgLocked) "ja" else "nein"))
        rows.add(DetailRow("NFC-Zähler", if (counterEnabled) "aktiviert" else "deaktiviert"))
        rows.add(DetailRow("Max. Fehlversuche (AUTHLIM)", if (authLimit == 0) "unbegrenzt" else authLimit.toString()))
        return rows
    }
}
