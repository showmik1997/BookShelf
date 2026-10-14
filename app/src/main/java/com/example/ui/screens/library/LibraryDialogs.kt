package com.example.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.BookEntity
import com.example.data.model.CategoryEntity
import com.example.service.pdf.ExtractedPdfInfo
import com.example.ui.components.CategoryIconHelper
import org.json.JSONArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookDialog(
    book: BookEntity,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (BookEntity) -> Unit
) {
    var title by remember { mutableStateOf(book.title) }
    var author by remember { mutableStateOf(book.author) }
    var categoryId by remember { mutableStateOf(book.categoryId) }
    var tagsString by remember {
        val initialTags = try {
            val arr = JSONArray(book.tagsJson)
            (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
        } catch (e: Exception) {
            ""
        }
        mutableStateOf(initialTags)
    }
    var description by remember { mutableStateOf(book.description) }
    var notes by remember { mutableStateOf(book.notes) }

    var categoryExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Document Details") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author / Publisher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                ExposedDropdownMenuBox(
                    expanded = categoryExpanded,
                    onExpandedChange = { categoryExpanded = !categoryExpanded }
                ) {
                    val currentCatName = categories.find { it.id == categoryId }?.name ?: "Select Category"
                    OutlinedTextField(
                        value = currentCatName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Category") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = categoryExpanded,
                        onDismissRequest = { categoryExpanded = false }
                    ) {
                        categories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat.name) },
                                onClick = {
                                    categoryId = cat.id
                                    categoryExpanded = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = tagsString,
                    onValueChange = { tagsString = it },
                    label = { Text("Tags (comma separated)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Personal Notes") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val tagsList = tagsString.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val updatedBook = book.copy(
                        title = title.ifBlank { book.title },
                        author = author.trim(),
                        categoryId = categoryId,
                        tagsJson = JSONArray(tagsList).toString(),
                        description = description.trim(),
                        notes = notes.trim()
                    )
                    onSave(updatedBook)
                }
            ) {
                Text("Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ImportConfirmationDialog(
    extractedInfo: ExtractedPdfInfo,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onConfirm: (finalCategory: String, finalTitle: String) -> Unit
) {
    var selectedCategoryId by remember { mutableStateOf(extractedInfo.categoryId) }
    var title by remember { mutableStateOf(extractedInfo.title) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PDF Classification & Import") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Automatic classification detected document attributes:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Document Title") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Suggested Category:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    categories.forEach { cat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selectedCategoryId == cat.id)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .clickable { selectedCategoryId = cat.id }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(CategoryIconHelper.parseColor(cat.colorHex), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = cat.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selectedCategoryId == cat.id) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }

                if (extractedInfo.isScannedImageOnly) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Notice: This PDF appears to be a scanned image without a native text layer. OCR may be required for full AI search.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedCategoryId, title) }) {
                Text("Confirm & Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CategoryManagementDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onAddCategory: (CategoryEntity) -> Unit,
    onDeleteCategory: (category: CategoryEntity, reassignToId: String) -> Unit
) {
    var isAddingNew by remember { mutableStateOf(false) }
    var categoryToDelete by remember { mutableStateOf<CategoryEntity?>(null) }

    // New Category Form State
    var newName by remember { mutableStateOf("") }
    var newDesc by remember { mutableStateOf("") }
    var newColorHex by remember { mutableStateOf("#2563EB") }
    var newIconName by remember { mutableStateOf("Folder") }

    val presetColors = listOf(
        "#2563EB", "#4F46E5", "#7C3AED", "#059669", "#0284C7",
        "#D97706", "#DC2626", "#0D9488", "#64748B", "#E11D48"
    )

    val presetIcons = listOf(
        "AccountBalance", "Receipt", "PointOfSale", "BusinessCenter",
        "Calculate", "School", "MenuBook", "FolderShared", "Folder"
    )

    if (categoryToDelete != null) {
        val cat = categoryToDelete!!
        var reassignId by remember { mutableStateOf("cat_uncategorized") }

        AlertDialog(
            onDismissRequest = { categoryToDelete = null },
            title = { Text("Delete Category \"${cat.name}\"?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Where should documents currently in this category be moved?")
                    categories.filter { it.id != cat.id }.forEach { otherCat ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (reassignId == otherCat.id)
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    else Color.Transparent
                                )
                                .clickable { reassignId = otherCat.id }
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "Move to: ${otherCat.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (reassignId == otherCat.id) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteCategory(cat, reassignId)
                        categoryToDelete = null
                    }
                ) {
                    Text("Confirm Delete", color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { categoryToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manage Categories") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (!isAddingNew) {
                    Button(
                        onClick = { isAddingNew = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Create New Category")
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    categories.forEach { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .background(CategoryIconHelper.parseColor(cat.colorHex), CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = cat.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (cat.description.isNotBlank()) {
                                        Text(
                                            text = cat.description,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (!cat.isDefault || cat.id != "cat_uncategorized") {
                                IconButton(
                                    onClick = { categoryToDelete = cat },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Remove category",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // Create Category Subform
                    Text("New Category Details", style = MaterialTheme.typography.titleSmall)

                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("Category Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = newDesc,
                        onValueChange = { newDesc = it },
                        label = { Text("Description") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Pick Color:", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(presetColors) { hex ->
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(CategoryIconHelper.parseColor(hex))
                                    .border(
                                        width = if (newColorHex == hex) 3.dp else 0.dp,
                                        color = if (newColorHex == hex) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable { newColorHex = hex }
                            )
                        }
                    }

                    Text("Pick Icon:", style = MaterialTheme.typography.labelMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(presetIcons) { iconName ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (newIconName == iconName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clickable { newIconName = iconName }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = CategoryIconHelper.getIcon(iconName),
                                        contentDescription = iconName,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (newIconName == iconName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = { isAddingNew = false }) {
                            Text("Back")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (newName.isNotBlank()) {
                                    val cat = CategoryEntity(
                                        id = "cat_${System.currentTimeMillis()}",
                                        name = newName.trim(),
                                        colorHex = newColorHex,
                                        iconName = newIconName,
                                        description = newDesc.trim(),
                                        isDefault = false,
                                        sortOrder = categories.size + 1
                                    )
                                    onAddCategory(cat)
                                    isAddingNew = false
                                    newName = ""
                                    newDesc = ""
                                }
                            }
                        ) {
                            Text("Save Category")
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isAddingNew) {
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }
        }
    )
}
