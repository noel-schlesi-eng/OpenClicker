package de.nfcmanager.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_tags")
class SavedTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val symbol: String,
    val colorIndex: Int,
    val note: String,
    val uidHex: String,
    val tagType: String,
    val tagFamily: String,
    /** Technologien, mit Komma getrennt. */
    val techList: String,
    val memoryBytes: Int?,
    val ndefMessage: ByteArray?,
    val ndefTypeName: String?,
    /** Detailabschnitte als JSON (siehe TagExport.sectionsToJson). */
    val detailsJson: String,
    val rawDump: String?,
    /** "scan", "import" oder "manual". */
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)
