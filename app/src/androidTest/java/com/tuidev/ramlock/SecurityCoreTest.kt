package com.tuidev.ramlock

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tuidev.ramlock.security.SecurityCore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityCoreTest {

    @Test
    fun helloFromRust_returnsExpectedGreeting() {
        val message = SecurityCore.helloFromRust()
        assertNotNull(message)
        assertTrue(message.contains("Rust"))
    }

    @Test
    fun encryptAndDecrypt_returnsOriginalData() {
        val password = "SuperSecretPassword123!"
        val originalText = "RamLock: Secure in-memory encryption using Rust and Kotlin."
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)

        // 1. Encryption with Rust
        val encryptedBytes = SecurityCore.encryptData(password, originalBytes)

        assertNotNull(encryptedBytes)
        // The encrypted content must be different from the original
        assertFalse(originalBytes.contentEquals(encryptedBytes))
        // Argon2 salt (16 bytes) + AES-GCM nonce (12 bytes) + authentication tag (16 bytes) = 44 bytes total overhead
        assertEquals(originalBytes.size + 16 + 12 + 16, encryptedBytes.size)

        // 2. Decoded using Rust
        val decryptedBytes = SecurityCore.decryptData(password, encryptedBytes)
        assertNotNull(decryptedBytes)
        val decryptedText = String(decryptedBytes!!, Charsets.UTF_8)

        // We check that we have retrieved the exact original message
        assertEquals(originalText, decryptedText)
        assertArrayEquals(originalBytes, decryptedBytes)
    }

    @Test
    fun encryptAndDecrypt_binaryPayload() {
        val password = "AnotherPassword_2026"
        // We simulate a 64 KB binary file
        val originalBytes = ByteArray(64 * 1024) { (it % 256).toByte() }

        val encryptedBytes = SecurityCore.encryptData(password, originalBytes)
        val decryptedBytes = SecurityCore.decryptData(password, encryptedBytes)

        assertArrayEquals(originalBytes, decryptedBytes)
    }
}
