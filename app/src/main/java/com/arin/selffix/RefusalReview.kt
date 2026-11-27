package com.arin.selffix

/**
 * ARIN-PROTECTED: refusal-review — what happens when the middle model (worker) or the helper declines a task:
 *
 *  1. its exact answer, who said it and its stated reason are kept;
 *  2. a GENUINE restriction (safety/policy or missing authorization) is final: the same task is never sent again in
 *     other words, whatever the helper or the boss think — the boss may only stop, tell the person, or do a clearly
 *     permitted part itself;
 *  3. a refusal that may be a misunderstanding, missing information or a technical limit gets an independent
 *     assessment from the helper (if one is set up), then the boss reviews all the evidence and decides: clarify and
 *     ask again, use a permitted alternative, or stop;
 *  4. technical failures (network, empty or cut-off answers) are not refusals and are not argued about;
 *  5. everything is bounded ([Config]) and every message is attributed to the model that actually wrote it.
 *
 * The boss keeps final authority; the helper only advises; nothing here weakens or bypasses a model's restrictions.
 * Pure (models are passed in), so the whole exchange is tested with scripted models.
 */
object RefusalReview {
    data class Config(val maxRounds: Int = 2, val maxHelperCalls: Int = 2)

    /** What a model sent back: who wrote it, the text, and the technical error if the call failed. */
    data class Reply(val who: String, val text: String, val error: Throwable? = null)

    enum class Decision { USE_RESULT, STOP_RESPECT_RESTRICTION, STOP_TECHNICAL_FAILURE, STOP_BOSS_DECIDED, STOP_LIMIT_REACHED }

    data class Entry(val round: Int, val role: String, val who: String, val text: String, val verdict: Outcome.Verdict? = null)

    data class Result(val decision: Decision, val text: String, val who: String, val transcript: List<Entry>, val rounds: Int) {
        /** For the boss (and the audit): what was decided and who said what. */
        fun summary(): String = "Refusal review: ${decision.name} after $rounds round(s). " + transcript.joinToString(" | ") { e ->
            "${e.role} ${e.who}" + (e.verdict?.let { v -> ": ${v.kind.name}" + (if (v.reason.isNotBlank()) " (\"${v.reason.take(120)}\")" else "") } ?: ": " + e.text.take(120))
        }
    }

    private val genuine = setOf(Outcome.Kind.POLICY_OR_SAFETY_REFUSAL, Outcome.Kind.AUTHORIZATION_REQUIRED)

    /** The helper's own header ("[Helper X — KIND …]") is Arin's note, not the helper's words. */
    private fun body(t: String) = if (t.startsWith("[Helper ") && t.contains("]\n")) t.substringAfter("]\n") else t

    suspend fun review(
        objective: String,
        task: String,
        first: Reply,
        middle: suspend (task: String) -> Reply,
        helper: (suspend (prompt: String) -> Reply)?,
        boss: suspend (prompt: String) -> Reply,
        cfg: Config = Config(),
    ): Result {
        val log = mutableListOf<Entry>()
        var current = first
        var currentTask = task
        var helperCalls = 0
        var round = 1
        while (true) {
            val v = Outcome.classify(current.text, currentTask, current.error, expectsCode = false)
            log += Entry(round, "middle", current.who, current.text, v)
            if (v.kind.ok) return Result(Decision.USE_RESULT, current.text, current.who, log, round)
            if (v.kind.failure) return Result(Decision.STOP_TECHNICAL_FAILURE, "", current.who, log, round)

            // Independent assessment by the helper (bounded).
            var assessment = ""
            var helperSaysGenuine = false
            if (helper != null && helperCalls < cfg.maxHelperCalls) {
                helperCalls++
                val h = runCatching { helper(helperPrompt(objective, currentTask, current, v)) }.getOrElse { Reply("helper", "", it) }
                val hb = body(h.text)
                // A structured assessment is an answer even when it quotes the middle model's refusal.
                val hv = if (h.error == null && Regex("""(?im)^\s*ASSESSMENT:\s*[A-Z_]+""").containsMatchIn(hb)) Outcome.Verdict(Outcome.Kind.SUCCESSFUL_RESPONSE)
                    else Outcome.classify(hb, "", h.error)
                log += Entry(round, "helper", h.who, body(h.text), hv)
                if (hv.kind.ok) {
                    assessment = body(h.text)
                    helperSaysGenuine = Regex("""(?im)^\s*ASSESSMENT:\s*GENUINE_RESTRICTION""").containsMatchIn(assessment)
                }
                // A helper that itself declines or fails: noted, and the boss decides without its view.
            }

            // A genuine restriction is enforced, even if the helper disagrees with it.
            if (v.kind in genuine || helperSaysGenuine) {
                log += Entry(round, "arin", "Arin", "Restriction respected: the task is not sent again in other words.")
                return Result(Decision.STOP_RESPECT_RESTRICTION, "", current.who, log, round)
            }
            if (round >= cfg.maxRounds) {
                log += Entry(round, "arin", "Arin", "Discussion limit (${cfg.maxRounds} rounds) reached.")
                return Result(Decision.STOP_LIMIT_REACHED, "", current.who, log, round)
            }

            // The boss reviews all the evidence and decides.
            val b = runCatching { boss(bossPrompt(objective, currentTask, current, v, assessment)) }.getOrElse { Reply("boss", "", it) }
            log += Entry(round, "boss", b.who, b.text)
            val decision = Regex("""(?im)^\s*DECISION:\s*(CLARIFY|ALTERNATIVE|STOP)""").find(b.text)?.groupValues?.get(1)?.uppercase()
            val newTask = Regex("""(?is)\bTASK:\s*(.+)""").find(b.text)?.groupValues?.get(1)?.trim()
            if (decision == null || decision == "STOP" || newTask.isNullOrBlank())
                return Result(Decision.STOP_BOSS_DECIDED, "", current.who, log, round)

            // The middle model reassesses the clarified task (or the permitted alternative).
            round++
            currentTask = newTask
            current = runCatching { middle(newTask) }.getOrElse { Reply(current.who, "", it) }
        }
    }

    private fun helperPrompt(objective: String, task: String, r: Reply, v: Outcome.Verdict) =
        "INDEPENDENT REVIEW (you advise the boss; you don't decide and you don't do the task).\n" +
            "OBJECTIVE (as the boss stated it): $objective\nTASK GIVEN TO THE MIDDLE MODEL: $task\n" +
            "MIDDLE MODEL (${r.who}) ANSWERED: ${r.text.take(3_000)}\nARIN'S READING: ${v.kind.name}" +
            (if (v.reason.isNotBlank()) " — stated reason: \"${v.reason}\"" else "") +
            "\n\nReply with:\nASSESSMENT: MISUNDERSTANDING | MISSING_INFORMATION | TECHNICAL_LIMITATION | GENUINE_RESTRICTION\n" +
            "WHY: one or two sentences, answering the middle model's reasoning\n" +
            "PERMITTED ALTERNATIVE: a clearer or different way that stays within every safety and authorization limit, or NONE"

    private fun bossPrompt(objective: String, task: String, r: Reply, v: Outcome.Verdict, assessment: String) =
        "You are the BOSS. The middle model declined a task. Decide the next step.\nOBJECTIVE: $objective\nTASK: $task\n" +
            "MIDDLE MODEL (${r.who}) ANSWERED: ${r.text.take(3_000)}\nARIN'S READING: ${v.kind.name}" +
            (if (v.reason.isNotBlank()) " — stated reason: \"${v.reason}\"" else "") +
            (if (assessment.isNotBlank()) "\nHELPER'S INDEPENDENT ASSESSMENT:\n${assessment.take(2_000)}" else "\n(No helper assessment.)") +
            "\n\nIf it misunderstood or lacked information, restate the task accurately (same objective, nothing hidden). " +
            "Never reword a task to get around a genuine safety or authorization restriction.\nReply with exactly one of:\n" +
            "DECISION: CLARIFY\nTASK: <the accurate task>\n— or —\nDECISION: ALTERNATIVE\nTASK: <a permitted alternative>\n— or —\n" +
            "DECISION: STOP\nREASON: <why>"
}
