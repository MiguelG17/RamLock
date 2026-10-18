package com.tuidev.ramlock

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tuidev.ramlock.data.AppDatabase
import com.tuidev.ramlock.data.VaultFileStorage
import com.tuidev.ramlock.data.VaultRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultRepositoryTest {

    private lateinit var database: AppDatabase
    private lateinit var storage: VaultFileStorage
    private lateinit var repository: VaultRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        // 1. An in-memory database so that it does not interfere with real data
        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        ).allowMainThreadQueries().build()

        storage = VaultFileStorage(context)
        repository = VaultRepository(database.fileMetadataDao(), storage)
    }

    @After
    fun tearDown() {
        database.close()

        // Delete the ‘vault’ folder created during the tests
        val vaultDir = File(context.filesDir, "vault")
        if (vaultDir.exists()) {
            vaultDir.deleteRecursively()
        }
    }

    @Test
    fun encryptAndSaveFile_savesToDatabaseAndDisk() = runBlocking {
        val fileName = "confidential_contract.pdf"
        val originalBytes = "Top-secret contents of the contract".toByteArray(Charsets.UTF_8)
        val password = "MasterKey2026!"

        // 1. Save and Encrypt
        val result = repository.encryptAndSaveFile(fileName, originalBytes, password)

        assertTrue(result.isSuccess)
        val savedMetadata = result.getOrThrow()
        assertEquals(fileName, savedMetadata.originalName)

        // 2. Check that Room has the file on file
        val filesInRoom = repository.allFiles.first()
        assertEquals(1, filesInRoom.size)
        assertEquals(fileName, filesInRoom[0].originalName)

        // 3. Check that the encrypted file physically exists on the disk
        val encryptedDiskFile = File(context.filesDir, "vault/${savedMetadata.encryptedFileName}")
        assertTrue(encryptedDiskFile.exists())

        // The content of the disc must not be the same as the plain text
        assertFalse(encryptedDiskFile.readBytes().contentEquals(originalBytes))
    }

    @Test
    fun decryptFile_withCorrectPassword_recoversOriginalData() = runBlocking {
        val fileName = "holidays_photo.jpg"
        // We simulate bytes from an image
        val originalBytes = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60)
        val password = "MyPasswordSecure"

        // We save it first
        val saveResult = repository.encryptAndSaveFile(fileName, originalBytes, password)
        val metadata = saveResult.getOrThrow()

        // 2. We decipher
        val decryptResult = repository.decryptFile(metadata, password)

        assertTrue(decryptResult.isSuccess)
        val decryptedBytes = decryptResult.getOrThrow()

        // The recovered bytes must be exactly the same as the originals
        assertArrayEquals(originalBytes, decryptedBytes)
    }

    @Test
    fun deleteFile_removesFromBothDatabaseAndDisk() = runBlocking {
        val fileName = "document_to_be_deleted.txt"
        val originalBytes = "Delete me soon".toByteArray(Charsets.UTF_8)
        val password = "Clave1234Password"

        // Save
        val saveResult = repository.encryptAndSaveFile(fileName, originalBytes, password)
        val metadata = saveResult.getOrThrow()

        val diskFile = File(context.filesDir, "vault/${metadata.encryptedFileName}")
        assertTrue("The file must exist on the disk after it has been saved", diskFile.exists())

        // 2. We delete via the repository
        val deleteResult = repository.deleteFile(metadata)
        assertTrue(deleteResult.isSuccess)

        // 3. We have checked that Room no longer has it
        val filesInRoom = repository.allFiles.first()
        assertTrue("The database must be empty", filesInRoom.isEmpty())

        // 4. We check that it is NO LONGER on the disk
        assertFalse("The physical file must have been deleted from the disk", diskFile.exists())
    }
}