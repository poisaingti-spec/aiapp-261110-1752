package com.arin.selffix

import java.io.File

/**
 * ARIN-PROTECTED: orchestrator — the Boss's decision layer (no AI call, no extra credits). Before work starts it records
 * the owner's exact objective as an immutable task record with a requirement ledger (stable IDs R1, R2…), diagnoses the
 * task, picks the first approach most likely to succeed at the lowest sensible cost, sets an adaptive budget, escalates
 * only on evidence (a materially different approach, never the same one again), and accepts a delegated result only
 * when evidence backs it — a model saying "done" is never proof.
 */
object Orchestrator {
    enum class TaskType { QUESTION, SMALL_CHANGE, FEATURE, DEBUG, BUILD, RESEARCH, SECURITY_REVIEW, OTHER }
    enum class Approach { DIRECT, MIDDLE, MIDDLE_WITH_HELPER_REVIEW, HELPER_SPECIALIST, INVESTIGATE_FIRST }
    enum class ReqStatus { NOT_STARTED, INVESTIGATING, IN_PROGRESS, IMPLEMENTED, VERIFIED, BLOCKED, FAILED }
    enum class Status { COMPLETED, REFUSED, BLOCKED, FAILED, INCOMPLETE, NEEDS_REVIEW }

    data class Requirement(val id: String, val text: String, val status: ReqStatus = ReqStatus.NOT_STARTED,
                           val evidence: List<String> = emptyList())

    /** Immutable record of what the owner asked; [original] never changes during the run. */
    data class TaskRecord(val runId: String, val chatId: String, val projectId: String, val original: String,
                          val restrictions: List<String>, val created: Long, val requirements: List<Requirement>)

    private val restriction = Regex("""(?i)\b(do not|don['’]t|never|must not|without|only|keep|preserve)\b""")

    fun record(runId: String, chatId: String, projectId: String, request: String, now: Long = System.currentTimeMillis()): TaskRecord {
        val reqs = Requirements.lines(request).ifEmpty { listOf(request.trim()).filter { it.isNotEmpty() } }
        return TaskRecord(runId, chatId, projectId, request, reqs.filter { restriction.containsMatchIn(it) }, now,
            reqs.mapIndexed { i, t -> Requirement("R${i + 1}", t) })
    }

    /** The ledger: statuses change only through [update]; VERIFIED needs evidence of something actually run or checked. */
    class Ledger(val task: TaskRecord) {
        private val items = LinkedHashMap<String, Requirement>().apply { task.requirements.forEach { put(it.id, it) } }
        val requirements: List<Requirement> get() = items.values.toList()
        fun update(id: String, status: ReqStatus, evidence: String = ""): Boolean {
            val r = items[id] ?: return false
            if (status == ReqStatus.VERIFIED && evidence.isBlank() && r.evidence.isEmpty()) return false
            items[id] = r.copy(status = status, evidence = if (evidence.isBlank()) r.evidence else r.evidence + evidence)
            return true
        }
        fun open() = requirements.filter { it.status != ReqStatus.VERIFIED }
        /** One line per requirement, for a brief: the IDs travel with every delegation. */
        fun brief() = requirements.joinToString("\n") { "${it.id} [${it.status}] ${it.text}" }
    }

    data class Diagnosis(val type: TaskType, val complexity: Int, val risk: Int, val needsSpecialist: Boolean, val reasons: List<String>)

    fun diagnose(request: String, reqCount: Int = Requirements.lines(request).size): Diagnosis {
        val t = request.lowercase()
        val why = mutableListOf<String>()
        val type = when {
            Regex("""\b(security|vulnerab|audit|permission|authori[sz])""").containsMatchIn(t) -> TaskType.SECURITY_REVIEW
            Regex("""\b(crash|error|bug|fails?|failing|broken|exception|doesn['’]t work|not working|fix)\b""").containsMatchIn(t) -> TaskType.DEBUG
            Regex("""\b(build|apk|compile|gradle)\b""").containsMatchIn(t) -> TaskType.BUILD
            Regex("""\b(research|compare|find out|look up|investigate|documentation)\b""").containsMatchIn(t) -> TaskType.RESEARCH
            Regex("""\b(add|implement|create|feature|integrate|redesign|upgrade)\b""").containsMatchIn(t) ->
                if (reqCount <= 2 && t.length < 300) TaskType.SMALL_CHANGE else TaskType.FEATURE
            Regex("""\b(change|rename|edit|update|replace|move)\b""").containsMatchIn(t) -> TaskType.SMALL_CHANGE
            t.trim().endsWith("?") || t.length < 160 -> TaskType.QUESTION
            else -> TaskType.OTHER
        }
        why += "type $type"
        var complexity = when { reqCount >= 8 || t.length > 2_000 -> 3; reqCount >= 3 || t.length > 400 -> 2; else -> 1 }
        if (type == TaskType.FEATURE || type == TaskType.SECURITY_REVIEW) complexity = maxOf(complexity, 2)
        why += "$reqCount requirement(s), complexity $complexity"
        val risk = when {
            Regex("""\b(delete|remove|drop|wipe|reset|migrat|production|payment|signing key|credential|password|database)""").containsMatchIn(t) -> 3
            type == TaskType.SECURITY_REVIEW || type == TaskType.FEATURE -> 2
            else -> 1
        }
        why += "risk $risk"
        val specialist = Regex("""\b(race condition|concurren|deadlock|memory leak|native|jni|ndk|obfuscat|reverse engineer|architecture|root cause|intermittent|flaky)""").containsMatchIn(t)
        if (specialist) why += "needs specialist knowledge"
        return Diagnosis(type, complexity, risk, specialist, why)
    }

    /** Limits for one run; higher risk earns extra verification, a simple task gets no consultations. */
    data class Budget(val modelCalls: Int, val retries: Int, val reviews: Int, val research: Int, val tools: Int)

    fun budgetFor(d: Diagnosis): Budget = when {
        d.type == TaskType.QUESTION && d.complexity == 1 -> Budget(modelCalls = 2, retries = 1, reviews = 0, research = 2, tools = 6)
        d.complexity == 1 && d.risk == 1 -> Budget(4, 2, 0, 3, 20)
        d.complexity == 3 || d.risk == 3 -> Budget(10, 3, 2, 8, 60)
        else -> Budget(6, 2, 1, 5, 40)
    }

    /**
     * Verified results per task type and approach (and per model). Only VERIFIED outcomes may steer routing; a model's own
     * claim of success is counted separately and ignored. Fewer than [MIN_SAMPLES] verified results → no influence.
     */
    class Stats(private val file: File? = null) {
        data class Tally(var tried: Int = 0, var verified: Int = 0)
        val byApproach = HashMap<String, Tally>()
        val byModel = HashMap<String, Tally>()
        init { load() }
        fun record(type: TaskType, approach: Approach, verified: Boolean, model: String = "") {
            byApproach.getOrPut("$type|$approach") { Tally() }.let { it.tried++; if (verified) it.verified++ }
            if (model.isNotBlank()) byModel.getOrPut(model) { Tally() }.let { it.tried++; if (verified) it.verified++ }
            save()
        }
        /** Verified success rate, or null when there isn't enough evidence to use. */
        fun rate(type: TaskType, approach: Approach): Double? =
            byApproach["$type|$approach"]?.takeIf { it.tried >= MIN_SAMPLES }?.let { it.verified.toDouble() / it.tried }
        fun modelRate(model: String): Double? = byModel[model]?.takeIf { it.tried >= MIN_SAMPLES }?.let { it.verified.toDouble() / it.tried }
        private fun save() = file?.let { f -> runCatching {
            f.parentFile?.mkdirs()
            f.writeText((byApproach.map { "a\t${it.key}\t${it.value.tried}\t${it.value.verified}" } +
                byModel.map { "m\t${it.key}\t${it.value.tried}\t${it.value.verified}" }).joinToString("\n"))
        } }
        private fun load() = file?.takeIf { it.exists() }?.let { f -> runCatching {
            f.readLines().map { it.split('\t') }.filter { it.size == 4 }.forEach { (k, n, t, v) ->
                (if (k == "a") byApproach else byModel)[n] = Tally(t.toIntOrNull() ?: 0, v.toIntOrNull() ?: 0)
            }
        } }
        companion object { const val MIN_SAMPLES = 3 }
    }

    data class Plan(val approach: Approach, val reason: String, val budget: Budget, val roles: List<String>) {
        fun line() = "$approach — $reason (budget: ${budget.modelCalls} model calls, ${budget.retries} retries, ${budget.reviews} reviews)"
    }

    /** Cost of each approach in model calls (for comparing a cheaper alternative). */
    fun cost(a: Approach) = when (a) { Approach.DIRECT -> 1; Approach.MIDDLE -> 2; Approach.INVESTIGATE_FIRST -> 2; Approach.HELPER_SPECIALIST -> 3; Approach.MIDDLE_WITH_HELPER_REVIEW -> 4 }

    /**
     * First choice: the cheapest approach expected to be correct. Measured success (when there is enough) can move the
     * choice to a costlier approach that actually works better, or back to a cheaper one that works just as well.
     */
    fun choose(d: Diagnosis, middle: Boolean, helper: Boolean, stats: Stats? = null, projectMap: Boolean = false): Plan {
        val budget = budgetFor(d)
        val candidates = buildList {
            add(Approach.DIRECT)
            if (middle) add(Approach.MIDDLE)
            if (helper) add(Approach.HELPER_SPECIALIST)
            if (middle && helper) add(Approach.MIDDLE_WITH_HELPER_REVIEW)
            if (middle && projectMap) add(Approach.INVESTIGATE_FIRST)
        }
        val needed = when {
            d.needsSpecialist && helper -> if (middle && d.risk >= 2) Approach.MIDDLE_WITH_HELPER_REVIEW else Approach.HELPER_SPECIALIST
            d.type == TaskType.QUESTION && d.complexity == 1 -> Approach.DIRECT
            d.risk == 3 && middle && helper && budget.reviews > 0 -> Approach.MIDDLE_WITH_HELPER_REVIEW
            // A change to an existing project: the worker first traces the related files from the project map.
            projectMap && middle && d.type in setOf(TaskType.FEATURE, TaskType.DEBUG, TaskType.SMALL_CHANGE) && d.complexity >= 2 -> Approach.INVESTIGATE_FIRST
            d.complexity >= 2 || d.type in setOf(TaskType.FEATURE, TaskType.DEBUG, TaskType.BUILD, TaskType.RESEARCH) ->
                if (middle) Approach.MIDDLE else Approach.DIRECT
            else -> Approach.DIRECT
        }
        var pick = needed
        var why = d.reasons.joinToString(", ")
        stats?.let { s ->
            val mine = s.rate(d.type, needed)
            val better = candidates.mapNotNull { a -> s.rate(d.type, a)?.let { a to it } }
                .filter { (a, r) -> mine != null && (r >= mine + 0.25 || (r >= mine && cost(a) < cost(needed))) }
                .minByOrNull { cost(it.first) }
            if (better != null) { pick = better.first; why += "; measured: ${better.first} verified ${(better.second * 100).toInt()}% vs ${(mine!! * 100).toInt()}%" }
        }
        val roles = when (pick) {
            Approach.DIRECT -> listOf("boss: answer")
            Approach.MIDDLE -> listOf("boss: plan + accept", "middle: investigate, implement, test")
            Approach.HELPER_SPECIALIST -> listOf("boss: plan + accept", "helper: specialist part only")
            Approach.MIDDLE_WITH_HELPER_REVIEW -> listOf("boss: plan + accept", "middle: implement + test", "helper: adversarial review")
            Approach.INVESTIGATE_FIRST -> listOf("boss: plan + accept", "middle: trace the related files, then implement + test")
        }
        return Plan(pick, why, budget, roles)
    }

    /**
     * After a failure: a materially different approach, chosen from the failure's cause — or null when nothing different
     * is left or the stop is final (a genuine safety or authorization refusal is never escalated around).
     */
    fun escalate(current: Plan, failure: Outcome.Kind, tried: Set<Approach>, middle: Boolean, helper: Boolean): Plan? {
        if (failure == Outcome.Kind.POLICY_OR_SAFETY_REFUSAL || failure == Outcome.Kind.AUTHORIZATION_REQUIRED) return null
        val unclear = failure == Outcome.Kind.MISUNDERSTOOD_OBJECTIVE || failure == Outcome.Kind.INSUFFICIENT_INFORMATION
        if (current.budget.retries <= 0) return null
        val next = if (unclear && tried.size <= 1) current.approach  // same executor once, with a corrected brief
        else (if (failure == Outcome.Kind.NETWORK_OR_PROVIDER_FAILURE) listOf(Approach.HELPER_SPECIALIST, Approach.DIRECT, Approach.MIDDLE)
              else listOf(Approach.HELPER_SPECIALIST, Approach.INVESTIGATE_FIRST, Approach.MIDDLE, Approach.DIRECT))
            .filter { (it != Approach.MIDDLE && it != Approach.INVESTIGATE_FIRST || middle) && (it != Approach.HELPER_SPECIALIST || helper) }
            .firstOrNull { it !in tried } ?: return null
        val why = when (failure) {
            Outcome.Kind.MISUNDERSTOOD_OBJECTIVE, Outcome.Kind.INSUFFICIENT_INFORMATION -> "restate the task with the missing detail"
            Outcome.Kind.NETWORK_OR_PROVIDER_FAILURE -> "provider failed — another executor"
            else -> "$failure — a different method on the unresolved part only"
        }
        return current.copy(approach = next, reason = why, budget = current.budget.copy(retries = (current.budget.retries - 1).coerceAtLeast(0)))
    }

    /** Status of one model response, from its classification. A refusal or failure is never COMPLETED. */
    fun statusOf(v: Outcome.Verdict): Status = when (v.kind) {
        Outcome.Kind.SUCCESSFUL_RESPONSE -> if (v.partial) Status.NEEDS_REVIEW else Status.COMPLETED
        Outcome.Kind.POLICY_OR_SAFETY_REFUSAL, Outcome.Kind.UNCLASSIFIED_REFUSAL -> Status.REFUSED
        Outcome.Kind.AUTHORIZATION_REQUIRED, Outcome.Kind.INSUFFICIENT_INFORMATION, Outcome.Kind.MISUNDERSTOOD_OBJECTIVE -> Status.BLOCKED
        Outcome.Kind.TECHNICAL_LIMITATION, Outcome.Kind.TOOL_EXECUTION_FAILURE, Outcome.Kind.NETWORK_OR_PROVIDER_FAILURE -> Status.FAILED
        Outcome.Kind.INCOMPLETE_RESPONSE -> Status.INCOMPLETE
    }

    /** What actually happened (not what a model says happened). */
    data class Evidence(val commands: List<Pair<String, Int>> = emptyList(), val filesChanged: List<String> = emptyList(),
                        val deliverable: String = "",
                        /** Source files the result must cite (investigate-first): a result naming none of them has no source evidence. */
                        val requiredRefs: List<String> = emptyList())

    data class Acceptance(val status: Status, val missing: List<String>, val reason: String) {
        val accepted get() = status == Status.COMPLETED
    }

    private val claimsDone = Regex("""(?i)\b(done|completed?|implemented|fixed|finished|all (?:tests )?pass(?:ed)?|works now|successfully)\b""")
    private val claimsRan = Regex("""(?i)\b(ran|executed|built|tested|compiled|installed|pushed|committed)\b""")

    /**
     * Compares a delegated result with the ledger and the evidence. A completion claim with no deliverable and no
     * executed step, a claimed run with no command actually run, or a failed command → not accepted.
     */
    fun accept(result: String, evidence: Evidence, ledger: Ledger): Acceptance {
        val body = result.trim()
        if (body.isEmpty()) return Acceptance(Status.FAILED, ledger.open().map { it.id }, "empty result")
        val verdict = Outcome.classify(body)
        if (!verdict.kind.ok) return Acceptance(statusOf(verdict), ledger.open().map { it.id }, "${verdict.kind}: ${verdict.reason}".take(200))
        val failedCmd = evidence.commands.firstOrNull { it.second != 0 }
        if (failedCmd != null) return Acceptance(Status.FAILED, ledger.open().map { it.id }, "command failed (exit ${failedCmd.second}): ${failedCmd.first.take(80)}")
        if (evidence.requiredRefs.isNotEmpty() && evidence.requiredRefs.none { r -> body.contains(r.substringAfterLast('/')) })
            return Acceptance(Status.NEEDS_REVIEW, ledger.open().map { it.id }, "no source evidence: cites none of the mapped files")
        val content = evidence.deliverable.ifBlank { body }
        val claimOnly = claimsDone.containsMatchIn(body) && content.length < 160 && evidence.commands.isEmpty() && evidence.filesChanged.isEmpty()
        if (claimOnly) return Acceptance(Status.NEEDS_REVIEW, ledger.open().map { it.id }, "claims completion without any deliverable or executed step")
        if (claimsRan.containsMatchIn(body) && Regex("""(?i)\bI (?:have )?(ran|executed|built|tested|compiled|installed|pushed)""").containsMatchIn(body) &&
            evidence.commands.isEmpty()) return Acceptance(Status.NEEDS_REVIEW, ledger.open().map { it.id }, "says it ran something, but nothing was run")
        val missing = Requirements.uncovered(ledger.open().map { it.text }, content + "\n" + evidence.filesChanged.joinToString("\n"))
            .mapNotNull { t -> ledger.requirements.firstOrNull { it.text == t }?.id }
        return if (missing.isEmpty()) Acceptance(Status.COMPLETED, emptyList(), "deliverable covers every open requirement")
        else Acceptance(Status.INCOMPLETE, missing, "not covered: ${missing.joinToString()}")
    }

    /**
     * The task as handed to the worker: the Boss's task, the focused project map (only the related files), and for
     * investigate-first the evidence format. Instructions only — no extra AI call.
     */
    fun delegationBrief(task: String, plan: Plan?, mapNote: String): String = buildString {
        append(task)
        if (mapNote.isNotBlank()) append("\n\n").append(mapNote)
        if (plan?.approach == Approach.INVESTIGATE_FIRST && mapNote.isNotBlank()) append("\nFirst read the files above that matter, then do the task. " +
            "For each finding give FILE, FUNCTION/CLASS, what the source actually shows, the change, its expected effect and how to verify it. " +
            "Label anything you didn't see in the source as ASSUMPTION. Don't claim you ran or tested anything you didn't.")
    }

    /** One adversarial review by the helper (only when the plan chose a review). */
    fun reviewQuestion(task: String, result: String, mapNote: String): String =
        "Independently review this implementation by another model. Task:\n$task\n\n" +
            (if (mapNote.isNotBlank()) "$mapNote\n" else "") +
            "Implementation:\n\"\"\"\n${result.take(12_000)}\n\"\"\"\n\nFind incorrect assumptions, missing dependencies, incomplete parts, " +
            "regressions, security or data-integrity problems, concurrency issues and claims without evidence. Start with " +
            "VERDICT: OK or VERDICT: ISSUES, then list each issue with the file and the fix. Don't rewrite what is correct."

    /** True when the review found problems (or gave no clear verdict). */
    fun reviewFoundIssues(answer: String): Boolean = !Regex("""(?i)VERDICT:\s*OK\b""").containsMatchIn(answer)

    // ---- Durable checkpoint (resume after interruption) and per-run diagnostics ----

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", "\\t")
    private fun unesc(s: String): String {
        val b = StringBuilder(); var i = 0
        while (i < s.length) { val c = s[i]; if (c == '\\' && i + 1 < s.length) { b.append(when (s[i + 1]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i + 1] }); i += 2 } else { b.append(c); i++ } }
        return b.toString()
    }

    /** Saves the task record, ledger and progress so an interrupted task resumes with the same objective. */
    fun saveCheckpoint(file: File, ledger: Ledger, plan: Plan?, progress: String) = runCatching {
        val t = ledger.task
        file.parentFile?.mkdirs()
        file.writeText(buildString {
            append("run\t${esc(t.runId)}\nchat\t${esc(t.chatId)}\nproject\t${esc(t.projectId)}\ncreated\t${t.created}\n")
            append("original\t${esc(t.original)}\nprogress\t${esc(progress)}\n")
            plan?.let { append("approach\t${it.approach}\n") }
            t.restrictions.forEach { append("restriction\t${esc(it)}\n") }
            ledger.requirements.forEach { append("req\t${it.id}\t${it.status}\t${esc(it.text)}\t${esc(it.evidence.joinToString("\u0001"))}\n") }
        })
    }.isSuccess

    data class Resumed(val ledger: Ledger, val approach: Approach?, val progress: String)

    fun loadCheckpoint(file: File): Resumed? = runCatching {
        if (!file.exists()) return null
        val m = HashMap<String, String>(); val reqs = mutableListOf<Requirement>(); val rest = mutableListOf<String>()
        file.readLines().forEach { l ->
            val p = l.split('\t')
            when (p[0]) {
                "req" -> if (p.size >= 4) reqs += Requirement(p[1], unesc(p[3]), ReqStatus.valueOf(p[2]),
                    p.getOrNull(4)?.let(::unesc)?.split('\u0001')?.filter { it.isNotEmpty() } ?: emptyList())
                "restriction" -> rest += unesc(p.getOrElse(1) { "" })
                else -> if (p.size >= 2) m[p[0]] = unesc(p[1])
            }
        }
        val task = TaskRecord(m["run"].orEmpty(), m["chat"].orEmpty(), m["project"].orEmpty(), m["original"].orEmpty(), rest,
            m["created"]?.toLongOrNull() ?: 0, reqs)
        Resumed(Ledger(task), m["approach"]?.let { runCatching { Approach.valueOf(it) }.getOrNull() }, m["progress"].orEmpty())
    }.getOrNull()

    /** One structured, redacted line per decision or outcome; never a whole transcript. */
    fun log(file: File, runId: String, chatId: String, event: String, fields: Map<String, String> = emptyMap()) = runCatching {
        file.parentFile?.mkdirs()
        if (file.length() > 512_000) file.writeText(file.readLines().takeLast(400).joinToString("\n") + "\n")
        val clean = fields.mapValues { Delegation.Brief.redact(it.value.take(300)).first }
        file.appendText((listOf("time=${System.currentTimeMillis()}", "run=$runId", "chat=$chatId", "event=$event") +
            clean.map { "${it.key}=${esc(it.value)}" }).joinToString("\t") + "\n")
    }
}

/** ARIN-PROTECTED: run-context — what one reply runs with, captured once at its start; background steps read only this. */
data class RunContext(val runId: String, val chatId: String, val chatTitle: String, val projectId: String,
                      val provider: String, val model: String, val workDir: String, val helperOn: Boolean, val workerOn: Boolean,
                      val objective: String)
