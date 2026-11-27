package com.arin.builder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ARIN-PROTECTED: project-memory — everything about one project that must survive closing the app, a model change or an
 * expired session, kept on the phone as a small JSON file (no AI, no credit):
 *  - the original objective and every later request;
 *  - the requirements checklist with a status for each item;
 *  - every build error (by fingerprint) with each fix tried and the fix that finally worked, so an error is never
 *    researched or "fixed" the same way twice, even in a later session;
 *  - every build (result, emulator test) and every commit (rollback points);
 *  - every file changed.
 * Only your main model and Arin itself read the whole of it; workers and the helper get just the part their task needs.
 */
class ProjectMemory private constructor(private val file: java.io.File, private val j: JSONObject) {

    enum class Status(val label: String) {
        PENDING("Pending"), IN_PROGRESS("In progress"), IMPLEMENTED("Implemented, not verified yet"),
        VERIFIED("Completed and verified (built, opens without crashing)"), BLOCKED("Blocked"), NEEDS_YOU("Needs your input")
    }

    private fun arr(name: String): JSONArray = j.optJSONArray(name) ?: JSONArray().also { j.put(name, it) }

    val objective: String get() = j.optString("objective")

    fun noteRequest(text: String) {
        val t = text.trim().take(4_000)
        if (t.isBlank()) return
        if (objective.isBlank()) j.put("objective", t)
        val reqs = arr("requests")
        if (reqs.length() == 0 || reqs.getJSONObject(reqs.length() - 1).optString("text") != t) {
            reqs.put(JSONObject().put("time", System.currentTimeMillis()).put("text", t))
        }
        save()
    }

    // ---- Requirements checklist ----

    /** Turns the requirements text into a checklist (no AI): each bullet, numbered line or sentence is one item. */
    fun setRequirements(text: String) {
        val items = text.lines().map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("===") }
            .flatMap { line ->
                val clean = line.replace(Regex("""^(\d+[.)]|[-*•])\s*"""), "")
                if (Regex("""^(\d+[.)]|[-*•])\s""").containsMatchIn(line)) listOf(clean)
                else clean.split(Regex("""(?<=[.!?])\s+(?=[A-Z])""")).map { it.trim() }
            }
            .filter { it.length in 8..400 }
            .distinct().take(40)
        if (items.isEmpty()) return
        val old = arr("requirements")
        val known = (0 until old.length()).map { old.getJSONObject(it) }.associateBy { it.optString("text") }
        val list = JSONArray()
        items.forEach { t -> list.put(known[t] ?: JSONObject().put("text", t).put("status", Status.PENDING.name)) }
        j.put("requirements", list)
        save()
    }

    fun setAllRequirements(status: Status, onlyFrom: Set<Status>? = null) {
        val r = arr("requirements")
        for (i in 0 until r.length()) {
            val o = r.getJSONObject(i)
            val now = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.PENDING)
            if (onlyFrom == null || now in onlyFrom) o.put("status", status.name)
        }
        save()
    }

    /** Plain requirement texts (the delegation gateway sends only the ones relevant to a task). */
    fun requirementList(): List<String> = arr("requirements").let { r -> (0 until r.length()).map { r.getJSONObject(it).optString("text") }.filter { it.isNotBlank() } }

    fun requirementsText(max: Int = 12): String {
        val r = arr("requirements")
        if (r.length() == 0) return ""
        return "**Requirements checklist**\n" + (0 until minOf(r.length(), max)).joinToString("\n") { i ->
            val o = r.getJSONObject(i)
            val s = runCatching { Status.valueOf(o.optString("status")) }.getOrDefault(Status.PENDING)
            val mark = when (s) { Status.VERIFIED -> "✅"; Status.IMPLEMENTED -> "🟡"; Status.BLOCKED -> "⛔"; Status.NEEDS_YOU -> "❓"; else -> "⬜" }
            "$mark ${o.optString("text").take(140)} — ${s.label}"
        } + if (r.length() > max) "\n…and ${r.length() - max} more" else ""
    }

    // ---- Errors and fixes ----

    private fun errorObj(fp: String): JSONObject {
        val e = arr("errors")
        for (i in 0 until e.length()) if (e.getJSONObject(i).optString("fp") == fp) return e.getJSONObject(i)
        return JSONObject().put("fp", fp).put("tries", JSONArray()).also { e.put(it) }
    }

    /** A fix round for error [fp] ([line] = what is wrong). */
    fun recordTry(fp: String, line: String, what: String) {
        val o = errorObj(fp)
        o.put("line", line.take(300)).put("count", o.optInt("count") + 1).put("last", System.currentTimeMillis())
        o.getJSONArray("tries").put(what.take(300))
        while (o.getJSONArray("tries").length() > 12) o.getJSONArray("tries").remove(0)
        save()
    }

    /** Error [fp] is gone: the last thing tried for it is what fixed it. */
    fun markFixed(fp: String) {
        val o = errorObj(fp)
        val t = o.getJSONArray("tries")
        if (t.length() > 0 && o.optString("fixedBy").isBlank()) { o.put("fixedBy", t.getString(t.length() - 1)); save() }
    }

    /** What this project already knows about error [fp]: earlier tries (didn't work) and the fix that worked. */
    fun knownAbout(fp: String): String {
        val e = arr("errors")
        val o = (0 until e.length()).map { e.getJSONObject(it) }.firstOrNull { it.optString("fp") == fp } ?: return ""
        val tries = o.optJSONArray("tries")?.let { t -> (0 until t.length()).map { t.getString(it) } }.orEmpty()
        return buildString {
            o.optString("fixedBy").takeIf { it.isNotBlank() }?.let {
                appendLine("THIS EXACT ERROR WAS FIXED BEFORE IN THIS PROJECT — this is what worked (reuse it): $it")
            }
            if (tries.isNotEmpty()) {
                appendLine("EARLIER SESSIONS ALREADY TRIED (seen ${o.optInt("count")} time(s)) — don't repeat what didn't work:")
                tries.takeLast(6).forEach { appendLine("- $it") }
            }
        }.trim()
    }

    // ---- Builds, commits, files ----

    fun recordCommit(sha: String, before: String?, message: String) {
        arr("commits").put(JSONObject().put("time", System.currentTimeMillis()).put("sha", sha).put("rollbackTo", before ?: "").put("message", message.take(200)))
        while (arr("commits").length() > 60) arr("commits").remove(0)
        save()
    }

    fun recordBuild(run: Long, success: Boolean, test: String) {
        arr("builds").put(JSONObject().put("time", System.currentTimeMillis()).put("run", run).put("success", success).put("test", test.take(200)))
        while (arr("builds").length() > 60) arr("builds").remove(0)
        save()
    }

    fun recordFiles(paths: Collection<String>) {
        val f = arr("files")
        val have = (0 until f.length()).map { f.getString(it) }.toMutableSet()
        paths.filter { have.add(it) }.forEach { f.put(it) }
        save()
    }

    /** The latest commit and the one before it (to roll back to). */
    fun lastRollback(): Pair<String, String>? = arr("commits").let { c ->
        if (c.length() == 0) null else c.getJSONObject(c.length() - 1).let { it.optString("sha") to it.optString("rollbackTo") }
    }

    // ---- The main model's brief for workers (the owner's words stay with the main model) ----
    /** The brief written for [sig] (a fingerprint of the owner's request texts), if there is one. */
    fun ownerBrief(sig: Int): String? = j.optJSONObject("ownerBrief")?.takeIf { it.optInt("sig") == sig }?.optString("text")?.ifBlank { null }
    /** The newest brief whatever it was written for (updated, not rewritten, when the owner asks for more). */
    fun lastOwnerBrief(): String = j.optJSONObject("ownerBrief")?.optString("text").orEmpty()
    fun setOwnerBrief(sig: Int, text: String) { j.put("ownerBrief", JSONObject().put("sig", sig).put("text", text)); save() }

    /** A short summary for your main model (what this project is and where it stands). */
    fun brief(): String = buildString {
        if (objective.isNotBlank()) appendLine("ORIGINAL OBJECTIVE: " + objective.take(600))
        val reqs = arr("requests")
        if (reqs.length() > 1) appendLine("LATER REQUESTS: " + (1 until reqs.length()).joinToString(" | ") { reqs.getJSONObject(it).optString("text").take(160) }.takeLast(1_200))
        val b = arr("builds")
        if (b.length() > 0) b.getJSONObject(b.length() - 1).let { appendLine("LAST BUILD: run ${it.optLong("run")} — " + (if (it.optBoolean("success")) "built" else "failed") + it.optString("test").let { t -> if (t.isBlank()) "" else ", test: $t" }) }
        val open = arr("errors").let { e -> (0 until e.length()).map { e.getJSONObject(it) }.filter { it.optString("fixedBy").isBlank() } }
        if (open.isNotEmpty()) appendLine("UNSOLVED ERRORS SEEN BEFORE: " + open.takeLast(3).joinToString(" | ") { it.optString("line").take(120) })
    }.trim()

    private fun save() = runCatching { file.parentFile?.mkdirs(); file.writeText(j.toString()) }

    companion object {
        private val cache = mutableMapOf<String, ProjectMemory>()

        /** The memory of the project with [key] (its package name, or "arin-self" for Arin's own updates). */
        @Synchronized
        fun of(c: Context, key: String): ProjectMemory = cache.getOrPut(key) {
            val f = java.io.File(c.filesDir, "project-memory/" + key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
            val j = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject().put("key", key).put("created", System.currentTimeMillis()))
            ProjectMemory(f, j)
        }
    }
}
