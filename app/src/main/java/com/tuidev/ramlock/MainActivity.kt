package com.tuidev.ramlock

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.tuidev.ramlock.data.AppDatabase
import com.tuidev.ramlock.data.SecurityPreferencesRepository
import com.tuidev.ramlock.data.VaultFileStorage
import com.tuidev.ramlock.data.VaultRepository
import com.tuidev.ramlock.ui.OnboardingScreen
import com.tuidev.ramlock.ui.VaultScreen
import com.tuidev.ramlock.ui.VaultViewModel
import com.tuidev.ramlock.ui.theme.RamLockTheme

class MainActivity : FragmentActivity() {

    private val requestCodeMap = mutableMapOf<Int, Int>()

    private val viewModel: VaultViewModel by viewModels {
        val database = AppDatabase.getDatabase(applicationContext)
        val storage = VaultFileStorage(applicationContext)
        val repository = VaultRepository(database.fileMetadataDao(), storage)
        val securityPrefs = SecurityPreferencesRepository(applicationContext)
        VaultViewModel.Factory(repository, securityPrefs)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        if ((requestCode != -1) && ((requestCode and -0x10000) != 0)) {
            var maskedCode = requestCode and 0xFFFF
            if (maskedCode == 0) maskedCode = 1
            while (requestCodeMap.containsKey(maskedCode)) {
                maskedCode = (maskedCode % 0xFFFF) + 1
            }
            requestCodeMap[maskedCode] = requestCode
            super.startActivityForResult(intent, maskedCode, options)
        } else {
            super.startActivityForResult(intent, requestCode, options)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val originalCode = requestCodeMap.remove(requestCode)
        if (originalCode != null) {
            if (!activityResultRegistry.dispatchResult(originalCode, resultCode, data)) {
                super.onActivityResult(requestCode, resultCode, data)
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("RamLock", "Uncaught exception in thread ${thread.name}", throwable)
        }

        // Block screenshots, screen recording, and recent apps preview thumbnails
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        enableEdgeToEdge()

        setContent {
            RamLockTheme {
                val isOnboardingCompleted by viewModel.isOnboardingCompleted.collectAsState()

                if (!isOnboardingCompleted) {
                    OnboardingScreen(
                        onFinishSetup = { pin, enableBiometrics, panicPin ->
                            viewModel.completeOnboarding(pin, enableBiometrics, panicPin)
                        }
                    )
                } else {
                    VaultScreen(viewModel = viewModel)
                }
            }
        }
    }
}
