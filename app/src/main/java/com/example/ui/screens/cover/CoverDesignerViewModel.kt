package com.example.ui.screens.cover

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BookEntity
import com.example.data.repository.BookShelfRepository
import com.example.service.pdf.CoverDesignerHelper
import com.example.service.pdf.PdfEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class CoverDesignerUiState(
    val book: BookEntity? = null,
    val title: String = "",
    val subtitle: String = "",
    val author: String = "",
    val bgColorHex: String = "#1E3A8A",
    val textColorHex: String = "#FFFFFF",
    val userImageBitmap: Bitmap? = null,
    val previewBitmap: Bitmap? = null,
    val isSaving: Boolean = false,
    val statusMessage: String? = null
)

class CoverDesignerViewModel(
    private val bookId: String,
    private val repository: BookShelfRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CoverDesignerUiState())
    val uiState: StateFlow<CoverDesignerUiState> = _uiState

    init {
        loadBook()
    }

    private fun loadBook() {
        viewModelScope.launch {
            val book = repository.getBookById(bookId)
            if (book != null) {
                _uiState.value = _uiState.value.copy(
                    book = book,
                    title = book.title,
                    subtitle = "BookShelf Edition",
                    author = book.author
                )
                refreshPreview()
            }
        }
    }

    fun setTitle(title: String) {
        _uiState.value = _uiState.value.copy(title = title)
        refreshPreview()
    }

    fun setSubtitle(sub: String) {
        _uiState.value = _uiState.value.copy(subtitle = sub)
        refreshPreview()
    }

    fun setAuthor(author: String) {
        _uiState.value = _uiState.value.copy(author = author)
        refreshPreview()
    }

    fun setBgColor(hex: String) {
        _uiState.value = _uiState.value.copy(bgColorHex = hex)
        refreshPreview()
    }

    fun setTextColor(hex: String) {
        _uiState.value = _uiState.value.copy(textColorHex = hex)
        refreshPreview()
    }

    fun setUserImageUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val input = context.contentResolver.openInputStream(uri)
                val bmp = BitmapFactory.decodeStream(input)
                input?.close()
                _uiState.value = _uiState.value.copy(userImageBitmap = bmp)
                refreshPreview()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun refreshPreview() {
        viewModelScope.launch {
            val s = _uiState.value
            val bgInt = android.graphics.Color.parseColor(s.bgColorHex)
            val textInt = android.graphics.Color.parseColor(s.textColorHex)
            val bmp = CoverDesignerHelper.generateCoverBitmap(
                title = s.title.ifBlank { "Document Title" },
                subtitle = s.subtitle,
                author = s.author,
                bgColorInt = bgInt,
                textColorInt = textInt,
                userImage = s.userImageBitmap
            )
            _uiState.value = _uiState.value.copy(previewBitmap = bmp)
        }
    }

    fun saveAsThumbnailOnly(context: Context, onComplete: () -> Unit) {
        val bmp = _uiState.value.previewBitmap ?: return
        val book = _uiState.value.book ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val coverFile = File(context.filesDir, "covers/${book.id}.png")
            CoverDesignerHelper.saveCoverAsThumbnail(bmp, coverFile)

            val updatedBook = book.copy(
                coverImagePath = coverFile.absolutePath,
                title = _uiState.value.title.ifBlank { book.title },
                author = _uiState.value.author
            )
            repository.updateBook(updatedBook)

            _uiState.value = _uiState.value.copy(
                isSaving = false,
                statusMessage = "Saved custom thumbnail for Library!"
            )
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    fun saveAndPrependToPdf(context: Context, onComplete: () -> Unit) {
        val bmp = _uiState.value.previewBitmap ?: return
        val book = _uiState.value.book ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isSaving = true)

            // Safe copy in BookShelf storage
            val sourcePdf = File(book.filePath)
            val newFileId = UUID.randomUUID().toString()
            val targetPdf = File(context.filesDir, "books/$newFileId.pdf")

            val success = CoverDesignerHelper.createPdfCopyWithPrependCover(bmp, sourcePdf, targetPdf)
            if (success) {
                val coverFile = File(context.filesDir, "covers/$newFileId.png")
                CoverDesignerHelper.saveCoverAsThumbnail(bmp, coverFile)
                val newPageCount = PdfEngine.getPageCount(targetPdf)

                val updatedBook = book.copy(
                    filePath = targetPdf.absolutePath,
                    coverImagePath = coverFile.absolutePath,
                    title = _uiState.value.title.ifBlank { book.title },
                    author = _uiState.value.author,
                    pageCount = newPageCount,
                    currentPage = 0
                )
                repository.updateBook(updatedBook)

                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    statusMessage = "Created safe copy with custom cover page prepended!"
                )
                withContext(Dispatchers.Main) { onComplete() }
            } else {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    statusMessage = "Could not prepend cover to PDF."
                )
            }
        }
    }
}
