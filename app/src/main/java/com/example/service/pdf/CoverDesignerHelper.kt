package com.example.service.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object CoverDesignerHelper {

    suspend fun generateCoverBitmap(
        title: String,
        subtitle: String,
        author: String,
        bgColorInt: Int,
        textColorInt: Int,
        userImage: Bitmap? = null,
        width: Int = 600,
        height: Int = 900
    ): Bitmap = withContext(Dispatchers.Default) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background
        canvas.drawColor(bgColorInt)

        // Decorative borders and book spine effect
        val borderPaint = Paint().apply {
            color = textColorInt
            alpha = 60
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        canvas.drawRect(24f, 24f, width - 24f, height - 24f, borderPaint)

        // Spine shadow on left
        val spinePaint = Paint().apply {
            color = android.graphics.Color.BLACK
            alpha = 40
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, 28f, height.toFloat(), spinePaint)

        var currentY = 160f

        // Draw User Image if supplied
        if (userImage != null) {
            val imgDestRect = RectF(60f, 80f, width - 60f, height * 0.45f)
            val srcRect = Rect(0, 0, userImage.width, userImage.height)
            canvas.drawBitmap(userImage, srcRect, imgDestRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            currentY = height * 0.52f
        }

        // Title Paint
        val titlePaint = Paint().apply {
            color = textColorInt
            textSize = if (title.length > 30) 42f else 54f
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }

        // Break title into lines if long
        val titleLines = wrapText(title, titlePaint, width - 100)
        for (line in titleLines) {
            canvas.drawText(line, width / 2f, currentY, titlePaint)
            currentY += titlePaint.textSize * 1.25f
        }

        currentY += 20f

        // Subtitle Paint
        if (subtitle.isNotBlank()) {
            val subPaint = Paint().apply {
                color = textColorInt
                alpha = 210
                textSize = 28f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            val subLines = wrapText(subtitle, subPaint, width - 120)
            for (line in subLines) {
                canvas.drawText(line, width / 2f, currentY, subPaint)
                currentY += subPaint.textSize * 1.3f
            }
        }

        // Author at bottom
        if (author.isNotBlank()) {
            val authorPaint = Paint().apply {
                color = textColorInt
                alpha = 230
                textSize = 30f
                typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                textAlign = Paint.Align.CENTER
                isAntiAlias = true
            }
            canvas.drawText(author, width / 2f, height - 70f, authorPaint)
        }

        bitmap
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Int): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = ""

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            if (paint.measureText(testLine) <= maxWidth) {
                currentLine = testLine
            } else {
                if (currentLine.isNotEmpty()) lines.add(currentLine)
                currentLine = word
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)
        return lines.ifEmpty { listOf(text) }
    }

    suspend fun saveCoverAsThumbnail(bitmap: Bitmap, targetFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun createPdfCopyWithPrependCover(
        coverBitmap: Bitmap,
        sourcePdf: File,
        targetPdf: File
    ): Boolean = withContext(Dispatchers.IO) {
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        val doc = PdfDocument()
        try {
            targetPdf.parentFile?.mkdirs()

            // Page 1: Designed Cover
            val coverPageInfo = PdfDocument.PageInfo.Builder(coverBitmap.width, coverBitmap.height, 1).create()
            val coverPage = doc.startPage(coverPageInfo)
            coverPage.canvas.drawBitmap(coverBitmap, 0f, 0f, null)
            doc.finishPage(coverPage)

            // Render existing pages into subsequent pages
            if (sourcePdf.exists() && sourcePdf.length() > 0) {
                pfd = ParcelFileDescriptor.open(sourcePdf, ParcelFileDescriptor.MODE_READ_ONLY)
                renderer = PdfRenderer(pfd)
                val totalOriginalPages = renderer.pageCount
                for (i in 0 until totalOriginalPages) {
                    val p = renderer.openPage(i)
                    val pInfo = PdfDocument.PageInfo.Builder(p.width, p.height, i + 2).create()
                    val pdfPage = doc.startPage(pInfo)
                    val bmp = Bitmap.createBitmap(p.width, p.height, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pdfPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                    doc.finishPage(pdfPage)
                    p.close()
                    bmp.recycle()
                }
            }

            FileOutputStream(targetPdf).use { fos ->
                doc.writeTo(fos)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            doc.close()
            renderer?.close()
            pfd?.close()
        }
    }
}
