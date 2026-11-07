package com.tuidev.ramlock

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tuidev.ramlock.data.SecurityPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecurityPreferencesTest {

    private lateinit var repository: SecurityPreferencesRepository

    @Before
    fun setUp() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        repository = SecurityPreferencesRepository(context)
        repository.clearSecuritySettings()
    }

    @Test
    fun saveAndVerifyMasterPin_withSaltedPbkdf2_verifiesSuccessfully() = runBlocking {
        val masterPin = "MasterKey2026!"
        repository.saveSecuritySettings(
            onboardingCompleted = true,
            masterPin = masterPin,
            enableBiometrics = false,
            panicPin = null
        )

        assertTrue(repository.isOnboardingCompleted.first())
        assertTrue(repository.verifyMasterPin("MasterKey2026!"))
        assertFalse(repository.verifyMasterPin("WrongPassword"))
    }

    @Test
    fun saveAndVerifyPanicPin_withSaltedPbkdf2_verifiesSuccessfully() = runBlocking {
        val masterPin = "MasterKey2026!"
        val panicPin = "PanicWipe999"
        repository.saveSecuritySettings(
            onboardingCompleted = true,
            masterPin = masterPin,
            enableBiometrics = false,
            panicPin = panicPin
        )

        assertTrue(repository.isPanicEnabled.first())
        assertTrue(repository.verifyPanicPin("PanicWipe999"))
        assertFalse(repository.verifyPanicPin("MasterKey2026!"))
        assertFalse(repository.verifyPanicPin("WrongPanicKey"))
    }

    @Test
    fun pbkdf2Hashing_producesUniqueHashesForSamePinWithDifferentSalts() {
        val pin = "TestPin123"
        val salt1 = ByteArray(16) { 1 }
        val salt2 = ByteArray(16) { 2 }

        val hash1 = SecurityPreferencesRepository.hashPin(pin, salt1)
        val hash2 = SecurityPreferencesRepository.hashPin(pin, salt2)

        assertFalse(hash1.contentEquals(hash2))
    }
}
