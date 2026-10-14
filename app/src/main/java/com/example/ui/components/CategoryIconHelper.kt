package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderShared
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.School
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

object CategoryIconHelper {
    fun getIcon(name: String): ImageVector {
        return when (name) {
            "AccountBalance" -> Icons.Default.AccountBalance
            "Receipt" -> Icons.Default.Receipt
            "PointOfSale" -> Icons.Default.PointOfSale
            "BusinessCenter" -> Icons.Default.BusinessCenter
            "Calculate" -> Icons.Default.Calculate
            "School" -> Icons.Default.School
            "MenuBook" -> Icons.Default.MenuBook
            "FolderShared" -> Icons.Default.FolderShared
            else -> Icons.Default.Folder
        }
    }

    fun parseColor(hex: String, fallback: Color = Color(0xFF2563EB)): Color {
        return try {
            Color(android.graphics.Color.parseColor(hex))
        } catch (e: Exception) {
            fallback
        }
    }
}
