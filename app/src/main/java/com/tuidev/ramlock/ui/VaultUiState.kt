package com.tuidev.ramlock.ui

import com.tuidev.ramlock.data.FileMetadata

data class VaultUiState(
    val isUnlocked: Boolean = false,
    val files: List<FileMetadata> = emptyList(),
    val isLoading: Boolean = false,
    val userMessage: UiText? = null,

    // File currently selected to open (drives the password dialog)
    val fileToOpen: FileMetadata? = null,

    // Decrypted result ready to display in the viewer
    val openedFile: OpenedFile? = null,
)

/**
 * Holds the decrypted bytes and the file type so the viewer knows how to render them.
 */
data class OpenedFile(
    val name: String,
    val category: FileCategory,
    val bytes: ByteArray
)
