package com.example.ui.screens.reader

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BookEntity
import com.example.data.model.BookmarkEntity
import com.example.data.preferences.EyeComfortMode
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BookShelfRepository
import com.example.service.pdf.PdfEngine
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class ReaderUiState(
    val book: BookEntity? = null,
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val isControlsVisible: Boolean = true,
    val eyeComfortMode: EyeComfortMode = EyeComfortMode.OFF,
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val cachedPages: Map<Int, Bitmap> = emptyMap(),
    val isLoadingPage: Boolean = false,
    val sessionStartMs: Long = System.currentTimeMillis()
)

class ReaderViewModel(
    private val bookId: String,
    private val repository: BookShelfRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = _uiState

    private var autoHideControlsJob: Job? = null
    private var readingProgressJob: Job? = null
    private var lastRecordedPage: Int = -1

    init {
        loadBook()
        observeBookmarks()
        loadPreferences()
    }

    private fun loadBook() {
        viewModelScope.launch {
            val book = repository.getBookById(bookId)
            if (book != null) {
                val initialPage = book.currentPage.coerceIn(0, (book.pageCount - 1).coerceAtLeast(0))
                _uiState.value = _uiState.value.copy(
                    book = book,
                    currentPage = initialPage,
                    totalPages = book.pageCount
                )
                lastRecordedPage = initialPage
                loadPageBitmap(initialPage)
                // Preload adjacent page
                if (initialPage + 1 < book.pageCount) loadPageBitmap(initialPage + 1)
                recordProgress(initialPage)
            }
        }
    }

    private fun observeBookmarks() {
        viewModelScope.launch {
            repository.getBookmarksForBook(bookId).collect { bms ->
                _uiState.value = _uiState.value.copy(bookmarks = bms)
            }
        }
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            preferencesRepository.preferencesFlow.collect { prefs ->
                _uiState.value = _uiState.value.copy(eyeComfortMode = prefs.eyeComfortMode)
            }
        }
    }

    fun setEyeComfortMode(mode: EyeComfortMode) {
        viewModelScope.launch {
            preferencesRepository.setEyeComfortMode(mode)
            _uiState.value = _uiState.value.copy(eyeComfortMode = mode)
        }
    }

    fun jumpToPage(pageIndex: Int) {
        val total = _uiState.value.totalPages
        val target = pageIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
        if (_uiState.value.currentPage != target) {
            _uiState.value = _uiState.value.copy(currentPage = target)
            loadPageBitmap(target)
            if (target + 1 < total) loadPageBitmap(target + 1)
            if (target - 1 >= 0) loadPageBitmap(target - 1)
            recordProgress(target)
        }
    }

    fun nextPage() {
        jumpToPage(_uiState.value.currentPage + 1)
    }

    fun prevPage() {
        jumpToPage(_uiState.value.currentPage - 1)
    }

    fun toggleControlsVisibility() {
        val next = !_uiState.value.isControlsVisible
        _uiState.value = _uiState.value.copy(isControlsVisible = next)
        if (next) {
            scheduleAutoHideControls()
        } else {
            autoHideControlsJob?.cancel()
        }
    }

    fun showControlsTemporarily() {
        _uiState.value = _uiState.value.copy(isControlsVisible = true)
        scheduleAutoHideControls()
    }

    private fun scheduleAutoHideControls() {
        autoHideControlsJob?.cancel()
        autoHideControlsJob = viewModelScope.launch {
            delay(3500)
            _uiState.value = _uiState.value.copy(isControlsVisible = false)
        }
    }

    fun addBookmarkAtCurrentPage() {
        val page = _uiState.value.currentPage
        viewModelScope.launch {
            repository.addBookmark(bookId, page, "Page ${page + 1}")
        }
    }

    fun removeBookmark(bookmarkId: String) {
        viewModelScope.launch {
            repository.deleteBookmark(bookmarkId)
        }
    }

    private fun loadPageBitmap(page: Int) {
        val book = _uiState.value.book ?: return
        if (_uiState.value.cachedPages.containsKey(page)) return

        viewModelScope.launch {
            val file = File(book.filePath)
            val bitmap = PdfEngine.renderPage(file, page, targetWidth = 1200)
            if (bitmap != null) {
                val updatedCache = _uiState.value.cachedPages.toMutableMap()
                // Evict distant pages if cache gets large
                if (updatedCache.size > 8) {
                    val distantKey = updatedCache.keys.maxByOrNull { Math.abs(it - page) }
                    distantKey?.let { updatedCache.remove(it)?.recycle() }
                }
                updatedCache[page] = bitmap
                _uiState.value = _uiState.value.copy(cachedPages = updatedCache)
            }
        }
    }

    private fun recordProgress(page: Int) {
        readingProgressJob?.cancel()
        readingProgressJob = viewModelScope.launch {
            val elapsed = System.currentTimeMillis() - _uiState.value.sessionStartMs
            repository.recordReadingProgress(bookId, page, elapsed.coerceAtLeast(0L))
            // Reload book to update visited count / reading percentage
            val updated = repository.getBookById(bookId)
            if (updated != null) {
                _uiState.value = _uiState.value.copy(book = updated)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Save final session state
        val page = _uiState.value.currentPage
        val elapsed = System.currentTimeMillis() - _uiState.value.sessionStartMs
        viewModelScope.launch {
            repository.recordReadingProgress(bookId, page, elapsed)
        }
        // Recycle bitmaps
        _uiState.value.cachedPages.values.forEach { it.recycle() }
    }
}
