package com.tuidev.ramlock

import com.tuidev.ramlock.data.SecurityPreferencesRepository
import com.tuidev.ramlock.data.VaultRepository
import com.tuidev.ramlock.ui.UiText
import com.tuidev.ramlock.ui.VaultViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

@OptIn(ExperimentalCoroutinesApi::class)
class VaultViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var mockRepository: VaultRepository
    private lateinit var mockSecurityPrefs: SecurityPreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockRepository = mock(VaultRepository::class.java)
        mockSecurityPrefs = mock(SecurityPreferencesRepository::class.java)

        `when`(mockRepository.allFiles).thenReturn(flowOf(emptyList()))
        `when`(mockSecurityPrefs.isOnboardingCompleted).thenReturn(flowOf(true))
        `when`(mockSecurityPrefs.isBiometricsEnabled).thenReturn(flowOf(false))
        `when`(mockSecurityPrefs.isPanicEnabled).thenReturn(flowOf(true))
        `when`(mockSecurityPrefs.hasMasterPin).thenReturn(flowOf(true))
        `when`(mockSecurityPrefs.hasPanicPin).thenReturn(flowOf(true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun unlockVault_withCorrectMasterPin_unlocksSuccessfully() = runTest {
        `when`(mockSecurityPrefs.verifyPanicPin("MasterKey123")).thenReturn(false)
        `when`(mockSecurityPrefs.verifyMasterPin("MasterKey123")).thenReturn(true)

        val viewModel = VaultViewModel(mockRepository, mockSecurityPrefs)
        viewModel.unlockVault("MasterKey123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isUnlocked)
        assertNull(viewModel.uiState.value.userMessage)
    }

    @Test
    fun unlockVault_withIncorrectPin_showsErrorMessage() = runTest {
        `when`(mockSecurityPrefs.verifyPanicPin("WrongPin")).thenReturn(false)
        `when`(mockSecurityPrefs.verifyMasterPin("WrongPin")).thenReturn(false)

        val viewModel = VaultViewModel(mockRepository, mockSecurityPrefs)
        viewModel.unlockVault("WrongPin")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isUnlocked)
        assertEquals(
            R.string.error_incorrect_password,
            (viewModel.uiState.value.userMessage as? UiText.StringResource)?.resId
        )
    }

    @Test
    fun unlockVault_withPanicPin_triggersWipeAndUnlocksEmptyVault() = runTest {
        `when`(mockSecurityPrefs.verifyPanicPin("Panic123")).thenReturn(true)
        `when`(mockRepository.wipeAllVaultFiles()).thenReturn(Result.success(Unit))

        val viewModel = VaultViewModel(mockRepository, mockSecurityPrefs)
        viewModel.unlockVault("Panic123")
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isUnlocked)
        assertTrue(viewModel.uiState.value.files.isEmpty())
        assertNull(viewModel.uiState.value.userMessage)
    }
}
