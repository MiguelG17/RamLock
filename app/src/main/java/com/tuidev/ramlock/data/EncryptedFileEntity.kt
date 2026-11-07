package com.tuidev.ramlock.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "files_metadata")
data class FileMetadata(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "original_name")
    val originalName: String,
    @ColumnInfo(name = "encrypted_file_name")
    val encryptedFileName: String,
    @ColumnInfo(name = "file_size")
    val fileSize: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "original_path", defaultValue = "NULL")
    val originalPath: String? = null
)
