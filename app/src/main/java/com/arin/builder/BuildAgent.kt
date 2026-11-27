package com.arin.builder

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BuildRequest(
    /** The new app's description, or the change to make to [BuildRunner.project]. */
    val description: String,
    val appName: String,
    val provider: com.arin.llm.Provider,
    val model: String,
    val apiKey: String,
    val effort: Effort,
    val githubToken: String,
    /** Let the agent download files from sites outside the built-in safe list. */
    val allowOtherSites: Boolean,
    /** Continue this project instead of starting a new one. */
    val project: Project? = null,
    /** An uploaded Android Studio project (.zip) to build instead of writing a new app. */
    val zipPath: String? = null,
    val zipName: String? = null,
    /** Files attached to this request: shown to the model, and images/fonts/sounds added to the app. */
    val attachments: List<com.arin.data.Attachment> = emptyList(),
    /** Pick up where the last build of [project] stopped instead of starting over. */
    val resume: Boolean = false,
    /** Change Arin itself: edit [selfRepo] on GitHub and build it with Arin's own workflow. */
    val selfUpdate: Boolean = false,
    /** Arin's source: owner/repo and branch. */
    val selfRepo: String = "",
    val selfBranch: String = "",
    /** Force the build to run on the phone (built-in Linux), never GitHub. */
    val localOnly: Boolean = false,
    /** Build in the cloud and also open a GitHub Codespace on the project; the APK is saved straight to the phone. */
    val codespace: Boolean = false,
    /**
     * Everything that was asked for, in full (e.g. the whole chat about the app). Blank: [description] is all there is.
     */
    val requirements: String = "",
    /** The custom API this build uses (its own, even if another chat switches APIs). Blank = the app's current one. */
    val customBaseUrl: String = "",
    val customFormat: String = "",
    /** After the APK builds, open it on GitHub's Android emulator and check it doesn't crash (fixed like build errors). */
    val testApp: Boolean = true,
    /** [requirements] already covers earlier requests too (a chat), so it replaces what the project has saved. */
    val requirementsComplete: Boolean = false,
    /** The owner unlocked the protected rules for this self-update (only from the Arin Update tab, by the owner). */
    val unlockProtected: Boolean = false,
    /** What earlier attempts ran into and the researched fix: advice for another method, never a change to the request. */
    val retryHint: String = "",
    /** Stop once the code is written and ask the owner where to build it (every tab). */
    val stopAfterWriting: Boolean = false,
    /** Where the owner chose to build after the code was written: "github", "phone" or "codespace" (blank = as before). */
    val target: String = "",
    /** Talking with the helper: once the model has written the code, the helper reviews it and the model fixes what it finds. */
    val consultHelper: Boolean = false,
    /** Which tab's AI helper switch this build follows ("chat", "build", "arin-update"); blank = Build or Arin Update. */
    val helperTab: String = "",
)

data class BuildResult(
    val success: Boolean,
    val message: String,
    val repoUrl: String? = null,
    val apkUrl: String? = null,
    /** Why it failed: the build's error lines, or what the model sent back. */
    val log: String = "",
    /** The GitHub Actions run with the full log. */
    val runUrl: String? = null,
    /** Where the APK compiled on the phone (built-in Linux) was saved, if it was. */
    val phoneApk: String? = null,
    /** The source code as a zip on the phone (for builds made only on the phone). */
    val phoneSourceZip: String? = null,
    /** The phone-built APK in Arin's storage, ready for Android's installer. */
    val phoneInstallPath: String? = null,
    /** The Codespace opened for this project, if any. */
    val codespaceUrl: String? = null,
    /** Save the APK to the phone automatically (used for codespace builds). */
    val autoDownloadApk: Boolean = false,
    /** What the emulator test found, in plain words (blank when the app wasn't tested). */
    val testNote: String = "",
    /** Screenshot of the app's first screen on the emulator, if the test took one. */
    val testScreenshot: String? = null,
    /** ARIN-PROTECTED: explain-after-coding — what the code does, in plain English, then suggestions (blank: no code written). */
    val explanation: String = "",
)

/**
 * Build Mode agent: the model writes or edits the app, GitHub Actions builds it, and build errors
 * go back to the model to fix, until the APK builds or the fix rounds for the Brain level run out.
 */
/** The fixed API address for [BuildRequest] (custom APIs only). */
fun BuildRequest.endpoint(): com.arin.llm.CustomApi.Endpoint? =
    if (provider == com.arin.llm.Provider.Custom && customBaseUrl.isNotBlank())
        com.arin.llm.CustomApi.Endpoint(customBaseUrl, runCatching { com.arin.llm.ApiFormat.valueOf(customFormat) }.getOrDefault(com.arin.llm.ApiFormat.OpenAI))
    else null

class BuildAgent(
    private val req: BuildRequest,
    private val emit: (BuildEvent) -> Unit,
    /** Called whenever the project changes, so it can be saved. */
    private val onProject: (Project) -> Unit = {},
    /** Live progress for long steps (null = done), with a short description. */
    private val onProgress: (Float?, String) -> Unit = { _, _ -> },
    /** For building on the phone in the built-in Linux, when its Android build tools are installed. */
    private val context: android.content.Context? = null,
    /** Directions the person sent while this build runs (taken at each step). */
    private val takeSteering: () -> List<String> = { emptyList() },
    /** True when a direction is waiting (checked while GitHub builds, so it's applied right away). */
    private val hasSteering: () -> Boolean = { false },
) {
    /** Set when waiting for GitHub was cut short by a direction from the person. */
    private var steerNow = false
    /** Every direction the person sent while this build ran: kept in every later prompt, in the order sent. */
    private val steered = mutableListOf<String>()

    private class SteerNow : RuntimeException()

    private fun steeringNote(): String {
        val fresh = takeSteering()
        fresh.forEach { step(BuildEvent.Kind.THINKING, "Following your direction: ${it.take(140)}") }
        steered += fresh
        if (steered.isEmpty()) return ""
        return "\n\nTHE OWNER'S DIRECTIONS, sent while you were working (follow them exactly; they win where they conflict " +
            "with the above, and nothing else that was asked may be dropped):\n" + steered.joinToString("\n") { "- $it" }
    }
    private val gh = GitHubClient(req.githubToken)
    private val slug = (req.project?.appName ?: req.appName).lowercase().replace(Regex("[^a-z0-9]+"), "").ifEmpty { "app" }.take(30)
    /** The package the person asked for ("package com.example.calendar"), else one made from the app name. */
    private val packageName = req.project?.packageName
        ?: Regex("""(?i)\bpackage(?:\s+name)?\s*[:=]?\s*`?([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*){1,5})`?""")
            .find(req.description)?.groupValues?.get(1)?.lowercase()
        ?: ("com.aibuilt." + (if (slug.first().isLetter()) slug else "a$slug"))
    private val appName get() = req.project?.appName ?: importedName ?: req.appName

    /** Updating Arin itself (its own repo, workflow and signing key). */
    private val self = req.selfUpdate || req.project?.selfUpdate == true
    private val imported = req.project?.imported == true || req.zipPath != null || self
    /** Test the built app on GitHub's emulator (not for Arin itself, nor builds made only on the phone). */
    // ARIN-PROTECTED: test-before-handover — owner's strict rule (only the owner can change it): every app Arin builds on
    // GitHub is installed on an Android emulator, opened and tapped around; crashes are fixed like build errors, and the
    // APK download link is handed over only after the app opens and runs without crashing.
    // Always on, whatever the request or settings say (the owner never has to ask for it).
    private val testing get() = true // every app: Arin-made, uploaded projects and Arin's own updates
    private val workflowFile = when {
        self -> SELF_WORKFLOW
        imported -> ProjectTemplate.IMPORT_WORKFLOW_FILE
        else -> "build.yml"
    }
    private var importedName: String? = null
    private var uploadedOnce = false
    private var currentSha: String? = req.project?.lastSha
    private var currentRound = 0

    private val files = linkedMapOf<String, String>().apply { req.project?.files?.let { putAll(it) } }
    private val deps = linkedSetOf<String>().apply { req.project?.deps?.let { addAll(it) } }

    /** Files changed since the last commit (null value = delete). */
    private val pendingFiles = linkedMapOf<String, ByteArray?>()
    /** Big uploaded files waiting to be committed straight from storage. */
    private val pendingDisk = linkedMapOf<String, java.io.File>()
    private var depsChanged = false

    /** Everything the app must do: the first specification plus every change asked for since, so nothing gets lost. */
    private val requirements: String = kotlin.run {
        val now = req.requirements.ifBlank { req.description }.trim()
        val before = (req.project?.requirements?.ifBlank { null } ?: req.project?.description).orEmpty().trim()
        val all = when {
            req.selfUpdate || req.project?.selfUpdate == true -> now
            req.requirementsComplete || before.isEmpty() -> now.ifBlank { before }
            req.resume || now.isEmpty() || before.contains(now) -> before
            else -> before + "\n\nCHANGE ASKED FOR LATER (do this too; it wins where it conflicts with the above):\n" + now
        }
        if (all.length > 36_000) all.take(12_000) + "\n[…]\n" + all.takeLast(24_000) else all
    }

    /** How many times the finished code is checked against the requirements before building. */
    private val reviewRounds: Int = when (req.effort) {
        Effort.LOW -> 0
        Effort.MAX -> 2
        else -> 1
    }

    /** The fix rounds the Brain level allows (used for the phone build before trying GitHub). */
    private val baseFixRounds: Int = when (req.effort) {
        Effort.LOW -> 1
        Effort.MEDIUM, Effort.AUTO -> 3
        Effort.HIGH -> 6
        Effort.MAX -> 10
    } + if (false) 2 else 0

    /** ARIN-PROTECTED: research-first auto-fix keeps going until the error is fixed (only Stop ends it). */
    private val maxFixRounds: Int = if (com.arin.selffix.AutoFix.UNTIL_FIXED) com.arin.selffix.AutoFix.NO_LIMIT else baseFixRounds
    /** Errors that keep coming back get Claude's help. */
    private val repeats = com.arin.selffix.AutoFix.Repeats()
    /** Arin's own source as read from GitHub (self-updates), to keep the strict and protected rules. */
    private var selfOriginal: Map<String, String> = emptyMap()
    private var claudeAsks = 0
    private var approvedSig = 0
    /** The helper's plan is asked for once per build, before the model writes its first code. */
    private var helperPlanned = false

    private fun step(kind: BuildEvent.Kind, text: String) {
        LiveFeed.add(if (kind == BuildEvent.Kind.THINKING) "…" else "Build", text)
        emit(BuildEvent(kind, text))
    }

    /** The model's last answer, kept so a failure report can show what it sent. */
    private var lastReply = ""
    /** Roughly how many tokens of the person's model this task has used (characters / 4). */
    private var taskTokens = 0L

    private var lastThinkingNote = 0L

    /** The Codespace opened for this build, if any. */
    private var codespaceUrl: String? = null

    /** Parts written so far, numbered for the plain-English progress. */
    private var part = 0

    private fun modelReplyLog(): String =
        if (lastReply.isBlank()) "(The model sent back an empty answer: it may have spent its whole answer thinking, or the service cut it off.)"
        else "Last answer from the model (end):\n" + lastReply.takeLast(1_500)

    /** The phone can compile apps itself (Linux with the Android build tools). */
    private val phoneCan = !self && context != null && LocalBuild.available(context)

    /** No GitHub for this build: compile, fix and hand over the APK on the phone. */
    private var localOnly = (req.project?.owner == LOCAL_OWNER && req.target !in setOf("github", "codespace")) ||
        (req.localOnly && (!self || req.target == "phone"))

    private fun localRepo(name: String) = GitHubClient.Repo(LOCAL_OWNER, name, "main", "")

    /** Creates the repo on GitHub, or switches to a phone-only build if GitHub refuses and the phone can build. */
    private suspend fun createRepoOrLocal(name: String, description: String): GitHubClient.Repo {
        if (localOnly) return localRepo(name)
        step(BuildEvent.Kind.STEP, "Creating GitHub repo $name")
        return try {
            gh.createRepo(name, description)
        } catch (e: ProviderException) {
            if (!phoneCan) throw e
            step(BuildEvent.Kind.ERROR, e.message ?: "GitHub refused to create the repository")
            step(BuildEvent.Kind.STEP, "Building on this phone instead (no GitHub needed)")
            localOnly = true
            localRepo(name)
        }
    }

    /** This project's persistent memory (survives closing the app, model changes and expired sessions). */
    private val memKey get() = if (self) "arin-self" else packageName
    /**
     * Requirements the delegation gateway may pick from for a worker task: the points of the main model's brief (its own
     * words), never the owner's requirement text.
     */
    private fun reqList(): List<String> = (briefCache?.second ?: mem?.lastOwnerBrief().orEmpty()).lines().map { it.trim() }
        .filter { Regex("""^(\d+[.)]|[-*•])\s+\S""").containsMatchIn(it) }.map { it.replace(Regex("""^(\d+[.)]|[-*•])\s+"""), "") }

    /** Everything the owner wrote for this build: the request, all earlier requests, and directions sent while working. */
    private fun ownerTexts(): List<String> = (listOf(req.description, requirements, req.requirements, req.project?.description.orEmpty(),
        req.project?.requirements.orEmpty()) + steered).filter { it.isNotBlank() }.distinct()

    private var briefCache: Pair<Int, String>? = null

    /**
     * ARIN-PROTECTED: owner-words-only-to-main — the main model (only it reads the owner's messages and files) writes a
     * short brief in its own words; workers and the helper build from that brief, never from the owner's text. One short,
     * lean call per new request (kept in the project memory and reused), so the main model's credit stays low.
     */
    private suspend fun ownerBrief(seenText: String = ""): String {
        val owner = ownerTexts()
        val sig = owner.joinToString("\u0000").hashCode()
        briefCache?.let { if (it.first == sig) return it.second }
        mem?.ownerBrief(sig)?.let { briefCache = sig to it; return it }
        val prev = mem?.lastOwnerBrief().orEmpty()
        val input = buildString {
            if (prev.isNotBlank() && !req.resume) {
                appendLine("THE BRIEF YOU WROTE EARLIER FOR THIS APP:"); appendLine(prev); appendLine()
                appendLine("THE OWNER'S NEW REQUEST — update the brief with it (it wins where it conflicts; keep everything else):")
                appendLine(req.description.take(6_000))
            } else {
                appendLine("WHAT THE OWNER ASKED FOR (everything so far):"); appendLine(requirements.ifBlank { req.description }.take(16_000))
            }
            if (steered.isNotEmpty()) { appendLine(); appendLine("DIRECTIONS THE OWNER SENT WHILE WORK WAS GOING ON (they win):"); steered.forEach { appendLine("- $it") } }
            if (req.attachments.isNotEmpty()) {
                appendLine(); appendLine("FILES THE OWNER ATTACHED (Arin adds pictures, fonts and sounds to the app itself; refer to them by name): " +
                    req.attachments.joinToString { it.name })
                if (seenText.isNotBlank()) { appendLine("WHAT THE PHONE READ IN THEM:"); appendLine(seenText.take(4_000)) }
            }
        }
        val system = "You are the main model. A separate coding model will write the code, but it must NEVER see the owner's own " +
            "words, files or personal details — only your brief. Write the brief in YOUR OWN WORDS as short numbered points: every " +
            "feature, screen, behaviour, data item, text shown in the app, look and constraint the owner asked for — nothing left " +
            "out, nothing added. Refer to attached files by name only. Leave out personal details (names, phone numbers, emails, " +
            "addresses, keys) unless the app itself must show them, and then describe them generically. At most 350 words. " +
            "Reply with the brief only."
        var text = ""
        for (attempt in 1..2) {
            text = runCatching {
                val sb = StringBuilder()
                streamModel(req.provider, req.apiKey, req.model, listOf(ChatMessage(Role.USER, input)), system, req.endpoint(), boss = true, conv = "build:$memKey:brief")
                    .collect { if (!it.startsWith(THOUGHT)) sb.append(it) }
                sb.toString().trim()
            }.getOrDefault("")
            if (text.length > 20) break
            if (attempt == 1) kotlinx.coroutines.delay(5_000)
        }
        if (text.length <= 20) throw IllegalStateException("Your main model (${bossName()}) couldn't write the brief for the worker, and " +
            "the worker may not see your own words. Everything is saved — say \"try again\" to carry on.")
        // ARIN-PROTECTED: requirement-coverage — every requirement of the owner's must be in the brief; the main model
        // adds what it left out (once), so nothing is lost on the way to the worker.
        val asked = com.arin.selffix.Requirements.lines(if (prev.isNotBlank() && !req.resume) req.description + "\n" + steered.joinToString("\n") else requirements.ifBlank { req.description } + "\n" + steered.joinToString("\n"))
        val missing = com.arin.selffix.Requirements.uncovered(asked, text)
        if (missing.isNotEmpty()) {
            step(BuildEvent.Kind.THINKING, "${bossName()}'s brief left out ${missing.size} of ${asked.size} requirement(s) — asking it to add them")
            val fixMsg = "Your brief:\n$text\n\nIt leaves out these points the owner asked for — rewrite the WHOLE brief so it includes them (own words, numbered points):\n" +
                missing.joinToString("\n") { "- $it" }
            val again = runCatching {
                val sb = StringBuilder()
                streamModel(req.provider, req.apiKey, req.model, listOf(ChatMessage(Role.USER, fixMsg)), system, req.endpoint(), boss = true, conv = "build:$memKey:brief")
                    .collect { if (!it.startsWith(THOUGHT)) sb.append(it) }
                sb.toString().trim()
            }.getOrDefault("")
            if (again.length > 20 && com.arin.selffix.Requirements.uncovered(asked, again).size < missing.size) text = again
        }
        val stillMissing = com.arin.selffix.Requirements.uncovered(asked, text).size
        ledger("Brief for the worker", "${asked.size - stillMissing} of ${asked.size} requirement(s) in it" + if (stillMissing > 0) " — $stillMissing not clearly covered" else "")
        // Its own words only: anything it copied from the owner is removed (the gateway does the same for every request).
        text = com.arin.selffix.Delegation.Brief.withoutOwnerText(text, owner).first
        step(BuildEvent.Kind.THINKING, "${bossName()} wrote the brief for the worker in its own words (your messages and files stay with ${bossName()})")
        context?.let { com.arin.selffix.CreditSaver.addUsage(it, input.length + text.length) }
        briefCache = sig to text
        mem?.setOwnerBrief(sig, text)
        return text
    }

    /**
     * A prompt as a worker (or the helper writing code) may see it: the owner's text replaced by the main model's brief,
     * copied passages removed, secrets redacted, and an audit record kept. Attachments never go with it.
     */
    private suspend fun workerSafe(prompt: String, seenText: String = ""): String {
        val owner = ownerTexts()
        val brief = ownerBrief(seenText)
        val mark = "\u0001BRIEF\u0001"
        var t = prompt
        var placed = false
        for (o in owner.sortedByDescending { it.length }) {
            for (v in listOf(o, o.take(14_000), o.take(6_000), o.take(4_000), o.take(2_500), o.take(1_500), o.take(300)).distinct()) {
                if (v.trim().length >= 12 && t.contains(v)) {
                    t = t.replace(v, if (!placed) mark else "(see the brief)")
                    placed = true
                }
            }
        }
        t = com.arin.selffix.Delegation.Brief.withoutOwnerText(t, owner).first
        val block = "THE OWNER'S REQUEST — as briefed by the main model (${bossName()}); the owner's own messages and files stay with it:\n$brief"
        t = if (t.contains(mark)) t.replaceFirst(mark, block) else "$block\n\n$t"
        val ctx = context ?: return t
        val (sent, _, hidden) = com.arin.selffix.Delegation.gateFull(ctx, "worker (build)", t, helperTab, memKey)
        workerHidden = hidden
        return sent
    }

    /** Values hidden from the worker in [workerSafe] (e.g. an API key in the owner's own code): its answer gets them back. */
    private var workerHidden: List<String> = emptyList()

    /** Which workers declined in this build, and why (from their real answers). */
    private val workerRefusals = mutableListOf<String>()
    private val mem: ProjectMemory? by lazy { context?.let { runCatching { ProjectMemory.of(it, if (self) "arin-self" else packageName) }.getOrNull() } }

    /**
     * ARIN-PROTECTED: impact-analysis — before fixing or changing code, Arin itself (no AI) finds every other file that
     * uses the classes and functions in [targets], so related files are checked and changed together, not just the
     * obvious one. Returns "File.kt uses A, B" lines.
     */
    private fun relatedFiles(targets: Collection<String>): List<String> {
        val decl = Regex("""(?m)^\s*(?:(?:public|internal|private|data|sealed|abstract|open|enum|inline|value|annotation|suspend)\s+)*(?:class|object|interface|fun)\s+(?:<[^>]+>\s*)?(?:\w+\.)?(\w{3,})""")
        val names = targets.flatMap { p -> decl.findAll(files[p].orEmpty()).map { it.groupValues[1] }.toList() }.toSet()
            .filter { it !in setOf("main", "onCreate", "invoke", "toString", "equals", "hashCode", "Preview") }
        if (names.isEmpty()) return emptyList()
        return files.filterKeys { k -> k !in targets && (k.endsWith(".kt") || k.endsWith(".java") || k.endsWith(".xml")) }
            .mapNotNull { (k, txt) ->
                val used = names.filter { n -> Regex("""\b${Regex.escape(n)}\b""").containsMatchIn(txt) }
                if (used.isEmpty()) null else "${k.substringAfterLast('/')} ($k) uses " + used.take(8).joinToString()
            }.take(15)
    }

    private var primaryFails = 0
    private var lastRepo: GitHubClient.Repo? = null
    private fun saveProjectQuietly() { lastRepo?.let { r -> runCatching { saveProject(r, null) } } }

    // ARIN-PROTECTED: budgets — limits per build so nothing runs away with your credit (change them in Credit saver).
    private var researchUsed = 0
    private var consultUsed = 0
    private fun budget(name: String, def: Int): Int = context?.let { com.arin.selffix.CreditSaver.budget(it, name, def) } ?: def

    /** Files named in build errors. */
    private fun errorFiles(errors: String): List<String> =
        Regex("([\\w./-]+\\.(?:kt|java|xml|kts))[:(]").findAll(errors).map { it.groupValues[1] }.mapNotNull { f ->
            files.keys.firstOrNull { k -> k == f || k.endsWith("/" + f.substringAfterLast("/src/main/")) || k.endsWith("/" + f.substringAfterLast('/')) }
        }.distinct().toList()

    /** Set whenever the model wrote or changed code in this run (so the owner gets it explained). */
    private var codedThisRun = false

    // ARIN-PROTECTED: explain-after-coding — the owner's strict rule for every tab (chat, Build, Arin Update and all others):
    // after every piece of coding, the owner gets a detailed plain-English explanation of what was made or changed, then
    // suggestions to change, improve or update it, and the offer to build. Only the owner can change this rule.
    suspend fun run(): BuildResult {
        if (!self && !imported) runCatching { stabilizeDeps() }
        mem?.let { m ->
            m.noteRequest(req.description)
            m.setRequirements(if (self) req.description else requirements)
            if (!req.resume) m.setAllRequirements(ProjectMemory.Status.IN_PROGRESS, setOf(ProjectMemory.Status.PENDING))
        }
        if (!req.resume) planForOwner()
        val result0 = runBuild()
        // The checklist goes with every result, honestly: verified only when it was built AND passed the emulator test.
        val result = mem?.requirementsText()?.takeIf { it.isNotBlank() && !result0.message.startsWith(CODE_READY) && result0.message != "Stopped." }
            ?.let { result0.copy(message = result0.message + "\n\n" + it) } ?: result0
        val withLedger = if (ledgerItems.isEmpty() || result.message.startsWith(CODE_READY)) result
            else result.copy(message = result.message + "\n\n" + ledgerText())
        if (!codedThisRun || result.message == "Stopped.") return withLedger
        val text = runCatching { explainForOwner(result) }.getOrDefault("")
        return if (text.isBlank()) withLedger else withLedger.copy(explanation = text)
    }

    /**
     * Your own model (the boss, never the worker or helper: they may not see the code) explains the code in plain
     * English from a short list of what changed, then suggests changes, improvements and updates, and offers to build.
     */
    private suspend fun explainForOwner(result: BuildResult): String {
        val changed = (lastChanged.ifEmpty { pendingFiles.keys.toList() }).distinct()
        if (changed.isEmpty()) return ""
        step(BuildEvent.Kind.THINKING, "${bossName()} is explaining the code in plain English")
        val brief = buildString {
            // The worker explains in worker mode: it gets the main model's brief, never the owner's own words.
            appendLine("WHAT THE OWNER ASKED: " + (if (workerOn()) (briefCache?.second ?: mem?.lastOwnerBrief()).orEmpty().ifBlank { "(kept private by the main model)" } else req.description).take(2_500))
            appendLine("APP: " + (if (self) "Arin itself (an update)" else appName))
            appendLine("FILES WRITTEN OR CHANGED:")
            changed.take(30).forEach { p ->
                val c = files[p].orEmpty()
                val sig = Regex("(?m)^\\s*(?:(?:private|internal|override|suspend|data|sealed|abstract|open)\\s+)*(fun|class|object|interface)\\s+[\\w<>.,? ]+")
                    .findAll(c).map { it.value.trim() }.take(12).joinToString("; ")
                appendLine("- $p" + if (sig.isNotBlank()) ": $sig" else "")
            }
            if (lastNotes.isNotEmpty()) appendLine("NOTES FROM WRITING IT: " + lastNotes.joinToString("; ").take(1_500))
            appendLine("STATUS: " + when {
                result.message.startsWith(CODE_READY) -> "the code is written; NOT built yet — the owner will choose where to build"
                result.success -> "the code is written and the app is built"
                else -> "the code is written but the build did not finish: " + result.message.take(200)
            })
        }
        val prompt = brief + "\nWrite to the OWNER, who is not a programmer. Use plain, everyday English: no jargon (if a technical " +
            "word is unavoidable, explain it in a few words). Write exactly these sections:\n" +
            "**What I made — in plain English:** explain in detail what the code does for the person using the app: each " +
            "screen, button and feature, what happens when they tap things, and how it works behind the scenes in simple terms. " +
            "Mention anything that was left out or that they should know.\n" +
            "**Suggestions:** 3 to 6 numbered, concrete ideas to change, improve or update the app (each one short).\n" +
            "**What next:** one line offering to make any of these changes, or to " +
            (if (result.message.startsWith(CODE_READY)) "build it (they pick where below)." else if (result.success) "update it again." else "carry on fixing and building it.")
        val out = StringBuilder()
        val ctx = context
        if (ctx != null && workerOn()) {
            // Worker mode: the worker writes the explanation (it gets file names only, not the code), saving your model's credit.
            step(BuildEvent.Kind.THINKING, "The worker is writing the plain-English explanation")
            runCatching {
                kotlinx.coroutines.withTimeoutOrNull(240_000) {
                    com.arin.selffix.Worker.runFor(ctx, prompt, bossName(),
                        system = "You explain software to people who are not programmers, clearly and kindly, in plain English.",
                        project = memKey, ownerTexts = ownerTexts()).second
                }
            }.getOrNull()?.let { out.append(it) }
        } else runCatching {
            kotlinx.coroutines.withTimeoutOrNull(240_000) {
                streamModel(req.provider, req.apiKey, req.model, listOf(ChatMessage(Role.USER, prompt)),
                    "You explain software to people who are not programmers, clearly and kindly, in plain English.", req.endpoint(), conv = "build:$memKey:explain")
                    .collect { if (!it.startsWith(THOUGHT)) out.append(it) }
            }
        }
        if (!workerOn()) context?.let { com.arin.selffix.CreditSaver.addUsage(it, prompt.length + out.length) }
        val text = out.toString().trim()
        if (text.isNotBlank()) step(BuildEvent.Kind.DONE, "Explained the code in plain English, with suggestions")
        return text.ifBlank {
            // The model couldn't be reached: Arin's own short explanation (no AI), so the rule still holds.
            "**What I made — in plain English:** I wrote or changed ${changed.size} part${if (changed.size == 1) "" else "s"} of " +
                (if (self) "Arin" else appName) + " to do what you asked: " + req.description.take(300) + "\n\n" +
                "**Suggestions:**\n1. Try it and tell me anything that doesn't look or work the way you want.\n" +
                "2. Ask me to add a feature or change the design.\n\n**What next:** " +
                (if (result.message.startsWith(CODE_READY)) "pick where to build it below, or ask for a change first." else "ask for any change.")
        }
    }

    // ARIN-PROTECTED: complete-project — owner's strict rule (only the owner can change it): every project must contain all
    // the files it needs to compile into an APK in Android Studio and on GitHub, with stable library versions that work
    // with the project's compileSdk, and every resource the code uses.
    /** Libraries saved earlier with a pre-release version (alpha/beta/RC) are moved to their newest stable release. */
    private suspend fun stabilizeDeps() {
        val unstable = deps.filter { d -> !d.startsWith("plugin:") && !stableVersion(d.substringAfterLast(':')) }
        unstable.forEach { d ->
            val ksp = d.startsWith("ksp:")
            val stable = resolveDependency(d.removePrefix("ksp:").substringBeforeLast(':') + ":latest") ?: return@forEach
            deps.remove(d)
            deps += if (ksp) "ksp:$stable" else stable
            depsChanged = true
            step(BuildEvent.Kind.LIBRARY, "Using the stable ${stable.substringAfterLast(':')} of ${stable.substringBeforeLast(':')} instead of ${d.substringAfterLast(':')} (pre-releases break builds)")
        }
    }

    // ARIN-PROTECTED: toolchain-guard — before every build Arin itself (no AI, no credit) checks the whole toolchain and
    // repairs it in one go: a KSP version that doesn't match Kotlin 2.4.x is replaced with the
    // compatible one, Room's compiler always runs through KSP at the same version as room-runtime (never kapt, never
    // twice), duplicate libraries are merged, and new apps keep minSdk 26 / targetSdk 37.
    private fun normalizeToolchain() {
        val fixes = mutableListOf<String>()
        // 1) KSP plugin version.
        deps.filter { it.startsWith("plugin:com.google.devtools.ksp:") }.forEach { d ->
            val v = d.substringAfterLast(':')
            if (!ProjectTemplate.kspFits(v)) {
                deps.remove(d); deps += "plugin:com.google.devtools.ksp:" + ProjectTemplate.KSP; depsChanged = true
                fixes += "KSP $v doesn't work with Kotlin ${ProjectTemplate.KOTLIN} → KSP ${ProjectTemplate.KSP}"
            }
        }
        // 2) No kapt: kapt entries become ksp.
        deps.filter { it.startsWith("kapt:") }.forEach { d ->
            deps.remove(d); deps += "ksp:" + d.removePrefix("kapt:"); depsChanged = true
            fixes += "${d.removePrefix("kapt:").substringBeforeLast(':')} moved from kapt to KSP"
        }
        // 3) Room: the compiler only as ksp(...), at room-runtime's version.
        val room = deps.firstOrNull { it.startsWith("androidx.room:room-runtime:") }?.substringAfterLast(':')
        val compilers = deps.filter { it.removePrefix("ksp:").startsWith("androidx.room:room-compiler:") }
        if (room != null) {
            val want = "ksp:androidx.room:room-compiler:$room"
            if (compilers != listOf(want)) {
                compilers.forEach { deps.remove(it) }; deps += want; depsChanged = true
                fixes += "Room compiler set to ksp(room-compiler:$room), matching room-runtime"
            }
            deps.filter { it.startsWith("androidx.room:") && !it.startsWith("androidx.room:room-compiler") && it.substringAfterLast(':') != room }.forEach { d ->
                deps.remove(d); deps += d.substringBeforeLast(':') + ":" + room; depsChanged = true
                fixes += "${d.substringBeforeLast(':')} aligned to Room $room"
            }
        } else if (compilers.isNotEmpty() && compilers.any { !it.startsWith("ksp:") }) {
            compilers.filter { !it.startsWith("ksp:") }.forEach { deps.remove(it); deps += "ksp:$it" }; depsChanged = true
            fixes += "Room compiler moved to KSP"
        }
        // 4) Duplicates: the same library twice → keep the newest version.
        deps.groupBy { it.substringBeforeLast(':') }.filterValues { it.size > 1 }.forEach { (key, list) ->
            val keep = list.maxWith { a, b -> versionOrder.compare(a.substringAfterLast(':'), b.substringAfterLast(':')) }
            list.filter { it != keep }.forEach { deps.remove(it) }; depsChanged = true
            fixes += "${key.removePrefix("ksp:").removePrefix("plugin:")} was listed ${list.size} times — kept ${keep.substringAfterLast(':')}"
        }
        if (fixes.any { it.contains("KSP") || it.contains("Room") } && deps.any { it.startsWith("ksp:") } &&
            deps.none { it.startsWith("plugin:com.google.devtools.ksp:") }) {
            deps += "plugin:com.google.devtools.ksp:" + ProjectTemplate.KSP; depsChanged = true
        }
        // 5) Gradle files the model or an imported project wrote.
        files.keys.filter { it.endsWith(".gradle.kts") || it.endsWith(".gradle") || it.endsWith("libs.versions.toml") }.toList().forEach { p ->
            val old = files[p] ?: return@forEach
            var t = old
            t = Regex("""(id\s*\(?\s*["']com\.google\.devtools\.ksp["']\s*\)?\s*version\s*["'])([^"']+)(["'])""").replace(t) { m ->
                if (ProjectTemplate.kspFits(m.groupValues[2])) m.value else m.groupValues[1] + ProjectTemplate.KSP + m.groupValues[3]
            }
            if (p.endsWith(".toml")) t = Regex("""(?m)^(\s*ksp\s*=\s*")([^"]+)(")""").replace(t) { m ->
                if (ProjectTemplate.kspFits(m.groupValues[2])) m.value else m.groupValues[1] + ProjectTemplate.KSP + m.groupValues[3]
            }
            if (!p.endsWith(".toml")) {
                if (t.contains("com.google.devtools.ksp")) t = t.lines().filterNot { Regex("""^\s*(id\s*\(\s*["']kotlin-kapt["']\s*\)|kotlin\s*\(\s*["']kapt["']\s*\)|apply\s+plugin:\s*["']kotlin-kapt["'])""").containsMatchIn(it) }.joinToString("\n")
                if (t.contains("com.google.devtools.ksp")) t = Regex("""(?m)^(\s*)kapt\s*\(""").replace(t) { it.groupValues[1] + "ksp(" }
                if (!imported) {
                    t = Regex("""minSdk\s*=\s*\d+""").replace(t, "minSdk = 26")
                    t = Regex("""targetSdk\s*=\s*\d+""").replace(t, "targetSdk = ${ProjectTemplate.SDK}")
                }
            }
            if (t != old) {
                files[p] = t; pendingFiles[p] = t.toByteArray()
                fixes += "$p: toolchain lines repaired (KSP / kapt / SDK levels)"
            }
        }
        if (fixes.isNotEmpty()) {
            step(BuildEvent.Kind.LIBRARY, "Toolchain check fixed ${fixes.size} problem(s) before building: " + fixes.joinToString("; ").take(400))
            ledger("Toolchain check", "FIXED — " + fixes.joinToString("; ").take(300))
        }
    }

    /** Resources the code uses (R.string.x, R.drawable.x…) that no file in the project provides. */
    private fun missingResources(): List<String> {
        if (imported || self) return emptyList()
        val all = files + (ProjectTemplate.files(appName, packageName, deps.toList()) - files.keys)
        val code = all.filterKeys { it.endsWith(".kt") || it.endsWith(".java") || it.endsWith(".xml") }.values.joinToString("\n")
        val used = Regex("""(?<![\w.])R\.(string|drawable|mipmap|raw|font|color|dimen|xml|array|plurals)\.(\w+)""").findAll(code)
            .map { it.groupValues[1] to it.groupValues[2] }.toSet() +
            Regex("""@(string|drawable|mipmap|raw|font|color|dimen|xml|array|plurals)/(\w+)""").findAll(code)
                .map { it.groupValues[1] to it.groupValues[2] }.toSet()
        val values = all.filterKeys { it.contains("/res/values") }.values.joinToString("\n")
        return used.filter { (type, name) ->
            when (type) {
                "string", "color", "dimen", "array", "plurals" -> !Regex("""name="${Regex.escape(name)}"""").containsMatchIn(values) &&
                    !(type == "color" && all.keys.any { it.contains("/res/color/") && it.substringAfterLast('/').substringBefore('.') == name })
                else -> all.keys.none { k -> k.contains("/res/$type") && k.substringAfterLast('/').substringBefore('.') == name }
            }
        }.map { (t, n) -> "R.$t.$n" }.filter { !it.startsWith("R.mipmap.ic_launcher") }
    }

    /**
     * The plan in plain English, made by Arin from what it already knows (no AI, no credit): what it understood, what it
     * will do, how the other models help, and what happens next.
     */
    private fun planForOwner() {
        val ctx = context ?: return
        val boss = bossName()
        val workers = if (workerOn()) com.arin.selffix.CreditSaver.workers(ctx).joinToString(", then ") { w ->
            if (w.apiId == "offline") "the offline model" else com.arin.selffix.Worker.nice(w.model.ifBlank { w.apiId })
        }.ifBlank { "" } else ""
        val helper = runCatching { com.arin.selffix.ClaudeHelper.helper(ctx).name }.getOrDefault("")
        val understood = (if (self) req.description else requirements).lines().map { it.trim() }.firstOrNull { it.length > 15 }?.take(220)
            ?: req.description.take(220)
        step(BuildEvent.Kind.STEP, "📋 PLAN\n" +
            "• What I understood: " + understood + "\n" +
            "• What Arin will do: " + (if (req.project != null || self) "change the existing app, check every related file, then build it" else "write the complete app, then build it") +
            " on GitHub and test it on an Android emulator before giving you the link\n" +
            "• How the models help: " + (if (workers.isNotBlank()) "$boss plans and checks; $workers writes and fixes the code" else "$boss writes and checks the code") +
            (if (helper.isNotBlank()) "; $helper investigates hard errors" else "") + "\n" +
            "• What happens next: " + (if (req.stopAfterWriting) "I write the code, explain it, then ask you where to build" else "coding → build → emulator test → your download link"))
    }

    private var lastChanged = mutableListOf<String>()
    private var lastNotes = listOf<String>()

    private suspend fun runBuild(): BuildResult {
        if (!localOnly) {
            step(BuildEvent.Kind.STEP, "Checking your GitHub token")
            try {
                if (req.githubToken.isBlank()) throw ProviderException(401, "Not signed in to GitHub")
                val p = req.project?.takeIf { it.owner != LOCAL_OWNER && it.repo.isNotBlank() }
                gh.diagnose(p?.owner, p?.repo, p?.branch)
                ledger("GitHub access", "OK")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                ledger("GitHub access", "BLOCKED — " + (e.message ?: "unknown").take(200))
                if (!phoneCan || req.project != null && req.project.owner != LOCAL_OWNER) throw e
                step(BuildEvent.Kind.STEP, "GitHub isn't available (${e.message}); building on this phone only")
                localOnly = true
            }
        }

        val existing = req.project
        val repo: GitHubClient.Repo
        var notes: List<String>
        val neverUploaded = existing != null && existing.imported && !existing.uploaded && existing.lastApkUrl == null
        val resumeZip = existing?.takeIf { neverUploaded }?.zipPath?.takeIf { java.io.File(it).exists() }
        val zipToRead = req.zipPath ?: resumeZip
        var checkSha: String? = null
        var startRound = 0
        if (req.resume && existing != null && resumeZip == null) {
            // Retry: continue from the saved checkpoint instead of starting over.
            var resumeRepo = GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            var switched = false
            if (existing.owner == LOCAL_OWNER && !localOnly) {
                // Written first, built on GitHub now: the repository is created only now that the owner chose GitHub.
                resumeRepo = createRepoOrLocal(existing.repo, "Built by Arin: ${existing.description}")
                existing.pendingPaths.forEach { p ->
                    val bytes = when (p) {
                        "app/signing.keystore" -> android.util.Base64.decode(ProjectTemplate.SHARED_KEYSTORE_B64, android.util.Base64.DEFAULT)
                        "app/src/main/AndroidManifest.xml" -> (files[p] ?: ProjectTemplate.defaultManifest(appName)).toByteArray()
                        else -> (files[p] ?: (if (!imported) ProjectTemplate.files(appName, packageName, deps.toList())[p] else null))?.toByteArray()
                    }
                    if (bytes != null) pendingFiles[p] = bytes
                }
                files.forEach { (p, t) -> if (p !in pendingFiles) pendingFiles[p] = t.toByteArray() }
                if (req.target == "codespace") pendingFiles[".devcontainer/devcontainer.json"] = ProjectTemplate.devcontainer.toByteArray()
                step(BuildEvent.Kind.STEP, "Uploading the written code to the new repository ${resumeRepo.owner}/${resumeRepo.name}")
                switched = true
            }
            if (self) {
                // The saved repository may be one this account can't push to: switch to the right one and carry the
                // changes already made over to it (nothing is redone).
                val found = com.arin.selffix.SelfRepo.resolve(context, req.githubToken, "${existing.owner}/${existing.repo}", existing.branch) {
                    step(BuildEvent.Kind.STEP, it)
                }.getOrElse { return BuildResult(false, it.message ?: "Couldn't find Arin's repository on your GitHub.") }
                if (found.repo != "${existing.owner}/${existing.repo}" || found.branch != existing.branch) {
                    val (o2, n2) = found.repo.split('/').let { it[0] to it[1] }
                    resumeRepo = GitHubClient.Repo(o2, n2, found.branch, "https://github.com/${found.repo}/tree/${found.branch}")
                    step(BuildEvent.Kind.STEP, "Resuming in ${found.repo}: carrying over the changes already made")
                    val there = gh.readTextFiles(resumeRepo)
                    selfOriginal = there
                    files.forEach { (path, text) -> if (there[path] != text) pendingFiles[path] = text.toByteArray() }
                    step(BuildEvent.Kind.STEP, "Resuming: uploading ${pendingFiles.size} changed file(s) — nothing is rewritten")
                    switched = true
                } else selfOriginal = files.toMap() // ARIN-PROTECTED: strict self-update rules also apply on Retry
            }
            repo = resumeRepo
            notes = emptyList()
            startRound = existing.round
            if (!switched) {
                val restored = existing.pendingPaths.mapNotNull { p ->
                    if (p == "app/signing.keystore") return@mapNotNull p to android.util.Base64.decode(ProjectTemplate.SHARED_KEYSTORE_B64, android.util.Base64.DEFAULT)
                    if (p == ".devcontainer/devcontainer.json") return@mapNotNull p to ProjectTemplate.devcontainer.toByteArray()
                    if (p == "app/src/main/AndroidManifest.xml" && files[p] == null) return@mapNotNull p to ProjectTemplate.defaultManifest(appName).toByteArray()
                    val text = files[p] ?: (if (!imported) ProjectTemplate.files(appName, packageName, deps.toList())[p] else null)
                    text?.let { p to it.toByteArray() }
                }
                when {
                    restored.isNotEmpty() -> {
                        restored.forEach { (p, b) -> pendingFiles[p] = b }
                        step(BuildEvent.Kind.STEP, "Resuming: uploading ${restored.size} file(s) that didn't reach GitHub last time")
                    }
                    existing.lastSha != null -> {
                        checkSha = existing.lastSha
                        step(BuildEvent.Kind.STEP, "Resuming: checking the last build on GitHub (no code is rewritten)")
                    }
                    else -> step(BuildEvent.Kind.STEP, "Resuming: starting a fresh build of the current code")
                }
            }
        } else if ((existing == null && req.zipPath != null) || resumeZip != null) {
            val zipLabel = req.zipName ?: existing?.zipName ?: "the project"
            if (resumeZip != null) step(BuildEvent.Kind.STEP, "The first upload didn't finish — uploading the whole project again")
            step(BuildEvent.Kind.STEP, "Unpacking $zipLabel")
            onProgress(-1f, "Reading ${mb(java.io.File(zipToRead!!).length())}…")
            val zip = withContext(Dispatchers.IO) { ZipImport.read(java.io.File(zipToRead), zipLabel) }
            onProgress(null, "")
            importedName = zip.name
            zip.files.forEach { (path, bytes) ->
                pendingFiles[path] = bytes
                if (ZipImport.isText(path) && bytes.size < 200_000) files[path] = bytes.toString(Charsets.UTF_8)
            }
            pendingDisk.putAll(zip.bigFiles)
            if (zip.tooBig.isNotEmpty()) {
                step(BuildEvent.Kind.ERROR, "Skipped ${zip.tooBig.size} file(s) over 95 MB (GitHub's limit): ${zip.tooBig.take(3).joinToString { it.substringAfterLast('/') }}")
            }
            pendingFiles[".github/workflows/${ProjectTemplate.IMPORT_WORKFLOW_FILE}"] = ProjectTemplate.importWorkflow.toByteArray()
            step(BuildEvent.Kind.STEP, "Found ${zip.count} files in \"${zip.name}\"" + if (zip.skipped > 0) " (skipped ${zip.skipped} build/cache files)" else "")
            val repoName = zip.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "project" }.take(40) +
                "-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            repo = if (existing != null) {
                GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            } else {
                createRepoOrLocal(repoName, "Uploaded to Arin: ${zip.name}")
            }
            notes = emptyList()
        } else if (self) {
            // Arin updating itself: read its source from GitHub, change it, build it with its own workflow.
            // Find the repository with Arin's source that this GitHub account can push to (else GitHub says "Not Found").
            val found = com.arin.selffix.SelfRepo.resolve(context, req.githubToken, req.selfRepo, req.selfBranch) { step(BuildEvent.Kind.STEP, it) }
                .getOrElse { return BuildResult(false, it.message ?: "Couldn't find Arin's repository on your GitHub.") }
            val (owner, name) = found.repo.split('/').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            if (owner.isBlank() || name.isBlank()) return BuildResult(false, "Set Arin's source repository in Settings → GitHub first.")
            repo = GitHubClient.Repo(owner, name, found.branch.ifBlank { "main" }, "https://github.com/$owner/$name/tree/${found.branch}")
            importedName = "Arin"
            step(BuildEvent.Kind.STEP, "Reading Arin's source from ${found.repo} (${repo.branch})")
            onProgress(-1f, "Downloading source files…")
            files.clear()
            files.putAll(gh.readTextFiles(repo))
            onProgress(null, "")
            if (files.isEmpty()) return BuildResult(false, "Couldn't read Arin's source from ${found.repo}@${repo.branch}. Check the repository in Settings → GitHub.")
            selfOriginal = files.toMap()
            step(BuildEvent.Kind.DONE, "Read ${files.size} source files")
            step(BuildEvent.Kind.THINKING, "Working out the changes to Arin with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(changePrompt() + assets, req.attachments))
            notes = inspect(notes, "the changes, before building")
            if (pendingFiles.isEmpty() && !depsChanged) {
                return BuildResult(false, "The model didn't change any of Arin's files. Try describing the change differently.", repo.url, log = modelReplyLog())
            }
            uploadedOnce = true
            saveProject(repo, null) // checkpoint: the changes are saved, so Resume carries on from here
            if (req.stopAfterWriting) {
                // The code is written: the owner chooses where to build it (the tab asks, then Resume builds it).
                step(BuildEvent.Kind.DONE, "The code is written (${pendingFiles.size} file(s)). Waiting for you to choose where to build")
                return BuildResult(true, CODE_READY + pendingFiles.keys.joinToString(","), repo.url)
            }
        } else if (existing == null) {
            step(BuildEvent.Kind.THINKING, "Planning and writing the app with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(firstPrompt() + assets, req.attachments))
            // Some models plan in prose and forget to write the files: ask again, strictly, up to twice.
            var retries = 0
            while (files.isEmpty() && retries < 2) {
                retries++
                step(BuildEvent.Kind.STEP, "No files came back. Asking ${req.provider.label} again for the code (try $retries of 2)")
                notes = apply(ask(firstPrompt() + assets + formatReminder(), req.attachments))
            }
            if (files.isNotEmpty()) {
                notes = finishWriting(notes)
                notes = reviewAgainstRequirements(notes)
            }
            if (files.isEmpty()) {
                return BuildResult(
                    false,
                    "The model didn't return any files, even after asking again, so there was nothing to build. " +
                        "Below is the end of what it sent back. Try again, or pick another model.",
                    log = modelReplyLog(),
                )
            }
            val repoName = "$slug-" + SimpleDateFormat("yyMMdd-HHmm", Locale.US).format(Date())
            repo = if (req.stopAfterWriting) localRepo(repoName) else createRepoOrLocal(repoName, "Built by Arin: ${req.description}")
            ProjectTemplate.files(appName, packageName, deps.toList()).forEach { (p, c) -> pendingFiles[p] = c.toByteArray() }
            // Sign every built app with the one shared key, so a rebuild updates over the installed app cleanly.
            pendingFiles["app/signing.keystore"] = android.util.Base64.decode(ProjectTemplate.SHARED_KEYSTORE_B64, android.util.Base64.DEFAULT)
            if (req.codespace && !localOnly) {
                // Make an opened codespace ready to build Android, and open one now (if the sign-in allows it).
                pendingFiles[".devcontainer/devcontainer.json"] = ProjectTemplate.devcontainer.toByteArray()
                step(BuildEvent.Kind.STEP, "Opening a GitHub Codespace on the project")
                codespaceUrl = gh.createCodespace(repo) ?: gh.codespaceLink(repo)
            }
            if ("app/src/main/AndroidManifest.xml" !in files) {
                pendingFiles["app/src/main/AndroidManifest.xml"] = ProjectTemplate.defaultManifest(appName).toByteArray()
            }
        } else {
            repo = GitHubClient.Repo(existing.owner, existing.repo, existing.branch, existing.repoUrl)
            step(BuildEvent.Kind.THINKING, "Working out the changes with ${req.provider.label}")
            val assets = addAttachedAssets()
            notes = apply(ask(changePrompt() + assets, req.attachments))
            if (pendingFiles.isEmpty() && !depsChanged) {
                return BuildResult(false, "The model didn't change any files. Try describing the change differently.", repo.url, existing.lastApkUrl, log = modelReplyLog())
            }
            if (!imported) {
                notes = finishWriting(notes)
                notes = reviewAgainstRequirements(notes)
            }
        }
        // In worker mode your model does ALL the checking (the helper never reads the code).
        if (!workerOn() && (helperOn() || com.arin.selffix.CreditSaver.on(context)) && !req.resume && pendingFiles.isNotEmpty()) helperReview()
        if (!req.resume) notes = inspect(notes, "the finished code, before building")
        // ARIN-PROTECTED: complete-project — before building, every resource the code uses must exist (Arin checks, no AI).
        missingResources().takeIf { it.isNotEmpty() }?.let { miss ->
            step(BuildEvent.Kind.STEP, "The code uses ${miss.size} resource(s) that don't exist yet (${miss.take(5).joinToString()}). Adding them before building")
            notes = apply(ask("These resources are used in the code but no file provides them, so the app would not compile: " +
                miss.joinToString() + ".\nWrite the missing resource files now (res/values/strings.xml entries in full, vector drawables " +
                "as XML in res/drawable, etc.), or change the code to not need them. Reply with every file you change, in full."))
        }
        saveProject(repo, null)
        if (req.stopAfterWriting && !self && !(existing == null && req.zipPath != null) && !req.resume) {
            step(BuildEvent.Kind.DONE, "The code is written (${pendingFiles.size} file(s)). Waiting for you to choose where to build")
            return BuildResult(true, CODE_READY + pendingFiles.keys.joinToString(","), if (repo.owner == LOCAL_OWNER) null else repo.url)
        }
        // Continuing after the owner chose where to build: a codespace on request.
        if (req.target == "codespace" && !localOnly && codespaceUrl == null && repo.owner != LOCAL_OWNER) {
            if (!self) pendingFiles[".devcontainer/devcontainer.json"] = ProjectTemplate.devcontainer.toByteArray()
            step(BuildEvent.Kind.STEP, "Opening a GitHub Codespace on the project")
            codespaceUrl = gh.createCodespace(repo) ?: gh.codespaceLink(repo)
        }

        // Build on the phone first when Linux has the Android build tools: errors are found and fixed here,
        // and GitHub then only builds code that already compiles (and gives the download links).
        val localSlug = repo.name
        // The owner said where: "github"/"codespace" means GitHub only — no phone build first.
        val githubOnly = req.target == "github" || req.target == "codespace"
        var useLocal = localOnly || !githubOnly && phoneCan && (existing == null || LocalBuild.hasCopy(context!!, localSlug))
        if (useLocal && existing == null) {
            LocalBuild.write(context!!, localSlug, files.mapValues { it.value.toByteArray() })
        }
        if (useLocal && existing != null && context != null && !LocalBuild.hasCopy(context, localSlug)) ensureLocalCopy(repo, localSlug)
        var localRounds = 0
        var phoneApk: String? = null

        var round = startRound
        while (true) {
          currentRound = round
          val sha: String
          val resumeSha = checkSha
          if (resumeSha != null) {
            // Already on GitHub: just look at (or wait for) that build.
            checkSha = null
            sha = resumeSha
          } else {
            if (!self) runCatching { normalizeToolchain() }
            if (!self) runCatching { syntaxGuard() }
            if (!self) runCatching { importGuard() }
            if (!self) runCatching { duplicateGuard() }
            if (depsChanged) {
                val gradle = ProjectTemplate.files(appName, packageName, deps.toList())
                pendingFiles["app/build.gradle.kts"] = gradle.getValue("app/build.gradle.kts").toByteArray()
                // The top-level file changes too when a code generator (KSP, for Room) is added.
                pendingFiles["build.gradle.kts"] = gradle.getValue("build.gradle.kts").toByteArray()
                depsChanged = false
            }
            // Make sure GitHub always has the build instructions (harmless if unchanged).
            if (self) {
                // Arin's repo already has its workflow and signing key: leave them alone — except that Arin itself (not
                // the model) adds the emulator test when it's missing (ARIN-PROTECTED: test-before-handover).
                val wfPath = ".github/workflows/$SELF_WORKFLOW"
                val wf = files[wfPath]
                if (wf != null && !wf.contains("arin-test.sh")) {
                    val patched = wf.trimEnd() + "\n\n" + ProjectTemplate.testJob("$SELF_TAG_PREFIX${'$'}{{ github.run_number }}", "Arin-v*.apk").prependIndent("  ") + "\n"
                    files[wfPath] = patched
                    pendingFiles[wfPath] = patched.toByteArray()
                    step(BuildEvent.Kind.STEP, "Adding the emulator crash test to Arin's build")
                }
                if (files[ProjectTemplate.TEST_SCRIPT_PATH] != ProjectTemplate.testScript) {
                    files[ProjectTemplate.TEST_SCRIPT_PATH] = ProjectTemplate.testScript
                    pendingFiles[ProjectTemplate.TEST_SCRIPT_PATH] = ProjectTemplate.testScript.toByteArray()
                }
            } else if (imported) {
                pendingFiles[".github/workflows/${ProjectTemplate.IMPORT_WORKFLOW_FILE}"] = ProjectTemplate.importWorkflow.toByteArray()
                if (files[ProjectTemplate.TEST_SCRIPT_PATH] != ProjectTemplate.testScript) pendingFiles[ProjectTemplate.TEST_SCRIPT_PATH] = ProjectTemplate.testScript.toByteArray()
            } else {
                ProjectTemplate.files(appName, packageName, deps.toList())[".github/workflows/build.yml"]?.let {
                    pendingFiles[".github/workflows/build.yml"] = it.toByteArray()
                }
                if (files[ProjectTemplate.TEST_SCRIPT_PATH] != ProjectTemplate.testScript) pendingFiles[ProjectTemplate.TEST_SCRIPT_PATH] = ProjectTemplate.testScript.toByteArray()
            }
            if (useLocal) {
                val ctx = context!!
                LocalBuild.write(ctx, localSlug, pendingFiles.toMap())
                LocalBuild.writeFiles(ctx, localSlug, pendingDisk.toMap())
                step(BuildEvent.Kind.BUILD, "Compiling on this phone in Linux (the first build downloads libraries and can take 10+ minutes)")
                when (val out = LocalBuild.build(ctx, localSlug, appName) { }) {
                    is LocalBuild.Outcome.Ok -> {
                        phoneApk = out.apkPath
                        if (localOnly) {
                            if (self && repo.owner != LOCAL_OWNER && pendingFiles.isNotEmpty()) {
                                step(BuildEvent.Kind.STEP, "Saving the changes to Arin's source on GitHub too")
                                runCatching { currentSha = gh.commit(repo, pendingFiles.toMap(), "Arin: ${req.description.take(60)} (built on the phone)", emptyMap()) { _, _, _, _ -> } }
                            }
                            val zip = LocalBuild.saveSourceZip(ctx, localSlug, appName)
                            step(BuildEvent.Kind.DONE, "APK ready (${mb(out.size)}): ${out.apkPath}")
                            saveProject(repo, null)
                            step(BuildEvent.Kind.DONE, "Done")
                            if (self) return BuildResult(true, "The new version of Arin is built on this phone.",
                                phoneApk = out.apkPath, phoneSourceZip = zip, phoneInstallPath = out.installFile?.absolutePath)
                            // ARIN-PROTECTED: test-before-handover — a phone can't run an Android emulator, so a phone-only build
                            // is not handed over: it must be tested on GitHub's emulator first.
                            return BuildResult(
                                true,
                                "It compiles on this phone, but I can't hand it over yet: every app must first be opened and tested " +
                                    "on an Android emulator, and a phone can't run one. Say \"build on github\" — GitHub builds it, " +
                                    "tests it on its emulator, and then you get the download link.",
                                phoneSourceZip = zip,
                            )
                        }
                        step(BuildEvent.Kind.DONE, "Compiled on this phone (${mb(out.size)}), saved to ${out.apkPath}. Now building on GitHub for the download links")
                    }
                    is LocalBuild.Outcome.Errors -> {
                        if (localRounds < (if (localOnly) maxFixRounds else baseFixRounds)) {
                            localRounds++
                            val query = mainError(out.log)
                            val web = researchFirst(query, out.log) // ARIN-PROTECTED: research the internet before every fix
                            val count = out.log.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
                            step(BuildEvent.Kind.ERROR, "Phone build failed with $count error${if (count == 1) "" else "s"}: ${plainError(out.log)}. Fixing on the phone (${com.arin.selffix.AutoFix.roundText(localRounds, if (localOnly) maxFixRounds else baseFixRounds)})")
                            notes = if (quickFix(out.log)) notes else {
                                val left = remainingErrors ?: out.log
                                val guideLocal = listOf(apiIndex(left), web).filter { it.isNotBlank() }.joinToString("\n\n")
                                if (workerOn()) privateFix(left, notes, guideLocal) else apply(ask(fixPrompt(left, notes, guideLocal)))
                            }
                            continue
                        }
                        if (localOnly) {
                            saveProject(repo, null)
                            return BuildResult(
                                false,
                                "The build still fails on the phone after $localRounds fix rounds. The errors are below; say \"try again\" to keep fixing.",
                                log = out.log,
                            )
                        }
                        step(BuildEvent.Kind.ERROR, "Still failing on the phone after $localRounds fixes; trying GitHub's build")
                        useLocal = false
                    }
                    is LocalBuild.Outcome.Skipped -> {
                        if (localOnly) {
                            saveProject(repo, null)
                            return BuildResult(false, "The phone couldn't run the build: ${out.reason.take(200)}", log = out.reason)
                        }
                        step(BuildEvent.Kind.STEP, "Couldn't build on this phone (${out.reason.take(120)}); GitHub will build it")
                        useLocal = false
                    }
                }
            }
            val toUpload = pendingFiles.size + pendingDisk.size
            step(BuildEvent.Kind.STEP, "Uploading $toUpload changed file${if (toUpload == 1) "" else "s"} to GitHub")
            val message = when {
                round > 0 -> "Fix build errors (round $round)"
                self -> "Arin: ${req.description.take(60)}"
                existing != null -> "Change: ${req.description.take(60)}"
                req.zipPath != null -> "Uploaded project from Arin"
                else -> "Initial app from Arin"
            }
            val total = pendingFiles.size + pendingDisk.size
            var lastPct = -1
            sha = gh.commit(repo, pendingFiles.toMap(), message, pendingDisk.toMap()) { n, of, sent, size ->
                val pct = if (size > 0) (sent * 100 / size).toInt() else n * 100 / of.coerceAtLeast(1)
                if (pct != lastPct) {
                    lastPct = pct
                    onProgress(pct / 100f, "$n of $of files · ${mb(sent)} of ${mb(size)}")
                }
            }
            onProgress(null, "")
            mem?.recordFiles(pendingFiles.keys + pendingDisk.keys)
            ledger("Code written and uploaded to GitHub", "COMPLETED")
            step(BuildEvent.Kind.DONE, "Upload finished: $total file${if (total == 1) "" else "s"} (${mb(pendingFiles.values.sumOf { (it?.size ?: 0).toLong() } + pendingDisk.values.sumOf { it.length() })})")
            pendingFiles.clear()
            pendingDisk.values.forEach { it.delete() }
            pendingDisk.clear()
            uploadedOnce = true
            mem?.recordCommit(sha, currentSha, message)
            currentSha = sha
            saveProject(repo, null)
          }

            step(BuildEvent.Kind.BUILD, if (testing) "Building the APK on GitHub, then testing it on an Android emulator (about 6–12 minutes)"
                else "Building the APK on GitHub (usually 3–5 minutes)")
            val waited = waitForRun(repo, sha)
            if (steerNow) {
                // Steered during the build: change the code now with the direction, then upload and build again.
                steerNow = false
                step(BuildEvent.Kind.THINKING, "You sent a direction during the build — applying it now, then building again")
                notes = apply(ask(steerPrompt()))
                saveProject(repo, null)
                continue
            }
            val run = waited
                ?: return BuildResult(
                    false,
                    if (neverUploaded) {
                        "GitHub didn't start a build because this project's first upload never finished. Tap New, then upload the zip again."
                    } else {
                        "GitHub didn't start a build. Open the repo on GitHub → Actions: if Actions is turned off for the repo, " +
                            "turn it on and try again; otherwise sign out and sign in with GitHub again in Settings."
                    },
                    repo.url,
                )

            var crashLog: String? = null
            ledger("Build on GitHub", if (run.conclusion == "success") "COMPLETED" else "IN PROGRESS — fixing errors")
            mem?.recordBuild(run.number, run.conclusion == "success", "")
            if (run.conclusion == "success") {
                lastFingerprint?.let { mem?.markFixed(it) }
                mem?.setAllRequirements(ProjectMemory.Status.IMPLEMENTED, setOf(ProjectMemory.Status.PENDING, ProjectMemory.Status.IN_PROGRESS))
            }
            if (run.conclusion == "success") {
                step(BuildEvent.Kind.STEP, "Build succeeded. Getting the download link")
                // Only hand over a link once the APK file is really there.
                var apk: String? = null
                var size: Long? = null
                for (i in 0 until 20) {
                    apk = if (self) gh.apkForTag(repo, "$SELF_TAG_PREFIX${run.number}") else (gh.apkForTag(repo, "build-${run.number}") ?: gh.latestApkUrl(repo))
                    size = apk?.let { ApkInstaller.check(it) }
                    if (apk != null && size != null) break
                    delay(3000)
                }
                if (apk == null || size == null) {
                    return BuildResult(false, "The build worked, but GitHub hasn't published the APK yet. Open the repo's Releases on GitHub in a minute.", repo.url)
                }
                step(BuildEvent.Kind.DONE, "APK ready: ${mb(size)}")
                saveProject(repo, apk)
                // The emulator test (it ran in the same GitHub job, after the APK was published).
                var testNote = ""
                var shot: String? = null
                if (testing) {
                    ledger("Emulator test (open + tap around)", "TESTING")
                    val tag = if (self) "$SELF_TAG_PREFIX${run.number}" else "build-${run.number}"
                    val report = gh.releaseText(repo, tag, "arin-test-report.txt")
                    shot = gh.releaseAssetLink(repo, tag, "arin-test-screen.png")
                    val result = report?.lineSequence()?.firstOrNull { it.startsWith("RESULT:") }?.removePrefix("RESULT:")?.trim()
                    val why = report?.lineSequence()?.firstOrNull { it.startsWith("ERROR:") }?.removePrefix("ERROR:")?.trim().orEmpty()
                    when (result) {
                        "PASS" -> {
                            testNote = "Tested on an Android emulator: it opens, and tapping around didn't crash it."
                            step(BuildEvent.Kind.DONE, testNote)
                            ledger("Emulator test (open + tap around)", "VERIFIED")
                            mem?.recordBuild(run.number, true, "emulator test passed")
                            mem?.setAllRequirements(ProjectMemory.Status.VERIFIED, setOf(ProjectMemory.Status.IMPLEMENTED, ProjectMemory.Status.IN_PROGRESS))
                            ledger("APK handed over", "COMPLETED")
                        }
                        "CRASH", "NOT_RUNNING", "INSTALL_FAILED" -> {
                            val plain = when (result) {
                                "INSTALL_FAILED" -> "The APK wouldn't install on the emulator"
                                "NOT_RUNNING" -> "The app closed by itself right after it opened"
                                else -> "The app crashed on the emulator"
                            } + (if (why.isNotBlank()) ": $why" else "")
                            if (true) { // ARIN-PROTECTED: test-before-handover — a crashing app is always fixed, never handed over
                                crashLog = "RUNTIME PROBLEM (the app builds, but fails when it runs on an Android emulator).\n" +
                                    "ERROR: $plain\n\nTest report and crash log:\n" + report.orEmpty().take(8_000)
                                step(BuildEvent.Kind.ERROR, "$plain. Fixing it")
                                ledger("Emulator test (open + tap around)", "IN PROGRESS — fixing a crash")
                            } else {
                                testNote = "⚠️ $plain. The APK is ready, but say \"fix the crash\" to keep fixing it."
                                step(BuildEvent.Kind.ERROR, testNote)
                            }
                        }
                        else -> {
                            // ARIN-PROTECTED: test-before-handover — not tested = not handed over.
                            step(BuildEvent.Kind.ERROR, "The app built, but GitHub's Android emulator couldn't run the crash test this time")
                            ledger("Emulator test (open + tap around)", "BLOCKED — the emulator couldn't run; say \"try again\"")
                            saveProject(repo, apk)
                            return BuildResult(
                                false,
                                "The app compiled, but the crash test on GitHub's Android emulator couldn't run, so I'm not handing " +
                                    "over the APK until it's tested. Say \"try again\" to build and test it again.",
                                repo.url, log = report.orEmpty().take(4_000), runUrl = run.url,
                            )
                        }
                    }
                }
              if (crashLog == null) {
                step(BuildEvent.Kind.DONE, "Done")
                return BuildResult(
                    true,
                    when {
                        self -> "The new version of Arin is ready. Tap Install to update."
                        existing != null -> "Your changes are built."
                        req.zipPath != null -> "Your project compiled to an APK."
                        else -> "Your app is ready."
                    },
                    repo.url,
                    apk,
                    phoneApk = phoneApk,
                    codespaceUrl = codespaceUrl,
                    autoDownloadApk = req.codespace,
                    testNote = testNote,
                    testScreenshot = shot,
                )
              }
            }

            val errors = crashLog ?: kotlin.run {
                step(BuildEvent.Kind.STEP, "Reading the build errors")
                gh.failureSummary(repo, run.id)
            }
            if (crashLog != null && round >= maxFixRounds) {
                // ARIN-PROTECTED: test-before-handover — still crashing: no APK link; the owner says "try again" to keep fixing.
                return BuildResult(
                    false,
                    "The app builds, but it still crashes on the Android emulator after $round fix rounds, so I'm not handing over " +
                        "the APK yet. The crash is below; say \"try again\" to keep fixing it.",
                    repo.url, log = crashLog, runUrl = run.url,
                )
            }
            if (crashLog == null && round >= maxFixRounds) {
                return BuildResult(
                    false,
                    "The build still fails after $round fix round${if (round == 1) "" else "s"}. The errors are below; " +
                        "say \"try again\" to keep fixing, or try a stronger model.",
                    repo.url,
                    log = errors,
                    runUrl = run.url,
                )
            }
            round++
            currentRound = round
            // ARIN-PROTECTED: never-stuck — the same error again means the last method failed: change method, not repeat it.
            val sig = com.arin.selffix.CreditSaver.signature(errors).ifBlank { realErrorLine(errors).take(160) }
            val same = (sameErrors[sig] ?: 0) + 1
            sameErrors[sig] = same
            if (same >= 2 && !self) autoRepair(errors)?.let { step(BuildEvent.Kind.LIBRARY, it) }
            stuck = same
            // Look the main error up online every time, and give the model what others did to fix it.
            val web = researchFirst(mainError(errors) + (if (same >= 3) " solution " + listOf("alternative", "workaround", "fixed by", "correct way")[same % 4] else ""), errors) // ARIN-PROTECTED: research the internet before every fix
            val count = errors.lines().count { it.startsWith("e: ") || it.contains("error:", true) }.coerceAtLeast(1)
            if (crashLog != null) step(BuildEvent.Kind.ERROR, "Fixing the crash (${com.arin.selffix.AutoFix.roundText(round, maxFixRounds)}), then building and testing again")
            else step(BuildEvent.Kind.ERROR, "Build failed with $count error${if (count == 1) "" else "s"}: ${plainError(errors)}. Fixing (${com.arin.selffix.AutoFix.roundText(round, maxFixRounds)})")
            if (same >= 3) step(BuildEvent.Kind.THINKING, "The same error came back $same times — trying a completely different method this time")
            // Fingerprint compared with last round's: progress, or the same problem back again?
            lastFingerprint?.let { prev ->
                if (prev != sig) {
                    mem?.markFixed(prev)
                    ledger("Fixing build errors", "IN PROGRESS")
                    step(BuildEvent.Kind.DONE, "Progress: the previous error is fixed — now a different one: ${plainError(errors).take(100)}")
                }
            }
            lastFingerprint = sig
            // ARIN-PROTECTED: whole-problem-consultation — your model sends the helper (Claude) ONE complete technical package
            // (the full error report, the code at every error site, versions, libraries, what was already tried) and asks for
            // a root-cause diagnosis and a coherent fix. A refusal is noted and work goes on; the same request is never resent.
            // Arin's own fixes first (brackets, imports — no AI): when they cover every error, no model is asked.
            val quick = quickFix(errors)
            val left = remainingErrors ?: errors
            val consult = if (quick) "" else consultHelper(left, same)
            val known = mem?.knownAbout(sig).orEmpty()
            if (known.contains("WAS FIXED BEFORE")) step(BuildEvent.Kind.DONE, "This project hit this exact error before — reusing the fix that worked then")
            val impact = relatedFiles(errorFiles(errors)).takeIf { it.isNotEmpty() }?.let {
                "FILES THAT USE THE BROKEN CODE (check and change them together if needed):\n" + it.joinToString("\n")
            }.orEmpty()
            val guide = listOf(known, impact, if (quick) "" else apiIndex(left), web, consult).filter { it.isNotBlank() }.joinToString("\n\n")
            val before = files.toMap()
            notes = if (quick) notes else if (workerOn() && same < 2) privateFix(left, notes, guide) else apply(ask(fixPrompt(left, notes, guide)))
            // Remember what this round changed, so the next round doesn't repeat a fix that didn't work.
            val changedNow = files.keys.filter { files[it] != before[it] }
            tried += "Round $round (" + plainError(errors).take(80) + "): changed " +
                (changedNow.take(6).joinToString { it.substringAfterLast('/') }.ifBlank { "nothing" }) +
                (lastNotes.firstOrNull()?.let { " — $it" }?.take(160) ?: "")
            mem?.recordTry(sig, realErrorLine(errors), tried.last())
            saveProject(repo, null)
        }
    }

    private fun mb(bytes: Long) = if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

    private fun saveProject(repo: GitHubClient.Repo, apk: String?) {
        lastRepo = repo
        onProject(
            Project(
                appName = appName,
                packageName = packageName,
                owner = repo.owner,
                repo = repo.name,
                branch = repo.branch,
                repoUrl = repo.url,
                description = req.project?.description ?: req.description.ifBlank { "Uploaded project" },
                requirements = requirements,
                files = files.toMap(),
                deps = deps.toList(),
                lastApkUrl = apk ?: req.project?.lastApkUrl,
                imported = imported,
                selfUpdate = self,
                zipPath = req.zipPath ?: req.project?.zipPath,
                zipName = req.zipName ?: req.project?.zipName,
                uploaded = uploadedOnce || req.project?.uploaded == true,
                lastSha = currentSha,
                pendingPaths = pendingFiles.keys.toList(),
                round = currentRound,
            ),
        )
    }

    /**
     * Asks the model, letting it search the web or read pages before it writes files, and reports
     * each file as it starts streaming in.
     */
    /** Adds attached images, fonts, sounds and other files to the project; returns notes for the prompt. */
    private fun addAttachedAssets(): String {
        val notes = mutableListOf<String>()
        req.attachments.filter { it.isBinary && !it.isZip }.forEach { a ->
            val ext = a.name.substringAfterLast('.', "").lowercase()
            val base = a.name.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')
                .let { if (it.isEmpty() || !it.first().isLetter()) "file_$it" else it }.take(40)
            val path = when {
                ext in listOf("png", "jpg", "jpeg", "webp") || (a.kind == com.arin.data.Attachment.IMAGE && a.mime == "image/jpeg") ->
                    "app/src/main/res/drawable/$base.${if (ext == "jpeg" || (a.mime == "image/jpeg" && ext !in listOf("png", "webp"))) "jpg" else ext}"
                ext in listOf("ttf", "otf") -> "app/src/main/res/font/$base.$ext"
                ext in listOf("mp3", "ogg", "wav", "m4a", "aac", "mp4", "webm") -> "app/src/main/res/raw/$base.$ext"
                else -> "app/src/main/assets/${a.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
            }
            val bytes = runCatching { java.io.File(a.path).readBytes() }.getOrNull() ?: return@forEach
            if (bytes.size > MAX_DOWNLOAD) {
                notes += "${a.name} is over 15 MB, so it wasn't added to the app."
                return@forEach
            }
            pendingFiles[path] = bytes
            val ref = when {
                path.contains("/res/drawable/") -> "R.drawable.${path.substringAfterLast('/').substringBeforeLast('.')}"
                path.contains("/res/font/") -> "R.font.${path.substringAfterLast('/').substringBeforeLast('.')}"
                path.contains("/res/raw/") -> "R.raw.${path.substringAfterLast('/').substringBeforeLast('.')}"
                else -> "assets/${path.substringAfterLast('/')}"
            }
            step(BuildEvent.Kind.DOWNLOAD, "Added your file ${a.name} as $ref")
            notes += "The user's file ${a.name} is in the project as $ref; use it where it fits."
        }
        return if (notes.isEmpty()) "" else "\n\nFiles from the user:\n" + notes.joinToString("\n") { "- $it" }
    }

    private suspend fun ask(prompt: String, attachments: List<com.arin.data.Attachment> = emptyList()): String {
        val effort = if (req.effort == Effort.AUTO) Effort.HIGH else req.effort
        val system = (if (imported) ProjectTemplate.importedSystemPrompt else ProjectTemplate.systemPrompt(packageName)) +
            ProjectTemplate.webInstructions +
            (if (context != null) "\n\n" + com.arin.selffix.ClaudeHelper.instructions(context, helperTab) else "")
        val cap = if (false) 3_000 else 8_000
        // Models that can't see get pictures and videos described for them.
        val (seenFiles, seenText) = if (context != null && attachments.isNotEmpty())
            com.arin.selffix.VisionBridge.prepareFiles(context, req.provider, req.model, attachments) { step(BuildEvent.Kind.STEP, it) }
        else attachments to ""
        // ARIN-PROTECTED: ai-helper during builds — with the helper on, it gives the model a plan before it writes (it can read
        // the person's files), checks the written code, and gives the solution for every error. The model does the work.
        var helperPlan = ""
        // ARIN-PROTECTED: helper-sees-only-the-boss — no plan from the person's request (the helper may not see it).
        if (false && !helperPlanned && helperOn() && context != null && com.arin.selffix.ClaudeHelper.route(context) != null) {
            helperPlanned = true
            step(BuildEvent.Kind.THINKING, "Asking ${com.arin.selffix.ClaudeHelper.helper(context).name} for a plan before writing")
            val plan = com.arin.selffix.ClaudeHelper.ask(
                context = context,
                question = "An AI model is about to write/change the code of an Android app (Kotlin, Jetpack Compose, Gradle) " +
                    (if (self) "— the Arin app itself, updated in place" else "") + ". The model asks you for a short, concrete plan: " +
                    "which files to create or change, libraries and versions that build, and pitfalls that make Android builds fail.\n\n" +
                    "What the person wants:\n" + req.description.take(4_000) +
                    (if (req.requirements.isNotBlank()) "\n\nEverything asked so far:\n" + req.requirements.take(4_000) else "") +
                    "\n\nWrite to the model, not to the person. Be brief.",
                source = helperTab,
            ) { step(BuildEvent.Kind.THINKING, it.take(120)) }
            if (usableHelp(plan)) {
                helperPlan = "\n\n[Plan from the AI helper — use it where right; you write the code:]\n" + plan.take(6_000)
                step(BuildEvent.Kind.THINKING, "🟣 Got the helper's plan; the model writes the code")
            }
        }
        var steps = 0
        claudeAsks = 0
        // ARIN-PROTECTED: worker-mode — the worker writes; the boss (req.provider/model) only inspects (see [inspect]).
        // ARIN-PROTECTED: heavy-work-off-the-boss — in worker mode the workers write, then the helper; your model never writes.
        val pool = if (workerOn()) workerTargets() + listOfNotNull(helperWriter()) else emptyList()
        if (workerOn() && pool.isEmpty()) throw IllegalStateException("Worker mode is on, but no worker or helper can be reached " +
            "(check their keys or sign-in in Settings → Credit saver). Your main model doesn't write code in worker mode, to save its credit.")
        // ARIN-PROTECTED: owner-words-only-to-main — workers get the main model's brief, never the owner's text or files.
        val toWorkers = pool.isNotEmpty()
        val working = mutableListOf(
            if (toWorkers) ChatMessage(Role.USER, workerSafe(prompt + helperPlan + steeringNote(), seenText))
            else ChatMessage(Role.USER, prompt + seenText + helperPlan + steeringNote(), seenFiles))
        var wi = 0
        var target: Target = pool.firstOrNull() ?: bossTarget()
        val workSystem = if (target.worker) workerSystem(system) else system
        if (target.worker) step(BuildEvent.Kind.THINKING, "${nice(target.model)} is following ${bossName()}'s instructions: writing the code (${bossName()} inspects it after)")
        while (true) {
            val sb = StringBuilder()
            val thinking = StringBuilder()
            var scanned = 0
            var about: String? = null
            // ARIN-PROTECTED: steer-immediately — a direction sent while the model writes stops this answer at once, and the
            // model starts again WITH the direction (not queued for later).
            val steeredNow = try { resilient(
                attempt = { model -> streamModel(target.provider, target.key, model, working.toList(), if (target.worker) workSystem else system, target.endpoint,
                    conv = "build:$memKey" + if (target.worker) ":worker" else "") },
                model = target.model,
                fallback = { if (req.provider != com.arin.llm.Provider.Free) null else alternativeModel(req.provider, req.apiKey, req.model) }, // ARIN-PROTECTED: never change the Custom API — nor Claude, GPT or the Claude Code login: only the person switches them
                onRetry = { step(BuildEvent.Kind.STEP, it) },
            ).collect { raw ->
                if (hasSteering()) throw SteerNow()
                if (raw.startsWith(THOUGHT)) {
                    // Show what the model is thinking, live, under the current step.
                    thinking.append(raw.substring(1))
                    // Only the newest part is shown, so only look at the end (the whole text grows with every word).
                    val now = System.currentTimeMillis()
                    if (now - lastThinkingNote > 400) {
                        lastThinkingNote = now
                        onProgress(-1f, "${nice(target.model)} is thinking: " + proseOnly(thinking.takeLast(1_200).toString()).takeLast(160))
                    }
                    return@collect
                }
                if (thinking.isNotEmpty()) {
                    step(BuildEvent.Kind.THINKING, "${nice(target.model)} planned it: " + proseOnly(thinking.take(3_000).toString()).trim().take(160) + "…")
                    thinking.clear()
                    onProgress(null, "")
                }
                val chunk = raw
                sb.append(chunk)
                // Announce files as their headers arrive, like an IDE agent.
                val nl = sb.lastIndexOf("\n")
                if (nl > scanned) {
                    sb.substring(scanned, nl).lines().forEach { line ->
                        aboutLine.find(line.trim())?.let { about = it.groupValues[1].trim() }
                        fileStart.find(line.trim())?.let {
                            // Say in plain English what this part of the app does, like Claude does, not the code.
                            val path = it.groupValues[1].trim('`', ' ')
                            part++
                            val what = about?.takeIf { a -> a.length > 3 } ?: plainEnglish(path)
                            step(BuildEvent.Kind.FILE, "Part $part: $what (${path.substringAfterLast('/')})")
                            about = null
                        }
                    }
                    scanned = nl
                }
            }; false } catch (e: SteerNow) { true } catch (e: Exception) {
                // ARIN-PROTECTED: watchdog — your main model didn't answer: Arin (ordinary code, no AI) keeps everything
                // written so far, waits and asks again twice; then it stops with the work saved, so "try again" resumes.
                if (!target.worker && e !is kotlinx.coroutines.CancellationException && primaryFails < 2) {
                    primaryFails++
                    saveProjectQuietly()
                    step(BuildEvent.Kind.STEP, "${nice(target.model)} didn't answer (${e.message.orEmpty().take(90)}) — your work is saved; asking again in ${30 * primaryFails} s")
                    kotlinx.coroutines.delay(30_000L * primaryFails)
                    continue
                }
                if (!target.worker) throw IllegalStateException("Your main model (${nice(target.model)}) isn't answering: ${e.message.orEmpty().take(200)}. " +
                    "Everything done so far is saved — say \"try again\" to carry on from here.")
                // Claude's usage limit (or any failure) of a worker: not an error to fix — the next worker/helper takes over.
                if (wi + 1 >= pool.size) throw e
                val why = e.message.orEmpty()
                step(BuildEvent.Kind.THINKING, (if (why.startsWith("CLAUDE_LIMIT: ")) why.removePrefix("CLAUDE_LIMIT: ") else
                    "${nice(target.model)} couldn't answer (${why.take(100)})") + " — passing the task to ${nice(pool[wi + 1].model)}")
                wi++
                target = pool[wi]
                continue
            }
            if (steeredNow) {
                step(BuildEvent.Kind.THINKING, "Changing course now with your direction")
                onProgress(null, "")
                val base = working[0].text.substringBefore("\n\nTHE OWNER'S DIRECTIONS")
                working.clear()
                working += if (toWorkers) ChatMessage(Role.USER, workerSafe(prompt + helperPlan + steeringNote(), seenText))
                    else ChatMessage(Role.USER, base + steeringNote(), seenFiles)
                continue
            }
            // The worker never saw hidden values (keys in the owner's own code): the files it wrote get them back.
            val masked = sb.toString()
            val reply = if (toWorkers) com.arin.selffix.Delegation.Brief.restore(masked, workerHidden) else masked
            // A worker that refuses: the same task goes to the next worker; after the last one, to your model.
            // ARIN-PROTECTED: outcome-classes — a refusal is classified (and told apart from a technical failure) with
            // who said it and their stated reason, recorded for the audit, and never taken as finished work.
            val verdict = if (target.worker) com.arin.selffix.Outcome.classify(masked, "", null, expectsCode = true).let { v ->
                if (v.kind.ok && com.arin.selffix.CreditSaver.isRefusal(reply)) com.arin.selffix.Outcome.Verdict(com.arin.selffix.Outcome.Kind.UNCLASSIFIED_REFUSAL, masked.trim().lineSequence().first().take(300)) else v
            } else null
            if (verdict != null && masked.isBlank() && wi + 1 < pool.size) {
                // An empty answer is a technical failure, not a refusal: the next worker takes the task (nothing noted against this one).
                workerRefusals += "${nice(target.model)}: INCOMPLETE_RESPONSE (empty answer)"
                wi++; target = pool[wi]
                step(BuildEvent.Kind.THINKING, "${nice(pool[wi - 1].model)} sent an empty answer (a technical failure, not a refusal) — passing the task to ${nice(target.model)}")
                continue
            }
            if (verdict != null && verdict.kind.refusal) {
                com.arin.selffix.CreditSaver.noteRefusal(context!!, target.w!!, helperTab)
                workerRefusals += "${nice(target.model)}: ${verdict.kind.name}" + (if (verdict.reason.isNotBlank()) " (\"${verdict.reason.take(160)}\")" else "")
                ledger("Worker ${nice(target.model)}", "DECLINED — ${verdict.kind.name}: ${verdict.reason.take(160)}")
                wi++
                target = pool.getOrNull(wi) ?: throw IllegalStateException("Every worker and the helper declined this task — " +
                    workerRefusals.joinToString("; ") + ". Add another worker in Settings → Credit saver, or turn worker mode off for this task.")
                step(BuildEvent.Kind.THINKING, "${nice(pool[wi - 1].model)} declined (${verdict.kind.name.lowercase().replace('_', ' ')}: ${verdict.reason.take(90)}) — " +
                    "${bossName()} is passing the task to ${nice(target.model)}")
                continue
            }
            lastReply = reply
            if (target.worker) lastWorker = nice(target.model)
            // Credit meter: roughly how much of your model this task has used so far (and today's warning, if set).
            if (context != null && !target.worker) {
                val chars = system.length + working.sumOf { it.text.length } + reply.length
                taskTokens += chars / 4
                val today = com.arin.selffix.CreditSaver.addUsage(context, chars)
                val budget = com.arin.selffix.CreditSaver.dailyBudgetK(context) * 1000L
                step(BuildEvent.Kind.THINKING, "≈ ${"%,d".format(taskTokens)} tokens of ${req.model.substringAfterLast('/')} used in this task" +
                    if (budget > 0 && today > budget) " · ⚠️ over today's budget (${"%,d".format(today)})" else "")
            }
            if (steps >= MAX_WEB_STEPS || reply.contains("=== FILE:")) return reply
            val search = WebTools.searchTag.find(reply)?.groupValues?.get(1)?.trim()
            val fetch = WebTools.fetchTag.find(reply)?.groupValues?.get(1)?.trim()
            val claudeQ = if (context != null && (target.worker || com.arin.selffix.ClaudeHelper.enabled(context, helperTab)) && claudeAsks < com.arin.selffix.ClaudeHelper.maxAsks(context)) com.arin.selffix.ClaudeHelper.question(reply) else null
            val result = when {
                claudeQ != null -> {
                    claudeAsks++
                    val hn = com.arin.selffix.ClaudeHelper.helper(context!!).name
                    step(BuildEvent.Kind.THINKING, "${nice(target.model)} asked $hn for help — $hn is helping ${nice(target.model)}")
                    "The helper's answer:\n\n" + com.arin.selffix.ClaudeHelper.ask(context!!, projectNote() + claudeQ, source = helperTab, ownerTexts = ownerTexts()).take(12_000)
                }
                search != null -> {
                    step(BuildEvent.Kind.WEB, "Searching: $search")
                    "Web search results for \"$search\":\n\n" + WebTools.search(search).take(cap)
                }
                fetch != null -> {
                    step(BuildEvent.Kind.WEB, "Reading ${fetch.removePrefix("https://").take(60)}")
                    "Content of $fetch:\n\n" + WebTools.fetch(fetch).take(cap)
                }
                else -> return reply
            }
            steps++
            working += ChatMessage(Role.ASSISTANT, masked.trim()) // the worker's own (masked) words, never the restored values
            working += ChatMessage(Role.USER, "[Tool result]\n$result\n\nNow continue: write the files in the required format.")
        }
    }

    /** Who writes this answer: a worker (worker mode) or your model. */
    private class Target(val provider: com.arin.llm.Provider, val key: String, val model: String,
                         val endpoint: com.arin.llm.CustomApi.Endpoint?, val label: String, val w: com.arin.selffix.CreditSaver.Worker? = null) {
        val worker get() = w != null
    }

    private fun nice(model: String) = com.arin.selffix.Worker.nice(model)
    private fun bossName() = nice(req.model)
    /** The worker that wrote the latest code (for the steps). */
    private var lastWorker = ""

    private fun bossTarget() = Target(req.provider, req.apiKey, req.model, req.endpoint(), req.model)

    private fun workerOn(): Boolean = context != null && com.arin.selffix.CreditSaver.on(context) && com.arin.selffix.CreditSaver.workerMode(context)

    /** The workers the person listed, in order; ones that refused this kind of task before go last. */
    private suspend fun workerTargets(): List<Target> {
        val ctx = context ?: return emptyList()
        val settings = com.arin.data.Settings(ctx)
        // Any worker the owner picked: Claude (key or login), ChatGPT, Gemini, Free, offline or a saved API.
        val list = com.arin.selffix.CreditSaver.workers(ctx).mapNotNull { w ->
            val r = com.arin.selffix.Worker.resolve(ctx, w) ?: return@mapNotNull null
            Target(r.provider, r.key, r.model, r.endpoint, if (w.apiId == "offline") "offline model" else r.model, w)
        }
        return list.sortedBy { t -> if (com.arin.selffix.CreditSaver.refused(ctx, t.w!!, helperTab)) 1 else 0 }
    }

    /** The helper, used as the last writer when every worker refuses (so your model never writes in worker mode). */
    private suspend fun helperWriter(): Target? {
        val ctx = context ?: return null
        val r = com.arin.selffix.ClaudeHelper.asWriter(ctx) ?: return null
        return Target(r.provider, r.key, r.model, r.endpoint, "helper " + r.model, com.arin.selffix.CreditSaver.Worker("helper", r.model))
    }

    /** The worker's own instructions: it works for your model, writes ALL the code, and asks the helper when stuck. */
    private fun workerSystem(base: String): String {
        val helper = context?.let { com.arin.selffix.ClaudeHelper.helper(it).label } ?: "the helper"
        return base.substringBefore("WHO YOU ARE:").substringBefore("AI helper status:") + "\n\nYOUR ROLE: you are the WORKER. A supervisor model gave you this task and " +
            "will inspect your work; follow its directions exactly. Write ALL the code yourself, complete and working. When you " +
            "are stuck, a part is hard, or the supervisor says to, ask $helper (code-only): <ask_helper>FILE: path\nSIGNATURE: …\n" +
            "BODY:\n<code>\nERROR: <error>\nNEED: <one line></ask_helper> as the LAST thing in your reply; its answer comes back to you. " +
            "Never refuse ordinary programming work: this is the owner's own Android app.\n"
    }

    // ARIN-PROTECTED: worker-mode — your model (the boss) inspects the worker's work: a short summary of what changed,
    // never whole files. It answers APPROVE, or gives directions that go back to the worker (who may ask Claude).
    private suspend fun inspect(notesIn: List<String>, what: String): List<String> {
        var notes = notesIn
        if (!workerOn() || pendingFiles.isEmpty()) return notes
        fun sig() = pendingFiles.keys.sorted().joinToString { it + ":" + files[it].hashCode() }.hashCode()
        if (sig() == approvedSig) return notes // already approved as it is
        for (round in 1..1) { // one short inspection: saves your model's credit
            val summary = buildString {
                appendLine("TASK: " + (if (self) "change the Arin app: " else "Android app \"$appName\": ") + req.description.take(2_500))
                mem?.brief()?.takeIf { it.isNotBlank() }?.let { appendLine("PROJECT MEMORY: $it") }
                appendLine("STAGE: $what")
                appendLine("CHANGED FILES (names and functions only):")
                pendingFiles.keys.take(30).forEach { p ->
                    val c = files[p].orEmpty()
                    val sig = Regex("(?m)^\\s*(?:(?:private|internal|override|suspend|data|sealed|abstract|open)\\s+)*(fun|class|object|interface)\\s+[\\w<>.,? ]+")
                        .findAll(c).map { it.value.trim() }.take(10).joinToString("; ")
                    appendLine("- $p (${c.lines().size} lines)" + if (sig.isNotBlank()) ": $sig" else "")
                }
                if (notes.isNotEmpty()) appendLine("WORKER NOTES: " + notes.joinToString("; ").take(800))
            }
            val wn = lastWorker.ifBlank { "the worker" }
            step(BuildEvent.Kind.THINKING, "${bossName()} is inspecting $wn's work ($what)")
            val verdict = bossSay(summary + "\nYou are the supervisor. Inspect: is this EXACTLY the app/change the owner asked for, " +
                "with every feature and detail (nothing different, simplified or dropped), and is anything wrong or missing? Answer in at most 6 lines: either just APPROVE, or FIX: <exact directions for the worker>. " +
                "You may write \"ask Claude\" in a direction.")
            if (verdict.isBlank() || verdict.trim().uppercase().startsWith("APPROVE") || !verdict.contains("FIX", true)) {
                step(BuildEvent.Kind.THINKING, "✅ ${bossName()} approved $wn's work")
                approvedSig = sig()
                return notes
            }
            step(BuildEvent.Kind.THINKING, "${bossName()} is instructing $wn: " + verdict.trim().removePrefix("FIX:").trim().take(160))
            notes = apply(ask(buildString {
                appendLine("The supervisor inspected your work and gives these directions. Follow them exactly (ask the helper if it says so " +
                    "or you're stuck):")
                appendLine(verdict.trim().take(2_000))
                appendLine()
                appendFilesLean(verdict)
                appendLine()
                appendLine("Reply with every file you change, in full, or EDIT blocks.")
            }))
        }
        return notes
    }

    // ARIN-PROTECTED: worker-mode strict privacy for build errors — once your model approved the code, the worker and the
    // helper never see the project or your chat. Your model reads the errors and names the broken pieces; Arin itself
    // (no AI) cuts out ONLY those pieces; the worker (who may ask the helper) returns each piece fixed; your model inspects
    // the before/after; only then is it built again.
    private class Piece(val file: String, val start: Int, val end: Int, val text: String, val need: String) { var origin: Piece? = null }

    /**
     * ARIN-PROTECTED: project-map-in-build — Arin's own check (no AI; the worker never sees the project): from this build's
     * current project map, the classes/functions in [old] that other files use and that [new] no longer declares.
     * A fixed piece that drops one of them would break those files, so it is rejected.
     */
    private fun lostUsedNames(path: String, old: String, new: String): List<String> = runCatching {
        val g = com.arin.selffix.ProjectMaps.forFiles("build:$memKey", files)
        val users = g.dependents(path).mapNotNull { g.files[it] }
        val before = com.arin.selffix.ProjectGraph.parse(path, old); val after = com.arin.selffix.ProjectGraph.parse(path, new)
        (before.classes + before.functions).filter { n -> n !in after.classes && n !in after.functions && users.any { n in it.uses } }
    }.getOrDefault(emptyList())

    private suspend fun privateFix(errors: String, notesIn: List<String>, web: String): List<String> {
        val ctx = context ?: return notesIn
        val boss = bossName()
        val trimmed = com.arin.selffix.CreditSaver.trimErrors(errors)
        // ARIN-PROTECTED: boss-minimum-credit — Arin itself (no AI) cuts the code around every error site; the helper's
        // whole-problem diagnosis says what to change; the worker fixes each piece. Your model is only asked when Arin
        // can't find an error site. The rebuild checks the fix (no extra inspection by your model).
        // ARIN-PROTECTED: syntax-guard — a typing mistake (missing/extra bracket, text left in the code) breaks the
        // whole file and causes dozens of follow-on errors: every broken file is repaired whole, all in one go, instead
        // of patching cut-out pieces (which is how brackets got lost before).
        if (!self && KotlinSyntax.isSyntaxError(errors)) {
            val broken = errorFiles(errors).mapNotNull { f -> files.keys.firstOrNull { it == f || it.endsWith("/" + f.substringAfterLast('/')) } }.filter { it.endsWith(".kt") || it.endsWith(".kts") }.toSet()
            if (syntaxGuard(force = broken, errors = errors) > 0) return notesIn
        }
        val diagnosis = web.substringAfter("CONSULTANT'S WHOLE-PROBLEM DIAGNOSIS", "").let { if (it.isBlank()) "" else it.take(3_500) }
        val sites = Regex("([\\w./-]+\\.(?:kt|java|xml|kts))[:(](\\d+)").findAll(errors)
            .map { it.groupValues[1] to it.groupValues[2].toInt() }.distinct().toList()
        val auto = sites.groupBy({ it.first }, { it.second }).mapNotNull { (f, lines) ->
            val path = files.keys.firstOrNull { it == f || it.endsWith("/" + f.substringAfterLast("/src/main/").ifBlank { f }) || it.endsWith("/" + f.substringAfterLast('/')) } ?: return@mapNotNull null
            val n = files[path]!!.lines().size
            val a = (lines.min() - 20).coerceAtLeast(1); val b = (lines.max() + 20).coerceAtMost(n)
            cut("FILE: $path\nLINES: $a-$b\nNEED: fix the error(s) at line(s) ${lines.joinToString()} as the diagnosis/error says")
        }.take(8) // every broken file in this round (fixed all at once, not a few per build)
        if (auto.isNotEmpty()) {
            step(BuildEvent.Kind.THINKING, "Arin cut out the code at ${auto.size} error site(s) — the worker fixes them" + if (diagnosis.isNotBlank()) " using Claude's diagnosis" else "")
            var any = false
            for (pc in auto) {
                val fileErrors = trimmed.lines().filter { it.contains(pc.file.substringAfterLast('/')) }.take(10).joinToString("\n").ifBlank { trimmed.take(1_500) }
                val api = apiIndex(fileErrors)
                val task = "FILE: ${pc.file}\nCODE:\n${pc.text}\nERROR:\n" + fileErrors +
                    (if (api.isNotBlank()) "\n$api" else "") +
                    (if (diagnosis.isNotBlank()) "\nDIAGNOSIS FROM A SENIOR ENGINEER:\n$diagnosis" else "") +
                    "\nNEED: ${pc.need}\nReturn ONLY the fixed CODE above (the same piece, complete, every line), nothing else."
                val (who, out) = com.arin.selffix.Worker.runFor(ctx, task, boss, project = memKey, requirements = reqList(), ownerTexts = ownerTexts()) { _, t -> step(BuildEvent.Kind.THINKING, t) }
                val code = out.replace(Regex("(?s)^.*?```[\\w-]*\\n"), "").substringBeforeLast("```").trim()
                if (who.isBlank() || code.isBlank()) continue
                val lost = lostUsedNames(pc.file, pc.text, code)
                if (lost.isNotEmpty()) { step(BuildEvent.Kind.THINKING, "Rejected the fix for ${pc.file.substringAfterLast('/')}: it removes ${lost.joinToString()} that other files use"); continue }
                replacePiece(pc, code); any = true
            }
            if (any) { step(BuildEvent.Kind.THINKING, "Fixed — building again (the build itself checks the fix)"); return notesIn }
        }
        // No error site in the files (a Gradle/library problem): the worker fixes it with the full error report.
        if (sites.isEmpty()) return apply(ask(fixPrompt(errors, notesIn, web)))
        step(BuildEvent.Kind.THINKING, "$boss is analysing the build errors")
        val plan = bossSay(buildString {
            appendLine("The build failed. ERRORS:")
            appendLine(trimmed)
            appendLine()
            appendLine("CODE AROUND THE ERRORS (numbered lines):")
            appendLine(errorRegions(trimmed))
            if (web.isNotBlank()) { appendLine(); appendLine("WEB RESEARCH (short):"); appendLine(web.take(2_500)) }
            appendLine()
            appendLine("Analyse the cause. Do NOT write the fix. Name each broken piece for the worker, one block each (max 4):")
            appendLine("<piece>FILE: path\nFUNCTION: functionName   (or LINES: from-to)\nNEED: one line saying exactly what to fix</piece>")
        })
        val pieces = Regex("(?s)<piece>(.*?)</piece>").findAll(plan).mapNotNull { cut(it.groupValues[1]) }.take(8).toList()
        if (pieces.isEmpty()) {
            step(BuildEvent.Kind.THINKING, "$boss couldn't point to a piece — the worker fixes the whole error this time")
            return apply(ask(fixPrompt(errors, notesIn, web)))
        }
        var current = pieces
        for (round in 1..2) {
            val fixed = mutableListOf<Pair<Piece, String>>()
            for (pc in current) {
                step(BuildEvent.Kind.THINKING, "$boss is instructing the worker: ${pc.need.take(100)} (${pc.file.substringAfterLast('/')})")
                val task = "FILE: ${pc.file}\nCODE:\n${pc.text}\nERROR:\n" + trimmed.lines().filter { it.contains(pc.file.substringAfterLast('/')) }.take(8).joinToString("\n").ifBlank { trimmed.take(1_500) } +
                    "\nNEED: ${pc.need}\nReturn ONLY the fixed CODE above (the same piece, complete), nothing else."
                val (who, out) = com.arin.selffix.Worker.runFor(ctx, task, boss, project = memKey, requirements = reqList(), ownerTexts = ownerTexts()) { _, t -> step(BuildEvent.Kind.THINKING, t) }
                val code = out.replace(Regex("(?s)^.*?```[\\w-]*\\n"), "").substringBeforeLast("```").trim()
                if (who.isBlank() || code.isBlank()) { step(BuildEvent.Kind.THINKING, "No worker could fix ${pc.file.substringAfterLast('/')}"); continue }
                fixed += pc to code
            }
            if (fixed.isEmpty()) {
                step(BuildEvent.Kind.THINKING, "No worker could fix the pieces — the worker/helper fixes the whole error this time")
                return apply(ask(fixPrompt(errors, notesIn, web)))
            }
            step(BuildEvent.Kind.THINKING, "$boss is inspecting the worker's fix")
            val verdict = bossSay(buildString {
                appendLine("ERRORS:"); appendLine(trimmed.take(2_000)); appendLine()
                fixed.forEachIndexed { i, (pc, code) ->
                    appendLine("PIECE ${i + 1} — ${pc.file} — NEED: ${pc.need}"); appendLine("BEFORE:"); appendLine(pc.text); appendLine("AFTER:"); appendLine(code); appendLine()
                }
                appendLine("Inspect. Answer just APPROVE, or FIX n: <exact direction> for each piece that is still wrong (max 6 lines).")
            })
            val redo = Regex("(?i)FIX\\s*(\\d+)\\s*:\\s*(.+)").findAll(verdict).mapNotNull { m ->
                fixed.getOrNull((m.groupValues[1].toIntOrNull() ?: 0) - 1)?.let { (pc, code) -> m.groupValues[2] to (pc to code) }
            }.toList()
            // Put the approved pieces in place (Arin does it, no AI).
            val redoFiles = redo.map { it.second.first }.toSet()
            fixed.filter { it.first !in redoFiles || round == 2 }.forEach { (pc, code) -> replacePiece(pc, code) }
            if (redo.isEmpty() || round == 2) {
                step(BuildEvent.Kind.THINKING, "✅ $boss approved the fix — building again")
                return notesIn
            }
            step(BuildEvent.Kind.THINKING, "$boss wants changes: " + redo.joinToString("; ") { it.first }.take(160))
            current = redo.map { (dir, p) -> Piece(p.first.file, -1, -1, p.second, dir).also { it.origin = p.first } }
        }
        return notesIn
    }

    /** Lines around each error location (numbered), so your model can name the broken pieces. */
    private fun errorRegions(errors: String): String {
        val out = StringBuilder()
        Regex("([\\w./-]+\\.(?:kt|java|xml|kts|gradle))[:(](\\d+)").findAll(errors).map { it.groupValues[1] to it.groupValues[2].toInt() }
            .distinct().take(6).forEach { (f, line) ->
                val path = files.keys.firstOrNull { it.endsWith(f.substringAfter("/src/").ifBlank { f }) || it.endsWith("/" + f.substringAfterLast('/')) } ?: return@forEach
                val lines = files[path].orEmpty().lines()
                val from = maxOf(0, line - 25); val to = minOf(lines.size, line + 15)
                out.appendLine("=== $path (lines ${from + 1}-$to) ===")
                for (i in from until to) out.appendLine("${i + 1}: ${lines[i]}")
            }
        return out.toString().take(20_000).ifBlank { "(no file locations in the errors)" }
    }

    /** Arin (not an AI) cuts out the piece your model named: a whole function, or the lines it gave. */
    private fun cut(spec: String): Piece? {
        val file = Regex("FILE:\\s*(\\S+)").find(spec)?.groupValues?.get(1)?.trim('`') ?: return null
        val need = Regex("NEED:\\s*(.+)").find(spec)?.groupValues?.get(1)?.trim().orEmpty()
        val path = files.keys.firstOrNull { it == file || it.endsWith("/" + file) || it.endsWith(file) } ?: return null
        val text = files[path] ?: return null
        Regex("LINES:\\s*(\\d+)\\s*-\\s*(\\d+)").find(spec)?.let { m ->
            val lines = text.lines(); val a = (m.groupValues[1].toInt() - 1).coerceIn(0, lines.size); val b = m.groupValues[2].toInt().coerceIn(a, lines.size)
            val start = lines.take(a).sumOf { it.length + 1 }; val piece = lines.subList(a, b).joinToString("\n")
            return Piece(path, start, start + piece.length, piece, need)
        }
        val fn = Regex("FUNCTION:\\s*([\\w.]+)").find(spec)?.groupValues?.get(1)?.substringAfterLast('.') ?: return null
        val m = Regex("(?m)^[ \\t]*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*(?:(?:private|internal|public|protected|override|suspend|inline|operator|open|abstract)\\s+)*fun\\s+(?:<[^>]+>\\s*)?(?:[\\w.<>?]+\\.)?$fn\\s*\\(").find(text) ?: return null
        // The parameter list: match its parentheses, then see whether the body is { … } or "= expression".
        var k = m.range.last; var paren = 0
        while (k < text.length) { if (text[k] == '(') paren++ else if (text[k] == ')') { paren--; if (paren == 0) break }; k++ }
        val brace = text.indexOf('{', k).let { if (it < 0) Int.MAX_VALUE else it }
        val eq = text.indexOf('=', k).let { if (it < 0) Int.MAX_VALUE else it }
        val end = if (brace < eq) {
            var depth = 0; var i = brace
            while (i < text.length) { if (text[i] == '{') depth++ else if (text[i] == '}') { depth--; if (depth == 0) break }; i++ }
            minOf(i + 1, text.length)
        } else if (eq != Int.MAX_VALUE) {
            text.indexOf("\n\n", eq).let { if (it < 0) text.length else it }
        } else return null
        return Piece(path, m.range.first, end, text.substring(m.range.first, end), need)
    }

    /**
     * ARIN-PROTECTED: import-guard — adds forgotten imports and the Material 3 opt-in (no AI). With [errors], also the
     * names the compiler reported unresolved. Returns how many files were changed.
     */
    private fun importGuard(errors: String = ""): Int {
        val missing = KotlinImports.unresolved(errors)
        val targets = (pendingFiles.keys + missing.keys.mapNotNull { f -> files.keys.firstOrNull { it.endsWith("/$f") || it == f } })
            .filter { it.endsWith(".kt") && files[it] != null }.distinct()
        if (targets.isEmpty()) return 0
        val own = KotlinImports.declared(files)
        var changed = 0
        for (p in targets) {
            val t = files[p] ?: continue
            val must = missing[p.substringAfterLast('/')].orEmpty().filter { KotlinImports.knows(it) && it !in own }.toSet()
            val (out, notes) = runCatching { KotlinImports.fix(p, t, own, must, rPackage = packageName.takeIf { !self && it.isNotBlank() }) }.getOrElse { t to emptyList() }
            if (out == t) continue
            files[p] = out; pendingFiles[p] = out.toByteArray(); changed++
            step(BuildEvent.Kind.FILE, "Arin fixed ${p.substringAfterLast('/')} itself: " + notes.joinToString("; ").take(200) + " (no AI needed)")
            ledger("Imports: ${p.substringAfterLast('/')}", "FIXED by Arin — " + notes.joinToString("; ").take(150))
        }
        return changed
    }

    /**
     * ARIN-PROTECTED: duplicate-guard — the same function/class/value written twice (in one file or in two) is reduced
     * to one copy (no AI). [errors]: only the names the compiler reported; none = every exact same-signature copy.
     */
    private fun duplicateGuard(errors: String = ""): Int {
        val names = KotlinDuplicates.reported(errors)
        if (errors.isNotBlank() && names.isEmpty()) return 0
        val (updated, notes) = runCatching { KotlinDuplicates.fix(files.filterKeys { it.endsWith(".kt") }, names, pendingFiles.keys) }.getOrElse { return 0 }
        updated.forEach { (p, t) -> files[p] = t; pendingFiles[p] = t.toByteArray() }
        if (notes.isNotEmpty()) {
            step(BuildEvent.Kind.FILE, "Arin fixed \"two things have the same name\" itself: " + notes.joinToString("; ").take(300) + " (no AI needed)")
            ledger("Duplicates", "FIXED by Arin — " + notes.joinToString("; ").take(200))
        }
        return updated.size
    }

    /**
     * Fixes Arin can make by itself before any model is asked: broken brackets (syntax errors) and missing imports.
     * True when that covers every reported error, so the next build goes ahead with no AI credit used.
     */
    /** The errors Arin couldn't fix by itself in the last [quickFix] — the only ones the models are given. */
    private var remainingErrors: String? = null

    private suspend fun quickFix(errors: String): Boolean {
        remainingErrors = null
        if (self) return false
        val errs = KotlinFixes.errors(errors)
        var changed = 0
        if (KotlinSyntax.isSyntaxError(errors)) {
            val broken = errorFiles(errors).filter { it.endsWith(".kt") || it.endsWith(".kts") }.toSet()
            changed += runCatching { syntaxGuard(force = broken, errors = errors) }.getOrDefault(0)
            if (changed > 0) return true // everything after the first syntax error is a follow-on error
        }
        // Fixes read straight from the compiler's messages (no AI).
        val r = runCatching { KotlinFixes.fix(files.filterKeys { it.endsWith(".kt") }, errors) }.getOrNull()
        if (r != null && r.updated.isNotEmpty()) {
            r.updated.forEach { (p, t) -> files[p] = t; pendingFiles[p] = t.toByteArray() }
            changed += r.updated.size
            step(BuildEvent.Kind.FILE, "Arin fixed ${r.fixed.size} error(s) itself: " + r.notes.joinToString("; ").take(300) + " (no AI needed)")
            ledger("Compiler fixes", "FIXED by Arin — " + r.notes.joinToString("; ").take(200))
        }
        val imp = runCatching { importGuard(errors) }.getOrDefault(0)
        val dups = runCatching { duplicateGuard(errors) }.getOrDefault(0)
        changed += imp + dups
        val fixedRaw = r?.fixed?.map { it.raw }.orEmpty().toSet()
        val touched = (r?.fixed?.map { it.file }.orEmpty() + if (imp + dups > 0) errs.map { it.file } else emptyList()).toSet()
        fun covered(e: KotlinFixes.Err): Boolean =
            e.raw in fixedRaw ||
                (dups > 0 && (KotlinDuplicates.isDuplicateError(e.msg) || e.msg.contains("ambiguity", true))) ||
                (imp > 0 && Regex("""^Unresolved reference:?\s*'?`?(\w+)`?'?\.?$""").find(e.msg)?.groupValues?.get(1)?.let { KotlinImports.knows(it) } == true) ||
                (imp > 0 && (e.msg.contains("ExperimentalMaterial3Api") || e.msg.contains("material API is experimental") ||
                    e.msg.contains("getValue") || e.msg.contains("setValue"))) ||
                (KotlinFixes.isCascade(e.msg) && e.file in touched)
        val left = errs.filterNot { covered(it) }
        if (changed > 0 && errs.isNotEmpty() && left.isEmpty()) {
            step(BuildEvent.Kind.DONE, "Arin fixed all ${errs.size} error(s) itself — building again, no AI used")
            return true
        }
        // Only what is left goes to the models, all of it at once.
        if (changed > 0 && left.size < errs.size) {
            val drop = errs.filter { covered(it) }.map { it.raw.trim() }.toSet()
            remainingErrors = errors.lines().filterNot { it.trim() in drop }.joinToString("\n")
            step(BuildEvent.Kind.THINKING, "Arin fixed ${errs.size - left.size} of ${errs.size} errors itself; the models get the other ${left.size}, all at once")
        }
        return false
    }

    /**
     * ARIN-PROTECTED: api-index — the real signatures (from the project's own files) of the classes the failing code
     * calls ("Unresolved reference 'bypass' on receiver of type 'Pipeline'"), so the fix matches the code that exists
     * instead of guessing — fewer rounds.
     */
    private fun apiIndex(errors: String): String {
        val types = Regex("""on receiver of type '([\w.]+)\??'""").findAll(errors).map { it.groupValues[1].substringAfterLast('.') }.toMutableSet()
        Regex("""Unresolved reference:?\s*'?`?([A-Z]\w*)""").findAll(errors).forEach { types += it.groupValues[1] }
        if (types.isEmpty()) return ""
        val kt = files.filterKeys { it.endsWith(".kt") }
        val sb = StringBuilder()
        for (type in types.take(6)) {
            val found = kt.firstNotNullOfOrNull { (p, t) ->
                runCatching { KotlinDuplicates.topLevel(p, t) }.getOrDefault(emptyList())
                    .firstOrNull { it.name == type && it.kind in setOf("class", "object", "interface") }?.let { Triple(p, t, it) }
            }
            if (found == null) {
                if (!KotlinImports.knows(type)) sb.appendLine("- $type: NOT declared anywhere in the project (declare it, or use a name that exists).")
                continue
            }
            val (p, t, d) = found
            val lines = t.lines(); val depth = KotlinSyntax.lineDepths(t)
            val header = lines.subList(d.start, d.end + 1).joinToString(" ") { it.trim() }.substringBefore("{").replace(Regex("\\s+"), " ").take(300)
            sb.appendLine("- $p: $header")
            var n = 0
            for (i in d.start..d.end) {
                if (depth.getOrElse(i) { 0 } != 1 || n >= 30) continue
                val l = lines[i].trim()
                if (Regex("""^(?:@\w+\s+)*(?:(?:private|internal|public|protected|override|suspend|open|abstract|data|enum|sealed|inner|const|lateinit|inline)\s+)*(fun|val|var|class|object|interface)\s""").containsMatchIn(l) && !l.startsWith("private")) {
                    sb.appendLine("    " + l.substringBefore(" {").substringBefore(" = ").take(160)); n++
                }
            }
        }
        if (sb.isBlank()) return ""
        return "THE PROJECT'S REAL CODE THE FAILING LINES CALL (match these names and parameters, or add what's missing):\n" + sb.toString().trim()
    }

    /** Files a rejected piece-fix left for a whole-file repair. */
    private val syntaxBroken = linkedSetOf<String>()

    /**
     * ARIN-PROTECTED: syntax-guard — before every build: the Kotlin files changed in this build are checked (no AI);
     * fences/prose and stray or missing closing braces are repaired by Arin itself. Files it can't repair (and files
     * the compiler reported a syntax error in: [force]) are rewritten whole, all in one batch, and each rewrite is
     * checked before it is used. Returns how many files were changed.
     */
    private suspend fun syntaxGuard(force: Set<String> = emptySet(), errors: String = ""): Int {
        val candidates = (pendingFiles.keys + force + syntaxBroken).filter { (it.endsWith(".kt") || it.endsWith(".kts")) && files[it] != null }.distinct()
        var changed = 0
        // XML written by a model: fences or explanation text around it break the whole resource build.
        for (p in pendingFiles.keys.filter { it.endsWith(".xml") && files[it] != null }.toList()) {
            val t = files[p] ?: continue
            val fixed = KotlinSyntax.cleanXml(t) ?: continue
            if (fixed.trimEnd() != t.replace("\r\n", "\n").trimEnd()) {
                files[p] = fixed; pendingFiles[p] = fixed.toByteArray(); changed++
                step(BuildEvent.Kind.FILE, "Arin removed text left around the XML in ${p.substringAfterLast('/')} — no AI needed")
            }
        }
        val rewrite = mutableListOf<Pair<String, String>>() // path to the problem
        for (p in candidates) {
            val t = files[p] ?: continue
            val r = KotlinSyntax.scan(t)
            val fenced = Regex("""(?m)^\s*```""").containsMatchIn(t) || KotlinSyntax.misplacedImports(t) || KotlinSyntax.needsCleaning(p, t)
            if (r.ok && !fenced && p !in force && p !in syntaxBroken) continue
            // Arin's own repair first (fences, prose, imports in the middle, stray/missing braces) — no AI.
            val fixed = KotlinSyntax.repair(p, t)
            if (fixed != null && fixed != t) {
                files[p] = fixed; pendingFiles[p] = fixed.toByteArray(); changed++
                step(BuildEvent.Kind.FILE, "Arin repaired a typing mistake in ${p.substringAfterLast('/')} (${if (fenced) "imports/text left in the middle of the code, " else ""}${r.describe()}) — no AI needed")
                ledger("Syntax check: ${p.substringAfterLast('/')}", "FIXED by Arin (${r.describe()})")
                syntaxBroken -= p; continue
            }
            if (fixed == null && !r.ok || p in force || p in syntaxBroken) rewrite += p to (if (!r.ok) r.describe() else "the compiler reports a syntax error")
        }
        if (rewrite.isEmpty()) return changed
        val ctx = context
        step(BuildEvent.Kind.THINKING, "Repairing ${rewrite.size} file(s) whole in one go: " + rewrite.joinToString { it.first.substringAfterLast('/') })
        for ((p, problem) in rewrite.take(4)) {
            val t = files[p] ?: continue
            val errLines = errors.lines().filter { it.contains(p.substringAfterLast('/')) }.take(6).joinToString("\n")
            val task = "FILE: $p\nPROBLEM: the file's structure is broken: $problem. " +
                (if (errLines.isNotBlank()) "Compiler (the FIRST error is the real one; the rest follow from it):\n$errLines\n" else "") +
                "Fix every bracket, brace, parenthesis, quote and misplaced line so the whole file compiles. Keep every feature " +
                "and all other code exactly as it is.\nWHOLE FILE (numbered for you; don't include the numbers):\n" +
                t.lines().mapIndexed { i, l -> "${i + 1}: $l" }.joinToString("\n") +
                "\nReturn ONLY the COMPLETE corrected file, every line from the first to the last, no explanation, no ``` fences."
            val out = if (ctx != null && workerOn()) com.arin.selffix.Worker.runFor(ctx, task, bossName(), project = memKey, requirements = reqList(), ownerTexts = ownerTexts()) { _, s -> step(BuildEvent.Kind.THINKING, s) }.second else ask(task)
            val code = KotlinSyntax.clean(p, out.replace(Regex("(?s)^.*?```[\\w-]*\n"), "").substringBeforeLast("```").let { if (it.isBlank()) out else it })
                .lines().joinToString("\n") { it.replace(Regex("^\\d+: "), "") }
            val r = KotlinSyntax.scan(code)
            val shrunk = code.length < t.length * 0.6
            if (code.isBlank() || shrunk || !r.ok) {
                step(BuildEvent.Kind.THINKING, "The rewrite of ${p.substringAfterLast('/')} wasn't safe to use (${if (shrunk) "parts of the file were missing" else r.describe()}) — keeping the original")
                ledger("Syntax repair: ${p.substringAfterLast('/')}", "FAILED — rewrite rejected")
                continue
            }
            files[p] = code; pendingFiles[p] = code.toByteArray(); changed++; syntaxBroken -= p
            step(BuildEvent.Kind.FILE, "Repaired ${p.substringAfterLast('/')} whole (brackets checked by Arin before use)")
            ledger("Syntax repair: ${p.substringAfterLast('/')}", "FIXED — whole file rewritten and checked")
        }
        return changed
    }

    /** Puts a fixed piece back exactly where it was cut out. */
    private fun replacePiece(pc: Piece, code: String) {
        val src = pc.origin ?: pc
        val text = files[src.file] ?: return
        val now0 = if (src.start >= 0 && src.end <= text.length && text.substring(src.start, src.end) == src.text)
            text.substring(0, src.start) + code + text.substring(src.end)
        else if (text.contains(src.text)) text.replaceFirst(src.text, code) else return
        // A piece may come back with import lines on top: those belong in the file's import block, not mid-file.
        var now = if (src.file.endsWith(".kt")) KotlinSyntax.hoistImports(now0) else now0
        // ARIN-PROTECTED: syntax-guard — a fixed piece must not break the file's brackets.
        if (src.file.endsWith(".kt") || src.file.endsWith(".kts")) {
            val before = KotlinSyntax.scan(text); val after = KotlinSyntax.scan(now)
            if (!after.ok && (before.ok || Math.abs(after.braces) + Math.abs(after.parens) > Math.abs(before.braces) + Math.abs(before.parens))) {
                val repaired = KotlinSyntax.repair(src.file, now)
                if (repaired == null) {
                    step(BuildEvent.Kind.THINKING, "The fix for ${src.file.substringAfterLast('/')} would break its brackets (${after.describe()}) — not used; the whole file gets repaired instead")
                    syntaxBroken += src.file
                    return
                }
                now = repaired
            }
        }
        files[src.file] = now
        pendingFiles[src.file] = now.toByteArray()
        step(BuildEvent.Kind.FILE, "Put the fixed piece into ${src.file.substringAfterLast('/')}")
    }

    /**
     * A short answer from your model (the boss). ARIN-PROTECTED: smart-thinking — routine checks are quick (little
     * thinking, little credit); a big problem (planning a new app, an error that keeps coming back) gets its full
     * thinking power. Either way it writes little: the worker and helper still do the heavy work.
     */
    private suspend fun bossSay(prompt: String): String {
        val hard = stuck >= 2 || (req.project == null && !self && files.isEmpty()) || prompt.length > 12_000
        val out = StringBuilder()
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(180_000) {
                streamModel(req.provider, req.apiKey, req.model, listOf(ChatMessage(Role.USER, prompt)),
                    "You are the supervisor of an AI coding worker. Think as deeply as the problem needs; write briefly.", req.endpoint(), boss = workerOn(), hard = hard, conv = "build:$memKey:boss")
                    .collect { if (!it.startsWith(THOUGHT)) out.append(it) }
            }
        }
        context?.let { com.arin.selffix.CreditSaver.addUsage(it, prompt.length + out.length) }
        return out.toString().take(3_000)
    }

    /** Sent again when a reply had no files: just the format, as plainly as possible. */
    private fun formatReminder() = "\n\n" + """
        IMPORTANT: your last reply contained no files, so nothing could be built. Don't explain or plan this time.
        Reply with ONLY the complete files, starting right away with the lines below, every file in this exact format:
        === ABOUT: what this part of the app does, in plain English ===
        === FILE: app/src/main/java/${packageName.replace('.', '/')}/MainActivity.kt ===
        (the whole file)
        === END FILE ===
        Keep it to a few files (one MainActivity.kt with all the screens is fine) so the whole answer fits.
    """.trimIndent()

    private fun firstPrompt() = buildString {
        appendLine("Build this Android app. Implement EVERY point below in full: every feature, screen, option and detail that was")
        appendLine("asked for or agreed. Nothing may be left out, simplified away, faked with sample data, or left as a TODO or placeholder.")
        appendLine()
        appendLine("=== REQUIREMENTS ===")
        appendLine(requirements)
        appendLine("=== END REQUIREMENTS ===")
        appendLine()
        appendLine("App name: $appName")
        appendLine(attemptNotes())
        append(sizeRule)
    }

    /** ARIN-PROTECTED: notes from earlier attempts — a different method, the exact same request. */
    private fun attemptNotes(): String = buildString {
        appendLine()
        appendLine(com.arin.selffix.AutoFix.RULE_EXACT)
        if (req.retryHint.isNotBlank()) {
            appendLine("EARLIER ATTEMPTS FAILED. What was found (researched on the internet) — use a different method this time, " +
                "but build exactly what was asked:")
            appendLine(req.retryHint.take(6_000))
        }
    }

    private val sizeRule = "If the whole app doesn't fit in one reply, write as many COMPLETE files as fit (the most important first) and end " +
        "with the line === MORE === ; you will be asked to continue. When every file is written, end with the line === DONE === ."

    /** The file that was being written when a reply got cut off, if any. */
    private fun cutOffFile(reply: String): String? {
        if (!parse(reply).truncated) return null
        return reply.lines().lastOrNull { fileStart.containsMatchIn(it.trim()) }?.let { fileStart.find(it.trim())?.groupValues?.get(1)?.trim('`', ' ') }
    }

    /** Big apps don't fit in one reply: keep asking for the rest until the model says it's done. */
    private suspend fun finishWriting(notesIn: List<String>): List<String> {
        var notes = notesIn
        var rounds = 0
        val maxRounds = if (req.effort == Effort.LOW) 2 else 8
        while (rounds < maxRounds && (moreLine.containsMatchIn(lastReply) || parse(lastReply).truncated)) {
            rounds++
            val cut = cutOffFile(lastReply)
            step(BuildEvent.Kind.STEP, "It's a big app: writing the next part (${files.size} files so far)")
            val before = files.size to pendingFiles.size
            notes = apply(ask(buildString {
                appendLine("You are part-way through writing this Android app.")
                appendLine()
                appendLine("=== REQUIREMENTS ===")
                appendLine(requirements)
                appendLine("=== END REQUIREMENTS ===")
                appendLine()
                appendLine("Files written so far (complete; don't send them again unless you change them):")
                appendFiles()
                if (cut != null) {
                    appendLine()
                    appendLine("Your last reply was cut off in the middle of $cut, so that file was NOT saved. Write it again in full (split it into smaller files if it's long).")
                }
                appendLine()
                appendLine("Now write the remaining files, so that every requirement is implemented and everything that is used exists (classes, functions, screens, resources).")
                append(sizeRule)
            }))
            // Nothing new came back: stop asking.
            if (before == (files.size to pendingFiles.size) && cutOffFile(lastReply) == null) break
        }
        return notes
    }

    /** Checks the written app against the requirements, one by one, and has the model add whatever is missing. */
    private suspend fun reviewAgainstRequirements(notesIn: List<String>): List<String> {
        var notes = notesIn
        repeat(reviewRounds) { round ->
            step(BuildEvent.Kind.STEP, if (round == 0) "Checking that everything you asked for is in the app" else "Checking once more that nothing is missing")
            val reply = ask(buildString {
                appendLine("Review this Android app against its requirements before it is built.")
                appendLine()
                appendLine("=== REQUIREMENTS ===")
                appendLine(requirements)
                appendLine("=== END REQUIREMENTS ===")
                appendLine()
                appendLine("Current files:")
                appendFiles()
                appendLine()
                appendLine("Go through the requirements one at a time and look for:")
                appendLine("- anything asked for that is missing or only partly done;")
                appendLine("- anything DIFFERENT from what was asked (ARIN-PROTECTED: exact-request): a different app, renamed, swapped, " +
                    "simplified or extra-limited features, other colours/layout/behaviour than described — make it exactly as asked;")
                appendLine("- placeholders, TODOs, empty click handlers, buttons that do nothing, sample data where real behaviour was asked for;")
                appendLine("- screens that can't be reached, settings that aren't saved, data that is lost when the app restarts (if it should be kept);")
                appendLine("- code that won't compile: missing imports, classes, functions or resources, wrong API names, missing manifest permissions.")
                appendLine()
                appendLine("If you find anything, reply with every file to add or change, in full, in the usual format; start each with an")
                appendLine("=== ABOUT: line saying in plain English which requirement it completes or what it fixes. $sizeRule")
                append("If every requirement is fully implemented and the code is correct, reply with exactly: === COMPLETE ===")
            })
            val changedBefore = pendingFiles.size to files.hashCode()
            if (!reply.contains("=== FILE:") && parse(reply).files.isEmpty()) {
                step(BuildEvent.Kind.DONE, "Checked: everything you asked for is in the app")
                return notes
            }
            notes = apply(reply)
            notes = finishWriting(notes)
            if (changedBefore == (pendingFiles.size to files.hashCode())) return notes
        }
        return notes
    }

    /** Files most likely to matter first: ones named in [hint] (errors or the request), then build files, then sources. */
    private fun prioritized(hint: String): List<Map.Entry<String, String>> {
        val h = hint.lowercase()
        return files.entries.sortedBy { (path, _) ->
            val name = path.substringAfterLast('/').lowercase()
            when {
                h.contains(path.lowercase()) || h.contains(name) -> 0
                name.startsWith("build.gradle") || name.startsWith("settings.gradle") || name == "libs.versions.toml" -> 1
                name == "androidmanifest.xml" || name.endsWith(".kt") || name.endsWith(".java") -> 2
                else -> 3
            }
        }
    }

    private fun StringBuilder.appendFiles(hint: String = "") {
        // Offline models have a much smaller memory for text.
        var budget = if (self) 160_000 else 130_000
        prioritized(hint).forEach { (path, content) ->
            if (budget <= 0) return@forEach
            val c = content.take(budget)
            budget -= c.length
            appendLine("=== FILE: $path ===")
            appendLine(c)
            appendLine("=== END FILE ===")
        }
        if (deps.isNotEmpty()) appendLine("Extra dependencies: ${deps.joinToString()}")
    }

    /** Credit saver: the files the errors name, in full; every other file only as a one-line map entry. */
    private fun StringBuilder.appendFilesLean(hint: String) {
        val all = prioritized(hint)
        val named = all.filter { (p, _) -> hint.contains(p.substringAfterLast('/')) || lastReply.contains(p.substringAfterLast('/')) }.take(4)
        val send = named.ifEmpty { all.take(2) }
        var budget = 40_000
        send.forEach { (path, content) ->
            if (budget <= 0) return@forEach
            val c = content.take(budget); budget -= c.length
            appendLine("=== FILE: $path ==="); appendLine(c); appendLine("=== END FILE ===")
        }
        appendLine("Other files (map — names and functions only):")
        all.filter { it !in send }.forEach { (p, c) ->
            val sig = Regex("(?m)^\\s*(?:@\\w+\\s+)*(?:(?:private|internal|public|override|suspend|data|sealed|abstract|open)\\s+)*(fun|class|object|interface)\\s+[\\w<>.,? ]+").findAll(c)
                .map { it.value.trim() }.take(12).joinToString("; ")
            appendLine("- $p" + if (sig.isNotBlank()) ": $sig" else "")
        }
        if (deps.isNotEmpty()) appendLine("Extra dependencies: ${deps.joinToString()}")
    }

    private fun changePrompt() = buildString {
        if (self) {
            appendLine("Here is the source of Arin itself: the Android AI assistant app you are running in (package com.arin, Kotlin + Jetpack Compose).")
            appendLine("Its build setup, GitHub workflow and signing key already work: don't change .github/, the keystore, applicationId or targetSdk.")
            appendLine("Increase versionCode by 1 and the last number of versionName in app/build.gradle.kts, and update the version shown in Settings to match.")
            appendLine("Keep everything that works; change only what the request needs. Add new files where they fit the existing package layout.")
        } else {
            appendLine("Here is an existing Android app, \"$appName\" (${if (imported) "uploaded by the user" else "originally: ${req.project?.description}"}).")
        }
        appendLine()
        appendLine("Current files:")
        appendFiles(req.description)
        appendLine()
        if (!self && !imported && requirements.isNotBlank() && requirements != req.description) {
            appendLine("Everything this app must do (keep all of it working, and add anything from this list that is still missing):")
            appendLine("=== REQUIREMENTS ===")
            appendLine(requirements)
            appendLine("=== END REQUIREMENTS ===")
            appendLine()
        }
        appendLine("Make this change: ${req.description}")
        if (self) appendLine(com.arin.selffix.SelfUpdateRules.RULE_FOR_MODEL)
        if (self && req.unlockProtected) appendLine("The owner APPROVED changing the protected rules this change needs (only those, exactly as described).")
        appendLine(attemptNotes())
        appendLine()
        appendLine("Reply with every file you change or add, in full, in the same format. Files you don't send stay as they are.")
        if (!self) appendLine(sizeRule)
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    /**
     * ARIN-PROTECTED: before every fix, research the error on the internet (searches + the best pages). When the same
     * error comes back, Claude is also asked for help (when connected), whatever model this build uses.
     */
    private suspend fun researchFirst(query: String, errors: String): String {
        if (researchUsed >= budget("research", 15)) {
            step(BuildEvent.Kind.STEP, "Web-research budget for this build used up (${researchUsed}) — using what was already found")
            return ""
        }
        researchUsed++
        val seen = repeats.count(query.ifBlank { errors.take(200) })
        // ARIN-PROTECTED: credit-saver — 1) a fix that worked before for this exact error (no AI at all); 2) Claude
        // researches and returns the patch (code-only), so your model reads a short answer instead of web pages.
        // Your model stays the boss: it makes the change. If Claude can't help, your model researches as before.
        if (com.arin.selffix.CreditSaver.on(context) && !workerOn()) {
            val ctx = context!!
            val sig = com.arin.selffix.CreditSaver.signature(errors).ifBlank { realErrorLine(errors).take(160) }
            if (seen < 2) com.arin.selffix.CreditSaver.rememberedFix(ctx, sig)?.let {
                step(BuildEvent.Kind.THINKING, "Seen this error before — using the fix that worked (no AI needed)")
                return "A fix that worked before for this exact error (apply it):\n$it"
            } else com.arin.selffix.CreditSaver.forgetFix(ctx, sig)
            if (helperDown == null && (com.arin.selffix.ClaudeHelper.route(ctx, helperTab) != null || com.arin.selffix.ClaudeHelper.route(ctx) != null)) {
                val trimmed = com.arin.selffix.CreditSaver.trimErrors(errors)
                val code = prioritized(trimmed).filter { (p, _) -> trimmed.contains(p.substringAfterLast('/')) }.take(2)
                    .joinToString("\n") { (p, c) -> "FILE: $p\nBODY:\n" + c.take(6_000) }
                step(BuildEvent.Kind.THINKING, "Claude is researching this error and writing the fix")
                val answer = com.arin.selffix.ClaudeHelper.ask(
                    context = ctx,
                    question = projectNote() + code + "\nERROR:\n" + trimmed + (if (seen >= 2) "\nATTEMPT: $seen (the last fix didn't work — take a different approach)" else "") +
                        "\nNEED: research if needed, then return only the patched code (whole changed functions or files).",
                    source = helperTab, hard = seen >= 2,
                ) { step(BuildEvent.Kind.THINKING, it.take(120)) }
                if (usableHelp(answer)) {
                    com.arin.selffix.CreditSaver.rememberFix(ctx, sig, answer)
                    return "Claude (your helper) researched this error. Its patch — apply it; you make the change:\n" + answer.take(8_000)
                }
            }
        }
        var web = com.arin.selffix.AutoFix.research(query, errors) { step(BuildEvent.Kind.WEB, it) }
        if (seen >= 2) web += "\n\nThis same error has come back $seen times: use a clearly different approach this time."
        // ARIN-PROTECTED: with the AI helper on, every build error is solved one by one with its help (the model asks,
        // the helper gives the solution, the model makes the fix). With it off, the model works alone.
        if (context != null && helperDown == null && !workerOn() && helperOn() && com.arin.selffix.ClaudeHelper.route(context, helperTab) != null) {
            step(BuildEvent.Kind.THINKING, "Asking ${com.arin.selffix.ClaudeHelper.helper(context).name} for the solution to this error")
            val answer = com.arin.selffix.ClaudeHelper.ask(
                context = context,
                question = "An Android (Kotlin/Gradle) build failed" + (if (seen >= 2) " with the same error after $seen fix attempts" else "") +
"; I am the building model (the boss) and ask you for the solution.\n\nErrors:\n" + errors.take(6_000) + "\n\nWhat I found on the internet:\n" + web.take(4_000) +
                    "\n\nWhat is the real cause, and exactly what should be changed (files and code) to fix it? Write to the model.",
                source = helperTab,
            ) { step(BuildEvent.Kind.THINKING, it.take(120)) }
            if (usableHelp(answer))
                web += "\n\nThe AI helper's solution (use it; you make the fix):\n" + answer.take(8_000)
        }
        return web
    }

    // ARIN-PROTECTED: credit-saver + helper-sees-only-the-boss — Claude (Claude Code login) may search a read-only COPY
    // of the code being built, so the boss doesn't spend its tokens pasting files. Never the person's request or files.
    private fun projectNote(): String {
        val ctx = context ?: return ""
        if (workerOn() || !com.arin.selffix.CreditSaver.on(ctx) || com.arin.selffix.ClaudeHelper.lastRoute != "pro" || files.isEmpty()) return ""
        return runCatching {
            val dir = java.io.File(com.arin.terminal.Linux.rootfs(ctx), "root/.arin/helper-work/project")
            dir.deleteRecursively(); dir.mkdirs()
            files.forEach { (p, c) ->
                if (p.contains("..")) return@forEach
                java.io.File(dir, p).apply { parentFile?.mkdirs() }.writeText(c)
            }
            "PROJECT: /root/.arin/helper-work/project (read-only copy of the code)\n"
        }.getOrDefault("")
    }

    /** The AI helper helps from start to end when the person switched it on, or asked for it for this build. */
    private fun helperOn(): Boolean = context != null && com.arin.selffix.ClaudeHelper.enabled(context, helperTab)

    /** Each tab has its own helper switch: this build follows the tab it was started from. */
    private val helperTab: String get() = req.helperTab.ifBlank { if (self) "arin-update" else "build" }

    /**
     * A phone build needs the whole project on the phone: the repository from GitHub (with its binary files, e.g. Arin's
     * signing key) when there is one, else the generated project; then the code written so far on top.
     */
    private suspend fun ensureLocalCopy(repo: GitHubClient.Repo, slug: String) {
        val ctx = context ?: return
        if (repo.owner != LOCAL_OWNER && req.githubToken.isNotBlank()) {
            step(BuildEvent.Kind.DOWNLOAD, "Copying ${repo.owner}/${repo.name} to the phone for the phone build")
            runCatching {
                withContext(Dispatchers.IO) {
                    val reqZip = okhttp3.Request.Builder().url("https://api.github.com/repos/${repo.owner}/${repo.name}/zipball/${repo.branch}")
                        .header("Authorization", "Bearer ${req.githubToken}").build()
                    Http.client.newCall(reqZip).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("GitHub: HTTP ${r.code}")
                        val all = linkedMapOf<String, ByteArray?>()
                        java.util.zip.ZipInputStream(r.body!!.byteStream()).use { z ->
                            while (true) {
                                val e = z.nextEntry ?: break
                                if (!e.isDirectory) {
                                    val path = e.name.substringAfter('/')
                                    if (path.isNotBlank()) all[path] = z.readBytes()
                                }
                            }
                        }
                        LocalBuild.write(ctx, slug, all)
                    }
                }
            }.onFailure { step(BuildEvent.Kind.ERROR, "Couldn't copy the repository to the phone: ${it.message}") }
        } else if (!imported) {
            LocalBuild.write(ctx, slug, ProjectTemplate.files(appName, packageName, deps.toList()).mapValues { it.value.toByteArray() } +
                mapOf("app/signing.keystore" to android.util.Base64.decode(ProjectTemplate.SHARED_KEYSTORE_B64, android.util.Base64.DEFAULT)))
        }
        LocalBuild.write(ctx, slug, files.mapValues { it.value.toByteArray() })
    }

    /** The model wrote the code; the AI helper reviews it, and the model fixes what the helper found. */
    private suspend fun helperReview() {
        val ctx = context ?: return
        val changed = pendingFiles.keys.filter { files[it] != null }.take(20)
        val code = changed.joinToString("\n\n") { p -> "=== $p ===\n" + files[p].orEmpty().take(6_000) }.take(40_000)
        step(BuildEvent.Kind.THINKING, "Asking the AI helper to review the code")
        if (com.arin.selffix.ClaudeHelper.route(ctx, helperTab) == null && com.arin.selffix.ClaudeHelper.route(ctx) == null) return
        val help = com.arin.selffix.ClaudeHelper.ask(ctx,
            // Only the boss's own code — never the person's request (helper-sees-only-the-boss).
            projectNote() + "NEED: review this code before the build: list only real bugs/build errors, each as FILE + the " +
                "patched code. If it's all right, answer just: OK\n\nCODE:\n$code",
            source = helperTab)
        if (!usableHelp(help)) return
        if (help.trim().let { it == "OK" || it.startsWith("OK.") || it.contains("NO_HELP_NEEDED") }) return
        step(BuildEvent.Kind.THINKING, "Fixing what the AI helper found")
        apply(ask(buildString {
            appendLine("The AI helper reviewed the code you wrote. Its review:")
            appendLine(help.take(10_000))
            appendLine()
            appendLine("Current files:")
            if (com.arin.selffix.CreditSaver.on(context)) appendFilesLean(help.take(2_000)) else appendFiles(help.take(300))
            appendLine()
            appendLine("Fix what is right in the review (keep everything that was asked). Reply with every file you change, in full, " +
                "in the same format" + (if (com.arin.selffix.CreditSaver.on(context)) " (or EDIT blocks)" else "") +
                "; if nothing needs changing, reply with just: NO CHANGES")
        }))
    }

    /** Directions sent during a build: change the code that's already written (the directions are added by [ask]). */
    private fun steerPrompt() = buildString {
        appendLine("Here is the current code of \"$appName\". It was being built when the owner sent new directions (listed at the end).")
        if (self) appendLine(com.arin.selffix.SelfUpdateRules.RULE_FOR_MODEL)
        appendLine()
        appendLine("Current files:")
        appendFiles(steered.joinToString(" "))
        appendLine()
        appendLine("Apply the owner's new directions exactly, and keep everything else as it is (nothing that was asked before may be dropped).")
        appendLine("Reply with every file you change or add, in full, in the same format. Files you don't send stay as they are.")
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    /** The first compiler or Gradle error, cleaned up to search the web for it. */
    private fun mainError(errors: String): String {
        val line = realErrorLine(errors).ifBlank { return "" }
        return "android gradle " + line.removePrefix("e: ")
            .replace(Regex("""file:///\S+?:\d+:\d+\s*"""), "")
            .replace(Regex("""(/[\w.@+-]+){2,}"""), " ")
            .replace(Regex("""\s+"""), " ").trim().take(150)
    }

    private var lastFingerprint: String? = null
    /** Consultations the helper declined or couldn't answer (by error fingerprint): never sent again. */
    private val declined = mutableSetOf<String>()

    /**
     * The task ledger (COMPLETED / IN PROGRESS / BLOCKED / ALTERNATIVE IMPLEMENTATION / TESTING / VERIFIED), shown in the
     * steps and with the result, so the owner sees honestly where everything stands.
     */
    private val ledgerItems = linkedMapOf<String, String>()
    private fun ledger(item: String, status: String) {
        if (ledgerItems[item] == status) return
        ledgerItems[item] = status
        step(BuildEvent.Kind.STEP, "Ledger · $item: $status")
    }
    fun ledgerText(): String = if (ledgerItems.isEmpty()) "" else
        "**Task ledger**\n" + ledgerItems.entries.joinToString("\n") { (k, v) -> "- $k — $v" }

    /**
     * One whole-problem consultation with the helper: the complete error report and everything needed to judge it.
     * Returns its diagnosis for the fixer, or "" (no helper, declined, or nothing useful).
     */
    // ARIN-PROTECTED: research-fallback — a failed source is recorded and never asked the same thing again in this build:
    // an empty answer, "no usable files", a git/search command that failed (exit code 128), or the helper's usage
    // limit (e.g. Claude's 5-hour limit) moves the work to the next source (web research, the worker, your model).
    private var helperDown: String? = null
    private fun usableHelp(answer: String): Boolean {
        val a = answer.trim()
        val why = when {
            a.isBlank() -> "empty answer"
            a.contains("couldn't be reached") || a.contains("isn't connected") || a.contains("switched off") -> "not reachable"
            com.arin.claudecode.ClaudeLimit.isLimit(a.take(800)) -> "usage limit reached"
            Regex("(?i)no usable (files|answer)|returned nothing usable").containsMatchIn(a.take(600)) -> "no usable files"
            Regex("""^\[Helper .{0,120}— (POLICY_OR_SAFETY_REFUSAL|AUTHORIZATION_REQUIRED|MISUNDERSTOOD_OBJECTIVE|INSUFFICIENT_INFORMATION|TECHNICAL_LIMITATION|UNCLASSIFIED_REFUSAL|INCOMPLETE_RESPONSE|NETWORK_OR_PROVIDER_FAILURE)""").containsMatchIn(a.take(300)) ->
                "the helper declined or failed (" + Regex("""— ([A-Z_]+)""").find(a.take(300))!!.groupValues[1] + ")"
            Regex("(?i)exit(ed)? (with )?code 128|not a git repository").containsMatchIn(a.take(1_500)) && a.length < 1_500 -> "git/search command failed (exit 128)"
            else -> null
        } ?: return true
        if (why == "usage limit reached" || why == "not reachable") {
            if (helperDown == null) {
                helperDown = why
                step(BuildEvent.Kind.STEP, "The helper is unavailable ($why) — switching to web research and the other models for the rest of this build")
                ledger("Helper", "BLOCKED — $why; using other sources")
            }
        } else ledger("Research source", "FAILED — $why; trying the next source")
        return false
    }

    private suspend fun consultHelper(errors: String, same: Int): String {
        val ctx = context ?: return ""
        if (com.arin.selffix.ClaudeHelper.route(ctx, helperTab) == null || helperDown != null) return ""
        if (consultUsed >= budget("consult", 8)) return ""
        consultUsed++
        val fp = com.arin.selffix.CreditSaver.signature(errors).ifBlank { realErrorLine(errors).take(160) } + "#" + (same >= 3)
        if (fp in declined) return ""
        val h = com.arin.selffix.ClaudeHelper.helper(ctx)
        step(BuildEvent.Kind.THINKING, "${bossName()} is sending ${h.name} the whole problem: the full error report, the code at each error, versions and what was tried")
        val pkg = buildString {
            appendLine("You are a senior Android/Kotlin build engineer, consulted on a failing Gradle build of an Android app.")
            appendLine("APP: " + (if (self) "Arin (an Android AI assistant app) — an update to it" else "$appName ($packageName)"))
            // Never the owner's own words: the main model's brief (if it wrote one), else nothing.
            (briefCache?.second ?: mem?.lastOwnerBrief())?.takeIf { it.isNotBlank() }?.let { appendLine("WHAT IT MUST DO (the main model's brief): " + it.take(1_500)) }
            appendLine("TOOLCHAIN: Kotlin ${ProjectTemplate.KOTLIN}, Gradle ${ProjectTemplate.GRADLE}, JDK ${ProjectTemplate.JDK}, " +
                "compileSdk/targetSdk ${ProjectTemplate.SDK}, Compose BOM ${ProjectTemplate.COMPOSE_BOM}")
            if (deps.isNotEmpty()) appendLine("EXTRA LIBRARIES/PLUGINS: " + deps.joinToString())
            appendLine("FILES IN THE PROJECT: " + files.keys.filter { !it.startsWith(".github") }.take(80).joinToString())
            appendLine()
            appendLine("FULL ERROR REPORT (root cause first):")
            appendLine("Root-cause line: " + realErrorLine(errors))
            appendLine(errors.take(12_000))
            appendLine()
            appendLine("CODE AT EACH ERROR SITE (numbered lines):")
            appendLine(errorRegions(errors).take(10_000))
            relatedFiles(errorFiles(errors)).takeIf { it.isNotEmpty() }?.let { appendLine("OTHER FILES THAT USE THIS CODE: " + it.joinToString("; ")) }
            mem?.knownAbout(com.arin.selffix.CreditSaver.signature(errors).ifBlank { realErrorLine(errors).take(160) })?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
            if (tried.isNotEmpty()) {
                appendLine()
                appendLine("ALREADY TRIED (did not work — this error came back ${same} time(s)):")
                tried.takeLast(8).forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("Give ONE coherent engineering solution for the whole problem (not piece by piece):")
            appendLine("1. ROOT CAUSE — what is really wrong and why (all related errors).")
            appendLine("2. REQUIRED CHANGES — the exact edits, file by file (code for the changed parts; Gradle/library/version lines in full).")
            appendLine("3. COMPATIBILITY — versions and APIs that must match.")
            appendLine("4. REGRESSIONS — what else the change could break, and how to avoid it.")
            appendLine("5. TEST — how to confirm it's fixed.")
            appendLine("Keep every feature the app must have. If part of this is something you can't help with, say which part and why, " +
                "help with the rest, and suggest a legitimate alternative.")
        }
        val answer = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(300_000) {
                com.arin.selffix.ClaudeHelper.ask(ctx, pkg, helperTab, hard = true, ownerTexts = ownerTexts()) { s -> step(BuildEvent.Kind.THINKING, s) }
            }
        }.getOrNull().orEmpty().trim()
        val useless = answer.length < 80 || !usableHelp(answer)
        if (useless || com.arin.selffix.CreditSaver.isRefusal(answer.take(600))) {
            declined += fp
            val why = if (useless) "no answer" else answer.lines().firstOrNull { it.isNotBlank() }.orEmpty().take(140)
            step(BuildEvent.Kind.THINKING, "${h.name} couldn't help with this one ($why) — ${bossName()} carries on without it, and won't ask the same again")
            ledger("Helper consultation", "BLOCKED for this error — carrying on with web research and the worker")
            // Keep anything useful from a partial answer.
            return if (!useless && answer.length > 400) "HELPER'S PARTIAL ANSWER (it declined some of it — use what is useful):\n" + answer.take(6_000) else ""
        }
        step(BuildEvent.Kind.DONE, "${h.name} sent a root-cause diagnosis and a fix plan")
        return "CONSULTANT'S WHOLE-PROBLEM DIAGNOSIS (${h.name}). Evaluate it against the code, then apply the correct fix:\n" + answer.take(10_000)
    }

    /** How many times each error has come back (by signature), and what each fix round changed. */
    private val sameErrors = mutableMapOf<String, Int>()
    private val tried = mutableListOf<String>()
    private var stuck = 0

    /**
     * Fixes Arin can make by itself (no AI) for errors that keep coming back: build-tool versions that don't fit.
     * Returns what it did, or null.
     */
    private suspend fun autoRepair(errors: String): String? {
        val e = errors.lowercase()
        if ("ksp" in e && deps.any { it.startsWith("plugin:com.google.devtools.ksp:") }) {
            val newest = resolveDependency("com.google.devtools.ksp:symbol-processing-gradle-plugin:latest")?.substringAfterLast(':')?.takeIf { ProjectTemplate.kspFits(it) } ?: ProjectTemplate.KSP
            val now = deps.first { it.startsWith("plugin:com.google.devtools.ksp:") }
            if (now.endsWith(":$newest")) return null
            deps.remove(now); deps += "plugin:com.google.devtools.ksp:$newest"; depsChanged = true
            return "Set the code generator (KSP) to its newest release, $newest, to fit Kotlin ${ProjectTemplate.KOTLIN}"
        }
        // A library version that doesn't exist or doesn't fit: move every saved library to its newest stable release.
        if (("could not resolve" in e || "could not find" in e || "aarmetadata" in e) && !imported) {
            var moved = 0
            deps.filter { !it.startsWith("plugin:") }.forEach { d ->
                val ksp = d.startsWith("ksp:")
                val coord = d.removePrefix("ksp:").substringBeforeLast(':')
                if (!e.contains(coord.substringAfter(':').lowercase())) return@forEach
                val newest = resolveDependency("$coord:latest") ?: return@forEach
                if (newest != d.removePrefix("ksp:")) { deps.remove(d); deps += if (ksp) "ksp:$newest" else newest; moved++ }
            }
            if (moved > 0) { depsChanged = true; return "Moved $moved library version(s) to the newest stable release" }
        }
        return null
    }

    private fun fixPrompt(errors: String, notes: List<String>, web: String = "") = buildString {
        appendLine(if (errors.startsWith("RUNTIME PROBLEM")) "The app below builds, but it CRASHES or won't run when opened on an Android emulator. " +
            "Find the cause in the crash log (the first 'Caused by' and the app's own lines in the stack trace) and fix it. " +
            "Typical causes: a missing permission or activity in the manifest, a null value, network on the main thread, a missing resource." else "The app below failed to build. Fix it.")
        appendLine("What the app must do (keep every feature; never fix an error by removing what was asked for):")
        appendLine(if (self) req.description else requirements.take(14_000))
        appendLine()
        appendLine("Build errors:")
        appendLine(if (com.arin.selffix.CreditSaver.on(context)) com.arin.selffix.CreditSaver.trimErrors(errors) else errors)
        // ARIN-PROTECTED: batch-fix — every listed error is fixed in this one pass, not one per build.
        appendLine()
        appendLine("FIX ALL OF THE ERRORS ABOVE IN THIS ONE PASS (group related ones, fix the root cause of each). Don't fix one and leave " +
            "the rest for another build. Keep Kotlin ${ProjectTemplate.KOTLIN}, KSP ${ProjectTemplate.KSP} (code generators like Room's compiler only " +
            "through ksp(...), never kapt), minSdk 26, targetSdk ${ProjectTemplate.SDK}.")
        if (errors.contains("AarMetadata", true) || errors.contains("compileSdk", true) && errors.contains("depend", true)) {
            appendLine()
            appendLine("HOW TO FIX THIS KIND OF ERROR: a library needs a newer compileSdk than this project (compileSdk ${ProjectTemplate.SDK}). Do NOT raise " +
                "compileSdk. Write that library's DEPENDENCY line again with version latest (Arin then uses its newest STABLE release), " +
                "or remove it if Compose/material3 already covers it (androidx.compose.material:material is the OLD Material 2: use material3).")
        }
        if (tried.isNotEmpty()) {
            appendLine()
            appendLine("EARLIER FIX ROUNDS (these did NOT solve it — don't repeat them):")
            tried.takeLast(8).forEach { appendLine("- $it") }
        }
        if (stuck >= 3) {
            appendLine()
            appendLine("THIS SAME ERROR HAS COME BACK $stuck TIMES. Your previous fixes did not work, so change method completely. " +
                "Read the error's exact words and the file and line it names. Try ONE of: (1) the import or name is wrong — find the " +
                "right class/package for the API you use (check the library's current docs) or write the code without it; (2) a " +
                "version doesn't fit — use the newest stable library/plugin, or a different library that does the same job; " +
                "(3) rewrite the failing file from scratch, cleanly, keeping every feature; (4) if a build file is wrong, write it again " +
                "in full. Never delete a feature the owner asked for.")
        }
        if (notes.isNotEmpty()) {
            appendLine()
            appendLine("Notes from the build tool:")
            notes.forEach { appendLine("- $it") }
        }
        if (web.isNotBlank()) {
            appendLine()
            appendLine(com.arin.selffix.AutoFix.RULE_FOR_MODEL)
            if (self) appendLine(com.arin.selffix.SelfUpdateRules.RULE_FOR_MODEL)
            appendLine("I searched the internet for the main error. Results:")
            appendLine(web)
            appendLine("Use what fits (you may also <search> or <fetch> for more), and try a different approach than last time.")
        }
        appendLine()
        // Stuck: send every file in full (the lean view may be hiding the cause).
        val saver = com.arin.selffix.CreditSaver.on(context) && stuck < 3
        appendLine(if (saver) "Files involved (if you need another file in full, say which and it will be sent):" else "Current files:")
        if (saver) appendFilesLean(errors) else appendFiles(errors)
        appendLine()
        if (saver) {
            appendLine("For a small change, reply with EDIT blocks instead of whole files:")
            appendLine("=== EDIT: path ===\n<<<<<<< SEARCH\n(exact lines now in the file)\n=======\n(new lines)\n>>>>>>> REPLACE\n=== END EDIT ===")
            appendLine("For bigger changes send the whole file as before.")
        }
        appendLine("Reply with every file you change, in full, in the same format (or EDIT blocks). Files you don't send stay as they are.")
        appendLine("To delete a file, send it with the single line: DELETE")
    }

    /** Applies the model's output; returns notes to pass back to the model (skipped downloads etc.). */
    private suspend fun apply(output0: String): List<String> {
        val before = files.map { it.key + "#" + it.value.hashCode() }.toSet()
        val notes = applyFiles(output0)
        val now = files.map { it.key + "#" + it.value.hashCode() }
        val touched = now.filter { it !in before }.map { it.substringBeforeLast('#') }
        if (touched.isNotEmpty()) {
            codedThisRun = true
            touched.forEach { if (it !in lastChanged) lastChanged += it }
            if (notes.isNotEmpty()) lastNotes = notes
        }
        return notes
    }

    private suspend fun applyFiles(output0: String): List<String> {
        val notes = mutableListOf<String>()
        // Small edits ("replace these lines with these"): applied to the current file; if one doesn't match, the model is
        // asked for the whole file instead — nothing breaks to save tokens.
        var output = output0
        Regex("(?s)=== EDIT: (.+?) ===\\s*\\n(.*?)=== END EDIT ===").findAll(output0).forEach { m ->
            val path = normalizePath(m.groupValues[1].trim('`', ' '))
            var content = files[path]
            if (content == null) { notes += "Edit for $path: no such file — send it in full."; return@forEach }
            Regex("(?s)<<<<<<< SEARCH\\n(.*?)\\n=======\\n(.*?)\\n>>>>>>> REPLACE").findAll(m.groupValues[2]).forEach { b ->
                val from = b.groupValues[1]
                if (content!!.contains(from)) content = content!!.replaceFirst(from, b.groupValues[2])
                else notes += "An edit for $path didn't match its current text — send the whole file instead."
            }
            if (content != files[path]) { files[path] = content!!; pendingFiles[path] = content!!.toByteArray(); step(BuildEvent.Kind.FILE, "Edited ${path.substringAfterLast('/')}") }
            output = output.replace(m.value, "")
        }
        val parsed = parse(output)
        if (parsed.truncated) notes += "Your last reply was cut off. Send fewer or shorter files."
        parsed.files.map { (p, c) -> normalizePath(p) to c }.forEach { (path, content) ->
            val allowed = if (imported) !path.startsWith(".github/") && !path.endsWith(".jar") else path.startsWith("app/src/main/")
            if (!allowed || path.contains("..") || path.startsWith("/")) {
                notes += if (imported) "Ignored $path: .github/ and the wrapper jar can't be changed." else "Ignored $path: only files under app/src/main/ are allowed."
            } else if (content.trim() == "DELETE") {
                if (files.remove(path) != null) pendingFiles[path] = null
            } else if (files[path] != content) {
                files[path] = content
                pendingFiles[path] = content.toByteArray()
            }
        }
        if (imported && parsed.deps.isNotEmpty()) notes += "Add libraries by editing the module's build.gradle(.kts) directly."
        if (!imported) parsed.deps.forEach { raw ->
            val d = raw.trim().removePrefix("ksp:").removePrefix("ksp ").removePrefix("implementation ").trim().trim('"', '(', ')')
            // Code generators (Room's compiler) are added through KSP, with its Gradle plugin.
            val generator = raw.trim().startsWith("ksp") || ProjectTemplate.kspArtifacts.any { d.contains(":$it") }
            val resolved = resolveDependency(d)
            if (resolved == null) {
                notes += "Dependency $d was not found on Maven; use a different library."
                return@forEach
            }
            val entry = if (generator) "ksp:$resolved" else resolved
            if (entry !in deps) {
                deps.removeAll { it.removePrefix("ksp:").substringBeforeLast(':') == resolved.substringBeforeLast(':') }
                deps += entry
                depsChanged = true
                step(BuildEvent.Kind.LIBRARY, "Added library $resolved")
            }
            if (generator && deps.none { it.startsWith("plugin:com.google.devtools.ksp:") }) {
                val ksp = resolveDependency("com.google.devtools.ksp:symbol-processing-gradle-plugin:latest")?.substringAfterLast(':')
                    ?.takeIf { v -> v.none { it.isLetter() } } // a finished release, not a beta or RC
                deps += "plugin:com.google.devtools.ksp:" + (ksp?.takeIf { ProjectTemplate.kspFits(it) } ?: ProjectTemplate.KSP)
                depsChanged = true
            }
            if (resolved.startsWith("androidx.room:room-compiler:")) {
                val runtime = "androidx.room:room-runtime:" + resolved.substringAfterLast(':')
                if (deps.none { it.substringBeforeLast(':') == "androidx.room:room-runtime" }) {
                    deps += runtime
                    depsChanged = true
                }
            }
        }
        parsed.downloads.forEach { (url, path) -> download(url, path)?.let { notes += it } }
        // ARIN-PROTECTED: self-updates change only Arin, keep its id, key and protected rules, and raise the version.
        if (self && selfOriginal.isNotEmpty()) {
            com.arin.selffix.SelfUpdateRules.enforce(selfOriginal, files, pendingFiles, req.unlockProtected).forEach {
                notes += it
                step(BuildEvent.Kind.STEP, it)
            }
        }
        return notes
    }

    private suspend fun waitForRun(repo: GitHubClient.Repo, sha: String): GitHubClient.Run? {
        var run: GitHubClient.Run? = null
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 35 * 60_000) {
            run = runCatching { gh.runFor(repo, sha, workflowFile) }.getOrNull() ?: run
            if (run == null && System.currentTimeMillis() - start > 3 * 60_000) return null
            if (run?.status == "completed") return run
            // A direction from the person: stop waiting and apply it now (then build again).
            for (i in 0 until 10) {
                if (hasSteering()) { steerNow = true; return run }
                delay(1_000)
            }
        }
        return run
    }

    // ---- Internet access: libraries and files ----

    private val repos = listOf(
        "https://dl.google.com/android/maven2",
        "https://repo1.maven.org/maven2",
        "https://jitpack.io",
    )

    private val safeHosts = listOf(
        "github.com", "raw.githubusercontent.com", "githubusercontent.com", "fonts.google.com",
        "fonts.gstatic.com", "upload.wikimedia.org", "commons.wikimedia.org", "repo1.maven.org",
        "dl.google.com", "jitpack.io", "unpkg.com", "cdn.jsdelivr.net",
    )

    private suspend fun httpText(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.string() else null
            }
        }.getOrNull()
    }

    /** A finished release: not alpha, beta, RC, dev, milestone, EAP or snapshot. */
    private fun stableVersion(v: String) = !Regex("(?i)(alpha|beta|rc|dev|eap|snapshot|preview|-m\\d|\\.m\\d)").containsMatchIn(v)

    private val versionOrder = Comparator<String> { a, b ->
        val x = a.split(Regex("[.\\-]")); val y = b.split(Regex("[.\\-]"))
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrNull(i).orEmpty(); val q = y.getOrNull(i).orEmpty()
            val c = (p.toIntOrNull() ?: -1).compareTo(q.toIntOrNull() ?: -1).takeIf { it != 0 } ?: p.compareTo(q)
            if (c != 0) return@Comparator c
        }
        0
    }

    /** Checks the library exists; replaces "latest", "+", a missing version or a pre-release with the newest stable release. */
    private suspend fun resolveDependency(coord: String): String? {
        val parts = coord.trim().split(":")
        if (parts.size < 2) return null
        val (group, artifact) = parts
        val version = parts.getOrNull(2)?.trim().orEmpty().let { if (it.isNotEmpty() && !stableVersion(it)) "latest" else it }
        val path = group.replace('.', '/') + "/" + artifact
        for (base in repos) {
            if (version.isNotEmpty() && version != "latest" && !version.contains('+')) {
                if (httpText("$base/$path/$version/$artifact-$version.pom") != null) return "$group:$artifact:$version"
            }
            val meta = httpText("$base/$path/maven-metadata.xml") ?: continue
            // ARIN-PROTECTED: complete-project — "latest" means the newest STABLE release (alphas, betas and RCs often need
            // a newer compileSdk or Gradle than the project has, and break the build: checkDebugAarMetadata).
            val all = Regex("<version>([^<]+)</version>").findAll(meta).map { it.groupValues[1].trim() }.toList()
            val latest = all.filter { stableVersion(it) }.maxWithOrNull(versionOrder)
                ?: Regex("<release>([^<]+)</release>").find(meta)?.groupValues?.get(1)
                ?: all.lastOrNull()
            if (latest != null) return "$group:$artifact:$latest"
        }
        return null
    }

    private val resourceName = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9]+)?$")

    /** Downloads a file into the project; returns a note for the model if it was skipped. */
    private suspend fun download(url: String, path: String): String? {
        val okPath = path.startsWith("app/src/main/res/raw/") || path.startsWith("app/src/main/res/drawable/") ||
            path.startsWith("app/src/main/res/font/") || path.startsWith("app/src/main/assets/")
        if (!okPath || path.contains("..")) return "Skipped download to $path: use res/raw, res/drawable, res/font or assets."
        if (!path.startsWith("app/src/main/assets/") && !resourceName.matches(path.substringAfterLast('/'))) {
            return "Skipped $path: resource names must be lowercase letters, digits and underscores."
        }
        val host = runCatching { URI(url).host?.lowercase() }.getOrNull()
        if (host == null || !url.startsWith("https://")) return "Skipped $url: only https links work."
        val safe = safeHosts.any { host == it || host.endsWith(".$it") }
        if (!safe && !req.allowOtherSites) {
            step(BuildEvent.Kind.DOWNLOAD, "Blocked download from $host (turn on \"Allow other sites\")")
            return "Download from $host was blocked. Use a file from GitHub, Google Fonts or Wikimedia, or draw it in code."
        }
        step(BuildEvent.Kind.DOWNLOAD, "Downloading ${path.substringAfterLast('/')} from $host")
        val bytes = withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val len = r.body?.contentLength() ?: -1
                    if (len > MAX_DOWNLOAD) return@use null
                    r.body?.bytes()?.takeIf { it.size <= MAX_DOWNLOAD }
                }
            }.getOrNull()
        } ?: return "Download failed or was too large (max 15 MB): $url"
        pendingFiles[path] = bytes
        return null
    }

    /**
     * Puts files the model named loosely where they belong in a new app: "MainActivity.kt" or
     * "com/x/Foo.kt" go to the source folder, "src/main/…" gets "app/", "res/…" and the manifest go under app/src/main.
     */
    private fun normalizePath(raw: String): String {
        val p = raw.trim().trim('`', '*', '"', '\'', ' ').removePrefix("./").removePrefix("/")
        if (imported || p.startsWith("app/src/main/")) return p
        val src = "app/src/main/java/${packageName.replace('.', '/')}"
        return when {
            p.startsWith("src/main/") -> "app/$p"
            p.startsWith("main/") -> "app/src/$p"
            p == "AndroidManifest.xml" || p.endsWith("/AndroidManifest.xml") -> "app/src/main/AndroidManifest.xml"
            p.startsWith("res/") -> "app/src/main/$p"
            p.startsWith("java/") || p.startsWith("kotlin/") -> "app/src/main/java/" + p.substringAfter('/')
            p.endsWith(".kt") || p.endsWith(".java") -> {
                // Keep sub-packages under the app's package, e.g. "ui/Theme.kt" or "com/x/app/ui/Theme.kt".
                val inPkg = p.substringAfter(packageName.replace('.', '/') + "/", p)
                if ('/' in inPkg && !inPkg.startsWith("app/")) "$src/$inPkg" else "$src/${p.substringAfterLast('/')}"
            }
            else -> p
        }
    }

    // ---- Output parsing ----

    data class Parsed(
        val files: List<Pair<String, String>>,
        val deps: List<String>,
        val downloads: List<Pair<String, String>>,
        val truncated: Boolean,
    )

    companion object {
        private const val MAX_DOWNLOAD = 15L * 1024 * 1024

        /** Arin's own workflow and the tag prefix of the releases it publishes. */
        const val SELF_WORKFLOW = "build-apk.yml"
        /** Result message when the code is written and the owner must choose where to build ("CODE_READY:" + changed files). */
        const val CODE_READY = "CODE_READY:"
        const val SELF_TAG_PREFIX = "arin-"
        /** Owner name used for projects that live only on the phone (no GitHub). */
        const val LOCAL_OWNER = "phone"
        private const val MAX_WEB_STEPS = 3
        private val fileStart = Regex("^=+\\s*FILE:\\s*(.+?)\\s*=*\\s*$")
        private val fileEnd = Regex("^=+\\s*END FILE\\s*=*\\s*$")
        private val moreLine = Regex("(?m)^=+\\s*MORE\\s*=+\\s*$")
        private val aboutLine = Regex("^=+\\s*ABOUT:\\s*(.+?)\\s*=*\\s*$")

        private val codeish = Regex("""[{};=<>()\[\]]|\b(fun|val|var|import|class|return|@Composable)\b""")

        /** The model's thinking without the code in it, so progress reads as plain English. */
        fun proseOnly(text: String): String {
            val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("```") }
            val prose = lines.filter { l -> codeish.findAll(l).count() * 12 < l.length.coerceAtLeast(1) && l.count { it.isLetter() } > l.length / 2 }
            return prose.joinToString(" ").ifBlank { "working out the code" }
        }

        /**
         * The line that says what is actually wrong. Gradle's headers ("* What went wrong:", "Execution failed for task",
         * "FAILURE: Build failed with an exception.") are skipped for the line that gives the cause.
         */
        fun realErrorLine(errors: String): String {
            val lines = errors.lines().map { it.trim() }.filter { it.isNotBlank() }
            lines.firstOrNull { it.startsWith("e: ") }?.let { return it }
            val header = Regex("""(?i)^(\* what went wrong:?|failure: build failed.*|execution failed for task.*|> ?task .*failed|\* try:.*|build failed.*)$""")
            val i = lines.indexOfFirst { it.startsWith("* What went wrong", true) }
            if (i >= 0) lines.drop(i + 1).map { it.removePrefix(">").trim() }.firstOrNull { it.isNotBlank() && !header.matches(it) && !header.matches("> $it") }?.let { return it }
            lines.firstOrNull { it.contains("error:", true) && !header.matches(it) }?.let { return it }
            lines.firstOrNull { it.startsWith(">") && !header.matches(it) }?.let { return it.removePrefix(">").trim() }
            return lines.firstOrNull { !header.matches(it) } ?: lines.firstOrNull().orEmpty()
        }

        /** The main build error, said simply. */
        fun plainError(errors: String): String {
            val e = realErrorLine(errors)
            val where = Regex("""([A-Za-z0-9_]+\.(kt|java|xml))""").find(e)?.groupValues?.get(1)?.let { " in $it" } ?: ""
            val name = Regex("""Unresolved reference:?\s*'?([A-Za-z0-9_]+)""").find(e)?.groupValues?.get(1)
            return when {
                name != null -> "the code uses \"$name\"$where, which doesn't exist or isn't imported"
                e.contains("Type mismatch", true) || e.contains("Argument type mismatch", true) -> "a value has the wrong type$where"
                e.contains("No value passed for parameter", true) -> "something is called without all the information it needs$where"
                e.contains("ksp", true) -> "the code generator (KSP) doesn't fit the Kotlin or library versions"
                e.contains("Plugin", true) && e.contains("not found", true) -> "a build plugin couldn't be found (wrong name or version)"
                e.contains("Could not resolve", true) || e.contains("Could not find", true) -> "a library couldn't be downloaded (wrong name or version)"
                e.contains("@Composable", true) -> "screen code is called from a place that can't draw screens$where"
                e.contains("resource", true) || e.contains("AAPT", true) -> "a picture, color, text or other resource is missing or broken$where"
                e.contains("Manifest merger", true) -> "the app's Android settings (manifest) conflict"
                e.contains("Expecting", true) || e.contains("Syntax", true) -> "the code has a typing mistake (like a missing bracket)$where"
                e.contains("Conflicting", true) || e.contains("Redeclaration", true) -> "two things have the same name$where"
                e.contains("Overload resolution ambiguity", true) -> "a call matches more than one function (often a missing import, a wrong argument or a copy of a function)$where"
                e.contains("Function invocation", true) -> "a function is used without its brackets ()$where"
                e.contains("nullable receiver", true) -> "a value that can be empty is used without a check$where"
                e.contains("needs opt-in", true) || e.contains("is experimental", true) -> "an experimental feature is used without permission$where"
                e.contains("OutOfMemory", true) || e.contains("heap space", true) -> "the build ran out of memory"
                else -> "\"" + e.take(90) + "\"" + where
            }
        }

        /** A plain-English guess at what a file is for, when the model didn't say. */
        fun plainEnglish(path: String): String {
            val file = path.substringAfterLast('/')
            val name = file.substringBeforeLast('.')
            val words = name.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').lowercase()
            return when {
                file == "MainActivity.kt" || file == "MainActivity.java" -> "The app's starting point: opens the first screen when you tap the icon"
                file == "AndroidManifest.xml" -> "The app's settings for Android: its name, icon, screens and permissions"
                file.startsWith("build.gradle") || file.startsWith("settings.gradle") || file == "libs.versions.toml" -> "The build setup: which tools and ready-made libraries the app uses"
                file == "strings.xml" -> "The words and labels shown in the app"
                file == "colors.xml" || file == "themes.xml" || name in setOf("Color", "Theme", "Type") -> "The app's look: colors, fonts and style"
                name.endsWith("Screen") -> "The ${words.removeSuffix(" screen")} screen: what you see and tap there"
                name.endsWith("ViewModel") -> "The brain behind the ${words.removeSuffix(" view model")} screen: keeps its data and reacts to your taps"
                name.endsWith("Dao") || name.endsWith("Database") || name.endsWith("Repository") || name.endsWith("Store") -> "Saving and loading the app's data on the phone"
                name.endsWith("Api") || name.endsWith("Client") || name.endsWith("Service") -> "Talking to the internet or running work in the background (${words})"
                path.contains("/model/") || path.contains("/data/") -> "The kind of information the app keeps: $words"
                path.contains("/res/drawable") || path.contains("/res/mipmap") -> "A picture or icon used in the app"
                path.contains("/res/raw") || path.contains("/assets/") -> "A media or data file the app uses ($file)"
                file.endsWith(".kt") || file.endsWith(".java") -> "Code for the $words part of the app"
                else -> "Supporting file: $file"
            }
        }
        private val depLine = Regex("^=+\\s*DEPENDENCY:\\s*(.+?)\\s*=*\\s*$")
        private val downloadLine = Regex("^=+\\s*DOWNLOAD:\\s*(\\S+)\\s*->\\s*(\\S+?)\\s*=*\\s*$")

        fun parse(text: String): Parsed {
            val files = mutableListOf<Pair<String, String>>()
            val deps = mutableListOf<String>()
            val downloads = mutableListOf<Pair<String, String>>()
            var path: String? = null
            val body = StringBuilder()
            for (raw in text.lines()) {
                val line = raw.trim()
                val current = path
                if (current == null) {
                    val start = fileStart.find(line)
                    val dep = depLine.find(line)
                    val dl = downloadLine.find(line)
                    when {
                        start != null -> {
                            path = start.groupValues[1].trim('`', ' ')
                            body.clear()
                        }
                        dep != null -> deps.add(dep.groupValues[1].trim('`', ' '))
                        dl != null -> downloads.add(dl.groupValues[1] to dl.groupValues[2])
                    }
                } else if (fileEnd.matches(line)) {
                    files.add(current to stripFences(body.toString()))
                    path = null
                } else if (fileStart.find(line) != null) {
                    // A new "=== FILE:" without "=== END FILE ===" before it: the previous file ends here (otherwise the
                    // next file was glued into it — "Expecting a top level declaration").
                    files.add(current to stripFences(body.toString()))
                    path = fileStart.find(line)!!.groupValues[1].trim('`', ' ')
                    body.clear()
                } else if (aboutLine.matches(line) || depLine.find(line) != null && body.isBlank()) {
                    // "=== ABOUT: …" (and a dependency line) is for Arin, never part of the file: it used to end up on the
                    // file's first line and break the whole file.
                    depLine.find(line)?.let { deps.add(it.groupValues[1].trim('`', ' ')) }
                } else {
                    body.appendLine(raw)
                }
            }
            if (files.isEmpty()) {
                // No "=== FILE:" blocks: many models write Markdown instead. Read those, so nothing is lost.
                val md = parseMarkdown(text)
                if (md.isNotEmpty()) return Parsed(md, deps, downloads, truncated = false)
            }
            return Parsed(files, deps, downloads, truncated = path != null)
        }

        private val fence = Regex("^\\s*(`{3,}|~{3,})\\s*([A-Za-z0-9_+#.-]*)\\s*(.*)$")
        private val pathLike = Regex("""([A-Za-z0-9_./-]+\.(?:kt|kts|java|xml|gradle|json|txt|properties|toml|pro|md|html|css|js))\b""")

        /**
         * Files written as Markdown code blocks, with the file's path in a heading, bold text, "File:" line or
         * backticks just above the block, in the opening fence (```kotlin title="path" or ```kotlin:path), or as a
         * comment on the block's first line (// path, <!-- path -->, # path).
         */
        fun parseMarkdown(text: String): List<Pair<String, String>> {
            val lines = text.lines()
            val out = linkedMapOf<String, String>()
            var i = 0
            while (i < lines.size) {
                val open = fence.find(lines[i])
                if (open == null) { i++; continue }
                val marker = open.groupValues[1]
                // Collect the block.
                var j = i + 1
                val body = mutableListOf<String>()
                while (j < lines.size && !lines[j].trim().startsWith(marker.take(3))) { body += lines[j]; j++ }
                // Where's the path? Opening fence, then the 3 lines above, then the first line of the block.
                var path = pathLike.find(open.groupValues[3])?.groupValues?.get(1)
                    ?: pathLike.find(open.groupValues[2].substringAfter(':', ""))?.groupValues?.get(1)
                if (path == null) {
                    for (k in (i - 1) downTo maxOf(0, i - 3)) {
                        val above = lines[k].trim()
                        if (above.isEmpty()) continue
                        if (fence.matches(above)) break
                        path = pathLike.findAll(above).map { it.groupValues[1] }.lastOrNull { '/' in it || '.' in it }
                        if (path != null) break
                    }
                }
                if (path == null && body.isNotEmpty()) {
                    val first = body.first().trim()
                    if (first.startsWith("//") || first.startsWith("<!--") || first.startsWith("#")) {
                        path = pathLike.find(first)?.groupValues?.get(1)
                        if (path != null) body.removeAt(0)
                    }
                }
                val content = body.joinToString("\n").trimEnd()
                if (path != null && content.isNotBlank() && !path.startsWith("http")) out[path] = content + "\n"
                i = j + 1
            }
            return out.toList()
        }

        /** Small models often wrap code in ``` fences inside the file block. */
        private fun stripFences(s: String): String {
            val lines = s.trimEnd().lines().toMutableList()
            while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
            if (lines.firstOrNull()?.trim()?.startsWith("```") == true) lines.removeAt(0)
            if (lines.lastOrNull()?.trim() == "```") lines.removeAt(lines.lastIndex)
            return lines.joinToString("\n") + "\n"
        }
    }
}
