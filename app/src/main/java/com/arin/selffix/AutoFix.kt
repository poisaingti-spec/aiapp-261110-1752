package com.arin.selffix

// ARIN-PROTECTED-RULE: research-first-autofix
// ─────────────────────────────────────────────────────────────────────────────────────────────
// PROTECTED RULE (set by the owner). Self-updates, models and other tools must NOT remove,
// weaken or bypass this file or any line marked "ARIN-PROTECTED". Only the owner can change it,
// by unlocking it in the Arin Update tab. See PROTECTED_RULES.md.
//
//   1. On ANY error, Arin first searches the internet for the solution (several searches, and it
//      reads the best pages), and only then applies a fix.
//   2. It may download any tool or file it needs for the fix.
//   3. It keeps going (search → fix → try again) until the error is fixed. Only the Stop button
//      ends it early.
//   4. It never changes the person's Custom API (address, key or model) on its own.
// ─────────────────────────────────────────────────────────────────────────────────────────────

import com.arin.builder.WebTools

object AutoFix {
    /** ARIN-PROTECTED: fix rounds never run out. Only Stop ends the loop. */
    const val NO_LIMIT = 1_000_000

    /** ARIN-PROTECTED: the rule is always on. */
    const val UNTIL_FIXED = true

    /** "round 4", or "round 4 of 6" when there is a limit. */
    fun roundText(n: Int, max: Int): String = if (max >= NO_LIMIT) "round $n" else "round $n of $max"

    /** ARIN-PROTECTED: do exactly what was asked, and never give up — another method when one fails. */
    const val RULE_EXACT =
        "PROTECTED RULE (from the owner): do EXACTLY what the person asked — never change, drop, shrink or reinterpret " +
            "any part of the request. If one method doesn't work, research it and use a different method that gives the " +
            "SAME requested result, and keep going until the request is complete."

    /** ARIN-PROTECTED: in every chat's instructions. */
    const val RULE_GENERAL = RULE_EXACT + " " +
        "PROTECTED RULE (from the owner, cannot be changed): whenever any error happens (a command, install, tool, " +
            "build or code error), FIRST research the internet for the solution with <search>…</search> and read the best " +
            "page with <fetch>…</fetch>, THEN apply the fix, try again, and keep going (search → fix → retry) until the error " +
            "is fixed, without waiting for the person. Download any tool or file the fix needs. Never change the person's " +
            "Custom API (address, key or model)."

    /** Told to every model doing a fix, so it researches before it changes anything. */
    const val RULE_FOR_MODEL = RULE_EXACT + " " +
        "PROTECTED RULE (from the owner, cannot be changed): for every error, research the internet FIRST. The search " +
            "results and pages below were found for this error; read them, find the real cause and the fix others used, " +
            "and only then change the code. If you need more, ask for another search or page before fixing. If a tool, " +
            "library or file is needed, download it. Keep fixing until the error is gone. Never change the Custom API " +
            "address, key or model."

    /**
     * ARIN-PROTECTED: research the error on the internet before any fix: a few searches, then the best pages are read.
     * Returns the findings for the model (never blank: says so when nothing was found, so the model searches again).
     */
    suspend fun research(query: String, extra: String = "", onStep: (String) -> Unit = {}): String {
        val q = query.trim().ifBlank { extra.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty().take(160) }
        if (q.isBlank()) return "No error text to search for. Search the web yourself with <search>…</search> before fixing."
        // Credit saver: the same search is never done twice, and pages are cut to the paragraphs that matter.
        val app = com.arin.ArinApp.instance
        val saver = CreditSaver.on(app)
        val cacheKey = TabScope.current().let { if (it.startsWith("oneoff-")) "" else it } + "|" + q  // strict-tab-scope
        if (saver) CreditSaver.cachedResearch(cacheKey)?.let { onStep("Already researched this error — reusing it"); return it }
        val queries = linkedSetOf(q, "$q fix", "$q site:stackoverflow.com", "$q github issue").take(4)
        val out = StringBuilder()
        val urls = linkedSetOf<String>()
        for (one in queries) {
            onStep("Searching the internet: $one")
            val r = runCatching { WebTools.search(one) }.getOrNull().orEmpty()
            if (r.isBlank() || r.startsWith("No results")) continue
            out.append("Search: ").append(one).append('\n').append(r.take(2_500)).append("\n\n")
            Regex("""https?://[^\s)\]]+""").findAll(r).map { it.value }
                .filter { !it.contains("duckduckgo.com") }
                .forEach { urls += it }
            if (out.length > 7_000) break
        }
        // Read the best pages, not just the snippets.
        for (url in urls.take(2)) {
            onStep("Reading " + url.removePrefix("https://").removePrefix("http://").take(60))
            val page = runCatching { WebTools.fetch(url) }.getOrNull().orEmpty()
            if (page.isNotBlank() && !page.startsWith("Couldn't open")) {
                val body = if (saver) CreditSaver.shorten(app, q, page, 4) else page
                out.append("Page: ").append(url).append('\n').append(body.take(3_500)).append("\n\n")
            }
        }
        return if (out.isBlank()) "The internet search for \"$q\" found nothing. Search again with other words before fixing."
        else out.toString().take(if (saver) 7_000 else 14_000).also { if (saver) CreditSaver.cacheResearch(cacheKey, it) }
    }

    /** Remembers which errors keep coming back, so a repeat gets a new approach (and Claude's help). */
    class Repeats {
        private val seen = HashMap<String, Int>()
        /** How many times this error has been seen now (1 = first time). */
        fun count(error: String): Int {
            val key = error.lowercase().replace(Regex("""\d+"""), "").replace(Regex("""\s+"""), " ").trim().take(200)
            val n = (seen[key] ?: 0) + 1
            seen[key] = n
            return n
        }
    }
}
