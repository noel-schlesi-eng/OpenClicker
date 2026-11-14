package de.nfcmanager.app.core

import de.nfcmanager.app.core.ndef.DraftException
import de.nfcmanager.app.core.ndef.DraftKind
import de.nfcmanager.app.core.ndef.NdefCodec
import de.nfcmanager.app.core.ndef.NdefDraft
import de.nfcmanager.app.core.ndef.NdefDrafts
import de.nfcmanager.app.core.ndef.NdefRecordData
import de.nfcmanager.app.core.ndef.NdefUri
import de.nfcmanager.app.core.ndef.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NdefDraftsTest {

    private fun draft(kind: DraftKind, vararg pairs: Pair<String, String>) = NdefDraft(kind, mapOf(*pairs))

    /** Draft → Nachricht → Draft und Vergleich aller Felder, die gesetzt wurden. */
    private fun roundTrip(d: NdefDraft): NdefDraft {
        val message = NdefDrafts.toMessage(listOf(d))
        val back = NdefDrafts.fromMessage(message)
        assertEquals(1, back.size)
        assertEquals(d.kind, back[0].kind)
        return back[0]
    }

    private fun assertFails(d: NdefDraft, expectedPart: String) {
        try {
            NdefDrafts.toRecord(d)
            fail("Erwartete DraftException mit '$expectedPart'")
        } catch (e: DraftException) {
            assertTrue("Meldung '${e.message}' enthält nicht '$expectedPart'", e.message!!.contains(expectedPart))
        }
    }

    @Test
    fun urlGetsHttpsWhenSchemeIsMissing() {
        val back = roundTrip(draft(DraftKind.URL, "url" to "example.com/pfad"))
        assertEquals("https://example.com/pfad", back.get("url"))
        assertEquals("ftp://server/x", roundTrip(draft(DraftKind.URL, "url" to "ftp://server/x")).get("url"))
    }

    @Test
    fun textRoundTripKeepsUmlautsAndLanguage() {
        val back = roundTrip(draft(DraftKind.TEXT, "text" to "Grüße & Küsse\nZeile 2", "lang" to "de"))
        assertEquals("Grüße & Küsse\nZeile 2", back.get("text"))
        assertEquals("de", back.get("lang"))
    }

    @Test
    fun phoneNumberIsCleaned() {
        val back = roundTrip(draft(DraftKind.PHONE, "number" to "+49 (170) 123-4567"))
        assertEquals("+491701234567", back.get("number"))
    }

    @Test
    fun emailWithSubjectAndBody() {
        val d = draft(DraftKind.EMAIL, "address" to "max@example.com", "subject" to "Hallo Welt & Co", "body" to "Zeile 1\nZeile 2 + mehr")
        val back = roundTrip(d)
        assertEquals("max@example.com", back.get("address"))
        assertEquals("Hallo Welt & Co", back.get("subject"))
        assertEquals("Zeile 1\nZeile 2 + mehr", back.get("body"))
    }

    @Test
    fun smsRoundTrip() {
        val back = roundTrip(draft(DraftKind.SMS, "number" to "0170 99", "body" to "Test 1"))
        assertEquals("017099", back.get("number"))
        assertEquals("Test 1", back.get("body"))
    }

    @Test
    fun geoAcceptsDecimalComma() {
        val back = roundTrip(draft(DraftKind.GEO, "lat" to "52,52", "lon" to "13.405"))
        assertEquals("52.52", back.get("lat"))
        assertEquals("13.405", back.get("lon"))
    }

    @Test
    fun contactRoundTripKeepsAllFields() {
        val d = draft(
            DraftKind.CONTACT,
            "given" to "Max", "family" to "Müller", "org" to "Muster, GmbH; Co", "title" to "Leiter",
            "phone" to "+49 170 1234567", "email" to "max@example.com", "url" to "https://example.com",
            "street" to "Hauptstr. 1", "postal" to "10115", "city" to "Berlin", "country" to "Deutschland",
            "note" to "Zeile 1\nZeile 2",
        )
        val back = roundTrip(d)
        for (key in listOf("given", "family", "org", "title", "phone", "email", "url", "street", "postal", "city", "country", "note")) {
            assertEquals("Feld $key", d.get(key), back.get(key))
        }
    }

    @Test
    fun wifiRoundTrip() {
        val back = roundTrip(draft(DraftKind.WIFI, "ssid" to "Café WLAN", "password" to "sehr-geheim-123", "security" to WifiSecurity.WPA_WPA2_PSK.name))
        assertEquals("Café WLAN", back.get("ssid"))
        assertEquals("sehr-geheim-123", back.get("password"))
        assertEquals(WifiSecurity.WPA_WPA2_PSK.name, back.get("security"))

        val open = roundTrip(draft(DraftKind.WIFI, "ssid" to "Gast", "security" to WifiSecurity.OPEN.name))
        assertEquals(WifiSecurity.OPEN.name, open.get("security"))
    }

    @Test
    fun mimeTextAndRawRoundTrip() {
        val mime = roundTrip(draft(DraftKind.MIME_TEXT, "mime" to "application/json", "content" to "{\"x\": [1, 2]}"))
        assertEquals("application/json", mime.get("mime"))
        assertEquals("{\"x\": [1, 2]}", mime.get("content"))

        val raw = draft(DraftKind.RAW, "tnf" to "4", "type" to "example.com:t", "id" to "0A0B", "payload" to "01 02 FF")
        val record = NdefDrafts.toRecord(raw)
        assertEquals(NdefRecordData.TNF_EXTERNAL_TYPE, record.tnf)
        assertEquals("0102FF", Hex.encode(record.payload))
        assertEquals("0A0B", Hex.encode(record.id))
        val back = NdefDrafts.fromRecord(record)
        // Externe Typen mit Binärdaten bleiben Rohdaten
        assertEquals(DraftKind.RAW, back.kind)
        assertEquals(record, NdefDrafts.toRecord(back))
    }

    @Test
    fun unsupportedRecordsSurviveAsRawWithoutLoss() {
        val inner = NdefCodec.encode(listOf(NdefRecordData.wellKnown(NdefRecordData.RTD_URI, NdefUri.encode("https://a.de"))))
        val smartPoster = NdefRecordData.wellKnown(NdefRecordData.RTD_SMART_POSTER, inner)
        val d = NdefDrafts.fromRecord(smartPoster)
        assertEquals(DraftKind.RAW, d.kind)
        assertEquals(smartPoster, NdefDrafts.toRecord(d))
    }

    @Test
    fun complexVCardIsKeptAsTextWithoutLoss() {
        val vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:A B\r\nTEL:1\r\nTEL:2\r\nPHOTO;ENCODING=b:AAAA\r\nEND:VCARD"
        val record = NdefRecordData.mime("text/vcard", vcard.toByteArray(Charsets.UTF_8))
        val d = NdefDrafts.fromRecord(record)
        assertEquals(DraftKind.MIME_TEXT, d.kind)
        assertEquals(record, NdefDrafts.toRecord(d))
    }

    @Test
    fun enterpriseWifiIsKeptAsRaw() {
        val payload = de.nfcmanager.app.core.ndef.WifiConfig.build("Firma", "x", WifiSecurity.WPA2_PSK)
        // Auth-Typ von WPA2-Personal (0020) auf WPA2-Enterprise (0010) ändern
        val hex = Hex.encode(payload).replace("100300020020", "100300020010")
        val record = NdefRecordData.mime("application/vnd.wfa.wsc", Hex.decode(hex))
        assertEquals(DraftKind.RAW, NdefDrafts.fromRecord(record).kind)
    }

    @Test
    fun severalDraftsFormOneMessage() {
        val bytes = NdefDrafts.toMessage(
            listOf(
                draft(DraftKind.URL, "url" to "https://a.de"),
                draft(DraftKind.TEXT, "text" to "Hallo"),
            ),
        )
        val drafts = NdefDrafts.fromMessage(bytes)
        assertEquals(listOf(DraftKind.URL, DraftKind.TEXT), drafts.map { it.kind })
    }

    @Test
    fun emptyOrInvalidMessagesGiveNoDrafts() {
        assertTrue(NdefDrafts.fromMessage(null).isEmpty())
        assertTrue(NdefDrafts.fromMessage(ByteArray(0)).isEmpty())
        assertTrue(NdefDrafts.fromMessage(Hex.decode("D00000")).isEmpty())
        assertTrue(NdefDrafts.fromMessage(byteArrayOf(1, 2, 3)).isEmpty())
    }

    @Test
    fun validationMessagesAreGerman() {
        assertFails(draft(DraftKind.URL, "url" to "  "), "Adresse")
        assertFails(draft(DraftKind.TEXT, "text" to ""), "Text")
        assertFails(draft(DraftKind.PHONE, "number" to "abc"), "Telefonnummer")
        assertFails(draft(DraftKind.EMAIL, "address" to "keine-mail"), "E-Mail")
        assertFails(draft(DraftKind.GEO, "lat" to "91", "lon" to "0"), "Koordinaten")
        assertFails(draft(DraftKind.GEO, "lat" to "x", "lon" to "0"), "Koordinaten")
        assertFails(draft(DraftKind.WIFI, "ssid" to "", "password" to "12345678"), "Netzwerkname")
        assertFails(draft(DraftKind.WIFI, "ssid" to "A", "password" to "kurz", "security" to WifiSecurity.WPA2_PSK.name), "8 Zeichen")
        assertFails(draft(DraftKind.CONTACT), "Namen")
        assertFails(draft(DraftKind.MIME_TEXT, "mime" to "kein-slash", "content" to "x"), "MIME")
        assertFails(draft(DraftKind.RAW, "tnf" to "9"), "TNF")
        assertFails(draft(DraftKind.RAW, "tnf" to "2", "type" to "a/b", "payload" to "XYZ"), "Hex")
        assertFails(draft(DraftKind.RAW, "tnf" to "2", "type" to "", "payload" to "00"), "Typ")
        try {
            NdefDrafts.toMessage(emptyList())
            fail("Erwartete DraftException")
        } catch (e: DraftException) {
            assertTrue(e.message!!.contains("mindestens einen Eintrag"))
        }
    }

    @Test
    fun oversizedMessageIsRejected() {
        val big = "x".repeat(70000)
        try {
            NdefDrafts.toMessage(listOf(draft(DraftKind.MIME_TEXT, "mime" to "text/plain", "content" to big)))
            fail("Erwartete DraftException")
        } catch (e: DraftException) {
            assertTrue(e.message!!.contains("zu groß"))
        }
    }

    @Test
    fun defaultsAreFilledByEmptyDraft() {
        val d = NdefDraft.empty(DraftKind.WIFI)
        assertEquals(WifiSecurity.WPA2_PSK.name, d.get("security"))
        assertEquals("de", NdefDraft.empty(DraftKind.TEXT).get("lang"))
        assertEquals("text/plain", NdefDraft.empty(DraftKind.MIME_TEXT).get("mime"))
    }
}
