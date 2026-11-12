package com.arin.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

enum class VoiceState { Starting, Listening, Thinking, Speaking, Paused }

/**
 * Talks with the person using the phone's own speech recognition and voice. Arin listens,
 * hands the words to [onHeard], then reads the reply aloud sentence by sentence as it arrives.
 * Must be used on the main thread.
 */
class VoiceController(
    private val context: Context,
    private val onHeard: (String) -> Unit,
    private val onInterrupt: () -> Unit,
) {
    var state by mutableStateOf(VoiceState.Starting)
        private set
    /** Words heard so far in the current turn. */
    var partial by mutableStateOf("")
        private set
    /** Microphone loudness, 0..1, for the animation. */
    var level by mutableFloatStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<String>()
    private val queued = AtomicInteger(0)
    private var replyDone = false
    private var closed = false
    var rate = 1.0f

    fun start() {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { mainThread { onUtteranceEnded() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) { mainThread { onUtteranceEnded() } }
                })
                pendingSpeech.toList().also { pendingSpeech.clear() }.forEach { speak(it) }
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            error = "This phone has no speech recognition. Install or enable Google's speech services."
            state = VoiceState.Paused
            return
        }
        listen()
    }

    fun listen() {
        if (closed) return
        tts?.stop()
        queued.set(0)
        partial = ""
        error = null
        state = VoiceState.Listening
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            recognizer = it
            it.setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
        r.cancel()
        r.startListening(intent)
    }

    /** A new reply is starting: nothing has been spoken from it yet. */
    fun replyStarted() {
        replyDone = false
        state = VoiceState.Thinking
    }

    /** Speak one sentence of the reply. */
    fun say(sentence: String) {
        val clean = speakable(sentence)
        if (clean.isBlank()) return
        if (!ttsReady) { pendingSpeech += clean; return }
        speak(clean)
    }

    /** The reply is complete; listening resumes once the last sentence has been spoken. */
    fun replyFinished() {
        replyDone = true
        if (queued.get() == 0 && state != VoiceState.Listening) listen()
    }

    /** Tap on the orb: stop talking and listen, or stop listening. */
    fun tap() {
        when (state) {
            VoiceState.Speaking, VoiceState.Thinking -> { onInterrupt(); replyDone = true; listen() }
            VoiceState.Paused -> listen()
            VoiceState.Listening -> { recognizer?.stopListening() }
            VoiceState.Starting -> {}
        }
    }

    fun close() {
        closed = true
        runCatching { recognizer?.destroy() }
        runCatching { tts?.stop(); tts?.shutdown() }
        recognizer = null
        tts = null
    }

    private fun speak(text: String) {
        state = VoiceState.Speaking
        queued.incrementAndGet()
        tts?.setSpeechRate(rate)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "arin-${System.nanoTime()}")
    }

    private fun onUtteranceEnded() {
        if (queued.decrementAndGet() <= 0) {
            queued.set(0)
            if (replyDone) listen() else if (state == VoiceState.Speaking) state = VoiceState.Thinking
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { level = 0f }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
            if (text.isEmpty()) { state = VoiceState.Paused; return }
            partial = text
            state = VoiceState.Thinking
            onHeard(text)
        }
        override fun onError(code: Int) {
            level = 0f
            when (code) {
                // Heard nothing: wait for a tap rather than listening forever.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> state = VoiceState.Paused
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    error = "Arin needs microphone permission for voice mode."
                    state = VoiceState.Paused
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                    error = "Speech recognition needs an internet connection right now."
                    state = VoiceState.Paused
                }
                else -> state = VoiceState.Paused
            }
        }
    }

    private fun mainThread(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    }

    companion object {
        /** Markdown and code read badly aloud: keep the words. */
        fun speakable(text: String): String = text
            .replace(Regex("""```.*?```""", RegexOption.DOT_MATCHES_ALL), " (code shown on screen) ")
            .replace(Regex("""`([^`]*)`"""), "$1")
            .replace(Regex("""\[([^\]]+)\]\([^)]*\)"""), "$1")
            .replace(Regex("""[*_#>|]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        private val sentenceEnd = Regex("""(?<=[.!?…:])\s+|\n{2,}""")

        /**
         * Splits [text] after [from] into whole sentences ready to speak. Returns the sentences
         * and the new offset; the unfinished tail is left for later.
         */
        fun nextSentences(text: String, from: Int, final: Boolean): Pair<List<String>, Int> {
            if (from >= text.length) return emptyList<String>() to from
            val rest = text.substring(from)
            val out = mutableListOf<String>()
            var consumed = 0
            var last = 0
            sentenceEnd.findAll(rest).forEach { m ->
                val piece = rest.substring(last, m.range.last + 1).trim()
                if (piece.isNotEmpty()) out += piece
                last = m.range.last + 1
                consumed = last
            }
            if (final && last < rest.length) {
                rest.substring(last).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                consumed = rest.length
            }
            return out to from + consumed
        }
    }
}
