package com.arin.selffix

// ARIN-PROTECTED-RULE: research-first-error-help
// PROTECTED RULE (from the owner): in EVERY tab, when an error happens, Arin first searches the internet for the
// solution, then fixes it by itself when it can; when the person's help is needed, it tells them in plain English
// exactly how to fix it (numbered steps). Only the owner can change this (see PROTECTED_RULES.md).

import android.content.Context
import com.arin.builder.ChatMessage
import com.arin.builder.Role
import com.arin.builder.THOUGHT
import com.arin.builder.streamModel
import com.arin.llm.Provider

object ErrorDoctor {
    /** The model to explain with: the tab's own (never switched), else the AI helper, else research alone. */
    data class Brain(val provider: Provider, val key: String, val model: String, val endpoint: com.arin.llm.CustomApi.Endpoint?)

    /** The error's key line, cleaned for a web search (no stack trace, paths or numbers). */
    fun query(error: String, where: String): String {
        val line = error.lines().map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("at ") && !it.startsWith("Why it failed") && !it.startsWith("⚠") }
            ?: error.take(160)
        val msg = line.substringAfterLast("Exception: ").ifBlank { line }
            .replace(Regex("""(/[\w.@+-]+){2,}"""), " ").replace(Regex("""\b\d{5,}\b"""), " ")
            .replace(Regex("""\s+"""), " ").trim().take(150)
        val topic = when {
            msg.contains("GitHub", true) -> "GitHub API"
            where.contains("build", true) -> "Android Gradle"
            else -> ""
        }
        return (if (topic.isNotBlank() && !msg.contains(topic, true)) "$topic " else "") + msg
    }

    /**
     * ARIN-PROTECTED: researches the error on the internet FIRST, then says in plain English what went wrong, what Arin
     * does about it, and — when the person must act — exactly how to fix it. Never blank.
     */
    suspend fun diagnose(context: Context, where: String, error: String, brain: Brain?, onStep: (String) -> Unit = {}): String {
        // Claude's usage limit is not a bug: nothing to search or fix.
        if (error.contains("CLAUDE_LIMIT") || com.arin.claudecode.ClaudeLimit.isLimit(error)) {
            return "**What happened:** " + error.substringAfter("CLAUDE_LIMIT: ").take(300) + "\n\n**Can Arin fix it by itself?** Yes — " +
                "it uses your other Claude account if it has credit, otherwise it carries on without Claude (the worker and web research)."
        }
        val q = query(error, where)
        val research = AutoFix.research(q, error, onStep)
        val ask = "An error happened in the Arin Android app ($where).\n\nError:\n${error.take(3_000)}\n\n" +
            "What I found on the internet about it:\n${research.take(9_000)}\n\n" +
            "Reply in plain, simple English for a non-programmer, short, in this form:\n" +
            "What went wrong: one sentence.\n" +
            "Can Arin fix it by itself? Yes or No, and what it will do.\n" +
            "If the person must do something: 'How to fix it:' then numbered steps (where to tap in Arin, e.g. " +
            "Settings → GitHub, or on the website). No code, no stack traces. Never tell them to change their Custom API " +
            "unless the error is clearly that API's key or address. Arin fixes these by ITSELF (say Yes): the model writing a " +
            "broken or invalid tool/function call, a conversation that is too long, a busy or overloaded service, a dropped " +
            "connection — it retries with a shorter conversation and plain tool tags, and its AI helper can do the step. " +
            "Never tell the person to start a new chat, delete messages or switch model for those."
        val system = "You explain app errors to non-programmers, clearly and kindly, using the research given."
        onStep("Working out how to fix it")
        // 1. The tab's own model (the same API and model: never switched).
        if (brain != null && !CreditSaver.on(context)) {
            val text = runCatching {
                val sb = StringBuilder()
                streamModel(brain.provider, brain.key, brain.model, listOf(ChatMessage(Role.USER, ask)), system, brain.endpoint)
                    .collect { if (!it.startsWith(THOUGHT)) sb.append(it) }
                sb.toString().trim()
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        // 2. The AI helper, when it's on.
        // Only when the helper is switched on in the tab the error came from.
        val tab = when {
            where.contains("Arin Update", true) || where.contains("updating Arin", true) -> "arin-update"
            where.contains("Claude Code", true) -> "claude-code"
            where.contains("Build", true) -> "build"
            where.contains("Movie", true) -> "movie"
            else -> "chat"
        }
        if (ClaudeHelper.route(context, tab) != null) {
            val text = runCatching { ClaudeHelper.ask(context, ask, source = tab) }.getOrNull()
            if (!text.isNullOrBlank() && !text.contains("couldn't be reached")) return text
        }
        // 3. The research itself, in short.
        val links = Regex("""https?://[^\s)\]]+""").findAll(research).map { it.value }.filter { !it.contains("duckduckgo") }.distinct().take(3).toList()
        return "What went wrong: " + PlainSummary.plainError(error) +
            "\nI searched the internet for \"$q\" but couldn't reach a model to explain it." +
            (if (links.isNotEmpty()) "\nPages that explain it:\n" + links.joinToString("\n") { "• $it" } else "")
    }
}
