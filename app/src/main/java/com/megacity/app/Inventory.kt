package com.megacity.app

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val DISTRIBUTOR_ID = "distributore"
const val START_CREDITS = 1200

val ITEM_TYPES = listOf("arma", "tool", "consumabile", "altro")

/** Un oggetto del gioco: nell'inventario di Adrian o nel catalogo di NODO-7. */
data class Item(
    val id: String = UUID.randomUUID().toString().take(8),
    val name: String,
    val type: String,
    val description: String,
    /** Cariche o usi rimasti; -1 = illimitato. */
    val charges: Int = -1,
    /** Prezzo in crediti (usato nel catalogo). */
    val price: Int = 0
) {
    val emoji: String
        get() = when (type) {
            "arma" -> "🔫"
            "tool" -> "🛠️"
            "consumabile" -> "💊"
            else -> "📦"
        }

    val chargesLabel: String get() = if (charges < 0) "∞" else charges.toString()
    val usable: Boolean get() = charges != 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("nome", name); put("tipo", type); put("descrizione", description)
        put("cariche", charges); put("prezzo", price)
    }

    /** Riga compatta per i prompt. */
    fun promptLine(withPrice: Boolean = false): String =
        "$name ($type, cariche ${chargesLabel})" + (if (withPrice) ", $price crediti" else "") + ": $description"

    companion object {
        /** Legge un oggetto da JSON, sia salvato dall'app sia scritto dal modello (campi in italiano). */
        fun fromJson(o: JSONObject): Item? {
            val name = o.optString("nome", o.optString("name", "")).trim()
            if (name.isEmpty()) return null
            val type = o.optString("tipo", o.optString("type", "altro")).trim().lowercase()
            return Item(
                id = o.optString("id", "").ifBlank { UUID.randomUUID().toString().take(8) },
                name = name.take(60),
                type = if (type in ITEM_TYPES) type else "altro",
                description = o.optString("descrizione", o.optString("description", "")).trim().take(200),
                charges = o.optInt("cariche", o.optInt("charges", -1)).coerceAtLeast(-1),
                price = o.optInt("prezzo", o.optInt("price", 0)).coerceAtLeast(0)
            )
        }
    }
}

fun itemsToJson(list: List<Item>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

fun itemsFromJson(s: String?): List<Item> {
    if (s.isNullOrBlank()) return emptyList()
    return try {
        val arr = JSONArray(s)
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Item::fromJson) }
    } catch (e: Exception) {
        emptyList()
    }
}

val START_INVENTORY = listOf(
    Item(
        name = "Pistola a impulsi", type = "arma",
        description = "Arma compatta a impulsi elettromagnetici, batteria a metà.", charges = 6
    ),
    Item(
        name = "Giacca termica", type = "altro",
        description = "Logora ma ancora isolante; protegge da freddo e pioggia acida.", charges = -1
    )
)

// =====================================================================
// TAG DI EFFETTO NELLE RISPOSTE DEL MODELLO
// =====================================================================

/** Effetti di gioco estratti da una risposta. */
data class Effects(
    val credits: Int = 0,
    val gained: List<Item> = emptyList(),
    val lost: List<String> = emptyList(),
    val catalog: List<Item>? = null
) {
    val isEmpty get() = credits == 0 && gained.isEmpty() && lost.isEmpty() && catalog == null
}

private val OPT = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
private val TAG_CREDITS = Regex("<crediti>\\s*([+-]?\\s*[\\d.]+)\\s*</crediti>", OPT)
private val TAG_GAIN = Regex("<ottieni>(.*?)</ottieni>", OPT)
private val TAG_LOSE = Regex("<perdi>(.*?)</perdi>", OPT)
private val TAG_CATALOG = Regex("<catalogo>(.*?)</catalogo>", OPT)
/** Tag aperto e mai chiuso (risposta troncata): si taglia da lì in poi. */
private val TAG_DANGLING = Regex("<(crediti|ottieni|perdi|catalogo)>.*$", OPT)
/** Blocchi ```json ... ``` che a volte il modello mette intorno ai tag. */
private val CODE_FENCE = Regex("```(json)?", RegexOption.IGNORE_CASE)

/** Catalogo JSON scritto dal modello: al massimo 5 oggetti con prezzo; null se non leggibile o vuoto. */
private fun parseCatalog(json: String): List<Item>? {
    val items: List<Item> = try {
        val arr = JSONArray(json.trim())
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val item = Item.fromJson(o) ?: continue
            if (item.price > 0) out += item
        }
        out.take(5)
    } catch (e: Exception) {
        emptyList()
    }
    return if (items.isEmpty()) null else items
}

/** Separa il testo da mostrare dai tag di effetto. */
fun parseEffects(raw: String): Pair<String, Effects> {
    var credits = 0
    TAG_CREDITS.findAll(raw).forEach { m ->
        m.groupValues[1].replace(" ", "").replace(".", "").toIntOrNull()?.let { credits += it }
    }
    val gained = TAG_GAIN.findAll(raw).mapNotNull { m ->
        try { Item.fromJson(JSONObject(m.groupValues[1].trim())) } catch (e: Exception) { null }
    }.toList()
    val lost = TAG_LOSE.findAll(raw).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
    val catalogJson: String? = TAG_CATALOG.find(raw)?.groupValues?.get(1)
    val catalog: List<Item>? = if (catalogJson == null) null else parseCatalog(catalogJson)

    val text = raw
        .replace(TAG_CREDITS, "").replace(TAG_GAIN, "").replace(TAG_LOSE, "").replace(TAG_CATALOG, "")
        .replace(TAG_DANGLING, "").replace(CODE_FENCE, "")
        .replace(Regex("\\n{3,}"), "\n\n").trim()
    return text to Effects(credits, gained, lost, catalog)
}

/** Istruzioni sui tag, comuni a tutti i personaggi e al narratore. */
const val EFFECTS_RULES =
    "EFFETTI DI GIOCO (crediti e oggetti di Adrian sono gestiti dal gioco): quando nella scena Adrian riceve " +
        "o perde davvero crediti o oggetti, aggiungi IN FONDO alla risposta i tag corrispondenti. Non usarli per " +
        "cose solo promesse, proposte o ipotetiche. Adrian non vede i tag.\n" +
        "<crediti>+500</crediti> oppure <crediti>-200</crediti>\n" +
        "<ottieni>{\"nome\":\"...\",\"tipo\":\"arma|tool|consumabile|altro\",\"descrizione\":\"...\",\"cariche\":3}</ottieni> " +
        "(cariche -1 = illimitato)\n" +
        "<perdi>nome esatto dell'oggetto</perdi>\n" +
        "Non togliere più crediti di quanti ne abbia Adrian e non togliere oggetti che non possiede."
