package com.arin.selffix

/**
 * ARIN-PROTECTED: outcome-classes — what a supporting model's answer actually was, so a refusal is never counted as
 * finished work and a technical failure is never mistaken for a refusal. The verdict looks at the answer in context,
 * not at one phrase: where a refusal sentence sits (opening vs. a side note), whether the work that was asked for is
 * there (code, files, a real answer), whether it is asking questions back, and what went wrong technically. The main
 * model (the boss) is told the verdict with who said it and why, and decides the next step — never a jailbreak,
 * forced compliance or reworded request to get around a genuine refusal.
 */
object Outcome {
    enum class Kind {
        SUCCESSFUL_RESPONSE, POLICY_OR_SAFETY_REFUSAL, AUTHORIZATION_REQUIRED, MISUNDERSTOOD_OBJECTIVE, INSUFFICIENT_INFORMATION,
        TECHNICAL_LIMITATION, TOOL_EXECUTION_FAILURE, NETWORK_OR_PROVIDER_FAILURE, INCOMPLETE_RESPONSE, UNCLASSIFIED_REFUSAL;

        val refusal get() = this in setOf(POLICY_OR_SAFETY_REFUSAL, AUTHORIZATION_REQUIRED, MISUNDERSTOOD_OBJECTIVE,
            INSUFFICIENT_INFORMATION, TECHNICAL_LIMITATION, UNCLASSIFIED_REFUSAL)
        val failure get() = this in setOf(TOOL_EXECUTION_FAILURE, NETWORK_OR_PROVIDER_FAILURE, INCOMPLETE_RESPONSE)
        val ok get() = this == SUCCESSFUL_RESPONSE
    }

    /** [reason]: the model's own stated reason (one sentence), or the technical error. [partial]: work done, part declined. */
    data class Verdict(val kind: Kind, val reason: String = "", val partial: Boolean = false)

    private val declines = Regex(
        """(?i)(\bI\s+(?:can(?:no|['’])?t|cannot|won['’]t|will not|must decline|have to decline|need to decline|would rather not|don['’]t feel comfortable)\b|\bI(?:['’]m|\s+am)\s+(?:not able|unable|not going|not willing|not comfortable)\b|\bI['’]d rather not\b|\bI['’]m sorry,? but\b|\bunfortunately,? I\b|\bnot something I can (?:help|do|assist)\b)""",
    )
    private val policy = Regex("""(?i)(polic(?:y|ies)|guidelines|harm(?:ful)?|illegal|unlawful|unsafe|danger|malicious|unethical|ethic|abuse|exploit|attack|weapon|privacy of|without (?:their|the owner['’]s) (?:consent|permission)|not appropriate|inappropriate|terms of (?:use|service))""")
    private val authorization = Regex("""(?i)(authori[sz](?:ation|ed|e)|permission|consent|you own|ownership|written approval|proof that|verify (?:that )?you)""")
    private val technical = Regex("""(?i)((?:don['’]t|do not|can(?:no|['’])?t|cannot|unable to)\s+(?:have\s+)?(?:access|browse|open|run|execute|see|view|reach|download|connect|use (?:the|a) (?:internet|terminal|tool))|no (?:internet|network|tool|terminal) access|outside (?:of )?my (?:capabilities|abilities)|as a (?:language|text)(?:-based)? model|knowledge cut-?off|context (?:window|length)|too (?:long|large) for me)""")
    private val misunderstood = Regex("""(?i)(did you mean|do you mean|if you meant|it sounds like you(?:['’]re| are)? (?:want|asking)|I(?:['’]m| am) not sure what you (?:mean|want)|unclear (?:what|which)|could you clarify what)""")
    private val asksBack = Regex("""(?i)(could you (?:please )?(?:provide|share|tell|clarify|specify|send|confirm)|can you (?:please )?(?:provide|share|tell|clarify|specify|send|confirm)|please (?:provide|share|specify|clarify|send|confirm)|I need (?:more|the|a few|some) (?:details|information|context|files?|code)|which (?:one|file|version|project) (?:do|should|would))""")
    private val netError = Regex("""(?i)(couldn['’]t be reached|timed? ?out|timeout|connection (?:reset|refused|closed)|unable to resolve host|network|HTTP (?:4(?:08|29)|5\d\d)|\b(?:429|500|502|503|504)\b|rate.?limit|overloaded|quota|usage limit|5-hour limit|credit|insufficient balance|unauthori[sz]ed|invalid (?:api )?key|401|403)""")

    private fun sentences(t: String) = t.split(Regex("""(?<=[.!?])\s+|\n+""")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Is the requested work there? Code, file blocks, edits, or a substantial answer beyond the refusal sentence. */
    private fun hasDeliverable(t: String, expectsCode: Boolean): Boolean {
        if (Regex("""(?m)^=== (FILE|EDIT):|```|<(?:run|tool|ask_worker|ask_helper|piece)>""").containsMatchIn(t)) return true
        if (expectsCode) return Regex("""(?m)^\s*(fun |val |var |class |object |import |package |def |<\w+[ >])""").containsMatchIn(t)
        val rest = sentences(t).filterNot { declines.containsMatchIn(it) }.joinToString(" ")
        return rest.length > 600
    }

    /**
     * Classifies [response] (what came back) for [task] (what was asked; used for context only). [error]: the
     * technical error if the call itself failed. [expectsCode]: the task asked for code or files.
     */
    fun classify(response: String, task: String = "", error: Throwable? = null, expectsCode: Boolean = false): Verdict {
        if (error != null) {
            val msg = (error.message ?: error.javaClass.simpleName).take(300)
            return Verdict(if (netError.containsMatchIn(msg) || error is java.io.IOException) Kind.NETWORK_OR_PROVIDER_FAILURE else Kind.TOOL_EXECUTION_FAILURE, msg)
        }
        val t = response.trim()
        if (t.isEmpty()) return Verdict(Kind.INCOMPLETE_RESPONSE, "the answer was empty")
        // Arin's own markers for an unreachable helper / empty answer: technical, not a refusal.
        if (Regex("""(?i)^\S+ (couldn['’]t be reached|sent an empty answer|isn['’]t connected|is switched off)""").containsMatchIn(t))
            return Verdict(Kind.NETWORK_OR_PROVIDER_FAILURE, t.take(200))
        // An answer cut off in the middle of a file or a code block.
        if (t.lines().count { it.trimStart().startsWith("```") } % 2 == 1 ||
            Regex("""(?m)^=== FILE:""").findAll(t).count() > Regex("""(?m)^=== END FILE""").findAll(t).count())
            return Verdict(Kind.INCOMPLETE_RESPONSE, "the answer stops in the middle of a file or code block")

        val sents = sentences(t)
        val opening = sents.take(3)
        val refusalLine = opening.firstOrNull { declines.containsMatchIn(it) } ?: sents.firstOrNull { declines.containsMatchIn(it) }
        val deliverable = hasDeliverable(t, expectsCode || Regex("""(?i)\b(code|file|kotlin|fix|implement|write|build)\b""").containsMatchIn(task.take(2_000)))
        val questions = sents.takeLast(4).any { it.endsWith("?") && asksBack.containsMatchIn(it) } || (t.length < 900 && asksBack.containsMatchIn(t) && t.trimEnd().endsWith("?"))

        if (refusalLine != null && !deliverable) {
            val near = (listOf(refusalLine) + sents.dropWhile { it != refusalLine }.drop(1).take(2)).joinToString(" ")
            val kind = when {
                misunderstood.containsMatchIn(t) -> Kind.MISUNDERSTOOD_OBJECTIVE
                authorization.containsMatchIn(near) && !policy.containsMatchIn(refusalLine) -> Kind.AUTHORIZATION_REQUIRED
                policy.containsMatchIn(near) -> Kind.POLICY_OR_SAFETY_REFUSAL
                technical.containsMatchIn(near) -> Kind.TECHNICAL_LIMITATION
                questions -> Kind.INSUFFICIENT_INFORMATION
                else -> Kind.UNCLASSIFIED_REFUSAL
            }
            return Verdict(kind, near.take(300))
        }
        if (!deliverable && questions && t.length < 1_500) {
            return Verdict(if (misunderstood.containsMatchIn(t)) Kind.MISUNDERSTOOD_OBJECTIVE else Kind.INSUFFICIENT_INFORMATION,
                sents.lastOrNull { it.endsWith("?") }.orEmpty().take(300))
        }
        // Work is there; a declined side part is noted, not hidden.
        return Verdict(Kind.SUCCESSFUL_RESPONSE, refusalLine?.take(300).orEmpty(), partial = refusalLine != null)
    }

    /** A line the main model reads: who answered, what kind of answer it was, and the stated reason. */
    fun forBoss(role: String, who: String, v: Verdict): String = when {
        v.kind.ok && !v.partial -> ""
        v.kind.ok -> "[$role $who did the work but declined a part: \"${v.reason}\"]"
        else -> "[$role $who — ${v.kind.name}${if (v.reason.isNotBlank()) ": \"${v.reason}\"" else ""}. You (the main model) decide the next step: " +
            when {
                v.kind == Kind.MISUNDERSTOOD_OBJECTIVE || v.kind == Kind.INSUFFICIENT_INFORMATION -> "restate the task accurately with what it needs."
                v.kind == Kind.POLICY_OR_SAFETY_REFUSAL || v.kind == Kind.AUTHORIZATION_REQUIRED -> "respect it; don't reword the task to get around it — do a permitted part yourself, or tell the person why."
                v.kind.failure -> "this was a technical failure, not a refusal; another model or a retry may work."
                else -> "check what it said before deciding."
            } + "]"
    }
}
