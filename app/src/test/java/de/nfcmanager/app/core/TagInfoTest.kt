package de.nfcmanager.app.core

import de.nfcmanager.app.core.hce.Type4Tag
import de.nfcmanager.app.core.tag.EmulationAssessor
import de.nfcmanager.app.core.tag.TagClassifier
import de.nfcmanager.app.core.tag.TagFamily
import de.nfcmanager.app.core.tag.TagMemory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagInfoTest {

    private val ndef = Hex.decode("D1010C5502" + Hex.encode("example.com".toByteArray()))

    /** Baut ein NTAG213-Abbild (45 Seiten) mit CC, NDEF-TLV und Konfigurationsseiten. */
    private fun ntag213Dump(auth0: Int = 0xFF, access: Int = 0x00): ByteArray {
        val dump = ByteArray(45 * 4)
        // Seite 0-2: UID, BCC, interne Bytes, Lock-Bytes
        val head = Hex.decode("04A1B2" + "C3D4E5F6" + "00" + "48000000")
        System.arraycopy(head, 0, dump, 0, minOf(head.size, 12))
        // Seite 3: Capability Container E1 10 12 00 (144 Byte)
        System.arraycopy(Hex.decode("E1101200"), 0, dump, 12, 4)
        // ab Seite 4: NDEF-TLV
        val tlv = byteArrayOf(0x03, ndef.size.toByte()) + ndef + byteArrayOf(0xFE.toByte())
        System.arraycopy(tlv, 0, dump, 16, tlv.size)
        // Seite 0x29: CFG0 (AUTH0 in Byte 3), Seite 0x2A: CFG1 (ACCESS in Byte 0)
        dump[0x29 * 4 + 3] = auth0.toByte()
        dump[0x2A * 4] = access.toByte()
        return dump
    }

    // ---- Speicher / TLV -----------------------------------------------------------------------

    @Test
    fun parsesType2CapabilityContainerAndNdefTlv() {
        val dump = ntag213Dump()
        val cc = TagMemory.parseType2Cc(dump)
        assertNotNull(cc)
        assertEquals(0xE1, cc!!.magic)
        assertEquals("1.0", cc.versionText)
        assertEquals(144, cc.dataAreaBytes)
        assertEquals(0, cc.readAccess)
        assertArrayEquals(ndef, TagMemory.scanTlvs(dump, 16, 16 + cc.dataAreaBytes).ndef)
    }

    @Test
    fun rejectsMissingCapabilityContainer() {
        assertNull(TagMemory.parseType2Cc(ByteArray(16)))
        assertNull(TagMemory.parseType2Cc(ByteArray(4)))
    }

    @Test
    fun parsesLongFormTlvLength() {
        val message = ByteArray(300) { (it % 7).toByte() }
        val data = byteArrayOf(0x03, 0xFF.toByte(), 0x01, 0x2C) + message + byteArrayOf(0xFE.toByte())
        val scan = TagMemory.scanTlvs(data, 0)
        assertArrayEquals(message, scan.ndef)
        assertFalse(scan.truncated)
    }

    @Test
    fun skipsNullLockAndMemoryControlTlvs() {
        val data = Hex.decode("0000" + "0103A01044" + "0203F03C33" + "00" + "030A" + "0102030405060708090A" + "FE")
        val scan = TagMemory.scanTlvs(data, 0)
        assertEquals(1, scan.lockControlTlvs)
        assertEquals(1, scan.memoryControlTlvs)
        assertArrayEquals(Hex.decode("0102030405060708090A"), scan.ndef)
    }

    @Test
    fun detectsTruncatedTlvAndEmptyTlv() {
        val truncated = TagMemory.scanTlvs(Hex.decode("0332" + "0102030405"), 0)
        assertTrue(truncated.truncated)
        assertEquals(5, truncated.ndef!!.size)

        val empty = TagMemory.scanTlvs(Hex.decode("0300FE"), 0)
        assertEquals(0, empty.ndef!!.size)

        assertNull(TagMemory.scanTlvs(Hex.decode("FE"), 0).ndef)
        assertNull(TagMemory.scanTlvs(ByteArray(8), 0).ndef)
    }

    @Test
    fun parsesType5CapabilityContainer() {
        val cc4 = TagMemory.parseType5Cc(Hex.decode("E1400E01"))!!
        assertEquals(112, cc4.dataAreaBytes)
        assertEquals(4, cc4.length)
        val cc8 = TagMemory.parseType5Cc(Hex.decode("E2400001" + "00000200"))!!
        assertEquals(512 * 8, cc8.dataAreaBytes)
        assertEquals(8, cc8.length)
        assertNull(TagMemory.parseType5Cc(Hex.decode("00000000")))
    }

    @Test
    fun describesNtagPasswordProtection() {
        val open = TagMemory.describeNtagConfig(ntag213Dump(), 0x29, 45)
        assertTrue(open[0].value.startsWith("nicht aktiv"))

        val protectedWrite = TagMemory.describeNtagConfig(ntag213Dump(auth0 = 0x10, access = 0x00), 0x29, 45)
        assertEquals("aktiv ab Seite 0x10 (Schreiben)", protectedWrite[0].value)

        val protectedRead = TagMemory.describeNtagConfig(ntag213Dump(auth0 = 0x04, access = 0x85), 0x29, 45)
        assertEquals("aktiv ab Seite 0x04 (Lesen und Schreiben)", protectedRead[0].value)
        assertEquals("5", protectedRead.first { it.label.startsWith("Max. Fehlversuche") }.value)

        assertTrue(TagMemory.describeNtagConfig(ByteArray(20), 0x29, 45).isEmpty())
    }

    // ---- Chip-Erkennung -------------------------------------------------------------------------

    @Test
    fun identifiesNtagVariantsFromGetVersion() {
        val ntag213 = TagClassifier.type2FromVersion(Hex.decode("0004040201000F03"))!!
        assertEquals("NTAG213", ntag213.name)
        assertEquals(45, ntag213.totalPages)
        assertEquals(144, ntag213.userBytes)
        assertEquals(41, ntag213.configPage)

        assertEquals("NTAG215", TagClassifier.type2FromVersion(Hex.decode("0004040201001103"))!!.name)
        val ntag216 = TagClassifier.type2FromVersion(Hex.decode("0004040201001303"))!!
        assertEquals("NTAG216", ntag216.name)
        assertEquals(231, ntag216.totalPages)
        assertEquals(227, ntag216.configPage)
        assertEquals("NTAG210", TagClassifier.type2FromVersion(Hex.decode("0004040101000B03"))!!.name)
        assertEquals("NTAG212", TagClassifier.type2FromVersion(Hex.decode("0004040101000E03"))!!.name)
    }

    @Test
    fun identifiesUltralightEv1AndRejectsForeignVersions() {
        val ul11 = TagClassifier.type2FromVersion(Hex.decode("0004030101000B03"))!!
        assertEquals("MIFARE Ultralight EV1 (MF0UL11)", ul11.name)
        assertEquals(20, ul11.totalPages)
        assertNull(TagClassifier.type2FromVersion(Hex.decode("0005040201000F03"))) // fremder Hersteller
        assertNull(TagClassifier.type2FromVersion(Hex.decode("0004")))             // zu kurz
        assertTrue(TagClassifier.type2FromVersion(Hex.decode("000404FF01000F03"))!!.name.startsWith("NTAG (Untertyp"))
    }

    @Test
    fun describesSakAndUidHints() {
        assertTrue(TagClassifier.describeSak(0x08).contains("MIFARE Classic 1K"))
        assertTrue(TagClassifier.describeSak(0x20).contains("ISO-DEP"))
        assertEquals("SAK 0x42", TagClassifier.describeSak(0x42))
        assertTrue(TagClassifier.uidHint(Hex.decode("08112233"))!!.contains("zufällige UID"))
        assertTrue(TagClassifier.uidHint(Hex.decode("04112233445566"))!!.contains("NXP"))
        assertNull(TagClassifier.uidHint(Hex.decode("12345678")))
        assertEquals("MIFARE Classic 4K", TagClassifier.mifareClassicName(4096))
        assertEquals("NXP Semiconductors", TagClassifier.iso15693Manufacturer(0x04))
    }

    // ---- Emulierbarkeit ---------------------------------------------------------------------------

    @Test
    fun ndefContentIsEmulatableAsType4ForEveryFamily() {
        for (family in TagFamily.values()) {
            val a = EmulationAssessor.assess(family, ndef)
            assertTrue("Familie $family", a.possible)
            assertTrue(a.explanation.contains("Type-4"))
            assertTrue(a.explanation.contains("UID"))
        }
    }

    @Test
    fun type2ExplanationNamesTheConcreteLimitation() {
        val a = EmulationAssessor.assess(TagFamily.TYPE2, ndef)
        assertTrue(a.explanation.contains("ISO 14443-3"))
        assertTrue(a.explanation.contains("ISO-DEP"))
    }

    @Test
    fun tagsWithoutNdefAreNotEmulatableAndExplainWhy() {
        val classic = EmulationAssessor.assess(TagFamily.MIFARE_CLASSIC, null)
        assertFalse(classic.possible)
        assertTrue(classic.explanation.contains("Crypto-1"))

        val isoDep = EmulationAssessor.assess(TagFamily.ISO_DEP, ByteArray(0))
        assertFalse(isoDep.possible)
        assertTrue(isoDep.explanation.contains("Zugriffsschutz"))

        assertTrue(EmulationAssessor.assess(TagFamily.TYPE5, null).explanation.contains("NFC-V"))
        assertTrue(EmulationAssessor.assess(TagFamily.NFC_F, null).explanation.contains("FeliCa"))
        assertFalse(EmulationAssessor.assess(TagFamily.UNKNOWN, null).possible)
    }

    @Test
    fun oversizedNdefIsNotEmulatable() {
        val a = EmulationAssessor.assess(TagFamily.TYPE4, ByteArray(Type4Tag.MAX_NDEF_MESSAGE_BYTES + 1))
        assertFalse(a.possible)
        assertEquals("Zu groß", a.headline)
    }

    @Test
    fun generalLimitsMentionRandomUid() {
        assertTrue(EmulationAssessor.GENERAL_LIMITS.any { it.contains("zufällige UID") })
        assertTrue(EmulationAssessor.GENERAL_LIMITS.any { it.contains("schreibgeschützt") })
    }
}
