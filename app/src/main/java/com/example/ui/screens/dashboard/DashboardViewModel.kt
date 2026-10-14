package com.example.ui.screens.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.BookEntity
import com.example.data.model.ReadingSessionEntity
import com.example.data.repository.BookShelfRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class DailyActivity(
    val dayLabel: String, // e.g. "Mon", "Tue"
    val dateStr: String,
    val readingMinutes: Int,
    val pagesRead: Int
)

data class DashboardUiState(
    val totalPdfs: Int = 0,
    val totalReadingTimeMs: Long = 0L,
    val pagesVisitedToday: Int = 0,
    val documentsOpenedToday: Int = 0,
    val averageDailyMinutes: Int = 0,
    val weeklyActivity: List<DailyActivity> = emptyList(),
    val recentlyOpened: List<BookEntity> = emptyList(),
    val mostReadBooks: List<BookEntity> = emptyList(),
    val activeBooksProgress: List<BookEntity> = emptyList()
)

class DashboardViewModel(
    private val repository: BookShelfRepository
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = combine(
        repository.allBooks,
        repository.allSessions,
        repository.recentBooks,
        repository.mostReadBooks
    ) { books, sessions, recent, mostRead ->
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todaySessions = sessions.filter { it.sessionDate == todayStr }

        val pagesToday = todaySessions.sumOf { it.pagesRead }
        val docsOpenedToday = todaySessions.map { it.bookId }.distinct().size

        val totalTimeMs = books.sumOf { it.totalReadingTimeMs }

        // Compute past 7 days activity
        val weeklyList = mutableListOf<DailyActivity>()
        val cal = Calendar.getInstance()
        val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

        for (i in 6 downTo 0) {
            val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -i) }
            val dStr = dateFormat.format(c.time)
            val dLabel = dayFormat.format(c.time)

            val daySessions = sessions.filter { it.sessionDate == dStr }
            val mins = (daySessions.sumOf { it.durationMs } / (1000 * 60)).toInt()
            val pages = daySessions.sumOf { it.pagesRead }

            weeklyList.add(DailyActivity(dayLabel = dLabel, dateStr = dStr, readingMinutes = mins, pagesRead = pages))
        }

        val totalWeeklyMinutes = weeklyList.sumOf { it.readingMinutes }
        val avgDailyMins = (totalWeeklyMinutes / 7).coerceAtLeast(0)

        val inProgressBooks = books.filter { it.readingPercentage > 0f && it.readingPercentage < 100f }
            .sortedByDescending { it.lastOpened }
            .take(5)

        DashboardUiState(
            totalPdfs = books.size,
            totalReadingTimeMs = totalTimeMs,
            pagesVisitedToday = pagesToday,
            documentsOpenedToday = docsOpenedToday,
            averageDailyMinutes = avgDailyMins,
            weeklyActivity = weeklyList,
            recentlyOpened = recent.filter { it.lastOpened > 0 }.take(6),
            mostReadBooks = mostRead.filter { it.totalReadingTimeMs > 0 }.take(6),
            activeBooksProgress = inProgressBooks
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )
}
