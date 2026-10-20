package com.tuidev.ramlock.ui

import com.tuidev.ramlock.data.FileMetadata


data class VaultUiState(
    val isUnlocked: Boolean = false,

    // List of encrypted files stored in Room
    val files: List<FileMetadata> = emptyList(),

    // True while a heavy encryption or decryption process is underway
    val isLoading: Boolean = false,

    // Temporary messages to display in a Snackbar (e.g., "File encrypted successfully")
    val userMessage: String? = null
)