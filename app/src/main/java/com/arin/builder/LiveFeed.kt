package com.arin.builder

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

/**
 * Everything the models are doing right now, word for word: Claude's thinking, the tools it runs (searches, commands,
 * files it reads or writes), workers' progress and every build step. Shown when the Summary is maximised, so the owner
 * can always see what is really happening (not just "Working").
 */
object LiveFeed {
    data class Line(val time: Long, val who: String, val text: String)

    val lines = mutableStateListOf<Line>()
    /** When anything last happened (for "last activity 12 s ago"). */
    var lastAt by mutableStateOf(0L)
        private set
    var lastLine by mutableStateOf("")
        private set

    private val partial = mutableMapOf<String, StringBuilder>()

    /** A whole line from [who]. Safe from any thread. */
    fun add(who: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        com.arin.ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            t.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.forEach { l ->
                if (lines.lastOrNull()?.let { it.who == who && it.text == l } == true) return@forEach
                lines += Line(now, who, l.take(600))
            }
            while (lines.size > 800) lines.removeAt(0)
            lastAt = now
            lastLine = "$who: " + t.lineSequence().last { it.isNotBlank() }.trim().take(140)
        }
    }

    /** A piece of streamed text from [who]: shown line by line as it arrives. */
    @Synchronized
    fun stream(who: String, chunk: String) {
        val b = partial.getOrPut(who) { StringBuilder() }
        b.append(chunk)
        val cut = b.lastIndexOf("\n")
        if (cut >= 0) { add(who, b.substring(0, cut)); b.delete(0, cut + 1) }
        else if (b.length > 240) { add(who, b.toString()); b.setLength(0) }
        else lastAt = System.currentTimeMillis()
    }
}
