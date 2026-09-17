package com.yomitanmobile.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yomitanmobile.data.local.entity.AudioFile
import kotlinx.coroutines.flow.Flow

/**
 * The index over the user's pronunciation archive. See [AudioFile].
 */
@Dao
interface AudioFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<AudioFile>)

    @Query("DELETE FROM audio_files")
    suspend fun clear()

    /** Distinct files, not rows: one file carries up to three keys. */
    @Query("SELECT COUNT(DISTINCT uri) FROM audio_files")
    suspend fun fileCount(): Int

    @Query("SELECT COUNT(DISTINCT uri) FROM audio_files")
    fun observeFileCount(): Flow<Int>

    /**
     * The best match among [keys], by [AudioFile.priority] — an
     * expression+reading hit before an expression before a bare reading.
     */
    @Query(
        "SELECT * FROM audio_files WHERE key IN (:keys) ORDER BY priority ASC, id ASC LIMIT 1"
    )
    suspend fun findBest(keys: List<String>): AudioFile?
}
