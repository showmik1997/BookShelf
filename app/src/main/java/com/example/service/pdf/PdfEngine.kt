package com.example.service.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object PdfEngine {

    suspend fun getPageCount(file: File): Int = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext 0
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            renderer.pageCount
        } catch (e: Exception) {
            e.printStackTrace()
            0
        } finally {
            renderer?.close()
            pfd?.close()
        }
    }

    suspend fun renderPage(
        file: File,
        pageIndex: Int,
        targetWidth: Int = 1080
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) return@withContext null
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var page: PdfRenderer.Page? = null
        try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null

            page = renderer.openPage(pageIndex)
            val aspectRatio = page.height.toFloat() / page.width.toFloat()
            val finalWidth = targetWidth.coerceAtLeast(300)
            val finalHeight = (finalWidth * aspectRatio).toInt().coerceAtLeast(400)

            val bitmap = Bitmap.createBitmap(finalWidth, finalHeight, Bitmap.Config.ARGB_8888)
            // Fill with white background before rendering PDF page content
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            page?.close()
            renderer?.close()
            pfd?.close()
        }
    }

    suspend fun renderAndSaveCover(
        pdfFile: File,
        outputCoverFile: File,
        targetWidth: Int = 420
    ): Boolean = withContext(Dispatchers.IO) {
        val bitmap = renderPage(pdfFile, 0, targetWidth) ?: return@withContext false
        try {
            outputCoverFile.parentFile?.mkdirs()
            FileOutputStream(outputCoverFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
