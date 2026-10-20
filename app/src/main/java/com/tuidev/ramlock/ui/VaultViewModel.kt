package com.tuidev.ramlock.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tuidev.ramlock.data.FileMetadata
import com.tuidev.ramlock.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VaultViewModel(
    private val repository: VaultRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    // The master password exists solely in RAM during the session
    private var currentPassword: String? = null

    init {
        // We can view the list of files in Room in real time
        viewModelScope.launch {
            repository.allFiles.collect { fileList ->
                _uiState.update { it.copy(files = fileList) }
            }
        }
    }

    /**
     * Unlock the vault by temporarily storing the password in RAM.
     */
    fun unlockVault(password: String) {
        if (password.isNotBlank()) {
            currentPassword = password
            _uiState.update { it.copy(isUnlocked = true, userMessage = null) }
        } else {
            _uiState.update { it.copy(userMessage = "The password cannot be left blank") }
        }
    }

    /**
     * Bloquea la bóveda y limpia la contraseña de la memoria RAM.
     */
    fun lockVault() {
        currentPassword = null
        _uiState.update { it.copy(isUnlocked = false) }
    }

    /**
     * Reads the file selected by the user using SAF (Storage Access Framework),
     * encrypts it using Rust, and stores it in the private vault.
     */
    fun importAndEncryptFile(context: Context, uri: Uri) {
        val password = currentPassword ?: run {
            _uiState.update { it.copy(userMessage = "Vault locked. Please log in first.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // 1. Retrieve the name of the original file
                val fileName = getFileName(context, uri) ?: "unnamed_file"

                // 2. Reading bytes using ContentResolver
                val fileBytes = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.readBytes()
                } ?: throw IllegalArgumentException("The selected file could not be read")

                // 3. Encrypt and save using the Repository
                val result = repository.encryptAndSaveFile(fileName, fileBytes, password)

                if (result.isSuccess) {
                    _uiState.update {
                        it.copy(isLoading = false, userMessage = "File \"$fileName\" has been successfully encrypted")
                    }
                } else {
                    _uiState.update {
                        it.copy(isLoading = false, userMessage = "Error during encryption: ${result.exceptionOrNull()?.message}")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(isLoading = false, userMessage = "Error: ${e.message}")
                }
            }
        }
    }

    /**
     * Decrypt a file from the vault to export or open it.
     */
    fun decryptFile(metadata: FileMetadata, onDecrypted: (ByteArray) -> Unit) {
        val password = currentPassword ?: run {
            _uiState.update { it.copy(userMessage = "Locked vault") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = repository.decryptFile(metadata, password)
            if (result.isSuccess) {
                _uiState.update { it.copy(isLoading = false) }
                onDecrypted(result.getOrThrow())
            } else {
                _uiState.update {
                    it.copy(isLoading = false, userMessage = "Decryption error. Incorrect password?")
                }
            }
        }
    }

    /**
     * Delete the file from Room and from the internal storage.
     */
    fun deleteFile(metadata: FileMetadata) {
        viewModelScope.launch {
            val result = repository.deleteFile(metadata)
            if (result.isSuccess) {
                _uiState.update { it.copy(userMessage = "File deleted") }
            } else {
                _uiState.update { it.copy(userMessage = "Error deleting the file") }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    /**
     * A utility for extracting the actual file name from an Android URI.
     */
    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        }
        return name ?: uri.lastPathSegment
    }

    /**
     * Factory to instantiate the ViewModel by passing the Repository.
     */
    class Factory(private val repository: VaultRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(VaultViewModel::class.java)) {
                return VaultViewModel(repository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}