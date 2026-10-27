package com.megacity.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class GeminiResult(
    val text: String,
    val promptTokens: Int,
    val outputTokens: Int,
    val latencyMs: Long,
    /** Operazioni di ricerca eseguite dal provider (solo Groq: executed_tools). */
    val searchCount: Int = 0,
    val searchQueries: List<String> = emptyList()
)

object GeminiClient {

    /** contents: lista di (role, testo) con role "user" o "model", già alternati. */
    suspend fun generate(
        p: Params,
        temperature: Float,
        system: String,
        contents: List<Pair<String, String>>
    ): Result<GeminiResult> = withContext(Dispatchers.IO) {
        runCatching {
            require(p.apiKey.isNotBlank()) { "API key mancante: inseriscila in Parametri." }
            val body = JSONObject().apply {
                put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                put("contents", JSONArray().apply {
                    contents.forEach { (role, text) ->
                        put(
                            JSONObject()
                                .put("role", role)
                                .put("parts", JSONArray().put(JSONObject().put("text", text)))
                        )
                    }
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", temperature.toDouble())
                    put("topP", p.topP.toDouble())
                    put("topK", p.topK)
                    put("maxOutputTokens", p.maxOutputTokens)
                })
                if (p.webSearch) {
                    put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
                }
            }

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/${p.model.trim()}:generateContent")
            val started = System.currentTimeMillis()
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", p.apiKey.trim())
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                val latency = System.currentTimeMillis() - started
                if (code !in 200..299) {
                    val msg = try {
                        JSONObject(raw).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        raw.take(300)
                    }
                    val retryAfter = conn.getHeaderField("retry-after")?.toDoubleOrNull()?.toInt()
                    throw ApiException(code, "HTTP $code: $msg", retryAfter)
                }
                val json = JSONObject(raw)
                val candidates = json.optJSONArray("candidates")
                val sb = StringBuilder()
                if (candidates != null && candidates.length() > 0) {
                    val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
                    }
                }
                if (sb.isEmpty()) {
                    val reason = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                    error("Risposta vuota" + if (reason.isNotEmpty()) " (bloccata: $reason)" else "")
                }
                val usage = json.optJSONObject("usageMetadata")
                GeminiResult(
                    text = sb.toString().trim(),
                    promptTokens = usage?.optInt("promptTokenCount", 0) ?: 0,
                    outputTokens = usage?.optInt("candidatesTokenCount", 0) ?: 0,
                    latencyMs = latency
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Embedding di un testo. Per gemini-embedding-2 il tipo di task va nel prefisso del testo, non in taskType. */
    suspend fun embed(p: Params, text: String, isQuery: Boolean): Result<FloatArray> = withContext(Dispatchers.IO) {
        runCatching {
            require(p.apiKey.isNotBlank()) { "API key mancante." }
            val model = p.embeddingModel.trim()
            val v2 = model.contains("embedding-2")
            val input = if (v2) {
                if (isQuery) "task: search result | query: $text" else "title: none | text: $text"
            } else text
            val body = JSONObject().apply {
                put("model", "models/$model")
                put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", input))))
                put("output_dimensionality", p.embeddingDim)
                if (!v2) put("taskType", if (isQuery) "RETRIEVAL_QUERY" else "RETRIEVAL_DOCUMENT")
            }
            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", p.apiKey.trim())
            }
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    val msg = try {
                        JSONObject(raw).getJSONObject("error").getString("message")
                    } catch (e: Exception) {
                        raw.take(300)
                    }
                    error("Embedding HTTP $code: $msg")
                }
                val json = JSONObject(raw)
                val arr = json.optJSONObject("embedding")?.optJSONArray("values")
                    ?: json.optJSONArray("embeddings")?.optJSONObject(0)?.optJSONArray("values")
                    ?: error("Risposta embedding non riconosciuta")
                FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
