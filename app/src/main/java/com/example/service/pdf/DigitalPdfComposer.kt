package com.example.service.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.example.service.ai.DocumentElement
import com.example.service.ai.ReconstructedDocumentPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object DigitalPdfComposer {

    // Standard A4 dimensions in PDF points (72 DPI)
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842

    const val MARGIN_LEFT = 42f
    const val MARGIN_RIGHT = 42f
    const val MARGIN_TOP = 50f
    const val MARGIN_BOTTOM = 50f

    val CONTENT_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT

    suspend fun composeDocument(
        context: Context,
        outputFile: File,
        documentTitle: String,
        pages: List<ReconstructedDocumentPage>
    ): Result<File> = withContext(Dispatchers.IO) {
        val pdfDocument = PdfDocument()

        try {
            var globalPageNumber = 1
            val totalPagesCount = pages.size.coerceAtLeast(1)

            for (pageData in pages) {
                var currentPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, globalPageNumber).create()
                var currentPage = pdfDocument.startPage(currentPageInfo)
                var canvas = currentPage.canvas

                var currentY = MARGIN_TOP

                // Helper to advance page if content overflows
                fun checkOverflow(neededHeight: Float) {
                    if (currentY + neededHeight > PAGE_HEIGHT - MARGIN_BOTTOM) {
                        // Draw running footer for current page
                        drawFooter(canvas, documentTitle, globalPageNumber, totalPagesCount, pageData.footerText)
                        pdfDocument.finishPage(currentPage)

                        globalPageNumber++
                        currentPageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, globalPageNumber).create()
                        currentPage = pdfDocument.startPage(currentPageInfo)
                        canvas = currentPage.canvas
                        currentY = MARGIN_TOP

                        // Draw running header on new page
                        drawHeader(canvas, documentTitle, pageData.headerText)
                        currentY += 24f
                    }
                }

                // Initial running header
                drawHeader(canvas, documentTitle, pageData.headerText)
                currentY += 20f

                val isOnlyFallback = pageData.elements.isEmpty() ||
                        (pageData.elements.size <= 3 && pageData.elements.any { it.text.contains("digitized with active Gemini", ignoreCase = true) })

                if (isOnlyFallback && pageData.originalBitmap != null) {
                    val bmp = pageData.originalBitmap
                    val availableWidth = CONTENT_WIDTH
                    val availableHeight = PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM - 20f
                    val scale = minOf(availableWidth / bmp.width.toFloat(), availableHeight / bmp.height.toFloat())
                    val drawW = bmp.width * scale
                    val drawH = bmp.height * scale
                    val drawX = MARGIN_LEFT + (availableWidth - drawW) / 2f
                    val drawY = currentY + (availableHeight - drawH) / 2f
                    canvas.drawBitmap(bmp, null, RectF(drawX, drawY, drawX + drawW, drawY + drawH), Paint(Paint.FILTER_BITMAP_FLAG))
                } else {
                    // Draw elements
                    for (element in pageData.elements) {
                        when (element.type.lowercase()) {
                        "heading" -> {
                            val fontSize = when (element.level) {
                                1 -> 17f
                                2 -> 14f
                                else -> 12f
                            }
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = fontSize
                                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                                color = parseColorSafely(element.colorHex, Color.rgb(29, 78, 216)) // Default primary blue
                            }

                            val lines = breakTextIntoLines(element.text, paint, CONTENT_WIDTH)
                            val blockHeight = lines.size * (fontSize + 6f) + 12f
                            checkOverflow(blockHeight)

                            currentY += 6f
                            for (line in lines) {
                                val x = when (element.alignment.lowercase()) {
                                    "center" -> MARGIN_LEFT + (CONTENT_WIDTH - paint.measureText(line)) / 2f
                                    "right" -> MARGIN_LEFT + CONTENT_WIDTH - paint.measureText(line)
                                    else -> MARGIN_LEFT
                                }
                                canvas.drawText(line, x, currentY + fontSize, paint)
                                currentY += fontSize + 5f
                            }
                            currentY += 6f
                        }

                        "paragraph" -> {
                            val fontSize = 10.5f
                            val lineSpacing = 15f
                            val tfStyle = when {
                                element.isBold && element.isItalic -> Typeface.BOLD_ITALIC
                                element.isBold -> Typeface.BOLD
                                element.isItalic -> Typeface.ITALIC
                                else -> Typeface.NORMAL
                            }
                            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = fontSize
                                typeface = Typeface.create(Typeface.SANS_SERIF, tfStyle)
                                color = parseColorSafely(element.colorHex, Color.rgb(31, 41, 55)) // #1F2937
                            }

                            val lines = breakTextIntoLines(element.text, paint, CONTENT_WIDTH)
                            val blockHeight = lines.size * lineSpacing + 8f
                            checkOverflow(blockHeight)

                            for (line in lines) {
                                val x = when (element.alignment.lowercase()) {
                                    "center" -> MARGIN_LEFT + (CONTENT_WIDTH - paint.measureText(line)) / 2f
                                    "right" -> MARGIN_LEFT + CONTENT_WIDTH - paint.measureText(line)
                                    else -> MARGIN_LEFT
                                }
                                canvas.drawText(line, x, currentY + fontSize, paint)
                                currentY += lineSpacing
                            }
                            currentY += 6f
                        }

                        "table" -> {
                            if (element.headers.isNotEmpty() || element.rows.isNotEmpty()) {
                                val numCols = maxOf(
                                    element.headers.size,
                                    element.rows.maxOfOrNull { it.size } ?: 1
                                ).coerceAtLeast(1)

                                val colWidth = CONTENT_WIDTH / numCols
                                val cellPadding = 5f
                                val rowHeight = 22f

                                val estimatedHeight = (element.rows.size + (if (element.headers.isNotEmpty()) 1 else 0)) * rowHeight + 16f
                                checkOverflow(estimatedHeight.coerceAtMost(200f))

                                val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = Color.rgb(203, 213, 225) // Slate-300
                                    style = Paint.Style.STROKE
                                    strokeWidth = 0.8f
                                }

                                val headerBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = Color.rgb(241, 245, 249) // Slate-100
                                    style = Paint.Style.FILL
                                }

                                val altRowBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    color = Color.rgb(248, 250, 252) // Slate-50
                                    style = Paint.Style.FILL
                                }

                                val headerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    textSize = 9.5f
                                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                                    color = Color.rgb(30, 41, 59)
                                }

                                val cellTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                    textSize = 9.0f
                                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                                    color = Color.rgb(51, 65, 85)
                                }

                                currentY += 4f

                                // Draw table headers
                                if (element.headers.isNotEmpty()) {
                                    val headerRect = RectF(MARGIN_LEFT, currentY, MARGIN_LEFT + CONTENT_WIDTH, currentY + rowHeight)
                                    canvas.drawRect(headerRect, headerBgPaint)
                                    canvas.drawRect(headerRect, borderPaint)

                                    for (c in 0 until numCols) {
                                        val cellLeft = MARGIN_LEFT + c * colWidth
                                        val colName = element.headers.getOrNull(c) ?: ""
                                        canvas.drawText(
                                            truncateText(colName, headerTextPaint, colWidth - cellPadding * 2),
                                            cellLeft + cellPadding,
                                            currentY + 14.5f,
                                            headerTextPaint
                                        )
                                        if (c > 0) {
                                            canvas.drawLine(cellLeft, currentY, cellLeft, currentY + rowHeight, borderPaint)
                                        }
                                    }
                                    currentY += rowHeight
                                }

                                // Draw table rows
                                for ((rowIndex, row) in element.rows.withIndex()) {
                                    checkOverflow(rowHeight + 4f)

                                    val rowRect = RectF(MARGIN_LEFT, currentY, MARGIN_LEFT + CONTENT_WIDTH, currentY + rowHeight)
                                    if (rowIndex % 2 == 1) {
                                        canvas.drawRect(rowRect, altRowBgPaint)
                                    }
                                    canvas.drawRect(rowRect, borderPaint)

                                    for (c in 0 until numCols) {
                                        val cellLeft = MARGIN_LEFT + c * colWidth
                                        val cellValue = row.getOrNull(c) ?: ""
                                        canvas.drawText(
                                            truncateText(cellValue, cellTextPaint, colWidth - cellPadding * 2),
                                            cellLeft + cellPadding,
                                            currentY + 14.5f,
                                            cellTextPaint
                                        )
                                        if (c > 0) {
                                            canvas.drawLine(cellLeft, currentY, cellLeft, currentY + rowHeight, borderPaint)
                                        }
                                    }
                                    currentY += rowHeight
                                }
                                currentY += 8f
                            }
                        }

                        "bullet_list" -> {
                            val fontSize = 10f
                            val bulletPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.rgb(59, 130, 246)
                                style = Paint.Style.FILL
                            }
                            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = fontSize
                                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                                color = Color.rgb(31, 41, 55)
                            }

                            for (item in element.items) {
                                val itemLines = breakTextIntoLines(item, textPaint, CONTENT_WIDTH - 20f)
                                checkOverflow(itemLines.size * 14f + 4f)

                                // Draw bullet dot
                                canvas.drawCircle(MARGIN_LEFT + 6f, currentY + 6.5f, 2.5f, bulletPaint)

                                for (l in itemLines) {
                                    canvas.drawText(l, MARGIN_LEFT + 18f, currentY + fontSize, textPaint)
                                    currentY += 14f
                                }
                                currentY += 2f
                            }
                            currentY += 6f
                        }

                        "key_value" -> {
                            val fontSize = 10f
                            val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = fontSize
                                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                                color = Color.rgb(30, 41, 59)
                            }
                            val valPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = fontSize
                                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                                color = Color.rgb(51, 65, 85)
                            }

                            checkOverflow(18f)
                            canvas.drawText(element.key, MARGIN_LEFT, currentY + fontSize, keyPaint)
                            val keyWidth = keyPaint.measureText(element.key) + 8f
                            canvas.drawText(element.value, MARGIN_LEFT + keyWidth, currentY + fontSize, valPaint)
                            currentY += 16f
                        }

                        "divider" -> {
                            checkOverflow(12f)
                            val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.rgb(226, 232, 240)
                                strokeWidth = 1f
                            }
                            canvas.drawLine(MARGIN_LEFT, currentY + 6f, MARGIN_LEFT + CONTENT_WIDTH, currentY + 6f, divPaint)
                            currentY += 14f
                        }
                    }
                }
                }

                // Final footer on this page
                drawFooter(canvas, documentTitle, globalPageNumber, totalPagesCount, pageData.footerText)
                pdfDocument.finishPage(currentPage)
                globalPageNumber++
            }

            // Write to file
            FileOutputStream(outputFile).use { out ->
                pdfDocument.writeTo(out)
            }

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            pdfDocument.close()
        }
    }

    private fun drawHeader(canvas: Canvas, title: String, headerCustom: String?) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            color = Color.rgb(148, 163, 184)
        }
        val headerStr = if (!headerCustom.isNullOrBlank()) headerCustom else title
        canvas.drawText(headerStr.take(50), MARGIN_LEFT, MARGIN_TOP - 16f, paint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            strokeWidth = 0.6f
        }
        canvas.drawLine(MARGIN_LEFT, MARGIN_TOP - 8f, MARGIN_LEFT + CONTENT_WIDTH, MARGIN_TOP - 8f, linePaint)
    }

    private fun drawFooter(canvas: Canvas, title: String, page: Int, total: Int, footerCustom: String?) {
        val y = PAGE_HEIGHT - MARGIN_BOTTOM + 20f
        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(226, 232, 240)
            strokeWidth = 0.6f
        }
        canvas.drawLine(MARGIN_LEFT, y - 10f, MARGIN_LEFT + CONTENT_WIDTH, y - 10f, linePaint)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 8.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            color = Color.rgb(148, 163, 184)
        }

        val footText = if (!footerCustom.isNullOrBlank()) footerCustom else "BookShelf Digital Replica"
        canvas.drawText(footText, MARGIN_LEFT, y + 4f, paint)

        val pageStr = "Page $page of $total"
        val pageX = MARGIN_LEFT + CONTENT_WIDTH - paint.measureText(pageStr)
        canvas.drawText(pageStr, pageX, y + 4f, paint)
    }

    private fun breakTextIntoLines(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return emptyList()
        val lines = mutableListOf<String>()
        val paragraphs = text.split("\n")

        for (para in paragraphs) {
            val words = para.split(" ")
            var currentLine = StringBuilder()

            for (w in words) {
                val candidate = if (currentLine.isEmpty()) w else "$currentLine $w"
                if (paint.measureText(candidate) <= maxWidth) {
                    currentLine = StringBuilder(candidate)
                } else {
                    if (currentLine.isNotEmpty()) {
                        lines.add(currentLine.toString())
                    }
                    currentLine = StringBuilder(w)
                }
            }
            if (currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
            }
        }
        return lines
    }

    private fun truncateText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var truncated = text
        while (truncated.length > 3 && paint.measureText("$truncated...") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated..."
    }

    private fun parseColorSafely(hex: String?, fallback: Int): Int {
        if (hex.isNullOrBlank()) return fallback
        return try {
            val clean = hex.trim()
            if (clean.startsWith("#")) {
                Color.parseColor(clean)
            } else {
                Color.parseColor("#$clean")
            }
        } catch (e: Exception) {
            fallback
        }
    }
}
