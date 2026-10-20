package com.tuidev.ramlock.data

import com.tuidev.ramlock.security.SecurityCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class VaultRepository(
    private val dao: FileMetadataDao,
    private val storage: VaultFileStorage
) {

    // 1. Reactive file flow for the UI
    val allFiles: Flow<List<FileMetadata>> = dao.getAllFiles()

    /**
     * Encrypt the bytes using Rust, save them to the private storage and record the metadata in Room.
     */
    suspend fun encryptAndSaveFile(
        originalName: String,
        fileBytes: ByteArray,
        password: String
    ): Result<FileMetadata> = withContext(Dispatchers.IO) {
        try {
            // A. Native in-memory encryption with Rust
            val encryptedBytes = SecurityCore.encryptData(password, fileBytes)

            // B. Physical storage in the internal sandbox
            val encryptedFileName = storage.saveEncryptedFile(encryptedBytes)

            // C. Registration in the Room database
            val metadata = FileMetadata(
                originalName = originalName,
                encryptedFileName = encryptedFileName,
                fileSize = fileBytes.size.toLong()
            )
            val generatedId = dao.insertFile(metadata)

            Result.success(metadata.copy(id = generatedId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Read the bytes from the private disk and decrypt them using Rust with the given password.
     */
    suspend fun decryptFile(
        metadata: FileMetadata,
        password: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            // A. Read encrypted bytes from sandbox
            val encryptedBytes = storage.readEncryptedFile(metadata.encryptedFileName)

            // B. Decrypt with Rust (returns null if password is invalid)
            val decryptedBytes = SecurityCore.decryptData(password, encryptedBytes)
                ?: return@withContext Result.failure(IllegalArgumentException("Incorrect password or corrupted file"))

            Result.success(decryptedBytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes the physical file from the disk and its entry in the database.
     */
    suspend fun deleteFile(metadata: FileMetadata): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // A. Delete physical file
            storage.deleteEncryptedFile(metadata.encryptedFileName)

            // B. Delete from Room
            dao.deleteFile(metadata)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}