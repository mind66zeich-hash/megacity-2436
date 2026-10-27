package com.megacity.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File

/** Righe che contengono solo un'azione tra asterischi: vanno sempre al narratore. */
private val PURE_ACTION = Regex("^\\*[^*]+\\*$")

/** Prefisso per parlare fuori dal gioco con un personaggio (debug, correzioni). */
private const val OOC = "OOC:"

private const val FEED_MAX = 500

class GameViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("megacity", Context.MODE_PRIVATE)
    private val feedFile = File(app.filesDir, "partita_feed.json")

    var params by mutableStateOf(Params.fromJson(prefs.getString("params", null)))
        private set
    var npcs by mutableStateOf(npcsFromJson(prefs.getString("npcs", null)))
        private set
    var playerLocation by mutableStateOf(prefs.getString("location", null) ?: LOC_MEGACITY)
        private set
    /** Personaggio a cui sono indirizzati i messaggi del giocatore. */
    var target by mutableStateOf(prefs.getString("target", null) ?: NARRATOR_ID)
        private set

    var stats by mutableStateOf(Stats())
        private set
    var busy by mutableStateOf(false)
        private set
    /** Id del personaggio che sta rispondendo (per l'indicatore "sta scrivendo"). */
    var typing by mutableStateOf<String?>(null)
        private set
    var backfilling by mutableStateOf(false)
        private set

    /** Incrementato a ogni modifica delle memorie per forzare la ricomposizione della UI. */
    var memoryVersion by mutableIntStateOf(0)
        private set

    val feed = mutableStateListOf<FeedMsg>()

    /** Una memoria indipendente per ogni personaggio (file separati). */
    val memories: Map<String, MemoryStore> = DEFAULT_NPCS.associate { it.id to MemoryStore(app, it.id) }

    init {
        loadFeed()
        if (feed.isEmpty()) addIntro()
    }

    fun npc(id: String): Npc = npcs.firstOrNull { it.id == id } ?: DEFAULT_NPCS.first()

    /** Luoghi raggiungibili: le strade più i luoghi in cui si trovano i personaggi. */
    val locations: List<String>
        get() = (listOf(LOC_STRADE, LOC_MEGACITY) + npcs.map { it.location })
            .filter { it.isNotBlank() }.distinct()

    fun isPresent(n: Npc) = n.isNarrator || n.location == playerLocation

    // ---------- Energia di Adrian ----------

    /** Token totali registrati all'ultimo riposo: l'energia si conta da qui. */
    private var energyBase by mutableStateOf(0L)

    /** Adrian riposa incapsulato e invisibile (resta salvato anche chiudendo l'app). */
    var invisible by mutableStateOf(prefs.getBoolean("invisible", false))
        private set

    /** Token usati dall'apertura dell'app, da una nuova partita o dall'ultimo riposo. */
    val sessionTokensUsed: Long get() = (stats.totalTokens - energyBase).coerceAtLeast(0)

    /** Token ancora disponibili nella sessione. */
    val tokensLeft: Long get() = (params.sessionTokenBudget - sessionTokensUsed).coerceAtLeast(0)

    /** Energia da 0 a 1: scende man mano che la sessione consuma token. */
    val energy: Float
        get() = if (params.sessionTokenBudget <= 0) 1f
        else (tokensLeft.toFloat() / params.sessionTokenBudget).coerceIn(0f, 1f)

    /** Riposo nella capsula: chat azzerata, energia piena, Adrian invisibile; il narratore descrive la scena. */
    fun rest() {
        if (busy) return
        energyBase = stats.totalTokens
        setInvisible(true)
        // Come il vecchio comando "dormi": la chat si azzera (e con lei la cronologia inviata ai personaggi),
        // i ricordi scaduti vengono rimossi, la memoria persistente di ogni personaggio resta intatta.
        feed.clear()
        val purged = memories.values.sumOf { it.purgeExpired() }
        memories.values.forEach { it.log("sleep", "riposo nella capsula") }
        memoryVersion++
        addFeed(
            FeedMsg(
                "system", "", "",
                "🌙 Riposo: chat azzerata" + (if (purged > 0) ", $purged ricordi scaduti rimossi" else "") +
                    ". Le memorie dei personaggi restano intatte.",
                playerLocation
            )
        )
        val t = "*Esausto, Adrian si ritira a riposare: il suo corpo viene sigillato in una capsula di incapsulamento " +
            "che lo rigenera e lo rende invisibile.*"
        addFeed(FeedMsg("player", PLAYER_ID, NARRATOR_ID, t, playerLocation))
        viewModelScope.launch { runTurn(npc(NARRATOR_ID), t) }
    }

    /** Uscita dalla capsula: Adrian torna visibile. */
    fun leaveCapsule() {
        if (busy || !invisible) return
        setInvisible(false)
        val t = "*La capsula si apre con un sibilo: Adrian ne esce ed è di nuovo visibile.*"
        addFeed(FeedMsg("player", PLAYER_ID, NARRATOR_ID, t, playerLocation))
        viewModelScope.launch { runTurn(npc(NARRATOR_ID), t) }
    }

    private fun setInvisible(v: Boolean) {
        invisible = v
        prefs.edit().putBoolean("invisible", v).apply()
    }

    // ---------- Parametri e schede ----------

    fun updateParams(change: (Params) -> Params) {
        params = change(params)
        prefs.edit().putString("params", params.toJson()).apply()
    }

    fun resetParams() {
        // Conserva chiavi, modelli, ambientazione e scheda del giocatore.
        updateParams {
            Params(
                apiKey = it.apiKey, model = it.model, provider = it.provider,
                groqKey = it.groqKey, groqModel = it.groqModel,
                world = it.world, playerSheet = it.playerSheet
            )
        }
    }

    fun updateNpc(id: String, change: (Npc) -> Npc) {
        npcs = npcs.map { if (it.id == id) change(it) else it }
        prefs.edit().putString("npcs", npcsToJson(npcs)).apply()
    }

    fun resetNpc(id: String) {
        val d = defaultNpc(id) ?: return
        updateNpc(id) { d }
    }

    fun selectTarget(id: String) {
        target = id
        prefs.edit().putString("target", id).apply()
    }

    // ---------- Gioco ----------

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        var who = npc(target)
        if (PURE_ACTION.matches(t)) who = npc(NARRATOR_ID)
        if (!isPresent(who)) {
            addFeed(FeedMsg("system", "", "", "${who.name} non è qui: si trova in ${who.location}.", playerLocation))
            return
        }
        val ooc = t.startsWith(OOC, ignoreCase = true)
        addFeed(FeedMsg("player", PLAYER_ID, who.id, t, playerLocation, ooc = ooc))
        viewModelScope.launch { runTurn(who, t) }
    }

    /** Spostamento: aggiorna il luogo e chiede al narratore di descrivere l'arrivo. */
    fun moveTo(location: String) {
        if (busy || location == playerLocation) return
        playerLocation = location
        prefs.edit().putString("location", location).apply()
        if (!isPresent(npc(target))) selectTarget(NARRATOR_ID)
        val t = "*Adrian si dirige verso: $location*"
        addFeed(FeedMsg("player", PLAYER_ID, NARRATOR_ID, t, location))
        viewModelScope.launch { runTurn(npc(NARRATOR_ID), t) }
    }

    private suspend fun runTurn(who: Npc, playerText: String) {
        busy = true
        typing = who.id
        val p = params
        val store = memories.getValue(who.id)
        store.log("turn_start", playerText.take(200))

        val ooc = playerText.startsWith(OOC, ignoreCase = true)
        val recalled = if (ooc) emptyList() else recallFor(p, store, playerText)
        val system = if (who.isNarrator) narratorPrompt(p, who, recalled) else npcPrompt(p, who, recalled)
        // Il messaggio OOC non è nella cronologia filtrata: lo si aggiunge solo per questa chiamata.
        val history = if (who.isNarrator) narratorHistory(p) else npcHistory(p, who)
        val contents = if (ooc) alternate(history + ("user" to playerText)) else history

        Llm.generate(p, who.temperature, system, contents)
            .onSuccess { o ->
                val r = o.result
                noteProvider(o)
                registerCall(r)
                val meta = if (p.showDiagnostics) "${Llm.label(o.provider)} · ${o.model} · ${r.latencyMs} ms" else ""
                addFeed(FeedMsg("npc", who.id, PLAYER_ID, r.text, playerLocation, meta = meta, ooc = ooc))
                if (!ooc) {
                    val content = if (who.isNarrator) {
                        "[$playerLocation] Adrian: ${playerText.take(300)} | Esito: ${r.text.take(500)}"
                    } else {
                        "[$playerLocation] Adrian: ${playerText.take(300)} | ${who.name}: ${r.text.take(500)}"
                    }
                    val saved = store.add(
                        Layer.EPISODIC, "dialogo", content,
                        source = who.id, ttlDays = p.memoryTtlDays
                    )
                    viewModelScope.launch { embedEntry(store, saved) }
                }
            }
            .onFailure { e ->
                stats = stats.copy(requests = stats.requests + 1, errors = stats.errors + 1)
                addFeed(FeedMsg("error", who.id, "", e.message ?: "Errore sconosciuto", playerLocation))
                store.log("error", e.message.orEmpty().take(200))
            }

        memoryVersion++
        typing = null
        busy = false
    }

    // ---------- Prompt ----------

    private fun recalledBlock(title: String, recalled: List<MemEntry>) = buildString {
        if (recalled.isNotEmpty()) {
            append("\n\n$title (usali solo se pertinenti):\n")
            recalled.forEach { append("- ${it.content}\n") }
        }
    }

    private fun lastSceneHere(): String? =
        feed.lastOrNull { it.role == "npc" && !it.ooc && it.speakerId == NARRATOR_ID && it.location == playerLocation }
            ?.text?.take(700)

    private fun npcPrompt(p: Params, n: Npc, recalled: List<MemEntry>): String = buildString {
        append(p.world)
        append("\n\nRUOLO: interpreti esclusivamente ${n.name} (${n.role}). ")
        append("Sei questo personaggio dentro la storia, non un assistente.\n")
        append(n.persona)
        append("\n\nIL GIOCATORE, con cui stai parlando: ${p.playerSheet}")
        if (invisible) {
            append(
                "\nSTATO DI ADRIAN: è incapsulato e invisibile. Non puoi vederlo né sapere dove si trova: " +
                    "se parla senti solo una voce senza corpo. Reagisci come farebbe ${n.name} davanti a una voce invisibile."
            )
        }
        append("\n\nLUOGO ATTUALE: ${n.location}")
        lastSceneHere()?.let { append("\nULTIMA DESCRIZIONE DELLA SCENA: $it") }
        append(recalledBlock("RICORDI DI ${n.name.uppercase()} (solo tuoi: gli altri personaggi non li conoscono)", recalled))
        append(
            "\n\nREGOLE DI RECITAZIONE:\n" +
                "- Rispondi in italiano, in prima persona, solo con battute e gesti di ${n.name}.\n" +
                "- Gesti e azioni brevi tra asterischi, per esempio *si sistema il guanto*.\n" +
                "- Non descrivere mai azioni, pensieri o parole di Adrian e non parlare al posto di altri personaggi.\n" +
                "- Sai solo ciò che ${n.name} ha visto, sentito o ricorda. Se Adrian cita fatti che non conosci, " +
                "reagisci come farebbe il personaggio: non inventare di saperli.\n" +
                "- Al massimo ${n.maxSentences} frasi per risposta, salvo che Adrian chieda un racconto.\n" +
                "- Resta sempre nel personaggio. Unica eccezione: se il messaggio inizia con \"OOC:\", " +
                "rispondi brevemente fuori dal gioco, come autore del personaggio."
        )
    }

    private fun narratorPrompt(p: Params, n: Npc, recalled: List<MemEntry>): String = buildString {
        append(p.world)
        append("\n\nRUOLO: sei il Narratore e il master di gioco di \"Megacity 2436\", un gioco di ruolo testuale. ")
        append(n.persona)
        append("\n\nIL GIOCATORE: ${p.playerSheet}")
        if (invisible) {
            append(
                "\nSTATO DI ADRIAN: riposa incapsulato in una capsula rigenerante che lo rende invisibile. " +
                    "Nessuno può vederlo, ma lui può muoversi, osservare e parlare. Tienine conto nelle reazioni di comparse e personaggi."
            )
        }
        append("\n\nLUOGO ATTUALE DI ADRIAN: $playerLocation")
        append("\n\nPERSONAGGI PRINCIPALI E DOVE SI TROVANO:\n")
        npcs.filter { !it.isNarrator }.forEach { append("- ${it.name}, ${it.role}: ${it.location}\n") }
        append("\nLUOGHI NOTI: ${locations.joinToString("; ")}")
        append(recalledBlock("RICORDI DELLA STORIA", recalled))
        append(
            "\n\nNella cronologia, le righe che iniziano con [Adrian → Nome] o [Nome] sono dialoghi diretti tra Adrian " +
                "e i personaggi principali: usale per la continuità, ma non ripeterle e non riassumerle.\n" +
                "Rispondi in italiano, al massimo ${n.maxSentences} frasi. Se il messaggio inizia con \"OOC:\", " +
                "rispondi brevemente fuori dal gioco."
        )
    }

    // ---------- Cronologie (ogni personaggio vede solo ciò che lo riguarda) ----------

    /** Il personaggio vede solo i propri scambi con Adrian. */
    private fun npcHistory(p: Params, n: Npc): List<Pair<String, String>> {
        val raw = feed.filter {
            !it.ooc && ((it.role == "player" && it.targetId == n.id) || (it.role == "npc" && it.speakerId == n.id))
        }.takeLast(p.historyMessages).map { (if (it.role == "player") "user" else "model") to it.text }
        return alternate(raw)
    }

    /** Il narratore vede tutta la storia recente, con i dialoghi dei PNG etichettati. */
    private fun narratorHistory(p: Params): List<Pair<String, String>> {
        val raw = feed.filter { !it.ooc && (it.role == "player" || it.role == "npc") }
            .takeLast(p.historyMessages)
            .map { m ->
                when {
                    m.role == "player" && m.targetId == NARRATOR_ID -> "user" to m.text
                    m.role == "player" -> "user" to "[Adrian → ${npc(m.targetId).name}] ${m.text}"
                    m.speakerId == NARRATOR_ID -> "model" to m.text
                    else -> "user" to "[${npc(m.speakerId).name}] ${m.text}"
                }
            }
        return alternate(raw)
    }

    /** Unisce i messaggi consecutivi con lo stesso ruolo e fa iniziare la cronologia con "user". */
    private fun alternate(raw: List<Pair<String, String>>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        raw.forEach { (role, text) ->
            if (out.isNotEmpty() && out.last().first == role) {
                out[out.lastIndex] = role to (out.last().second + "\n" + text)
            } else {
                out += role to text
            }
        }
        while (out.isNotEmpty() && out.first().first != "user") out.removeAt(0)
        return out
    }

    // ---------- Statistiche ----------

    private fun noteProvider(o: LlmOutcome) {
        stats = stats.copy(
            lastProvider = "${Llm.label(o.provider)} · ${o.model}",
            groqCalls = stats.groqCalls + if (o.provider == "groq") 1 else 0,
            failovers = stats.failovers + if (o.failedOverFrom != null) 1 else 0
        )
        if (o.failedOverFrom != null) {
            addFeed(
                FeedMsg(
                    "system", "", "",
                    "↪️ ${o.failedOverFrom}. Risposta da ${Llm.label(o.provider)} (${o.model}).",
                    playerLocation
                )
            )
        }
    }

    private fun registerCall(r: GeminiResult) {
        stats = stats.copy(
            requests = stats.requests + 1,
            promptTokens = stats.promptTokens + r.promptTokens,
            outputTokens = stats.outputTokens + r.outputTokens,
            latenciesMs = (stats.latenciesMs + r.latencyMs).takeLast(60)
        )
    }

    // ---------- Feed e partita ----------

    private fun addFeed(m: FeedMsg) {
        feed += m
        while (feed.size > FEED_MAX) feed.removeAt(0)
        saveFeed()
    }

    private fun saveFeed() {
        try {
            val arr = JSONArray()
            feed.forEach { arr.put(it.toJson()) }
            val tmp = File(feedFile.path + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(feedFile)) { feedFile.writeText(arr.toString()); tmp.delete() }
        } catch (e: Exception) {
            // Salvataggio non riuscito: la partita continua in memoria.
        }
    }

    private fun loadFeed() {
        try {
            if (!feedFile.exists()) return
            val arr = JSONArray(feedFile.readText())
            for (i in 0 until arr.length()) feed += FeedMsg.fromJson(arr.getJSONObject(i))
        } catch (e: Exception) {
            feed.clear()
        }
    }

    private fun addIntro() {
        addFeed(
            FeedMsg(
                "npc", NARRATOR_ID, PLAYER_ID,
                "Anno 2436. La pioggia filtrata scivola sulle insegne del Settore 4. " +
                    "Spingi la porta del Locale Megacity: bassi profondi, fumo aromatico, sguardi che ti pesano addosso. " +
                    "Dietro una vetrata rialzata, un uomo in abito antracite ti osserva con un guanto sulla mano destra. " +
                    "In un angolo, un distributore di materiale informatico ronza e lampeggia in verde.",
                LOC_MEGACITY
            )
        )
    }

    /** Nuova partita: cancella il feed e le memorie di tutti i personaggi. Le schede restano. */
    fun newGame() {
        if (busy) return
        feed.clear()
        memories.values.forEach { it.clearAll() }
        playerLocation = LOC_MEGACITY
        prefs.edit().putString("location", LOC_MEGACITY).apply()
        selectTarget(NARRATOR_ID)
        stats = Stats()
        energyBase = 0
        setInvisible(false)
        addIntro()
        memoryVersion++
    }

    fun clearFeedOnly() {
        if (busy) return
        feed.clear()
        saveFeed()
    }

    // ---------- Memoria (azioni dell'utente) ----------

    fun addFact(ownerId: String, text: String) {
        if (text.isBlank()) return
        val store = memories[ownerId] ?: return
        val saved = store.add(
            Layer.SEMANTIC, "fatto", text.trim(), source = "autore", ttlDays = 0,
            confidence = 1.0, validation = "verified", criticality = "high"
        )
        memoryVersion++
        viewModelScope.launch { embedEntry(store, saved) }
    }

    fun makePermanent(ownerId: String, id: String) {
        memories[ownerId]?.makePermanent(id)
        memoryVersion++
    }

    fun deleteMemory(ownerId: String, id: String) {
        memories[ownerId]?.delete(id)
        memoryVersion++
    }

    // ---------- Memoria semantica (embedding + coseno) ----------

    /** Richiamo ibrido: coseno sui ricordi con vettore; parole in comune per quelli senza vettore. */
    private suspend fun recallFor(p: Params, store: MemoryStore, query: String): List<MemEntry> {
        if (p.memoryTopK <= 0) return emptyList()
        var result = emptyList<MemEntry>()
        var embeddingOk = false
        if (p.useEmbeddings && p.apiKey.isNotBlank() && store.vectors.isNotEmpty()) {
            GeminiClient.embed(p, query, true)
                .onSuccess { q ->
                    embeddingOk = true
                    val hits = store.recallSemantic(q, p.embeddingModel.trim(), p.memoryTopK, p.similarityThreshold)
                    stats = stats.copy(
                        embedCalls = stats.embedCalls + 1,
                        lastSimilarity = hits.firstOrNull()?.score ?: 0f,
                        lastRecallHits = hits.size
                    )
                    result = hits.map { it.entry }
                }
                .onFailure { e ->
                    stats = stats.copy(embedErrors = stats.embedErrors + 1)
                    store.log("embed_error", e.message.orEmpty().take(200))
                }
        }
        if (result.size < p.memoryTopK) {
            val extra = store.recall(query, p.memoryTopK).filter { e ->
                result.none { it.id == e.id } && (!embeddingOk || store.vectors[e.id] == null)
            }
            result = result + extra.take(p.memoryTopK - result.size)
        }
        // I fatti inseriti dall'autore valgono sempre: si aggiungono anche se non richiamati.
        val facts = store.entries.filter { it.layer == Layer.SEMANTIC && it.sourceAgent == "autore" }
            .filter { f -> result.none { it.id == f.id } }
            .takeLast(10)
        return facts + result
    }

    private suspend fun embedEntry(store: MemoryStore, e: MemEntry) {
        val p = params
        if (!p.useEmbeddings || p.apiKey.isBlank()) return
        GeminiClient.embed(p, e.content, false)
            .onSuccess { v ->
                store.setVector(e.id, p.embeddingModel.trim(), v)
                stats = stats.copy(embedCalls = stats.embedCalls + 1)
                memoryVersion++
            }
            .onFailure { err ->
                stats = stats.copy(embedErrors = stats.embedErrors + 1)
                store.log("embed_error", err.message.orEmpty().take(200))
            }
    }

    fun missingVectors(): Int =
        memories.values.sumOf { it.missingVector(params.embeddingModel.trim(), params.embeddingDim).size }

    /** Calcola i vettori mancanti o incompatibili per tutti i personaggi. */
    fun backfillEmbeddings() {
        val p = params
        if (backfilling || !p.useEmbeddings || p.apiKey.isBlank()) return
        val model = p.embeddingModel.trim()
        val todo = memories.values.flatMap { s -> s.missingVector(model, p.embeddingDim).map { s to it } }
        if (todo.isEmpty()) return
        viewModelScope.launch {
            backfilling = true
            todo.forEach { (s, e) ->
                embedEntry(s, e)
                delay(300)
            }
            backfilling = false
            val done = todo.count { (s, e) -> s.vectors[e.id]?.model == model }
            addFeed(FeedMsg("system", "", "", "🧭 Embedding calcolati per $done di ${todo.size} ricordi.", playerLocation))
        }
    }
}
