package com.tuidev.ramlock.inteface

import androidx.room3.Dao
import androidx.room3.Delete
import androidx.room3.Insert
import androidx.room3.Query
import com.tuidev.ramlock.data.FileMetadata

@Dao
interface FileMetadataDao {
    @Insert
    suspend fun insertFile(fileMetadata: FileMetadata)

    @Delete
    suspend fun deleteFile(fileMetadata: FileMetadata)

    @Query("SELECT * FROM file_metadata")
    suspend fun getAllFiles(): List<FileMetadata>

}
