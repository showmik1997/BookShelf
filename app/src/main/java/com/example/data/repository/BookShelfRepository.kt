package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.example.data.db.BookDao
import com.example.data.db.BookShelfDatabase
import com.example.data.db.BookmarkDao
import com.example.data.db.CategoryDao
import com.example.data.db.PdfPageTextDao
import com.example.data.db.ReadingSessionDao
import com.example.data.model.BookEntity
import com.example.data.model.BookmarkEntity
import com.example.data.model.CategoryEntity
import com.example.data.model.PdfPageTextEntity
import com.example.data.model.ReadingSessionEntity
import com.example.service.pdf.ExtractedPdfInfo
import com.example.service.pdf.PdfEngine
import com.example.service.pdf.PdfTextExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed class ImportResult {
    data class Success(val book: BookEntity, val extractedInfo: ExtractedPdfInfo) : ImportResult()
    data class Duplicate(val existingBook: BookEntity) : ImportResult()
    data class Error(val message: String) : ImportResult()
}

class BookShelfRepository(
    private val context: Context,
    private val database: BookShelfDatabase
) {
    private val bookDao: BookDao = database.bookDao()
    private val categoryDao: CategoryDao = database.categoryDao()
    private val bookmarkDao: BookmarkDao = database.bookmarkDao()
    private val readingSessionDao: ReadingSessionDao = database.readingSessionDao()
    private val pageTextDao: PdfPageTextDao = database.pdfPageTextDao()

    val allBooks: Flow<List<BookEntity>> = bookDao.getAllBooks()
    val allCategories: Flow<List<CategoryEntity>> = categoryDao.getAllCategories()
    val favoriteBooks: Flow<List<BookEntity>> = bookDao.getFavoriteBooks()
    val recentBooks: Flow<List<BookEntity>> = bookDao.getRecentBooks(10)
    val mostReadBooks: Flow<List<BookEntity>> = bookDao.getMostReadBooks(10)
    val allSessions: Flow<List<ReadingSessionEntity>> = readingSessionDao.getAllSessions()

    fun getBookFlow(id: String): Flow<BookEntity?> = bookDao.getBookByIdFlow(id)
    suspend fun getBookById(id: String): BookEntity? = bookDao.getBookById(id)
    fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>> = bookmarkDao.getBookmarksForBook(bookId)

    private val booksDir: File get() = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir: File get() = File(context.filesDir, "covers").apply { mkdirs() }

    suspend fun importPdfFromUri(
        uri: Uri,
        originalFileName: String,
        customCategoryId: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val inputStream = contentResolver.openInputStream(uri)
                ?: return@withContext ImportResult.Error("Could not open file from storage.")

            val bytes = inputStream.use { it.readBytes() }
            if (bytes.size < 4 || !isPdfHeader(bytes)) {
                return@withContext ImportResult.Error("Selected file is not a valid PDF document.")
            }

            importPdfBytes(bytes, originalFileName, customCategoryId)
        } catch (e: Exception) {
            e.printStackTrace()
            ImportResult.Error("Import failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    suspend fun importPdfBytes(
        bytes: ByteArray,
        originalFileName: String,
        customCategoryId: String? = null
    ): ImportResult = withContext(Dispatchers.IO) {
        val hash = PdfTextExtractor.computeSha256(bytes)
        val existing = bookDao.getBookByHash(hash)
        if (existing != null) {
            return@withContext ImportResult.Duplicate(existing)
        }

        val bookId = UUID.randomUUID().toString()
        val destFile = File(booksDir, "$bookId.pdf")
        FileOutputStream(destFile).use { it.write(bytes) }

        val pageCount = PdfEngine.getPageCount(destFile).coerceAtLeast(1)
        val coverFile = File(coversDir, "$bookId.png")
        PdfEngine.renderAndSaveCover(destFile, coverFile)

        val currentCategories = categoryDao.getCategoriesList()
        val extractedInfo = PdfTextExtractor.extractInfo(
            pdfBytes = bytes,
            originalFileName = originalFileName,
            totalPages = pageCount,
            availableCategories = currentCategories
        )
        val chosenCategory = customCategoryId ?: extractedInfo.categoryId

        // Index page texts into Room
        val pageEntities = extractedInfo.pageTexts.map { (pageNum, text) ->
            PdfPageTextEntity(
                id = "${bookId}_$pageNum",
                bookId = bookId,
                pageNumber = pageNum,
                extractedText = text
            )
        }
        if (pageEntities.isNotEmpty()) {
            pageTextDao.insertPages(pageEntities)
        }

        val tagsJson = JSONArray(extractedInfo.tags).toString()
        val book = BookEntity(
            id = bookId,
            title = extractedInfo.title,
            author = extractedInfo.author,
            categoryId = chosenCategory,
            filePath = destFile.absolutePath,
            coverImagePath = if (coverFile.exists()) coverFile.absolutePath else null,
            originalFileName = originalFileName,
            fileSize = destFile.length(),
            pageCount = pageCount,
            currentPage = 0,
            readingPercentage = 0f,
            visitedPagesJson = "[]",
            isFavorite = false,
            tagsJson = tagsJson,
            description = extractedInfo.description,
            notes = "",
            contentHash = hash,
            dateAdded = System.currentTimeMillis(),
            lastOpened = 0L,
            totalReadingTimeMs = 0L
        )

        bookDao.insertBook(book)
        ImportResult.Success(book, extractedInfo)
    }

    suspend fun importScannedPdf(
        pdfFile: File,
        customTitle: String,
        customCategoryId: String? = null,
        extraPageTexts: List<String> = emptyList()
    ): BookEntity = withContext(Dispatchers.IO) {
        val bytes = pdfFile.readBytes()
        val hash = PdfTextExtractor.computeSha256(bytes)
        val bookId = java.util.UUID.randomUUID().toString()
        val destFile = File(booksDir, "$bookId.pdf")
        pdfFile.copyTo(destFile, overwrite = true)

        val pageCount = PdfEngine.getPageCount(destFile).coerceAtLeast(1)
        val coverFile = File(coversDir, "$bookId.png")
        PdfEngine.renderAndSaveCover(destFile, coverFile)

        val currentCategories = categoryDao.getCategoriesList()
        val extractedInfo = PdfTextExtractor.extractInfo(
            pdfBytes = bytes,
            originalFileName = "$customTitle.pdf",
            totalPages = pageCount,
            availableCategories = currentCategories
        )
        val chosenCategory = customCategoryId ?: extractedInfo.categoryId

        // Index page texts into Room: prefer digitized text from scanner, fallback to PDF extractor
        val pageEntities = mutableListOf<com.example.data.model.PdfPageTextEntity>()
        if (extraPageTexts.isNotEmpty()) {
            for ((index, text) in extraPageTexts.withIndex()) {
                if (text.isNotBlank()) {
                    pageEntities.add(
                        com.example.data.model.PdfPageTextEntity(
                            id = "${bookId}_${index + 1}",
                            bookId = bookId,
                            pageNumber = index + 1,
                            extractedText = text
                        )
                    )
                }
            }
        }
        if (pageEntities.isEmpty()) {
            extractedInfo.pageTexts.forEach { (pageNum, text) ->
                pageEntities.add(
                    com.example.data.model.PdfPageTextEntity(
                        id = "${bookId}_$pageNum",
                        bookId = bookId,
                        pageNumber = pageNum,
                        extractedText = text
                    )
                )
            }
        }
        if (pageEntities.isNotEmpty()) {
            pageTextDao.insertPages(pageEntities)
        }

        val book = BookEntity(
            id = bookId,
            title = customTitle.ifBlank { extractedInfo.title },
            author = "Scanned Document",
            categoryId = chosenCategory,
            filePath = destFile.absolutePath,
            coverImagePath = if (coverFile.exists()) coverFile.absolutePath else null,
            originalFileName = "$customTitle.pdf",
            fileSize = destFile.length(),
            pageCount = pageCount,
            currentPage = 0,
            readingPercentage = 0f,
            visitedPagesJson = "[]",
            isFavorite = false,
            tagsJson = "[\"Scanned\",\"Digital Replica\"]",
            description = "High-fidelity computer-composed digital PDF replica created from scanned camera pages.",
            notes = "",
            contentHash = hash,
            dateAdded = System.currentTimeMillis(),
            lastOpened = 0L,
            totalReadingTimeMs = 0L
        )

        bookDao.insertBook(book)
        book
    }

    private fun isPdfHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val header = String(bytes.copyOfRange(0, 5.coerceAtMost(bytes.size)), Charsets.ISO_8859_1)
        return header.startsWith("%PDF")
    }

    suspend fun updateBook(book: BookEntity) = bookDao.updateBook(book)

    suspend fun toggleFavorite(bookId: String, currentFavorite: Boolean) {
        bookDao.updateFavorite(bookId, !currentFavorite)
    }

    suspend fun updateBookCategory(bookId: String, categoryId: String) {
        bookDao.updateCategory(bookId, categoryId)
    }

    suspend fun bulkUpdateCategory(bookIds: List<String>, categoryId: String) {
        bookDao.bulkUpdateCategory(bookIds, categoryId)
    }

    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId)
        if (book != null) {
            File(book.filePath).delete()
            book.coverImagePath?.let { File(it).delete() }
            pageTextDao.deletePagesForBook(bookId)
            bookmarkDao.deleteBookmarksForBook(bookId)
            bookDao.deleteBookById(bookId)
        }
    }

    suspend fun bulkDeleteBooks(bookIds: List<String>) = withContext(Dispatchers.IO) {
        for (id in bookIds) {
            deleteBook(id)
        }
    }

    suspend fun recordReadingProgress(
        bookId: String,
        newPage: Int,
        sessionDurationMs: Long = 0L
    ) = withContext(Dispatchers.IO) {
        val book = bookDao.getBookById(bookId) ?: return@withContext
        val visitedSet = try {
            val jsonArr = JSONArray(book.visitedPagesJson)
            val set = mutableSetOf<Int>()
            for (i in 0 until jsonArr.length()) {
                set.add(jsonArr.getInt(i))
            }
            set
        } catch (e: Exception) {
            mutableSetOf()
        }

        val wasVisited = visitedSet.contains(newPage)
        visitedSet.add(newPage)

        val totalPages = book.pageCount.coerceAtLeast(1)
        val percentage = ((visitedSet.size.toFloat() / totalPages.toFloat()) * 100f).coerceIn(0f, 100f)
        val updatedVisitedJson = JSONArray(visitedSet.toList()).toString()

        val updatedBook = book.copy(
            currentPage = newPage.coerceIn(0, totalPages - 1),
            readingPercentage = percentage,
            visitedPagesJson = updatedVisitedJson,
            lastOpened = System.currentTimeMillis(),
            totalReadingTimeMs = book.totalReadingTimeMs + sessionDurationMs
        )
        bookDao.updateBook(updatedBook)

        // Log session entry if duration > 1000ms or a new page was read
        if (sessionDurationMs > 1000L || !wasVisited) {
            val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val session = ReadingSessionEntity(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                sessionDate = todayStr,
                pagesRead = if (!wasVisited) 1 else 0,
                durationMs = sessionDurationMs,
                timestamp = System.currentTimeMillis()
            )
            readingSessionDao.insertSession(session)
        }
    }

    suspend fun addBookmark(bookId: String, pageNumber: Int, label: String = "") {
        val bm = BookmarkEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            pageNumber = pageNumber,
            label = label.ifBlank { "Page ${pageNumber + 1}" },
            createdAt = System.currentTimeMillis()
        )
        bookmarkDao.insertBookmark(bm)
    }

    suspend fun deleteBookmark(bookmarkId: String) {
        bookmarkDao.deleteBookmarkById(bookmarkId)
    }

    // Category Management
    suspend fun addCategory(category: CategoryEntity) = categoryDao.insertCategory(category)
    suspend fun updateCategory(category: CategoryEntity) = categoryDao.updateCategory(category)
    suspend fun deleteCategoryAndReassign(category: CategoryEntity, reassignToId: String) = withContext(Dispatchers.IO) {
        categoryDao.reassignCategoryBooks(category.id, reassignToId)
        categoryDao.deleteCategory(category)
    }

    // Backup & Restore
    suspend fun exportBackupJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())
        root.put("exportedBy", "BookShelf Android")

        val booksArr = JSONArray()
        // Note: getAllBooks is Flow; we can query database directly or via DAO
        val allBooksList = database.openHelper.readableDatabase.query("SELECT * FROM books").use { cursor ->
            val list = mutableListOf<JSONObject>()
            while (cursor.moveToNext()) {
                val obj = JSONObject()
                for (col in cursor.columnNames) {
                    val idx = cursor.getColumnIndex(col)
                    obj.put(col, cursor.getString(idx) ?: "")
                }
                list.add(obj)
            }
            list
        }
        for (b in allBooksList) booksArr.put(b)
        root.put("books", booksArr)

        val categoriesArr = JSONArray()
        database.openHelper.readableDatabase.query("SELECT * FROM categories").use { cursor ->
            while (cursor.moveToNext()) {
                val obj = JSONObject()
                for (col in cursor.columnNames) {
                    val idx = cursor.getColumnIndex(col)
                    obj.put(col, cursor.getString(idx) ?: "")
                }
                categoriesArr.put(obj)
            }
        }
        root.put("categories", categoriesArr)

        val bookmarksArr = JSONArray()
        database.openHelper.readableDatabase.query("SELECT * FROM bookmarks").use { cursor ->
            while (cursor.moveToNext()) {
                val obj = JSONObject()
                for (col in cursor.columnNames) {
                    val idx = cursor.getColumnIndex(col)
                    obj.put(col, cursor.getString(idx) ?: "")
                }
                bookmarksArr.put(obj)
            }
        }
        root.put("bookmarks", bookmarksArr)

        root.toString(2)
    }

    suspend fun restoreBackupJson(jsonString: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonString)
            if (!root.has("books") || !root.has("categories")) {
                return@withContext Result.failure(IllegalArgumentException("Invalid BookShelf backup file format: missing books or categories."))
            }

            val cats = root.getJSONArray("categories")
            for (i in 0 until cats.length()) {
                val c = cats.getJSONObject(i)
                categoryDao.insertCategory(
                    CategoryEntity(
                        id = c.getString("id"),
                        name = c.getString("name"),
                        colorHex = c.optString("colorHex", "#2563EB"),
                        iconName = c.optString("iconName", "Folder"),
                        description = c.optString("description", ""),
                        isDefault = c.optBoolean("isDefault", false),
                        sortOrder = c.optInt("sortOrder", 0)
                    )
                )
            }

            val booksArr = root.getJSONArray("books")
            var restoredBooksCount = 0
            for (i in 0 until booksArr.length()) {
                val b = booksArr.getJSONObject(i)
                val bookId = b.getString("id")
                val book = BookEntity(
                    id = bookId,
                    title = b.optString("title", "Untitled Document"),
                    author = b.optString("author", ""),
                    categoryId = b.optString("categoryId", "uncategorized"),
                    filePath = b.optString("filePath", ""),
                    coverImagePath = if (b.has("coverImagePath") && b.getString("coverImagePath").isNotBlank()) b.getString("coverImagePath") else null,
                    originalFileName = b.optString("originalFileName", "document.pdf"),
                    fileSize = b.optLong("fileSize", 0L),
                    pageCount = b.optInt("pageCount", 1),
                    currentPage = b.optInt("currentPage", 0),
                    readingPercentage = b.optDouble("readingPercentage", 0.0).toFloat(),
                    visitedPagesJson = b.optString("visitedPagesJson", "[]"),
                    isFavorite = b.optBoolean("isFavorite", false),
                    tagsJson = b.optString("tagsJson", "[]"),
                    description = b.optString("description", ""),
                    notes = b.optString("notes", ""),
                    contentHash = b.optString("contentHash", ""),
                    dateAdded = b.optLong("dateAdded", System.currentTimeMillis()),
                    lastOpened = b.optLong("lastOpened", 0L),
                    totalReadingTimeMs = b.optLong("totalReadingTimeMs", 0L)
                )
                bookDao.insertBook(book)
                restoredBooksCount++
            }

            val bms = root.optJSONArray("bookmarks")
            var restoredBmsCount = 0
            if (bms != null) {
                for (i in 0 until bms.length()) {
                    val b = bms.getJSONObject(i)
                    bookmarkDao.insertBookmark(
                        BookmarkEntity(
                            id = b.getString("id"),
                            bookId = b.getString("bookId"),
                            pageNumber = b.getInt("pageNumber"),
                            label = b.optString("label", "Page ${b.getInt("pageNumber") + 1}"),
                            createdAt = b.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                    restoredBmsCount++
                }
            }

            Result.success("Backup successfully restored! ($restoredBooksCount books, ${cats.length()} categories, $restoredBmsCount bookmarks)")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun removeDemoBooksIfPresent() = withContext(Dispatchers.IO) {
        val demoTitles = listOf(
            "BookShelf Quickstart & Manual",
            "Principles of Financial Accounting",
            "Applied Calculus & Linear Algebra Primer"
        )
        val demoBookIds = database.openHelper.readableDatabase.query("SELECT id, title, notes FROM books").use { cursor ->
            val list = mutableListOf<String>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val title = cursor.getString(1)
                val notes = cursor.getString(2) ?: ""
                if (demoTitles.contains(title) || notes.contains("Starter textbook in BookShelf library.")) {
                    list.add(id)
                }
            }
            list
        }
        for (id in demoBookIds) {
            deleteBook(id)
        }

        // Clean up pre-created categories as requested by user
        try {
            database.openHelper.writableDatabase.execSQL(
                "UPDATE books SET categoryId = 'cat_uncategorized' WHERE categoryId IN ('cat_banking', 'cat_bills', 'cat_accounting', 'cat_business', 'cat_mathematics', 'cat_education', 'cat_tech_manuals', 'cat_personal')"
            )
            database.openHelper.writableDatabase.execSQL(
                "DELETE FROM categories WHERE id IN ('cat_banking', 'cat_bills', 'cat_accounting', 'cat_business', 'cat_mathematics', 'cat_education', 'cat_tech_manuals', 'cat_personal')"
            )
            database.categoryDao().insertCategory(
                CategoryEntity(
                    id = "cat_uncategorized",
                    name = "Uncategorized",
                    colorHex = "#64748B",
                    iconName = "Folder",
                    description = "General and unassigned PDF documents",
                    isDefault = true,
                    sortOrder = 1
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
