package de.nfcmanager.app.core.tag

/**
 * Erkennung von Chip-Typen anhand öffentlich dokumentierter Kennwerte
 * (GET_VERSION-Antwort, SAK, ATQA, UID-Herstellercode).
 * Es werden ausschließlich frei abfragbare Identifikationsdaten ausgewertet.
 */
object TagClassifier {

    /** Geometrie eines Type-2-Chips. Seitenzahlen beziehen sich auf 4-Byte-Seiten. */
    data class Type2Chip(
        val name: String,
        /** Anzahl lesbarer Seiten ab Seite 0 oder null, wenn unbekannt. */
        val totalPages: Int?,
        val userBytes: Int?,
        /** Seite mit CFG0 (AUTH0), falls der Chip die NTAG21x-Konfiguration besitzt. */
        val configPage: Int?,
    )

    /** Wertet die 8-Byte-Antwort auf GET_VERSION (0x60) aus. Null, wenn sie nicht zu einem NXP-Type-2-Chip passt. */
    fun type2FromVersion(v: ByteArray): Type2Chip? {
        if (v.size < 8) return null
        val vendor = v[1].toInt() and 0xFF
        val product = v[2].toInt() and 0xFF
        val sub = v[3].toInt() and 0xFF
        val storage = v[6].toInt() and 0xFF
        if (vendor != 0x04) return null

        fun chip(name: String, pages: Int, user: Int) = Type2Chip(name, pages, user, pages - 4)

        return when (product) {
            0x04 -> when (sub) {
                0x01 -> when (storage) {
                    0x0B -> chip("NTAG210", 20, 48)
                    0x0E -> chip("NTAG212", 41, 128)
                    else -> Type2Chip("NTAG (Speichercode 0x%02X)".format(storage), null, null, null)
                }
                0x02 -> when (storage) {
                    0x0F -> chip("NTAG213", 45, 144)
                    0x11 -> chip("NTAG215", 135, 504)
                    0x13 -> chip("NTAG216", 231, 888)
                    else -> Type2Chip("NTAG (Speichercode 0x%02X)".format(storage), null, null, null)
                }
                0x04 -> when (storage) {
                    0x0F -> chip("NTAG213 F", 45, 144)
                    0x13 -> chip("NTAG216 F", 231, 888)
                    else -> Type2Chip("NTAG F (Speichercode 0x%02X)".format(storage), null, null, null)
                }
                0x05 -> when (storage) {
                    0x13 -> Type2Chip("NTAG I2C 1k", null, 888, null)
                    0x15 -> Type2Chip("NTAG I2C 2k", null, 1912, null)
                    else -> Type2Chip("NTAG I2C", null, null, null)
                }
                else -> Type2Chip("NTAG (Untertyp 0x%02X)".format(sub), null, null, null)
            }
            0x03 -> when (storage) {
                0x0B -> chip("MIFARE Ultralight EV1 (MF0UL11)", 20, 48)
                0x0E -> chip("MIFARE Ultralight EV1 (MF0UL21)", 41, 128)
                else -> Type2Chip("MIFARE Ultralight EV1", null, null, null)
            }
            else -> null
        }
    }

    /** Beschreibung des SAK-Bytes (Select Acknowledge) von NFC-A-Tags. */
    fun describeSak(sak: Int): String = when (sak) {
        0x00 -> "NFC Forum Type 2 (NTAG, MIFARE Ultralight)"
        0x08 -> "MIFARE Classic 1K oder kompatibel"
        0x09 -> "MIFARE Classic Mini"
        0x10 -> "MIFARE Plus 2K (SL2)"
        0x11 -> "MIFARE Plus 4K (SL2)"
        0x18 -> "MIFARE Classic 4K"
        0x20 -> "ISO 14443-4 (ISO-DEP), z. B. DESFire, NTAG 424, Smartcards"
        0x28 -> "ISO 14443-4 mit MIFARE-Classic-1K-Emulation"
        0x38 -> "ISO 14443-4 mit MIFARE-Classic-4K-Emulation"
        0x88 -> "MIFARE Classic 1K (Infineon)"
        else -> "SAK 0x%02X".format(sak)
    }

    /** Herstellername anhand des ersten UID-Bytes (nur bei 7-Byte-UIDs aussagekräftig). */
    fun manufacturerName(code: Int): String? = when (code) {
        0x02 -> "STMicroelectronics"
        0x04 -> "NXP Semiconductors"
        0x05 -> "Infineon Technologies"
        0x07 -> "Texas Instruments"
        0x16 -> "EM Microelectronic-Marin"
        0x1D -> "Fujitsu"
        else -> null
    }

    /** Hinweis zur UID oder null. */
    fun uidHint(uid: ByteArray): String? {
        if (uid.size == 4 && (uid[0].toInt() and 0xFF) == 0x08) {
            return "UID beginnt mit 08: zufällige UID (typisch für Handy-Emulation oder Datenschutzmodus)"
        }
        if (uid.size == 7) {
            val name = manufacturerName(uid[0].toInt() and 0xFF)
            if (name != null) return "Hersteller laut UID: $name"
        }
        return null
    }

    fun mifareClassicName(sizeBytes: Int): String = when (sizeBytes) {
        320 -> "MIFARE Classic Mini"
        1024 -> "MIFARE Classic 1K"
        2048 -> "MIFARE Classic 2K (Plus im SL1-Modus)"
        4096 -> "MIFARE Classic 4K"
        else -> "MIFARE Classic"
    }

    /** ISO-15693-Herstellercode (UID-Byte 6 in MSB-Schreibweise). */
    fun iso15693Manufacturer(code: Int): String? = when (code) {
        0x01 -> "Motorola"
        0x02 -> "STMicroelectronics"
        0x03 -> "Hitachi"
        0x04 -> "NXP Semiconductors"
        0x05 -> "Infineon Technologies"
        0x07 -> "Texas Instruments"
        0x08 -> "Fujitsu"
        0x0E -> "Samsung"
        0x16 -> "EM Microelectronic-Marin"
        else -> null
    }
}
