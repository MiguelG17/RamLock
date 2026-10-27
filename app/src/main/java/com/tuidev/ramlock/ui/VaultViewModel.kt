package com.tuidev.ramlock.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tuidev.ramlock.R
import com.tuidev.ramlock.data.FileMetadata
import com.tuidev.ramlock.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

class VaultViewModel(
    private val repository: VaultRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    // The master password exists solely in RAM during the session
    private var currentPassword: String? = null

    init {
        viewModelScope.launch {
            repository.allFiles.collect { fileList ->
                _uiState.update { it.copy(files = fileList) }
            }
        }
    }

    /**
     * Checks if the physical encrypted file is missing from disk storage.
     */
    fun isFileOrphaned(encryptedFileName: String): Boolean {
        return repository.isFileOrphaned(encryptedFileName)
    }

    /**
     * Unlock the vault by temporarily storing the password in RAM.
     */
    fun unlockVault(password: String) {
        if (password.isNotBlank()) {
            currentPassword = password
            _uiState.update { it.copy(isUnlocked = true, userMessage = null) }
        } else {
            _uiState.update { it.copy(userMessage = UiText.StringResource(R.string.error_empty_password)) }
        }
    }

    /**
     * Lock the vault and wipe the session password from RAM memory.
     */
    fun lockVault() {
        currentPassword = null
        _uiState.update { it.copy(isUnlocked = false) }
    }

    /**
     * Reads the file selected by the user using SAF, encrypts it with Rust using the specified file password,
     * saves it into the private vault, and attempts to delete the original source file.
     */
    fun importAndEncryptFile(context: Context, uri: Uri, filePassword: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Pre-check file size to prevent OutOfMemoryError
                val maxSizeBytes = 150L * 1024 * 1024 // 150 MB limit
                val fileSize = getFileSize(context, uri)
                if (fileSize > maxSizeBytes) {
                    val sizeMb = String.format(Locale.US, "%.1f", fileSize / (1024f * 1024f))
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            userMessage = UiText.StringResource(R.string.error_file_too_large, sizeMb)
                        )
                    }
                    return@launch
                }

                // 1. Retrieve original name
                val fileName = getFileName(context, uri) ?: "unnamed_file"

                // 2. Read bytes using ContentResolver
                val fileBytes = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.readBytes()
                } ?: throw IllegalArgumentException(context.getString(R.string.error_could_not_read_file))

                // 3. Encrypt and save using Repository (storing original uri string)
                val result = repository.encryptAndSaveFile(
                    originalName = fileName,
                    fileBytes = fileBytes,
                    password = filePassword,
                    originalPath = uri.toString()
                )

                if (result.isSuccess) {
                    // 4. Move file: attempt to delete the original file
                    val originalDeleted = deleteOriginalUri(context, uri)
                    val msg = if (originalDeleted) {
                        UiText.StringResource(R.string.msg_file_encrypted_and_moved, fileName)
                    } else {
                        UiText.StringResource(R.string.msg_file_encrypted, fileName)
                    }
                    _uiState.update {
                        it.copy(isLoading = false, userMessage = msg)
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            userMessage = UiText.StringResource(
                                R.string.error_during_encryption,
                                result.exceptionOrNull()?.message ?: ""
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        userMessage = UiText.StringResource(
                            R.string.error_generic,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    /**
     * Decrypts a file in memory for extraction out of the vault.
     */
    fun decryptForExtraction(
        metadata: FileMetadata,
        password: String,
        onSuccess: (ByteArray) -> Unit
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val result = repository.decryptFile(metadata, password)

            if (result.isSuccess) {
                _uiState.update { it.copy(isLoading = false) }
                onSuccess(result.getOrThrow())
            } else {
                val exception = result.exceptionOrNull()
                val message = if (exception is FileNotFoundException) {
                    UiText.StringResource(R.string.error_file_not_found_in_storage)
                } else {
                    UiText.StringResource(R.string.error_incorrect_password)
                }
                _uiState.update {
                    it.copy(isLoading = false, userMessage = message)
                }
            }
        }
    }

    /**
     * Finalizes extraction by writing the decrypted bytes to the destination URI and removing the file from the vault.
     */
    fun finalizeExtraction(
        context: Context,
        destinationUri: Uri,
        bytes: ByteArray,
        metadata: FileMetadata
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            try {
                context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                    outputStream.write(bytes)
                } ?: throw IllegalArgumentException(context.getString(R.string.error_could_not_write_destination))

                // Delete from vault repository (Room DB + internal file)
                repository.deleteFile(metadata)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        userMessage = UiText.StringResource(R.string.msg_file_extracted, metadata.originalName)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        userMessage = UiText.StringResource(
                            R.string.error_saving_extracted_file,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    /**
     * Attempts to delete the source URI to complete the "move" operation.
     */
    private fun deleteOriginalUri(context: Context, uri: Uri): Boolean {
        return try {
            if (DocumentsContract.isDocumentUri(context, uri)) {
                DocumentsContract.deleteDocument(context.contentResolver, uri)
            } else {
                val rows = context.contentResolver.delete(uri, null, null)
                if (rows > 0) true else {
                    val path = uri.path
                    if (uri.scheme == "file" && path != null) {
                        File(path).delete()
                    } else {
                        false
                    }
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Delete the file metadata from Room and physical file from internal storage.
     */
    fun deleteFile(metadata: FileMetadata) {
        viewModelScope.launch {
            val result = repository.deleteFile(metadata)
            if (result.isSuccess) {
                _uiState.update { it.copy(userMessage = UiText.StringResource(R.string.msg_file_deleted)) }
            } else {
                _uiState.update { it.copy(userMessage = UiText.StringResource(R.string.error_deleting_file)) }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    /**
     * Utility for extracting the actual file name from an Android URI.
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
     * Utility for querying file size efficiently from URI metadata.
     */
    private fun getFileSize(context: Context, uri: Uri): Long {
        var size: Long = -1
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        if (size <= 0 && uri.scheme == "file" && uri.path != null) {
            size = File(uri.path!!).length()
        }
        return size
    }

    /** Called when user taps a file card: triggers password prompt dialog. */
    fun onFileClick(metadata: FileMetadata) {
        _uiState.update { it.copy(fileToOpen = metadata) }
    }

    /** Called when user cancels file password dialog. */
    fun dismissOpenDialog() {
        _uiState.update { it.copy(fileToOpen = null) }
    }

    /** Called when user closes file viewer. */
    fun closeViewer() {
        _uiState.update { it.copy(openedFile = null) }
    }

    /** Decrypts and opens a file using the provided password. */
    fun openFile(metadata: FileMetadata, password: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, fileToOpen = null) }

            val result = repository.decryptFile(metadata, password)

            if (result.isSuccess) {
                val category = getFileCategory(metadata.originalName)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        openedFile = OpenedFile(
                            name = metadata.originalName,
                            category = category,
                            bytes = result.getOrThrow()
                        )
                    )
                }
            } else {
                val exception = result.exceptionOrNull()
                val message = if (exception is FileNotFoundException) {
                    UiText.StringResource(R.string.error_file_not_found_in_storage)
                } else {
                    UiText.StringResource(R.string.error_incorrect_password)
                }
                _uiState.update {
                    it.copy(isLoading = false, userMessage = message)
                }
            }
        }
    }

    /** Factory to instantiate ViewModel with repository. */
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
