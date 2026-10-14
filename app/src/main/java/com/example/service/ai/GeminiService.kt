package com.example.service.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GeminiChatMessage(
    val role: String, // "user" or "model"
    val text: String
)

data class GeminiChatResponse(
    val text: String,
    val error: String? = null
)

data class DocumentElement(
    val type: String, // "heading", "paragraph", "table", "bullet_list", "key_value", "divider"
    val text: String = "",
    val level: Int = 1, // 1, 2, 3 for headings
    val colorHex: String? = null, // e.g. "#1E3A8A" or "#111827"
    val alignment: String = "left", // "left", "center", "right"
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val headers: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val items: List<String> = emptyList(),
    val key: String = "",
    val value: String = ""
)

data class ReconstructedDocumentPage(
    val pageTitle: String = "",
    val headerText: String = "",
    val footerText: String = "",
    val elements: List<DocumentElement> = emptyList(),
    val originalBitmap: Bitmap? = null
)

class GeminiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchAvailableModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API key is empty"))
        }

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
                    } catch (e: Exception) {
                        "HTTP ${response.code}"
                    }
                    return@withContext Result.failure(IOException("Failed to fetch Gemini models: $errMsg"))
                }

                val json = JSONObject(body)
                val modelsArr = json.optJSONArray("models") ?: JSONArray()
                val models = mutableListOf<String>()

                for (i in 0 until modelsArr.length()) {
                    val obj = modelsArr.getJSONObject(i)
                    val name = obj.getString("name").removePrefix("models/")
                    val supportedMethods = obj.optJSONArray("supportedGenerationMethods")
                    val isGenerate = if (supportedMethods != null) {
                        var hasGen = false
                        for (j in 0 until supportedMethods.length()) {
                            if (supportedMethods.getString(j) == "generateContent") hasGen = true
                        }
                        hasGen
                    } else true

                    if (isGenerate && (name.contains("gemini") || name.contains("flash") || name.contains("pro"))) {
                        models.add(name)
                    }
                }

                // Ensure popular standard Gemini models are present
                val standardModels = listOf("gemini-3.8-flash", "gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro")
                val combined = (standardModels + models).distinct().filter { it != "gemini-2.5-flash" }
                Result.success(combined)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun normalizeModel(model: String): String {
        val clean = model.ifBlank { "gemini-3.8-flash" }.removePrefix("models/")
        return if (clean == "gemini-2.5-flash") "gemini-3.8-flash" else clean
    }

    suspend fun generateContent(
        apiKey: String,
        model: String,
        systemInstruction: String? = null,
        messages: List<GeminiChatMessage>
    ): GeminiChatResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext GeminiChatResponse("", error = "Please provide your Gemini API Key in Settings.")
        }

        val cleanModel = normalizeModel(model)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$apiKey"

        val jsonBody = JSONObject().apply {
            if (!systemInstruction.isNullOrBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().apply { put("text", systemInstruction) }))
                })
            }

            val contentsArr = JSONArray()
            for (msg in messages) {
                contentsArr.put(JSONObject().apply {
                    put("role", if (msg.role == "assistant") "model" else msg.role)
                    put("parts", JSONArray().put(JSONObject().apply { put("text", msg.text) }))
                })
            }
            put("contents", contentsArr)

            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 4096)
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $body"
                    }
                    return@withContext GeminiChatResponse("", error = errMsg)
                }

                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val contentObj = firstCandidate.optJSONObject("content")
                    val parts = contentObj?.optJSONArray("parts")
                    if (parts != null && parts.length() > 0) {
                        val text = parts.getJSONObject(0).optString("text", "")
                        GeminiChatResponse(text)
                    } else {
                        GeminiChatResponse("", error = "Gemini returned empty parts.")
                    }
                } else {
                    GeminiChatResponse("", error = "No candidate responses returned by Gemini.")
                }
            }
        } catch (e: Exception) {
            GeminiChatResponse("", error = "Network connection failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    suspend fun reconstructDocumentPage(
        apiKey: String,
        model: String,
        imageBitmap: Bitmap
    ): Result<ReconstructedDocumentPage> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please provide a Gemini API Key in Settings to scan with AI."))
        }

        // Convert Bitmap to base64 jpeg
        val baos = ByteArrayOutputStream()
        // Resize down if excessively large to keep payload fast and sharp
        val maxDim = 1600
        val scale = if (imageBitmap.width > maxDim || imageBitmap.height > maxDim) {
            val maxCurrent = maxOf(imageBitmap.width, imageBitmap.height)
            maxDim.toFloat() / maxCurrent
        } else 1.0f

        val finalBitmap = if (scale < 1.0f) {
            Bitmap.createScaledBitmap(imageBitmap, (imageBitmap.width * scale).toInt(), (imageBitmap.height * scale).toInt(), true)
        } else {
            imageBitmap
        }

        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        val imageBytes = baos.toByteArray()
        val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        val cleanKey = apiKey.trim()
        val cleanModel = normalizeModel(model)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent?key=$cleanKey"

        val prompt = """
You are an expert document OCR transcriber and digital layout compositor.
Carefully examine this scanned document page. Transcribe and extract ALL text, headings, sections, tables, bullet lists, math formulas, numbers, and key-values with 100% precision.
Preserve the document's original language (Bengali, English, etc.) faithfully without translating.

Output a structured JSON document:
{
  "pageTitle": "Main title or heading of this document page",
  "headerText": "Page header if present",
  "footerText": "Page footer or page number",
  "elements": [
    {
      "type": "heading",
      "level": 1,
      "text": "Header or Section Title",
      "colorHex": "#1E3A8A",
      "alignment": "left"
    },
    {
      "type": "paragraph",
      "text": "Transcribed text paragraph in full fidelity...",
      "isBold": false,
      "isItalic": false,
      "colorHex": "#1F2937",
      "alignment": "left"
    },
    {
      "type": "table",
      "headers": ["Col 1", "Col 2", "Col 3"],
      "rows": [
        ["Row 1 Col 1", "Row 1 Col 2", "Row 1 Col 3"]
      ]
    },
    {
      "type": "bullet_list",
      "items": ["Point 1", "Point 2"]
    },
    {
      "type": "key_value",
      "key": "Label:",
      "value": "Value"
    }
  ]
}
""".trimIndent()

        val jsonBody = JSONObject().apply {
            val partsArr = JSONArray()
            partsArr.put(JSONObject().apply { put("text", prompt) })
            partsArr.put(JSONObject().apply {
                put("inlineData", JSONObject().apply {
                    put("mimeType", "image/jpeg")
                    put("data", base64Image)
                })
            })

            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", partsArr)
            }))

            put("generationConfig", JSONObject().apply {
                put("temperature", 0.1)
                put("responseMimeType", "application/json")
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val errMsg = try {
                        JSONObject(body).optJSONObject("error")?.optString("message") ?: "HTTP ${response.code}"
                    } catch (e: Exception) {
                        "HTTP ${response.code}: $body"
                    }
                    return@withContext Result.failure(IOException("Gemini scan analysis failed: $errMsg"))
                }

                val json = JSONObject(body)
                val candidates = json.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val contentObj = firstCandidate.optJSONObject("content")
                    val parts = contentObj?.optJSONArray("parts")
                    val textPart = if (parts != null && parts.length() > 0) {
                        parts.getJSONObject(0).optString("text", "")
                    } else ""

                    if (textPart.isNotBlank()) {
                        val pageObj = parseReconstructedJson(textPart).copy(originalBitmap = imageBitmap)
                        Result.success(pageObj)
                    } else {
                        Result.failure(IOException("Empty response from Gemini vision model."))
                    }
                } else {
                    Result.failure(IOException("No candidates returned from Gemini vision model."))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseReconstructedJson(rawText: String): ReconstructedDocumentPage {
        val startBrace = rawText.indexOf('{')
        val endBrace = rawText.lastIndexOf('}')

        if (startBrace != -1 && endBrace > startBrace) {
            val potentialJson = rawText.substring(startBrace, endBrace + 1)
            try {
                val obj = JSONObject(potentialJson)
                val title = obj.optString("pageTitle", "")
                val header = obj.optString("headerText", "")
                val footer = obj.optString("footerText", "")

                val elementsList = mutableListOf<DocumentElement>()
                val elementsArr = obj.optJSONArray("elements") ?: JSONArray()

                for (i in 0 until elementsArr.length()) {
                    val elObj = elementsArr.opt(i)
                    if (elObj is JSONObject) {
                        val type = elObj.optString("type", "paragraph")
                        val text = elObj.optString("text", "")
                        val level = elObj.optInt("level", 1)
                        val colorHex = if (elObj.has("colorHex") && !elObj.isNull("colorHex")) elObj.getString("colorHex") else null
                        val alignment = elObj.optString("alignment", "left")
                        val isBold = elObj.optBoolean("isBold", false)
                        val isItalic = elObj.optBoolean("isItalic", false)

                        val headersList = mutableListOf<String>()
                        val headersArr = elObj.optJSONArray("headers")
                        if (headersArr != null) {
                            for (h in 0 until headersArr.length()) headersList.add(headersArr.getString(h))
                        }

                        val rowsList = mutableListOf<List<String>>()
                        val rowsArr = elObj.optJSONArray("rows")
                        if (rowsArr != null) {
                            for (r in 0 until rowsArr.length()) {
                                val rowArr = rowsArr.optJSONArray(r)
                                if (rowArr != null) {
                                    val rowItems = mutableListOf<String>()
                                    for (c in 0 until rowArr.length()) {
                                        rowItems.add(rowArr.optString(c, ""))
                                    }
                                    rowsList.add(rowItems)
                                }
                            }
                        }

                        val itemsList = mutableListOf<String>()
                        val itemsArr = elObj.optJSONArray("items")
                        if (itemsArr != null) {
                            for (it in 0 until itemsArr.length()) itemsList.add(itemsArr.optString(it, ""))
                        }

                        val key = elObj.optString("key", "")
                        val value = elObj.optString("value", "")

                        elementsList.add(
                            DocumentElement(
                                type = type,
                                text = text,
                                level = level,
                                colorHex = colorHex,
                                alignment = alignment,
                                isBold = isBold,
                                isItalic = isItalic,
                                headers = headersList,
                                rows = rowsList,
                                items = itemsList,
                                key = key,
                                value = value
                            )
                        )
                    } else if (elObj is String && elObj.isNotBlank()) {
                        elementsList.add(DocumentElement(type = "paragraph", text = elObj))
                    }
                }

                if (elementsList.isNotEmpty()) {
                    return ReconstructedDocumentPage(
                        pageTitle = title,
                        headerText = header,
                        footerText = footer,
                        elements = elementsList
                    )
                }
            } catch (e: Exception) {
                // Fall through to markdown line parsing
            }
        }

        // Resilient fallback: parse rawText as markdown lines or paragraphs
        val lines = rawText.lines()
        val elements = mutableListOf<DocumentElement>()
        val bulletBuffer = mutableListOf<String>()

        fun flushBullets() {
            if (bulletBuffer.isNotEmpty()) {
                elements.add(DocumentElement(type = "bullet_list", items = bulletBuffer.toList()))
                bulletBuffer.clear()
            }
        }

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank() || trimmed.startsWith("```")) {
                flushBullets()
                continue
            }
            if (trimmed.startsWith("### ")) {
                flushBullets()
                elements.add(DocumentElement(type = "heading", level = 3, text = trimmed.removePrefix("### ").trim()))
            } else if (trimmed.startsWith("## ")) {
                flushBullets()
                elements.add(DocumentElement(type = "heading", level = 2, text = trimmed.removePrefix("## ").trim()))
            } else if (trimmed.startsWith("# ")) {
                flushBullets()
                elements.add(DocumentElement(type = "heading", level = 1, text = trimmed.removePrefix("# ").trim()))
            } else if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                bulletBuffer.add(trimmed.drop(2).trim())
            } else {
                flushBullets()
                elements.add(DocumentElement(type = "paragraph", text = trimmed))
            }
        }
        flushBullets()

        return ReconstructedDocumentPage(
            pageTitle = elements.firstOrNull { it.type == "heading" }?.text ?: "Scanned Page",
            headerText = "",
            footerText = "",
            elements = if (elements.isNotEmpty()) elements else listOf(DocumentElement(type = "paragraph", text = rawText.take(2000)))
        )
    }
}
