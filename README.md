# Megacity 2436 (Android)

Gioco di ruolo testuale cyberpunk per Android. Tu sei Adrian; i personaggi sono agenti gestiti dall'AI
(Gemini o Groq), ognuno con una memoria indipendente.

## Compilare su GitHub

1. Crea un repository vuoto su GitHub e carica tutto il contenuto di questa cartella (compresa `.github/`).
2. La compilazione parte a ogni push su `main` (oppure **Actions > Build APK > Run workflow**).
3. A build finita apri l'esecuzione e scarica l'artefatto **megacity-2436-debug**: dentro c'è `app-debug.apk`.
4. Installalo sul telefono (serve consentire l'installazione da fonti sconosciute).

## Primo avvio

Inserisci la API key di Gemini (Google AI Studio) o di Groq (console.groq.com) nella schermata Gioco
o in **Parametri**. La chiave resta sul telefono.

## Personaggi

| Personaggio | Dove si trova | Note |
|---|---|---|
| 📖 Narratore | ovunque | Descrive scene ed esiti delle azioni, fa da master: per ora decide l'AI le regole |
| 🤖 Nysera | Laboratorio Helix – Livello 112 | Androide sintetico ultra avanzato; ex relazione con Varga |
| 😎 Mr Nice | Laboratorio Helix – Livello 112 | Intermediario: offre missioni pagate |
| 🕴️ Varga | Locale Megacity – Settore 4 | Direttore del locale; ex relazione con Nysera |
| 💋 Vanessa | Locale Megacity – Settore 4 | Maliziosa e loquace, informatrice di Varga (e non solo) |
| 🖥️ NODO-7 | Locale Megacity – Settore 4 | Distributore di materiale informatico, catalogo dinamico |

Nomi, aspetto, personalità, segreti e luoghi si modificano nella scheda **Personaggi**, insieme
all'ambientazione comune e alla scheda di Adrian.

## Come si gioca

- In alto scegli il luogo (📍): spostarti fa descrivere l'arrivo al narratore.
- In basso scegli con chi parlare. Si può parlare solo con chi è nello stesso luogo.
- Un messaggio fatto solo di un'azione tra asterischi (`*sfondo la porta*`) va sempre al narratore.
- **⚡ Energia di Adrian:** scende con i token usati nella sessione (limite in Parametri, predefinito 100.000).
  Il tasto **🛌 Riposa** (nella capsula) si può usare in qualsiasi momento, ed è evidenziato quando l'energia arriva a zero: come il vecchio "dormi" azzera la chat (le memorie restano), energia di nuovo piena, Adrian viene incapsulato e diventa invisibile
  ai personaggi (sentono solo la sua voce), ma puoi continuare a giocare. **Esci dalla capsula** lo rende di nuovo visibile.
- **💳 Crediti e 🎒 Inventario** sono gestiti dal gioco (si parte con 1.200 ¢, pistola a impulsi e giacca termica).
  Narratore e personaggi possono dare o togliere crediti e oggetti: l'app li applica e lo segnala in chat.
  Nella scheda **Inventario** ogni oggetto ha **Usa** (scala una carica e manda l'azione al narratore) e **Lascia**.
- **NODO-7** rinnova il catalogo ogni volta che arrivi al Locale Megacity: 5 oggetti calibrati sui tuoi crediti
  (3 alla tua portata, 1 un po' sopra, 1 raro o illegale). Quando parli con lui compaiono le schede con **Compra**.
- **📋 Missioni di Mr Nice:** arrivando al Laboratorio Helix, Mr Nice prepara 3 lavori (facile, media, difficile)
  con ricompensa in crediti; quando parli con lui compaiono le schede con **Accetta**. Una missione alla volta.
  Quando l'obiettivo è raggiunto il narratore (o il personaggio coinvolto) la segna completata; poi torni da
  Mr Nice e premi **Riscuoti**. La missione in corso si vede nella scheda Inventario, dove si può abbandonare.
- **🎤 Microfono:** il tasto accanto al campo di testo avvia la dettatura in italiano (riconoscimento vocale di Android).
  Il testo finisce nel campo; in Parametri puoi farlo inviare subito ("Invia subito dopo la dettatura").
- **🔊 Lettura ad alta voce:** narratore e personaggi vengono letti appena rispondono (sintesi vocale di Android,
  nessun token). Ognuno ha voce, tono e velocità propri (scheda Personaggi, con "Prova voce"); le azioni tra
  asterischi usano la voce del narratore. 🔊 su ogni messaggio per riascoltarlo, ⏹ Stop per interrompere;
  si spegne in Parametri.
- `OOC: ...` parla fuori dal gioco con il personaggio selezionato (utile per correggerlo): lo scambio
  non entra nella sua memoria né nella cronologia.

## Memorie indipendenti

Ogni personaggio ha i suoi file (`memorie/<id>_payload.json`, `_index.bin`, `_event_log.jsonl`).
Un PNG riceve solo i propri scambi con Adrian, i propri ricordi e l'ultima descrizione del narratore
nel luogo in cui si trova. Il narratore vede tutta la storia recente.

**Ricordi riassunti:** dopo ogni risposta, in background, un modello leggero di Groq (predefinito
`openai/gpt-oss-20b`) riassume lo scambio dal punto di vista del personaggio (il narratore come cronaca).
Il riassunto diventa il ricordo; il testo originale completo resta visibile con "Mostra originale".
I token dei riassunti scaricano l'energia di Adrian. Senza chiave Groq, o se il riassunto fallisce,
si salva il testo originale. Si disattiva e si cambia modello in Parametri → Memoria.

Nella scheda **Memoria** scegli il personaggio, leggi o cancelli i suoi ricordi e aggiungi fatti che
conoscerà solo lui (restano sempre presenti nel suo prompt).

**Nuova partita** (in Parametri) cancella storia e memorie di tutti; le schede dei personaggi restano.

## Tecnica

- Kotlin + Jetpack Compose, minSdk 26. Gradle 8.7 installato dal workflow, senza wrapper.
- Provider Gemini e Groq: se uno risponde 429/503 si ritenta una volta, poi si passa all'altro (se hai la chiave).
- Richiamo dei ricordi con embedding Gemini e similarità del coseno, più parole in comune come riserva.
- La ricerca web è sempre spenta nel gioco. Il parlamento di validazione e le fasi omeostatiche del template sono stati rimossi.
- `./rinomina.sh "Nome" com.tuo.package` cambia nome e package dell'app.
