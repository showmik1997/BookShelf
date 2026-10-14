package com.example.ui.screens.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.model.CategoryEntity
import com.example.data.preferences.UserPreferencesRepository
import com.example.data.repository.BookShelfRepository
import com.example.data.security.KeyStoreManager
import com.example.service.ai.DocumentElement
import com.example.service.ai.GeminiService
import com.example.service.ai.ReconstructedDocumentPage
import com.example.service.pdf.DigitalPdfComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ScannerInitialAction {
    NONE,
    CAMERA,
    GALLERY
}

data class ScannedPageItem(
    val file: File,
    val bitmap: Bitmap
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScannerDialog(
    categories: List<CategoryEntity>,
    repository: BookShelfRepository,
    geminiService: GeminiService,
    keyStoreManager: KeyStoreManager,
    initialAction: ScannerInitialAction = ScannerInitialAction.NONE,
    onDismiss: () -> Unit,
    onDocumentCreated: (bookId: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val scannedPages = remember { mutableStateListOf<ScannedPageItem>() }

    val defaultTitle = remember {
        val dateFormat = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault())
        "Scanned Doc - ${dateFormat.format(Date())}"
    }
    var documentTitle by remember { mutableStateOf(defaultTitle) }
    var selectedCategoryId by remember { mutableStateOf(categories.firstOrNull()?.id ?: "uncategorized") }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }

    var isProcessing by remember { mutableStateOf(false) }
    var processingStatus by remember { mutableStateOf("") }
    var processingProgress by remember { mutableStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Camera temp file reference
    var currentCameraTempFile by remember { mutableStateOf<File?>(null) }
    var currentCameraUri by remember { mutableStateOf<Uri?>(null) }

    // Activity Result Launchers
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            currentCameraTempFile?.let { file ->
                if (file.exists() && file.length() > 0) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            scannedPages.add(ScannedPageItem(file, bitmap))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCameraCapture(context) { file, uri ->
                currentCameraTempFile = file
                currentCameraUri = uri
                takePictureLauncher.launch(uri)
            }
        } else {
            Toast.makeText(context, "Camera permission is required to scan pages directly.", Toast.LENGTH_SHORT).show()
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            scope.launch(Dispatchers.IO) {
                for (uri in uris) {
                    try {
                        context.contentResolver.openInputStream(uri)?.use { stream ->
                            val tempFile = File.createTempFile("gallery_page_", ".jpg", context.cacheDir)
                            tempFile.outputStream().use { out -> stream.copyTo(out) }
                            val bmp = BitmapFactory.decodeFile(tempFile.absolutePath)
                            if (bmp != null) {
                                withContext(Dispatchers.Main) {
                                    scannedPages.add(ScannedPageItem(tempFile, bmp))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun startCamera() {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            launchCameraCapture(context) { file, uri ->
                currentCameraTempFile = file
                currentCameraUri = uri
                takePictureLauncher.launch(uri)
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(initialAction) {
        when (initialAction) {
            ScannerInitialAction.CAMERA -> startCamera()
            ScannerInitialAction.GALLERY -> galleryPickerLauncher.launch("image/*")
            ScannerInitialAction.NONE -> Unit
        }
    }

    Dialog(
        onDismissRequest = { if (!isProcessing) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .testTag("document_scanner_dialog")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DocumentScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AI Document Scanner",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Reconstruct pages as computer-composed digital PDF",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        enabled = !isProcessing
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Document Details Card
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = documentTitle,
                                onValueChange = { documentTitle = it },
                                label = { Text("Document Title") },
                                singleLine = true,
                                enabled = !isProcessing,
                                modifier = Modifier.fillMaxWidth().testTag("scanner_title_input")
                            )

                            // Category Selector
                            ExposedDropdownMenuBox(
                                expanded = categoryDropdownExpanded && !isProcessing,
                                onExpandedChange = { categoryDropdownExpanded = !categoryDropdownExpanded }
                            ) {
                                val selectedCat = categories.find { it.id == selectedCategoryId }
                                OutlinedTextField(
                                    value = selectedCat?.name ?: "Select Category",
                                    onValueChange = {},
                                    readOnly = true,
                                    label = { Text("Category") },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryDropdownExpanded) },
                                    enabled = !isProcessing,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .menuAnchor()
                                )

                                ExposedDropdownMenu(
                                    expanded = categoryDropdownExpanded,
                                    onDismissRequest = { categoryDropdownExpanded = false }
                                ) {
                                    categories.forEach { cat ->
                                        DropdownMenuItem(
                                            text = { Text(cat.name) },
                                            onClick = {
                                                selectedCategoryId = cat.id
                                                categoryDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Scanned Pages Section Header & Badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Scanned Pages",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = if (scannedPages.isNotEmpty()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "${scannedPages.size} page${if (scannedPages.size == 1) "" else "s"}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (scannedPages.isNotEmpty()) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }

                    // Perfectly Symmetrical, 50/50 Aligned Action Buttons for Camera & Gallery
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { startCamera() },
                            enabled = !isProcessing,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("scanner_open_camera_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CameraAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Camera",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = { galleryPickerLauncher.launch("image/*") },
                            enabled = !isProcessing,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .testTag("scanner_pick_gallery_button")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.PhotoLibrary,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Gallery",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // Pages Preview Grid / Row
                    if (scannedPages.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "No pages added yet",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Choose Camera to snap pages or Gallery to import photos",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Camera Card (50%)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(110.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { startCamera() }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.CameraAlt,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Scan Camera",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Take photo",
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    // Gallery Card (50%)
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(110.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { galleryPickerLauncher.launch("image/*") }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(12.dp),
                                            verticalArrangement = Arrangement.Center,
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.PhotoLibrary,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "Pick Gallery",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "Import images",
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            scannedPages.forEachIndexed { index, page ->
                                Card(
                                    shape = RoundedCornerShape(10.dp),
                                    elevation = CardDefaults.cardElevation(2.dp),
                                    modifier = Modifier
                                        .width(130.dp)
                                        .height(180.dp)
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        Image(
                                            bitmap = page.bitmap.asImageBitmap(),
                                            contentDescription = "Page ${index + 1}",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )

                                        // Badge
                                        Surface(
                                            color = MaterialTheme.colorScheme.primaryContainer,
                                            shape = RoundedCornerShape(bottomEnd = 8.dp),
                                            modifier = Modifier.align(Alignment.TopStart)
                                        ) {
                                            Text(
                                                text = "Page ${index + 1}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        // Delete Button
                                        if (!isProcessing) {
                                            IconButton(
                                                onClick = { scannedPages.removeAt(index) },
                                                modifier = Modifier
                                                    .align(Alignment.TopEnd)
                                                    .padding(2.dp)
                                                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), CircleShape)
                                                    .size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.Delete,
                                                    contentDescription = "Remove page",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Add More Page Card
                            if (!isProcessing) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier
                                        .width(100.dp)
                                        .height(180.dp)
                                        .clickable { startCamera() }
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxSize(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = "Add page",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            "Add Page",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Processing Indicator
                    AnimatedVisibility(visible = isProcessing) {
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = processingStatus,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { processingProgress },
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                )
                                Text(
                                    text = "Analyzing headings, exact tables, headers, footers & colors using Google Gemini to construct an authentic digital PDF...",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Error Notice
                    errorMessage?.let { err ->
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Error: $err",
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Bottom Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isProcessing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }

                    Button(
                        onClick = {
                            if (scannedPages.isEmpty()) {
                                Toast.makeText(context, "Please scan or add at least one page first.", Toast.LENGTH_SHORT).show()
                                return@Button
                            }

                            scope.launch {
                                isProcessing = true
                                errorMessage = null
                                processingProgress = 0.1f

                                try {
                                    val preferencesRepo = UserPreferencesRepository(context)
                                    val apiKey = try {
                                        preferencesRepo.preferencesFlow.first().geminiApiKey
                                    } catch (e: Exception) {
                                        ""
                                    }.ifBlank { getEffectiveApiKey(keyStoreManager) }.trim()

                                    val reconstructedPages = mutableListOf<ReconstructedDocumentPage>()
                                    val allExtractedTexts = mutableListOf<String>()

                                    val total = scannedPages.size
                                    for ((i, page) in scannedPages.withIndex()) {
                                        processingStatus = if (apiKey.isNotBlank()) {
                                            "Analyzing & transcribing Page ${i + 1} of $total with Gemini..."
                                        } else {
                                            "Composing Page ${i + 1} of $total..."
                                        }
                                        processingProgress = 0.15f + ((i.toFloat() / total) * 0.6f)

                                        if (apiKey.isNotBlank()) {
                                            val result = geminiService.reconstructDocumentPage(
                                                apiKey = apiKey,
                                                model = "gemini-3.8-flash",
                                                imageBitmap = page.bitmap
                                            )
                                            result.fold(
                                                onSuccess = { reconstructed ->
                                                    reconstructedPages.add(reconstructed)
                                                    val pageText = buildString {
                                                        if (reconstructed.pageTitle.isNotBlank()) appendLine(reconstructed.pageTitle)
                                                        for (el in reconstructed.elements) {
                                                            if (el.text.isNotBlank()) appendLine(el.text)
                                                            if (el.items.isNotEmpty()) appendLine(el.items.joinToString("\n"))
                                                            if (el.rows.isNotEmpty()) appendLine(el.rows.joinToString("\n") { r -> r.joinToString(" | ") })
                                                            if (el.key.isNotBlank()) appendLine("${el.key} ${el.value}")
                                                        }
                                                    }.trim()
                                                    allExtractedTexts.add(pageText)
                                                },
                                                onFailure = { err ->
                                                    reconstructedPages.add(createFallbackPage(page, i + 1))
                                                    allExtractedTexts.add("")
                                                }
                                            )
                                        } else {
                                            // Offline fallback: retains the original scanned high-res page image
                                            reconstructedPages.add(createFallbackPage(page, i + 1))
                                            allExtractedTexts.add("")
                                        }
                                    }

                                    processingStatus = "Composing vector digital typography & tables..."
                                    processingProgress = 0.85f

                                    val tempPdfFile = withContext(Dispatchers.IO) {
                                        File.createTempFile("composed_scan_", ".pdf", context.cacheDir)
                                    }

                                    val composeResult = DigitalPdfComposer.composeDocument(
                                        context = context,
                                        outputFile = tempPdfFile,
                                        documentTitle = documentTitle.trim(),
                                        pages = reconstructedPages
                                    )

                                    composeResult.fold(
                                        onSuccess = { finalPdfFile ->
                                            processingStatus = "Adding document to library & indexing..."
                                            processingProgress = 0.95f

                                            val createdBook = repository.importScannedPdf(
                                                pdfFile = finalPdfFile,
                                                customTitle = documentTitle.trim(),
                                                customCategoryId = selectedCategoryId,
                                                extraPageTexts = allExtractedTexts
                                            )

                                            isProcessing = false
                                            Toast.makeText(context, "Scanned document composed and saved to Library!", Toast.LENGTH_LONG).show()
                                            onDocumentCreated(createdBook.id)
                                        },
                                        onFailure = { e ->
                                            isProcessing = false
                                            errorMessage = "Failed to compose digital PDF: ${e.localizedMessage}"
                                        }
                                    )
                                } catch (e: Exception) {
                                    isProcessing = false
                                    errorMessage = "Scanning error: ${e.localizedMessage ?: "Unknown failure"}"
                                }
                            }
                        },
                        enabled = scannedPages.isNotEmpty() && !isProcessing,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .weight(2f)
                            .testTag("scanner_convert_to_pdf_button")
                    ) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isProcessing) "Composing PDF..." else "Convert to Digital PDF",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

private fun launchCameraCapture(context: Context, onReady: (File, Uri) -> Unit) {
    try {
        val tempFile = File.createTempFile("camera_scan_${System.currentTimeMillis()}_", ".jpg", context.cacheDir)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
        onReady(tempFile, uri)
    } catch (e: Exception) {
        Toast.makeText(context, "Could not initialize camera storage: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
    }
}

private fun getEffectiveApiKey(keyStoreManager: KeyStoreManager): String {
    val saved = keyStoreManager.getApiKey().trim()
    if (saved.isNotBlank()) return saved
    val buildKey = try {
        BuildConfig.GEMINI_API_KEY.trim()
    } catch (e: Throwable) {
        ""
    }
    return if (buildKey.isNotBlank() && buildKey != "MY_GEMINI_API_KEY") buildKey else ""
}

private fun createFallbackPage(page: ScannedPageItem, pageNumber: Int): ReconstructedDocumentPage {
    return ReconstructedDocumentPage(
        pageTitle = "Document Page $pageNumber",
        headerText = "BookShelf Scanned Document",
        footerText = "Page $pageNumber",
        elements = emptyList(),
        originalBitmap = page.bitmap
    )
}
