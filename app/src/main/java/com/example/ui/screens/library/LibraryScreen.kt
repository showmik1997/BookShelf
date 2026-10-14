package com.example.ui.screens.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.example.data.model.BookEntity
import com.example.data.model.CategoryEntity
import com.example.data.preferences.LayoutMode
import com.example.data.preferences.SortOption
import com.example.data.repository.BookShelfRepository
import com.example.data.security.KeyStoreManager
import com.example.service.ai.GeminiService
import com.example.ui.components.BookGridTile
import com.example.ui.components.BookListTile
import com.example.ui.components.CategoryIconHelper
import com.example.ui.screens.scanner.DocumentScannerDialog
import com.example.ui.screens.scanner.ScannerInitialAction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    repository: BookShelfRepository,
    geminiService: GeminiService,
    keyStoreManager: KeyStoreManager,
    onOpenReader: (String) -> Unit,
    onOpenCoverDesigner: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCategoryManagerDialog by remember { mutableStateOf(false) }
    var showBulkCategoryDialog by remember { mutableStateOf(false) }
    var showBulkDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showAddOrScanSheet by remember { mutableStateOf(false) }
    var showDocumentScanner by remember { mutableStateOf(false) }
    var scannerInitialAction by remember { mutableStateOf(ScannerInitialAction.NONE) }

    // SAF File pickers
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            viewModel.importUris(context, uris)
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let {
            // Provide feedback for folder selection
            viewModel.importUris(context, listOf(it))
        }
    }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusMessage()
        }
    }

    // Dialogs
    uiState.editingBook?.let { book ->
        EditBookDialog(
            book = book,
            categories = uiState.categories,
            onDismiss = { viewModel.setEditingBook(null) },
            onSave = { updated -> viewModel.saveBookChanges(updated) }
        )
    }

    if (showCategoryManagerDialog) {
        CategoryManagementDialog(
            categories = uiState.categories,
            onDismiss = { showCategoryManagerDialog = false },
            onAddCategory = { viewModel.addCategory(it) },
            onDeleteCategory = { cat, reassignTo -> viewModel.deleteCategoryAndReassign(cat, reassignTo) }
        )
    }

    if (showBulkCategoryDialog) {
        AlertDialog(
            onDismissRequest = { showBulkCategoryDialog = false },
            title = { Text("Move ${uiState.selectedBookIds.size} Document(s)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    uiState.categories.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    viewModel.bulkChangeCategory(cat.id)
                                    showBulkCategoryDialog = false
                                }
                                .padding(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(CategoryIconHelper.parseColor(cat.colorHex), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(cat.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showBulkCategoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBulkDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBulkDeleteConfirmDialog = false },
            title = { Text("Delete ${uiState.selectedBookIds.size} Selected Documents?") },
            text = { Text("This will permanently remove these files from your BookShelf library. The original files on your device will NOT be modified.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.bulkDeleteSelected()
                        showBulkDeleteConfirmDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBulkDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showAddOrScanSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddOrScanSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp, top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Add Documents",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(bottom = 4.dp)
                )

                // Option 1: Add PDF
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            showAddOrScanSheet = false
                            pdfPickerLauncher.launch(arrayOf("application/pdf"))
                        }
                        .testTag("option_add_pdf")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PictureAsPdf,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Add PDF",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Import existing PDF files from your device storage",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Option 2: Scan Document
                Surface(
                    onClick = {
                        showAddOrScanSheet = false
                        scannerInitialAction = ScannerInitialAction.NONE
                        showDocumentScanner = true
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("option_scan_document")
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DocumentScanner,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Scan Document",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "AI Composed",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Camera scan pages into an authentic computer-composed replica PDF",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDocumentScanner) {
        DocumentScannerDialog(
            categories = uiState.categories,
            repository = repository,
            geminiService = geminiService,
            keyStoreManager = keyStoreManager,
            initialAction = scannerInitialAction,
            onDismiss = {
                showDocumentScanner = false
                scannerInitialAction = ScannerInitialAction.NONE
            },
            onDocumentCreated = { createdBookId ->
                showDocumentScanner = false
                scannerInitialAction = ScannerInitialAction.NONE
                onOpenReader(createdBookId)
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (uiState.isSelectionMode) {
                // Multi-selection Top Bar
                TopAppBar(
                    title = { Text("${uiState.selectedBookIds.size} Selected") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close selection")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showBulkCategoryDialog = true }) {
                            Icon(Icons.Filled.DriveFileMove, contentDescription = "Change Category")
                        }
                        IconButton(onClick = { showBulkDeleteConfirmDialog = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete Selected", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            } else {
                // Standard Top Bar
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "BookShelf",
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                            )
                        }
                    },
                    actions = {
                        // Categories manager
                        IconButton(onClick = { showCategoryManagerDialog = true }) {
                            Icon(Icons.Filled.Category, contentDescription = "Categories")
                        }

                        // Sort Menu Button
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Date Added" + if (uiState.sortOption == SortOption.DATE_ADDED) " ✓" else "") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.DATE_ADDED)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Title (A-Z)" + if (uiState.sortOption == SortOption.TITLE) " ✓" else "") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.TITLE)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Recently Opened" + if (uiState.sortOption == SortOption.LAST_OPENED) " ✓" else "") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.LAST_OPENED)
                                        showSortMenu = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Reading Progress" + if (uiState.sortOption == SortOption.READING_PROGRESS) " ✓" else "") },
                                    onClick = {
                                        viewModel.setSortOption(SortOption.READING_PROGRESS)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }

                        // Grid / List Toggle
                        IconButton(onClick = { viewModel.toggleLayoutMode() }) {
                            Icon(
                                imageVector = if (uiState.layoutMode == LayoutMode.GRID) Icons.Filled.ViewList else Icons.Filled.GridView,
                                contentDescription = "Toggle view"
                            )
                        }
                    }
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(
                    onClick = { showAddOrScanSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("fab_import_pdf")
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add PDF or Scan")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search title, author, tags, notes...", style = MaterialTheme.typography.bodyMedium) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotBlank()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("library_search_input")
                )
            }

            // Category Filter Chips (Horizontal Scroll)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // All Categories Chip
                FilterChip(
                    selected = uiState.selectedCategoryId == null,
                    onClick = { viewModel.setSelectedCategory(null) },
                    label = { Text("All") },
                    colors = FilterChipDefaults.filterChipColors()
                )

                // Favorites Toggle Chip
                FilterChip(
                    selected = uiState.filterFavoritesOnly,
                    onClick = { viewModel.setFavoritesOnly(!uiState.filterFavoritesOnly) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Favorite,
                            contentDescription = null,
                            tint = if (uiState.filterFavoritesOnly) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("Favorites") }
                )

                // Category items
                uiState.categories.forEach { cat ->
                    val isSelected = uiState.selectedCategoryId == cat.id
                    val catColor = CategoryIconHelper.parseColor(cat.colorHex)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            viewModel.setSelectedCategory(if (isSelected) null else cat.id)
                        },
                        leadingIcon = {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(catColor, CircleShape)
                            )
                        },
                        label = { Text(cat.name) }
                    )
                }
            }

            // Reading Status Filters (All, Unread, In Progress, Completed)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ReadingFilter.values().forEach { filter ->
                    val isSelected = uiState.readingFilter == filter
                    val labelText = when (filter) {
                        ReadingFilter.ALL -> "All Status"
                        ReadingFilter.UNREAD -> "Unread"
                        ReadingFilter.IN_PROGRESS -> "In Progress"
                        ReadingFilter.COMPLETED -> "Completed"
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { viewModel.setReadingFilter(filter) }
                    ) {
                        Text(
                            text = labelText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Loading indicator during background import
            if (uiState.isImporting) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Importing & indexing PDF documents...", style = MaterialTheme.typography.bodySmall)
                }
            }

            // Book Content (Grid vs List)
            if (uiState.books.isEmpty() && !uiState.isImporting) {
                // Empty State
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.MenuBook,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = if (uiState.searchQuery.isNotBlank() || uiState.selectedCategoryId != null)
                                "No documents match your filter"
                            else
                                "Your BookShelf is empty",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (uiState.searchQuery.isNotBlank() || uiState.selectedCategoryId != null)
                                "Try clearing your search query or choosing another category."
                            else
                                "Import PDFs from your device storage or browse the Store to add books to your personal library.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(0.85f)
                        )

                        Spacer(modifier = Modifier.height(20.dp))

                        TextButton(
                            onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) }
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Import PDF Documents")
                        }
                    }
                }
            } else {
                if (uiState.layoutMode == LayoutMode.GRID) {
                    // Grid View: Compact, top-left aligned, adaptive columns
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 150.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize().testTag("library_grid")
                    ) {
                        items(uiState.books, key = { it.id }) { book ->
                            val cat = uiState.categories.find { it.id == book.categoryId }
                            BookGridTile(
                                book = book,
                                category = cat,
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = uiState.selectedBookIds.contains(book.id),
                                onClick = { onOpenReader(book.id) },
                                onLongClick = { viewModel.toggleSelection(book.id) },
                                onFavoriteToggle = { viewModel.toggleFavorite(book) },
                                onEditClick = { viewModel.setEditingBook(book) },
                                onCoverDesignerClick = { onOpenCoverDesigner(book.id) },
                                onDeleteClick = { viewModel.deleteBook(book.id) }
                            )
                        }
                    }
                } else {
                    // List View
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize().testTag("library_list")
                    ) {
                        items(uiState.books, key = { it.id }) { book ->
                            val cat = uiState.categories.find { it.id == book.categoryId }
                            BookListTile(
                                book = book,
                                category = cat,
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = uiState.selectedBookIds.contains(book.id),
                                onClick = { onOpenReader(book.id) },
                                onLongClick = { viewModel.toggleSelection(book.id) },
                                onFavoriteToggle = { viewModel.toggleFavorite(book) },
                                onEditClick = { viewModel.setEditingBook(book) },
                                onCoverDesignerClick = { onOpenCoverDesigner(book.id) },
                                onDeleteClick = { viewModel.deleteBook(book.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
