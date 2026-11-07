package com.tuidev.ramlock.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.ui.graphics.vector.ImageVector

enum class FileCategory {
    IMAGE, TEXT, VIDEO, AUDIO, PDF, OTHER
}

/**
 * Determines the preview category based on the file's original extension.
 */
fun getFileCategory(originalName: String): FileCategory {
    return when (originalName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> FileCategory.IMAGE
        "txt", "md", "json", "xml", "csv", "log", "kt", "py", "js" -> FileCategory.TEXT
        "mp4", "mkv", "avi", "mov", "webm" -> FileCategory.VIDEO
        "mp3", "ogg", "wav", "flac", "aac" -> FileCategory.AUDIO
        "pdf" -> FileCategory.PDF
        else -> FileCategory.OTHER
    }
}

/**
 * Returns a high-quality vector icon for the file type card.
 */
fun getFileIconVector(originalName: String): ImageVector {
    return when (getFileCategory(originalName)) {
        FileCategory.IMAGE -> Icons.Outlined.Image
        FileCategory.TEXT -> Icons.Outlined.Description
        FileCategory.VIDEO -> Icons.Outlined.Movie
        FileCategory.AUDIO -> Icons.Outlined.MusicNote
        FileCategory.PDF -> Icons.Outlined.PictureAsPdf
        FileCategory.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}

/**
 * Returns a human-readable MIME type string for FileProvider usage.
 */
fun getMimeType(originalName: String): String {
    return when (getFileCategory(originalName)) {
        FileCategory.IMAGE -> "image/*"
        FileCategory.TEXT -> "text/plain"
        FileCategory.VIDEO -> "video/*"
        FileCategory.AUDIO -> "audio/*"
        FileCategory.PDF -> "application/pdf"
        FileCategory.OTHER -> "*/*"
    }
}
