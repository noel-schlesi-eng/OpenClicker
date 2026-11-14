package de.nfcmanager.app.core

import de.nfcmanager.app.core.ndef.NdefCodec
import de.nfcmanager.app.core.ndef.NdefFormatException
import de.nfcmanager.app.core.ndef.NdefRecordData
import de.nfcmanager.app.core.ndef.NdefUri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NdefCodecTest {

    @Test
    fun parsesKnownUriRecord() {
        // D1 01 0C 55 02 "example.com"  ->  https://www.example.com
        val bytes = Hex.decode("D1010C5502" + Hex.encode("example.com".toByteArray()))
        val records = NdefCodec.parse(bytes)
        assertEquals(1, records.size)
        assertEquals(NdefRecordData.TNF_WELL_KNOWN, records[0].tnf)
        assertEquals("U", records[0].typeString)
        assertEquals("https://www.example.com", NdefUri.decode(records[0].payload))
    }

    @Test
    fun encodesKnownTextRecordExactly() {
        val payload = byteArrayOf(0x02, 'd'.code.toByte(), 'e'.code.toByte()) + "Hallo".toByteArray()
        val bytes = NdefCodec.encode(listOf(NdefRecordData.wellKnown(NdefRecordData.RTD_TEXT, payload)))
        assertEquals("D10108540264 6548616C6C6F".replace(" ", ""), Hex.encode(bytes))
    }

    @Test
    fun roundTripWithSeveralRecordsSetsMessageFlags() {
        val records = listOf(
            NdefRecordData.wellKnown(NdefRecordData.RTD_URI, NdefUri.encode("https://a.de")),
            NdefRecordData.mime("text/plain", "abc".toByteArray()),
            NdefRecordData.external("example.com:x", byteArrayOf(1, 2, 3)),
        )
        val bytes = NdefCodec.encode(records)
        assertEquals(0x80, bytes[0].toInt() and 0xC0) // MB gesetzt, ME nicht
        assertEquals(records, NdefCodec.parse(bytes))
    }

    @Test
    fun longPayloadUsesFourByteLength() {
        val payload = ByteArray(1000) { (it % 200).toByte() }
        val record = NdefRecordData.mime("application/octet-stream", payload)
        val bytes = NdefCodec.encode(listOf(record))
        assertEquals(0, bytes[0].toInt() and 0x10) // SR-Flag nicht gesetzt
        val parsed = NdefCodec.parse(bytes)
        assertArrayEquals(payload, parsed[0].payload)
    }

    @Test
    fun recordIdIsPreserved() {
        val record = NdefRecordData(NdefRecordData.TNF_MIME_MEDIA, "a/b".toByteArray(), byteArrayOf(9, 8), byteArrayOf(7))
        val parsed = NdefCodec.parse(NdefCodec.encode(listOf(record)))
        assertArrayEquals(byteArrayOf(9, 8), parsed[0].id)
    }

    @Test
    fun parsesChunkedRecords() {
        // Drei Chunks eines MIME-Records "a/b": "AB", "CD", "E"
        val bytes = Hex.decode(
            "B2" + "03" + "02" + Hex.encode("a/b".toByteArray()) + "4142" +   // MB, CF, SR, TNF=2
                "36" + "00" + "02" + "4344" +                                 // CF, SR, TNF=6 (Unchanged)
                "56" + "00" + "01" + "45",                                    // ME, SR, TNF=6, letzter Chunk
        )
        val parsed = NdefCodec.parse(bytes)
        assertEquals(1, parsed.size)
        assertEquals("a/b", parsed[0].typeString)
        assertEquals("ABCDE", String(parsed[0].payload))
    }

    @Test
    fun rejectsInvalidData() {
        val invalid = listOf(
            "",                         // leer
            "D1",                       // Header abgeschnitten
            "51010C55",                 // erster Record ohne MB
            "D10155",                   // Payload-Länge fehlt
            "D1010555" + "0102",        // Länge größer als Daten
            "C100FFFFFFFF",             // 4-Byte-Länge 4 GiB, keine Daten
            "D60100",                   // TNF Unchanged im ersten Record
            "B1010155" + "00",          // Chunk ohne Fortsetzung (CF gesetzt, Ende)
        )
        for (hex in invalid) {
            val data = Hex.decode(hex)
            try {
                NdefCodec.parse(data)
                fail("Erwartete NdefFormatException für '$hex'")
            } catch (e: NdefFormatException) {
                // erwartet
            }
            assertNull(NdefCodec.tryParse(data))
        }
    }

    @Test
    fun secondMessageBeginFlagIsRejected() {
        val a = Hex.decode("9101015500") // MB, kein ME
        val b = Hex.decode("D1010155 00".replace(" ", "")) // MB erneut
        try {
            NdefCodec.parse(a + b)
            fail("Erwartete NdefFormatException")
        } catch (e: NdefFormatException) {
            // erwartet
        }
    }

    @Test
    fun trailingBytesAfterMessageEndAreIgnored() {
        val msg = Hex.decode("D1010C5502" + Hex.encode("example.com".toByteArray()))
        val parsed = NdefCodec.parse(msg + byteArrayOf(0, 0, 0))
        assertEquals(1, parsed.size)
    }

    @Test
    fun detectsEmptyMessage() {
        assertTrue(NdefCodec.isEffectivelyEmpty(NdefCodec.parse(Hex.decode("D00000"))))
        assertFalse(NdefCodec.isEffectivelyEmpty(NdefCodec.parse(Hex.decode("D1010C5502" + Hex.encode("example.com".toByteArray())))))
    }

    @Test
    fun uriPrefixSelectionUsesLongestPrefix() {
        assertEquals(0x02, NdefUri.encode("https://www.example.com")[0].toInt())
        assertEquals(0x04, NdefUri.encode("https://example.com")[0].toInt())
        assertEquals(0x05, NdefUri.encode("tel:+491701234567")[0].toInt())
        assertEquals(0x1E, NdefUri.encode("urn:epc:id:sgtin:1")[0].toInt())
        assertEquals(0x00, NdefUri.encode("custom-scheme://x")[0].toInt())
        assertEquals("custom-scheme://x", NdefUri.decode(NdefUri.encode("custom-scheme://x")))
        for (uri in listOf("https://www.a.de", "http://b.de", "mailto:x@y.z", "smb://srv/share", "urn:nfc:ext:a:b")) {
            assertEquals(uri, NdefUri.decode(NdefUri.encode(uri)))
        }
    }

    @Test
    fun hexHelpersAreTolerant() {
        assertArrayEquals(byteArrayOf(0x04, 0xA1.toByte(), 0xFF.toByte()), Hex.decode("04:a1-ff"))
        assertArrayEquals(byteArrayOf(0x04, 0xA1.toByte()), Hex.decode("0x04 0xA1"))
        assertNull(Hex.tryDecode("ABC"))
        assertNull(Hex.tryDecode("ZZ"))
        assertEquals("04 A1", Hex.encode(byteArrayOf(4, 0xA1.toByte()), " "))
        assertTrue(Hex.dump(ByteArray(20) { it.toByte() }).contains("0010"))
    }
}
