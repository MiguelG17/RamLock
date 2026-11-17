package com.tuidev.ramlock.ui

import com.tuidev.ramlock.data.FileMetadata
import java.io.File

data class VaultUiState(
    val isUnlocked: Boolean = false,
    val files: List<FileMetadata> = emptyList(),
    val isLoading: Boolean = false,
    val userMessage: UiText? = null,

    // Real-time progress for streaming operations
    val operationProgress: OperationProgress? = null,

    // File currently selected to open (drives the password dialog)
    val fileToOpen: FileMetadata? = null,

    // Decrypted result ready to display in the viewer
    val openedFile: OpenedFile? = null,
)

data class OperationProgress(
    val title: String,
    val fileName: String,
    val bytesProcessed: Long,
    val totalBytes: Long,
) {
    val progressFraction: Float
        get() = if (totalBytes > 0) (bytesProcessed.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val percentage: Int
        get() = (progressFraction * 100).toInt()
}

/**
 * Holds the reference to the decrypted temp file and the file type so the viewer knows how to render them.
 */
data class OpenedFile(
    val name: String,
    val category: FileCategory,
    val file: File
)
