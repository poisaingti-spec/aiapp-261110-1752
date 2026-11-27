package com.arin.builder

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/** A saved Build Mode project: the app, its whole agent conversation, and when it was last used. */
@Serializable
data class SavedProject(
    val id: String,
    val updated: Long = System.currentTimeMillis(),
    val project: Project? = null,
    val timeline: List<TimelineItem> = emptyList(),
) {
    val title: String get() = project?.appName
        ?: (timeline.firstOrNull { it is TimelineItem.User } as? TimelineItem.User)?.text?.take(50)
        ?: "Untitled"
    val lastResult: TimelineItem.Result? get() = timeline.lastOrNull { it is TimelineItem.Result } as? TimelineItem.Result
}

/** The single-project save format used before projects history. */
@Serializable
private data class OldSaved(val project: Project? = null, val timeline: List<TimelineItem> = emptyList())

/**
 * One build project's live state. Several can run at the same time (a chat build and a Build-tab build, or builds
 * from two chats); each has its own timeline, progress and running flag.
 */
class BuildSession(val id: String) {
    val timeline = mutableStateListOf<TimelineItem>()
    var project by mutableStateOf<Project?>(null)
    var running by mutableStateOf(false)
    var progress by mutableStateOf<Float?>(null)
    var progressText by mutableStateOf("")
    /** The request the service should run next for this session. */
    @Volatile internal var pending: BuildRequest? = null
    /** The last request started here (so a written build can continue where the owner chooses). */
    @Volatile var lastRequest: BuildRequest? = null

    /** Directions the person sent while the build runs; the agent reads them at its next step. */
    val steering = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** Steers the running build: shown in its steps, followed at the agent's next step. */
    fun steer(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        steering += t
        timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.STEP, "↪ Your direction — following it now: $t"))
    }
}

/**
 * Shared Build Mode state: the open project's agent timeline. Every project is saved, so you can
 * come back to any of them later and keep asking for changes (like chats).
 */
object BuildRunner {
    /** Called on the main thread when a build ends (with its session id), so the chat can post the result and its links. */
    var onFinished: ((BuildResult, Project?, String) -> Unit)? = null

    /** Every build session in use (running ones, and the one open in the Build tab). */
    val sessions = androidx.compose.runtime.mutableStateMapOf<String, BuildSession>()

    /** The session the Build tab shows. */
    var current by mutableStateOf(BuildSession(UUID.randomUUID().toString()).also { sessions[it.id] = it })
        private set

    fun session(id: String?): BuildSession? = id?.let { sessions[it] }

    /** True if any build is running. */
    val anyRunning: Boolean get() = sessions.values.any { it.running }

    /** How many builds are running now. */
    val runningCount: Int get() = sessions.values.count { it.running }

    /** A saved project as a session (already open, or loaded from disk), without switching the Build tab to it. */
    fun sessionFor(context: Context, id: String): BuildSession? {
        sessions[id]?.let { return it }
        val saved = runCatching { json.decodeFromString<SavedProject>(File(dir(context), "$id.json").readText()) }.getOrNull() ?: return null
        return BuildSession(saved.id).also { s ->
            s.project = saved.project
            s.timeline.addAll(saved.timeline)
            sessions[s.id] = s
        }
    }

    /** A brand-new session, without switching the Build tab to it. */
    fun createSession(): BuildSession = BuildSession(UUID.randomUUID().toString()).also { sessions[it.id] = it }

    /** Download link for the source as a zip: the exact commit that was built, else the branch. */
    fun sourceZipUrl(p: Project): String =
        if (!p.lastSha.isNullOrBlank()) "https://github.com/${p.owner}/${p.repo}/archive/${p.lastSha}.zip"
        else "https://github.com/${p.owner}/${p.repo}/archive/refs/heads/${p.branch}.zip"

    /** The repo page on GitHub. */
    fun repoPage(p: Project): String = "https://github.com/${p.owner}/${p.repo}" + if (p.selfUpdate) "/tree/${p.branch}" else ""

    // The Build tab's session, under the names the rest of the app already uses.
    val timeline: androidx.compose.runtime.snapshots.SnapshotStateList<TimelineItem> get() = current.timeline
    val running: Boolean get() = current.running
    val project: Project? get() = current.project
    val projectId: String get() = current.id
    /** Live progress of the current long step (upload/unpack): 0..1, or null when there's none. */
    val progress: Float? get() = current.progress
    val progressText: String get() = current.progressText

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "build_projects").apply { mkdirs() }
    private fun prefs(context: Context) = context.getSharedPreferences("build_mode", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        // One-time move from the single-project file used before.
        val old = File(context.filesDir, "build_mode.json")
        if (old.exists()) {
            runCatching {
                val o = json.decodeFromString<OldSaved>(old.readText())
                if (o.timeline.isNotEmpty()) {
                    val id = UUID.randomUUID().toString()
                    File(dir(context), "$id.json").writeText(json.encodeToString(SavedProject(id, old.lastModified(), o.project, o.timeline)))
                    prefs(context).edit().putString("current", id).apply()
                }
            }
            old.delete()
        }
        prefs(context).getString("current", null)?.let { open(context, it) }
    }

    private val io = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO +
            com.arin.CrashLog.handler { runCatching { com.arin.ArinApp.instance }.getOrNull() },
    )

    /** Saves soon (batched, off the main thread) so frequent progress updates don't make the app stutter. */
    fun save(context: Context, s: BuildSession = current) {
        if (pendingSaves[s.id]?.isActive == true) return
        val app = context.applicationContext
        pendingSaves[s.id] = io.launch {
            kotlinx.coroutines.delay(1_500)
            writeNow(app, s)
        }
    }

    private val pendingSaves = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()

    /** Saves right away (end of a build). */
    fun saveNow(context: Context, s: BuildSession = current) {
        pendingSaves[s.id]?.cancel()
        val app = context.applicationContext
        val snapshot = SavedProject(s.id, System.currentTimeMillis(), s.project, s.timeline.toList())
        io.launch { write(app, snapshot) }
    }

    private suspend fun writeNow(context: Context, s: BuildSession) {
        val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            SavedProject(s.id, System.currentTimeMillis(), s.project, s.timeline.toList())
        }
        write(context, snapshot)
    }

    /** One save at a time, so two never write the same file at once. */
    private val writeLock = Any()

    private fun write(context: Context, s: SavedProject) {
        if (s.timeline.isEmpty() && s.project == null) return
        // Keep saved builds a sensible size: very long event texts (logs) are cut.
        val small = s.copy(timeline = s.timeline.takeLast(1_500).map { t ->
            if (t is TimelineItem.Event && t.event.text.length > 4_000) TimelineItem.Event(t.event.copy(text = t.event.text.take(4_000))) else t
        })
        synchronized(writeLock) { writeFile(context, small) }
    }

    private fun writeFile(context: Context, s: SavedProject) {
        runCatching {
            val f = File(dir(context), "${s.id}.json")
            val tmp = File(dir(context), "${s.id}.tmp")
            tmp.writeText(json.encodeToString(s))
            tmp.renameTo(f)
            if (s.id == current.id) prefs(context).edit().putString("current", s.id).apply()
        }
    }

    /** All saved projects, most recently used first. */
    fun list(context: Context): List<SavedProject> = dir(context).listFiles { f -> f.name.endsWith(".json") }.orEmpty()
        .mapNotNull { f -> runCatching { json.decodeFromString<SavedProject>(f.readText()) }.getOrNull() }
        .sortedByDescending { it.updated }

    /** Opens a saved project to keep working on it. */
    fun open(context: Context, id: String): Boolean {
        val s = sessionFor(context, id) ?: return false
        if (current.id != s.id && !current.running) { saveNow(context, current); dropIfIdle(current) }
        current = s
        prefs(context).edit().putString("current", id).apply()
        return true
    }

    /** Forgets an idle session's in-memory copy (it's saved on disk) so sessions don't pile up. */
    private fun dropIfIdle(s: BuildSession) {
        if (!s.running && s.pending == null && s.id != current.id) sessions.remove(s.id)
    }

    fun delete(context: Context, id: String) {
        if (sessions[id]?.running == true) return
        File(dir(context), "$id.json").delete()
        if (id == projectId) newProject(context)
        sessions.remove(id)
    }

    /** Starts a fresh project; the current one stays saved in Projects (and its repo stays on GitHub). */
    fun newProject(context: Context) {
        val old = current
        if (old.timeline.isNotEmpty() || old.project != null) saveNow(context, old)
        current = createSession()
        if (!old.running) sessions.remove(old.id)
        prefs(context).edit().remove("current").apply()
    }
}
