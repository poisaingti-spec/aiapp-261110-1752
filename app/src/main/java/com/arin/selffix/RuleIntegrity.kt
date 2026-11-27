package com.arin.selffix

import android.content.Context

/**
 * ARIN-PROTECTED: rule-integrity — the exact instruction texts the three models get at runtime (main model, middle
 * model/worker, helper, plus the build and review instructions), each with a SHA-256 fingerprint. The owner-approved
 * fingerprints are in assets/instruction-baseline.txt:
 *  - a unit test fails the build if any instruction text changed, disappeared or was replaced (the marker baseline in
 *    .github only proves a rule's comment is still there; this proves the words the models actually receive);
 *  - Arin checks the same at start-up and writes a warning to Logs if the instructions in the running app differ.
 * Only the owner updates the baseline (the failing test prints the new fingerprints to approve).
 */
object RuleIntegrity {
    /** Fixed sample values for the few instructions that include a name or a number. */
    fun texts(c: Context? = null): Map<String, String> = linkedMapOf(
        "main.system" to mainSystem(c),
        "main.owner-rules" to TaskSummary.RULE,
        "main.worker-mode" to Worker.instructionsText("WORKERS"),
        "main.helper-use" to ClaudeHelper.instructionsText("HELPER", "", 3, delegate = false),
        "main.helper-credit-saver" to ClaudeHelper.DELEGATE,
        "middle.system" to Worker.SYSTEM,
        "middle.team-command" to TeamWork.COMMAND_SYSTEM,
        "middle.team-write" to TeamWork.WRITE_SYSTEM,
        "middle.team-digest" to TeamWork.DIGEST_SYSTEM,
        "helper.system" to ClaudeHelper.SYSTEM,
        "build.system" to com.arin.builder.ProjectTemplate.systemPrompt("com.example.app"),
        "review.boss-guidance" to listOf(Outcome.Kind.POLICY_OR_SAFETY_REFUSAL, Outcome.Kind.MISUNDERSTOOD_OBJECTIVE, Outcome.Kind.NETWORK_OR_PROVIDER_FAILURE)
            .joinToString("\n") { Outcome.forBoss("X", "Y", Outcome.Verdict(it, "R")) },
    )

    /**
     * The main model's fixed instructions. The parts that change with the date, the app version or this phone's
     * settings (today's date, the version, the helper/worker/team-work blocks — those are checked on their own above)
     * are taken out, so the same text is compared on the build server and on the phone.
     */
    private fun mainSystem(c: Context?): String {
        var t = com.arin.llm.Prompt.system(webSearch = true, memoryOn = false, memories = emptyList(), project = null, terminal = true)
        if (c != null) listOf(
            runCatching { ClaudeHelper.instructions(c) }.getOrDefault(""),
            runCatching { Worker.instructions(c) }.getOrDefault(""),
            runCatching { TeamWork.instructions(c) }.getOrDefault(""),
        ).filter { it.isNotEmpty() }.forEach { t = t.replace(it, "") }
        return t.replace(Regex("""This is Arin version .*?\. (?=Use Markdown)"""), "").replace(Regex("""Today is [^.]*\.\n\n"""), "")
    }

    fun sha256(t: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(t.toByteArray()).joinToString("") { "%02x".format(it) }

    fun digests(c: Context? = null): Map<String, String> = texts(c).mapValues { sha256(it.value) }

    /** "name sha256" lines. */
    fun parse(baseline: String): Map<String, String> = baseline.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        .associate { it.substringBefore(' ') to it.substringAfter(' ').trim() }

    /** What differs from the approved [baseline]: changed, missing, or new-but-unapproved instructions. */
    fun differences(baseline: Map<String, String>, now: Map<String, String>): List<String> =
        baseline.filter { (k, v) -> now[k] != v }.map { (k, _) -> if (k in now) "$k changed" else "$k missing" } +
            now.keys.filter { it !in baseline }.map { "$it not approved yet" }

    /** Start-up check: a warning in Logs if the running app's instructions aren't the approved ones. */
    fun checkApp(c: Context) {
        runCatching {
            val base = parse(c.assets.open("instruction-baseline.txt").bufferedReader().readText())
            val diff = differences(base, digests(c))
            if (diff.isNotEmpty()) Activity.log("chat", "⚠️ Protected model instructions differ from the owner-approved ones: " + diff.joinToString("; "))
        }.onFailure { Activity.log("chat", "⚠️ Couldn't check the protected model instructions: ${it.message?.take(120)}") }
    }
}
