package com.example.ui.screens.reader

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.preferences.EyeComfortMode
import com.example.ui.theme.EyeComfortDimBg
import com.example.ui.theme.EyeComfortSepiaBg
import com.example.ui.theme.EyeComfortWarmBg

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showBookmarksDropdown by remember { mutableStateOf(false) }
    var showEyeComfortDropdown by remember { mutableStateOf(false) }

    // Pinch-to-zoom scale and offset
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        if (scale > 1f) {
            val maxOffsetX = (scale - 1) * 500f
            val maxOffsetY = (scale - 1) * 800f
            offset = Offset(
                x = (offset.x + offsetChange.x).coerceIn(-maxOffsetX, maxOffsetX),
                y = (offset.y + offsetChange.y).coerceIn(-maxOffsetY, maxOffsetY)
            )
        } else {
            offset = Offset.Zero
        }
    }

    // Back handler exits full screen or navigates back
    BackHandler {
        if (!uiState.isControlsVisible) {
            viewModel.showControlsTemporarily()
        } else {
            onNavigateBack()
        }
    }

    // Determine reader background color and page color filter based on Eye Comfort mode
    val readerBgColor = when (uiState.eyeComfortMode) {
        EyeComfortMode.OFF -> MaterialTheme.colorScheme.background
        EyeComfortMode.WARM -> EyeComfortWarmBg
        EyeComfortMode.SEPIA -> EyeComfortSepiaBg
        EyeComfortMode.DIM -> EyeComfortDimBg
    }

    val pageColorFilter = when (uiState.eyeComfortMode) {
        EyeComfortMode.OFF -> null
        EyeComfortMode.WARM -> ColorFilter.tint(Color(0xFFFFF6D6), BlendMode.Multiply)
        EyeComfortMode.SEPIA -> ColorFilter.tint(Color(0xFFF3E8CE), BlendMode.Multiply)
        EyeComfortMode.DIM -> ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    -0.85f, 0f, 0f, 0f, 225f,
                    0f, -0.85f, 0f, 0f, 225f,
                    0f, 0f, -0.85f, 0f, 225f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(readerBgColor)
            .testTag("reader_screen_container")
    ) {
        // Main PDF Page Viewer Area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            viewModel.toggleControlsVisibility()
                        },
                        onDoubleTap = {
                            scale = if (scale > 1f) 1f else 2f
                            offset = Offset.Zero
                        }
                    )
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
                .transformable(state = transformState),
            contentAlignment = Alignment.Center
        ) {
            val currentBitmap = uiState.cachedPages[uiState.currentPage]
            if (currentBitmap != null && !currentBitmap.isRecycled) {
                Image(
                    bitmap = currentBitmap.asImageBitmap(),
                    contentDescription = "PDF Page ${uiState.currentPage + 1}",
                    colorFilter = pageColorFilter,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 8.dp)
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(36.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Rendering Page ${uiState.currentPage + 1}...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Top Toolbar Overlay (Full-screen auto-hideable)
        AnimatedVisibility(
            visible = uiState.isControlsVisible,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = uiState.book?.title ?: "Document Reader",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Page ${uiState.currentPage + 1} of ${uiState.totalPages} • ${(uiState.book?.readingPercentage ?: 0f).toInt()}% read",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("reader_back_button")
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        // Eye Comfort dropdown selector
                        Box {
                            IconButton(onClick = { showEyeComfortDropdown = true }) {
                                Icon(
                                    imageVector = Icons.Filled.RemoveRedEye,
                                    contentDescription = "Eye Comfort",
                                    tint = if (uiState.eyeComfortMode != EyeComfortMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            DropdownMenu(
                                expanded = showEyeComfortDropdown,
                                onDismissRequest = { showEyeComfortDropdown = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Eye Comfort: Off" + if (uiState.eyeComfortMode == EyeComfortMode.OFF) " ✓" else "") },
                                    onClick = {
                                        viewModel.setEyeComfortMode(EyeComfortMode.OFF)
                                        showEyeComfortDropdown = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Warm (Gentle Yellow)" + if (uiState.eyeComfortMode == EyeComfortMode.WARM) " ✓" else "") },
                                    onClick = {
                                        viewModel.setEyeComfortMode(EyeComfortMode.WARM)
                                        showEyeComfortDropdown = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sepia (Classic Paper)" + if (uiState.eyeComfortMode == EyeComfortMode.SEPIA) " ✓" else "") },
                                    onClick = {
                                        viewModel.setEyeComfortMode(EyeComfortMode.SEPIA)
                                        showEyeComfortDropdown = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Dim (Low Light)" + if (uiState.eyeComfortMode == EyeComfortMode.DIM) " ✓" else "") },
                                    onClick = {
                                        viewModel.setEyeComfortMode(EyeComfortMode.DIM)
                                        showEyeComfortDropdown = false
                                    }
                                )
                            }
                        }

                        // Bookmarks dropdown selector
                        Box {
                            IconButton(onClick = { showBookmarksDropdown = true }) {
                                Icon(
                                    imageVector = if (uiState.bookmarks.any { it.pageNumber == uiState.currentPage }) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                    contentDescription = "Bookmarks",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }

                            DropdownMenu(
                                expanded = showBookmarksDropdown,
                                onDismissRequest = { showBookmarksDropdown = false },
                                modifier = Modifier.width(260.dp)
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.BookmarkAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text("Bookmark Page ${uiState.currentPage + 1}", fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    onClick = {
                                        viewModel.addBookmarkAtCurrentPage()
                                        showBookmarksDropdown = false
                                    }
                                )

                                if (uiState.bookmarks.isNotEmpty()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        modifier = Modifier.fillMaxWidth().height(1.dp)
                                    ) {}

                                    uiState.bookmarks.forEach { bm ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "Page ${bm.pageNumber + 1}${if (bm.label.isNotBlank() && bm.label != "Page ${bm.pageNumber + 1}") " • ${bm.label}" else ""}",
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    // Individual X icon to remove bookmark immediately without separate remove button
                                                    IconButton(
                                                        onClick = { viewModel.removeBookmark(bm.id) },
                                                        modifier = Modifier.size(24.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Close,
                                                            contentDescription = "Remove bookmark",
                                                            tint = MaterialTheme.colorScheme.error,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            },
                                            onClick = {
                                                viewModel.jumpToPage(bm.pageNumber)
                                                showBookmarksDropdown = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            }
        }

        // Bottom Page Navigation Bar (Full-screen auto-hideable)
        AnimatedVisibility(
            visible = uiState.isControlsVisible,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 8.dp,
                shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // Page scrubber slider
                    Slider(
                        value = uiState.currentPage.toFloat(),
                        onValueChange = { viewModel.jumpToPage(it.toInt()) },
                        valueRange = 0f..(uiState.totalPages - 1).coerceAtLeast(0).toFloat(),
                        steps = (uiState.totalPages - 2).coerceAtLeast(0),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Navigation buttons and page counter
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.prevPage() },
                            enabled = uiState.currentPage > 0
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Previous Page")
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "Page ${uiState.currentPage + 1} / ${uiState.totalPages}",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        IconButton(
                            onClick = { viewModel.nextPage() },
                            enabled = uiState.currentPage < uiState.totalPages - 1
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Next Page")
                        }
                    }
                }
            }
        }
    }
}
