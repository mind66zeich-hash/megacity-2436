package com.megacity.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val MRNICE_ID = "mrnice"

/** Stati di una missione accettata. */
const val MISSION_ACTIVE = "attiva"
const val MISSION_DONE = "completata"   // obiettivo raggiunto, ricompensa da riscuotere da Mr Nice

/** Una missione della bacheca di Mr Nice. */
data class Mission(
    val id: String = UUID.randomUUID().toString().take(8),
    val title: String,
    val objective: String,
    val location: String,
    val difficulty: String,
    val reward: Int,
    val status: String = ""
) {
    val difficultyEmoji: String
        get() = when (difficulty) {
            "facile" -> "🟢"
            "media" -> "🟡"
            else -> "🔴"
        }

    fun promptLine(): String = "\"$title\" ($difficulty, $reward crediti): $objective. Luogo: $location."

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("titolo", title); put("obiettivo", objective); put("luogo", location)
        put("difficolta", difficulty); put("ricompensa", reward); put("stato", status)
    }

    companion object {
        /** Legge una missione salvata dall'app o scritta dal modello (campi in italiano). */
        fun fromJson(o: JSONObject): Mission? {
            val title = o.optString("titolo", o.optString("title", "")).trim()
            val reward = o.optInt("ricompensa", o.optInt("reward", 0))
            if (title.isEmpty() || reward <= 0) return null
            val diff = o.optString("difficolta", o.optString("difficoltà", "media")).trim().lowercase()
            return Mission(
                id = o.optString("id", "").ifBlank { UUID.randomUUID().toString().take(8) },
                title = title.take(80),
                objective = o.optString("obiettivo", o.optString("objective", "")).trim().take(300),
                location = o.optString("luogo", o.optString("location", "Settore 4")).trim().take(80),
                difficulty = if (diff in listOf("facile", "media", "difficile")) diff else "media",
                reward = reward.coerceIn(1, 100_000),
                status = o.optString("stato", "")
            )
        }
    }
}

fun missionsToJson(list: List<Mission>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

fun missionsFromJson(s: String?): List<Mission> {
    if (s.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(s)
        val out = mutableListOf<Mission>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            Mission.fromJson(o)?.let { out += it }
        }
        out
    } catch (e: Exception) {
        emptyList()
    }
}

fun missionFromJson(s: String?): Mission? {
    if (s.isNullOrBlank()) return null
    return try { Mission.fromJson(JSONObject(s)) } catch (e: Exception) { null }
}

/** Bacheca scritta dal modello: al massimo 3 missioni; null se non leggibile o vuota. */
fun parseBoard(json: String): List<Mission>? {
    val list = missionsFromJson(json.trim()).take(3)
    return if (list.isEmpty()) null else list
}
