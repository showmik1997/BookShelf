package com.example.service.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OpenAiChatMessage(
    val role: String,
    val content: String
)

data class OpenAiChatResponse(
    val text: String,
    val error: String? = null
)

class OpenAiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun fetchAvailableModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("OpenAI API key is empty"))
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/models")
            .addHeader("Authorization", "Bearer $apiKey")
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
                    return@withContext Result.failure(IOException("Failed to fetch models: $errMsg"))
                }

                val json = JSONObject(body)
                val data = json.getJSONArray("data")
                val models = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val id = data.getJSONObject(i).getString("id")
                    // Filter popular GPT models
                    if (id.startsWith("gpt-") || id.startsWith("o1") || id.startsWith("o3")) {
                        models.add(id)
                    }
                }
                models.sort()
                Result.success(if (models.isNotEmpty()) models else listOf("gpt-4o-mini", "gpt-4o", "gpt-3.5-turbo"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun generateChatCompletion(
        apiKey: String,
        model: String,
        messages: List<OpenAiChatMessage>
    ): OpenAiChatResponse = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext OpenAiChatResponse("", error = "Please provide your OpenAI API Key in Settings.")
        }

        val jsonBody = JSONObject().apply {
            put("model", model.ifBlank { "gpt-4o-mini" })
            val msgsArray = JSONArray()
            for (msg in messages) {
                msgsArray.put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
            put("messages", msgsArray)
            put("temperature", 0.3)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
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
                    return@withContext OpenAiChatResponse("", error = errMsg)
                }

                val json = JSONObject(body)
                val choices = json.getJSONArray("choices")
                if (choices.length() > 0) {
                    val choice = choices.getJSONObject(0)
                    val message = choice.getJSONObject("message")
                    val content = message.optString("content", "")
                    OpenAiChatResponse(content)
                } else {
                    OpenAiChatResponse("", error = "Empty completion response from OpenAI.")
                }
            }
        } catch (e: Exception) {
            OpenAiChatResponse("", error = "Network connection failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }
}
