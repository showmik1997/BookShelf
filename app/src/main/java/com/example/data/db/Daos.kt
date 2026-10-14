package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.AiMessageEntity
import com.example.data.model.BookEntity
import com.example.data.model.BookmarkEntity
import com.example.data.model.CategoryEntity
import com.example.data.model.PdfPageTextEntity
import com.example.data.model.ReadingSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    fun getAllCategories(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder ASC, name ASC")
    suspend fun getCategoriesList(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun getCategoryById(id: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategories(categories: List<CategoryEntity>)

    @Update
    suspend fun updateCategory(category: CategoryEntity)

    @Delete
    suspend fun deleteCategory(category: CategoryEntity)

    @Query("UPDATE books SET categoryId = :newCategoryId WHERE categoryId = :oldCategoryId")
    suspend fun reassignCategoryBooks(oldCategoryId: String, newCategoryId: String)
}

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY dateAdded DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    fun getBookByIdFlow(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE contentHash = :hash LIMIT 1")
    suspend fun getBookByHash(hash: String): BookEntity?

    @Query("SELECT * FROM books WHERE isFavorite = 1 ORDER BY lastOpened DESC")
    fun getFavoriteBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE lastOpened > 0 ORDER BY lastOpened DESC LIMIT :limit")
    fun getRecentBooks(limit: Int = 10): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY totalReadingTimeMs DESC LIMIT :limit")
    fun getMostReadBooks(limit: Int = 10): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("UPDATE books SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun updateFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE books SET categoryId = :categoryId WHERE id = :id")
    suspend fun updateCategory(id: String, categoryId: String)

    @Query("UPDATE books SET categoryId = :categoryId WHERE id IN (:ids)")
    suspend fun bulkUpdateCategory(ids: List<String>, categoryId: String)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookById(id: String)

    @Query("DELETE FROM books WHERE id IN (:ids)")
    suspend fun bulkDeleteBooks(ids: List<String>)

    @Query("SELECT COUNT(*) FROM books")
    fun getTotalBookCount(): Flow<Int>
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY pageNumber ASC")
    fun getBookmarksForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmarkById(id: String)

    @Query("DELETE FROM bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksForBook(bookId: String)
}

@Dao
interface ReadingSessionDao {
    @Query("SELECT * FROM reading_sessions ORDER BY timestamp DESC")
    fun getAllSessions(): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE sessionDate = :date")
    fun getSessionsForDate(date: String): Flow<List<ReadingSessionEntity>>

    @Query("SELECT * FROM reading_sessions WHERE timestamp >= :sinceTimestamp ORDER BY timestamp ASC")
    fun getSessionsSince(sinceTimestamp: Long): Flow<List<ReadingSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ReadingSessionEntity)
}

@Dao
interface PdfPageTextDao {
    @Query("SELECT * FROM pdf_page_texts WHERE bookId = :bookId ORDER BY pageNumber ASC")
    suspend fun getPagesForBook(bookId: String): List<PdfPageTextEntity>

    @Query("SELECT * FROM pdf_page_texts WHERE bookId = :bookId AND pageNumber = :pageNumber LIMIT 1")
    suspend fun getPage(bookId: String, pageNumber: Int): PdfPageTextEntity?

    @Query("SELECT * FROM pdf_page_texts WHERE extractedText LIKE '%' || :query || '%' LIMIT 100")
    suspend fun searchAcrossAllBooks(query: String): List<PdfPageTextEntity>

    @Query("SELECT * FROM pdf_page_texts WHERE bookId = :bookId AND extractedText LIKE '%' || :query || '%' LIMIT 50")
    suspend fun searchInBook(bookId: String, query: String): List<PdfPageTextEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPages(pages: List<PdfPageTextEntity>)

    @Query("DELETE FROM pdf_page_texts WHERE bookId = :bookId")
    suspend fun deletePagesForBook(bookId: String)
}

@Dao
interface AiMessageDao {
    @Query("SELECT * FROM ai_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<AiMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: AiMessageEntity)

    @Query("DELETE FROM ai_messages")
    suspend fun clearAllMessages()
}
