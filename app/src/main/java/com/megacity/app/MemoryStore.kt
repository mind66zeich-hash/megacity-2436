package com.megacity.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt
import java.util.UUID

enum class Layer(val label: String) {
    EPISODIC("Episodica"),
    SEMANTIC("Semantica"),
    PROCEDURAL("Procedurale")
}

/** Schema canonico a 13 parametri + layer di appartenenza. */
data class MemEntry(
    val id: String,
    val timestamp: Long,
    val sourceAgent: String,
    val type: String,
    val content: String,
    val confidence: Double,
    val ttlDays: Int,                // 0 = nessuna scadenza
    val supersededBy: String?,
    val validationStatus: String,
    val contextHash: String,
    val criticality: String,
    val goalId: String,
    val taskId: String,
    val layer: Layer
) {
    fun isExpired(now: Long) = ttlDays > 0 && timestamp + ttlDays * 86_400_000L < now
    fun isActive(now: Long) = supersededBy == null && !isExpired(now)

    fun toJson() = JSONObject().apply {
        put("id", id); put("timestamp", timestamp); put("source_agent", sourceAgent)
        put("type", type); put("content", content); put("confidence", confidence)
        put("ttl", ttlDays); put("superseded_by", supersededBy ?: JSONObject.NULL)
        put("validation_status", validationStatus); put("context_hash", contextHash)
        put("criticality", criticality); put("goal_id", goalId); put("task_id", taskId)
        put("layer", layer.name)
    }

    companion object {
        fun fromJson(o: JSONObject) = MemEntry(
            id = o.getString("id"),
            timestamp = o.getLong("timestamp"),
            sourceAgent = o.optString("source_agent"),
            type = o.optString("type"),
            content = o.optString("content"),
            confidence = o.optDouble("confidence", 0.5),
            ttlDays = o.optInt("ttl", 0),
            supersededBy = if (o.isNull("superseded_by")) null else o.optString("superseded_by"),
            validationStatus = o.optString("validation_status", "unverified"),
            contextHash = o.optString("context_hash"),
            criticality = o.optString("criticality", "low"),
            goalId = o.optString("goal_id", "G0"),
            taskId = o.optString("task_id", "T0"),
            layer = runCatching { Layer.valueOf(o.getString("layer")) }.getOrDefault(Layer.EPISODIC)
        )
    }
}

class Vec(val model: String, val values: FloatArray)

class Hit(val entry: MemEntry, val score: Float)

/** Memoria di un singolo personaggio: ogni id ha i suoi file, quindi le memorie sono indipendenti. */
class MemoryStore(context: Context, val ownerId: String) {
    private val dir = File(context.filesDir, "memorie").apply { mkdirs() }
    private val payloadFile = File(dir, "${ownerId}_payload.json")
    private val logFile = File(dir, "${ownerId}_event_log.jsonl")
    private val indexFile = File(dir, "${ownerId}_index.bin")

    val entries = mutableListOf<MemEntry>()

    /** Matrice vettoriale: id del ricordo -> embedding. Equivalente del file .npy della specifica. */
    val vectors = mutableMapOf<String, Vec>()
    var eventCount = 0
        private set

    init {
        load()
    }

    private fun load() {
        try {
            if (payloadFile.exists()) {
                val arr = JSONArray(payloadFile.readText())
                for (i in 0 until arr.length()) entries += MemEntry.fromJson(arr.getJSONObject(i))
            }
            if (logFile.exists()) eventCount = logFile.useLines { it.count() }
            loadIndex()
        } catch (e: Exception) {
            // File corrotto: si riparte da memoria vuota senza bloccare l'app.
        }
    }

    private fun persist() {
        val arr = JSONArray()
        entries.forEach { arr.put(it.toJson()) }
        // Scrittura atomica: prima su file temporaneo, poi rinomina (evita file a metà se l'app si chiude).
        val tmp = File(payloadFile.path + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(payloadFile)) { payloadFile.writeText(arr.toString()); tmp.delete() }
    }

    /** Registro append-only: ogni mutazione è una riga. */
    fun log(event: String, detail: String = "") {
        val line = JSONObject().put("ts", System.currentTimeMillis()).put("event", event).put("detail", detail)
        logFile.appendText(line.toString() + "\n")
        eventCount++
    }

    fun add(
        layer: Layer,
        type: String,
        content: String,
        source: String,
        ttlDays: Int,
        confidence: Double = 0.5,
        validation: String = "unverified",
        criticality: String = "low"
    ): MemEntry {
        val e = MemEntry(
            id = UUID.randomUUID().toString().take(8),
            timestamp = System.currentTimeMillis(),
            sourceAgent = source, type = type, content = content,
            confidence = confidence, ttlDays = ttlDays, supersededBy = null,
            validationStatus = validation,
            contextHash = Integer.toHexString(content.hashCode()),
            criticality = criticality, goalId = "G0", taskId = "T0", layer = layer
        )
        entries += e
        persist()
        log("add", "${layer.name}:${e.id}")
        return e
    }

    fun delete(id: String) {
        if (entries.removeAll { it.id == id }) {
            vectors.remove(id)
            persist()
            persistIndex()
            log("delete", id)
        }
    }

    /** Trasforma un ricordo con scadenza in memoria semantica permanente. Il vettore resta collegato allo stesso id. */
    fun makePermanent(id: String) {
        val i = entries.indexOfFirst { it.id == id }
        if (i < 0) return
        val e = entries[i]
        if (e.layer == Layer.SEMANTIC && e.ttlDays == 0) return
        entries[i] = e.copy(layer = Layer.SEMANTIC, ttlDays = 0)
        persist()
        log("promote", id)
    }

    /** Rimuove le voci scadute (TTL). Le semantiche senza TTL e gli invarianti restano intatti. */
    fun purgeExpired(): Int {
        val now = System.currentTimeMillis()
        val n = entries.count { it.isExpired(now) }
        if (n > 0) {
            val gone = entries.filter { it.isExpired(now) }.map { it.id }
            entries.removeAll { it.isExpired(now) }
            gone.forEach { vectors.remove(it) }
            persist()
            persistIndex()
        }
        log("purge_expired", n.toString())
        return n
    }

    /** Nuova partita: cancella ricordi, vettori e registro di questo personaggio. */
    fun clearAll() {
        entries.clear()
        vectors.clear()
        payloadFile.delete()
        indexFile.delete()
        logFile.delete()
        eventCount = 0
    }

    fun count(layer: Layer): Int = entries.count { it.layer == layer }

    /** Richiamo per sovrapposizione di parole (nessun embedding in questa versione). */
    fun recall(query: String, k: Int): List<MemEntry> {
        val now = System.currentTimeMillis()
        val q = tokens(query)
        if (q.isEmpty() || k <= 0) return emptyList()
        return entries.asSequence()
            .filter { it.isActive(now) }
            .map { it to tokens(it.content).count { t -> t in q } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(k)
            .map { it.first }
            .toList()
    }

    // ---------- Vettori e similarità del coseno ----------

    private fun loadIndex() {
        if (!indexFile.exists()) return
        DataInputStream(BufferedInputStream(indexFile.inputStream())).use { d ->
            val n = d.readInt()
            repeat(n) {
                val id = d.readUTF()
                val model = d.readUTF()
                val dim = d.readInt()
                val v = FloatArray(dim) { d.readFloat() }
                vectors[id] = Vec(model, v)
            }
        }
    }

    private fun persistIndex() {
        DataOutputStream(BufferedOutputStream(indexFile.outputStream())).use { d ->
            d.writeInt(vectors.size)
            vectors.forEach { (id, v) ->
                d.writeUTF(id)
                d.writeUTF(v.model)
                d.writeInt(v.values.size)
                v.values.forEach { d.writeFloat(it) }
            }
        }
    }

    fun setVector(id: String, model: String, values: FloatArray) {
        if (entries.none { it.id == id }) return // ricordo cancellato nel frattempo
        vectors[id] = Vec(model, values)
        persistIndex()
        log("embed", id)
    }

    private fun hasVector(id: String, model: String, dim: Int): Boolean {
        val v = vectors[id] ?: return false
        return v.model == model && v.values.size == dim
    }

    fun missingVector(model: String, dim: Int): List<MemEntry> =
        entries.filter { !hasVector(it.id, model, dim) }

    fun vectorCount(model: String, dim: Int): Int =
        entries.count { hasVector(it.id, model, dim) }

    fun recallSemantic(query: FloatArray, model: String, k: Int, threshold: Float): List<Hit> {
        if (k <= 0) return emptyList()
        val now = System.currentTimeMillis()
        return entries.asSequence()
            .filter { it.isActive(now) }
            .mapNotNull { e ->
                val v = vectors[e.id]
                if (v == null || v.model != model || v.values.size != query.size) null
                else Hit(e, cosine(query, v.values))
            }
            .filter { it.score >= threshold }
            .sortedByDescending { it.score }
            .take(k)
            .toList()
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        val den = sqrt(na) * sqrt(nb)
        return if (den == 0f) 0f else dot / den
    }

    private fun tokens(s: String): Set<String> =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.toSet()
}
