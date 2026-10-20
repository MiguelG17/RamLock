package com.tuidev.ramlock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.tuidev.ramlock.data.AppDatabase
import com.tuidev.ramlock.data.VaultFileStorage
import com.tuidev.ramlock.data.VaultRepository
import com.tuidev.ramlock.ui.VaultScreen
import com.tuidev.ramlock.ui.VaultViewModel
import com.tuidev.ramlock.ui.theme.RamLockTheme

class MainActivity : ComponentActivity() {

    // Instantiate dependencies and attach the ViewModel
    private val viewModel: VaultViewModel by viewModels {
        val database = AppDatabase.getDatabase(applicationContext)
        val storage = VaultFileStorage(applicationContext)
        val repository = VaultRepository(database.fileMetadataDao(), storage)
        VaultViewModel.Factory(repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            RamLockTheme {
                VaultScreen(viewModel = viewModel)
            }
        }
    }
}