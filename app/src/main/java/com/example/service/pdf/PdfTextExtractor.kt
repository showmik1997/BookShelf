package com.example.service.pdf

import com.example.data.model.CategoryEntity
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.InflaterInputStream

data class ExtractedPdfInfo(
    val title: String,
    val author: String,
    val categoryId: String,
    val tags: List<String>,
    val description: String,
    val pageTexts: Map<Int, String>, // 0-indexed page to text
    val isScannedImageOnly: Boolean,
    val contentHash: String
)

object PdfTextExtractor {

    fun computeSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(bytes)
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun extractInfo(
        pdfBytes: ByteArray,
        originalFileName: String,
        totalPages: Int,
        availableCategories: List<CategoryEntity> = emptyList()
    ): ExtractedPdfInfo {
        val hash = computeSha256(pdfBytes)
        val rawContent = String(pdfBytes, Charsets.ISO_8859_1)

        val metadataTitle = extractMetadataField(rawContent, "/Title")
            .ifBlank { extractXmlField(rawContent, "dc:title") }
        val metadataAuthor = extractMetadataField(rawContent, "/Author")
            .ifBlank { extractXmlField(rawContent, "dc:creator") }
        val metadataSubject = extractMetadataField(rawContent, "/Subject")
            .ifBlank { extractXmlField(rawContent, "dc:description") }

        val cleanTitle = when {
            metadataTitle.isNotBlank() && metadataTitle.length >= 3 -> metadataTitle
            else -> originalFileName.removeSuffix(".pdf").removeSuffix(".PDF")
                .replace('_', ' ').replace('-', ' ').trim()
        }

        val pageTexts = extractPageTexts(rawContent, pdfBytes, totalPages)
        val combinedText = pageTexts.values.joinToString(" ").lowercase()
        val hasExtractedText = combinedText.filter { it.isLetter() }.length > 30

        val (suggestedCategory, tags) = classifyContent(
            fileName = originalFileName,
            title = cleanTitle,
            subject = metadataSubject,
            textSample = combinedText,
            categories = availableCategories
        )

        val shortDescription = buildDescription(cleanTitle, tags, hasExtractedText)

        return ExtractedPdfInfo(
            title = cleanTitle,
            author = metadataAuthor,
            categoryId = suggestedCategory,
            tags = tags,
            description = shortDescription,
            pageTexts = pageTexts,
            isScannedImageOnly = !hasExtractedText,
            contentHash = hash
        )
    }

    private fun extractMetadataField(raw: String, field: String): String {
        val regex = Regex("""$field\s*\(([^)]+)\)""")
        val match = regex.find(raw)
        return match?.groupValues?.get(1)?.replace("\\(", "(")?.replace("\\)", ")")?.trim() ?: ""
    }

    private fun extractXmlField(raw: String, tag: String): String {
        val regex = Regex("""<$tag[^>]*>(?:<[^>]+>)*([^<]+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(raw)
        return match?.groupValues?.get(1)?.trim() ?: ""
    }

    private fun extractPageTexts(raw: String, bytes: ByteArray, totalPages: Int): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        val extractedChunks = mutableListOf<String>()

        // 1. Try finding streams between 'stream' and 'endstream'
        val streamRegex = Regex("""stream\r?\n([\s\S]*?)\r?\nendstream""")
        for (match in streamRegex.findAll(raw)) {
            val streamContent = match.groupValues[1]
            val text = tryDecompressFlate(streamContent.toByteArray(Charsets.ISO_8859_1))
                ?: streamContent
            val parsedText = parseTextOperators(text)
            if (parsedText.isNotBlank()) {
                extractedChunks.add(parsedText)
            }
        }

        // 2. If no streams extracted, extract literal string parentheses throughout raw bytes
        if (extractedChunks.isEmpty()) {
            val literalRegex = Regex("""\(([a-zA-Z0-9 ,.\-_:?!]{4,100})\)""")
            val literals = literalRegex.findAll(raw).map { it.groupValues[1] }.filter { it.any { c -> c.isLetter() } }.toList()
            if (literals.isNotEmpty()) {
                extractedChunks.add(literals.joinToString(" "))
            }
        }

        if (totalPages <= 0) return result
        if (extractedChunks.isEmpty()) {
            for (p in 0 until totalPages) result[p] = ""
            return result
        }

        val chunksPerPage = (extractedChunks.size.toDouble() / totalPages).coerceAtLeast(1.0)
        for (p in 0 until totalPages) {
            val startIdx = (p * chunksPerPage).toInt().coerceAtMost(extractedChunks.size)
            val endIdx = ((p + 1) * chunksPerPage).toInt().coerceAtMost(extractedChunks.size)
            val pageText = if (startIdx < endIdx) {
                extractedChunks.subList(startIdx, endIdx).joinToString("\n")
            } else if (startIdx < extractedChunks.size) {
                extractedChunks[startIdx]
            } else {
                ""
            }
            result[p] = pageText.take(4000)
        }

        return result
    }

    private fun tryDecompressFlate(data: ByteArray): String? {
        return try {
            val bis = data.inputStream()
            val inflater = InflaterInputStream(bis)
            val bos = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            var len: Int
            while (inflater.read(buffer).also { len = it } > 0) {
                bos.write(buffer, 0, len)
            }
            bos.toString("UTF-8")
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTextOperators(streamStr: String): String {
        val sb = StringBuilder()
        val tjRegex = Regex("""\(([^)]*)\)\s*Tj""")
        val arrayRegex = Regex("""\[(.*?)\]\s*TJ""")

        for (m in tjRegex.findAll(streamStr)) {
            val txt = m.groupValues[1].replace("\\(", "(").replace("\\)", ")")
            if (txt.isNotBlank()) sb.append(txt).append(" ")
        }

        for (m in arrayRegex.findAll(streamStr)) {
            val inside = m.groupValues[1]
            val itemRegex = Regex("""\(([^)]*)\)""")
            for (im in itemRegex.findAll(inside)) {
                val txt = im.groupValues[1].replace("\\(", "(").replace("\\)", ")")
                if (txt.isNotBlank()) sb.append(txt).append(" ")
            }
        }

        return sb.toString().trim()
    }

    private fun classifyContent(
        fileName: String,
        title: String,
        subject: String,
        textSample: String,
        categories: List<CategoryEntity>
    ): Pair<String, List<String>> {
        val combined = "$fileName $title $subject $textSample".lowercase()
        val tags = mutableListOf<String>()

        // Generate tags based on common content words
        val keywordTagMap = mapOf(
            "invoice" to "invoice", "receipt" to "receipt", "bill" to "bill",
            "bank" to "banking", "statement" to "statement",
            "tax" to "tax", "accounting" to "accounting", "ledger" to "finance",
            "math" to "math", "calculus" to "calculus", "algebra" to "algebra", "equation" to "equations",
            "code" to "coding", "software" to "software", "manual" to "manual",
            "guide" to "guide", "lecture" to "education", "syllabus" to "syllabus",
            "report" to "report", "contract" to "contract", "resume" to "resume"
        )
        for ((word, tag) in keywordTagMap) {
            if (combined.contains(word) && !tags.contains(tag)) {
                tags.add(tag)
            }
        }
        if (tags.isEmpty()) tags.add("document")

        // If categories are provided, score each category
        var bestCategoryId = "cat_uncategorized"
        var highestScore = 0

        for (cat in categories) {
            if (cat.id == "cat_uncategorized") continue

            var score = 0
            val catNameLower = cat.name.lowercase().trim()
            val catWords = catNameLower.split("\\W+".toRegex()).filter { it.length > 2 }

            // 1. Direct match with category name
            if (combined.contains(catNameLower)) {
                score += 15
            }
            for (word in catWords) {
                if (title.lowercase().contains(word) || fileName.lowercase().contains(word)) {
                    score += 10
                }
                if (combined.contains(word)) {
                    score += 5
                }
            }

            // 2. Synonyms and related terms
            val synonyms = getCategorySynonyms(catNameLower)
            for (syn in synonyms) {
                if (combined.contains(syn)) {
                    score += 4
                }
            }

            if (score > highestScore) {
                highestScore = score
                bestCategoryId = cat.id
            }
        }

        // If no categories or no match found, fallback to first category or uncategorized
        if (highestScore == 0) {
            bestCategoryId = categories.firstOrNull { it.id == "cat_uncategorized" }?.id
                ?: categories.firstOrNull()?.id
                ?: "cat_uncategorized"
        }

        return bestCategoryId to tags
    }

    private fun getCategorySynonyms(name: String): List<String> {
        val list = mutableListOf<String>()
        when {
            name.contains("bank") -> list.addAll(listOf("statement", "account", "deposit", "credit", "debit", "balance", "iban"))
            name.contains("bill") || name.contains("invoice") -> list.addAll(listOf("receipt", "payment", "due date", "amount due", "subtotal", "tax invoice"))
            name.contains("account") -> list.addAll(listOf("ledger", "balance sheet", "p&l", "audit", "cpa", "financial"))
            name.contains("business") || name.contains("manage") -> list.addAll(listOf("strategy", "corporate", "proposal", "contract", "meeting", "quarterly"))
            name.contains("math") -> list.addAll(listOf("equation", "calculus", "algebra", "matrix", "derivative", "integral", "theorem"))
            name.contains("edu") || name.contains("study") || name.contains("school") -> list.addAll(listOf("syllabus", "course", "textbook", "university", "assignment", "exam", "quiz"))
            name.contains("tech") || name.contains("manual") || name.contains("doc") -> list.addAll(listOf("specification", "hardware", "user guide", "api", "schematic", "troubleshoot"))
            name.contains("person") -> list.addAll(listOf("resume", "cv", "certificate", "passport", "bio", "id"))
        }
        return list
    }

    private fun buildDescription(title: String, tags: List<String>, hasText: Boolean): String {
        val scanNote = if (!hasText) " (Image/Scanned document — OCR may be needed)" else ""
        return "Document \"$title\" with tags [${tags.joinToString(", ")}]$scanNote."
    }
}
