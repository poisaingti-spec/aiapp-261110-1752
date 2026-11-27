package com.arin.claudecode

import android.content.Context

/**
 * Claude's usage limit ("You've hit your 5-hour limit · resets 6:20am"). It is NOT a build error: it is never searched on
 * the internet or "fixed". Arin switches to another Claude account that still has credit; if none has, Claude is skipped
 * (workers and web research carry on) until the limit resets.
 */
object ClaudeLimit {
    private val limitText = Regex("""(?i)(hit your .{0,20}limit|usage limit|limit reached|rate.?limit|out of (extra )?usage|credit balance is too low|resets? (at )?\d)""")

    fun isLimit(text: String?): Boolean = text != null && limitText.containsMatchIn(text) && text.contains("claude", true).or(text.contains("limit", true))

    /** account id → time until which it is out of credit. */
    private val limited = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun isLimited(account: String?): Boolean = (limited[account ?: "main"] ?: 0L) > System.currentTimeMillis()

    /** True when no Claude account can answer right now. */
    fun allLimited(c: Context): Boolean {
        val ids = ClaudeAccounts.list(c).map { it.id }
        return if (ids.isEmpty()) isLimited("main") else ids.all { isLimited(it) }
    }

    /**
     * Records the limit for [account] and switches to another account that still has credit.
     * Returns a plain note for the steps.
     */
    fun hit(c: Context, account: String?, message: String): String {
        val acc = account ?: ClaudeAccounts.active(c) ?: "main"
        limited[acc] = System.currentTimeMillis() + 5 * 3_600_000L
        val resets = Regex("""(?i)resets?\s+([^·\n]{2,40})""").find(message)?.groupValues?.get(1)?.trim()
        val other = ClaudeAccounts.list(c).firstOrNull { it.id != acc && !isLimited(it.id) }
        return if (other != null) {
            ClaudeAccounts.setActive(c, other.id)
            com.arin.builder.LiveFeed.add("Arin", "Claude account \"${ClaudeAccounts.name(c, acc)}\" hit its limit — switched to \"${other.name}\"")
            "Claude's limit was reached on \"${ClaudeAccounts.name(c, acc)}\"" + (resets?.let { " (resets $it)" } ?: "") +
                " — switched to your other account \"${other.name}\" and carrying on"
        } else {
            "Claude's usage limit is reached" + (resets?.let { " (resets $it)" } ?: "") +
                " and no other Claude account has credit — carrying on without Claude (worker and web research)"
        }
    }
}
