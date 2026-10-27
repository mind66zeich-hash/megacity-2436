package com.megacity.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** Un pezzo di testo da leggere con una certa voce. */
data class SpeechPart(val text: String, val voice: Npc)

/** Asterischi: *azione* → voce del narratore, il resto → voce di chi parla. */
private val ACTION = Regex("\\*([^*]+)\\*")

/** Toglie emoji e simboli che la sintesi leggerebbe male. */
private val NOISE = Regex("[\\p{So}\\p{Cn}\\u200D\\uFE0F]")

/**
 * Lettura ad alta voce con la sintesi vocale di Android (TextToSpeech, di solito il motore Google).
 * Nessun costo: non usa API esterne né token.
 */
class Speaker(context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private val pending = mutableListOf<List<SpeechPart>>()
    private var counter = 0
    private var lastId = ""

    /** Vero mentre sta leggendo (per mostrare il tasto Stop). */
    var speaking by mutableStateOf(false)
        private set

    /** Voci italiane installate sul telefono (nome tecnico del motore). */
    var italianVoices by mutableStateOf<List<String>>(emptyList())
        private set

    /** Messaggio d'errore se la sintesi non è disponibile. */
    var problem by mutableStateOf<String?>(null)
        private set

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        main.post { onInit(status) }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            problem = "Sintesi vocale non disponibile su questo telefono."
            return
        }
        val res = tts.setLanguage(Locale.ITALIAN)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            problem = "Voce italiana non installata: scaricala nelle impostazioni della sintesi vocale."
        }
        italianVoices = try {
            tts.voices.orEmpty()
                .filter { it.locale.language == "it" }
                .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.name }))
                .map { it.name }
        } catch (e: Exception) {
            emptyList()
        }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { speaking = true }
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastId) main.post { speaking = false }
            }

            @Deprecated("Richiesto dalla classe astratta")
            override fun onError(utteranceId: String?) {
                if (utteranceId == lastId) main.post { speaking = false }
            }
        })
        ready = true
        pending.forEach { enqueue(it) }
        pending.clear()
    }

    /** Divide un messaggio: le azioni tra asterischi con la voce del narratore, le battute con quella del personaggio. */
    fun split(text: String, speaker: Npc, narrator: Npc): List<SpeechPart> {
        if (speaker.isNarrator) return listOf(SpeechPart(text.replace("*", ""), narrator))
        val parts = mutableListOf<SpeechPart>()
        var last = 0
        ACTION.findAll(text).forEach { m ->
            if (m.range.first > last) parts += SpeechPart(text.substring(last, m.range.first), speaker)
            parts += SpeechPart(m.groupValues[1], narrator)
            last = m.range.last + 1
        }
        if (last < text.length) parts += SpeechPart(text.substring(last), speaker)
        return parts
    }

    fun speak(parts: List<SpeechPart>, interrupt: Boolean = false) {
        if (interrupt) stop()
        if (!ready) {
            pending.add(parts)
            return
        }
        enqueue(parts)
    }

    private fun enqueue(parts: List<SpeechPart>) {
        parts.forEach { part ->
            val clean = part.text.replace(NOISE, " ").replace(Regex("\\s+"), " ").trim()
            if (clean.isEmpty() || clean.none { it.isLetterOrDigit() }) return@forEach
            applyVoice(part.voice)
            lastId = "u${counter++}"
            // Il testo troppo lungo va spezzato: il motore ha un limite per singola richiesta.
            clean.chunked(TextToSpeech.getMaxSpeechInputLength() - 10).forEachIndexed { i, chunk ->
                val id = if (i == 0) lastId else "${lastId}_$i"
                tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, id)
                lastId = id
            }
        }
    }

    private fun applyVoice(n: Npc) {
        val wanted = n.voiceName
        val voice = if (wanted.isBlank()) null else try {
            tts.voices?.firstOrNull { it.name == wanted }
        } catch (e: Exception) {
            null
        }
        // setVoice restituisce un int: va chiamato come metodo, non come proprietà Kotlin.
        if (voice != null) tts.setVoice(voice) else tts.setLanguage(Locale.ITALIAN)
        tts.setPitch(n.voicePitch.coerceIn(0.3f, 2.5f))
        tts.setSpeechRate(n.voiceRate.coerceIn(0.3f, 2.5f))
    }

    fun stop() {
        pending.clear()
        if (ready) tts.stop()
        speaking = false
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
