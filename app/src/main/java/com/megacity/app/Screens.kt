package com.megacity.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// =====================================================================
// GIOCO
// =====================================================================

@Composable
fun GameScreen(vm: GameViewModel, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val target = vm.npc(vm.target)

    LaunchedEffect(vm.feed.size, vm.typing) {
        val total = vm.feed.size + if (vm.typing != null) 1 else 0
        if (total > 0) listState.animateScrollToItem(total - 1)
    }

    Column(modifier.fillMaxSize().imePadding()) {

        // Luogo attuale e spostamento
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var open by remember { mutableStateOf(false) }
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { open = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                    Text("📍 ${vm.playerLocation}  ▾", maxLines = 1)
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    vm.locations.forEach { loc ->
                        val here = vm.npcs.filter { !it.isNarrator && it.location == loc }.joinToString(", ") { it.name }
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(if (loc == vm.playerLocation) "✓ $loc" else loc)
                                    if (here.isNotEmpty()) {
                                        Text("Qui: $here", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            },
                            onClick = { open = false; vm.moveTo(loc) }
                        )
                    }
                }
            }
        }

        EnergyBar(vm)

        if (vm.params.activeKey.isBlank()) {
            var quickKey by remember { mutableStateOf("") }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Inserisci la API key di ${Llm.label(vm.params.provider)} per iniziare (si modifica anche in Parametri).",
                    color = MaterialTheme.colorScheme.error
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = quickKey,
                        onValueChange = { quickKey = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text("API key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                    Button(
                        onClick = {
                            vm.updateParams {
                                if (it.provider == "groq") it.copy(groqKey = quickKey.trim())
                                else it.copy(apiKey = quickKey.trim())
                            }
                            quickKey = ""
                        },
                        enabled = quickKey.isNotBlank()
                    ) { Text("Salva") }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(vm.feed) { m -> FeedBubble(vm, m) }
            vm.typing?.let { id ->
                item {
                    Text(
                        "${vm.npc(id).emoji} ${vm.npc(id).name} sta scrivendo…",
                        style = MaterialTheme.typography.labelMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Scelta dell'interlocutore
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            vm.npcs.forEach { n ->
                val present = vm.isPresent(n)
                CharChip(
                    label = "${n.emoji} ${n.name}" + if (present) "" else " · altrove",
                    color = Color(n.color),
                    selected = vm.target == n.id,
                    enabled = present && !vm.busy
                ) { vm.selectTarget(n.id) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(if (target.isNarrator) "Cosa fai? (*azione*)" else "Parla a ${target.name}… (*azione* per agire)")
                },
                maxLines = 4
            )
            Button(
                onClick = { vm.send(input); input = "" },
                enabled = input.isNotBlank() && !vm.busy
            ) { Text(if (vm.busy) "…" else "Invia") }
        }
    }
}

/** Barra dell'energia di Adrian: piena a inizio sessione, si svuota con i token consumati. */
@Composable
private fun EnergyBar(vm: GameViewModel) {
    val e = vm.energy
    val color = when {
        e > 0.5f -> Color(0xFF00E676)
        e > 0.2f -> Color(0xFFFFC400)
        else -> Color(0xFFFF1744)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "⚡ Energia di Adrian ${"%.0f".format(e * 100)}%",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                "%,d / %,d token".format(vm.tokensLeft, vm.params.sessionTokenBudget.toLong()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { e },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.18f),
            strokeCap = StrokeCap.Round
        )
        if (e <= 0f) {
            Text(
                "Energia esaurita: Adrian ha bisogno di riposare. Puoi anche continuare a giocare così.",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                modifier = Modifier.padding(top = 2.dp)
            )
            Button(
                onClick = { vm.rest() },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            ) { Text("🛌 Riposa nella capsula (azzera la chat, energia piena, invisibile)") }
        }
        if (vm.invisible) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "👻 Incapsulato e invisibile",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFB388FF),
                    fontWeight = FontWeight.Bold
                )
                OutlinedButton(onClick = { vm.leaveCapsule() }, enabled = !vm.busy) { Text("Esci dalla capsula") }
            }
        }
    }
}

@Composable
private fun FeedBubble(vm: GameViewModel, m: FeedMsg) {
    val cs = MaterialTheme.colorScheme
    val speaker = vm.npc(m.speakerId)
    val (bg, label, align) = when (m.role) {
        "player" -> Triple(
            cs.primaryContainer,
            if (m.targetId == NARRATOR_ID) "Adrian" else "Adrian → ${vm.npc(m.targetId).name}",
            Alignment.CenterEnd
        )
        "npc" -> Triple(
            if (speaker.isNarrator) cs.surfaceVariant.copy(alpha = 0.6f) else Color(speaker.color).copy(alpha = 0.16f),
            "${speaker.emoji} ${speaker.name}",
            Alignment.CenterStart
        )
        "error" -> Triple(cs.errorContainer, "Errore", Alignment.CenterStart)
        else -> Triple(Color(0x336D4FC2), "Sistema", Alignment.CenterStart)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = align) {
        Column(
            Modifier
                .fillMaxWidth(if (m.role == "npc" && speaker.isNarrator) 1f else 0.9f)
                .background(bg, RoundedCornerShape(14.dp))
                .padding(10.dp)
        ) {
            val clipboard = LocalClipboardManager.current
            var copied by remember(m) { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    label + if (m.ooc) " · fuori gioco" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (m.role == "npc" && !speaker.isNarrator) Color(speaker.color) else Color.Unspecified
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(m.text)); copied = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(if (copied) "Copiato ✓" else "Copia", style = MaterialTheme.typography.labelSmall)
                }
            }
            SelectionContainer {
                Text(m.text, fontStyle = if (m.role == "npc" && speaker.isNarrator) FontStyle.Italic else FontStyle.Normal)
            }
            if (m.meta.isNotEmpty()) {
                Text(
                    m.meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

// =====================================================================
// PERSONAGGI
// =====================================================================

@Composable
fun CharactersScreen(vm: GameViewModel, modifier: Modifier = Modifier) {
    val p = vm.params
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {

        Text(
            "Le modifiche valgono dalla risposta successiva. Le memorie dei personaggi non cambiano.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Section("🌆 Ambientazione (comune a tutti)") {
            OutlinedTextField(
                value = p.world,
                onValueChange = { v -> vm.updateParams { it.copy(world = v) } },
                modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 14
            )
            OutlinedButton(onClick = { vm.updateParams { it.copy(world = DEFAULT_WORLD) } }) { Text("Ripristina") }
        }

        Section("🧍 Adrian (tu)") {
            Text(
                "Chi sei, cosa porti con te, quanti crediti hai. Tutti i personaggi la leggono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = p.playerSheet,
                onValueChange = { v -> vm.updateParams { it.copy(playerSheet = v) } },
                modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 10
            )
            OutlinedButton(onClick = { vm.updateParams { it.copy(playerSheet = DEFAULT_PLAYER_SHEET) } }) { Text("Ripristina") }
        }

        vm.npcs.forEach { n -> NpcEditor(vm, n) }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun NpcEditor(vm: GameViewModel, n: Npc) {
    var open by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    Section("${n.emoji} ${n.name}") {
        Text(
            n.role + if (n.isNarrator) "" else " · 📍 ${n.location}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { open = !open }) { Text(if (open) "Chiudi scheda" else "Modifica scheda") }
        if (open) {
            OutlinedTextField(
                value = n.name, onValueChange = { v -> vm.updateNpc(n.id) { it.copy(name = v) } },
                label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = n.role, onValueChange = { v -> vm.updateNpc(n.id) { it.copy(role = v) } },
                label = { Text("Ruolo") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = n.emoji, onValueChange = { v -> vm.updateNpc(n.id) { it.copy(emoji = v.take(4)) } },
                label = { Text("Emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            if (!n.isNarrator) {
                OutlinedTextField(
                    value = n.location, onValueChange = { v -> vm.updateNpc(n.id) { it.copy(location = v) } },
                    label = { Text("Luogo") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    vm.locations.filter { it != n.location }.forEach { loc ->
                        TextButton(onClick = { vm.updateNpc(n.id) { it.copy(location = loc) } }) {
                            Text(loc, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            OutlinedTextField(
                value = n.persona, onValueChange = { v -> vm.updateNpc(n.id) { it.copy(persona = v) } },
                label = { Text(if (n.isNarrator) "Stile e regole del narratore" else "Personalità, aspetto, passato, segreti") },
                modifier = Modifier.fillMaxWidth(), minLines = 6, maxLines = 20
            )
            SliderRow(
                "Temperatura (creatività)", n.temperature, 0f..1.5f, 29,
                hint = "Bassa = coerente e prevedibile, alta = sorprendente e meno affidabile."
            ) { v -> vm.updateNpc(n.id) { it.copy(temperature = Math.round(v * 20) / 20f) } }
            IntSliderRow("Frasi massime per risposta", n.maxSentences, 1, 15) { v ->
                vm.updateNpc(n.id) { it.copy(maxSentences = v) }
            }
            OutlinedButton(onClick = { confirmReset = true }) { Text("Ripristina scheda predefinita") }
        }
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Ripristinare ${n.name}?") },
            text = { Text("La scheda torna a quella predefinita. Le tue modifiche andranno perse; la memoria resta.") },
            confirmButton = { TextButton(onClick = { vm.resetNpc(n.id); confirmReset = false }) { Text("Ripristina") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Annulla") } }
        )
    }
}

// =====================================================================
// PARAMETRI (con statistiche)
// =====================================================================

@Composable
fun ParamsScreen(vm: GameViewModel, modifier: Modifier = Modifier) {
    val p = vm.params
    val s = vm.stats
    var showKey by remember { mutableStateOf(false) }
    var showGroqKey by remember { mutableStateOf(false) }
    var confirmNewGame by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {

        Section("🎮 Partita") {
            Text(
                "Nuova partita cancella la storia e le memorie di tutti i personaggi. Le schede dei personaggi restano.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = { confirmNewGame = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Nuova partita")
            }
            OutlinedButton(onClick = { vm.clearFeedOnly() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text("Svuota solo lo schermo (le memorie restano)")
            }
        }

        Section("🚀 Provider AI") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("gemini" to "Gemini", "groq" to "Groq").forEach { (id, name) ->
                    if (p.provider == id) {
                        Button(onClick = {}, modifier = Modifier.weight(1f)) { Text(name) }
                    } else {
                        OutlinedButton(
                            onClick = { vm.updateParams { it.copy(provider = id) } },
                            modifier = Modifier.weight(1f)
                        ) { Text(name) }
                    }
                }
            }
            Text("Attivo: ${Llm.label(p.provider)} · ${Llm.modelOf(p, p.provider)}", fontWeight = FontWeight.Bold)
            SwitchRow(
                "Passa all'altro provider se non risponde", p.failover,
                "Dopo un nuovo tentativo, gli errori 429 e 503 fanno passare la richiesta all'altro provider, se ne hai la chiave."
            ) { v -> vm.updateParams { it.copy(failover = v) } }
            SwitchRow(
                "Mostra provider sotto le risposte", p.showDiagnostics,
                "Aggiunge sotto ogni risposta provider, modello e tempo impiegato."
            ) { v -> vm.updateParams { it.copy(showDiagnostics = v) } }
            OutlinedTextField(
                value = p.groqKey,
                onValueChange = { v -> vm.updateParams { it.copy(groqKey = v.trim()) } },
                label = { Text("API key Groq") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showGroqKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            TextButton(onClick = { showGroqKey = !showGroqKey }) { Text(if (showGroqKey) "Nascondi chiave" else "Mostra chiave") }
            OutlinedTextField(
                value = p.groqModel,
                onValueChange = { v -> vm.updateParams { it.copy(groqModel = v.trim()) } },
                label = { Text("Modello Groq") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("Modelli suggeriti (tocca per usarli)", style = MaterialTheme.typography.labelMedium)
            GROQ_PRESETS.forEach { (id, name) ->
                TextButton(onClick = { vm.updateParams { it.copy(groqModel = id) } }) {
                    Text(if (p.groqModel == id) "✓ $name · $id" else "$name · $id")
                }
            }
            Text(
                "Gli ID dei modelli cambiano spesso: se uno dà errore 404, scegline un altro. " +
                    "Groq non offre embedding: la memoria a coseno usa sempre la chiave Gemini.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("🔌 Connessione Gemini") {
            OutlinedTextField(
                value = p.apiKey,
                onValueChange = { v -> vm.updateParams { it.copy(apiKey = v.trim()) } },
                label = { Text("API key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Nascondi chiave" else "Mostra chiave") }
            OutlinedTextField(
                value = p.model,
                onValueChange = { v -> vm.updateParams { it.copy(model = v.trim()) } },
                label = { Text("Modello") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.updateParams { it.copy(model = "gemini-3.8-flash") } }) {
                    Text("Usa gemini-3.8-flash")
                }
                if (p.apiKey.isNotBlank()) {
                    TextButton(onClick = { vm.updateParams { it.copy(apiKey = "") } }) { Text("Cancella chiave") }
                }
            }
            Text(
                "Il modello è un campo libero: se Google cambia nome o ritira un modello, " +
                    "scrivi qui il nuovo ID (elenco su ai.google.dev/gemini-api/docs/models).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("⚡ Energia di Adrian") {
            IntSliderRow(
                "Token massimi per sessione", p.sessionTokenBudget, 10_000, 1_000_000, 10_000,
                hint = "L'energia è piena all'apertura dell'app, a una nuova partita o dopo il riposo nella capsula, e scende con i token usati " +
                    "(input + output di ogni risposta). Predefinito: 100.000."
            ) { v -> vm.updateParams { it.copy(sessionTokenBudget = v) } }
            Text(
                "Usati in questa sessione: %,d token · rimasti: %,d".format(vm.sessionTokensUsed, vm.tokensLeft),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Section("🎛️ Generazione") {
            SliderRow("Top-P", p.topP, 0f..1f, 19) { v -> vm.updateParams { it.copy(topP = round2(v)) } }
            IntSliderRow("Top-K", p.topK, 1, 100) { v -> vm.updateParams { it.copy(topK = v) } }
            IntSliderRow("Max token in uscita", p.maxOutputTokens, 256, 8192, 256) { v -> vm.updateParams { it.copy(maxOutputTokens = v) } }
            IntSliderRow(
                "Messaggi recenti inviati", p.historyMessages, 4, 60, 2,
                hint = "Quanti messaggi della conversazione con quel personaggio riceve a ogni risposta. Più alto = più coerenza, più token."
            ) { v -> vm.updateParams { it.copy(historyMessages = v) } }
            Text(
                "La temperatura si regola per ogni personaggio nella scheda Personaggi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Section("🧠 Memoria dei personaggi") {
            IntSliderRow(
                "Scadenza dei ricordi", p.memoryTtlDays, 0, 365, 1, " gg",
                hint = "0 = i ricordi non scadono mai (consigliato per una partita)."
            ) { v -> vm.updateParams { it.copy(memoryTtlDays = v) } }
            IntSliderRow(
                "Ricordi richiamati per risposta", p.memoryTopK, 0, 20
            ) { v -> vm.updateParams { it.copy(memoryTopK = v) } }
            SwitchRow(
                "Richiamo per similarità (embedding)", p.useEmbeddings,
                "Usa la chiave Gemini. Ogni ricordo e ogni messaggio fanno una chiamata embedding in più."
            ) { v -> vm.updateParams { it.copy(useEmbeddings = v) } }
            OutlinedTextField(
                value = p.embeddingModel,
                onValueChange = { v -> vm.updateParams { it.copy(embeddingModel = v.trim()) } },
                label = { Text("Modello di embedding") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            IntSliderRow(
                "Dimensione del vettore", p.embeddingDim, 128, 3072, 128,
                hint = "Consigliati 768, 1536, 3072. Se la cambi, usa \"Calcola embedding mancanti\" in Memoria."
            ) { v -> vm.updateParams { it.copy(embeddingDim = v) } }
            SliderRow(
                "Soglia di similarità minima", p.similarityThreshold, 0f..1f, 99,
                hint = "Sotto questa soglia il ricordo non viene richiamato."
            ) { v -> vm.updateParams { it.copy(similarityThreshold = round2(v)) } }
        }

        Section("📊 Statistiche della sessione") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Richieste", s.requests.toString(), Modifier.weight(1f))
                StatTile("Errori", s.errors.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Token input", s.promptTokens.toString(), Modifier.weight(1f))
                StatTile("Token output", s.outputTokens.toString(), Modifier.weight(1f))
            }
            StatTile("Ultima risposta da", s.lastProvider, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Chiamate Groq", s.groqCalls.toString(), Modifier.weight(1f))
                StatTile("Passaggi automatici", s.failovers.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Latenza ultima", "${s.lastLatencyMs} ms", Modifier.weight(1f))
                StatTile("Latenza media", "${s.avgLatencyMs} ms", Modifier.weight(1f))
            }
            Sparkline(s.latenciesMs)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Chiamate embedding", s.embedCalls.toString(), Modifier.weight(1f))
                StatTile("Errori embedding", s.embedErrors.toString(), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("Ultimo coseno (max)", "%.3f".format(s.lastSimilarity), Modifier.weight(1f))
                StatTile("Ricordi richiamati", s.lastRecallHits.toString(), Modifier.weight(1f))
            }
        }

        OutlinedButton(
            onClick = { vm.resetParams() },
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) { Text("Ripristina i parametri (tiene chiavi, modelli e ambientazione)") }
    }

    if (confirmNewGame) {
        AlertDialog(
            onDismissRequest = { confirmNewGame = false },
            title = { Text("Iniziare una nuova partita?") },
            text = { Text("La storia e le memorie di tutti i personaggi verranno cancellate definitivamente.") },
            confirmButton = { TextButton(onClick = { vm.newGame(); confirmNewGame = false }) { Text("Nuova partita") } },
            dismissButton = { TextButton(onClick = { confirmNewGame = false }) { Text("Annulla") } }
        )
    }
}

private fun round2(v: Float) = Math.round(v * 100) / 100f

@Composable
private fun Sparkline(values: List<Long>) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(60.dp)) {
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
        if (values.size < 2) return@Canvas
        val max = values.max().coerceAtLeast(1L).toFloat()
        val stepX = size.width / (values.size - 1)
        for (i in 0 until values.size - 1) {
            val y1 = size.height - (values[i] / max) * (size.height - 6f)
            val y2 = size.height - (values[i + 1] / max) * (size.height - 6f)
            drawLine(line, Offset(i * stepX, y1), Offset((i + 1) * stepX, y2), strokeWidth = 5f, cap = StrokeCap.Round)
        }
    }
}

// =====================================================================
// MEMORIA (una per personaggio)
// =====================================================================

@Composable
fun MemoryScreen(vm: GameViewModel, modifier: Modifier = Modifier) {
    vm.memoryVersion
    var owner by remember { mutableStateOf(NARRATOR_ID) }
    var fact by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault()) }
    val store = vm.memories.getValue(owner)
    val who = vm.npc(owner)
    val sorted = store.entries.sortedByDescending { it.timestamp }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                vm.npcs.forEach { n ->
                    CharChip(
                        "${n.emoji} ${n.name} (${vm.memories.getValue(n.id).entries.size})",
                        Color(n.color), owner == n.id, true
                    ) { owner = n.id }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Aggiungi un fatto che ${who.name} sa", fontWeight = FontWeight.Bold)
                    Text(
                        "Solo ${who.name} lo conoscerà, e lo avrà sempre presente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = fact, onValueChange = { fact = it },
                        modifier = Modifier.fillMaxWidth(), minLines = 2,
                        placeholder = { Text("Es.: Adrian mi deve 300 crediti") }
                    )
                    Button(onClick = { vm.addFact(owner, fact); fact = "" }, enabled = fact.isNotBlank()) {
                        Text("Salva")
                    }
                }
            }
        }
        item {
            val missing = vm.missingVectors()
            OutlinedButton(
                onClick = { vm.backfillEmbeddings() },
                enabled = missing > 0 && !vm.backfilling && vm.params.useEmbeddings && vm.params.apiKey.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (vm.backfilling) "Calcolo in corso…" else "Calcola embedding mancanti, tutti i personaggi ($missing)")
            }
        }
        if (sorted.isEmpty()) {
            item { Text("${who.name} non ricorda ancora nulla. Ogni scambio con Adrian diventa un ricordo.") }
        }
        items(sorted, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${e.layer.label} · ${e.type}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(fmt.format(Date(e.timestamp)), style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(e.content, maxLines = 8)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "ttl ${if (e.ttlDays == 0) "∞" else "${e.ttlDays}gg"} · " +
                                (store.vectors[e.id]?.let { "🧭 ${it.values.size}d" } ?: "no vettore"),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Row {
                            if (e.ttlDays > 0) {
                                TextButton(onClick = { vm.makePermanent(owner, e.id) }) { Text("Rendi permanente") }
                            }
                            TextButton(onClick = { vm.deleteMemory(owner, e.id) }) { Text("Elimina") }
                        }
                    }
                }
            }
        }
    }
}
