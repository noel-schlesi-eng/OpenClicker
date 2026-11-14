package de.nfcmanager.app.core

import de.nfcmanager.app.core.ndef.DecodedRecord
import de.nfcmanager.app.core.ndef.NdefCodec
import de.nfcmanager.app.core.ndef.NdefDecoder
import de.nfcmanager.app.core.ndef.NdefRecordData
import de.nfcmanager.app.core.ndef.NdefUri
import de.nfcmanager.app.core.ndef.RecordKind
import de.nfcmanager.app.core.ndef.WifiConfig
import de.nfcmanager.app.core.ndef.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdefDecoderTest {

    private fun uri(u: String) = NdefRecordData.wellKnown(NdefRecordData.RTD_URI, NdefUri.encode(u))

    private fun text(t: String, lang: String = "de"): NdefRecordData {
        val l = lang.toByteArray(Charsets.US_ASCII)
        return NdefRecordData.wellKnown(NdefRecordData.RTD_TEXT, byteArrayOf(l.size.toByte()) + l + t.toByteArray(Charsets.UTF_8))
    }

    private fun field(r: DecodedRecord, label: String) =
        r.fields.firstOrNull { it.label == label }?.value

    @Test
    fun decodesWebUrl() {
        val r = NdefDecoder.decode(uri("https://www.example.com/a?b=1"))
        assertEquals(RecordKind.URI, r.kind)
        assertEquals("Webadresse", r.title)
        assertEquals("https://www.example.com/a?b=1", r.summary)
        assertEquals("https://www.example.com/a?b=1", r.openUri)
        assertEquals("www.example.com", field(r, "Host"))
    }

    @Test
    fun decodesPhoneMailAndGeoUris() {
        assertEquals("Telefonnummer", NdefDecoder.decode(uri("tel:+491701234567")).title)
        assertEquals("+491701234567", NdefDecoder.decode(uri("tel:+491701234567")).summary)
        val mail = NdefDecoder.decode(uri("mailto:max@example.com?subject=Hi"))
        assertEquals("E-Mail", mail.title)
        assertEquals("max@example.com", mail.summary)
        assertEquals("Standort", NdefDecoder.decode(uri("geo:52.52,13.405")).title)
    }

    @Test
    fun doesNotOfferToOpenUnsafeSchemes() {
        assertNull(NdefDecoder.decode(uri("file:///sdcard/x")).openUri)
        assertNull(NdefDecoder.decode(uri("smb://server/share")).openUri)
        assertNull(NdefDecoder.decode(uri("intent://x#Intent;end")).openUri)
    }

    @Test
    fun decodesUtf8AndUtf16Text() {
        val r = NdefDecoder.decode(text("Grüße aus Köln"))
        assertEquals("Text", r.title)
        assertEquals("Grüße aus Köln", field(r, "Text"))
        assertEquals("de", field(r, "Sprache"))
        assertEquals("UTF-8", field(r, "Kodierung"))

        val utf16 = "Hi".toByteArray(Charsets.UTF_16) // mit BOM
        val payload = byteArrayOf(0x82.toByte(), 'e'.code.toByte(), 'n'.code.toByte()) + utf16
        val r16 = NdefDecoder.decode(NdefRecordData.wellKnown(NdefRecordData.RTD_TEXT, payload))
        assertEquals("Hi", field(r16, "Text"))
        assertEquals("UTF-16", field(r16, "Kodierung"))
    }

    @Test
    fun brokenTextRecordDoesNotCrash() {
        val r = NdefDecoder.decode(NdefRecordData.wellKnown(NdefRecordData.RTD_TEXT, byteArrayOf(0x05, 'd'.code.toByte())))
        assertEquals(RecordKind.TEXT, r.kind)
        assertTrue(r.title.contains("beschädigt"))
    }

    @Test
    fun decodesSmartPoster() {
        val inner = NdefCodec.encode(
            listOf(
                text("Mein Titel"),
                uri("https://beispiel.de"),
                NdefRecordData.wellKnown("act".toByteArray(), byteArrayOf(0)),
            ),
        )
        val r = NdefDecoder.decode(NdefRecordData.wellKnown(NdefRecordData.RTD_SMART_POSTER, inner))
        assertEquals(RecordKind.SMART_POSTER, r.kind)
        assertEquals("Mein Titel – https://beispiel.de", r.summary)
        assertEquals("Mein Titel", field(r, "Titel"))
        assertEquals("Ausführen", field(r, "Empfohlene Aktion"))
        assertEquals("https://beispiel.de", r.openUri)
        assertEquals(2, r.children.size)
    }

    @Test
    fun decodesVCard30() {
        val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Mustermann;Max;;;\r\nFN:Max Mustermann\r\nORG:Muster GmbH\r\n" +
            "TITLE:Geschäftsführer\r\nTEL;TYPE=CELL:+49 170 1234567\r\nEMAIL;TYPE=INTERNET:max@example.com\r\n" +
            "URL:https://example.com\r\nADR;TYPE=WORK:;;Hauptstr. 1;Berlin;;10115;Deutschland\r\nNOTE:Hallo\\, Welt\r\nEND:VCARD"
        val r = NdefDecoder.decode(NdefRecordData.mime("text/vcard", vcard.toByteArray(Charsets.UTF_8)))
        assertEquals(RecordKind.CONTACT, r.kind)
        assertEquals("Max Mustermann", r.summary)
        assertEquals("Max Mustermann", field(r, "Name"))
        assertEquals("Muster GmbH", field(r, "Organisation"))
        assertEquals("+49 170 1234567", field(r, "Telefon (Mobil)"))
        assertEquals("max@example.com", field(r, "E-Mail"))
        assertEquals("Hauptstr. 1, 10115 Berlin, Deutschland", field(r, "Adresse"))
        assertEquals("Hallo, Welt", field(r, "Notiz"))
        assertNotNull(r.contact)
    }

    @Test
    fun decodesVCard21WithQuotedPrintableAndFolding() {
        val vcard = "BEGIN:VCARD\nVERSION:2.1\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller;Hans;;;\n" +
            "FN:Hans M\n ueller\nTEL;CELL:0170 111\nEND:VCARD"
        val r = NdefDecoder.decode(NdefRecordData.mime("text/x-vCard", vcard.toByteArray(Charsets.UTF_8)))
        assertEquals(RecordKind.CONTACT, r.kind)
        assertEquals("Hans Mueller", field(r, "Name"))
        val card = r.contact!!
        assertEquals("Müller", card.familyName)
        assertEquals("Hans", card.givenName)
        assertEquals("0170 111", card.phones[0].value)
    }

    @Test
    fun invalidVCardFallsBackToGenericMime() {
        val r = NdefDecoder.decode(NdefRecordData.mime("text/vcard", "kein vcard".toByteArray()))
        assertEquals(RecordKind.MIME, r.kind)
    }

    @Test
    fun decodesWifiCredentialsAndMarksPasswordAsSensitive() {
        val payload = WifiConfig.build("Heimnetz", "geheim1234", WifiSecurity.WPA2_PSK)
        val r = NdefDecoder.decode(NdefRecordData.mime(WifiConfig.MIME_TYPE, payload))
        assertEquals(RecordKind.WIFI, r.kind)
        assertEquals("Heimnetz", r.summary)
        assertEquals("WPA2-Personal (AES)", field(r, "Sicherheit"))
        val pw = r.fields.first { it.label == "Passwort" }
        assertEquals("geheim1234", pw.value)
        assertTrue(pw.sensitive)
    }

    @Test
    fun decodesOpenWifiWithoutPasswordField() {
        val payload = WifiConfig.build("Gast", "", WifiSecurity.OPEN)
        val r = NdefDecoder.decode(NdefRecordData.mime(WifiConfig.MIME_TYPE, payload))
        assertEquals("Offen", field(r, "Sicherheit"))
        assertNull(field(r, "Passwort"))
    }

    @Test
    fun decodesBluetoothHandover() {
        val payload = Hex.decode("0F00" + "FFEEDDCCBBAA" + "06095465737431")
        val r = NdefDecoder.decode(NdefRecordData.mime("application/vnd.bluetooth.ep.oob", payload))
        assertEquals(RecordKind.BLUETOOTH, r.kind)
        assertEquals("Test1", field(r, "Gerätename"))
        assertEquals("AA:BB:CC:DD:EE:FF", field(r, "Bluetooth-Adresse"))
    }

    @Test
    fun decodesAndroidApplicationRecord() {
        val r = NdefDecoder.decode(NdefRecordData.external("android.com:pkg", "de.example.app".toByteArray()))
        assertEquals(RecordKind.ANDROID_APP, r.kind)
        assertEquals("de.example.app", r.summary)
        assertEquals("https://play.google.com/store/apps/details?id=de.example.app", r.openUri)
    }

    @Test
    fun decodesGenericMimeTextAndBinary() {
        val json = NdefDecoder.decode(NdefRecordData.mime("application/json", "{\"a\":1}".toByteArray()))
        assertEquals(RecordKind.MIME, json.kind)
        assertEquals("{\"a\":1}", field(json, "Inhalt"))

        val bin = NdefDecoder.decode(NdefRecordData.mime("application/octet-stream", byteArrayOf(0, 1, 2, 0xFF.toByte())))
        assertNull(field(bin, "Inhalt"))
        assertEquals("00 01 02 FF", field(bin, "Inhalt (Hex)"))
    }

    @Test
    fun decodesAbsoluteUriExternalAndEmptyRecords() {
        val abs = NdefDecoder.decode(NdefRecordData(NdefRecordData.TNF_ABSOLUTE_URI, "https://example.com/x".toByteArray()))
        assertEquals(RecordKind.ABSOLUTE_URI, abs.kind)
        assertEquals("https://example.com/x", abs.openUri)

        val ext = NdefDecoder.decode(NdefRecordData.external("example.com:thing", byteArrayOf(1)))
        assertEquals(RecordKind.EXTERNAL, ext.kind)

        assertEquals(RecordKind.EMPTY, NdefDecoder.decode(NdefRecordData.emptyRecord()).kind)
        assertEquals(RecordKind.UNKNOWN, NdefDecoder.decode(NdefRecordData(NdefRecordData.TNF_UNKNOWN, ByteArray(0), ByteArray(0), byteArrayOf(1))).kind)
    }

    @Test
    fun summarizesWholeMessages() {
        assertEquals("Kein NDEF-Inhalt", NdefDecoder.summarize(null))
        assertEquals("Ungültige NDEF-Daten", NdefDecoder.summarize(byteArrayOf(1, 2, 3)))
        assertEquals("Webadresse: https://a.de", NdefDecoder.summarize(NdefCodec.encode(listOf(uri("https://a.de")))))
        val two = NdefCodec.encode(listOf(uri("https://a.de"), text("x")))
        assertEquals("Webadresse: https://a.de (+1 weitere)", NdefDecoder.summarize(two))
        assertEquals("Leerer Eintrag", NdefDecoder.summarize(Hex.decode("D00000")))
    }

    @Test
    fun decodeMessageReportsErrorsInGerman() {
        val m = NdefDecoder.decodeMessage(byteArrayOf(0x51, 0, 0))
        assertTrue(m.records.isEmpty())
        assertNotNull(m.error)
        assertTrue(m.error!!.startsWith("Die NDEF-Daten sind ungültig"))
    }
}
