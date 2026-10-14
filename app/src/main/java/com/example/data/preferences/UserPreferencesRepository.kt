package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "bookshelf_settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class EyeComfortMode { OFF, WARM, SEPIA, DIM }
enum class LayoutMode { GRID, LIST }
enum class SortOption { DATE_ADDED, TITLE, LAST_OPENED, READING_PROGRESS }

data class AppPreferences(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val eyeComfortMode: EyeComfortMode = EyeComfortMode.OFF,
    val layoutMode: LayoutMode = LayoutMode.GRID,
    val sortOption: SortOption = SortOption.DATE_ADDED,
    val sortAscending: Boolean = false,
    val geminiModel: String = "gemini-3.8-flash",
    val openAiModel: String = "gemini-3.8-flash",
    val customModel: String = "",
    val geminiApiKey: String = "",
    val autoClassifyImports: Boolean = true,
    val storeAutoDownloadOpen: Boolean = true
)

class UserPreferencesRepository(private val context: Context) {

    private val KEY_THEME = stringPreferencesKey("theme_mode")
    private val KEY_EYE_COMFORT = stringPreferencesKey("eye_comfort_mode")
    private val KEY_LAYOUT = stringPreferencesKey("layout_mode")
    private val KEY_SORT = stringPreferencesKey("sort_option")
    private val KEY_SORT_ASC = booleanPreferencesKey("sort_asc")
    private val KEY_GEMINI_MODEL = stringPreferencesKey("gemini_model")
    private val KEY_MODEL = stringPreferencesKey("openai_model")
    private val KEY_CUSTOM_MODEL = stringPreferencesKey("custom_model")
    private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
    private val KEY_AUTO_CLASSIFY = booleanPreferencesKey("auto_classify")
    private val KEY_STORE_AUTO_OPEN = booleanPreferencesKey("store_auto_open")

    val preferencesFlow: Flow<AppPreferences> = context.dataStore.data.map { prefs ->
        val rawGemini = prefs[KEY_GEMINI_MODEL] ?: prefs[KEY_MODEL]
        val effectiveGemini = if (rawGemini.isNullOrBlank() || rawGemini == "gemini-2.5-flash" || rawGemini == "models/gemini-2.5-flash") {
            "gemini-3.8-flash"
        } else {
            rawGemini
        }

        AppPreferences(
            themeMode = try {
                ThemeMode.valueOf(prefs[KEY_THEME] ?: ThemeMode.SYSTEM.name)
            } catch (e: Exception) {
                ThemeMode.SYSTEM
            },
            eyeComfortMode = try {
                EyeComfortMode.valueOf(prefs[KEY_EYE_COMFORT] ?: EyeComfortMode.OFF.name)
            } catch (e: Exception) {
                EyeComfortMode.OFF
            },
            layoutMode = try {
                LayoutMode.valueOf(prefs[KEY_LAYOUT] ?: LayoutMode.GRID.name)
            } catch (e: Exception) {
                LayoutMode.GRID
            },
            sortOption = try {
                SortOption.valueOf(prefs[KEY_SORT] ?: SortOption.DATE_ADDED.name)
            } catch (e: Exception) {
                SortOption.DATE_ADDED
            },
            sortAscending = prefs[KEY_SORT_ASC] ?: false,
            geminiModel = effectiveGemini,
            openAiModel = effectiveGemini,
            customModel = prefs[KEY_CUSTOM_MODEL] ?: "",
            geminiApiKey = prefs[KEY_GEMINI_API_KEY]?.trim() ?: "",
            autoClassifyImports = prefs[KEY_AUTO_CLASSIFY] ?: true,
            storeAutoDownloadOpen = prefs[KEY_STORE_AUTO_OPEN] ?: true
        )
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GEMINI_API_KEY] = apiKey.trim()
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[KEY_THEME] = mode.name }
    }

    suspend fun setEyeComfortMode(mode: EyeComfortMode) {
        context.dataStore.edit { it[KEY_EYE_COMFORT] = mode.name }
    }

    suspend fun setLayoutMode(mode: LayoutMode) {
        context.dataStore.edit { it[KEY_LAYOUT] = mode.name }
    }

    suspend fun toggleLayoutMode() {
        context.dataStore.edit { prefs ->
            val current = try {
                LayoutMode.valueOf(prefs[KEY_LAYOUT] ?: LayoutMode.GRID.name)
            } catch (e: Exception) {
                LayoutMode.GRID
            }
            val next = if (current == LayoutMode.GRID) LayoutMode.LIST else LayoutMode.GRID
            prefs[KEY_LAYOUT] = next.name
        }
    }

    suspend fun setSortOption(sort: SortOption, asc: Boolean) {
        context.dataStore.edit {
            it[KEY_SORT] = sort.name
            it[KEY_SORT_ASC] = asc
        }
    }

    suspend fun setGeminiModel(model: String) {
        context.dataStore.edit {
            it[KEY_GEMINI_MODEL] = model
            it[KEY_MODEL] = model
        }
    }

    suspend fun setOpenAiModel(model: String) {
        setGeminiModel(model)
    }

    suspend fun setCustomModel(model: String) {
        context.dataStore.edit { it[KEY_CUSTOM_MODEL] = model }
    }

    suspend fun setAutoClassify(enabled: Boolean) {
        context.dataStore.edit { it[KEY_AUTO_CLASSIFY] = enabled }
    }

    suspend fun setStoreAutoOpen(enabled: Boolean) {
        context.dataStore.edit { it[KEY_STORE_AUTO_OPEN] = enabled }
    }
}
