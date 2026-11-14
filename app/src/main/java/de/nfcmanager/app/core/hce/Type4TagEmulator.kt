package de.nfcmanager.app.core.hce

/** Art des zuletzt verarbeiteten Kommandos, für Protokoll und Statistik. */
enum class ApduEvent {
    APPLICATION_SELECTED,
    APPLICATION_REJECTED,
    FILE_SELECTED,
    FILE_REJECTED,
    CC_READ,
    NDEF_LENGTH_READ,
    NDEF_READ,
    NDEF_READ_COMPLETE,
    WRITE_DENIED,
    UNSUPPORTED,
    ERROR,
}

/** Antwort auf ein APDU-Kommando samt Ereignis und lesbarer Beschreibung. */
class ApduResult(
    val response: ByteArray,
    val event: ApduEvent,
    val description: String,
) {
    /** Die letzten zwei Bytes der Antwort als Status Word. */
    val statusWord: Int
        get() = if (response.size >= 2) {
            ((response[response.size - 2].toInt() and 0xFF) shl 8) or (response[response.size - 1].toInt() and 0xFF)
        } else {
            0
        }
}

/**
 * Zustandsmaschine für einen NFC Forum Type 4 Tag (NDEF Tag Application, Version 2.0).
 *
 * Sie beantwortet ISO-7816-4-APDUs genau so, wie es ein physischer Type-4-Tag tut:
 *
 *  1. SELECT Application (AID D2760000850101)
 *  2. SELECT File E103 (Capability Container) und READ BINARY
 *  3. SELECT File E104 (NDEF-Datei) und READ BINARY (erst NLEN, dann die Nachricht)
 *
 * Die Nachricht wird beim SELECT der Anwendung "eingefroren": Ändert sich der Datensatz
 * während eines Lesevorgangs, sieht das Lesegerät trotzdem eine konsistente Datei.
 * Schreibzugriffe werden abgelehnt (der emulierte Tag ist schreibgeschützt).
 *
 * Reines Kotlin ohne Android-Abhängigkeit, damit die Logik auf der JVM vollständig getestet wird.
 *
 * @param messageProvider liefert die aktuell bereitzustellende NDEF-Nachricht oder null,
 *        wenn gerade nichts emuliert werden soll (dann antwortet der Tag mit "Datei nicht gefunden").
 */
class Type4TagEmulator(private val messageProvider: () -> ByteArray?) {

    private enum class Selected { NONE, CC, NDEF }

    private var applicationSelected = false
    private var selected = Selected.NONE
    private var ccFile = ByteArray(0)
    private var ndefFile = ByteArray(0)

    val isApplicationSelected: Boolean get() = applicationSelected

    /** Setzt den Zustand zurück (Verbindung verloren oder andere Anwendung gewählt). */
    fun reset() {
        applicationSelected = false
        selected = Selected.NONE
        ccFile = ByteArray(0)
        ndefFile = ByteArray(0)
    }

    private class Command(val ins: Int, val p1: Int, val p2: Int, val data: ByteArray, val le: Int)

    /** Verarbeitet ein Command-APDU und liefert die Antwort. Wirft nie eine Exception. */
    fun process(apdu: ByteArray): ApduResult {
        return try {
            processInternal(apdu)
        } catch (e: RuntimeException) {
            result(Type4Tag.SW_WRONG_LENGTH, ApduEvent.ERROR, "Interner Fehler bei der Verarbeitung")
        }
    }

    private fun processInternal(apdu: ByteArray): ApduResult {
        if (apdu.size < 4) return result(Type4Tag.SW_WRONG_LENGTH, ApduEvent.ERROR, "Kommando zu kurz")
        val cla = apdu[0].toInt() and 0xFF
        if (cla != 0x00) {
            return result(Type4Tag.SW_CLA_NOT_SUPPORTED, ApduEvent.UNSUPPORTED, "Klasse %02X nicht unterstützt".format(cla))
        }
        val cmd = parse(apdu) ?: return result(Type4Tag.SW_WRONG_LENGTH, ApduEvent.ERROR, "Ungültige Kommandolänge")

        return when (cmd.ins) {
            INS_SELECT -> handleSelect(cmd)
            INS_READ_BINARY -> handleReadBinary(cmd)
            INS_UPDATE_BINARY -> result(
                Type4Tag.SW_SECURITY_STATUS_NOT_SATISFIED,
                ApduEvent.WRITE_DENIED,
                "Schreibversuch abgelehnt (Tag ist schreibgeschützt)",
            )
            else -> result(
                Type4Tag.SW_INS_NOT_SUPPORTED,
                ApduEvent.UNSUPPORTED,
                "Befehl %02X nicht unterstützt".format(cmd.ins),
            )
        }
    }

    private fun handleSelect(cmd: Command): ApduResult {
        return when (cmd.p1) {
            0x04 -> selectApplication(cmd.data)
            0x00 -> selectFile(cmd.data)
            else -> result(Type4Tag.SW_INCORRECT_P1P2, ApduEvent.ERROR, "SELECT mit unbekanntem Modus")
        }
    }

    private fun selectApplication(aid: ByteArray): ApduResult {
        selected = Selected.NONE
        if (!aid.contentEquals(Type4Tag.AID)) {
            applicationSelected = false
            return result(Type4Tag.SW_FILE_NOT_FOUND, ApduEvent.APPLICATION_REJECTED, "Unbekannte Anwendung angefragt")
        }
        val message = messageProvider()
        if (message == null || message.size > Type4Tag.MAX_NDEF_MESSAGE_BYTES) {
            applicationSelected = false
            return result(
                Type4Tag.SW_FILE_NOT_FOUND,
                ApduEvent.APPLICATION_REJECTED,
                "Anwendung angefragt, aber kein Datensatz aktiv",
            )
        }
        ndefFile = Type4Tag.buildNdefFile(message)
        ccFile = Type4Tag.buildCapabilityContainer(maxOf(ndefFile.size, 5))
        applicationSelected = true
        return result(Type4Tag.SW_OK, ApduEvent.APPLICATION_SELECTED, "NDEF-Anwendung ausgewählt (${message.size} Byte Inhalt)")
    }

    private fun selectFile(data: ByteArray): ApduResult {
        if (!applicationSelected) {
            selected = Selected.NONE
            return result(Type4Tag.SW_FILE_NOT_FOUND, ApduEvent.FILE_REJECTED, "Datei gewählt, aber keine Anwendung aktiv")
        }
        if (data.size != 2) {
            selected = Selected.NONE
            return result(Type4Tag.SW_WRONG_LENGTH, ApduEvent.FILE_REJECTED, "Dateikennung hat falsche Länge")
        }
        val fileId = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        return when (fileId) {
            Type4Tag.FILE_ID_CC -> {
                selected = Selected.CC
                result(Type4Tag.SW_OK, ApduEvent.FILE_SELECTED, "Capability-Container (E103) gewählt")
            }
            Type4Tag.FILE_ID_NDEF -> {
                selected = Selected.NDEF
                result(Type4Tag.SW_OK, ApduEvent.FILE_SELECTED, "NDEF-Datei (E104) gewählt")
            }
            else -> {
                selected = Selected.NONE
                result(Type4Tag.SW_FILE_NOT_FOUND, ApduEvent.FILE_REJECTED, "Unbekannte Datei %04X".format(fileId))
            }
        }
    }

    private fun handleReadBinary(cmd: Command): ApduResult {
        if (!applicationSelected || selected == Selected.NONE) {
            return result(Type4Tag.SW_NO_CURRENT_EF, ApduEvent.ERROR, "Lesen ohne gewählte Datei")
        }
        if (cmd.p1 and 0x80 != 0) {
            return result(Type4Tag.SW_INCORRECT_P1P2, ApduEvent.ERROR, "Kurze Datei-IDs (SFI) werden nicht unterstützt")
        }
        if (cmd.le < 0) {
            return result(Type4Tag.SW_WRONG_LENGTH, ApduEvent.ERROR, "READ BINARY ohne Le-Feld")
        }
        val offset = ((cmd.p1 and 0x7F) shl 8) or cmd.p2
        val file = if (selected == Selected.CC) ccFile else ndefFile
        if (offset >= file.size) {
            return result(Type4Tag.SW_WRONG_OFFSET, ApduEvent.ERROR, "Leseposition $offset hinter Dateiende")
        }
        val count = minOf(cmd.le, file.size - offset, MAX_READ_BYTES)
        val response = ByteArray(count + 2)
        System.arraycopy(file, offset, response, 0, count)
        response[count] = (Type4Tag.SW_OK shr 8).toByte()
        response[count + 1] = Type4Tag.SW_OK.toByte()

        val event: ApduEvent
        val text: String
        if (selected == Selected.CC) {
            event = ApduEvent.CC_READ
            text = "Capability-Container gelesen"
        } else if (offset == 0 && count <= 2 && file.size > 2) {
            event = ApduEvent.NDEF_LENGTH_READ
            text = "NDEF-Länge gelesen"
        } else if (offset + count >= file.size) {
            event = ApduEvent.NDEF_READ_COMPLETE
            text = "NDEF-Nachricht vollständig gelesen"
        } else {
            event = ApduEvent.NDEF_READ
            text = "NDEF-Daten gelesen (Position $offset, $count Byte)"
        }
        return ApduResult(response, event, text)
    }

    /** Zerlegt ein kurzes (oder Case-2E-) APDU in seine Bestandteile. Null bei ungültiger Länge. */
    private fun parse(apdu: ByteArray): Command? {
        val ins = apdu[1].toInt() and 0xFF
        val p1 = apdu[2].toInt() and 0xFF
        val p2 = apdu[3].toInt() and 0xFF
        val n = apdu.size
        if (n == 4) return Command(ins, p1, p2, ByteArray(0), -1)
        if (n == 5) return Command(ins, p1, p2, ByteArray(0), decodeLe(apdu[4].toInt() and 0xFF))

        val b4 = apdu[4].toInt() and 0xFF
        if (b4 == 0) {
            // Erweitertes Le (Case 2E): 00 LeHi LeLo. Erweiterte Lc werden nicht unterstützt.
            if (n == 7) {
                val le = ((apdu[5].toInt() and 0xFF) shl 8) or (apdu[6].toInt() and 0xFF)
                return Command(ins, p1, p2, ByteArray(0), if (le == 0) 65536 else le)
            }
            return null
        }
        val lc = b4
        if (n == 5 + lc) return Command(ins, p1, p2, apdu.copyOfRange(5, 5 + lc), -1)
        if (n == 6 + lc) {
            return Command(ins, p1, p2, apdu.copyOfRange(5, 5 + lc), decodeLe(apdu[5 + lc].toInt() and 0xFF))
        }
        return null
    }

    private fun decodeLe(b: Int): Int = if (b == 0) 256 else b

    private fun result(sw: Int, event: ApduEvent, text: String) = ApduResult(Type4Tag.sw(sw), event, text)

    private companion object {
        const val INS_SELECT = 0xA4
        const val INS_READ_BINARY = 0xB0
        const val INS_UPDATE_BINARY = 0xD6

        /** Eine kurze Antwort darf höchstens 255 Datenbytes plus Status Word enthalten. */
        const val MAX_READ_BYTES = 255
    }
}
