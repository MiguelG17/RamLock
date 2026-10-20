package com.tuidev.ramlock.ui

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
 * Returns a descriptive emoji icon for the file type card.
 */
fun getFileIcon(originalName: String): String {
    return when (getFileCategory(originalName)) {
        FileCategory.IMAGE -> "🖼️"
        FileCategory.TEXT -> "📝"
        FileCategory.VIDEO -> "🎬"
        FileCategory.AUDIO -> "🎵"
        FileCategory.PDF -> "📄"
        FileCategory.OTHER -> "📦"
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