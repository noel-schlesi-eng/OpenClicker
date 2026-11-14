package de.nfcmanager.app.core.tag

import de.nfcmanager.app.core.Hex

/** Grobe Tag-Familie; bestimmt u. a., ob und wie ein Tag emuliert werden kann. */
enum class TagFamily(val label: String) {
    TYPE1("NFC Forum Type 1 (Topaz/Jewel)"),
    TYPE2("NFC Forum Type 2 (NTAG / MIFARE Ultralight)"),
    TYPE3("NFC Forum Type 3 (FeliCa)"),
    TYPE4("NFC Forum Type 4 (ISO-DEP)"),
    TYPE5("NFC Forum Type 5 / ISO 15693 (NFC-V)"),
    MIFARE_CLASSIC("MIFARE Classic"),
    ISO_DEP("ISO-DEP (ISO 14443-4)"),
    NFC_B("NFC-B (ISO 14443-3B)"),
    NFC_F("NFC-F (FeliCa)"),
    NFC_A("NFC-A (ISO 14443-3A)"),
    UNKNOWN("Unbekannt");

    companion object {
        fun fromName(name: String?): TagFamily = values().firstOrNull { it.name == name } ?: UNKNOWN
    }
}

data class DetailRow(val label: String, val value: String)

/** Eine Gruppe zusammengehöriger Detailwerte, z. B. "NFC-A" oder "Speicher". */
data class DetailSection(val title: String, val rows: List<DetailRow>)

/**
 * Alles, was beim Auslesen eines Tags ermittelt wurde.
 * (Bewusst keine data class, weil sie Byte-Arrays enthält.)
 */
class TagSnapshot(
    val uid: ByteArray,
    val techList: List<String>,
    val tagType: String,
    val family: TagFamily,
    /** Gesamter Nutzspeicher in Byte, falls bekannt. */
    val memoryBytes: Int?,
    /** Rohe NDEF-Nachricht (null, wenn kein NDEF-Inhalt vorhanden ist). */
    val ndefMessage: ByteArray?,
    val ndefTypeName: String?,
    /** Maximale NDEF-Größe laut Tag, falls bekannt. */
    val ndefCapacity: Int?,
    val ndefWritable: Boolean?,
    val sections: List<DetailSection>,
    /** Hexdump des gelesenen Speichers (nur öffentlich lesbare Bereiche), falls vorhanden. */
    val rawDump: String?,
    /** Hinweise und Warnungen zum Lesevorgang. */
    val notes: List<String>,
    val scannedAt: Long,
) {
    val uidHex: String get() = Hex.encode(uid, ":")
    val hasNdef: Boolean get() = ndefMessage != null && ndefMessage.isNotEmpty()
}
