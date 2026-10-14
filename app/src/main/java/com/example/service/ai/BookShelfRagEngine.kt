package com.example.service.ai

import com.example.data.db.BookDao
import com.example.data.db.PdfPageTextDao
import com.example.data.model.BookEntity
import com.example.data.model.PdfPageTextEntity
import org.json.JSONObject

data class RetrievedSource(
    val bookId: String,
    val bookTitle: String,
    val pageNumber: Int, // 0-indexed
    val displayPage: Int, // 1-indexed
    val snippet: String,
    val score: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("bookId", bookId)
        put("bookTitle", bookTitle)
        put("pageNumber", pageNumber)
        put("displayPage", displayPage)
        put("snippet", snippet)
    }

    companion object {
        fun fromJson(json: JSONObject): RetrievedSource = RetrievedSource(
            bookId = json.optString("bookId"),
            bookTitle = json.optString("bookTitle"),
            pageNumber = json.optInt("pageNumber"),
            displayPage = json.optInt("displayPage", json.optInt("pageNumber") + 1),
            snippet = json.optString("snippet"),
            score = 1.0
        )
    }
}

class BookShelfRagEngine(
    private val bookDao: BookDao,
    private val pageTextDao: PdfPageTextDao,
    private val geminiService: GeminiService
) {

    suspend fun searchRelevantPages(
        query: String,
        targetBookId: String? = null,
        targetCategoryId: String? = null,
        limit: Int = 4
    ): List<RetrievedSource> {
        val queryTokens = query.lowercase().split("\\W+".toRegex()).filter { it.length > 2 }
        if (queryTokens.isEmpty()) return emptyList()

        val candidatePages: List<PdfPageTextEntity> = if (targetBookId != null) {
            pageTextDao.getPagesForBook(targetBookId)
        } else {
            // Search all pages across books
            val firstToken = queryTokens.first()
            val list = pageTextDao.searchAcrossAllBooks(firstToken)
            if (list.size < 20) {
                // If small, also fetch pages containing other tokens
                val set = list.toMutableSet()
                for (token in queryTokens.drop(1).take(3)) {
                    set.addAll(pageTextDao.searchAcrossAllBooks(token))
                }
                set.toList()
            } else {
                list
            }
        }

        if (candidatePages.isEmpty()) return emptyList()

        // Cache books map
        val booksMap = mutableMapOf<String, BookEntity?>()
        val scoredList = mutableListOf<RetrievedSource>()

        for (page in candidatePages) {
            val book = booksMap.getOrPut(page.bookId) { bookDao.getBookById(page.bookId) }
            if (book == null) continue
            if (targetCategoryId != null && book.categoryId != targetCategoryId) continue

            val lowerText = page.extractedText.lowercase()
            var matches = 0
            for (token in queryTokens) {
                if (lowerText.contains(token)) {
                    matches++
                }
            }

            if (matches > 0) {
                val score = matches.toDouble() / queryTokens.size + (if (lowerText.contains(query.lowercase())) 1.0 else 0.0)
                val snippet = extractSnippet(page.extractedText, queryTokens)
                scoredList.add(
                    RetrievedSource(
                        bookId = page.bookId,
                        bookTitle = book.title,
                        pageNumber = page.pageNumber,
                        displayPage = page.pageNumber + 1,
                        snippet = snippet,
                        score = score
                    )
                )
            }
        }

        return scoredList.sortedByDescending { it.score }.take(limit)
    }

    suspend fun generateAnswer(
        query: String,
        apiKey: String,
        model: String,
        targetBookId: String? = null,
        targetCategoryId: String? = null,
        previousMessages: List<GeminiChatMessage> = emptyList()
    ): Pair<String, List<RetrievedSource>> {
        val sources = searchRelevantPages(query, targetBookId, targetCategoryId)

        val systemPrompt = buildString {
            append("You are BookShelf AI Assistant, an expert native PDF study and library tutor powered by Google Gemini. ")
            append("The user is asking questions about documents in their BookShelf library.\n\n")
            append("STRICT RULES:\n")
            append("1. Answer using the provided document excerpts where possible.\n")
            append("2. Clearly distinguish between TEXTBOOK/DOCUMENT EXCERPTS and your own ADDITIONAL EXPLANATION.\n")
            append("3. For mathematical solutions or accounting equations, write equations, variables, intermediate calculation steps, and units in clear, readable steps.\n")
            append("4. Cite the document title and exact page number for factual points (e.g., [Document: 'Title', Page 12]).\n")
            append("5. If no relevant document excerpt is found or available, state honestly that no relevant passage was found in the indexed library documents, then provide general knowledge if helpful while noting it is outside the library documents.\n")
            append("6. Never invent false citations or claim to have read a book without indexed text.\n\n")

            if (sources.isNotEmpty()) {
                append("--- RELEVANT INDEXED LIBRARY PASSAGES ---\n")
                for (s in sources) {
                    append("Book: \"${s.bookTitle}\" (Page ${s.displayPage})\n")
                    append("Excerpt: ${s.snippet}\n\n")
                }
                append("--- END PASSAGES ---\n")
            } else {
                append("NOTE: No indexed document excerpts matched this query.\n")
            }
        }

        if (apiKey.isBlank()) {
            // Local fallback response when no API key configured
            val localResponse = if (sources.isNotEmpty()) {
                buildString {
                    append("I found ${sources.size} relevant passage(s) in your library:\n\n")
                    for (s in sources) {
                        append("📖 **${s.bookTitle}** — Page ${s.displayPage}\n")
                        append("> \"${s.snippet.take(280)}...\"\n\n")
                    }
                    append("\n*(To enable Google Gemini synthesis, math step-by-step solving, and conversational reasoning, enter your Gemini API Key in Settings.)*")
                }
            } else {
                "No matching passages were found in your indexed library for \"$query\".\n\nEnsure your PDFs contain searchable text (not purely scanned images) and are indexed in BookShelf. To configure AI answering, add your Gemini API Key in Settings."
            }
            return localResponse to sources
        }

        val requestMessages = mutableListOf<GeminiChatMessage>()
        // Add previous multi-turn conversation context
        for (m in previousMessages.takeLast(6)) {
            requestMessages.add(m)
        }
        requestMessages.add(GeminiChatMessage(role = "user", text = query))

        val response = geminiService.generateContent(
            apiKey = apiKey,
            model = model.ifBlank { "gemini-3.8-flash" },
            systemInstruction = systemPrompt,
            messages = requestMessages
        )

        val answerText = if (response.error != null) {
            "Error from Gemini AI service: ${response.error}\n\n" + (if (sources.isNotEmpty()) "However, here are the retrieved library passages:\n" + sources.joinToString("\n\n") { "• ${it.bookTitle} (Page ${it.displayPage}): \"${it.snippet}\"" } else "")
        } else {
            response.text
        }

        return answerText to sources
    }

    private fun extractSnippet(text: String, tokens: List<String>): String {
        if (text.isBlank()) return "No text extracted from this page."
        val lower = text.lowercase()
        var bestIndex = -1
        for (t in tokens) {
            val idx = lower.indexOf(t)
            if (idx != -1 && (bestIndex == -1 || idx < bestIndex)) {
                bestIndex = idx
            }
        }
        return if (bestIndex != -1) {
            val start = (bestIndex - 60).coerceAtLeast(0)
            val end = (bestIndex + 240).coerceAtMost(text.length)
            (if (start > 0) "..." else "") + text.substring(start, end).replace("\n", " ").trim() + (if (end < text.length) "..." else "")
        } else {
            text.take(300).replace("\n", " ").trim() + if (text.length > 300) "..." else ""
        }
    }
}
