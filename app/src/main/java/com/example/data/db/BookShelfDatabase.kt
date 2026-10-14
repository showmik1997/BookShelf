package com.example.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AiMessageEntity
import com.example.data.model.BookEntity
import com.example.data.model.BookmarkEntity
import com.example.data.model.CategoryEntity
import com.example.data.model.PdfPageTextEntity
import com.example.data.model.ReadingSessionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [
        CategoryEntity::class,
        BookEntity::class,
        BookmarkEntity::class,
        ReadingSessionEntity::class,
        PdfPageTextEntity::class,
        AiMessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class BookShelfDatabase : RoomDatabase() {

    abstract fun categoryDao(): CategoryDao
    abstract fun bookDao(): BookDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun pdfPageTextDao(): PdfPageTextDao
    abstract fun aiMessageDao(): AiMessageDao

    companion object {
        @Volatile
        private var INSTANCE: BookShelfDatabase? = null

        fun getDatabase(context: Context): BookShelfDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BookShelfDatabase::class.java,
                    "bookshelf_database.db"
                )
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            // Populate default categories
                            CoroutineScope(Dispatchers.IO).launch {
                                INSTANCE?.categoryDao()?.insertCategories(DEFAULT_CATEGORIES)
                            }
                        }
                    })
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        val DEFAULT_CATEGORIES = listOf(
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
    }
}
