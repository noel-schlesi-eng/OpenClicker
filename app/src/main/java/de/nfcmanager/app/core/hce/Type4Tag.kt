package de.nfcmanager.app.core.hce

/**
 * Konstanten der NFC Forum Type 4 Tag Technical Specification (Version 2.0)
 * für die "NDEF Tag Application".
 */
object Type4Tag {
    /** AID der NDEF Tag Application, Mapping Version 2.0: D2 76 00 00 85 01 01. */
    val AID: ByteArray = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
    const val AID_HEX = "D2760000850101"

    /** Dateikennungen laut Spezifikation. */
    const val FILE_ID_CC = 0xE103
    const val FILE_ID_NDEF = 0xE104

    /** Im CC-File angekündigte Maximalgrößen (bewährte, konservative Standardwerte). */
    const val MLE = 0x003B
    const val MLC = 0x0034

    /** Mapping Version 2.0 */
    const val MAPPING_VERSION = 0x20

    /**
     * Größte NDEF-Nachricht, die emuliert werden kann: Die NDEF-Datei besteht aus
     * 2 Byte NLEN plus Nachricht, die maximale Dateigröße im CC ist 0xFFFE.
     */
    const val MAX_NDEF_MESSAGE_BYTES = 0xFFFE - 2

    // Status Words (ISO 7816-4)
    const val SW_OK = 0x9000
    const val SW_WRONG_LENGTH = 0x6700
    const val SW_SECURITY_STATUS_NOT_SATISFIED = 0x6982
    const val SW_NO_CURRENT_EF = 0x6986
    const val SW_FILE_NOT_FOUND = 0x6A82
    const val SW_INCORRECT_P1P2 = 0x6A86
    const val SW_WRONG_OFFSET = 0x6B00
    const val SW_INS_NOT_SUPPORTED = 0x6D00
    const val SW_CLA_NOT_SUPPORTED = 0x6E00

    /** Zwei-Byte-Antwort (Status Word) als Byte-Array. */
    fun sw(code: Int): ByteArray = byteArrayOf((code shr 8).toByte(), code.toByte())

    /** Erzeugt das 15 Byte lange Capability-Container-File. */
    fun buildCapabilityContainer(maxNdefFileSize: Int): ByteArray = byteArrayOf(
        0x00, 0x0F,                                            // CCLEN = 15
        MAPPING_VERSION.toByte(),                              // Mapping Version 2.0
        (MLE shr 8).toByte(), MLE.toByte(),                    // MLe
        (MLC shr 8).toByte(), MLC.toByte(),                    // MLc
        0x04, 0x06,                                            // NDEF File Control TLV (T=04, L=06)
        (FILE_ID_NDEF shr 8).toByte(), FILE_ID_NDEF.toByte(),  // NDEF File ID
        (maxNdefFileSize shr 8).toByte(), maxNdefFileSize.toByte(), // Maximale NDEF-Dateigröße
        0x00,                                                  // Lesezugriff: frei
        0xFF.toByte(),                                         // Schreibzugriff: gesperrt (schreibgeschützt)
    )

    /** Erzeugt die NDEF-Datei: 2 Byte NLEN (Big Endian) gefolgt von der NDEF-Nachricht. */
    fun buildNdefFile(message: ByteArray): ByteArray {
        val out = ByteArray(2 + message.size)
        out[0] = (message.size shr 8).toByte()
        out[1] = message.size.toByte()
        System.arraycopy(message, 0, out, 2, message.size)
        return out
    }
}
