package com.megacity.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Errore HTTP di un provider, con il codice per decidere se ritentare o passare all'altro provider. */
class ApiException(val code: Int, message: String, val retryAfterSec: Int? = null) : Exception(message)

data class LlmOutcome(
    val result: GeminiResult,
    val provider: String,          // "gemini" | "groq"
    val model: String,
    val failedOverFrom: String?    // errore del provider principale, se la risposta viene dall'altro
)

/** Modelli suggeriti per Groq. Il campo modello resta libero: gli ID cambiano spesso. */
val GROQ_PRESETS = listOf(
    "qwen/qwen3.8-27b" to "Qwen 3.8 27B (anteprima)",
    "llama-3.3-70b-versatile" to "Llama 3.3 70B",
    "llama-3.1-8b-instant" to "Llama 3.1 8B",
    "openai/gpt-oss-120b" to "GPT-OSS 120B",
    "openai/gpt-oss-20b" to "GPT-OSS 20B"
)

object GroqClient {

    private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    /** Segnaposto di citazione della ricerca integrata, tipo 【2†L6-L10】: senza elenco fonti non servono. */
    private val CITE = Regex("\u3010[^\u3011]*\u3011")

    /** contents: lista di (role, testo) con role "user" o "model". Lancia ApiException sugli errori HTTP. */
    suspend fun generate(
        p: Params,
        temperature: Float,
        system: String,
        contents: List<Pair<String, String>>
    ): GeminiResult = withContext(Dispatchers.IO) {
        require(p.groqKey.isNotBlank()) { "API key Groq mancante: inseriscila in Parametri." }

        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        contents.forEach { (role, text) ->
            messages.put(
                JSONObject()
                    .put("role", if (role == "model") "assistant" else "user")
                    .put("content", text)
            )
        }
        // Groq non supporta top_k: si inviano solo temperature, top_p e il limite di token.
        val body = JSONObject().apply {
            put("model", p.groqModel.trim())
            put("messages", messages)
            put("temperature", temperature.toDouble())
            put("top_p", p.topP.toDouble())
            // Navigare consuma molti token (ragionamento + pagine lette): con la ricerca attiva almeno 4096.
            put("max_completion_tokens", if (p.groqBrowse) maxOf(p.maxOutputTokens, 4096) else p.maxOutputTokens)
            if (p.groqBrowse) {
                // Ricerca web integrata di Groq (solo GPT-OSS): la esegue il server in una sola chiamata.
                put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
                put("tool_choice", if (p.groqForceSearch) "required" else "auto")
                put("reasoning_effort", "low") // consigliato da Groq: sessioni di ricerca più brevi
            }
        }

        val started = System.currentTimeMillis()
        val conn = (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${p.groqKey.trim()}")
            setRequestProperty("User-Agent", "Megacity2436/1.0 (Android)")
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
            val choice = json.optJSONArray("choices")?.optJSONObject(0)
            val msgObj = choice?.optJSONObject("message")
            var text = if (msgObj == null || msgObj.isNull("content")) "" else msgObj.optString("content", "")

            // I modelli con ragionamento (Qwen) possono includere il pensiero tra <think>…</think>.
            text = text.replace(THINK, "").replace(CITE, "")
            val thinkStart = text.indexOf("<think>")
            if (thinkStart >= 0) text = text.substring(0, thinkStart)
            text = text.trim()

            if (text.isEmpty()) {
                val reason = choice?.optString("finish_reason").orEmpty()
                error(
                    "Risposta vuota" + (if (reason.isNotEmpty()) " (motivo: $reason)" else "") +
                        ". Se il modello ragiona, alza «Max token in uscita» in Parametri."
                )
            }
            val usage = json.optJSONObject("usage")
            // Ricerche realmente eseguite da Groq (executed_tools): serve a diagnosticare se la ricerca è partita.
            val exec = msgObj?.optJSONArray("executed_tools")
            val queries = mutableListOf<String>()
            if (exec != null) {
                for (i in 0 until exec.length()) {
                    val t = exec.optJSONObject(i) ?: continue
                    if (t.optString("type") == "browser_search") {
                        val q = try { JSONObject(t.optString("arguments")).optString("query") } catch (e: Exception) { "" }
                        if (q.isNotBlank()) queries += q
                    }
                }
            }
            GeminiResult(
                text = text,
                promptTokens = usage?.optInt("prompt_tokens", 0) ?: 0,
                outputTokens = usage?.optInt("completion_tokens", 0) ?: 0,
                latencyMs = latency,
                searchCount = exec?.length() ?: 0,
                searchQueries = queries
            )
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Diagnostica: richiesta minima con browser_search e tool_choice "required".
     * Non lancia eccezioni: restituisce sempre un testo con codice HTTP, tempo e risposta grezza (o errore).
     */
    suspend fun probe(p: Params): String = withContext(Dispatchers.IO) {
        if (p.groqKey.isBlank()) return@withContext "API key Groq mancante: inseriscila qui sopra."
        val model = p.groqModel.trim()
        val body = JSONObject().apply {
            put("model", model)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().put("role", "user").put(
                        "content",
                        "Cerca sul web e dimmi un titolo di notizia di oggi, indicando la fonte."
                    )
                )
            )
            put("max_completion_tokens", 2048)
            put("tools", JSONArray().put(JSONObject().put("type", "browser_search")))
            put("tool_choice", "required")
            put("reasoning_effort", "low")
        }
        val out = StringBuilder()
        out.append("Modello: ").append(model).append('\n')
        out.append("Richiesta: tools=[browser_search], tool_choice=required, reasoning_effort=low\n")
        val started = System.currentTimeMillis()
        val conn = try {
            (URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 90000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${p.groqKey.trim()}")
                setRequestProperty("User-Agent", "Megacity2436/1.0 (Android)")
            }
        } catch (e: Exception) {
            return@withContext out.append("Errore di connessione: ").append(e.message).toString()
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val ms = System.currentTimeMillis() - started
            out.append("HTTP ").append(code).append(" in ").append(ms).append(" ms\n")

            if (code in 200..299) {
                try {
                    val json = JSONObject(raw)
                    val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    val msg = choice?.optJSONObject("message")
                    out.append("finish_reason: ").append(choice?.optString("finish_reason")).append('\n')
                    out.append("campi del messaggio: ").append(msg?.keys()?.asSequence()?.joinToString(", ")).append('\n')
                    val exec = msg?.optJSONArray("executed_tools")
                    out.append(
                        "ricerca eseguita da Groq (executed_tools): " +
                            (if (exec != null && exec.length() > 0) "SÌ (${exec.length()})" else "no / non indicato") + "\n"
                    )
                    out.append("\n--- RISPOSTA GREZZA ---\n").append(json.toString(2).take(8000))
                } catch (e: Exception) {
                    out.append("\n--- RISPOSTA GREZZA (non JSON) ---\n").append(raw.take(8000))
                }
            } else {
                out.append("\n--- ERRORE GREZZO ---\n").append(raw.take(4000))
            }
            out.toString()
        } catch (e: Exception) {
            out.append("Errore: ").append(e.javaClass.simpleName).append(": ").append(e.message).toString()
        } finally {
            conn.disconnect()
        }
    }
}

/** Sceglie il provider, ritenta una volta sugli errori temporanei e, se abilitato, passa all'altro provider. */
object Llm {

    private val RETRYABLE = setOf(429, 500, 502, 503, 504)

    fun label(provider: String) = if (provider == "groq") "Groq" else "Gemini"

    fun modelOf(p: Params, provider: String) =
        if (provider == "groq") p.groqModel.trim() else p.model.trim()

    private fun keyOf(p: Params, provider: String) = if (provider == "groq") p.groqKey else p.apiKey

    private fun isTransient(e: Exception): Boolean =
        (e is ApiException && (e.code in RETRYABLE || e.code == 404)) || e is IOException

    private suspend fun call(
        p: Params,
        provider: String,
        temperature: Float,
        system: String,
        contents: List<Pair<String, String>>
    ): GeminiResult {
        var attempt = 0
        while (true) {
            try {
                return if (provider == "groq") {
                    GroqClient.generate(p, temperature, system, contents)
                } else {
                    GeminiClient.generate(p, temperature, system, contents).getOrThrow()
                }
            } catch (e: ApiException) {
                if (e.code in RETRYABLE && attempt < 1) {
                    attempt++
                    delay((e.retryAfterSec ?: 2).coerceIn(1, 6) * 1000L)
                } else {
                    throw e
                }
            }
        }
    }

    suspend fun generate(
        p: Params,
        temperature: Float,
        system: String,
        contents: List<Pair<String, String>>
    ): Result<LlmOutcome> = runCatching {
        val primary = if (p.provider == "groq") "groq" else "gemini"
        val secondary = if (primary == "groq") "gemini" else "groq"
        try {
            LlmOutcome(call(p, primary, temperature, system, contents), primary, modelOf(p, primary), null)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            val canFailOver = p.failover && keyOf(p, secondary).isNotBlank() && isTransient(e)
            if (!canFailOver) throw e
            try {
                LlmOutcome(
                    call(p, secondary, temperature, system, contents),
                    secondary,
                    modelOf(p, secondary),
                    "${label(primary)}: ${e.message}"
                )
            } catch (e2: Exception) {
                if (e2 is CancellationException) throw e2
                error("${label(primary)}: ${e.message} | ${label(secondary)}: ${e2.message}")
            }
        }
    }
}
