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

    /** Sintesi vocale per leggere le risposte. */
    val speaker = Speaker(app)

    // ---------- Crediti, inventario, catalogo di NODO-7 (gestiti dal gioco, non dall'AI) ----------

    var credits by mutableIntStateOf(prefs.getInt("credits", START_CREDITS))
        private set
    var inventory by mutableStateOf(
        if (prefs.contains("inventory")) itemsFromJson(prefs.getString("inventory", null)) else START_INVENTORY
    )
        private set
    /** I 5 oggetti in vendita ora da NODO-7. */
    var catalog by mutableStateOf(itemsFromJson(prefs.getString("catalog", null)))
        private set
    /** Le 3 missioni offerte ora da Mr Nice. */
    var board by mutableStateOf(missionsFromJson(prefs.getString("board", null)))
        private set
    /** Vero dopo l'arrivo al Laboratorio Helix: Mr Nice proporrà nuove missioni alla prossima interazione. */
    var boardStale by mutableStateOf(prefs.getBoolean("boardStale", true))
        private set
    /** La missione accettata (al massimo una): attiva, oppure completata e da riscuotere. */
    var mission by mutableStateOf(missionFromJson(prefs.getString("mission", null)))
        private set

    /** Vero dopo l'arrivo al Locale Megacity: NODO-7 genererà un nuovo catalogo alla prossima interazione. */
    var catalogStale by mutableStateOf(prefs.getBoolean("catalogStale", true))
        private set

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
        speaker.stop()
        energyBase = stats.totalTokens
        applyInvisible(true)
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
        applyInvisible(false)
        val t = "*La capsula si apre con un sibilo: Adrian ne esce ed è di nuovo visibile.*"
        addFeed(FeedMsg("player", PLAYER_ID, NARRATOR_ID, t, playerLocation))
        viewModelScope.launch { runTurn(npc(NARRATOR_ID), t) }
    }

    private fun applyInvisible(v: Boolean) {
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

    // ---------- Lettura ad alta voce ----------

    fun speakMsg(m: FeedMsg, interrupt: Boolean = false) {
        speaker.speak(speaker.split(m.text, npc(m.speakerId), npc(NARRATOR_ID)), interrupt)
    }

    fun stopSpeaking() = speaker.stop()

    /** Prova la voce di un personaggio dalla scheda Personaggi. */
    fun testVoice(n: Npc) {
        speaker.speak(listOf(SpeechPart("Sono ${n.name}. Benvenuto nel Settore 4.", n)), interrupt = true)
    }

    override fun onCleared() {
        speaker.shutdown()
        super.onCleared()
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        speaker.stop()
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
        speaker.stop()
        playerLocation = location
        prefs.edit().putString("location", location).apply()
        if (location == npc(DISTRIBUTOR_ID).location) markCatalogStale()
        if (location == npc(MRNICE_ID).location) {
            boardStale = true
            saveInventoryState()
        }
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
                val (shown, fx) = parseEffects(r.text)
                addFeed(FeedMsg("npc", who.id, PLAYER_ID, shown.ifBlank { "…" }, playerLocation, meta = meta, ooc = ooc))
                val fxLog = if (ooc) "" else applyEffects(who, fx)
                if (!ooc) {
                    // Il ricordo si salva in background: riassunto con il modello leggero, o testo originale se non riesce.
                    val loc = playerLocation
                    val epoch = gameEpoch
                    val remembered = if (fxLog.isEmpty()) shown else "$shown\n[Effetti: $fxLog]"
                    viewModelScope.launch { saveMemory(p, store, who, loc, playerText, remembered, epoch) }
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

    // ---------- Ricordi riassunti ----------

    /** Cambia a ogni nuova partita: un salvataggio in background iniziato prima viene scartato. */
    private var gameEpoch = 0

    private suspend fun saveMemory(
        p: Params, store: MemoryStore, who: Npc, loc: String,
        playerText: String, reply: String, epoch: Int
    ) {
        val original = "[$loc] Adrian: ${playerText.take(3000)}\n" +
            (if (who.isNarrator) "Esito: " else "${who.name}: ") + reply.take(3000)
        val summary = if (p.summarizeMemories && p.groqKey.isNotBlank()) summarize(p, who, original) else null
        if (epoch != gameEpoch) return
        val saved = if (summary != null) {
            store.add(
                Layer.EPISODIC, "riassunto", "[$loc] $summary",
                source = who.id, ttlDays = p.memoryTtlDays, fullText = original
            )
        } else {
            store.add(Layer.EPISODIC, "dialogo", original, source = who.id, ttlDays = p.memoryTtlDays)
        }
        memoryVersion++
        embedEntry(store, saved)
    }

    /** Riassunto dello scambio con il modello leggero di Groq. I token contano per l'energia di Adrian. */
    private suspend fun summarize(p: Params, who: Npc, original: String): String? {
        val system = if (who.isNarrator) {
            "Sei il modulo di memoria del Narratore di un gioco di ruolo cyberpunk ambientato nel 2436. " +
                "Riassumi lo scambio in 1-3 frasi in italiano, in terza persona, come cronaca della storia. "
        } else {
            "Sei il modulo di memoria di ${who.name} (${who.role}) in un gioco di ruolo cyberpunk ambientato nel 2436. " +
                "Riassumi lo scambio in 1-3 frasi in italiano, in prima persona dal punto di vista di ${who.name}. "
        } + "Conserva: nomi, luoghi, promesse, accordi, prezzi e crediti, oggetti dati o ricevuti, " +
            "informazioni rivelate o tenute nascoste, cambiamenti di atteggiamento verso Adrian. " +
            "Niente premesse né commenti: scrivi solo il riassunto."
        return try {
            val r = GroqClient.generate(
                p.copy(groqModel = p.summaryModel.trim(), maxOutputTokens = 1024),
                0.2f, system, listOf("user" to original), reasoningEffort = "low"
            )
            registerCall(r)
            stats = stats.copy(groqCalls = stats.groqCalls + 1)
            r.text.trim().takeIf { it.isNotEmpty() }?.take(1200)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            stats = stats.copy(requests = stats.requests + 1, errors = stats.errors + 1)
            store(who).log("summary_error", e.message.orEmpty().take(200))
            null
        }
    }

    private fun store(n: Npc): MemoryStore = memories.getValue(n.id)

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
        append("\nCIÒ CHE ADRIAN PORTA CON SÉ (lo conosci solo se è visibile o se te lo mostra): ")
        append(if (inventory.isEmpty()) "niente." else inventory.joinToString("; ") { it.name })
        if (n.id == DISTRIBUTOR_ID) append(catalogInstructions())
        if (n.id == MRNICE_ID) append(boardInstructions())
        else missionNote(forNarrator = false)?.let { append(it) }
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
        append("\n\n").append(EFFECTS_RULES)
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
        append("\n\nCREDITI DI ADRIAN: $credits")
        append("\nINVENTARIO DI ADRIAN:\n")
        if (inventory.isEmpty()) append("- vuoto\n") else inventory.forEach { append("- ${it.promptLine()}\n") }
        append("Adrian può usare solo oggetti che possiede e con cariche disponibili.")
        missionNote(forNarrator = true)?.let { append(it) }
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
        append("\n\n").append(EFFECTS_RULES)
    }

    // ---------- Catalogo, acquisti, effetti, uso degli oggetti ----------

    private fun catalogInstructions(): String = buildString {
        append("\n\nCREDITI DI ADRIAN: $credits")
        if (catalogStale || catalog.isEmpty()) {
            append(
                "\nNUOVO CATALOGO: il tuo assortimento si è appena rinnovato. Annuncialo in una frase (i dettagli li mostra il terminale) " +
                    "e IN FONDO alla risposta aggiungi esattamente 5 oggetti nuovi, calibrati sui crediti di Adrian: " +
                    "3 che può permettersi, 1 un po' sopra le sue possibilità, 1 raro o illegale e costoso. " +
                    "Materiale informatico, hacking, impianti, armi leggere e gadget cyberpunk del 2436. Formato:\n" +
                    "<catalogo>[{\"nome\":\"...\",\"tipo\":\"arma|tool|consumabile|altro\",\"descrizione\":\"max 20 parole\"," +
                    "\"prezzo\":150,\"cariche\":3}, ...]</catalogo>\n(cariche -1 = illimitato; prezzo intero in crediti)"
            )
        } else {
            append("\nCATALOGO ATTUALE (vendi solo questi, il catalogo non cambia finché Adrian non torna nel locale):\n")
            catalog.forEach { append("- ${it.promptLine(withPrice = true)}\n") }
            append("Per i tuoi acquisti non usare i tag di effetto: le vendite passano dal pulsante COMPRA.")
        }
    }

    private fun boardInstructions(): String = buildString {
        val m = mission
        append("\n\nCREDITI DI ADRIAN: $credits")
        when {
            m != null && m.status == MISSION_DONE -> append(
                "\nMISSIONE DI ADRIAN: \"${m.title}\" risulta COMPLETATA. La ricompensa di ${m.reward} crediti " +
                    "si incassa con il pulsante RISCUOTI: se Adrian chiede di essere pagato, invitalo a confermare lì. " +
                    "Non usare il tag <crediti> per pagarlo."
            )
            m != null -> append(
                "\nMISSIONE IN CORSO DI ADRIAN (affidata da te): ${m.promptLine()} " +
                    "Finché non la conclude o non la abbandona non può accettarne altre. Non pagarlo prima del completamento."
            )
        }
        if (boardStale || board.isEmpty()) {
            append(
                "\nNUOVI LAVORI: hai appena ricevuto nuovi incarichi. Accennali in una frase (i dettagli li mostra la bacheca) " +
                    "e IN FONDO alla risposta aggiungi esattamente 3 missioni: una facile (200-400 crediti), " +
                    "una media (500-900), una difficile (1000-2000). Obiettivi concreti e verificabili nel gioco " +
                    "(recuperare o consegnare un oggetto, hackerare un sistema, scoprire un'informazione, seguire qualcuno), " +
                    "ambientati nel Settore 4: ${locations.joinToString("; ")} o luoghi nuovi vicini. " +
                    "Possono coinvolgere Varga, Vanessa o NODO-7, senza contraddire ciò che sai di loro. Formato:\n" +
                    "<missioni>[{\"titolo\":\"...\",\"obiettivo\":\"max 30 parole\",\"luogo\":\"...\"," +
                    "\"difficolta\":\"facile|media|difficile\",\"ricompensa\":300}, ...]</missioni>"
            )
        } else {
            append("\nLAVORI IN BACHECA (offri solo questi finché Adrian non torna nel laboratorio):\n")
            board.forEach { append("- ${it.promptLine()}\n") }
            append("Le missioni si accettano con il pulsante ACCETTA della bacheca.")
        }
    }

    /** Missione attiva vista da narratore e personaggi (Mr Nice ha istruzioni sue). */
    private fun missionNote(forNarrator: Boolean): String? {
        val m = mission ?: return null
        if (m.status == MISSION_DONE) {
            return if (forNarrator) "\nMISSIONE DI ADRIAN: \"${m.title}\" completata; deve tornare da Mr Nice a riscuotere." else null
        }
        return if (forNarrator) {
            "\n\nMISSIONE ATTIVA DI ADRIAN (affidata da Mr Nice): ${m.promptLine()}\n" +
                "Quando l'obiettivo è davvero raggiunto aggiungi IN FONDO alla risposta <missione>completata</missione>; " +
                "se diventa impossibile <missione>fallita</missione>. Non segnarla completata per tentativi a metà. " +
                "La ricompensa la paga Mr Nice, non tu: non usare <crediti> per la missione."
        } else {
            "\n\n(Informazione di gioco: non citarla se il tuo personaggio non può saperla) Adrian ha questa missione: " +
                "${m.promptLine()} Se l'obiettivo si compie davvero nel dialogo con te, aggiungi IN FONDO alla risposta " +
                "<missione>completata</missione>."
        }
    }

    private fun markCatalogStale() {
        catalogStale = true
        prefs.edit().putBoolean("catalogStale", true).apply()
    }

    private fun saveInventoryState() {
        prefs.edit()
            .putInt("credits", credits)
            .putString("inventory", itemsToJson(inventory))
            .putString("catalog", itemsToJson(catalog))
            .putBoolean("catalogStale", catalogStale)
            .putString("board", missionsToJson(board))
            .putBoolean("boardStale", boardStale)
            .putString("mission", mission?.toJson()?.toString())
            .apply()
    }

    private fun findItem(name: String): Item? {
        val n = name.trim().lowercase()
        return inventory.firstOrNull { it.name.lowercase() == n }
            ?: inventory.firstOrNull { it.name.lowercase().contains(n) || n.contains(it.name.lowercase()) }
    }

    /** Applica crediti, oggetti e catalogo dalla risposta; restituisce un riepilogo per la memoria. */
    private fun applyEffects(who: Npc, fx: Effects): String {
        if (fx.isEmpty) return ""
        val log = mutableListOf<String>()
        if (fx.catalog != null && who.id == DISTRIBUTOR_ID) {
            catalog = fx.catalog
            catalogStale = false
            addFeed(FeedMsg("system", "", "", "🖥️ Nuovo catalogo di ${who.name}: ${fx.catalog.size} oggetti in vendita.", playerLocation))
        }
        if (fx.board != null && who.id == MRNICE_ID) {
            board = fx.board
            boardStale = false
            addFeed(FeedMsg("system", "", "", "📋 Nuovi lavori di ${who.name}: ${fx.board.size} missioni in bacheca.", playerLocation))
        }
        val m = mission
        if (fx.missionOutcome != null && m != null && m.status == MISSION_ACTIVE) {
            if (fx.missionOutcome == MISSION_DONE) {
                mission = m.copy(status = MISSION_DONE)
                log += "missione \"${m.title}\" completata"
                addFeed(
                    FeedMsg(
                        "system", "", "",
                        "✅ Obiettivo raggiunto: \"${m.title}\". Torna da ${npc(MRNICE_ID).name} " +
                            "(${npc(MRNICE_ID).location}) a riscuotere ${m.reward} ¢.",
                        playerLocation
                    )
                )
            } else {
                mission = null
                log += "missione \"${m.title}\" fallita"
                addFeed(FeedMsg("system", "", "", "❌ Missione fallita: \"${m.title}\". Nessuna ricompensa.", playerLocation))
                rememberForMrNice("Adrian ha fallito la missione \"${m.title}\".")
            }
        }
        if (fx.credits != 0) {
            val before = credits
            credits = (credits + fx.credits).coerceAtLeast(0)
            val delta = credits - before
            if (delta != 0) {
                val sign = if (delta > 0) "+" else ""
                log += "$sign$delta crediti"
                addFeed(FeedMsg("system", "", "", "💳 $sign$delta ¢ (${who.name}) · saldo $credits ¢", playerLocation))
            }
        }
        fx.lost.forEach { name ->
            val found = findItem(name)
            if (found != null) {
                inventory = inventory - found
                log += "perso ${found.name}"
                addFeed(FeedMsg("system", "", "", "🎒 Perso: ${found.name} (${who.name})", playerLocation))
            }
        }
        fx.gained.forEach { item ->
            inventory = inventory + item
            log += "ottenuto ${item.name}"
            addFeed(FeedMsg("system", "", "", "🎒 Ottenuto: ${item.emoji} ${item.name} (${who.name})", playerLocation))
        }
        saveInventoryState()
        return log.joinToString("; ")
    }

    private fun rememberForMrNice(text: String) {
        val store = memories.getValue(MRNICE_ID)
        val saved = store.add(Layer.EPISODIC, "missione", "[${npc(MRNICE_ID).location}] $text", source = MRNICE_ID, ttlDays = params.memoryTtlDays)
        memoryVersion++
        viewModelScope.launch { embedEntry(store, saved) }
    }

    /** Accetta una missione della bacheca (una sola alla volta). */
    fun acceptMission(m: Mission) {
        if (busy || mission != null) return
        val nice = npc(MRNICE_ID)
        if (!isPresent(nice)) return
        speaker.stop()
        mission = m.copy(status = MISSION_ACTIVE)
        board = board.filter { it.id != m.id }
        saveInventoryState()
        addFeed(FeedMsg("player", PLAYER_ID, MRNICE_ID, "*Adrian accetta il lavoro: \"${m.title}\".*", playerLocation))
        addFeed(
            FeedMsg(
                "npc", MRNICE_ID, PLAYER_ID,
                "*sorride senza mostrare gli occhi* Affare fatto, amico. ${m.objective} Luogo: ${m.location}. " +
                    "Torna da me a lavoro finito e avrai i tuoi ${m.reward} crediti.",
                playerLocation
            )
        )
        addFeed(FeedMsg("system", "", "", "📋 Missione accettata: ${m.difficultyEmoji} ${m.title} · ricompensa ${m.reward} ¢", playerLocation))
        rememberForMrNice("Ho affidato ad Adrian la missione ${m.promptLine()}")
    }

    /** Riscuote la ricompensa di una missione completata: solo da Mr Nice. */
    fun collectReward() {
        val m = mission ?: return
        if (busy || m.status != MISSION_DONE) return
        val nice = npc(MRNICE_ID)
        if (!isPresent(nice)) return
        speaker.stop()
        credits += m.reward
        mission = null
        saveInventoryState()
        addFeed(FeedMsg("player", PLAYER_ID, MRNICE_ID, "*Adrian si presenta da ${nice.name} per riscuotere.*", playerLocation))
        addFeed(
            FeedMsg(
                "npc", MRNICE_ID, PLAYER_ID,
                "*fa scivolare un chip di credito sul tavolo* Lavoro pulito, amico. ${m.reward} crediti, come d'accordo. " +
                    "Quando vuoi altro, sai dove trovarmi.",
                playerLocation
            )
        )
        addFeed(FeedMsg("system", "", "", "💳 +${m.reward} ¢ (missione \"${m.title}\") · saldo $credits ¢", playerLocation))
        rememberForMrNice("Ho pagato ${m.reward} crediti ad Adrian per la missione \"${m.title}\", completata.")
    }

    /** Abbandona la missione attiva (nessuna ricompensa). */
    fun abandonMission() {
        val m = mission ?: return
        if (busy) return
        mission = null
        saveInventoryState()
        addFeed(FeedMsg("system", "", "", "📋 Missione abbandonata: \"${m.title}\".", playerLocation))
        rememberForMrNice("Adrian ha abbandonato la missione \"${m.title}\" senza portarla a termine.")
    }

    /** Acquisto dal catalogo di NODO-7: lo gestisce il gioco, senza chiamate al modello. */
    fun buy(item: Item) {
        if (busy) return
        val nodo = npc(DISTRIBUTOR_ID)
        if (!isPresent(nodo)) return
        if (credits < item.price) {
            addFeed(FeedMsg("system", "", "", "💳 Crediti insufficienti per ${item.name}: servono ${item.price} ¢, ne hai $credits.", playerLocation))
            return
        }
        credits -= item.price
        inventory = inventory + item.copy(id = java.util.UUID.randomUUID().toString().take(8))
        catalog = catalog.filter { it.id != item.id }
        saveInventoryState()
        addFeed(FeedMsg("player", PLAYER_ID, DISTRIBUTOR_ID, "*Adrian conferma l'acquisto di ${item.name} sul terminale.*", playerLocation))
        addFeed(
            FeedMsg(
                "npc", DISTRIBUTOR_ID, PLAYER_ID,
                "TRANSAZIONE COMPLETATA. ${item.name.uppercase()} EROGATO. -${item.price} CREDITI. SALDO: $credits. GRAZIE-GRAZIE PER L'ACQUISTO.",
                playerLocation
            )
        )
        addFeed(FeedMsg("system", "", "", "🎒 Ottenuto: ${item.emoji} ${item.name} · 💳 -${item.price} ¢ · saldo $credits ¢", playerLocation))
        val saved = memories.getValue(DISTRIBUTOR_ID).add(
            Layer.EPISODIC, "vendita", "[$playerLocation] Ho venduto ${item.name} ad Adrian per ${item.price} crediti.",
            source = DISTRIBUTOR_ID, ttlDays = params.memoryTtlDays
        )
        memoryVersion++
        viewModelScope.launch { embedEntry(memories.getValue(DISTRIBUTOR_ID), saved) }
    }

    /** Usa un oggetto: scala una carica e manda l'azione al narratore, che ne decide l'esito. */
    fun useItem(item: Item, targetId: String, detail: String) {
        if (busy || !item.usable) return
        speaker.stop()
        val current = inventory.firstOrNull { it.id == item.id } ?: return
        val left = if (current.charges > 0) current.charges - 1 else current.charges
        inventory = when {
            current.type == "consumabile" && left == 0 -> inventory.filter { it.id != current.id }
            else -> inventory.map { if (it.id == current.id) it.copy(charges = left) else it }
        }
        saveInventoryState()
        val on = if (targetId == NARRATOR_ID) "" else " su ${npc(targetId).name}"
        val extra = detail.trim().let { if (it.isEmpty()) "" else ": $it" }
        val chargesNote = when {
            current.charges < 0 -> ""
            left == 0 && current.type == "consumabile" -> " (ultimo uso, esaurito)"
            left == 0 -> " (cariche esaurite)"
            else -> " (restano $left cariche)"
        }
        val t = "*Adrian usa ${current.name}$on$extra*"
        addFeed(FeedMsg("player", PLAYER_ID, NARRATOR_ID, t, playerLocation))
        addFeed(FeedMsg("system", "", "", "🎒 Usato: ${current.emoji} ${current.name}$chargesNote", playerLocation))
        selectTarget(NARRATOR_ID)
        viewModelScope.launch { runTurn(npc(NARRATOR_ID), "$t$chargesNote") }
    }

    fun dropItem(item: Item) {
        if (busy) return
        inventory = inventory.filter { it.id != item.id }
        saveInventoryState()
        addFeed(FeedMsg("system", "", "", "🎒 Hai lasciato: ${item.name}", playerLocation))
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
        if (m.role == "npc" && params.ttsEnabled) speakMsg(m)
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
        speaker.stop()
        feed.clear()
        memories.values.forEach { it.clearAll() }
        playerLocation = LOC_MEGACITY
        prefs.edit().putString("location", LOC_MEGACITY).apply()
        selectTarget(NARRATOR_ID)
        stats = Stats()
        credits = START_CREDITS
        inventory = START_INVENTORY
        catalog = emptyList()
        catalogStale = true
        board = emptyList()
        boardStale = true
        mission = null
        saveInventoryState()
        gameEpoch++
        energyBase = 0
        applyInvisible(false)
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
