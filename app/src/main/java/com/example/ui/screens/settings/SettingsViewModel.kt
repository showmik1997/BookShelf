package com.example.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.preferences.EyeComfortMode
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BookShelfRepository
import com.example.data.security.KeyStoreManager
import com.example.service.ai.GeminiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val defaultEyeComfort: EyeComfortMode = EyeComfortMode.OFF,
    val geminiKey: String = "",
    val geminiModel: String = "gemini-3.8-flash",
    val customModel: String = "",
    val availableModels: List<String> = listOf("gemini-3.8-flash", "gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro"),
    val isRefreshingModels: Boolean = false,
    val isKeyVisible: Boolean = false,
    val autoClassify: Boolean = true,
    val statusMessage: String? = null,
    val errorMessage: String? = null
) {
    // Backward compatibility aliases
    val openAiKey: String get() = geminiKey
    val openAiModel: String get() = geminiModel
}

class SettingsViewModel(
    private val repository: BookShelfRepository,
    private val keyStoreManager: KeyStoreManager,
    private val preferencesRepository: UserPreferencesRepository,
    private val geminiService: GeminiService
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        SettingsUiState(
            geminiKey = getInitialApiKey()
        )
    )
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                _uiState.value = _uiState.value.copy(
                    themeMode = prefs.themeMode,
                    defaultEyeComfort = prefs.eyeComfortMode,
                    geminiModel = prefs.geminiModel.ifBlank { "gemini-3.8-flash" },
                    customModel = prefs.customModel,
                    geminiKey = if (_uiState.value.geminiKey.isBlank() && prefs.geminiApiKey.isNotBlank()) prefs.geminiApiKey else _uiState.value.geminiKey,
                    autoClassify = prefs.autoClassifyImports
                )
            }
        }
    }

    private fun getInitialApiKey(): String {
        val fromPrefs = runBlocking {
            try {
                preferencesRepository.preferencesFlow.first().geminiApiKey
            } catch (e: Exception) {
                ""
            }
        }
        if (fromPrefs.isNotBlank()) return fromPrefs
        val saved = keyStoreManager.getApiKey()
        if (saved.isNotBlank()) return saved
        val buildKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Throwable) {
            ""
        }
        return if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY") buildKey else ""
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setThemeMode(mode)
        }
    }

    fun setEyeComfortMode(mode: EyeComfortMode) {
        viewModelScope.launch {
            preferencesRepository.setEyeComfortMode(mode)
        }
    }

    fun setAutoClassify(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepository.setAutoClassify(enabled)
        }
    }

    fun setApiKey(key: String) {
        _uiState.value = _uiState.value.copy(geminiKey = key)
        viewModelScope.launch {
            keyStoreManager.saveApiKey(key.trim())
            preferencesRepository.setGeminiApiKey(key.trim())
        }
    }

    fun setModel(model: String) {
        _uiState.value = _uiState.value.copy(geminiModel = model)
    }

    fun setCustomModel(model: String) {
        _uiState.value = _uiState.value.copy(customModel = model)
    }

    fun toggleKeyVisibility() {
        _uiState.value = _uiState.value.copy(isKeyVisible = !_uiState.value.isKeyVisible)
    }

    fun refreshModels() {
        val key = _uiState.value.geminiKey.ifBlank { getInitialApiKey() }
        if (key.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = "Please enter a Gemini API key before refreshing models.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isRefreshingModels = true, errorMessage = null)
            val result = geminiService.fetchAvailableModels(key)
            result.fold(
                onSuccess = { models ->
                    _uiState.value = _uiState.value.copy(
                        availableModels = models,
                        isRefreshingModels = false,
                        statusMessage = "Loaded ${models.size} models from Google Gemini."
                    )
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(
                        isRefreshingModels = false,
                        errorMessage = err.localizedMessage ?: "Failed to refresh models."
                    )
                }
            )
        }
    }

    fun saveAiSettings() {
        viewModelScope.launch {
            val key = _uiState.value.geminiKey.trim()
            keyStoreManager.saveApiKey(key)
            preferencesRepository.setGeminiApiKey(key)

            val chosenModel = if (_uiState.value.customModel.isNotBlank()) {
                _uiState.value.customModel.trim()
            } else {
                _uiState.value.geminiModel
            }
            preferencesRepository.setGeminiModel(chosenModel)
            preferencesRepository.setCustomModel(_uiState.value.customModel.trim())

            _uiState.value = _uiState.value.copy(
                statusMessage = if (key.isNotBlank()) "Gemini AI settings saved & activated!" else "Gemini settings updated.",
                errorMessage = null
            )
        }
    }

    fun exportBackup(onJsonReady: (String) -> Unit) {
        viewModelScope.launch {
            val json = repository.exportBackupJson()
            onJsonReady(json)
        }
    }

    fun exportBackupToFile(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val json = repository.exportBackupJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Backup successfully exported and saved to file!",
                    errorMessage = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Failed to export backup: ${e.localizedMessage}"
                )
            }
        }
    }

    fun shareBackup(context: Context) {
        viewModelScope.launch {
            try {
                val json = repository.exportBackupJson()
                val backupFile = withContext(Dispatchers.IO) {
                    val file = File(context.cacheDir, "bookshelf_backup_${System.currentTimeMillis()}.json")
                    FileOutputStream(file).use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                    file
                }
                val fileUri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    backupFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    putExtra(Intent.EXTRA_SUBJECT, "BookShelf Library Backup")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share or Save BookShelf Backup"))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Could not share backup: ${e.localizedMessage}"
                )
            }
        }
    }

    fun restoreBackup(jsonString: String) {
        viewModelScope.launch {
            val result = repository.restoreBackupJson(jsonString)
            result.fold(
                onSuccess = { msg ->
                    _uiState.value = _uiState.value.copy(statusMessage = msg, errorMessage = null)
                },
                onFailure = { err ->
                    _uiState.value = _uiState.value.copy(errorMessage = "Restore failed: ${err.localizedMessage}")
                }
            )
        }
    }

    fun restoreBackupFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                val jsonString = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (jsonString.isNullOrBlank()) {
                    _uiState.value = _uiState.value.copy(errorMessage = "The selected backup file is empty.")
                    return@launch
                }
                restoreBackup(jsonString)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Failed to read backup file: ${e.localizedMessage}")
            }
        }
    }

    fun clearCache(context: Context) {
        viewModelScope.launch {
            try {
                context.cacheDir.deleteRecursively()
                _uiState.value = _uiState.value.copy(statusMessage = "Temporary cache cleared.")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(errorMessage = "Could not clear cache: ${e.localizedMessage}")
            }
        }
    }

    fun clearMessages() {
        _uiState.value = _uiState.value.copy(statusMessage = null, errorMessage = null)
    }
}
