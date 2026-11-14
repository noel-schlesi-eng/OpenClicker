package de.nfcmanager.app.core.ndef

/**
 * Plattformunabhängige Darstellung eines NDEF-Records (NFC Forum NDEF 1.0).
 * Entspricht inhaltlich android.nfc.NdefRecord, ist aber auf der JVM testbar.
 */
class NdefRecordData(
    val tnf: Int,
    val type: ByteArray = ByteArray(0),
    val id: ByteArray = ByteArray(0),
    val payload: ByteArray = ByteArray(0),
) {
    /** Typfeld als Text (für Well-Known, MIME, Absolute-URI und External Types). */
    val typeString: String get() = String(type, Charsets.UTF_8)

    val isEmpty: Boolean get() = tnf == TNF_EMPTY

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NdefRecordData) return false
        return tnf == other.tnf &&
            type.contentEquals(other.type) &&
            id.contentEquals(other.id) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var h = tnf
        h = 31 * h + type.contentHashCode()
        h = 31 * h + id.contentHashCode()
        h = 31 * h + payload.contentHashCode()
        return h
    }

    override fun toString(): String =
        "NdefRecordData(tnf=$tnf, type=$typeString, id=${id.size}B, payload=${payload.size}B)"

    companion object {
        const val TNF_EMPTY = 0x00
        const val TNF_WELL_KNOWN = 0x01
        const val TNF_MIME_MEDIA = 0x02
        const val TNF_ABSOLUTE_URI = 0x03
        const val TNF_EXTERNAL_TYPE = 0x04
        const val TNF_UNKNOWN = 0x05
        const val TNF_UNCHANGED = 0x06
        const val TNF_RESERVED = 0x07

        val RTD_TEXT = byteArrayOf(0x54)               // "T"
        val RTD_URI = byteArrayOf(0x55)                // "U"
        val RTD_SMART_POSTER = byteArrayOf(0x53, 0x70) // "Sp"

        fun wellKnown(type: ByteArray, payload: ByteArray) =
            NdefRecordData(TNF_WELL_KNOWN, type, ByteArray(0), payload)

        fun mime(mimeType: String, payload: ByteArray) =
            NdefRecordData(TNF_MIME_MEDIA, mimeType.toByteArray(Charsets.US_ASCII), ByteArray(0), payload)

        fun external(domainAndType: String, payload: ByteArray) =
            NdefRecordData(TNF_EXTERNAL_TYPE, domainAndType.toByteArray(Charsets.UTF_8), ByteArray(0), payload)

        fun emptyRecord() = NdefRecordData(TNF_EMPTY)
    }
}
