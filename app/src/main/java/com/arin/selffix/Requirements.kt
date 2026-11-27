package com.arin.selffix

/**
 * ARIN-PROTECTED: requirement-coverage — checks (no AI) that every requirement the owner wrote is still there after it
 * was summarised (chat memory) or restated (the main model's brief for workers). A requirement counts as kept when most
 * of its meaningful words (or their word stems) appear; anything missing is put back or sent to the main model to add,
 * so no requirement silently disappears.
 */
object Requirements {
    private val stop = setOf(
        "the", "and", "for", "with", "that", "this", "from", "have", "what", "when", "will", "your", "make", "want", "please",
        "also", "they", "them", "then", "there", "their", "about", "should", "would", "could", "into", "just", "like", "some",
        "more", "only", "need", "does", "done", "here", "were", "been", "being", "which", "while", "after", "before", "again",
        "must", "app", "arin", "can", "you", "all", "it's", "its", "every", "each", "very", "really", "thing", "things", "able",
    )

    private fun stems(t: String): Set<String> = Regex("""[\p{L}\p{N}]{3,}""").findAll(t.lowercase())
        .map { it.value }.filter { it !in stop }.map { if (it.length > 6) it.take(6) else it }.toSet()

    /** The owner's requirement-like statements in [text]: list items and sentences of 4+ words. */
    fun lines(text: String): List<String> = text.lines()
        .flatMap { l -> if (Regex("""^\s*(\d+[.)]|[-*•])\s+""").containsMatchIn(l)) listOf(l) else l.split(Regex("""(?<=[.!?])\s+""")) }
        .map { it.trim().replace(Regex("""^(\d+[.)]|[-*•])\s+"""), "") }
        .filter { it.split(Regex("\\s+")).size >= 4 && stems(it).size >= 2 }
        .distinct()

    /** The requirements in [reqs] that [text] doesn't keep (fewer than [need] of their meaningful words are in it). */
    fun uncovered(reqs: List<String>, text: String, need: Double = 0.5): List<String> {
        val have = stems(text)
        return reqs.filter { r ->
            val s = stems(r)
            s.isNotEmpty() && s.count { it in have } < maxOf(1.0, s.size * need)
        }
    }
}
