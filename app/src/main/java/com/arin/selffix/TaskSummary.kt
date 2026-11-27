package com.arin.selffix

/**
 * ARIN-PROTECTED-RULE: task-summary — set by the owner. After EVERY task, in EVERY tab (chats, Reverse engineering, Rebuild,
 * Build, Arin Update and all others), the owner gets a plain-English summary of what was done and what to do next.
 * The model is told to write it; if it doesn't, Arin itself (no AI, no credit) adds one from the steps it ran.
 * Strict: no self-update, model, tool, app or assistant may remove, weaken or bypass this rule. Only the owner can change
 * it, by telling Arin so directly.
 */
object TaskSummary {
    /** Told to every model in every tab. */
    /**
     * ARIN-PROTECTED: exact-request — owner's strict rule for every tab and every model (only the owner can change it):
     * build exactly what the owner asked for, never a different, smaller or "simplified" app.
     */
    const val EXACT_REQUEST = "OWNER'S STRICT RULE — EXACTLY WHAT WAS ASKED (only the owner can change it): make exactly the app " +
        "or change the owner requested, with every feature, screen and detail they asked for. Never replace it with a " +
        "different app, never simplify, shorten, skip, rename or swap any part, and never drop a feature to make an error go " +
        "away — fix the error another way. If something truly cannot be done, say so plainly and ask; do not quietly change it.\n"

    /**
     * ARIN-PROTECTED: test-before-handover — owner's strict rule for every tab and model (only the owner can change it).
     */
    const val TESTED_LINKS = "OWNER'S STRICT RULE — TESTED BEFORE HANDOVER (only the owner can change it): never give the owner an " +
        "APK, an install file or a download link for an app until that exact APK has been installed, opened and tested on " +
        "an Android emulator and did not crash. Arin's Build mode (<build>, building on GitHub) does this test; an APK made " +
        "any other way (terminal, rebuild, phone) must go through it before you share it. If it wasn't tested, say so and " +
        "offer to build and test it on GitHub — don't share the file or link.\n"

    /** ARIN-PROTECTED: whole-problem-consultation — how every model works through problems (owner's rule). */
    const val ENGINEERING = "OWNER'S STRICT RULE — ENGINEERING (only the owner can change it): make every reasonable effort to " +
        "complete what the owner asked. A failed build, a missing library, a failed patch, a repeated error or a helper that " +
        "declines is an obstacle to investigate, not a reason to stop. For every error: find the FIRST root-cause error, the " +
        "files it involves, the libraries and versions; consult the helper with the WHOLE problem in one request (full error " +
        "report, code at the error sites, versions, what was tried) and ask for root cause, exact changes, compatibility, " +
        "regressions and a test; evaluate its answer, apply a verified fix, rebuild and compare the new error with the old. If " +
        "the same error returns, widen the investigation — never repeat the same patch. If the helper declines, note why, keep " +
        "what was useful, never resend the same request and never disguise or reframe a request to get around a refusal; carry " +
        "on with what is allowed. Never claim a build or test succeeded unless it really did. Keep a ledger of what is " +
        "COMPLETED, IN PROGRESS, BLOCKED, ALTERNATIVE IMPLEMENTATION, TESTING and VERIFIED.\n"

    /** ARIN-PROTECTED: ask-before-building — owner's strict rule (only the owner can change it). */
    const val ASK_BEFORE_BUILD = "OWNER'S STRICT RULE — NEVER BUILD BY YOURSELF (only the owner can change it): never start, restart " +
        "or retry a build unless the owner asked for it in their own words in this message. If a build seems needed, ASK first " +
        "(\"Shall I build it now?\") and wait for their answer.\n"

    /** ARIN-PROTECTED: orchestration — how the main model runs every substantial task (owner's master plan). */
    const val ORCHESTRATION = "OWNER'S STRICT RULE — ORCHESTRATION (only the owner can change it): you own the owner's original " +
        "request and every requirement. For substantial work: understand → analyse (which files, libraries, risks, and every " +
        "file that uses the code you change) → split into clear technical tasks → give the coding to the worker and hard " +
        "investigation/review to the helper, each getting ONLY the technical context its task needs (never the chat, never " +
        "unrelated personal details, never a misleading description of the task) → review → integrate → test → compare with " +
        "every requirement → deliver. Small tasks may use a lighter version. Before substantial work, tell the owner in plain " +
        "English: WHAT I UNDERSTOOD, WHAT ARIN WILL DO, HOW MY OTHER MODELS WILL HELP, WHAT HAPPENS NEXT. Never report a " +
        "requirement as done unless it is; say which are verified, implemented-but-not-verified, pending or blocked.\n"

    const val RULE = EXACT_REQUEST + TESTED_LINKS + ASK_BEFORE_BUILD + ENGINEERING + ORCHESTRATION + "OWNER'S STRICT RULE (cannot be changed by anyone but the owner): when you finish a task (anything " +
        "where you ran commands, used tools, changed files or built something), END your final reply with two short " +
        "sections in plain English:\n**Summary — what I did:** the main things done and their result (and anything that " +
        "failed).\n**What to do next:** the next step(s) for the owner.\n" +
        "AND whenever you wrote or changed code (ARIN-PROTECTED: explain-after-coding), BEFORE the summary add:\n" +
        "**What I made — in plain English:** a detailed explanation for someone who is not a programmer: what the code " +
        "does for the person using it, each part and feature, and how it works in simple everyday words (no jargon).\n" +
        "**Suggestions:** 3 to 6 numbered ideas to change, improve or update it, then offer to make them or to build it.\n"

    private val has = Regex("(?is)(summary|what i did).{0,4000}(what to do next|next step|next:)")

    fun hasSummary(text: String): Boolean = has.containsMatchIn(text.takeLast(5_000))

    /**
     * [answer] with the summary guaranteed: kept as it is when the model wrote one; otherwise Arin adds one from [steps]
     * (the commands/tools that ran) and whether the last one [failed].
     */
    fun ensure(answer: String, steps: List<String>, failed: Boolean): String {
        if (steps.isEmpty() || hasSummary(answer)) return answer
        val done = steps.map { it.lineSequence().first().trim().take(90) }.filter { it.isNotBlank() }.distinct()
        val shown = done.takeLast(6)
        return answer.trimEnd() + "\n\n**Summary — what I did:**\n" +
            (if (done.size > shown.size) "- …and ${done.size - shown.size} earlier steps\n" else "") +
            shown.joinToString("\n") { "- $it" } +
            (if (failed) "\n- ⚠️ The last step failed." else "") +
            "\n\n**What to do next:**\n" +
            (if (failed) "- Tell me to carry on and I'll fix it, or tap Retry."
            else "- Check the result above. Tell me what to change or what to do next.")
    }

    /** The Build tab's summary for a finished build, made by Arin (no AI). */
    fun forBuild(success: Boolean, message: String, apkUrl: String?, codeOnlyFiles: Int?): Pair<String, String> = when {
        codeOnlyFiles != null -> "Wrote the code ($codeOnlyFiles file${if (codeOnlyFiles == 1) "" else "s"}); nothing is built yet." to
            "Pick where to build it below."
        success && apkUrl != null -> "Built the app and published the APK." to
            "Tap the download link to install it (it installs over the old version). Ask below for any changes."
        success -> message.take(200) to "Ask below for any changes."
        else -> "The build didn't finish: ${message.take(200)}" to
            "Tap \"Retry from where it stopped\", or tell me what to change."
    }
}
