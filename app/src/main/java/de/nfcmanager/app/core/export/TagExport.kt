package de.nfcmanager.app.core.export

import de.nfcmanager.app.core.Hex
import de.nfcmanager.app.core.json.Json
import de.nfcmanager.app.core.json.JsonException
import de.nfcmanager.app.core.tag.DetailRow
import de.nfcmanager.app.core.tag.DetailSection

/** Ein Tag in der Austauschform (Export/Import). Enthält nur frei gelesene Daten. */
class ExportedTag(
    val name: String,
    val symbol: String,
    val colorIndex: Int,
    val note: String,
    val uidHex: String,
    val tagType: String,
    val tagFamily: String,
    val techList: List<String>,
    val memoryBytes: Int?,
    val ndefMessage: ByteArray?,
    val ndefTypeName: String?,
    val sections: List<DetailSection>,
    val rawDump: String?,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

class ImportResult(val tags: List<ExportedTag>, val invalid: Int)

object TagExport {
    const val FORMAT = "nfc-manager-export"
    const val VERSION = 1

    fun sectionsToJson(sections: List<DetailSection>): String = Json.write(sectionsToValue(sections))

    private fun sectionsToValue(sections: List<DetailSection>): List<Any?> = sections.map { s ->
        mapOf("title" to s.title, "rows" to s.rows.map { mapOf("label" to it.label, "value" to it.value) })
    }

    /** Tolerant: unbrauchbare Eingaben ergeben eine leere Liste. */
    fun sectionsFromJson(text: String?): List<DetailSection> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            sectionsFromValue(Json.parse(text))
        } catch (e: JsonException) {
            emptyList()
        }
    }

    private fun sectionsFromValue(v: Any?): List<DetailSection> {
        val list = v as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val rows = (m["rows"] as? List<*>).orEmpty().mapNotNull { r ->
                val rm = r as? Map<*, *> ?: return@mapNotNull null
                val label = rm["label"] as? String ?: return@mapNotNull null
                DetailRow(label, rm["value"] as? String ?: "")
            }
            DetailSection(m["title"] as? String ?: "", rows)
        }
    }

    fun export(tags: List<ExportedTag>, exportedAt: Long): String {
        val doc = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "version" to VERSION,
            "exportedAt" to exportedAt,
            "tags" to tags.map { t ->
                linkedMapOf<String, Any?>(
                    "name" to t.name,
                    "symbol" to t.symbol,
                    "colorIndex" to t.colorIndex,
                    "note" to t.note,
                    "uid" to t.uidHex,
                    "tagType" to t.tagType,
                    "tagFamily" to t.tagFamily,
                    "techList" to t.techList,
                    "memoryBytes" to t.memoryBytes,
                    "ndefMessage" to t.ndefMessage?.let { Hex.encode(it) },
                    "ndefTypeName" to t.ndefTypeName,
                    "details" to sectionsToValue(t.sections),
                    "rawDump" to t.rawDump,
                    "source" to t.source,
                    "createdAt" to t.createdAt,
                    "updatedAt" to t.updatedAt,
                )
            },
        )
        return Json.write(doc, pretty = true)
    }

    /** @throws JsonException wenn die Datei kein gültiger Export dieser App ist. */
    fun import(text: String): ImportResult {
        val root = Json.parse(text) as? Map<*, *> ?: throw JsonException("Keine gültige Exportdatei")
        if (root["format"] != FORMAT) throw JsonException("Dies ist keine Exportdatei dieser App")
        val version = (root["version"] as? Long) ?: throw JsonException("Version fehlt")
        if (version > VERSION) throw JsonException("Die Datei stammt aus einer neueren App-Version (Format $version)")
        val list = root["tags"] as? List<*> ?: throw JsonException("Keine Tags in der Datei")
        var invalid = 0
        val out = ArrayList<ExportedTag>()
        val now = System.currentTimeMillis()
        for (item in list) {
            val m = item as? Map<*, *>
            val tag = if (m == null) null else parseTag(m, now)
            if (tag == null) invalid++ else out.add(tag)
        }
        return ImportResult(out, invalid)
    }

    private fun parseTag(m: Map<*, *>, now: Long): ExportedTag? {
        val ndefHex = m["ndefMessage"] as? String
        val ndef = if (ndefHex == null) null else (Hex.tryDecode(ndefHex) ?: return null)
        val uid = m["uid"] as? String ?: ""
        val name = (m["name"] as? String)?.trim().orEmpty().ifEmpty { "Importierter Tag" }
        return ExportedTag(
            name = name.take(80),
            symbol = (m["symbol"] as? String).orEmpty().take(8).ifEmpty { "🏷️" },
            colorIndex = ((m["colorIndex"] as? Long) ?: 0L).toInt().coerceIn(0, 99),
            note = (m["note"] as? String).orEmpty().take(2000),
            uidHex = uid,
            tagType = m["tagType"] as? String ?: "Unbekannter Tag",
            tagFamily = m["tagFamily"] as? String ?: "UNKNOWN",
            techList = (m["techList"] as? List<*>).orEmpty().filterIsInstance<String>(),
            memoryBytes = (m["memoryBytes"] as? Long)?.toInt(),
            ndefMessage = ndef,
            ndefTypeName = m["ndefTypeName"] as? String,
            sections = sectionsFromValue(m["details"]),
            rawDump = m["rawDump"] as? String,
            source = m["source"] as? String ?: "import",
            createdAt = (m["createdAt"] as? Long) ?: now,
            updatedAt = (m["updatedAt"] as? Long) ?: now,
        )
    }
}
