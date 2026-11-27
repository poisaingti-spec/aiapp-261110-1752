package com.arin.selffix

import android.content.Context
import com.arin.llm.Turn

/**
 * ARIN-PROTECTED: boss-memory — the main model remembers the WHOLE chat while reading very little of it each time.
 *
 * The method (the one long-chat agents use: a pinned head, a running summary, a recent tail, and recall):
 *  1. The first message is always sent (what the chat is about).
 *  2. Everything older than the latest turns is kept in a running MEMORY note: every request, requirement, decision,
 *     name, number, file, link and unfinished task. It is updated in batches (not every message), only from the messages
 *     that are new since last time, so each message is read once more in total — and the note stays the same between
 *     updates, so servers with a prompt cache serve it at a fraction of the price. Written by the offline model on the
 *     phone when there is one (free); otherwise by your main model, briefly. The worker and helper never see the chat.
 *  3. The latest turns are sent whole.
 *  4. RECALL: older messages that share words with your new message are added word-for-word (no AI, free), so exact
 *     details (numbers, names, code you pasted) are never lost to the summary.
 * Nothing is deleted from the chat itself.
 */
object ChatMemory {
    private const val KEEP = 8          // latest turns sent whole
    private const val BATCH = 6         // update the memory once this many older turns are waiting
    private const val PREFS = "chat_memory"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun forget(c: Context, chat: String) = prefs(c).edit().remove("$chat|sum").remove("$chat|upto").remove("$chat|sig").apply()

    /**
     * [turns] made small for the main model. [latest] = the owner's newest message (for recall).
     * [summarize] writes text with a cheap call (offline model or the main model, lean); "" when it couldn't.
     */
    suspend fun compact(c: Context, chat: String, turns: List<Turn>, latest: String, summarize: suspend (String) -> String): List<Turn> {
        if (turns.size <= KEEP + 2) return turns
        var keepFrom = turns.size - KEEP
        while (keepFrom < turns.size - 1 && !turns[keepFrom].fromUser) keepFrom++
        val first = turns.first().let { if (it.text.length > 4_000) it.copy(text = it.text.take(3_500) + "\n…\n" + it.text.takeLast(400)) else it }
        val old = turns.subList(1, keepFrom)
        val p = prefs(c)
        var summary = p.getString("$chat|sum", "").orEmpty()
        var upto = p.getInt("$chat|upto", 0)
        // The chat was cleared or edited (the first old message differs): start the memory again.
        val sig = old.firstOrNull()?.text?.take(200).hashCode()
        if (upto > old.size || p.getInt("$chat|sig", sig) != sig) { summary = ""; upto = 0 }
        if (old.size - upto >= BATCH || (summary.isBlank() && old.isNotEmpty())) {
            val fresh = old.subList(upto, old.size).joinToString("\n\n") { t ->
                (if (t.fromUser) "OWNER: " else "ARIN: ") + t.text.replace(Regex("(?s)<think>.*?</think>"), "").trim().let {
                    if (it.length > 3_000) it.take(2_400) + "\n…\n" + it.takeLast(500) else it
                }
            }
            val updated = runCatching {
                summarize(
                    "You keep the MEMORY of a long chat between the owner and Arin (an AI assistant that builds apps). " +
                        "Rewrite the MEMORY so it also covers the NEW MESSAGES. Keep EVERY request and requirement (exactly as " +
                        "asked), decision, app and file name, number, version, link, setting, preference, error and unfinished " +
                        "task, and what was done about each. Drop greetings and chit-chat. Short bullet points grouped by topic, " +
                        "newest state last; at most about 700 words. Reply with the MEMORY only.\n\n" +
                        "MEMORY SO FAR:\n" + summary.ifBlank { "(empty)" } + "\n\nNEW MESSAGES:\n" + fresh,
                ).trim()
            }.getOrDefault("")
            if (updated.length > 40) {
                summary = updated.take(8_000)
                upto = old.size
                p.edit().putString("$chat|sum", summary).putInt("$chat|upto", upto).putInt("$chat|sig", sig).apply()
            }
        }
        // Old turns not in the memory yet (a summary failed or the batch isn't full): sent short.
        val pending = old.subList(minOf(upto, old.size), old.size).joinToString("\n") { t ->
            (if (t.fromUser) "OWNER: " else "ARIN: ") + t.text.let { if (it.length > 700) it.take(550) + " …" + it.takeLast(120) else it }
        }
        val recall = recall(old.subList(0, minOf(upto, old.size)), latest)
        // ARIN-PROTECTED: requirement-coverage — a request of the owner's that the summary dropped is kept word for word.
        val dropped = if (summary.isBlank()) emptyList() else Requirements.uncovered(
            old.subList(0, minOf(upto, old.size)).filter { it.fromUser }.flatMap { Requirements.lines(it.text) }, summary,
        ).takeLast(12)
        val memo = buildString {
            append("[Memory of this chat, kept by Arin — everything said before the latest messages. Use it; ask if something isn't here.]\n")
            append(summary.ifBlank { "(not written yet)" })
            if (dropped.isNotEmpty()) append("\n\n[Owner's requests the summary left out, word for word — still required:]\n")
                .append(dropped.joinToString("\n") { "- " + it.take(400) })
            if (pending.isNotBlank()) append("\n\n[Older messages not yet in the memory:]\n").append(pending)
            if (recall.isNotBlank()) append("\n\n[Earlier messages that match the owner's new message, word for word:]\n").append(recall)
        }
        return listOf(first, Turn(false, memo)) + turns.subList(keepFrom, turns.size)
    }

    /** Up to 3 older messages sharing the most words with [latest] (word for word, cut around the match). */
    private fun recall(old: List<Turn>, latest: String): String {
        val words = Regex("[\\p{L}\\p{N}_.-]{4,}").findAll(latest.lowercase()).map { it.value.trim('.', '-') }
            .filter { it.length >= 4 && it !in common }.toSet()
        if (words.isEmpty()) return ""
        return old.map { t -> t to words.count { w -> t.text.contains(w, ignoreCase = true) } }
            .filter { it.second >= 2 || (words.size == 1 && it.second == 1) }
            .sortedByDescending { it.second }.take(3)
            .joinToString("\n") { (t, _) ->
                val txt = t.text
                val at = words.mapNotNull { w -> txt.indexOf(w, ignoreCase = true).takeIf { it >= 0 } }.minOrNull() ?: 0
                val from = maxOf(0, at - 300)
                (if (t.fromUser) "OWNER: " else "ARIN: ") + (if (from > 0) "…" else "") + txt.substring(from, minOf(txt.length, from + 900)) +
                    (if (from + 900 < txt.length) "…" else "")
            }
    }

    private val common = setOf(
        "this", "that", "with", "from", "have", "what", "when", "will", "your", "make", "want", "please", "also", "they",
        "them", "then", "there", "their", "about", "should", "would", "could", "into", "just", "like", "some", "more", "only",
        "need", "does", "done", "here", "were", "been", "being", "which", "while", "after", "before", "again", "the app", "arin",
    )
}
