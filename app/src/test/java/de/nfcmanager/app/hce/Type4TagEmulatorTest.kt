package de.nfcmanager.app.hce

import de.nfcmanager.app.core.Hex
import de.nfcmanager.app.core.hce.ApduEvent
import de.nfcmanager.app.core.hce.Type4Tag
import de.nfcmanager.app.core.hce.Type4TagEmulator
import de.nfcmanager.app.core.ndef.NdefCodec
import de.nfcmanager.app.core.ndef.NdefRecordData
import de.nfcmanager.app.core.ndef.NdefUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Prüft die Type-4-Tag-Zustandsmaschine mit genau dem Ablauf, den ein NFC-Lesegerät
 * (z. B. der Android-NFC-Stack oder ein iPhone) beim Lesen eines Type-4-Tags durchführt.
 */
class Type4TagEmulatorTest {

    private fun uriMessage(uri: String): ByteArray =
        NdefCodec.encode(listOf(NdefRecordData.wellKnown(NdefRecordData.RTD_URI, NdefUri.encode(uri))))

    private fun textMessage(length: Int): ByteArray {
        val text = (0 until length).joinToString("") { ((it % 26) + 'a'.code).toChar().toString() }
        val payload = byteArrayOf(2, 'd'.code.toByte(), 'e'.code.toByte()) + text.toByteArray(Charsets.UTF_8)
        return NdefCodec.encode(listOf(NdefRecordData.wellKnown(NdefRecordData.RTD_TEXT, payload)))
    }

    private fun send(tag: Type4TagEmulator, hex: String) = tag.process(Hex.decode(hex))

    private fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun readBinary(tag: Type4TagEmulator, offset: Int, len: Int): ByteArray {
        val apdu = byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), len.toByte())
        val r = tag.process(apdu)
        assertEquals("READ BINARY @$offset", Type4Tag.SW_OK, r.statusWord)
        return r.response.copyOf(r.response.size - 2)
    }

    /** Liest die Nachricht so wie ein Lesegerät: AID, CC, NDEF-Datei, NLEN, Daten in MLe-großen Stücken. */
    private fun readLikeAReader(tag: Type4TagEmulator): ByteArray {
        assertEquals(Type4Tag.SW_OK, send(tag, "00A4040007D276000085010100").statusWord)
        assertEquals(Type4Tag.SW_OK, send(tag, "00A4000C02E103").statusWord)
        val cc = readBinary(tag, 0, 15)
        assertEquals(15, u16(cc, 0))
        assertEquals(0x20, cc[2].toInt())
        val mle = u16(cc, 3)
        assertEquals(0x04, cc[7].toInt())
        assertEquals(0x06, cc[8].toInt())
        assertEquals(Type4Tag.FILE_ID_NDEF, u16(cc, 9))
        assertEquals(0x00, cc[13].toInt())           // Lesezugriff frei
        assertEquals(0xFF, cc[14].toInt() and 0xFF)  // Schreibzugriff gesperrt

        assertEquals(Type4Tag.SW_OK, send(tag, "00A4000C02E104").statusWord)
        val nlen = u16(readBinary(tag, 0, 2), 0)
        assertTrue("Maximale NDEF-Dateigröße muss NLEN + 2 abdecken", u16(cc, 11) >= nlen + 2)

        val out = ByteArrayOutputStream()
        var offset = 2
        while (out.size() < nlen) {
            val n = minOf(mle, nlen - out.size())
            out.write(readBinary(tag, offset, n))
            offset += n
        }
        return out.toByteArray()
    }

    @Test
    fun fullReadSequenceReturnsOriginalMessage() {
        val message = uriMessage("https://www.example.com/pfad?x=1")
        val tag = Type4TagEmulator { message }
        assertArrayEquals(message, readLikeAReader(tag))
    }

    @Test
    fun largeMessageIsReadInSeveralChunks() {
        val message = textMessage(1500)
        val tag = Type4TagEmulator { message }
        assertTrue(message.size > 1500)
        assertArrayEquals(message, readLikeAReader(tag))
    }

    @Test
    fun maximumSupportedMessageSizeWorks() {
        val message = ByteArray(Type4Tag.MAX_NDEF_MESSAGE_BYTES) { (it % 251).toByte() }
        val tag = Type4TagEmulator { message }
        assertEquals(Type4Tag.SW_OK, send(tag, "00A4040007D276000085010100").statusWord)
        assertEquals(Type4Tag.SW_OK, send(tag, "00A4000C02E103").statusWord)
        val cc = readBinary(tag, 0, 15)
        assertEquals(0xFFFE, u16(cc, 11))
    }

    @Test
    fun oversizedMessageIsRejected() {
        val tag = Type4TagEmulator { ByteArray(Type4Tag.MAX_NDEF_MESSAGE_BYTES + 1) }
        assertEquals(Type4Tag.SW_FILE_NOT_FOUND, send(tag, "00A4040007D276000085010100").statusWord)
    }

    @Test
    fun emptyMessageHasZeroLength() {
        val tag = Type4TagEmulator { ByteArray(0) }
        assertEquals(Type4Tag.SW_OK, send(tag, "00A4040007D276000085010100").statusWord)
        assertEquals(Type4Tag.SW_OK, send(tag, "00A4000C02E104").statusWord)
        val nlen = readBinary(tag, 0, 2)
        assertArrayEquals(byteArrayOf(0, 0), nlen)
    }

    @Test
    fun selectWithoutActiveMessageIsRejected() {
        val tag = Type4TagEmulator { null }
        val r = send(tag, "00A4040007D276000085010100")
        assertEquals(Type4Tag.SW_FILE_NOT_FOUND, r.statusWord)
        assertEquals(ApduEvent.APPLICATION_REJECTED, r.event)
        assertFalse(tag.isApplicationSelected)
    }

    @Test
    fun otherApplicationIdIsRejected() {
        val tag = Type4TagEmulator { uriMessage("https://a.de") }
        assertEquals(Type4Tag.SW_FILE_NOT_FOUND, send(tag, "00A4040007D276000085010000").statusWord)
        assertEquals(Type4Tag.SW_FILE_NOT_FOUND, send(tag, "00A404000800000000000000AA").statusWord)
    }

    @Test
    fun unknownFileIsRejected() {
        val tag = Type4TagEmulator { uriMessage("https://a.de") }
        send(tag, "00A4040007D276000085010100")
        assertEquals(Type4Tag.SW_FILE_NOT_FOUND, send(tag, "00A4000C02E105").statusWord)
        assertEquals(Type4Tag.SW_WRONG_LENGTH, send(tag, "00A4000C01E1").statusWord)
    }

    @Test
    fun fileSelectWithoutApplicationIsRejected() {
        val tag = Type4TagEmulator { uriMessage("https://a.de") }
        assertEquals(Type4Tag.SW_FILE_NOT_FOUND, send(tag, "00A4000C02E103").statusWord)
    }

    @Test
    fun readWithoutSelectedFileFails() {
        val tag = Type4TagEmulator { uriMessage("https://a.de") }
        send(tag, "00A4040007D276000085010100")
        assertEquals(Type4Tag.SW_NO_CURRENT_EF, send(tag, "00B000000F").statusWord)
    }

    @Test
    fun readBeyondEndOfFileFails() {
        val message = uriMessage("https://a.de")
        val tag = Type4TagEmulator { message }
        send(tag, "00A4040007D276000085010100")
        send(tag, "00A4000C02E104")
        val fileSize = message.size + 2
        val hi = (fileSize shr 8).toString(16).padStart(2, '0')
        val lo = (fileSize and 0xFF).toString(16).padStart(2, '0')
        assertEquals(Type4Tag.SW_WRONG_OFFSET, send(tag, "00B0$hi${lo}01").statusWord)
    }

    @Test
    fun readIsClampedToFileEnd() {
        val message = uriMessage("https://a.de")
        val tag = Type4TagEmulator { message }
        send(tag, "00A4040007D276000085010100")
        send(tag, "00A4000C02E104")
        val r = send(tag, "00B0000000") // Le = 0 bedeutet 256
        assertEquals(Type4Tag.SW_OK, r.statusWord)
        assertEquals(message.size + 2 + 2, r.response.size)
        assertEquals(ApduEvent.NDEF_READ_COMPLETE, r.event)
    }

    @Test
    fun writesAreDenied() {
        val tag = Type4TagEmulator { uriMessage("https://a.de") }
        send(tag, "00A4040007D276000085010100")
        send(tag, "00A4000C02E104")
        val r = send(tag, "00D60000020000")
        assertEquals(Type4Tag.SW_SECURITY_STATUS_NOT_SATISFIED, r.statusWord)
        assertEquals(ApduEvent.WRITE_DENIED, r.event)
    }

    @Test
    fun unsupportedInstructionAndClass() {
        val tag = Type4TagEmulator { uriMessage("https://a.de") }
        assertEquals(Type4Tag.SW_INS_NOT_SUPPORTED, send(tag, "00CA000000").statusWord)
        assertEquals(Type4Tag.SW_CLA_NOT_SUPPORTED, send(tag, "80A4040000").statusWord)
    }

    @Test
    fun malformedApdusAreAnsweredWithoutCrashing() {
        val tag = Type4TagEmulator { uriMessage("https://a.de") }
        assertEquals(Type4Tag.SW_WRONG_LENGTH, tag.process(ByteArray(0)).statusWord)
        assertEquals(Type4Tag.SW_WRONG_LENGTH, tag.process(byteArrayOf(0, 0xA4.toByte(), 4)).statusWord)
        // Lc = 7, aber nur 3 Datenbytes vorhanden
        assertEquals(Type4Tag.SW_WRONG_LENGTH, send(tag, "00A4040007D27600").statusWord)
        // READ BINARY ohne Le
        send(tag, "00A4040007D276000085010100")
        send(tag, "00A4000C02E103")
        assertEquals(Type4Tag.SW_WRONG_LENGTH, send(tag, "00B00000").statusWord)
    }

    @Test
    fun extendedLeIsSupportedForReadBinary() {
        val message = uriMessage("https://a.de")
        val tag = Type4TagEmulator { message }
        send(tag, "00A4040007D276000085010100")
        send(tag, "00A4000C02E104")
        val r = send(tag, "00B00000" + "000002")
        assertEquals(Type4Tag.SW_OK, r.statusWord)
        assertEquals(4, r.response.size)
    }

    @Test
    fun reselectingTheApplicationResetsFileSelection() {
        val tag = Type4TagEmulator { uriMessage("https://a.de") }
        send(tag, "00A4040007D276000085010100")
        send(tag, "00A4000C02E104")
        send(tag, "00A4040007D276000085010100")
        assertEquals(Type4Tag.SW_NO_CURRENT_EF, send(tag, "00B0000002").statusWord)
    }

    @Test
    fun resetAfterDeactivationForgetsEverything() {
        val tag = Type4TagEmulator { uriMessage("https://a.de") }
        send(tag, "00A4040007D276000085010100")
        send(tag, "00A4000C02E104")
        tag.reset()
        assertFalse(tag.isApplicationSelected)
        assertEquals(Type4Tag.SW_NO_CURRENT_EF, send(tag, "00B0000002").statusWord)
    }

    @Test
    fun messageIsFrozenDuringASessionAndRefreshedOnNextSelect() {
        val first = uriMessage("https://eins.de")
        val second = uriMessage("https://zwei.example.org/lang")
        var current = first
        val tag = Type4TagEmulator { current }
        send(tag, "00A4040007D276000085010100")
        current = second // Datensatz ändert sich mitten im Lesevorgang
        send(tag, "00A4000C02E104")
        val nlen = u16(readBinary(tag, 0, 2), 0)
        assertEquals(first.size, nlen)
        // Neue Sitzung liefert den neuen Inhalt
        assertArrayEquals(second, readLikeAReader(tag))
    }

    @Test
    fun capabilityContainerMatchesSpecificationLayout() {
        val cc = Type4Tag.buildCapabilityContainer(0x0123)
        assertEquals(15, cc.size)
        assertEquals("000F20003B00340406E1040123" + "00FF", Hex.encode(cc))
    }

    @Test
    fun ndefFileStartsWithBigEndianLength() {
        val file = Type4Tag.buildNdefFile(ByteArray(300) { 1 })
        assertEquals(302, file.size)
        assertEquals(0x01, file[0].toInt())
        assertEquals(0x2C, file[1].toInt())
    }

    @Test
    fun applicationIdMatchesNfcForumSpecification() {
        assertEquals("D2760000850101", Hex.encode(Type4Tag.AID))
        assertEquals(Type4Tag.AID_HEX, Hex.encode(Type4Tag.AID))
    }
}
