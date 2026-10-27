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
| 🕴️ Varga | Locale Megacity – Settore 4 | Direttore del locale; ex relazione con Nysera |
| 🖥️ NODO-7 | Locale Megacity – Settore 4 | Distributore di materiale informatico, con catalogo e prezzi |

Nomi, aspetto, personalità, segreti e luoghi si modificano nella scheda **Personaggi**, insieme
all'ambientazione comune e alla scheda di Adrian.

## Come si gioca

- In alto scegli il luogo (📍): spostarti fa descrivere l'arrivo al narratore.
- In basso scegli con chi parlare. Si può parlare solo con chi è nello stesso luogo.
- Un messaggio fatto solo di un'azione tra asterischi (`*sfondo la porta*`) va sempre al narratore.
- **⚡ Energia di Adrian:** scende con i token usati nella sessione (limite in Parametri, predefinito 100.000).
  Il tasto **🛌 Riposa** (nella capsula) si può usare in qualsiasi momento, ed è evidenziato quando l'energia arriva a zero: come il vecchio "dormi" azzera la chat (le memorie restano), energia di nuovo piena, Adrian viene incapsulato e diventa invisibile
  ai personaggi (sentono solo la sua voce), ma puoi continuare a giocare. **Esci dalla capsula** lo rende di nuovo visibile.
- `OOC: ...` parla fuori dal gioco con il personaggio selezionato (utile per correggerlo): lo scambio
  non entra nella sua memoria né nella cronologia.

## Memorie indipendenti

Ogni personaggio ha i suoi file (`memorie/<id>_payload.json`, `_index.bin`, `_event_log.jsonl`).
Un PNG riceve solo i propri scambi con Adrian, i propri ricordi e l'ultima descrizione del narratore
nel luogo in cui si trova. Il narratore vede tutta la storia recente.

Nella scheda **Memoria** scegli il personaggio, leggi o cancelli i suoi ricordi e aggiungi fatti che
conoscerà solo lui (restano sempre presenti nel suo prompt).

**Nuova partita** (in Parametri) cancella storia e memorie di tutti; le schede dei personaggi restano.

## Tecnica

- Kotlin + Jetpack Compose, minSdk 26. Gradle 8.7 installato dal workflow, senza wrapper.
- Provider Gemini e Groq: se uno risponde 429/503 si ritenta una volta, poi si passa all'altro (se hai la chiave).
- Richiamo dei ricordi con embedding Gemini e similarità del coseno, più parole in comune come riserva.
- La ricerca web è sempre spenta nel gioco. Il parlamento di validazione e le fasi omeostatiche del template sono stati rimossi.
- `./rinomina.sh "Nome" com.tuo.package` cambia nome e package dell'app.
