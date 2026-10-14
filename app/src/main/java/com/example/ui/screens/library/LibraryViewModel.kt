package com.example.ui.screens.library

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BookEntity
import com.example.data.model.CategoryEntity
import com.example.data.preferences.AppPreferences
import com.example.data.preferences.LayoutMode
import com.example.data.preferences.SortOption
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BookShelfRepository
import com.example.data.repository.ImportResult
import com.example.service.pdf.ExtractedPdfInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ReadingFilter { ALL, UNREAD, IN_PROGRESS, COMPLETED }

data class LibraryUiState(
    val books: List<BookEntity> = emptyList(),
    val categories: List<CategoryEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedCategoryId: String? = null, // null = all
    val filterFavoritesOnly: Boolean = false,
    val readingFilter: ReadingFilter = ReadingFilter.ALL,
    val layoutMode: LayoutMode = LayoutMode.GRID,
    val sortOption: SortOption = SortOption.DATE_ADDED,
    val sortAscending: Boolean = false,
    val selectedBookIds: Set<String> = emptySet(),
    val isSelectionMode: Boolean = false,
    val editingBook: BookEntity? = null,
    val pendingImportConfirmation: ExtractedPdfInfo? = null,
    val statusMessage: String? = null,
    val isImporting: Boolean = false
)

class LibraryViewModel(
    private val repository: BookShelfRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    private val _filterFavoritesOnly = MutableStateFlow(false)
    private val _readingFilter = MutableStateFlow(ReadingFilter.ALL)
    private val _selectedBookIds = MutableStateFlow<Set<String>>(emptySet())
    private val _editingBook = MutableStateFlow<BookEntity?>(null)
    private val _pendingImportConfirmation = MutableStateFlow<ExtractedPdfInfo?>(null)
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _isImporting = MutableStateFlow(false)

    val preferences: StateFlow<AppPreferences> = preferencesRepository.preferencesFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AppPreferences()
    )

    init {
        viewModelScope.launch {
            repository.removeDemoBooksIfPresent()
        }
    }

    val uiState: StateFlow<LibraryUiState> = combine(
        repository.allBooks,
        repository.allCategories,
        preferencesRepository.preferencesFlow,
        _searchQuery,
        _selectedCategoryId,
        _filterFavoritesOnly,
        _readingFilter,
        _selectedBookIds,
        _editingBook,
        _pendingImportConfirmation,
        _statusMessage,
        _isImporting
    ) { args: Array<Any?> ->
        val rawBooks = args[0] as List<BookEntity>
        val categories = args[1] as List<CategoryEntity>
        val prefs = args[2] as AppPreferences
        val query = args[3] as String
        val catId = args[4] as String?
        val favOnly = args[5] as Boolean
        val readFilter = args[6] as ReadingFilter
        val selectedIds = args[7] as Set<String>
        val editBook = args[8] as BookEntity?
        val pendingImport = args[9] as ExtractedPdfInfo?
        val statusMsg = args[10] as String?
        val importing = args[11] as Boolean

        // Filter books
        var filtered = rawBooks.filter { book ->
            val matchesQuery = query.isBlank() ||
                book.title.contains(query, ignoreCase = true) ||
                book.author.contains(query, ignoreCase = true) ||
                book.tagsJson.contains(query, ignoreCase = true) ||
                book.description.contains(query, ignoreCase = true) ||
                book.notes.contains(query, ignoreCase = true)

            val matchesCategory = catId == null || book.categoryId == catId
            val matchesFav = !favOnly || book.isFavorite
            val matchesReading = when (readFilter) {
                ReadingFilter.ALL -> true
                ReadingFilter.UNREAD -> book.readingPercentage <= 0f
                ReadingFilter.IN_PROGRESS -> book.readingPercentage > 0f && book.readingPercentage < 100f
                ReadingFilter.COMPLETED -> book.readingPercentage >= 100f
            }

            matchesQuery && matchesCategory && matchesFav && matchesReading
        }

        // Sort books
        filtered = when (prefs.sortOption) {
            SortOption.DATE_ADDED -> if (prefs.sortAscending) filtered.sortedBy { it.dateAdded } else filtered.sortedByDescending { it.dateAdded }
            SortOption.TITLE -> if (prefs.sortAscending) filtered.sortedBy { it.title.lowercase() } else filtered.sortedByDescending { it.title.lowercase() }
            SortOption.LAST_OPENED -> if (prefs.sortAscending) filtered.sortedBy { it.lastOpened } else filtered.sortedByDescending { it.lastOpened }
            SortOption.READING_PROGRESS -> if (prefs.sortAscending) filtered.sortedBy { it.readingPercentage } else filtered.sortedByDescending { it.readingPercentage }
        }

        LibraryUiState(
            books = filtered,
            categories = categories,
            searchQuery = query,
            selectedCategoryId = catId,
            filterFavoritesOnly = favOnly,
            readingFilter = readFilter,
            layoutMode = prefs.layoutMode,
            sortOption = prefs.sortOption,
            sortAscending = prefs.sortAscending,
            selectedBookIds = selectedIds,
            isSelectionMode = selectedIds.isNotEmpty(),
            editingBook = editBook,
            pendingImportConfirmation = pendingImport,
            statusMessage = statusMsg,
            isImporting = importing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = LibraryUiState()
    )

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setSelectedCategory(catId: String?) { _selectedCategoryId.value = catId }
    fun setFavoritesOnly(only: Boolean) { _filterFavoritesOnly.value = only }
    fun setReadingFilter(filter: ReadingFilter) { _readingFilter.value = filter }

    fun toggleLayoutMode() {
        viewModelScope.launch {
            preferencesRepository.toggleLayoutMode()
        }
    }

    fun setSortOption(sort: SortOption) {
        viewModelScope.launch {
            val asc = if (uiState.value.sortOption == sort) !uiState.value.sortAscending else false
            preferencesRepository.setSortOption(sort, asc)
        }
    }

    fun toggleSelection(bookId: String) {
        val current = _selectedBookIds.value.toMutableSet()
        if (current.contains(bookId)) current.remove(bookId) else current.add(bookId)
        _selectedBookIds.value = current
    }

    fun clearSelection() {
        _selectedBookIds.value = emptySet()
    }

    fun toggleFavorite(book: BookEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(book.id, book.isFavorite)
        }
    }

    fun setEditingBook(book: BookEntity?) {
        _editingBook.value = book
    }

    fun saveBookChanges(book: BookEntity) {
        viewModelScope.launch {
            repository.updateBook(book)
            _editingBook.value = null
            _statusMessage.value = "Updated \"${book.title}\""
        }
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            repository.deleteBook(bookId)
            _statusMessage.value = "Document removed from library."
        }
    }

    fun bulkDeleteSelected() {
        viewModelScope.launch {
            val ids = _selectedBookIds.value.toList()
            repository.bulkDeleteBooks(ids)
            clearSelection()
            _statusMessage.value = "${ids.size} document(s) removed."
        }
    }

    fun bulkChangeCategory(categoryId: String) {
        viewModelScope.launch {
            val ids = _selectedBookIds.value.toList()
            repository.bulkUpdateCategory(ids, categoryId)
            clearSelection()
            _statusMessage.value = "Moved ${ids.size} document(s) to new category."
        }
    }

    fun importUris(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isImporting.value = true
            var importedCount = 0
            var duplicateCount = 0

            for (uri in uris) {
                val fileName = queryDisplayName(context, uri) ?: "Document_${System.currentTimeMillis()}.pdf"
                when (val result = repository.importPdfFromUri(uri, fileName)) {
                    is ImportResult.Success -> {
                        importedCount++
                    }
                    is ImportResult.Duplicate -> {
                        duplicateCount++
                    }
                    is ImportResult.Error -> {
                        _statusMessage.value = "Import error: ${result.message}"
                    }
                }
            }

            _isImporting.value = false
            _statusMessage.value = when {
                importedCount > 0 && duplicateCount > 0 -> "Imported $importedCount document(s). ($duplicateCount duplicate(s) skipped)"
                importedCount > 0 -> "Successfully imported $importedCount document(s)."
                duplicateCount > 0 -> "Skipped $duplicateCount duplicate document(s) already in library."
                else -> null
            }
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        var name: String? = null
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex != -1) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return name
    }

    fun addCategory(cat: CategoryEntity) {
        viewModelScope.launch {
            repository.addCategory(cat)
            _statusMessage.value = "Category \"${cat.name}\" created."
        }
    }

    fun deleteCategoryAndReassign(category: CategoryEntity, reassignToId: String) {
        viewModelScope.launch {
            repository.deleteCategoryAndReassign(category, reassignToId)
            _statusMessage.value = "Category deleted and documents moved."
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
