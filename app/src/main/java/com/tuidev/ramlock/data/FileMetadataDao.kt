package com.tuidev.ramlock.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(fileMetadata: FileMetadata): Long

    @Delete
    suspend fun deleteFile(fileMetadata: FileMetadata): Int

    @Query("SELECT * FROM files_metadata ORDER BY created_at DESC")
    fun getAllFiles(): Flow<List<FileMetadata>>

    @Query("SELECT * FROM files_metadata WHERE id = :id LIMIT 1")
    suspend fun getFileById(id: Long): FileMetadata?
}
