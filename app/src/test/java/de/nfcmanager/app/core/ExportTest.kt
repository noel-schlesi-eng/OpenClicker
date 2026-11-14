package de.nfcmanager.app.core

import de.nfcmanager.app.core.export.ExportedTag
import de.nfcmanager.app.core.export.TagExport
import de.nfcmanager.app.core.json.Json
import de.nfcmanager.app.core.json.JsonException
import de.nfcmanager.app.core.tag.DetailRow
import de.nfcmanager.app.core.tag.DetailSection
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ExportTest {
    private fun sample() = ExportedTag(
        name = "Büro \"Tür\"\n2", symbol = "🏠", colorIndex = 3, note = "Zeile1\tTab\\",
        uidHex = "04:A1:B2:C3:D4:E5:F6", tagType = "NTAG215", tagFamily = "TYPE2",
        techList = listOf("NfcA", "Ndef"), memoryBytes = 504,
        ndefMessage = byteArrayOf(0xD1.toByte(), 0x01, 0x04, 0x54, 0x02, 0x65, 0x6E, 0x41),
        ndefTypeName = "NFC Forum Type 2",
        sections = listOf(DetailSection("Allgemein", listOf(DetailRow("UID", "04:A1")))),
        rawDump = "00: 04 A1", source = "scan", createdAt = 1L, updatedAt = 2L,
    )

    @Test fun jsonRoundTrip() {
        val v = mapOf("a" to listOf(1L, 2.5, true, null, "x\u0001\"é"), "b" to emptyMap<String, Any?>())
        assertEquals(v, Json.parse(Json.write(v)))
        assertEquals(v, Json.parse(Json.write(v, pretty = true)))
    }

    @Test fun jsonRejectsGarbage() {
        for (bad in listOf("", "{", "[1,]", "{\"a\":}", "tru", "\"abc", "[1] x", "{\"a\" 1}", "\"\\x\"")) {
            try { Json.parse(bad); fail("sollte fehlschlagen: $bad") } catch (e: JsonException) { }
        }
    }

    @Test fun jsonDepthLimit() {
        val deep = "[".repeat(200) + "]".repeat(200)
        try { Json.parse(deep); fail("zu tief") } catch (e: JsonException) { }
    }

    @Test fun exportImportRoundTrip() {
        val text = TagExport.export(listOf(sample()), 5L)
        val r = TagExport.import(text)
        assertEquals(0, r.invalid)
        val t = r.tags.single()
        val s = sample()
        assertEquals(s.name, t.name); assertEquals(s.symbol, t.symbol); assertEquals(s.note, t.note)
        assertEquals(s.colorIndex, t.colorIndex); assertEquals(s.uidHex, t.uidHex)
        assertArrayEquals(s.ndefMessage, t.ndefMessage)
        assertEquals(s.sections, t.sections); assertEquals(s.techList, t.techList)
        assertEquals(504, t.memoryBytes); assertEquals(2L, t.updatedAt)
    }

    @Test fun nullNdefAndBadEntries() {
        val tag = ExportedTag("x", "", 0, "", "", "t", "UNKNOWN", emptyList(), null, null, null, emptyList(), null, "scan", 1, 1)
        val doc = TagExport.export(listOf(tag), 1)
        val r = TagExport.import(doc)
        assertNull(r.tags.single().ndefMessage)
        val bad = """{"format":"nfc-manager-export","version":1,"tags":[{"name":"a","ndefMessage":"ZZ"},5,{"name":"ok"}]}"""
        val r2 = TagExport.import(bad)
        assertEquals(2, r2.invalid)
        assertEquals("ok", r2.tags.single().name)
    }

    @Test fun importRejectsForeignAndNewer() {
        for (bad in listOf("[]", """{"format":"other","version":1,"tags":[]}""", """{"format":"nfc-manager-export","version":2,"tags":[]}""", """{"format":"nfc-manager-export","version":1}""")) {
            try { TagExport.import(bad); fail(bad) } catch (e: JsonException) { }
        }
    }

    @Test fun sectionsTolerant() {
        assertTrue(TagExport.sectionsFromJson("kaputt").isEmpty())
        assertTrue(TagExport.sectionsFromJson(null).isEmpty())
        val s = listOf(DetailSection("A", listOf(DetailRow("l", "v"))))
        assertEquals(s, TagExport.sectionsFromJson(TagExport.sectionsToJson(s)))
    }
}
