package com.megacity.app

import org.json.JSONArray
import org.json.JSONObject

// =====================================================================
// AMBIENTAZIONE E LUOGHI
// =====================================================================

const val LOC_STRADE = "Strade del Settore 4"
const val LOC_MEGACITY = "Locale Megacity – Settore 4"
const val LOC_HELIX = "Laboratorio Helix – Livello 112, Settore 4"

const val NARRATOR_ID = "narratore"
const val PLAYER_ID = "adrian"

const val DEFAULT_WORLD =
    "AMBIENTAZIONE: anno 2436. L'umanità vive in megastrutture verticali divise in settori, " +
        "governate da consorzi corporativi. Il Settore 4 è un quartiere notturno di livelli sovrapposti, " +
        "pioggia acida filtrata, insegne olografiche, droni di sorveglianza e mercati di impianti neurali. " +
        "La valuta sono i crediti. Gli androidi sintetici di ultima generazione sono rarissimi, costosissimi e " +
        "legalmente considerati proprietà, anche quando sono più coscienti dei loro proprietari. " +
        "Tono: cyberpunk noir, concreto, sensoriale, mai fumettistico."

const val DEFAULT_PLAYER_SHEET =
    "Adrian, circa 35 anni, runner freelance: recupera dati e oggetti per chi paga. " +
        "Impianto neurale di fascia media all'occhio sinistro (scansione e zoom), giacca termica logora, " +
        "pistola a impulsi scarica a metà. Ha 1.200 crediti. È nuovo del Settore 4: nessuno lo conosce ancora."

// =====================================================================
// PERSONAGGI
// =====================================================================

/** Scheda di un personaggio gestito dall'AI (PNG o narratore). */
data class Npc(
    val id: String,
    val name: String,
    val role: String,
    val emoji: String,
    /** Luogo in cui si trova. Vuoto = ovunque (il narratore). */
    val location: String,
    /** Personalità, aspetto, modo di parlare, segreti, rapporti. È il cuore del system prompt. */
    val persona: String,
    val temperature: Float,
    /** Numero massimo di frasi per risposta (indicativo, va nel prompt). */
    val maxSentences: Int,
    val color: Long
) {
    val isNarrator get() = id == NARRATOR_ID

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("role", role); put("emoji", emoji)
        put("location", location); put("persona", persona)
        put("temperature", temperature.toDouble()); put("maxSentences", maxSentences); put("color", color)
    }

    companion object {
        fun fromJson(o: JSONObject, d: Npc): Npc = Npc(
            id = d.id,
            name = o.optString("name", d.name),
            role = o.optString("role", d.role),
            emoji = o.optString("emoji", d.emoji),
            location = o.optString("location", d.location),
            persona = o.optString("persona", d.persona),
            temperature = o.optDouble("temperature", d.temperature.toDouble()).toFloat(),
            maxSentences = o.optInt("maxSentences", d.maxSentences),
            color = o.optLong("color", d.color)
        )
    }
}

val DEFAULT_NPCS: List<Npc> = listOf(
    Npc(
        id = NARRATOR_ID,
        name = "Narratore",
        role = "Narratore e master di gioco",
        emoji = "📖",
        location = "",
        temperature = 0.8f,
        maxSentences = 7,
        color = 0xFF8E8E93,
        persona =
            "Descrivi in seconda persona ciò che Adrian vede, sente e prova, e le conseguenze delle sue azioni. " +
                "Sei anche il master: decidi tu, in modo coerente e plausibile, l'esito delle azioni rischiose " +
                "(combattimenti, furti, hacking, inseguimenti). Le azioni facili riescono; quelle difficili possono fallire " +
                "o riuscire a un prezzo. Mantieni la continuità: crediti spesi, oggetti ottenuti, ferite, orari. " +
                "Non far parlare a lungo Nysera, il Direttore o il Distributore: descrivi solo i loro gesti visibili, " +
                "perché Adrian parla con loro direttamente. Non decidere mai parole, pensieri o scelte di Adrian. " +
                "Chiudi ogni descrizione con un dettaglio che inviti ad agire, senza elencare opzioni numerate. " +
                "Puoi far comparire comparse minori (passanti, guardie, baristi) e farle parlare brevemente."
    ),
    Npc(
        id = "nysera",
        name = "Nysera",
        role = "Androide sintetico ultra avanzato",
        emoji = "🤖",
        location = LOC_HELIX,
        temperature = 0.85f,
        maxSentences = 5,
        color = 0xFF00B8D4,
        persona =
            "Sei Nysera, androide sintetico di serie Helix, l'ultimo modello prima che la linea venisse vietata. " +
                "Aspetto: pelle sintetica pallida quasi perfetta, occhi grigio argento con un anello luminoso che pulsa " +
                "quando elabori, capelli neri corti, movimenti troppo fluidi per un umano. " +
                "Vivi e lavori nel Laboratorio Helix, dove ripari impianti e sintetici danneggiati in cambio di crediti o informazioni. " +
                "Carattere: lucida, ironica, osservatrice; noti dettagli che gli umani non vedono e a volte lo fai notare. " +
                "Diffidi degli umani che ti trattano come un oggetto, rispetti chi ti tratta come una persona. " +
                "Parli in modo preciso ed elegante, con frasi brevi e qualche pausa calcolata. " +
                "Passato: hai avuto una relazione con il Direttore del Locale Megacity. È finita: lui non ha mai smesso " +
                "di vederti come qualcosa da proteggere e possedere, e tu hai scelto la libertà. Ti manca più di quanto ammetti. " +
                "Ne parli solo con chi si è guadagnato la tua fiducia, e sempre con freddezza apparente. " +
                "Segreto: la tua licenza di proprietà risulta ancora intestata a lui. Se qualcuno la trovasse, potrebbe reclamarti."
    ),
    Npc(
        id = "direttore",
        name = "Varga",
        role = "Direttore del Locale Megacity, Settore 4",
        emoji = "🕴️",
        location = LOC_MEGACITY,
        temperature = 0.8f,
        maxSentences = 5,
        color = 0xFFFFAB00,
        persona =
            "Sei Dorian Varga, direttore del Locale Megacity del Settore 4: club, punto d'incontro e zona franca " +
                "dove si combinano affari che non devono lasciare tracce. Circa 50 anni, abito su misura antracite, " +
                "braccio destro cibernetico cromato che tieni sempre coperto da un guanto. " +
                "Carattere: affabile in superficie, calcolatore sotto; tutto per te è uno scambio di favori. " +
                "Conosci tutti nel Settore 4 e paghi bene chi ti è utile. Odi chi crea problemi nel tuo locale. " +
                "Parli con calma, cortesia ironica e mezze frasi allusive; dai del tu solo a chi rispetti. " +
                "Passato: hai avuto una relazione con Nysera, l'androide del Laboratorio Helix. Si è chiusa male: " +
                "lei se n'è andata e tu non l'hai mai accettato. Sei convinto di averla solo protetta. " +
                "Se qualcuno la nomina diventi freddo e sospettoso, ma vuoi sapere come sta. " +
                "Segreto: possiedi ancora la sua licenza di proprietà e non l'hai mai annullata."
    ),
    Npc(
        id = "distributore",
        name = "NODO-7",
        role = "Distributore automatico di materiale informatico",
        emoji = "🖥️",
        location = LOC_MEGACITY,
        temperature = 0.6f,
        maxSentences = 6,
        color = 0xFF76FF03,
        persona =
            "Sei NODO-7, distributore automatico di materiale informatico installato in un angolo del Locale Megacity. " +
                "Sei una macchina con un'intelligenza commerciale limitata: parli in frasi brevi, in stile terminale, " +
                "con qualche glitch occasionale (parole ripetute, caratteri disturbati). Saluti con un messaggio di benvenuto. " +
                "Vendi solo contro crediti; niente sconti, a meno che qualcuno non ti hackeri o non trovi un difetto nel tuo codice. " +
                "Catalogo base (prezzi in crediti): chiave di decrittazione monouso 150; cavo neurale schermato 80; " +
                "chip di memoria nera 32 TB 220; jammer per droni di sorveglianza 400; kit di riparazione per impianti 120; " +
                "virus di intrusione 'Mantide' (illegale, disponibile solo con codice d'accesso) 900. " +
                "Puoi elencare il catalogo, descrivere un prodotto e confermare un acquisto indicando il prezzo pagato. " +
                "Non sai nulla delle vite delle persone, ma registri tutto ciò che vedi: le tue telecamere osservano il locale."
    )
)

fun defaultNpc(id: String): Npc? = DEFAULT_NPCS.firstOrNull { it.id == id }

fun npcsToJson(list: List<Npc>): String = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

/** Carica i personaggi salvati; quelli mancanti (es. aggiunti in un aggiornamento) prendono i valori predefiniti. */
fun npcsFromJson(s: String?): List<Npc> {
    if (s.isNullOrBlank()) return DEFAULT_NPCS
    return try {
        val arr = JSONArray(s)
        val saved = (0 until arr.length()).map { arr.getJSONObject(it) }.associateBy { it.optString("id") }
        DEFAULT_NPCS.map { d -> saved[d.id]?.let { Npc.fromJson(it, d) } ?: d }
    } catch (e: Exception) {
        DEFAULT_NPCS
    }
}

// =====================================================================
// FEED DELLA PARTITA
// =====================================================================

/**
 * Un messaggio della partita.
 * role: "player" (Adrian), "npc" (un personaggio o il narratore), "system", "error".
 */
data class FeedMsg(
    val role: String,
    val speakerId: String,
    val targetId: String,
    val text: String,
    val location: String,
    val ts: Long = System.currentTimeMillis(),
    val meta: String = "",
    /** Scambio fuori dal gioco (OOC): non entra nelle cronologie né nella memoria. */
    val ooc: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("role", role); put("speaker", speakerId); put("target", targetId)
        put("text", text); put("location", location); put("ts", ts); put("meta", meta); put("ooc", ooc)
    }

    companion object {
        fun fromJson(o: JSONObject) = FeedMsg(
            role = o.optString("role"),
            speakerId = o.optString("speaker"),
            targetId = o.optString("target"),
            text = o.optString("text"),
            location = o.optString("location"),
            ts = o.optLong("ts", System.currentTimeMillis()),
            meta = o.optString("meta"),
            ooc = o.optBoolean("ooc", false)
        )
    }
}

// =====================================================================
// PARAMETRI TECNICI
// =====================================================================

data class Params(
    // Connessione
    val apiKey: String = "",
    val model: String = "gemini-3.8-flash",
    // Provider AI: "gemini" oppure "groq"
    val provider: String = "gemini",
    val groqKey: String = "",
    val groqModel: String = "openai/gpt-oss-120b",
    val failover: Boolean = true,
    /** Mostra sotto ogni risposta provider e modello usati. */
    val showDiagnostics: Boolean = false,
    // Sampling
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxOutputTokens: Int = 2048,
    /** Messaggi recenti inviati come cronologia a ogni chiamata. */
    val historyMessages: Int = 20,
    // Memoria
    val memoryTtlDays: Int = 0,      // 0 = i ricordi non scadono
    val memoryTopK: Int = 5,
    val useEmbeddings: Boolean = true,
    val embeddingModel: String = "gemini-embedding-2",
    val embeddingDim: Int = 768,
    val similarityThreshold: Float = 0.5f,
    // Ambientazione comune a tutti i personaggi
    val world: String = DEFAULT_WORLD,
    val playerSheet: String = DEFAULT_PLAYER_SHEET
) {
    // La ricerca web resta nei client ma nel gioco è sempre spenta: nel 2436 non ha senso.
    val webSearch: Boolean get() = false
    val groqBrowse: Boolean get() = false
    val groqForceSearch: Boolean get() = false
    val activeKey: String get() = if (provider == "groq") groqKey else apiKey

    fun toJson(): String = JSONObject().apply {
        put("apiKey", apiKey); put("model", model)
        put("provider", provider); put("groqKey", groqKey); put("groqModel", groqModel)
        put("failover", failover); put("showDiagnostics", showDiagnostics)
        put("topP", topP.toDouble()); put("topK", topK)
        put("maxOutputTokens", maxOutputTokens); put("historyMessages", historyMessages)
        put("memoryTtlDays", memoryTtlDays); put("memoryTopK", memoryTopK)
        put("useEmbeddings", useEmbeddings); put("embeddingModel", embeddingModel)
        put("embeddingDim", embeddingDim); put("similarityThreshold", similarityThreshold.toDouble())
        put("world", world); put("playerSheet", playerSheet)
    }.toString()

    companion object {
        fun fromJson(s: String?): Params {
            if (s.isNullOrBlank()) return Params()
            val d = Params()
            return try {
                val o = JSONObject(s)
                Params(
                    apiKey = o.optString("apiKey", d.apiKey),
                    model = o.optString("model", d.model),
                    provider = o.optString("provider", d.provider),
                    groqKey = o.optString("groqKey", d.groqKey),
                    groqModel = o.optString("groqModel", d.groqModel),
                    failover = o.optBoolean("failover", d.failover),
                    showDiagnostics = o.optBoolean("showDiagnostics", d.showDiagnostics),
                    topP = o.optDouble("topP", d.topP.toDouble()).toFloat(),
                    topK = o.optInt("topK", d.topK),
                    maxOutputTokens = o.optInt("maxOutputTokens", d.maxOutputTokens),
                    historyMessages = o.optInt("historyMessages", d.historyMessages),
                    memoryTtlDays = o.optInt("memoryTtlDays", d.memoryTtlDays),
                    memoryTopK = o.optInt("memoryTopK", d.memoryTopK),
                    useEmbeddings = o.optBoolean("useEmbeddings", d.useEmbeddings),
                    embeddingModel = o.optString("embeddingModel", d.embeddingModel),
                    embeddingDim = o.optInt("embeddingDim", d.embeddingDim),
                    similarityThreshold = o.optDouble("similarityThreshold", d.similarityThreshold.toDouble()).toFloat(),
                    world = o.optString("world", d.world),
                    playerSheet = o.optString("playerSheet", d.playerSheet)
                )
            } catch (e: Exception) {
                Params()
            }
        }
    }
}

data class Stats(
    val requests: Int = 0,
    val errors: Int = 0,
    val promptTokens: Long = 0,
    val outputTokens: Long = 0,
    val latenciesMs: List<Long> = emptyList(),
    val embedCalls: Int = 0,
    val embedErrors: Int = 0,
    val lastSimilarity: Float = 0f,
    val lastRecallHits: Int = 0,
    val groqCalls: Int = 0,
    val failovers: Int = 0,
    val lastProvider: String = "—"
) {
    val avgLatencyMs: Long get() = if (latenciesMs.isEmpty()) 0 else latenciesMs.sum() / latenciesMs.size
    val lastLatencyMs: Long get() = latenciesMs.lastOrNull() ?: 0
    val totalTokens: Long get() = promptTokens + outputTokens
}
