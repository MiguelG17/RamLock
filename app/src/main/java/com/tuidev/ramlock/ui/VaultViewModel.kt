package com.tuidev.ramlock.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.tuidev.ramlock.R
import com.tuidev.ramlock.data.FileMetadata
import com.tuidev.ramlock.data.SecurityPreferencesRepository
import com.tuidev.ramlock.data.VaultRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.Locale

class VaultViewModel(
    private val repository: VaultRepository,
    private val securityPreferences: SecurityPreferencesRepository
) : ViewModel() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e("RamLock", "Unhandled exception in VaultViewModel coroutine", throwable)
        _uiState.update {
            it.copy(
                isLoading = false,
                userMessage = UiText.StringResource(
                    R.string.error_generic,
                    throwable.message ?: ""
                )
            )
        }
    }

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    // Security preferences reactive state flows
    val isOnboardingCompleted: StateFlow<Boolean> = securityPreferences.isOnboardingCompleted
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isBiometricsEnabled: StateFlow<Boolean> = securityPreferences.isBiometricsEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val masterPin: StateFlow<String?> = securityPreferences.masterPin
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isPanicEnabled: StateFlow<Boolean> = securityPreferences.isPanicEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val panicPin: StateFlow<String?> = securityPreferences.panicPin
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Master session password stored exclusively in RAM
    private var currentPassword: String? = null

    init {
        viewModelScope.launch(exceptionHandler) {
            repository.allFiles.collect { fileList ->
                _uiState.update { it.copy(files = fileList) }
            }
        }
    }

    /**
     * Completes initial onboarding and saves security settings.
     */
    fun completeOnboarding(pin: String?, enableBiometrics: Boolean, panicPin: String?) {
        viewModelScope.launch(exceptionHandler) {
            securityPreferences.saveSecuritySettings(
                onboardingCompleted = true,
                masterPin = pin,
                enableBiometrics = enableBiometrics,
                panicPin = panicPin
            )
            if (!pin.isNullOrBlank()) {
                currentPassword = pin
                _uiState.update { it.copy(isUnlocked = true) }
            }
        }
    }

    /**
     * Saves updated security settings from Settings Dialog.
     */
    fun saveSecuritySettings(pin: String?, enableBiometrics: Boolean, panicPin: String?) {
        viewModelScope.launch(exceptionHandler) {
            securityPreferences.saveSecuritySettings(
                onboardingCompleted = true,
                masterPin = pin,
                enableBiometrics = enableBiometrics,
                panicPin = panicPin
            )
            _uiState.update {
                it.copy(userMessage = UiText.StringResource(R.string.settings_save_success))
            }
        }
    }

    /**
     * Unlock vault with password or biometric success. Checks for Panic Key.
     */
    fun unlockVault(password: String) {
        viewModelScope.launch(exceptionHandler) {
            val configuredPanic = securityPreferences.panicPin.first()
            val panicActive = securityPreferences.isPanicEnabled.first()

            // Panic Key check: If entered key matches configured Panic Key, wipe everything instantly and enter empty vault
            if (panicActive && !configuredPanic.isNullOrBlank() && password == configuredPanic) {
                triggerPanicWipe(password)
                return@launch
            }

            val configuredMaster = securityPreferences.masterPin.first()
            if (configuredMaster.isNullOrBlank() || password == configuredMaster) {
                currentPassword = password
                _uiState.update { it.copy(isUnlocked = true, userMessage = null) }
            } else {
                _uiState.update {
                    it.copy(
                        isUnlocked = false,
                        userMessage = UiText.StringResource(R.string.error_incorrect_password)
                    )
                }
            }
        }
    }

    /**
     * Unlock vault session via Biometric authentication success.
     */
    fun unlockVaultWithBiometrics() {
        viewModelScope.launch(exceptionHandler) {
            val pin = securityPreferences.masterPin.first()
            if (!pin.isNullOrBlank()) {
                currentPassword = pin
            } else {
                currentPassword = "biometric_session_key"
            }
            _uiState.update { it.copy(isUnlocked = true, userMessage = null) }
        }
    }

    /**
     * Emergency Panic Wipe: Deletes all files physically and in Room DB, then unlocks into an empty vault.
     */
    private suspend fun triggerPanicWipe(password: String) {
        _uiState.update { it.copy(isLoading = true) }
        repository.wipeAllVaultFiles()
        currentPassword = password
        _uiState.update {
            it.copy(
                isUnlocked = true,
                isLoading = false,
                files = emptyList(),
                userMessage = null
            )
        }
    }

    /**
     * Checks if the physical encrypted file is missing from disk storage.
     */
    fun isFileOrphaned(encryptedFileName: String): Boolean {
        return try {
            repository.isFileOrphaned(encryptedFileName)
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Lock the vault and wipe session password from RAM memory.
     */
    fun lockVault() {
        currentPassword = null
        _uiState.update { it.copy(isUnlocked = false) }
    }

    /**
     * Reads the file selected by user using SAF, encrypts it with Rust using specified file password,
     * saves it into private vault, and attempts to delete original source file.
     */
    fun importAndEncryptFile(context: Context, uri: Uri, filePassword: String) {
        viewModelScope.launch(exceptionHandler) {
            _uiState.update { it.copy(isLoading = true) }

            try {
                // Move all heavy I/O off the main thread to prevent ANR
                val importResult = withContext(Dispatchers.IO) {
                    // Pre-check file size to prevent OutOfMemoryError
                    val maxSizeBytes = 150L * 1024 * 1024 // 150 MB limit
                    val fileSize = getFileSize(context, uri)
                    if (fileSize > maxSizeBytes) {
                        val sizeMb = String.format(Locale.US, "%.1f", fileSize / (1024f * 1024f))
                        return@withContext ImportResult.TooLarge(sizeMb)
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
                        // 4. Move file: attempt to delete original file
                        val originalDeleted = deleteOriginalUri(context, uri)
                        ImportResult.Success(fileName, originalDeleted)
                    } else {
                        ImportResult.EncryptionError(result.exceptionOrNull()?.message ?: "")
                    }
                }

                // Update UI on main thread based on result
                when (importResult) {
                    is ImportResult.TooLarge -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                userMessage = UiText.StringResource(R.string.error_file_too_large, importResult.sizeMb)
                            )
                        }
                    }
                    is ImportResult.Success -> {
                        val msg = if (importResult.originalDeleted) {
                            UiText.StringResource(R.string.msg_file_encrypted_and_moved, importResult.fileName)
                        } else {
                            UiText.StringResource(R.string.msg_file_encrypted, importResult.fileName)
                        }
                        _uiState.update {
                            it.copy(isLoading = false, userMessage = msg)
                        }
                    }
                    is ImportResult.EncryptionError -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                userMessage = UiText.StringResource(
                                    R.string.error_during_encryption,
                                    importResult.errorMessage
                                )
                            )
                        }
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

    private sealed class ImportResult {
        data class TooLarge(val sizeMb: String) : ImportResult()
        data class Success(val fileName: String, val originalDeleted: Boolean) : ImportResult()
        data class EncryptionError(val errorMessage: String) : ImportResult()
    }

    /**
     * Decrypts a file in memory for extraction out of vault.
     */
    fun decryptForExtraction(
        metadata: FileMetadata,
        password: String,
        onSuccess: (ByteArray) -> Unit
    ) {
        viewModelScope.launch(exceptionHandler) {
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
     * Finalizes extraction by writing decrypted bytes to destination URI and removing file from vault.
     */
    fun finalizeExtraction(
        context: Context,
        destinationUri: Uri,
        bytes: ByteArray,
        metadata: FileMetadata
    ) {
        viewModelScope.launch(exceptionHandler) {
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
     * Attempts to delete source URI to complete "move" operation.
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
     * Delete file metadata from Room and physical file from internal storage.
     */
    fun deleteFile(metadata: FileMetadata) {
        viewModelScope.launch(exceptionHandler) {
            try {
                val result = repository.deleteFile(metadata)
                if (result.isSuccess) {
                    _uiState.update { it.copy(userMessage = UiText.StringResource(R.string.msg_file_deleted)) }
                } else {
                    _uiState.update { it.copy(userMessage = UiText.StringResource(R.string.error_deleting_file)) }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(userMessage = UiText.StringResource(R.string.error_generic, e.message ?: ""))
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

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

    fun onFileClick(metadata: FileMetadata) {
        _uiState.update { it.copy(fileToOpen = metadata) }
    }

    fun dismissOpenDialog() {
        _uiState.update { it.copy(fileToOpen = null) }
    }

    fun closeViewer() {
        _uiState.update { it.copy(openedFile = null) }
    }

    fun openFile(metadata: FileMetadata, password: String) {
        viewModelScope.launch(exceptionHandler) {
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

    /** Factory to instantiate ViewModel with repository & security preferences. */
    class Factory(
        private val repository: VaultRepository,
        private val securityPreferences: SecurityPreferencesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(VaultViewModel::class.java)) {
                return VaultViewModel(repository, securityPreferences) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
