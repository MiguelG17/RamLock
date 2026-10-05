package com.tuidev.ramlock.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey

@Entity
data class FileMetadata(
    @PrimaryKey(autoGenerate = true) val uid: Int,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "file_size") val fileSize: Long?,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long?
)