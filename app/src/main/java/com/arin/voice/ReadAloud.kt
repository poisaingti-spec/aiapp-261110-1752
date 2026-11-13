package com.arin.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Reads a reply out loud with the phone's own voice (free, works offline). */
object ReadAloud {
    private var tts: TextToSpeech? = null
    private var ready = false

    /** The message being read right now, or null. */
    var speakingId by mutableStateOf<String?>(null)
        private set

    /** Starts reading [text]; tapping again on the same message stops it. */
    fun toggle(context: Context, id: String, text: String) {
        if (speakingId == id) {
            stop()
            return
        }
        val plain = plainText(text)
        if (plain.isBlank()) return
        speakingId = id
        val engine = tts
        if (engine != null && ready) {
            speak(engine, plain)
        } else {
            tts = TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                val t = tts
                if (ready && t != null && speakingId == id) speak(t, plain) else speakingId = null
            }
        }
    }

    fun stop() {
        runCatching { tts?.stop() }
        speakingId = null
    }

    private fun speak(engine: TextToSpeech, text: String) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == LAST) speakingId = null
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                speakingId = null
            }
        })
        engine.stop()
        val parts = chunks(text)
        parts.forEachIndexed { i, part ->
            engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, if (i == parts.lastIndex) LAST else "part$i")
        }
    }

    private const val LAST = "arin-last"

    /** The engine takes a few thousand characters at a time: split at sentence ends. */
    private fun chunks(text: String, size: Int = 3000): List<String> {
        val out = mutableListOf<String>()
        var rest = text
        while (rest.length > size) {
            val cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), size).let { if (it < size / 2) size else it + 1 }
            out += rest.substring(0, cut)
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) out += rest
        return out
    }

    /** Markdown turned into something pleasant to listen to. */
    private fun plainText(markdown: String): String =
        markdown
            .replace(Regex("```[\\s\\S]*?```"), " (code not read out) ")
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("https?://\\S+"), " link ")
            .replace(Regex("(?m)^\\s*\\|?\\s*:?-{2,}.*$"), " ")
            .replace(Regex("[*_#>`|~]"), " ")
            .replace(Regex("[ \\t]+"), " ")
            .trim()
}
