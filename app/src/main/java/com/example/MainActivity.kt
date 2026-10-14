package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.db.BookShelfDatabase
import com.example.data.preferences.ThemeMode
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BookShelfRepository
import com.example.data.security.KeyStoreManager
import com.example.service.ai.BookShelfRagEngine
import com.example.service.ai.GeminiService
import com.example.ui.navigation.MainDestination
import com.example.ui.screens.ai.AiAssistantScreen
import com.example.ui.screens.ai.AiAssistantViewModel
import com.example.ui.screens.cover.CoverDesignerScreen
import com.example.ui.screens.cover.CoverDesignerViewModel
import com.example.ui.screens.dashboard.DashboardScreen
import com.example.ui.screens.dashboard.DashboardViewModel
import com.example.ui.screens.library.LibraryScreen
import com.example.ui.screens.library.LibraryViewModel
import com.example.ui.screens.reader.ReaderScreen
import com.example.ui.screens.reader.ReaderViewModel
import com.example.ui.screens.settings.SettingsScreen
import com.example.ui.screens.settings.SettingsViewModel
import com.example.ui.screens.store.StoreBrowserScreen
import com.example.ui.screens.store.StoreScreen
import com.example.ui.theme.BookShelfTheme

sealed class ActiveScreen {
    data class Main(val destination: MainDestination) : ActiveScreen()
    data class Reader(val bookId: String) : ActiveScreen()
    data class CoverDesigner(val bookId: String) : ActiveScreen()
    data class StoreBrowser(val url: String, val title: String) : ActiveScreen()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BookShelfApp()
        }
    }
}

@Composable
fun BookShelfApp() {
    val context = LocalContext.current

    // Initialize core singletons
    val database = remember { BookShelfDatabase.getDatabase(context) }
    val repository = remember { BookShelfRepository(context, database) }
    val keyStoreManager = remember { KeyStoreManager(context) }
    val preferencesRepository = remember { UserPreferencesRepository(context) }
    val geminiService = remember { GeminiService() }
    val ragEngine = remember {
        BookShelfRagEngine(
            bookDao = database.bookDao(),
            pageTextDao = database.pdfPageTextDao(),
            geminiService = geminiService
        )
    }

    // ViewModels
    val libraryViewModel = remember { LibraryViewModel(repository, preferencesRepository) }
    val dashboardViewModel = remember { DashboardViewModel(repository) }
    val aiAssistantViewModel = remember {
        AiAssistantViewModel(
            repository = repository,
            ragEngine = ragEngine,
            aiMessageDao = database.aiMessageDao(),
            keyStoreManager = keyStoreManager,
            preferencesRepository = preferencesRepository
        )
    }
    val settingsViewModel = remember {
        SettingsViewModel(
            repository = repository,
            keyStoreManager = keyStoreManager,
            preferencesRepository = preferencesRepository,
            geminiService = geminiService
        )
    }

    // App Preferences & Theme State
    val prefs by preferencesRepository.preferencesFlow.collectAsStateWithLifecycle(initialValue = com.example.data.preferences.AppPreferences())
    val isSystemDark = isSystemInDarkTheme()
    val isDarkTheme = when (prefs.themeMode) {
        ThemeMode.SYSTEM -> isSystemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    var activeScreen by remember { mutableStateOf<ActiveScreen>(ActiveScreen.Main(MainDestination.LIBRARY)) }

    BookShelfTheme(darkTheme = isDarkTheme) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTabletOrExpanded = maxWidth >= 720.dp
            val isReaderMode = activeScreen is ActiveScreen.Reader

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    if (!isTabletOrExpanded && !isReaderMode) {
                        val currentMain = (activeScreen as? ActiveScreen.Main)?.destination ?: MainDestination.LIBRARY
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.testTag("bottom_nav_bar")
                        ) {
                            MainDestination.values().forEach { destination ->
                                val selected = currentMain == destination
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { activeScreen = ActiveScreen.Main(destination) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                            contentDescription = destination.title
                                        )
                                    },
                                    label = { Text(destination.title) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.testTag("nav_${destination.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    // Tablet Side Navigation Rail
                    if (isTabletOrExpanded && !isReaderMode) {
                        val currentMain = (activeScreen as? ActiveScreen.Main)?.destination ?: MainDestination.LIBRARY
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(88.dp)
                                .testTag("navigation_rail")
                        ) {
                            Spacer(modifier = Modifier.height(16.dp))
                            MainDestination.values().forEach { destination ->
                                val selected = currentMain == destination
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { activeScreen = ActiveScreen.Main(destination) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                            contentDescription = destination.title
                                        )
                                    },
                                    label = { Text(destination.title) },
                                    colors = NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                    ),
                                    modifier = Modifier.testTag("rail_${destination.route}")
                                )
                            }
                        }
                    }

                    // Content Area
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (val screen = activeScreen) {
                            is ActiveScreen.Main -> {
                                when (screen.destination) {
                                    MainDestination.LIBRARY -> {
                                        LibraryScreen(
                                            viewModel = libraryViewModel,
                                            repository = repository,
                                            geminiService = geminiService,
                                            keyStoreManager = keyStoreManager,
                                            onOpenReader = { bookId -> activeScreen = ActiveScreen.Reader(bookId) },
                                            onOpenCoverDesigner = { bookId -> activeScreen = ActiveScreen.CoverDesigner(bookId) }
                                        )
                                    }
                                    MainDestination.DASHBOARD -> {
                                        DashboardScreen(
                                            viewModel = dashboardViewModel,
                                            onOpenReader = { bookId -> activeScreen = ActiveScreen.Reader(bookId) }
                                        )
                                    }
                                    MainDestination.STORE -> {
                                        StoreScreen(
                                            onOpenStoreBrowser = { url, title ->
                                                activeScreen = ActiveScreen.StoreBrowser(url, title)
                                            }
                                        )
                                    }
                                    MainDestination.AI_ASSISTANT -> {
                                        AiAssistantScreen(
                                            viewModel = aiAssistantViewModel,
                                            onOpenBookAtPage = { bookId -> activeScreen = ActiveScreen.Reader(bookId) },
                                            onNavigateToSettings = { activeScreen = ActiveScreen.Main(MainDestination.SETTINGS) }
                                        )
                                    }
                                    MainDestination.SETTINGS -> {
                                        SettingsScreen(
                                            viewModel = settingsViewModel
                                        )
                                    }
                                }
                            }
                            is ActiveScreen.Reader -> {
                                val readerViewModel = remember(screen.bookId) {
                                    ReaderViewModel(screen.bookId, repository, preferencesRepository)
                                }
                                ReaderScreen(
                                    viewModel = readerViewModel,
                                    onNavigateBack = {
                                        activeScreen = ActiveScreen.Main(MainDestination.LIBRARY)
                                    }
                                )
                            }
                            is ActiveScreen.CoverDesigner -> {
                                val coverViewModel = remember(screen.bookId) {
                                    CoverDesignerViewModel(screen.bookId, repository)
                                }
                                CoverDesignerScreen(
                                    viewModel = coverViewModel,
                                    onNavigateBack = {
                                        activeScreen = ActiveScreen.Main(MainDestination.LIBRARY)
                                    }
                                )
                            }
                            is ActiveScreen.StoreBrowser -> {
                                StoreBrowserScreen(
                                    initialUrl = screen.url,
                                    storeTitle = screen.title,
                                    repository = repository,
                                    onNavigateBackToLibrary = {
                                        activeScreen = ActiveScreen.Main(MainDestination.LIBRARY)
                                    },
                                    onOpenBookInReader = { bookId ->
                                        activeScreen = ActiveScreen.Reader(bookId)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
