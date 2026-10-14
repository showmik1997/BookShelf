package com.example.ui.screens.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.example.data.db.AiMessageDao
import com.example.data.model.AiMessageEntity
import com.example.data.model.BookEntity
import com.example.data.model.CategoryEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BookShelfRepository
import com.example.data.security.KeyStoreManager
import com.example.service.ai.BookShelfRagEngine
import com.example.service.ai.GeminiChatMessage
import com.example.service.ai.RetrievedSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID

data class ChatMessageUi(
    val id: String,
    val role: String, // "user", "assistant"
    val content: String,
    val sources: List<RetrievedSource> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
)

data class AiAssistantUiState(
    val messages: List<ChatMessageUi> = emptyList(),
    val isGenerating: Boolean = false,
    val books: List<BookEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val scopeBookId: String? = null, // null = all library
    val scopeCategoryId: String? = null,
    val hasApiKey: Boolean = false,
    val modelName: String = "gemini-3.8-flash",
    val errorMessage: String? = null
)

class AiAssistantViewModel(
    private val repository: BookShelfRepository,
    private val ragEngine: BookShelfRagEngine,
    private val aiMessageDao: AiMessageDao,
    private val keyStoreManager: KeyStoreManager,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _isGenerating = MutableStateFlow(false)
    private val _scopeBookId = MutableStateFlow<String?>(null)
    private val _scopeCategoryId = MutableStateFlow<String?>(null)
    private val _errorMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<AiAssistantUiState> = combine(
        aiMessageDao.getAllMessages(),
        repository.allBooks,
        repository.allCategories,
        preferencesRepository.preferencesFlow,
        _isGenerating,
        _scopeBookId,
        _scopeCategoryId,
        _errorMessage
    ) { args: Array<Any?> ->
        val rawMessages = args[0] as List<AiMessageEntity>
        val books = args[1] as List<BookEntity>
        val categories = args[2] as List<CategoryEntity>
        val prefs = args[3] as com.example.data.preferences.AppPreferences
        val generating = args[4] as Boolean
        val bookId = args[5] as String?
        val catId = args[6] as String?
        val error = args[7] as String?

        val uiMsgs = rawMessages.map { entity ->
            val sourcesList = mutableListOf<RetrievedSource>()
            if (!entity.sourcesJson.isNullOrBlank()) {
                try {
                    val arr = JSONArray(entity.sourcesJson)
                    for (i in 0 until arr.length()) {
                        sourcesList.add(RetrievedSource.fromJson(arr.getJSONObject(i)))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            ChatMessageUi(
                id = entity.id,
                role = entity.role,
                content = entity.content,
                sources = sourcesList,
                timestamp = entity.timestamp
            )
        }

        val effectiveKey = prefs.geminiApiKey.ifBlank { getEffectiveApiKey() }

        AiAssistantUiState(
            messages = uiMsgs,
            isGenerating = generating,
            books = books,
            categories = categories,
            scopeBookId = bookId,
            scopeCategoryId = catId,
            hasApiKey = effectiveKey.isNotBlank(),
            modelName = prefs.geminiModel.ifBlank { "gemini-3.8-flash" },
            errorMessage = error
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AiAssistantUiState()
    )

    private fun getEffectiveApiKey(): String {
        val saved = keyStoreManager.getApiKey().trim()
        if (saved.isNotBlank()) return saved
        val buildKey = try {
            BuildConfig.GEMINI_API_KEY.trim()
        } catch (e: Throwable) {
            ""
        }
        return if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY") buildKey else ""
    }

    fun setScopeBook(bookId: String?) {
        _scopeBookId.value = bookId
        if (bookId != null) _scopeCategoryId.value = null
    }

    fun setScopeCategory(categoryId: String?) {
        _scopeCategoryId.value = categoryId
        if (categoryId != null) _scopeBookId.value = null
    }

    fun sendMessage(query: String) {
        if (query.isBlank() || _isGenerating.value) return

        viewModelScope.launch {
            _isGenerating.value = true
            _errorMessage.value = null

            val userMsg = AiMessageEntity(
                id = UUID.randomUUID().toString(),
                role = "user",
                content = query.trim(),
                sourcesJson = null,
                timestamp = System.currentTimeMillis()
            )
            aiMessageDao.insertMessage(userMsg)

            val currentMessages = uiState.value.messages.map {
                GeminiChatMessage(role = it.role, text = it.content)
            }

            val apiKey = try {
                preferencesRepository.preferencesFlow.first().geminiApiKey
            } catch (e: Exception) {
                ""
            }.ifBlank { getEffectiveApiKey() }
            val model = uiState.value.modelName
            val bookScope = _scopeBookId.value
            val catScope = _scopeCategoryId.value

            val (answer, sources) = ragEngine.generateAnswer(
                query = query,
                apiKey = apiKey,
                model = model,
                targetBookId = bookScope,
                targetCategoryId = catScope,
                previousMessages = currentMessages
            )

            val sourcesJson = if (sources.isNotEmpty()) {
                val arr = JSONArray()
                for (s in sources) {
                    arr.put(s.toJson())
                }
                arr.toString()
            } else null

            val assistantMsg = AiMessageEntity(
                id = UUID.randomUUID().toString(),
                role = "assistant",
                content = answer,
                sourcesJson = sourcesJson,
                timestamp = System.currentTimeMillis()
            )
            aiMessageDao.insertMessage(assistantMsg)

            _isGenerating.value = false
        }
    }

    fun clearConversation() {
        viewModelScope.launch {
            aiMessageDao.clearAllMessages()
        }
    }
}
