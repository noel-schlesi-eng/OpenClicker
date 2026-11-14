package de.nfcmanager.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedTagDao {
    @Query("SELECT * FROM saved_tags ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SavedTagEntity>>

    @Query("SELECT * FROM saved_tags WHERE id = :id")
    fun observeById(id: Long): Flow<SavedTagEntity?>

    @Query("SELECT * FROM saved_tags WHERE id = :id")
    suspend fun getById(id: Long): SavedTagEntity?

    @Query("SELECT * FROM saved_tags")
    suspend fun getAll(): List<SavedTagEntity>

    @Insert
    suspend fun insert(tag: SavedTagEntity): Long

    @Insert
    suspend fun insertAll(tags: List<SavedTagEntity>)

    @Update
    suspend fun update(tag: SavedTagEntity)

    @Query("DELETE FROM saved_tags WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM saved_tags")
    suspend fun deleteAll()
}
