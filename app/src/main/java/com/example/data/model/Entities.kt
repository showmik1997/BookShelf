package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: String,
    val iconName: String, // e.g. "AccountBalance", "Receipt", "Calculate", "School", etc.
    val description: String = "",
    val isDefault: Boolean = false,
    val sortOrder: Int = 0
)

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String = "",
    val categoryId: String,
    val filePath: String, // Internal app-managed path: e.g. files/books/{id}.pdf
    val coverImagePath: String? = null, // e.g. files/covers/{id}.png
    val originalFileName: String,
    val fileSize: Long = 0L,
    val pageCount: Int = 1,
    val currentPage: Int = 0, // 0-indexed
    val readingPercentage: Float = 0f, // 0..100
    val visitedPagesJson: String = "[]", // JSON array of unique page numbers visited: e.g. "[0, 1, 2]"
    val isFavorite: Boolean = false,
    val tagsJson: String = "[]", // JSON array of string tags
    val description: String = "",
    val notes: String = "",
    val contentHash: String = "", // SHA-256 for duplicate detection
    val dateAdded: Long = System.currentTimeMillis(),
    val lastOpened: Long = 0L,
    val totalReadingTimeMs: Long = 0L
)

@Entity(tableName = "bookmarks")
data class BookmarkEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val pageNumber: Int, // 0-indexed
    val label: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "reading_sessions")
data class ReadingSessionEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val sessionDate: String, // YYYY-MM-DD
    val pagesRead: Int = 1,
    val durationMs: Long = 0L,
    val timestamp: Long = System.currentTimeMillis()
)

@Entity(tableName = "pdf_page_texts")
data class PdfPageTextEntity(
    @PrimaryKey val id: String, // "${bookId}_${pageNumber}"
    val bookId: String,
    val pageNumber: Int,
    val extractedText: String
)

@Entity(tableName = "ai_messages")
data class AiMessageEntity(
    @PrimaryKey val id: String,
    val role: String, // "user", "assistant", "system"
    val content: String,
    val sourcesJson: String? = null, // JSON array of SourceRef { bookId, bookTitle, pageNumber, snippet }
    val timestamp: Long = System.currentTimeMillis()
)
