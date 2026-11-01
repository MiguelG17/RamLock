package com.tuidev.ramlock.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ramlock_security_prefs")

class SecurityPreferencesRepository(private val context: Context) {

    companion object {
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_BIOMETRICS_ENABLED = booleanPreferencesKey("biometrics_enabled")
        private val KEY_MASTER_PIN = stringPreferencesKey("master_pin")
        private val KEY_PANIC_ENABLED = booleanPreferencesKey("panic_enabled")
        private val KEY_PANIC_PIN = stringPreferencesKey("panic_pin")
    }

    val isOnboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    val isBiometricsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BIOMETRICS_ENABLED] ?: false
    }

    val masterPin: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_MASTER_PIN]
    }

    val isPanicEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_PANIC_ENABLED] ?: false
    }

    val panicPin: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[KEY_PANIC_PIN]
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

            if (!masterPin.isNullOrBlank()) {
                prefs[KEY_MASTER_PIN] = masterPin
            } else {
                prefs.remove(KEY_MASTER_PIN)
            }

            if (!panicPin.isNullOrBlank()) {
                prefs[KEY_PANIC_ENABLED] = true
                prefs[KEY_PANIC_PIN] = panicPin
            } else {
                prefs[KEY_PANIC_ENABLED] = false
                prefs.remove(KEY_PANIC_PIN)
            }
        }
    }

    suspend fun clearSecuritySettings() {
        context.dataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
