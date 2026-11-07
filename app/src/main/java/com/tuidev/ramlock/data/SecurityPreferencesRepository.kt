package com.tuidev.ramlock.data

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ramlock_security_prefs")

class SecurityPreferencesRepository(private val context: Context) {

    companion object {
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_BIOMETRICS_ENABLED = booleanPreferencesKey("biometrics_enabled")

        // Salted PBKDF2 Hashing Keys
        private val KEY_MASTER_PIN_HASH = stringPreferencesKey("master_pin_hash")
        private val KEY_MASTER_PIN_SALT = stringPreferencesKey("master_pin_salt")
        private val KEY_PANIC_ENABLED = booleanPreferencesKey("panic_enabled")
        private val KEY_PANIC_PIN_HASH = stringPreferencesKey("panic_pin_hash")
        private val KEY_PANIC_PIN_SALT = stringPreferencesKey("panic_pin_salt")

        // Legacy fallback keys
        private val KEY_LEGACY_MASTER_PIN = stringPreferencesKey("master_pin")
        private val KEY_LEGACY_PANIC_PIN = stringPreferencesKey("panic_pin")

        private const val PBKDF2_ITERATIONS = 10000
        private const val PBKDF2_KEY_LENGTH = 256

        fun hashPin(pin: String, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            return factory.generateSecret(spec).encoded
        }
    }

    val isOnboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val isBiometricsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BIOMETRICS_ENABLED] ?: false
    }

    val isPanicEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PANIC_ENABLED] ?: false
    }

    val hasMasterPin: Flow<Boolean> = context.dataStore.data.map { prefs ->
        !prefs[KEY_MASTER_PIN_HASH].isNullOrBlank() || !prefs[KEY_LEGACY_MASTER_PIN].isNullOrBlank()
    }

    val hasPanicPin: Flow<Boolean> = context.dataStore.data.map { prefs ->
        (prefs[KEY_PANIC_ENABLED] ?: false) &&
                (!prefs[KEY_PANIC_PIN_HASH].isNullOrBlank() || !prefs[KEY_LEGACY_PANIC_PIN].isNullOrBlank())
    }

    suspend fun saveSecuritySettings(
        onboardingCompleted: Boolean = true,
        masterPin: String?,
        enableBiometrics: Boolean,
        panicPin: String?
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = onboardingCompleted
            prefs[KEY_BIOMETRICS_ENABLED] = enableBiometrics

            // Clean legacy plaintext keys
            prefs.remove(KEY_LEGACY_MASTER_PIN)
            prefs.remove(KEY_LEGACY_PANIC_PIN)

            // masterPin == null means "keep existing Master PIN hash"
            if (masterPin != null) {
                if (masterPin.isNotBlank()) {
                    val masterSalt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                    val masterHash = hashPin(masterPin, masterSalt)
                    prefs[KEY_MASTER_PIN_SALT] = Base64.encodeToString(masterSalt, Base64.NO_WRAP)
                    prefs[KEY_MASTER_PIN_HASH] = Base64.encodeToString(masterHash, Base64.NO_WRAP)
                } else {
                    prefs.remove(KEY_MASTER_PIN_SALT)
                    prefs.remove(KEY_MASTER_PIN_HASH)
                }
            }

            // panicPin == null means "keep existing Panic PIN hash if enabled"
            if (panicPin != null) {
                if (panicPin.isNotBlank()) {
                    val panicSalt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                    val panicHash = hashPin(panicPin, panicSalt)
                    prefs[KEY_PANIC_ENABLED] = true
                    prefs[KEY_PANIC_PIN_SALT] = Base64.encodeToString(panicSalt, Base64.NO_WRAP)
                    prefs[KEY_PANIC_PIN_HASH] = Base64.encodeToString(panicHash, Base64.NO_WRAP)
                } else {
                    prefs[KEY_PANIC_ENABLED] = false
                    prefs.remove(KEY_PANIC_PIN_SALT)
                    prefs.remove(KEY_PANIC_PIN_HASH)
                }
            }
        }
    }

    suspend fun verifyMasterPin(candidatePin: String): Boolean {
        var isValid = false
        context.dataStore.edit { prefs ->
            val storedHash = prefs[KEY_MASTER_PIN_HASH]
            val storedSalt = prefs[KEY_MASTER_PIN_SALT]

            if (!storedHash.isNullOrBlank() && !storedSalt.isNullOrBlank()) {
                try {
                    val saltBytes = Base64.decode(storedSalt, Base64.NO_WRAP)
                    val expectedHash = Base64.decode(storedHash, Base64.NO_WRAP)
                    val candidateHash = hashPin(candidatePin, saltBytes)
                    isValid = MessageDigest.isEqual(candidateHash, expectedHash)
                } catch (_: Exception) {
                    isValid = false
                }
            } else {
                // Legacy plaintext fallback
                val legacyPin = prefs[KEY_LEGACY_MASTER_PIN]
                if (!legacyPin.isNullOrBlank() && candidatePin == legacyPin) {
                    isValid = true
                    // Auto-migrate legacy PIN to PBKDF2 hash
                    val masterSalt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                    val masterHash = hashPin(candidatePin, masterSalt)
                    prefs[KEY_MASTER_PIN_SALT] = Base64.encodeToString(masterSalt, Base64.NO_WRAP)
                    prefs[KEY_MASTER_PIN_HASH] = Base64.encodeToString(masterHash, Base64.NO_WRAP)
                    prefs.remove(KEY_LEGACY_MASTER_PIN)
                }
            }
        }
        return isValid
    }

    suspend fun verifyPanicPin(candidatePin: String): Boolean {
        var isMatch = false
        context.dataStore.edit { prefs ->
            val panicEnabled = prefs[KEY_PANIC_ENABLED] ?: false
            if (!panicEnabled) {
                isMatch = false
                return@edit
            }

            val storedHash = prefs[KEY_PANIC_PIN_HASH]
            val storedSalt = prefs[KEY_PANIC_PIN_SALT]

            if (!storedHash.isNullOrBlank() && !storedSalt.isNullOrBlank()) {
                try {
                    val saltBytes = Base64.decode(storedSalt, Base64.NO_WRAP)
                    val expectedHash = Base64.decode(storedHash, Base64.NO_WRAP)
                    val candidateHash = hashPin(candidatePin, saltBytes)
                    isMatch = MessageDigest.isEqual(candidateHash, expectedHash)
                } catch (_: Exception) {
                    isMatch = false
                }
            } else {
                // Legacy plaintext fallback
                val legacyPanicPin = prefs[KEY_LEGACY_PANIC_PIN]
                if (!legacyPanicPin.isNullOrBlank() && candidatePin == legacyPanicPin) {
                    isMatch = true
                    val panicSalt = ByteArray(16).apply { SecureRandom().nextBytes(this) }
                    val panicHash = hashPin(candidatePin, panicSalt)
                    prefs[KEY_PANIC_PIN_SALT] = Base64.encodeToString(panicSalt, Base64.NO_WRAP)
                    prefs[KEY_PANIC_PIN_HASH] = Base64.encodeToString(panicHash, Base64.NO_WRAP)
                    prefs.remove(KEY_LEGACY_PANIC_PIN)
                }
            }
        }
        return isMatch
    }

    suspend fun clearSecuritySettings() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
