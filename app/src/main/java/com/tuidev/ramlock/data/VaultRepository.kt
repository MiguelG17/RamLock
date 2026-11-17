package com.tuidev.ramlock.data

import android.os.ParcelFileDescriptor
import com.tuidev.ramlock.security.SecurityCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class VaultRepository(
    private val dao: FileMetadataDao,
    private val storage: VaultFileStorage
) {

    // Reactive file flow for the UI
    val allFiles: Flow<List<FileMetadata>> = dao.getAllFiles()

    /**
     * Checks if the physical encrypted file is missing from disk.
     */
    fun isFileOrphaned(encryptedFileName: String): Boolean {
        return !storage.fileExists(encryptedFileName)
    }

    /**
     * Streams encryption from a source ParcelFileDescriptor directly into an encrypted file in internal storage,
     * recording metadata in Room. Memory consumption is constant (<1 MB) regardless of file size.
     */
    suspend fun encryptAndSaveFileStream(
        originalName: String,
        sourcePfd: ParcelFileDescriptor,
        password: String,
        fileSize: Long,
        originalPath: String? = null
    ): Result<FileMetadata> = withContext(Dispatchers.IO) {
        val targetFile = storage.createEncryptedFile()
        try {
            val targetPfd = ParcelFileDescriptor.open(
                targetFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
            )

            val success = targetPfd.use { outStream ->
                SecurityCore.encryptStream(password, sourcePfd.fd, outStream.fd)
            }

            if (!success) {
                targetFile.delete()
                return@withContext Result.failure(IllegalStateException("Failed to encrypt file via stream"))
            }

            val metadata = FileMetadata(
                originalName = originalName,
                encryptedFileName = targetFile.name,
                fileSize = fileSize,
                originalPath = originalPath
            )
            val generatedId = dao.insertFile(metadata)

            Result.success(metadata.copy(id = generatedId))
        } catch (e: Exception) {
            targetFile.delete()
            Result.failure(e)
        }
    }

    /**
     * Decrypts an encrypted vault file directly into a destination ParcelFileDescriptor via streaming.
     * Supports both modern streaming (RMLK) and legacy block formats.
     */
    suspend fun decryptToFileStream(
        metadata: FileMetadata,
        password: String,
        destinationPfd: ParcelFileDescriptor
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (storage.isStreamEncrypted(metadata.encryptedFileName)) {
                val encPfd = storage.openEncryptedFilePfd(metadata.encryptedFileName)
                val success = encPfd.use { inStream ->
                    SecurityCore.decryptStream(password, inStream.fd, destinationPfd.fd)
                }
                if (!success) {
                    return@withContext Result.failure(IllegalArgumentException("Incorrect password or corrupted stream file"))
                }
                Result.success(Unit)
            } else {
                // Fallback for legacy block-encrypted files
                val encryptedBytes = storage.readEncryptedFile(metadata.encryptedFileName)
                val decryptedBytes = SecurityCore.decryptData(password, encryptedBytes)
                    ?: return@withContext Result.failure(IllegalArgumentException("Incorrect password or corrupted file"))

                FileOutputStream(destinationPfd.fileDescriptor).use { output ->
                    output.write(decryptedBytes)
                    output.flush()
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Encrypt the bytes using Rust, save them to private storage and record metadata in Room.
     */
    suspend fun encryptAndSaveFile(
        originalName: String,
        fileBytes: ByteArray,
        password: String,
        originalPath: String? = null
    ): Result<FileMetadata> = withContext(Dispatchers.IO) {
        try {
            val targetFile = storage.createEncryptedFile()
            val tempPlainFile = File.createTempFile("temp_in_", ".bin", targetFile.parentFile)
            tempPlainFile.writeBytes(fileBytes)

            val inPfd = ParcelFileDescriptor.open(tempPlainFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val outPfd = ParcelFileDescriptor.open(
                targetFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
            )

            val success = inPfd.use { inStream ->
                outPfd.use { outStream ->
                    SecurityCore.encryptStream(password, inStream.fd, outStream.fd)
                }
            }
            tempPlainFile.delete()

            if (!success) {
                targetFile.delete()
                return@withContext Result.failure(IllegalStateException("Failed to encrypt file data"))
            }

            val metadata = FileMetadata(
                originalName = originalName,
                encryptedFileName = targetFile.name,
                fileSize = fileBytes.size.toLong(),
                originalPath = originalPath
            )
            val generatedId = dao.insertFile(metadata)

            Result.success(metadata.copy(id = generatedId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Read bytes from private disk and decrypt them using Rust with the given password.
     * Handles both modern RMLK streaming files and legacy block-encrypted files.
     */
    suspend fun decryptFile(
        metadata: FileMetadata,
        password: String
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            if (storage.isStreamEncrypted(metadata.encryptedFileName)) {
                val encFile = storage.getEncryptedFile(metadata.encryptedFileName)
                val tempDecFile = File.createTempFile("temp_dec_", ".bin", encFile.parentFile)
                val encPfd = storage.openEncryptedFilePfd(metadata.encryptedFileName)
                val decPfd = ParcelFileDescriptor.open(
                    tempDecFile,
                    ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
                )

                val success = encPfd.use { inStream ->
                    decPfd.use { outStream ->
                        SecurityCore.decryptStream(password, inStream.fd, outStream.fd)
                    }
                }

                if (!success) {
                    tempDecFile.delete()
                    return@withContext Result.failure(IllegalArgumentException("Incorrect password or corrupted file"))
                }

                val decryptedBytes = tempDecFile.readBytes()
                tempDecFile.delete()
                Result.success(decryptedBytes)
            } else {
                // A. Read encrypted bytes from sandbox
                val encryptedBytes = storage.readEncryptedFile(metadata.encryptedFileName)

                // B. Decrypt with Rust (returns null if password is invalid)
                val decryptedBytes = SecurityCore.decryptData(password, encryptedBytes)
                    ?: return@withContext Result.failure(IllegalArgumentException("Incorrect password or corrupted file"))

                Result.success(decryptedBytes)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Deletes the physical file from disk and its entry in database.
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

    /**
     * Complete panic wipe: Purges all files from private storage and wipes Room DB records.
     */
    suspend fun wipeAllVaultFiles(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            storage.clearAllFiles()
            dao.deleteAllFiles()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
