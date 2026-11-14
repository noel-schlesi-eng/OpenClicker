package de.nfcmanager.app.nfc

import android.nfc.NdefMessage
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import de.nfcmanager.app.core.Hex
import de.nfcmanager.app.core.ndef.NdefCodec
import de.nfcmanager.app.core.tag.DetailRow
import de.nfcmanager.app.core.tag.DetailSection
import de.nfcmanager.app.core.tag.TagClassifier
import de.nfcmanager.app.core.tag.TagFamily
import de.nfcmanager.app.core.tag.TagMemory
import de.nfcmanager.app.core.tag.TagSnapshot
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Liest einen von Android erkannten Tag über die offiziellen android.nfc.tech-Klassen aus.
 *
 * Grundsätze:
 *  - Es werden nur öffentlich zugängliche Informationen gelesen: Identifikationsdaten, NDEF-Inhalt und
 *    frei lesbarer Speicher (Type 2 / Type 5).
 *  - Es erfolgt keine Authentifizierung. MIFARE-Classic-Sektoren, ISO-DEP-Anwendungen und passwortgeschützte
 *    Bereiche werden nicht ausgelesen.
 *  - Jede Technologie wird einzeln abgesichert; ein Fehler bricht nie den gesamten Lesevorgang ab.
 *
 * Muss auf einem Hintergrund-Thread aufgerufen werden (der Reader-Mode-Callback von Android tut das).
 */
object TagReader {

    private const val MAX_TYPE2_PAGES = 256
    private const val MAX_TYPE5_BLOCKS = 256
    private const val TYPE5_TIME_BUDGET_MS = 4000L

    fun read(tag: Tag): TagSnapshot =
        try {
            readInternal(tag)
        } catch (e: Exception) {
            fallback(tag, e)
        }

    private fun fallback(tag: Tag, error: Exception): TagSnapshot {
        val uid = try {
            tag.id ?: ByteArray(0)
        } catch (e: Exception) {
            ByteArray(0)
        }
        return TagSnapshot(
            uid = uid,
            techList = safeTechList(tag),
            tagType = "Unbekannter Tag",
            family = TagFamily.UNKNOWN,
            memoryBytes = null,
            ndefMessage = null,
            ndefTypeName = null,
            ndefCapacity = null,
            ndefWritable = null,
            sections = listOf(DetailSection("Allgemein", listOf(DetailRow("UID", Hex.encode(uid, ":"))))),
            rawDump = null,
            notes = listOf("Der Tag konnte nicht vollständig gelesen werden: ${error.javaClass.simpleName}"),
            scannedAt = System.currentTimeMillis(),
        )
    }

    private fun safeTechList(tag: Tag): List<String> =
        try {
            tag.techList.map { it.substringAfterLast('.') }
        } catch (e: Exception) {
            emptyList()
        }

    private fun readInternal(tag: Tag): TagSnapshot {
        val notes = ArrayList<String>()
        val sections = ArrayList<DetailSection>()
        val uid = tag.id ?: ByteArray(0)
        val techs = safeTechList(tag)

        var family = TagFamily.UNKNOWN
        var tagType: String? = null
        var memoryBytes: Int? = null
        var rawDump: String? = null
        var ndefBytes: ByteArray? = null
        var ndefTypeLabel: String? = null
        var ndefCapacity: Int? = null
        var ndefWritable: Boolean? = null

        // ---- Identifikation ohne Verbindungsaufbau -------------------------------------------------
        var atqa: ByteArray? = null
        var sak = -1
        if ("NfcA" in techs) {
            val a = NfcA.get(tag)
            if (a != null) {
                atqa = safe { a.atqa }
                sak = safe { a.sak.toInt() and 0xFFFF } ?: -1
                val rows = ArrayList<DetailRow>()
                if (atqa != null) rows.add(DetailRow("ATQA", Hex.encode(atqa, " ")))
                if (sak >= 0) {
                    rows.add(DetailRow("SAK", "0x%02X".format(sak)))
                    rows.add(DetailRow("SAK-Bedeutung", TagClassifier.describeSak(sak)))
                }
                safe { a.maxTransceiveLength }?.let { rows.add(DetailRow("Max. Übertragungslänge", "$it Bytes")) }
                if (rows.isNotEmpty()) sections.add(DetailSection("NFC-A (ISO 14443-3A)", rows))
            }
        }
        if ("NfcB" in techs) {
            val b = NfcB.get(tag)
            if (b != null) {
                val rows = ArrayList<DetailRow>()
                safe { b.applicationData }?.let { rows.add(DetailRow("Anwendungsdaten", Hex.encode(it, " "))) }
                safe { b.protocolInfo }?.let { rows.add(DetailRow("Protokollinfo", Hex.encode(it, " "))) }
                safe { b.maxTransceiveLength }?.let { rows.add(DetailRow("Max. Übertragungslänge", "$it Bytes")) }
                sections.add(DetailSection("NFC-B (ISO 14443-3B)", rows))
            }
        }
        if ("NfcF" in techs) {
            val f = NfcF.get(tag)
            if (f != null) {
                val rows = ArrayList<DetailRow>()
                rows.add(DetailRow("IDm (UID)", Hex.encode(uid, ":")))
                safe { f.manufacturer }?.let { rows.add(DetailRow("PMm (Hersteller-Parameter)", Hex.encode(it, " "))) }
                safe { f.systemCode }?.let { rows.add(DetailRow("System-Code", Hex.encode(it, " "))) }
                safe { f.maxTransceiveLength }?.let { rows.add(DetailRow("Max. Übertragungslänge", "$it Bytes")) }
                sections.add(DetailSection("NFC-F (FeliCa)", rows))
            }
        }
        if ("IsoDep" in techs) {
            val d = IsoDep.get(tag)
            if (d != null) {
                val rows = ArrayList<DetailRow>()
                safe { d.historicalBytes }?.let { rows.add(DetailRow("Historische Bytes (NFC-A)", Hex.encode(it, " "))) }
                safe { d.hiLayerResponse }?.let { rows.add(DetailRow("Higher-Layer-Antwort (NFC-B)", Hex.encode(it, " "))) }
                safe { d.isExtendedLengthApduSupported }?.let { rows.add(DetailRow("Erweiterte APDU-Längen", if (it) "ja" else "nein")) }
                safe { d.maxTransceiveLength }?.let { rows.add(DetailRow("Max. Übertragungslänge", "$it Bytes")) }
                rows.add(DetailRow("Hinweis", "Anwendungen auf dem Chip werden nicht ausgelesen."))
                sections.add(DetailSection("ISO-DEP (ISO 14443-4)", rows))
            }
        }
        var classic: MifareClassic? = null
        if ("MifareClassic" in techs) {
            classic = MifareClassic.get(tag)
            if (classic != null) {
                val c = classic
                val rows = ArrayList<DetailRow>()
                val size = safe { c.size }
                if (size != null) {
                    rows.add(DetailRow("Typ", TagClassifier.mifareClassicName(size)))
                    rows.add(DetailRow("Speichergröße", "$size Bytes"))
                    memoryBytes = size
                }
                safe { c.sectorCount }?.let { rows.add(DetailRow("Sektoren", it.toString())) }
                safe { c.blockCount }?.let { rows.add(DetailRow("Blöcke", it.toString())) }
                rows.add(DetailRow("Hinweis", "Sektoren sind durch Schlüssel geschützt und werden bewusst nicht gelesen."))
                sections.add(DetailSection("MIFARE Classic", rows))
            }
        } else if (sak == 0x08 || sak == 0x18 || sak == 0x09 || sak == 0x88) {
            notes.add("Dieser Tag ist ein MIFARE-Classic-Typ, aber der NFC-Chip dieses Geräts unterstützt MIFARE Classic nicht. Nur die Kennung ist lesbar.")
        }

        // ---- NDEF (cachedNdefMessage benötigt keinen Verbindungsaufbau) -----------------------------
        val ndef = if ("Ndef" in techs) Ndef.get(tag) else null
        if (ndef != null) {
            val type = safe { ndef.type }
            ndefTypeLabel = ndefTypeLabel(type)
            ndefCapacity = safe { ndef.maxSize }
            ndefWritable = safe { ndef.isWritable }
            var message: NdefMessage? = safe { ndef.cachedNdefMessage }
            if (message == null) message = readNdefLive(ndef, notes)
            if (message != null) {
                val bytes = message.toByteArray()
                val records = NdefCodec.tryParse(bytes)
                if (records != null && !NdefCodec.isEffectivelyEmpty(records)) ndefBytes = bytes
            }
            val rows = ArrayList<DetailRow>()
            rows.add(DetailRow("Tag-Typ laut Android", ndefTypeLabel ?: (type ?: "unbekannt")))
            ndefCapacity?.let { rows.add(DetailRow("Maximale NDEF-Größe", "$it Bytes")) }
            ndefWritable?.let { rows.add(DetailRow("Beschreibbar", if (it) "ja" else "nein (schreibgeschützt)")) }
            safe { ndef.canMakeReadOnly() }?.let { rows.add(DetailRow("Dauerhaft sperrbar", if (it) "ja" else "nein")) }
            rows.add(
                DetailRow(
                    "NDEF-Inhalt",
                    if (ndefBytes != null) "${ndefBytes.size} Bytes" else "leer",
                ),
            )
            sections.add(DetailSection("NDEF", rows))
        } else if ("NdefFormatable" in techs) {
            sections.add(
                DetailSection(
                    "NDEF",
                    listOf(DetailRow("Status", "Tag ist leer, aber NDEF-formatierbar (noch kein Inhalt).")),
                ),
            )
        }

        // ---- NFC Forum Type 2 (NTAG, Ultralight): frei lesbarer Speicher ---------------------------
        var type2Name: String? = null
        if ("MifareUltralight" in techs) {
            val mfu = MifareUltralight.get(tag)
            if (mfu != null) {
                val result = readType2(mfu, notes)
                if (result != null) {
                    family = TagFamily.TYPE2
                    type2Name = result.name
                    memoryBytes = result.userBytes ?: memoryBytes
                    rawDump = result.dumpText
                    sections.add(DetailSection("MIFARE Ultralight / NTAG", result.rows))
                    if (ndefBytes == null && ndef == null && result.fallbackNdef != null) {
                        val records = NdefCodec.tryParse(result.fallbackNdef)
                        if (records != null && !NdefCodec.isEffectivelyEmpty(records)) {
                            ndefBytes = result.fallbackNdef
                            notes.add("NDEF-Inhalt aus dem Speicherabbild rekonstruiert (Android meldete keine NDEF-Unterstützung).")
                        }
                    }
                }
            }
        }

        // ---- ISO 15693 / NFC-V ----------------------------------------------------------------------
        var type5Name: String? = null
        if ("NfcV" in techs) {
            val v = NfcV.get(tag)
            if (v != null) {
                val result = readType5(v, uid, notes)
                family = TagFamily.TYPE5
                type5Name = result.name
                memoryBytes = result.totalBytes ?: memoryBytes
                rawDump = result.dumpText ?: rawDump
                sections.add(DetailSection("NFC-V (ISO 15693)", result.rows))
                if (ndefBytes == null && result.ndef != null) {
                    val records = NdefCodec.tryParse(result.ndef)
                    if (records != null && !NdefCodec.isEffectivelyEmpty(records)) ndefBytes = result.ndef
                }
            }
        }

        // ---- Familie und Typname bestimmen ---------------------------------------------------------
        val ndefType = if (ndef != null) safe { ndef.type } else null
        family = when {
            ndefType == Ndef.NFC_FORUM_TYPE_1 -> TagFamily.TYPE1
            ndefType == Ndef.NFC_FORUM_TYPE_2 -> TagFamily.TYPE2
            ndefType == Ndef.NFC_FORUM_TYPE_3 -> TagFamily.TYPE3
            ndefType == Ndef.NFC_FORUM_TYPE_4 -> TagFamily.TYPE4
            ndefType == Ndef.MIFARE_CLASSIC -> TagFamily.MIFARE_CLASSIC
            family != TagFamily.UNKNOWN -> family
            classic != null || "MifareClassic" in techs -> TagFamily.MIFARE_CLASSIC
            "IsoDep" in techs -> TagFamily.ISO_DEP
            "NfcV" in techs -> TagFamily.TYPE5
            "NfcF" in techs -> TagFamily.NFC_F
            "NfcB" in techs -> TagFamily.NFC_B
            "NfcA" in techs -> if (sak == 0x00) TagFamily.TYPE2 else TagFamily.NFC_A
            else -> TagFamily.UNKNOWN
        }

        tagType = when {
            type2Name != null -> type2Name
            type5Name != null -> type5Name
            family == TagFamily.MIFARE_CLASSIC && classic != null -> safe { TagClassifier.mifareClassicName(classic.size) }
            family == TagFamily.MIFARE_CLASSIC -> "MIFARE Classic (Chipsatz ohne Classic-Unterstützung)"
            family == TagFamily.TYPE4 -> "NFC Forum Type 4 Tag (ISO-DEP)"
            family == TagFamily.ISO_DEP -> {
                if (atqa != null && atqa.size >= 2 && (atqa[0].toInt() and 0xFF) == 0x03 && (atqa[1].toInt() and 0xFF) == 0x44) {
                    "ISO-DEP-Karte (vermutlich MIFARE DESFire)"
                } else {
                    "ISO-DEP-Karte (ISO 14443-4)"
                }
            }
            family == TagFamily.TYPE3 || family == TagFamily.NFC_F -> "FeliCa (NFC-F)"
            family == TagFamily.TYPE1 -> "NFC Forum Type 1 Tag (Topaz/Jewel)"
            family == TagFamily.NFC_B -> "NFC-B-Tag (ISO 14443-3B)"
            family == TagFamily.NFC_A -> "NFC-A-Tag (${TagClassifier.describeSak(sak)})"
            else -> "Unbekannter Tag"
        }

        // ---- Allgemeiner Abschnitt (an erster Stelle) -------------------------------------------------
        val general = ArrayList<DetailRow>()
        general.add(DetailRow("Tag-Typ", tagType ?: "Unbekannter Tag"))
        general.add(DetailRow("Familie", family.label))
        general.add(DetailRow("UID", Hex.encode(uid, ":")))
        general.add(DetailRow("UID-Länge", "${uid.size} Bytes"))
        TagClassifier.uidHint(uid)?.let { general.add(DetailRow("UID-Hinweis", it)) }
        if (family == TagFamily.TYPE5 && uid.size == 8 && (uid[7].toInt() and 0xFF) == 0xE0) {
            general.add(DetailRow("UID (ISO-15693-Schreibweise)", Hex.encode(uid.reversedArray(), ":")))
        }
        general.add(DetailRow("Technologien", techs.joinToString(", ").ifEmpty { "keine" }))
        memoryBytes?.let { general.add(DetailRow("Speicher", "$it Bytes")) }
        sections.add(0, DetailSection("Allgemein", general))

        return TagSnapshot(
            uid = uid,
            techList = techs,
            tagType = tagType ?: "Unbekannter Tag",
            family = family,
            memoryBytes = memoryBytes,
            ndefMessage = ndefBytes,
            ndefTypeName = ndefTypeLabel ?: ndefTypeLabel(ndefType),
            ndefCapacity = ndefCapacity,
            ndefWritable = ndefWritable,
            sections = sections,
            rawDump = rawDump,
            notes = notes,
            scannedAt = System.currentTimeMillis(),
        )
    }

    // ---- NDEF ---------------------------------------------------------------------------------------

    private fun ndefTypeLabel(type: String?): String? = when (type) {
        Ndef.NFC_FORUM_TYPE_1 -> "NFC Forum Type 1"
        Ndef.NFC_FORUM_TYPE_2 -> "NFC Forum Type 2"
        Ndef.NFC_FORUM_TYPE_3 -> "NFC Forum Type 3"
        Ndef.NFC_FORUM_TYPE_4 -> "NFC Forum Type 4"
        Ndef.MIFARE_CLASSIC -> "MIFARE Classic (NDEF über MAD)"
        else -> null
    }

    private fun readNdefLive(ndef: Ndef, notes: MutableList<String>): NdefMessage? {
        return try {
            ndef.connect()
            ndef.ndefMessage
        } catch (e: TagLostException) {
            notes.add("Der Tag wurde beim Lesen des NDEF-Inhalts zu früh entfernt.")
            null
        } catch (e: Exception) {
            null
        } finally {
            try {
                ndef.close()
            } catch (e: Exception) {
                // ignorieren
            }
        }
    }

    // ---- Type 2 -------------------------------------------------------------------------------------

    private class Type2Result(
        val name: String,
        val userBytes: Int?,
        val rows: List<DetailRow>,
        val dumpText: String?,
        val fallbackNdef: ByteArray?,
    )

    private fun readType2(mfu: MifareUltralight, notes: MutableList<String>): Type2Result? {
        val type = safe { mfu.type } ?: MifareUltralight.TYPE_UNKNOWN
        val maxPages = if (type == MifareUltralight.TYPE_ULTRALIGHT_C) 44 else MAX_TYPE2_PAGES
        val buffer = ByteArrayOutputStream()
        var first: ByteArray? = null
        var stoppedAt: Int? = null
        var lost = false
        var version: ByteArray? = null

        try {
            mfu.connect()
            var page = 0
            read@ while (page < maxPages) {
                val chunk: ByteArray = try {
                    mfu.readPages(page)
                } catch (e: TagLostException) {
                    lost = true
                    stoppedAt = page
                    break@read
                } catch (e: IOException) {
                    stoppedAt = page
                    break@read
                }
                if (chunk.size < 16) {
                    stoppedAt = page
                    break@read
                }
                val head = first
                if (head == null) {
                    first = chunk.copyOf(16)
                } else {
                    // Überlauf erkennen: NTAG liest am Speicherende wieder ab Seite 0.
                    if (chunk.contentEquals(head)) break@read
                    var wrapAt = -1
                    for (k in 1..3) {
                        if (chunk.copyOfRange(4 * k, 16).contentEquals(head.copyOf(16 - 4 * k))) {
                            wrapAt = k
                            break
                        }
                    }
                    if (wrapAt > 0) {
                        buffer.write(chunk, 0, 4 * wrapAt)
                        break@read
                    }
                }
                buffer.write(chunk, 0, 16)
                page += 4
            }

            // GET_VERSION erst nach dem Lesen: Chips ohne diesen Befehl quittieren mit NAK und würden
            // sonst die Verbindung beenden, bevor der Speicher gelesen ist.
            if (!lost && buffer.size() >= 16) {
                if (stoppedAt != null) {
                    try {
                        mfu.close()
                        mfu.connect()
                    } catch (e: IOException) {
                        // Wiederverbinden nicht möglich – GET_VERSION wird dann übersprungen.
                    }
                }
                version = try {
                    mfu.transceive(byteArrayOf(0x60))
                } catch (e: Exception) {
                    null
                }
            }
        } catch (e: TagLostException) {
            lost = true
        } catch (e: IOException) {
            notes.add("Der Speicher des Tags konnte nicht gelesen werden.")
        } finally {
            try {
                mfu.close()
            } catch (e: Exception) {
                // ignorieren
            }
        }

        if (lost) notes.add("Der Tag wurde zu früh entfernt. Bitte erneut anlegen und ruhig halten, damit alle Daten gelesen werden.")
        val dump = buffer.toByteArray()
        if (dump.size < 16) return null

        val chip = version?.let { TagClassifier.type2FromVersion(it) }
        val cc = TagMemory.parseType2Cc(dump)
        val totalPages = dump.size / 4

        val name = when {
            chip != null -> chip.name
            cc != null && cc.dataAreaBytes == 144 -> "NTAG213 (aus Capability Container abgeleitet)"
            cc != null && cc.dataAreaBytes == 496 -> "NTAG215 (aus Capability Container abgeleitet)"
            cc != null && cc.dataAreaBytes == 872 -> "NTAG216 (aus Capability Container abgeleitet)"
            type == MifareUltralight.TYPE_ULTRALIGHT_C -> "MIFARE Ultralight C"
            type == MifareUltralight.TYPE_ULTRALIGHT -> "MIFARE Ultralight / NTAG (Chip-Variante nicht bestimmbar)"
            else -> "NFC Forum Type 2 Tag"
        }
        val userBytes = chip?.userBytes ?: cc?.dataAreaBytes

        val rows = ArrayList<DetailRow>()
        rows.add(DetailRow("Chip", name))
        if (version != null) rows.add(DetailRow("GET_VERSION-Antwort", Hex.encode(version, " ")))
        val expected = chip?.totalPages
        rows.add(DetailRow("Gelesene Seiten", if (expected != null) "$totalPages von $expected" else "$totalPages (je 4 Bytes)"))
        if (expected != null && totalPages < expected) {
            notes.add("Nur $totalPages von $expected Seiten waren lesbar (Rest vermutlich passwortgeschützt oder Tag zu früh entfernt).")
        }
        if (userBytes != null) rows.add(DetailRow("Nutzspeicher", "$userBytes Bytes"))
        if (cc != null) {
            rows.add(DetailRow("Capability Container", Hex.encode(dump.copyOfRange(12, 16), " ")))
            rows.add(DetailRow("NDEF-Mapping-Version", cc.versionText))
            rows.add(DetailRow("NDEF-Datenbereich", "${cc.dataAreaBytes} Bytes"))
            rows.add(DetailRow("Lesezugriff (CC)", TagMemory.describeAccess(cc.readAccess)))
            rows.add(DetailRow("Schreibzugriff (CC)", TagMemory.describeAccess(cc.writeAccess)))
        } else {
            rows.add(DetailRow("Capability Container", "nicht vorhanden (Tag nicht NDEF-formatiert)"))
        }
        if (dump.size >= 12) {
            val lock0 = dump[10].toInt() and 0xFF
            val lock1 = dump[11].toInt() and 0xFF
            rows.add(
                DetailRow(
                    "Statische Sperrbits",
                    if (lock0 == 0 && lock1 == 0) "keine gesetzt" else "gesetzt (0x%02X%02X) – Teile sind schreibgeschützt".format(lock0, lock1),
                ),
            )
        }
        val configPage = chip?.configPage
        val chipPages = chip?.totalPages
        if (configPage != null && chipPages != null) {
            rows.addAll(TagMemory.describeNtagConfig(dump, configPage, chipPages))
        }

        val fallback = if (cc != null && dump.size > 16) {
            TagMemory.scanTlvs(dump, 16, minOf(dump.size, 16 + cc.dataAreaBytes)).ndef?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return Type2Result(name, userBytes, rows, Hex.dump(dump, 16), fallback)
    }

    // ---- Type 5 / ISO 15693 -------------------------------------------------------------------------

    private class Type5Result(
        val name: String,
        val totalBytes: Int?,
        val rows: List<DetailRow>,
        val dumpText: String?,
        val ndef: ByteArray?,
    )

    private fun readType5(v: NfcV, uid: ByteArray, notes: MutableList<String>): Type5Result {
        val rows = ArrayList<DetailRow>()
        safe { v.dsfId }?.let { rows.add(DetailRow("DSFID", "0x%02X".format(it.toInt() and 0xFF))) }
        safe { v.responseFlags }?.let { rows.add(DetailRow("Antwort-Flags", "0x%02X".format(it.toInt() and 0xFF))) }
        safe { v.maxTransceiveLength }?.let { rows.add(DetailRow("Max. Übertragungslänge", "$it Bytes")) }

        var manufacturer: String? = null
        if (uid.size == 8 && (uid[7].toInt() and 0xFF) == 0xE0) {
            manufacturer = TagClassifier.iso15693Manufacturer(uid[6].toInt() and 0xFF)
            rows.add(DetailRow("Hersteller", manufacturer ?: "Code 0x%02X".format(uid[6].toInt() and 0xFF)))
        }

        var blockCount: Int? = null
        var blockSize = 4
        val data = ByteArrayOutputStream()
        var readError = false
        var lost = false
        val started = System.currentTimeMillis()

        try {
            v.connect()
            // Get System Information (0x2B), nicht adressiert, hohe Datenrate
            try {
                val info = v.transceive(byteArrayOf(0x02, 0x2B))
                if (info.size >= 10 && info[0].toInt() == 0) {
                    val infoFlags = info[1].toInt() and 0xFF
                    var idx = 10
                    if (infoFlags and 0x01 != 0) idx++ // DSFID
                    if (infoFlags and 0x02 != 0) idx++ // AFI
                    if (infoFlags and 0x04 != 0 && idx + 1 < info.size) {
                        blockCount = (info[idx].toInt() and 0xFF) + 1
                        blockSize = (info[idx + 1].toInt() and 0x1F) + 1
                        idx += 2
                    }
                    if (infoFlags and 0x08 != 0 && idx < info.size) {
                        rows.add(DetailRow("IC-Referenz", "0x%02X".format(info[idx].toInt() and 0xFF)))
                    }
                }
            } catch (e: TagLostException) {
                lost = true
            } catch (e: IOException) {
                // Befehl nicht unterstützt – Blöcke werden trotzdem versucht
            }

            if (!lost) {
                val limit = minOf(blockCount ?: MAX_TYPE5_BLOCKS, MAX_TYPE5_BLOCKS)
                var block = 0
                while (block < limit) {
                    if (System.currentTimeMillis() - started > TYPE5_TIME_BUDGET_MS) {
                        notes.add("Lesen des NFC-V-Speichers nach ${TYPE5_TIME_BUDGET_MS / 1000} Sekunden beendet.")
                        break
                    }
                    val resp = try {
                        v.transceive(byteArrayOf(0x02, 0x20, block.toByte()))
                    } catch (e: TagLostException) {
                        lost = true
                        break
                    } catch (e: IOException) {
                        readError = true
                        break
                    }
                    if (resp.isEmpty() || resp[0].toInt() != 0) {
                        readError = true // Fehler-Flag: Block nicht lesbar (z. B. gesperrt)
                        break
                    }
                    data.write(resp, 1, resp.size - 1)
                    block++
                }
            }
        } catch (e: TagLostException) {
            lost = true
        } catch (e: IOException) {
            notes.add("Der NFC-V-Tag konnte nicht gelesen werden.")
        } finally {
            try {
                v.close()
            } catch (e: Exception) {
                // ignorieren
            }
        }

        if (lost) notes.add("Der Tag wurde zu früh entfernt. Bitte erneut anlegen und ruhig halten.")
        val bytes = data.toByteArray()
        val total = if (blockCount != null) blockCount * blockSize else null
        if (blockCount != null) {
            rows.add(DetailRow("Blöcke", "$blockCount × $blockSize Bytes"))
        }
        rows.add(DetailRow("Gelesene Daten", "${bytes.size} Bytes"))
        if (readError && total != null && bytes.size < total) {
            notes.add("Nur ${bytes.size} von $total Bytes waren lesbar (Rest vermutlich gesperrt).")
        }
        val limitHit = blockCount != null && blockCount > MAX_TYPE5_BLOCKS
        if (limitHit) notes.add("Speicher größer als $MAX_TYPE5_BLOCKS Blöcke: nur die ersten $MAX_TYPE5_BLOCKS wurden gelesen.")

        var ndef: ByteArray? = null
        val cc = TagMemory.parseType5Cc(bytes)
        if (cc != null) {
            rows.add(DetailRow("Capability Container", Hex.encode(bytes.copyOfRange(0, cc.length), " ")))
            rows.add(DetailRow("NDEF-Datenbereich", "${cc.dataAreaBytes} Bytes"))
            ndef = TagMemory.scanTlvs(bytes, cc.length).ndef?.takeIf { it.isNotEmpty() }
        } else if (bytes.isNotEmpty()) {
            rows.add(DetailRow("Capability Container", "nicht vorhanden (Tag nicht NDEF-formatiert)"))
        }

        val name = "ISO 15693 (NFC-V)" + (manufacturer?.let { " – $it" } ?: "")
        return Type5Result(name, total, rows, if (bytes.isEmpty()) null else Hex.dump(bytes, 16), ndef)
    }

    // ---- Hilfen -------------------------------------------------------------------------------------

    /** Führt einen Getter aus und gibt bei jedem Fehler null zurück (manche Treiber werfen bei Zugriff auf Tag-Eigenschaften). */
    private inline fun <T> safe(block: () -> T): T? =
        try {
            block()
        } catch (e: Exception) {
            null
        }
}
