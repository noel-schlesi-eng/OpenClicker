package de.nfcmanager.app.data

import de.nfcmanager.app.core.export.ExportedTag
import de.nfcmanager.app.core.export.TagExport
import de.nfcmanager.app.core.tag.DetailSection
import de.nfcmanager.app.core.tag.TagSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Ein Tag mit ausgewerteten Detailabschnitten für die Anzeige. */
class SavedTag(val entity: SavedTagEntity) {
    val id: Long get() = entity.id
    val sections: List<DetailSection> by lazy { TagExport.sectionsFromJson(entity.detailsJson) }
    val techList: List<String> get() = entity.techList.split(',').filter { it.isNotBlank() }
    val hasNdef: Boolean get() = entity.ndefMessage?.isNotEmpty() == true
}

class ImportSummary(val imported: Int, val skipped: Int, val invalid: Int)

class TagRepository(private val dao: SavedTagDao) {

    fun observeAll(): Flow<List<SavedTag>> = dao.observeAll().map { list -> list.map { SavedTag(it) } }

    fun observe(id: Long): Flow<SavedTag?> = dao.observeById(id).map { it?.let { e -> SavedTag(e) } }

    suspend fun get(id: Long): SavedTag? = dao.getById(id)?.let { SavedTag(it) }

    suspend fun saveSnapshot(snapshot: TagSnapshot, name: String, symbol: String, colorIndex: Int): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            SavedTagEntity(
                name = name.trim().ifEmpty { snapshot.tagType }.take(80),
                symbol = symbol,
                colorIndex = colorIndex,
                note = "",
                uidHex = snapshot.uidHex,
                tagType = snapshot.tagType,
                tagFamily = snapshot.family.name,
                techList = snapshot.techList.joinToString(","),
                memoryBytes = snapshot.memoryBytes,
                ndefMessage = snapshot.ndefMessage,
                ndefTypeName = snapshot.ndefTypeName,
                detailsJson = TagExport.sectionsToJson(snapshot.sections),
                rawDump = snapshot.rawDump,
                source = "scan",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    /** Legt einen Eintrag nur aus NDEF-Inhalt an (im Editor erstellt). */
    suspend fun createFromMessage(name: String, symbol: String, colorIndex: Int, message: ByteArray, typeName: String?): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            SavedTagEntity(
                name = name.trim().ifEmpty { "Neuer Eintrag" }.take(80),
                symbol = symbol, colorIndex = colorIndex, note = "", uidHex = "",
                tagType = "Selbst erstellter Inhalt", tagFamily = "UNKNOWN", techList = "",
                memoryBytes = null, ndefMessage = message, ndefTypeName = typeName,
                detailsJson = "[]", rawDump = null, source = "manual", createdAt = now, updatedAt = now,
            ),
        )
    }

    suspend fun updateMeta(id: Long, name: String, symbol: String, colorIndex: Int, note: String) {
        val e = dao.getById(id) ?: return
        dao.update(copy(e, name = name.trim().ifEmpty { e.name }.take(80), symbol = symbol, colorIndex = colorIndex, note = note.take(2000)))
    }

    suspend fun updateMessage(id: Long, message: ByteArray) {
        val e = dao.getById(id) ?: return
        dao.update(copy(e, ndefMessage = message))
    }

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun exportJson(ids: Set<Long>? = null): String {
        val all = dao.getAll().filter { ids == null || it.id in ids }.sortedBy { it.createdAt }
        return TagExport.export(all.map { toExported(it) }, System.currentTimeMillis())
    }

    /** @throws de.nfcmanager.app.core.json.JsonException bei ungültiger Datei. */
    suspend fun importJson(text: String): ImportSummary {
        val parsed = TagExport.import(text)
        val existing = dao.getAll()
        val seen = HashSet<String>()
        for (e in existing) seen.add(key(e.name, e.uidHex, e.ndefMessage))
        var skipped = 0
        val fresh = ArrayList<SavedTagEntity>()
        for (t in parsed.tags) {
            if (!seen.add(key(t.name, t.uidHex, t.ndefMessage))) {
                skipped++
                continue
            }
            fresh.add(
                SavedTagEntity(
                    name = t.name, symbol = t.symbol, colorIndex = t.colorIndex, note = t.note, uidHex = t.uidHex,
                    tagType = t.tagType, tagFamily = t.tagFamily, techList = t.techList.joinToString(","),
                    memoryBytes = t.memoryBytes, ndefMessage = t.ndefMessage, ndefTypeName = t.ndefTypeName,
                    detailsJson = TagExport.sectionsToJson(t.sections), rawDump = t.rawDump, source = "import",
                    createdAt = t.createdAt, updatedAt = t.updatedAt,
                ),
            )
        }
        dao.insertAll(fresh)
        return ImportSummary(fresh.size, skipped, parsed.invalid)
    }

    private fun key(name: String, uid: String, ndef: ByteArray?): String =
        name + "|" + uid + "|" + (ndef?.joinToString("") { "%02x".format(it) } ?: "-")

    private fun toExported(e: SavedTagEntity) = ExportedTag(
        name = e.name, symbol = e.symbol, colorIndex = e.colorIndex, note = e.note, uidHex = e.uidHex,
        tagType = e.tagType, tagFamily = e.tagFamily, techList = e.techList.split(',').filter { it.isNotBlank() },
        memoryBytes = e.memoryBytes, ndefMessage = e.ndefMessage, ndefTypeName = e.ndefTypeName,
        sections = TagExport.sectionsFromJson(e.detailsJson), rawDump = e.rawDump, source = e.source,
        createdAt = e.createdAt, updatedAt = e.updatedAt,
    )

    private fun copy(
        e: SavedTagEntity,
        name: String = e.name,
        symbol: String = e.symbol,
        colorIndex: Int = e.colorIndex,
        note: String = e.note,
        ndefMessage: ByteArray? = e.ndefMessage,
    ) = SavedTagEntity(
        id = e.id, name = name, symbol = symbol, colorIndex = colorIndex, note = note, uidHex = e.uidHex,
        tagType = e.tagType, tagFamily = e.tagFamily, techList = e.techList, memoryBytes = e.memoryBytes,
        ndefMessage = ndefMessage, ndefTypeName = e.ndefTypeName, detailsJson = e.detailsJson, rawDump = e.rawDump,
        source = e.source, createdAt = e.createdAt, updatedAt = System.currentTimeMillis(),
    )
}
