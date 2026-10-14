package com.example.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.LocalMall
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.LocalMall
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class MainDestination(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    LIBRARY("library", "Library", Icons.Filled.MenuBook, Icons.Outlined.MenuBook),
    DASHBOARD("dashboard", "Dashboard", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    STORE("store", "PDF Store", Icons.Filled.LocalMall, Icons.Outlined.LocalMall),
    AI_ASSISTANT("ai_assistant", "Assistant", Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome),
    SETTINGS("settings", "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
}

object SubDestinations {
    const val READER = "reader/{bookId}"
    fun readerRoute(bookId: String) = "reader/$bookId"

    const val COVER_DESIGNER = "cover_designer/{bookId}"
    fun coverDesignerRoute(bookId: String) = "cover_designer/$bookId"

    const val STORE_BROWSER = "store_browser"
}
