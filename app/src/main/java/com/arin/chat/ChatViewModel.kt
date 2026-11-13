package com.arin.chat

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arin.ArinApp
import com.arin.app.ReplyService
import com.arin.data.Attachment
import com.arin.data.Attachments
import com.arin.data.Chat
import com.arin.data.ChatStore
import com.arin.data.ChatSummary
import com.arin.data.Memory
import com.arin.data.MemoryStore
import com.arin.data.Message
import com.arin.data.Project
import com.arin.data.ProjectStore
import com.arin.data.Settings
import com.arin.data.Source
import com.arin.data.ThemeMode
import com.arin.llm.DefaultModels
import com.arin.llm.Delta
import com.arin.llm.Llm
import com.arin.llm.ModelOption
import com.arin.llm.Prompt
import com.arin.llm.Provider
import com.arin.llm.Turn
import com.arin.llm.WebSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the chat, the chats list, projects and Settings share. One instance for the whole app. */
/** Where a build runs. */
enum class BuildTarget { BUILD_TAB, CHAT, PHONE, AUTO }

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ChatStore(app)
    private val projectStore = ProjectStore(app)
    private val memoryStore = MemoryStore(app)
    val settings = Settings(app)

    // ---- Current chat ----
    var chatId by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("New chat")
        private set
    /** The project the current chat belongs to, or null. */
    var chatProject by mutableStateOf<Project?>(null)
        private set
    val messages = mutableStateListOf<Message>()

    /** Files waiting to go with the next message. */
    val pending = mutableStateListOf<Attachment>()

    /** Text from another app's Share button, waiting to be placed in the message box. */
    var sharedText by mutableStateOf<String?>(null)

    /** Index of the sent message being edited, or null. */
    var editing by mutableStateOf<Int?>(null)
        private set

    var streaming by mutableStateOf(false)
        private set
    var liveText by mutableStateOf("")
        private set
    var liveThinking by mutableStateOf("")
        private set
    /** What Arin is doing right now when it isn't writing, such as "Searching the web for …". */
    var status by mutableStateOf<String?>(null)
        private set
    var notice by mutableStateOf<String?>(null)

    /** Details of the last crash, shown once when Arin opens again. */
    var crashReport by mutableStateOf(com.arin.CrashLog.takeReport(app))

    fun dismissCrash() { crashReport = null }

    /** Set when a reply finishes; voice mode reads it aloud. */
    var lastFinished by mutableStateOf<Message?>(null)
        private set

    private var job: Job? = null

    // ---- Chats and projects ----
    val chats = mutableStateListOf<ChatSummary>()
    var query by mutableStateOf("")
        private set
    val projects = mutableStateListOf<Project>()
    val memories = mutableStateListOf<Memory>()

    // ---- Settings ----
    var provider by mutableStateOf(settings.provider)
        private set
    var models by mutableStateOf(DefaultModels.of(settings.provider))
        private set
    var model by mutableStateOf(settings.model(settings.provider) ?: models.first().id)
        private set
    var theme by mutableStateOf(settings.theme)
        private set
    var showThinking by mutableStateOf(settings.showThinking)
        private set
    var webSearch by mutableStateOf(settings.webSearch)
        private set
    var memoryOn by mutableStateOf(settings.memoryOn)
        private set

    /** Custom instructions (Settings): about the person, and how to answer. */
    var aboutYou by mutableStateOf(settings.aboutYou)
        private set
    var howToAnswer by mutableStateOf(settings.howToAnswer)
        private set
    var replyStyle by mutableStateOf(settings.replyStyle)
        private set

    fun saveCustomInstructions(about: String, how: String) {
        aboutYou = about.trim()
        howToAnswer = how.trim()
        settings.aboutYou = aboutYou
        settings.howToAnswer = howToAnswer
        notice = "Custom instructions saved"
    }

    fun chooseStyle(style: String) {
        replyStyle = style
        settings.replyStyle = style
    }

    /** Deep research: many searches and page reads, then a report with sources. */
    var deepResearch by mutableStateOf(false)
        private set

    fun toggleDeepResearch(on: Boolean) {
        deepResearch = on
        if (on) notice = "Deep research is on: Arin searches and reads many pages, then writes a report."
    }

    /** A temporary chat isn't saved, doesn't use memory and doesn't appear in the list. */
    var temporary by mutableStateOf(false)
        private set

    /** Whether the open chat is pinned to the top of the list. */
    private var chatPinned = false

    fun togglePin(id: String, pinned: Boolean) {
        if (id == chatId) chatPinned = pinned
        viewModelScope.launch(Dispatchers.IO) {
            store.setPinned(id, pinned)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    var terminalOn by mutableStateOf(settings.terminalOn)
        private set
    var terminalAsk by mutableStateOf(settings.terminalAsk)
        private set

    /** Commands run so far in the reply being written (shown live). */
    val liveCommands = mutableStateListOf<com.arin.data.CommandRun>()

    /** A command or tool call waiting for the person to allow or refuse it. */
    var commandToApprove by mutableStateOf<String?>(null)
        private set
    /** True when [commandToApprove] is an MCP tool call rather than a shell command. */
    var approvingTool by mutableStateOf(false)
        private set

    private fun shorten(text: String, max: Int = 20_000): String =
        if (text.length <= max) text
        else text.take(4_000) + "\n…(${text.length - max} characters skipped)…\n" + text.takeLast(max - 4_000)

    // ---- MCP servers ----

    var mcpServers by mutableStateOf(settings.mcpServers)
        private set

    /** Status line per server name, filled by [checkServer]. */
    val serverStatus = androidx.compose.runtime.mutableStateMapOf<String, String>()

    fun saveServer(server: com.arin.mcp.McpServer) {
        mcpServers = mcpServers.filterNot { it.name == server.name } + server
        settings.mcpServers = mcpServers
        com.arin.mcp.Mcp.forget(server.name)
    }

    fun removeServer(server: com.arin.mcp.McpServer) {
        mcpServers = mcpServers.filterNot { it.name == server.name }
        settings.mcpServers = mcpServers
        com.arin.mcp.Mcp.forget(server.name)
        serverStatus.remove(server.name)
    }

    fun toggleServer(server: com.arin.mcp.McpServer, on: Boolean) = saveServer(server.copy(enabled = on))

    /** Connects to [server] (starting it in Termux if needed) and records the result. */
    fun checkServer(server: com.arin.mcp.McpServer) {
        serverStatus[server.name] = "Connecting…"
        viewModelScope.launch {
            com.arin.mcp.Mcp.forget(server.name)
            val problems = com.arin.mcp.Mcp.connectAll(getApplication(), listOf(server.copy(enabled = true)), canStart = terminalOn)
            serverStatus[server.name] = com.arin.mcp.Mcp.status(server.name) ?: problems.firstOrNull() ?: "Not reachable"
        }
    }

    /**
     * Adds REA, turns on the terminal, and starts a chat where the model installs and checks it,
     * the way the setup is done with Claude Code.
     */
    // ---- Built-in Linux ----

    var linuxInstalled by mutableStateOf(com.arin.terminal.Linux.installed(app))
        private set
    var linuxReady by mutableStateOf(com.arin.terminal.Linux.nodeReady(app))
        private set
    /** True while installing Linux or REA. */
    var linuxBusy by mutableStateOf(false)
        private set
    /** Recent progress lines from installing. */
    val linuxLog = mutableStateListOf<String>()

    private fun logLine(line: String) {
        ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            linuxLog += line
            while (linuxLog.size > 40) linuxLog.removeAt(0)
        }
    }

    /** Installs Ubuntu inside Arin (and REA too, with [withRea]). Keeps going if the screen closes. */
    fun installLinux(withRea: Boolean = false) {
        if (linuxBusy) return
        val app = getApplication<Application>()
        linuxBusy = true
        linuxLog.clear()
        ReplyService.start(app)
        ArinApp.scope.launch {
            try {
                com.arin.terminal.Linux.install(app) { logLine(it) }
                linuxInstalled = true
                linuxReady = true
                toggleTerminal(true)
                if (withRea) installReaInLinux(app)
                notice = if (withRea) "Linux and REA are installed" else "Linux is installed"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logLine("Failed: ${e.message}")
                notice = "Linux install failed: ${e.message}"
            } finally {
                linuxInstalled = com.arin.terminal.Linux.installed(app)
                linuxReady = com.arin.terminal.Linux.nodeReady(app)
                linuxBusy = false
                ReplyService.stop(app)
            }
        }
    }

    /** Stops the old REA bridge, reinstalls REA with the PRoot fixes, and reconnects. */
    fun repairRea() {
        if (linuxBusy) return
        val app = getApplication<Application>()
        linuxBusy = true
        linuxLog.clear()
        ReplyService.start(app)
        ArinApp.scope.launch {
            try {
                installReaInLinux(app)
                notice = "REA repaired"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logLine("Failed: ${e.message}")
                notice = "REA repair failed: ${e.message}"
            } finally {
                linuxBusy = false
                ReplyService.stop(app)
            }
        }
    }

    private suspend fun installReaInLinux(app: Application) {
        logLine("Stopping any old REA bridge…")
        com.arin.mcp.Mcp.forget(com.arin.mcp.Mcp.REA.name)
        com.arin.terminal.Linux.run(app, "pkill -f mcp-bridge.mjs; sleep 1; echo stopped", timeoutMs = 30_000)
        val r = com.arin.terminal.Linux.installRea(app) { logLine(it) }
        if (r.exitCode != 0 || r.error != null) throw IllegalStateException("REA didn't install: " + (r.error ?: r.combined().takeLast(400)))
        // For APKs: JDK, JADX and apktool (REA's Android tools). Keep going if this part fails.
        val tools = com.arin.terminal.Linux.installAndroidTools(app) { logLine(it) }
        if (tools.exitCode != 0 || tools.error != null) logLine("Android tools didn't install fully; APK analysis may not work: " + (tools.error ?: tools.combined().takeLast(200)))
        logLine("Installing Arin's MCP bridge…")
        com.arin.mcp.Mcp.installBridge(app)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            saveServer(com.arin.mcp.Mcp.REA.copy(enabled = true))
        }
        logLine("Starting REA and connecting…")
        val problems = com.arin.mcp.Mcp.connectAll(app, listOf(com.arin.mcp.Mcp.REA), canStart = true)
        val status = com.arin.mcp.Mcp.status(com.arin.mcp.Mcp.REA.name)
        serverStatus[com.arin.mcp.Mcp.REA.name] = status ?: problems.firstOrNull() ?: "Not reachable"
        logLine(status?.let { "REA is connected: $it" } ?: ("REA didn't connect: " + problems.joinToString("; ")))
    }

    var devToolsReady by mutableStateOf(com.arin.terminal.Linux.devToolsReady(app))
        private set

    /** Whether apps can be compiled to APKs in the built-in Linux. */
    var androidBuildReady by mutableStateOf(com.arin.terminal.Linux.androidBuildReady(app))
        private set

    /** Installs Java, Kotlin, Gradle and the Android SDK in the built-in Linux. */
    fun installDevTools() {
        if (linuxBusy) return
        val app = getApplication<Application>()
        linuxBusy = true
        linuxLog.clear()
        ReplyService.start(app)
        ArinApp.scope.launch {
            try {
                if (!com.arin.terminal.Linux.nodeReady(app)) com.arin.terminal.Linux.install(app) { logLine(it) }
                linuxInstalled = true
                linuxReady = true
                val r = com.arin.terminal.Linux.installDevTools(app) { logLine(it) }
                if (r.exitCode != 0 || r.error != null) throw IllegalStateException(r.error ?: r.combined().takeLast(400))
                devToolsReady = true
                // The Android SDK too, so apps compile to APKs right on the phone.
                val a = com.arin.terminal.Linux.installAndroidBuild(app) { logLine(it) }
                androidBuildReady = com.arin.terminal.Linux.androidBuildReady(app)
                toggleTerminal(true)
                notice = if (androidBuildReady) "Java, Kotlin, Gradle and the Android SDK are installed: apps now build on this phone too"
                    else "Java, Kotlin and Gradle are installed, but the Android SDK part didn't finish: " +
                        (a.error ?: a.combined().lines().lastOrNull { it.isNotBlank() } ?: "") + " Apps still build on GitHub."
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                logLine("Failed: ${e.message}")
                notice = "Developer tools install failed: ${e.message}"
            } finally {
                linuxBusy = false
                ReplyService.stop(app)
            }
        }
    }

    fun removeLinux() {
        if (linuxBusy) return
        val app = getApplication<Application>()
        linuxBusy = true
        ArinApp.scope.launch {
            com.arin.mcp.Mcp.forget(com.arin.mcp.Mcp.REA.name)
            com.arin.terminal.Linux.remove(app)
            linuxInstalled = false
            linuxReady = false
            devToolsReady = false
            androidBuildReady = false
            linuxBusy = false
            linuxLog.clear()
            notice = "Linux removed"
        }
    }

    fun setUpRea() {
        toggleTerminal(true)
        if (mcpServers.none { it.name == com.arin.mcp.Mcp.REA.name }) saveServer(com.arin.mcp.Mcp.REA)
        val app = getApplication<Application>()
        if (com.arin.terminal.Linux.installed(app) || com.arin.terminal.Termux.problem(app) != null) {
            // Built-in Linux: install everything without the model, then let the model check it works.
            if (!com.arin.terminal.Linux.supported()) {
                notice = "This phone's processor isn't supported by Arin's built-in Linux. Set up Termux instead."
                return
            }
            if (linuxBusy) return
            linuxBusy = true
            linuxLog.clear()
            ReplyService.start(app)
            ArinApp.scope.launch {
                try {
                    if (!com.arin.terminal.Linux.nodeReady(app)) com.arin.terminal.Linux.install(app) { logLine(it) }
                    linuxInstalled = true
                    linuxReady = true
                    installReaInLinux(app)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        newChat()
                        send(REA_CHECK_PROMPT)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logLine("Failed: ${e.message}")
                    notice = "REA setup failed: ${e.message}"
                } finally {
                    linuxBusy = false
                    ReplyService.stop(app)
                }
            }
            return
        }
        newChat()
        viewModelScope.launch {
            // Put Arin's bridge in Termux first, so the model can start and test REA through it.
            if (com.arin.terminal.Shell.problem(app) == null) com.arin.mcp.Mcp.installBridge(app)
            send(REA_SETUP_PROMPT)
        }
    }
    private var approval: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    fun answerCommand(allow: Boolean, always: Boolean = false) {
        if (always) toggleTerminalAsk(false)
        approval?.complete(allow)
    }

    private suspend fun askToRun(command: String, tool: Boolean): Boolean {
        if (!terminalAsk) return true
        val d = kotlinx.coroutines.CompletableDeferred<Boolean>()
        approval = d
        approvingTool = tool
        commandToApprove = command
        try {
            return d.await()
        } finally {
            commandToApprove = null
            approval = null
        }
    }

    fun toggleTerminal(on: Boolean) {
        terminalOn = on
        settings.terminalOn = on
    }

    fun toggleTerminalAsk(on: Boolean) {
        terminalAsk = on
        settings.terminalAsk = on
    }

    /** Runs a quick check in Termux; returns what it printed or the problem. */
    suspend fun testTerminal(): String {
        val r = com.arin.terminal.Shell.run(
            getApplication(),
            "echo \"Termux is connected\"; uname -m; (node -v 2>/dev/null && npm -v 2>/dev/null) || echo 'Node not installed yet'; python --version 2>/dev/null || echo 'Python not installed yet'",
            timeoutMs = 30_000,
        )
        return r.combined()
    }
    var notifyOnReply by mutableStateOf(settings.notifyOnReply)
        private set

    init {
        refreshChats()
        refreshProjects()
        memories.addAll(memoryStore.list())
        loadModels()
    }

    // ---------------- Sending ----------------

    // ---- Build mode (apps compiled to APKs on GitHub) ----

    init {
        // When a build ends, post the result in this chat with real download links.
        com.arin.builder.BuildRunner.onFinished = { result, project -> postBuildResult(result, project) }
    }

    // ---------------- Builds started from chat stay in chat ----------------

    /** The chat a build was started from (null: none, or it was started in the Build tab). */
    private var chatBuildChat: String? = null
    /** The Build-mode project that chat build works on. */
    private var chatBuildProject: String? = null
    /** The live progress message in that chat. */
    private var chatBuildMsg: String? = null
    private var chatBuildJob: Job? = null

    private val retryWords = Regex("""\b(try again|retry|again|continue|resume|fix( it| the)?( build| error)?s?|keep going|carry on)\b""", RegexOption.IGNORE_CASE)

    /** Shows the build's steps live in this chat, like Build mode does. */
    private fun followBuildInChat(title: String) {
        if (chatId == null) persist()
        chatBuildChat = chatId
        chatBuildProject = com.arin.builder.BuildRunner.projectId
        val msg = Message(fromUser = false, text = "🛠️ **$title…**\n\nStarting.")
        chatBuildMsg = msg.id
        messages += msg
        persist()
        chatBuildJob?.cancel()
        chatBuildJob = viewModelScope.launch {
            var last = ""
            while (true) {
                val text = buildProgressText(title, done = false)
                if (text != last) {
                    last = text
                    replaceBuildMessage(text, save = false)
                }
                if (!com.arin.builder.BuildRunner.running && com.arin.builder.BuildRunner.pending == null) {
                    kotlinx.coroutines.delay(1_500)
                    if (!com.arin.builder.BuildRunner.running) break
                }
                kotlinx.coroutines.delay(700)
            }
        }
    }

    private fun buildProgressText(title: String, done: Boolean): String = buildString {
        append(if (done) "🛠️ **$title — steps**\n\n" else "🛠️ **$title…**\n\n")
        val events = com.arin.builder.BuildRunner.timeline.toList()
            .filterIsInstance<com.arin.builder.TimelineItem.Event>().map { it.event }
            .filter { it.kind != com.arin.builder.BuildEvent.Kind.THINKING }
        val shown = events.takeLast(if (done) 25 else 14)
        if (events.size > shown.size) append("_…${events.size - shown.size} earlier steps_\n")
        shown.forEach { e ->
            val icon = when (e.kind) {
                com.arin.builder.BuildEvent.Kind.ERROR -> "⚠️"
                com.arin.builder.BuildEvent.Kind.DONE -> "✅"
                com.arin.builder.BuildEvent.Kind.BUILD -> "⚙️"
                com.arin.builder.BuildEvent.Kind.FILE -> "📄"
                com.arin.builder.BuildEvent.Kind.WEB -> "🌐"
                com.arin.builder.BuildEvent.Kind.DOWNLOAD, com.arin.builder.BuildEvent.Kind.LIBRARY -> "📦"
                else -> "•"
            }
            val line = e.text.lines().first().take(240)
            append("- $icon ").append(line).append('\n')
        }
        if (!done) {
            val p = com.arin.builder.BuildRunner.progressText
            if (p.isNotBlank()) append("\n_${p.take(120)}_\n")
            append("\nYou can keep chatting; I'll post the APK and source links here when it's done.")
        }
    }

    /** Puts [text] in the chat build's progress message, in whichever chat it lives. */
    private fun replaceBuildMessage(text: String, save: Boolean) {
        val id = chatBuildMsg ?: return
        val target = chatBuildChat
        if (target == chatId) {
            val i = messages.indexOfFirst { it.id == id }
            if (i >= 0) messages[i] = messages[i].copy(text = text)
            if (save) persist()
        } else if (save && target != null) {
            ArinApp.scope.launch(Dispatchers.IO) {
                store.load(target)?.let { c -> store.save(c.copy(messages = c.messages.map { if (it.id == id) it.copy(text = text) else it })) }
            }
        }
    }

    /** Adds [msg] to the chat the build came from, even if another chat is open now. */
    private fun postToBuildChat(msg: Message, progressId: String?, steps: String) {
        val target = chatBuildChat
        if (target == null || target == chatId) {
            messages += msg
            persist()
        } else {
            ArinApp.scope.launch(Dispatchers.IO) {
                store.load(target)?.let { c ->
                    val updated = c.messages.map { if (it.id == progressId) it.copy(text = steps) else it } + msg
                    store.save(c.copy(messages = updated, updated = System.currentTimeMillis()))
                }
                withContext(Dispatchers.Main) { refreshChats() }
            }
        }
    }

    private fun postBuildResult(result: com.arin.builder.BuildResult, project: com.arin.builder.Project?) {
        // Started in the Build tab: the result stays there.
        if (chatBuildMsg == null || chatBuildProject != com.arin.builder.BuildRunner.projectId) return
        chatBuildJob?.cancel()
        val title = when {
            project?.selfUpdate == true -> "Updating Arin"
            else -> "Building ${project?.appName ?: "the app"}"
        }
        val steps = buildProgressText(title, done = true)
        val progressId = chatBuildMsg
        if (chatBuildChat == chatId) replaceBuildMessage(steps, save = false)
        chatBuildMsg = null
        val name = project?.appName ?: "The app"
        val phoneOnly = project?.owner == com.arin.builder.BuildAgent.LOCAL_OWNER
        val text = if (result.success && phoneOnly) buildString {
            append("✅ **$name is built on this phone.**\n\n")
            result.phoneApk?.let { append("- **APK:** `$it`\n") }
            result.phoneSourceZip?.let { append("- **Source code (zip):** `$it`\n") }
            append("\nAndroid's install screen opens now. Both files are in your Download/Arin folder.")
        } else if (result.success && project != null) buildString {
            append(if (project.selfUpdate) "✅ **The new version of Arin is built.**\n\n" else "✅ **$name is built.**\n\n")
            result.apkUrl?.let { append("- **APK:** [Download $name APK]($it)\n  $it\n") }
            val zip = com.arin.builder.BuildRunner.sourceZipUrl(project)
            append("- **Source code (zip):** [Download source zip]($zip)\n  $zip\n")
            append("- **GitHub repository:** [Open on GitHub](${com.arin.builder.BuildRunner.repoPage(project)})\n")
            result.phoneApk?.let { append("- **Also compiled on this phone:** `$it`\n") }
            append(if (project.selfUpdate) "\nTap the APK link (or Install in Build mode) to update Arin." else "\nTap the APK link to download and install it.")
        } else {
            buildString {
                append("⚠️ **The build of $name failed.**\n\n**Why it failed:** ${result.message}\n")
                if (result.log.isNotBlank()) {
                    val lines = result.log.lines().filter { it.isNotBlank() }
                    append("\n**Failure log**")
                    if (lines.size > 60) append(" (first 60 of ${lines.size} lines)")
                    append(":\n```\n")
                    append(lines.take(60).joinToString("\n") { it.take(300) }.replace("```", "` ` `"))
                    append("\n```\n")
                }
                result.runUrl?.let { append("\n- **Full build log:** [Open the run on GitHub]($it)\n") }
                result.repoUrl?.let { append("- **GitHub repository:** [Open on GitHub]($it)\n") }
                append("\nSay \"try again\" to keep fixing it, or describe what to change.")
            }
        }
        lastFailure = if (result.success) "" else
            "The last Build mode run ($name) FAILED. Reason: ${result.message}" +
                (if (result.log.isNotBlank()) " Error log:\n${result.log.take(4_000)}\n" else "") +
                (result.runUrl?.let { " Full log: $it ." } ?: "") +
                " When the person asks why it failed, explain from this log in plain words; to fix it, emit a <build> tag that says what to change.\n\n"
        postToBuildChat(Message(fromUser = false, text = text), progressId, steps)
        if (result.success) result.phoneInstallPath?.let { path ->
            runCatching { com.arin.builder.ApkInstaller.install(getApplication(), java.io.File(path)) }
        }
    }

    /** Details of the last failed build, for the model to explain or fix. */
    private var lastFailure = ""

    /** The latest build's links, for the model to give when asked. */
    private fun latestBuildInfo(): String {
        if (com.arin.builder.BuildRunner.running) {
            val step = com.arin.builder.BuildRunner.timeline.lastOrNull { it is com.arin.builder.TimelineItem.Event }
                as? com.arin.builder.TimelineItem.Event
            return "A build of ${com.arin.builder.BuildRunner.project?.appName ?: "an app"} is running now in this chat" +
                (step?.let { " (current step: ${it.event.text.take(150)})" } ?: "") +
                ". Its steps show in the chat and the links will be posted when it's done. Don't start another <build> " +
                "until it finishes; answer other questions normally.\n\n"
        }
        if (lastFailure.isNotEmpty()) return lastFailure
        val p = com.arin.builder.BuildRunner.project ?: return ""
        val apk = p.lastApkUrl ?: return ""
        return "Latest app built in Build mode: ${p.appName}" + (if (p.selfUpdate) " (Arin itself)" else "") +
            ". APK download: $apk . Source code zip: ${com.arin.builder.BuildRunner.sourceZipUrl(p)} . " +
            "GitHub repository: ${com.arin.builder.BuildRunner.repoPage(p)} . When the person asks for the links, the APK or the " +
            "source code, give these exact links (never make links up).\n\n"
    }

    /** How hard Build mode works (more fix rounds when higher). */
    var effort by mutableStateOf(com.arin.builder.Effort.HIGH)
        private set

    fun chooseEffort(e: com.arin.builder.Effort) {
        effort = e
    }

    fun key(p: Provider): String = settings.key(p)

    fun githubToken(): String = settings.secret("GITHUB")

    /** Set when chat starts a build, so the screen can switch to Build mode. */
    var openBuild by mutableStateOf(false)

    /** Starts building an app from chat; returns a problem to show, or null when it started. */
    fun startBuild(name: String, description: String, target: BuildTarget = BuildTarget.CHAT): String? {
        val token = githubToken()
        val app = getApplication<Application>()
        val phoneReady = com.arin.terminal.Linux.androidBuildReady(app)
        // Where to build: the phone (built-in Linux), or GitHub's cloud shown in the chat or the Build tab. Auto picks one.
        val onPhone = target == BuildTarget.PHONE || (target == BuildTarget.AUTO && phoneReady && token.isBlank())
        val inBuildTab = target == BuildTarget.BUILD_TAB
        return when {
            target == BuildTarget.PHONE && !phoneReady ->
                "To build on the phone, install the Android build tools in Settings → Linux first."
            !onPhone && token.isBlank() && !phoneReady ->
                "To build apps, sign in with GitHub in Settings → GitHub, or install the Android build tools in Settings → Linux to build on this phone. Then ask again."
            com.arin.builder.BuildRunner.running -> "A build is already running. Open Build mode to see it."
            provider.needsKey && settings.key(provider).isBlank() -> "Add your ${provider.label} key in Settings."
            else -> {
                // A follow-up in the same chat ("try again", "add dark mode") keeps working on the same app.
                val current = com.arin.builder.BuildRunner.project
                val same = current != null && !current.selfUpdate && chatBuildChat != null && chatBuildChat == chatId &&
                    chatBuildProject == com.arin.builder.BuildRunner.projectId &&
                    (name.isBlank() || name.equals(current.appName, ignoreCase = true) ||
                        retryWords.containsMatchIn(description.take(80)))
                val lastFailed = (com.arin.builder.BuildRunner.timeline.lastOrNull { it is com.arin.builder.TimelineItem.Result }
                    as? com.arin.builder.TimelineItem.Result)?.success == false
                if (!same) com.arin.builder.BuildRunner.newProject(app)
                val appName = if (same) current!!.appName
                    else name.ifBlank { description.split(Regex("\\s+")).take(3).joinToString(" ").take(30) }
                com.arin.builder.BuildService.start(
                    app,
                    com.arin.builder.BuildRequest(
                        description = description,
                        appName = appName,
                        provider = provider,
                        model = model,
                        apiKey = settings.key(provider),
                        effort = effort,
                        githubToken = token,
                        allowOtherSites = true,
                        project = if (same) current else null,
                        resume = same && lastFailed && retryWords.containsMatchIn(description.take(80)),
                        // The whole conversation goes to the builder, so nothing that was discussed gets lost.
                        requirements = buildRequirements(description),
                        requirementsComplete = true,
                        localOnly = onPhone,
                    ),
                )
                if (inBuildTab) openBuild = true
                else followBuildInChat((if (same) "Working on $appName again" else "Building $appName") + if (onPhone) " on this phone" else "")
                null
            }
        }
    }

    /** The person tapped "Build now" (or "Update Arin now") under a reply that prepared a build. */
    fun confirmBuildAt(message: Message, target: BuildTarget) {
        val (name, spec) = message.pendingBuild.split('\u0000', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (spec.isBlank()) return
        val problem = if (name == "update") startSelfUpdate(spec) else startBuild(name, spec, target)
        if (problem != null) { notice = problem; return }
        val i = messages.indexOfFirst { it.id == message.id }
        if (i >= 0) messages[i] = messages[i].copy(pendingBuild = "", text = messages[i].text.substringBefore("\n\n_Where should I build"))
        persist()
    }

    /**
     * Everything the app must do, for the builder: the specification written when the person said "build", plus the
     * conversation itself (what they asked for and what Arin proposed), so details the specification skipped still get built.
     */
    private fun buildRequirements(spec: String): String {
        val talk = messages.filter { m ->
            !m.failed && m.text.isNotBlank() &&
                !(m.text.startsWith("🛠️") || m.text.startsWith("✅ **") || m.text.startsWith("⚠️ **The build"))
        }
        // Newest first within a budget, so a very long chat keeps what was said last; then back in order.
        fun pick(fromUser: Boolean, each: Int, total: Int): List<Message> {
            var left = total
            return talk.filter { it.fromUser == fromUser }.asReversed().takeWhile { m ->
                (left > 0).also { left -= minOf(m.text.length, each) }
            }
        }
        val keep = (pick(true, 4_000, 18_000) + pick(false, 2_500, 12_000)).map { it.id }.toSet()
        return buildString {
            appendLine("SPECIFICATION (written when the person said \"build\"):")
            appendLine(spec.trim())
            appendLine()
            appendLine("THE CONVERSATION ABOUT THIS APP, in order. Everything the person asked for, and everything Arin proposed that the")
            appendLine("person didn't reject, must be in the app, including details the specification above left out. Where two messages")
            appendLine("conflict, the later one wins. Ignore parts that are not about this app.")
            var n = 0
            talk.filter { it.id in keep }.forEach { m ->
                n++
                val files = m.attachments.takeIf { it.isNotEmpty() }?.joinToString { it.name }?.let { " [attached: $it]" }.orEmpty()
                if (m.fromUser) appendLine("[$n] PERSON: " + m.text.trim().take(4_000) + files)
                else appendLine("[$n] ARIN: " + m.text.trim().take(2_500))
            }
        }.trim()
    }

    /**
     * Downloads [url] for the person and for terminal tools: into the built-in Linux (/root/files) when it's
     * installed, otherwise into Download/Arin. Returns a line for the model.
     */
    private suspend fun downloadFile(app: Application, url: String, name: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val fileName = name.ifBlank { url.substringAfterLast('/').substringBefore('?').ifBlank { "download" } }
                .replace(Regex("[^A-Za-z0-9._ ()-]"), "_").take(120)
            try {
                val req = okhttp3.Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Android) Arin").build()
                com.arin.builder.Http.client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext "Download failed: HTTP ${r.code} for $url"
                    val body = r.body ?: return@withContext "Download failed: empty response from $url"
                    val linuxPath = if (com.arin.terminal.Linux.installed(app)) {
                        body.byteStream().use { com.arin.terminal.Linux.importFile(app, it, fileName) }
                    } else null
                    if (linuxPath != null) {
                        "Downloaded $fileName (${r.header("Content-Length")?.toLongOrNull()?.let { com.arin.data.Attachments.readableSize(it) } ?: "size unknown"}) to $linuxPath in Linux. Terminal tools can use it there."
                    } else {
                        val values = android.content.ContentValues().apply {
                            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/Arin")
                        }
                        val dest = if (android.os.Build.VERSION.SDK_INT >= 29) {
                            app.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        } else null
                        if (dest != null) {
                            app.contentResolver.openOutputStream(dest)?.use { out -> body.byteStream().use { it.copyTo(out, 64 * 1024) } }
                            "Downloaded $fileName to the phone's Download/Arin folder (/storage/emulated/0/Download/Arin/$fileName)."
                        } else {
                            val f = java.io.File(app.getExternalFilesDir(null), fileName)
                            f.outputStream().use { out -> body.byteStream().use { it.copyTo(out, 64 * 1024) } }
                            "Downloaded $fileName to ${f.absolutePath}."
                        }
                    }
                }
            } catch (e: Exception) {
                "Download failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }

    // ---- Arin updating itself ----

    /** Where Arin's own source lives on GitHub (owner/repo and branch). */
    fun selfRepo(): String = settings.secret("SELF_REPO").ifBlank { DEFAULT_SELF_REPO }

    fun selfBranch(): String = settings.secret("SELF_BRANCH").ifBlank { DEFAULT_SELF_BRANCH }

    fun saveSelfRepo(repo: String, branch: String) {
        settings.setSecret("SELF_REPO", repo.trim().removePrefix("https://github.com/").trimEnd('/'))
        settings.setSecret("SELF_BRANCH", branch.trim())
    }

    /** Changes Arin's own code on GitHub, builds a new version and offers to install it. Returns a problem, or null. */
    fun startSelfUpdate(change: String): String? {
        val token = githubToken()
        val app = getApplication<Application>()
        return when {
            change.isBlank() -> "Describe what to change in Arin."
            token.isBlank() -> "To update Arin, sign in with GitHub in Settings → GitHub, then ask again."
            com.arin.builder.BuildRunner.running -> "A build is already running. Open Build mode to see it."
            provider.needsKey && settings.key(provider).isBlank() -> "Add your ${provider.label} key in Settings."
            else -> {
                com.arin.builder.BuildRunner.newProject(app)
                com.arin.builder.BuildService.start(
                    app,
                    com.arin.builder.BuildRequest(
                        description = change.trim(),
                        appName = "Arin",
                        provider = provider,
                        model = model,
                        apiKey = settings.key(provider),
                        effort = effort,
                        githubToken = token,
                        allowOtherSites = true,
                        selfUpdate = true,
                        selfRepo = selfRepo(),
                        selfBranch = selfBranch(),
                    ),
                )
                followBuildInChat("Updating Arin")
                null
            }
        }
    }

    // ---- Sign in with GitHub (device flow) ----

    var githubUser by mutableStateOf(settings.secret("GITHUB_USER"))
        private set
    var githubCode by mutableStateOf<com.arin.builder.GitHubLogin.DeviceCode?>(null)
        private set
    var githubLoginError by mutableStateOf<String?>(null)
        private set
    private var loginJob: kotlinx.coroutines.Job? = null

    fun githubClientId(): String = settings.secret("GITHUB_CLIENT_ID").ifBlank { com.arin.builder.GitHubLogin.DEFAULT_CLIENT_ID }

    fun saveGithubClientId(value: String) = settings.setSecret("GITHUB_CLIENT_ID", value)

    fun saveGithubToken(value: String) {
        settings.setSecret("GITHUB", value)
        if (value.isBlank()) githubUser = "" else refreshGithubUser()
    }

    /** Starts sign-in; [openBrowser] opens github.com/login/device where you enter the code. */
    fun signInGithub(openBrowser: (String) -> Unit) {
        val clientId = githubClientId()
        loginJob?.cancel()
        githubLoginError = null
        loginJob = viewModelScope.launch {
            try {
                val code = com.arin.builder.GitHubLogin.start(clientId)
                githubCode = code
                openBrowser(code.verificationUri)
                val token = com.arin.builder.GitHubLogin.waitForToken(clientId, code)
                settings.setSecret("GITHUB", token)
                refreshGithubUser()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                githubLoginError = e.message ?: "Sign-in failed."
            } finally {
                githubCode = null
            }
        }
    }

    fun signOutGithub() {
        loginJob?.cancel()
        settings.setSecret("GITHUB", "")
        settings.setSecret("GITHUB_USER", "")
        githubUser = ""
    }

    private fun refreshGithubUser() {
        val token = settings.secret("GITHUB")
        viewModelScope.launch {
            var result: Result<String> = Result.failure(Exception())
            for (attempt in 0 until 3) {
                result = runCatching { com.arin.builder.GitHubClient(token).login() }
                if (result.isSuccess) break
                kotlinx.coroutines.delay(3000)
            }
            result.onSuccess {
                githubUser = it
                settings.setSecret("GITHUB_USER", it)
            }
        }
    }

    // ---- Fixing errors automatically ----

    /**
     * What the model is told after a failure: Arin searches the web for the error itself, every time, and passes on what
     * it found with the instruction to apply a fix and retry, so known solutions are used straight away.
     */
    private suspend fun fixAdvice(streak: Int, errorQuery: String, sources: MutableList<Source>): String {
        val base = "Fix this yourself and keep going: find the cause in the output, apply a fix (install what's missing, change " +
            "the command or flags, correct the code or config) and try again. Don't stop to ask unless it truly needs the person."
        if (errorQuery.isBlank()) {
            return "$base If you're not sure of the fix, search the web for the error with <search>…</search>."
        }
        status = "Searching the web for the error: “${errorQuery.take(60)}”"
        val found = runCatching { WebSearch.searchForModel(errorQuery) }.getOrNull()
        status = null
        found?.first?.take(4)?.forEach { h -> if (sources.none { it.url == h.url }) sources += Source(h.title, h.url) }
        val web = found?.second?.take(7_000).orEmpty()
        return if (web.isBlank()) {
            "$base A web search for the error found nothing; search with other words (<search>) or try a different approach."
        } else {
            "I searched the web for this error (\"$errorQuery\"). Results:\n$web\n\n" +
                "Use them to fix it: read the most relevant page with <fetch> if you need the details, apply the fix and " +
                "try again." + if (streak >= 2) " This has failed $streak times in a row, so try a different approach than before." else ""
        }
    }

    // ---- Steering: messages sent while a reply is running ----

    /** Messages waiting to be given to the model at its next step. */
    val steering = mutableStateListOf<String>()

    @Volatile private var steerPending = false

    /** Adds a message for the running reply; the model sees it at its next step (or right away, if it's writing). */
    fun steer(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        steering += t
        steerPending = true
    }

    fun removeSteer(index: Int) {
        if (index in steering.indices) steering.removeAt(index)
        steerPending = steering.isNotEmpty()
    }

    private suspend fun takeSteering(): List<String> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val list = steering.toList()
        steering.clear()
        steerPending = false
        list
    }

    /** Thrown inside a stream to stop it when the person steers. */
    private class SteerInterrupt : Exception()

    fun send(text: String) {
        if (streaming) {
            steer(text)
            return
        }
        val edit = editing?.takeIf { it in messages.indices && messages[it].fromUser }
        val kept = edit?.let { messages[it].attachments }.orEmpty()
        val files = kept + pending.toList()
        if (text.isBlank() && files.isEmpty()) return
        pending.clear()
        if (edit != null) {
            // Replace the edited message and everything after it.
            val dropped = messages.subList(edit, messages.size).toList()
            Attachments.deleteFiles(dropped.flatMap { it.attachments }.filterNot { it in kept })
            while (messages.size > edit) messages.removeAt(messages.lastIndex)
            editing = null
        }
        messages += Message(fromUser = true, text = text.trim(), attachments = files)
        if (messages.size == 1 && !isReverseEngineeringChat()) title = makeTitle(text, files)
        persist()
        reply()
    }

    /** Start editing a sent message; returns its text for the message box. */
    fun startEdit(index: Int): String? {
        if (streaming || index !in messages.indices || !messages[index].fromUser) return null
        editing = index
        return messages[index].text
    }

    fun cancelEdit() {
        editing = null
    }

    /** Ask again for the last reply. */
    fun retry() {
        if (streaming) return
        while (messages.isNotEmpty() && !messages.last().fromUser) messages.removeAt(messages.lastIndex)
        if (messages.isEmpty()) return
        persist()
        reply()
    }

    fun stop() {
        job?.cancel()
    }

    private fun reply() {
        val history = messages.filterNot { it.failed }
        val p = provider
        val m = model
        val key = settings.key(p)
        val project = chatProject
        // REA is active in the chat named "Reverse engineering", or wherever it's switched on from the ⋮ menu.
        val reaChat = reaActive()
        val useTerminal = terminalOn || reaChat
        // REA (reverse engineering) is allowed only in the chat named "Reverse engineering"; other chats run normal.
        val servers = mcpServers.filter { it.enabled && (reaChat || it.name != com.arin.mcp.Mcp.REA.name) } +
            (if (reaChat && mcpServers.none { it.name == com.arin.mcp.Mcp.REA.name && it.enabled }) listOf(com.arin.mcp.Mcp.REA) else emptyList())
        val activeNames = servers.map { it.name }.toSet()
        val memoryFlag = memoryOn && !temporary
        val memoryList = if (memoryFlag) memories.toList() else emptyList()
        val deep = deepResearch
        val searchFlag = webSearch || deep
        val useSearch = webSearch || deep
        // What the person said this turn: only an order to build ("build", "make the APK") lets a build start.
        val userSaid = history.lastOrNull { it.fromUser }?.text.orEmpty()
        val hasChatBuild = history.any { !it.fromUser && (it.text.startsWith("🛠️") || it.text.startsWith("⚠️ **The build")) }
        val app = getApplication<Application>()
        streaming = true
        liveText = ""
        liveThinking = ""
        status = null
        ReplyService.start(app)
        // App-wide scope: the reply finishes even if this screen goes away.
        job = ArinApp.scope.launch {
            val thinking = StringBuilder()
            var thinkStart = 0L
            var answer = ""
            val sources = mutableListOf<Source>()
            val ran = mutableListOf<com.arin.data.CommandRun>()
            val foundMedia = mutableListOf<com.arin.data.MediaItem>()
            var webSteps = 0
            var nudges = 0
            var buildNudges = 0
            var stopped = false
            var failStreak = 0
            liveCommands.clear()
            var failure: String? = null
            try {
                val turns = Attachments.toTurns(p, history, projectMedia(project)).toMutableList()
                if (servers.isNotEmpty()) {
                    status = "Connecting to tools…"
                    val problems = com.arin.mcp.Mcp.connectAll(app, servers, canStart = useTerminal)
                    status = null
                    if (problems.isNotEmpty()) notice = problems.joinToString("\n")
                }
                val system = Prompt.system(
                    searchFlag, memoryFlag, memoryList, project, useTerminal, com.arin.mcp.Mcp.promptSection(activeNames),
                    terminalInfo = com.arin.terminal.Shell.describe(app),
                    pastChats = if (memoryFlag) withContext(Dispatchers.IO) { runCatching { store.recentTitles(chatId) }.getOrDefault("") } else "",
                    aboutPerson = aboutYou,
                    howToAnswer = howToAnswer,
                    style = replyStyle,
                    deepResearch = deep,
                ) + latestBuildInfo()
                var recalls = 0
                var searches = 0
                while (true) {
                    // Messages the person sent while Arin was working go in before the next step.
                    val notes = takeSteering()
                    if (notes.isNotEmpty()) {
                        val joined = "[While you were working, the person sent this. Follow it; it overrides earlier plans where they conflict.]\n" +
                            notes.joinToString("\n")
                        val last = turns.lastIndex
                        if (last >= 0 && turns[last].fromUser) turns[last] = turns[last].copy(text = turns[last].text + "\n\n" + joined)
                        else turns += Turn(true, joined)
                        notes.forEach { n ->
                            val s = com.arin.data.CommandRun(n, "", null, kind = "steer")
                            ran += s
                            liveCommands += s
                        }
                    }
                    val text = StringBuilder()
                    var streamRetries = 0
                    val interrupted = try {
                        while (true) {
                            // After a cut-off reply, send back what came so far and ask the model to carry on, so a
                            // retry continues from where it stopped instead of starting over.
                            val streamTurns = if (text.isEmpty()) turns
                                else turns + Turn(false, text.toString()) +
                                    Turn(true, "Your reply above was cut off mid-way. Continue it from exactly where it stopped. Do not repeat anything already written and do not start over — just carry straight on.")
                            try {
                                Llm.stream(p, key, m, system, streamTurns).collect { d ->
                                    if (steerPending) throw SteerInterrupt()
                                    when (d) {
                                        is Delta.Text -> {
                                            text.append(d.text)
                                            val now = text.toString()
                                            // Hold back what may be a search request; show everything else as it arrives.
                                            liveText = if (useSearch && searches < Prompt.MAX_SEARCHES && Prompt.maybeSearch(now)) "" else Prompt.clean(now)
                                        }
                                        is Delta.Thinking -> {
                                            if (thinkStart == 0L) thinkStart = System.currentTimeMillis()
                                            thinking.append(d.text)
                                            liveThinking = thinking.toString()
                                        }
                                    }
                                }
                                break
                            } catch (e: SteerInterrupt) {
                                throw e
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // Free models sometimes send an incomplete or invalid reply the gateway drops: retry a
                                // couple of times, continuing from the partial text so nothing already written is lost.
                                if (streamRetries < 2 && Prompt.isRetryable(e.message)) {
                                    streamRetries++
                                    status = if (text.isEmpty()) "The model hiccuped — retrying…"
                                        else "The model's reply cut off — continuing from where it stopped…"
                                    kotlinx.coroutines.delay(800)
                                    status = null
                                    continue
                                }
                                throw e
                            }
                        }
                        false
                    } catch (e: SteerInterrupt) {
                        true
                    }
                    if (interrupted) {
                        // Keep what was written so far, then continue with the person's new message.
                        val partial = text.toString()
                        if (partial.isNotBlank()) turns += Turn(false, partial + "\n[Stopped here because the person sent a message.]")
                        liveText = ""
                        continue
                    }
                    val full = text.toString()
                    val cmd = if (useTerminal && ran.count { it.kind != "steer" } < Prompt.MAX_COMMANDS) Prompt.command(full) else null
                    if (cmd != null) {
                        liveText = ""
                        val allowed = askToRun(cmd, tool = false)
                        val run = if (!allowed) {
                            com.arin.data.CommandRun(cmd, "The person chose not to run this command.", null, ran = false)
                        } else {
                            status = "Running: " + cmd.lineSequence().first().take(80)
                            val r = com.arin.terminal.Shell.run(app, cmd)
                            status = null
                            com.arin.data.CommandRun(cmd, r.forModel(), r.exitCode)
                        }
                        ran += run
                        liveCommands += run
                        turns += Turn(false, full)
                        val failed = run.ran && run.exitCode != 0
                        failStreak = if (failed) failStreak + 1 else 0
                        turns += Turn(
                            true,
                            when {
                                !run.ran -> "I chose not to run that command. Continue without it, or suggest another way."
                                !failed -> "Command finished (exit code ${run.exitCode ?: "unknown"}). Output:\n```\n${run.output}\n```\n" +
                                    "Continue the task: run the next command if needed, or answer when you're done."
                                else -> "Command FAILED (exit code ${run.exitCode ?: "unknown"}). Output:\n```\n${run.output}\n```\n" +
                                    fixAdvice(failStreak, Prompt.errorQuery(run.output, cmd), sources)
                            },
                        )
                        continue
                    }
                    val call = if (ran.count { it.kind != "steer" } < Prompt.MAX_COMMANDS && com.arin.mcp.Mcp.connected(activeNames).isNotEmpty()) Prompt.toolCall(full) else null
                    if (call != null) {
                        liveText = ""
                        val (name, body) = call
                        val run = if (name == "find_tools") {
                            com.arin.data.CommandRun("find_tools: $body", com.arin.mcp.Mcp.find(body), 0, kind = "tool")
                        } else {
                            val args = runCatching {
                                kotlinx.serialization.json.Json.parseToJsonElement(body.ifBlank { "{}" }) as kotlinx.serialization.json.JsonObject
                            }.getOrNull()
                            val label = "$name ${body.take(400)}"
                            when {
                                args == null -> com.arin.data.CommandRun(label, "The arguments weren't one valid JSON object. Send them as {\"name\": value}.", 1, kind = "tool")
                                !askToRun(label, tool = true) -> com.arin.data.CommandRun(label, "The person chose not to allow this tool call.", null, ran = false, kind = "tool")
                                else -> {
                                    status = "Using $name"
                                    val r = com.arin.mcp.Mcp.call(app, name, args)
                                    status = null
                                    com.arin.data.CommandRun(label, shorten(r.text), if (r.isError) 1 else 0, kind = "tool")
                                }
                            }
                        }
                        ran += run
                        liveCommands += run
                        turns += Turn(false, full)
                        turns += Turn(
                            true,
                            if (!run.ran) "I didn't allow that tool call. Continue without it, or suggest another way."
                            else if (run.exitCode != 0) {
                                failStreak++
                                "Result of $name (FAILED):\n```\n${run.output}\n```\n" + fixAdvice(failStreak, Prompt.errorQuery(run.output, name), sources)
                            } else {
                                failStreak = 0
                                "Result of $name:\n```\n${run.output}\n```\n" +
                                    "Continue the task: call the next tool or command if needed, or answer when you're done."
                            },
                        )
                        continue
                    }
                    // Past chats: read what was said before.
                    val recallQuery = if (memoryFlag && recalls < 4) Prompt.recall(full) else null
                    if (recallQuery != null) {
                        recalls++
                        liveText = ""
                        status = "Remembering “$recallQuery”"
                        val found = withContext(Dispatchers.IO) { runCatching { store.recall(recallQuery, chatId) }.getOrDefault("") }
                        status = null
                        val result = if (found.isBlank())
                            "No past chat mentions \"$recallQuery\". Try other words once, or answer from this chat and ask the person for the detail."
                        else "Matching parts of past chats:\n\n$found\n\nNow answer using these where they help."
                        val step = com.arin.data.CommandRun("recall: $recallQuery", result.take(2_000), 0, kind = "tool")
                        ran += step
                        liveCommands += step
                        turns += Turn(false, full)
                        turns += Turn(true, result)
                        continue
                    }
                    // Internet steps: show pictures or videos, read a page, download a file.
                    if (webSteps < Prompt.MAX_WEB_STEPS) {
                        val media = Prompt.mediaRequest(full)
                        val page = if (media == null) Prompt.fetchUrl(full) else null
                        val file = if (media == null && page == null) Prompt.download(full) else null
                        if (media != null || page != null || file != null) {
                            webSteps++
                            liveText = ""
                            val result: String = when {
                                media != null -> {
                                    val (video, q) = media
                                    status = if (video) "Finding videos of “$q”" else "Finding pictures of “$q”"
                                    val found = runCatching {
                                        if (video) com.arin.media.MediaSearch.videos(q) else com.arin.media.MediaSearch.images(q)
                                    }.getOrDefault(emptyList())
                                    foundMedia += found.filter { f -> foundMedia.none { it.url == f.url } }
                                    if (found.isEmpty()) "No ${if (video) "videos" else "pictures"} were found for \"$q\". Try other words, or tell the person."
                                    else "Found ${found.size} ${if (video) "videos" else "pictures"} for \"$q\" (" +
                                        found.take(6).joinToString("; ") { it.title.take(60) } +
                                        "). They are shown to the person right under your reply. Now finish your answer; don't list their links."
                                }
                                page != null -> {
                                    status = "Reading " + page.removePrefix("https://").removePrefix("http://").take(60)
                                    "Content of $page:\n\n" + runCatching { com.arin.builder.WebTools.fetch(page) }.getOrElse { "Couldn't open it: ${it.message}" }.take(24_000)
                                }
                                else -> {
                                    val (name, url) = file!!
                                    status = "Downloading " + url.substringAfterLast('/').substringBefore('?').take(60)
                                    downloadFile(app, url, name)
                                }
                            }
                            status = null
                            val label = when {
                                media != null -> (if (media.first) "videos: " else "pictures: ") + media.second
                                page != null -> "fetch $page"
                                else -> "download ${file!!.second}"
                            }
                            val step = com.arin.data.CommandRun(label, result.take(2_000), 0, kind = "tool")
                            ran += step
                            liveCommands += step
                            turns += Turn(false, full)
                            turns += Turn(true, result)
                            continue
                        }
                    }
                    val working = ran.any { it.kind != "steer" && !it.command.startsWith("recall: ") }
                    val q = when {
                        deep && searches < Prompt.MAX_DEEP_SEARCHES -> Prompt.searchQueryAnywhere(full)
                        (useSearch || useTerminal) && working && searches < Prompt.MAX_TASK_SEARCHES -> Prompt.searchQueryAnywhere(full)
                        useSearch && searches < Prompt.MAX_SEARCHES -> Prompt.searchQuery(full)
                        else -> null
                    }
                    if (q == null) {
                        val saidNow = listOf(userSaid) + ran.filter { it.kind == "steer" }.map { it.command }
                        val mayBuild = saidNow.any { Prompt.saysBuild(it, hasChatBuild) }
                        // The person said "build" but the reply has no <build> tag: ask for it once.
                        if (buildNudges < 1 && !working && saidNow.any { Prompt.ordersBuild(it) } &&
                            Prompt.buildRequest(full) == null && Prompt.selfUpdate(full) == null &&
                            !com.arin.builder.BuildRunner.running && !Prompt.clean(full).trimEnd().endsWith("?")
                        ) {
                            buildNudges++
                            turns += Turn(false, full)
                            turns += Turn(
                                true,
                                "The person told you to build, but your reply had no <build> tag, so nothing started. If this is about " +
                                    "an Android app, send it now: <build name=\"App name\">the complete numbered specification of " +
                                    "EVERYTHING discussed in this conversation</build>, with at most one short sentence before it. " +
                                    "If they meant something else, just answer them.",
                            )
                            liveText = ""
                            continue
                        }
                        // Announced a build or update ("Build spec going out now:") but left out the tag? Ask for it.
                        if (nudges < 3 && Prompt.promisedBuildWithoutTag(full) && (mayBuild || full.takeLast(300).contains("updat", true))) {
                            nudges++
                            turns += Turn(false, full)
                            turns += Turn(
                                true,
                                "You said the build is starting, but your reply had no <build> tag, so nothing started. Send it now: " +
                                    "<build name=\"App name\">the full description of the app</build> (or <update_arin>…</update_arin> " +
                                    "for a change to Arin). Only the tag and at most one short sentence.",
                            )
                            liveText = ""
                            continue
                        }
                        // Stopped mid-task ("fixing it by…:" with no command)? Ask the model to carry on.
                        if (working && nudges < 3 && (useTerminal || com.arin.mcp.Mcp.connected(activeNames).isNotEmpty()) && Prompt.looksUnfinished(full)) {
                            nudges++
                            turns += Turn(false, full)
                            turns += Turn(
                                true,
                                "You described your next step but didn't send it. Do it now: put the <run> command or <tool> call " +
                                    "as the last thing in your reply. If the task is actually finished, give the final answer instead.",
                            )
                            liveText = ""
                            continue
                        }
                        answer = full
                        break
                    }
                    searches++
                    status = "Searching the web for “$q”"
                    liveText = ""
                    val (hits, results) = WebSearch.searchForModel(q)
                    hits.take(5).forEach { h -> if (sources.none { it.url == h.url }) sources += Source(h.title, h.url) }
                    status = null
                    turns += Turn(false, "<search>$q</search>")
                    turns += Turn(true, results + "\n\nNow answer my earlier message using these results where they help.")
                }
                if (Prompt.clean(answer).isBlank() && ran.isEmpty()) failure = "The model sent an empty reply. Tap Retry, or pick another model."
            } catch (e: CancellationException) {
                stopped = true
                answer = liveText // Stop pressed: keep whatever arrived.
            } catch (e: Throwable) {
                failure = e.message ?: "Something went wrong."
                com.arin.CrashLog.note(getApplication(), "Reply failed", "${p.label} · $m: ${failure}", e.stackTraceToString().take(4_000))
            } finally {
                val seconds = if (thinkStart > 0) ((System.currentTimeMillis() - thinkStart) / 1000).toInt() else 0
                if (memoryFlag) Prompt.memories(answer).forEach { remember(it) }
                // The model asked to change Arin itself: start a self-update build.
                // Updating Arin itself: only on the person's order, otherwise offer an "Update Arin now" button.
                var pendingBuild = ""
                if (failure == null && !stopped) Prompt.selfUpdate(answer)?.let { change ->
                    if (listOf(userSaid).any { Prompt.ordersUpdate(it) }) {
                        ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                            startSelfUpdate(change)?.let { notice = it }
                        }
                    } else pendingBuild = "update\u0000$change"
                }
                // The model asked to build an app: start Build mode.
                // (Launched separately: this block also runs after Stop, when this coroutine is cancelled.)
                // It starts only when the person said so ("build", "make the APK", or "try again" after a build).
                val saidAll = listOf(userSaid) + ran.filter { it.kind == "steer" }.map { it.command }
                val buildAsked = if (failure == null && !stopped) Prompt.buildRequest(answer) else null
                var buildNote = ""
                if (buildAsked != null) {
                    // A build is prepared, but nothing starts until the person picks where to build it.
                    pendingBuild = buildAsked.first + "\u0000" + buildAsked.second
                    buildNote = if (saidAll.any { Prompt.saysBuild(it, hasChatBuild) })
                        "\n\n_Where should I build it? Pick below — nothing builds until you choose._"
                    else "\n\n_Where should I build it? Pick below when you're ready._"
                }
                // Pictures and videos: found by searches, plus any the model linked in its text, plus ones it drew.
                foundMedia += com.arin.media.MediaSearch.linked(answer).filter { l -> foundMedia.none { it.url == l.url } }
                foundMedia += Prompt.drawings(answer).filter { d -> foundMedia.none { it.url == d.url } }
                val shown = com.arin.media.MediaSearch.withoutImages(Prompt.clean(answer)) + buildNote
                val msg = if (failure != null && shown.isBlank()) {
                    Message(fromUser = false, text = failure!!, failed = true)
                } else {
                    if (failure != null) notice = failure
                    Message(
                        fromUser = false,
                        text = shown.ifBlank { if (ran.isNotEmpty()) "Ran ${ran.size} command${if (ran.size == 1) "" else "s"}." else "(Stopped)" },
                        thinking = thinking.toString(),
                        thoughtSeconds = seconds,
                        sources = sources,
                        commands = ran.toList(),
                        media = foundMedia.toList(),
                        pendingBuild = pendingBuild,
                    )
                }
                messages += msg
                streaming = false
                liveText = ""
                liveThinking = ""
                liveCommands.clear()
                status = null
                job = null
                persist()
                lastFinished = msg
                ReplyService.stop(app)
                // Messages sent after the model's last step become the next message.
                ArinApp.scope.launch(kotlinx.coroutines.Dispatchers.Main) {
                    if (steering.isNotEmpty() && !streaming) {
                        val rest = steering.joinToString("\n")
                        steering.clear()
                        steerPending = false
                        send(rest)
                    }
                }
                val id = chatId
                if (!ArinApp.inForeground && notifyOnReply && id != null && !msg.failed) {
                    ReplyService.notifyReply(app, id, title, msg.text)
                }
            }
        }
    }

    /** Every project file goes with the first message: text, documents, photos and PDFs. */
    private fun projectMedia(project: Project?): List<Attachment> = project?.files.orEmpty()

    private fun makeTitle(text: String, files: List<Attachment>): String {
        val base = text.lines().firstOrNull { it.isNotBlank() }?.trim() ?: files.firstOrNull()?.name ?: "New chat"
        return if (base.length > 48) base.take(47).trimEnd() + "…" else base
    }

    private fun persist() {
        if (messages.isEmpty() || temporary) return
        val id = chatId ?: java.util.UUID.randomUUID().toString().also { chatId = it }
        val chat = Chat(id = id, title = title, updated = System.currentTimeMillis(), messages = messages.toList(), projectId = chatProject?.id, pinned = chatPinned, reaOn = reaOn)
        ArinApp.scope.launch(Dispatchers.IO) {
            store.save(chat)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    // ---------------- Attachments ----------------

    /** Name of the file being read right now (big files take a moment), or null. */
    var readingFile by mutableStateOf<String?>(null)
        private set

    fun attach(uris: List<Uri>) {
        viewModelScope.launch {
            uris.forEach { uri ->
                readingFile = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "file"
                try {
                    pending += Attachments.read(getApplication(), uri, forTerminal = terminalOn)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    notice = e.message ?: "Couldn't read that file."
                } finally {
                    readingFile = null
                }
            }
        }
    }

    fun removePending(a: Attachment) {
        pending.remove(a)
        Attachments.deleteFiles(listOf(a))
    }

    // ---------------- Chats list ----------------

    /** Start a new chat, optionally inside [project]. */
    fun newChat(project: Project? = null, temporary: Boolean = false) {
        if (streaming) stop()
        this.temporary = temporary
        reaOn = false
        chatPinned = false
        if (temporary) notice = "Temporary chat: it isn't saved and doesn't use memory."
        chatId = null
        title = "New chat"
        chatProject = project
        messages.clear()
        Attachments.deleteFiles(pending.toList())
        pending.clear()
        editing = null
    }

    /** The one chat where REA (reverse engineering) works. Everywhere else it stays off. */
    fun openReverseEngineering() {
        val existing = chats.firstOrNull { it.title.equals(REVERSE_ENG_TITLE, ignoreCase = true) }
        if (existing != null) {
            open(existing.id)
        } else {
            newChat()
            title = REVERSE_ENG_TITLE
            notice = "Reverse engineering chat: REA tools work here. Other chats stay normal."
        }
    }

    /** True when the open chat is the reverse-engineering chat (the only one that may use REA). */
    private fun isReverseEngineeringChat() = title.equals(REVERSE_ENG_TITLE, ignoreCase = true)

    /** REA turned on for THIS chat from the ⋮ menu (the "Reverse engineering" chat always has it on). */
    var reaOn by mutableStateOf(false)
        private set

    /** Whether REA is active in the open chat (its name, or the per-chat switch). */
    fun reaActive() = isReverseEngineeringChat() || reaOn

    fun toggleRea(on: Boolean) {
        reaOn = on
        if (on) {
            if (!terminalOn) { terminalOn = true; settings.terminalOn = true }
            notice = when {
                !com.arin.terminal.Linux.installed(getApplication()) ->
                    "REA is on for this chat. Install Linux and REA in Settings → Linux first, then it will work here."
                com.arin.mcp.Mcp.status(com.arin.mcp.Mcp.REA.name) == null ->
                    "REA is on for this chat. It starts on your next message (first start takes a few seconds)."
                else -> "REA is on for this chat: all its tools are available here."
            }
        } else {
            notice = "REA is off for this chat."
        }
        persist()
    }

    fun open(id: String) {
        if (id == chatId) return
        if (streaming) stop()
        viewModelScope.launch {
            val chat = withContext(Dispatchers.IO) { store.load(id) } ?: run {
                notice = "That chat couldn't be opened."
                return@launch
            }
            temporary = false
            reaOn = chat.reaOn
            chatPinned = chat.pinned
            chatId = chat.id
            title = chat.title
            chatProject = chat.projectId?.let { pid -> projects.firstOrNull { it.id == pid } }
            messages.clear()
            messages.addAll(chat.messages)
            Attachments.deleteFiles(pending.toList())
            pending.clear()
            editing = null
        }
    }

    fun rename(id: String, newTitle: String) {
        val t = newTitle.trim().ifEmpty { return }
        if (id == chatId) title = t
        viewModelScope.launch(Dispatchers.IO) {
            store.rename(id, t)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    fun moveToProject(id: String, project: Project?) {
        if (id == chatId) chatProject = project
        viewModelScope.launch(Dispatchers.IO) {
            store.moveToProject(id, project?.id)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    fun delete(id: String) {
        if (id == chatId) newChat()
        viewModelScope.launch(Dispatchers.IO) {
            store.delete(id)
            withContext(Dispatchers.Main) { refreshChats() }
        }
    }

    fun deleteAll() {
        newChat()
        viewModelScope.launch(Dispatchers.IO) {
            store.deleteAll()
            projectStore.deleteAll()
            withContext(Dispatchers.Main) {
                refreshChats()
                refreshProjects()
            }
        }
    }

    fun search(q: String) {
        query = q
        refreshChats()
    }

    private fun refreshChats() {
        val q = query
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.search(q) }
            if (q == query) {
                chats.clear()
                chats.addAll(list)
            }
        }
    }

    fun chatsIn(project: Project): List<ChatSummary> = chats.filter { it.projectId == project.id }

    // ---------------- Projects ----------------

    private fun refreshProjects() {
        projects.clear()
        projects.addAll(projectStore.list())
        chatProject = chatProject?.let { cur -> projects.firstOrNull { it.id == cur.id } }
    }

    fun createProject(name: String): Project {
        val p = Project(name = name.trim().ifEmpty { "New project" })
        projectStore.save(p)
        refreshProjects()
        return p
    }

    fun updateProject(p: Project) {
        projectStore.save(p)
        refreshProjects()
    }

    fun deleteProject(p: Project) {
        if (chatProject?.id == p.id) chatProject = null
        viewModelScope.launch(Dispatchers.IO) {
            store.detachProject(p.id)
            projectStore.delete(p.id)
            withContext(Dispatchers.Main) {
                refreshProjects()
                refreshChats()
            }
        }
    }

    fun addProjectFiles(p: Project, uris: List<Uri>) {
        viewModelScope.launch {
            var current = projects.firstOrNull { it.id == p.id } ?: p
            uris.forEach { uri ->
                try {
                    current = current.copy(files = current.files + Attachments.read(getApplication(), uri))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    notice = e.message ?: "Couldn't read that file."
                }
            }
            updateProject(current)
        }
    }

    fun removeProjectFile(p: Project, file: Attachment) {
        Attachments.deleteFiles(listOf(file))
        updateProject(p.copy(files = p.files - file))
    }

    // ---------------- Memory ----------------

    private fun remember(fact: String) {
        if (memoryStore.add(fact)) {
            memories.clear()
            memories.addAll(memoryStore.list())
        }
    }

    fun addMemory(fact: String) = remember(fact)

    fun forget(m: Memory) {
        memoryStore.remove(m.id)
        memories.remove(m)
    }

    fun forgetAll() {
        memoryStore.clear()
        memories.clear()
    }

    // ---------------- Settings ----------------

    fun chooseProvider(p: Provider) {
        provider = p
        settings.provider = p
        models = DefaultModels.of(p)
        model = settings.model(p) ?: models.first().id
        loadModels()
    }

    // ---- Custom API ----

    /** Custom APIs saved by the person; one tap switches to any of them (each keeps its own key and model). */
    val savedApis = androidx.compose.runtime.mutableStateListOf<com.arin.data.SavedApi>().apply { addAll(settings.savedApis) }

    /** Saves the API in use now under [name] (or renames it if it's already saved). */
    fun saveCurrentApi(name: String) {
        val n = name.trim().ifBlank { com.arin.llm.CustomApi.label(customBaseUrl) }
        val api = com.arin.data.SavedApi(n, customBaseUrl, customFormat.name)
        val i = savedApis.indexOfFirst { it.baseUrl == customBaseUrl }
        if (i >= 0) savedApis[i] = api else savedApis += api
        settings.savedApis = savedApis.toList()
        notice = "Saved \"$n\""
    }

    fun removeSavedApi(api: com.arin.data.SavedApi) {
        savedApis.remove(api)
        settings.savedApis = savedApis.toList()
    }

    /** Switches replies to a saved API, with its own key and last model. */
    fun useSavedApi(api: com.arin.data.SavedApi) {
        val format = runCatching { com.arin.llm.ApiFormat.valueOf(api.format) }.getOrDefault(com.arin.llm.ApiFormat.OpenAI)
        if (api.model.isNotBlank() && settings.model(Provider.Custom).isNullOrBlank()) settings.setModelFor(api.baseUrl, api.model)
        setCustomBase(api.baseUrl, format)
        chooseProvider(Provider.Custom)
        notice = "Using ${api.name}" + if (settings.key(Provider.Custom).isBlank()) " (no API key saved for it: tap Edit to add one)" else ""
    }

    /**
     * Adds a new API, or changes [old], from one form: name, base URL, key, model and format.
     * Returns a problem to show, or null when saved. The new API is switched on right away.
     */
    fun saveApi(old: com.arin.data.SavedApi?, name: String, url: String, key: String, model: String, format: com.arin.llm.ApiFormat): String? {
        val base = com.arin.llm.CustomApi.normalize(url, format)
        if (base.isBlank()) return "Enter the base URL, e.g. https://example.com/v1"
        if (!base.startsWith("https://") && !base.startsWith("http://")) return "The base URL must start with https://"
        val api = com.arin.data.SavedApi(name.trim().ifBlank { com.arin.llm.CustomApi.label(base) }, base, format.name, model.trim())
        // A key left empty while editing keeps the one already saved.
        if (key.isNotBlank()) settings.setKeyFor(base, key)
        else if (old != null && old.baseUrl != base) settings.setKeyFor(base, settings.keyFor(old.baseUrl))
        settings.setModelFor(base, model)
        // Replace the one being edited (or one with the same address); keep a single entry per address.
        val list = savedApis.toMutableList()
        val i = list.indexOfFirst { (old != null && it == old) || it.baseUrl == base }
        if (i >= 0) list[i] = api else list += api
        val cleaned = list.filterIndexed { idx, it -> it.baseUrl != base || idx == list.indexOf(api) }
        savedApis.clear()
        savedApis.addAll(cleaned)
        settings.savedApis = cleaned
        useSavedApi(api)
        return null
    }

    fun hasKeyFor(api: com.arin.data.SavedApi) = settings.keyFor(api.baseUrl).isNotBlank()

    /** The saved API in use right now, if any. */
    fun currentSavedApi(): com.arin.data.SavedApi? =
        if (provider == Provider.Custom) savedApis.firstOrNull { it.baseUrl == customBaseUrl } else null

    var customBaseUrl by mutableStateOf(settings.customBaseUrl)
        private set
    var customFormat by mutableStateOf(settings.customFormat)
        private set
    var customModel by mutableStateOf(settings.customModel)
        private set

    /** Switches the Custom API to another address. Each address keeps its own key and model. */
    fun setCustomBase(url: String, format: com.arin.llm.ApiFormat = customFormat) {
        settings.customFormat = format
        settings.customBaseUrl = url
        customFormat = format
        customBaseUrl = settings.customBaseUrl
        customModel = settings.customModel
        refreshCustom()
    }

    fun chooseCustomFormat(format: com.arin.llm.ApiFormat) {
        settings.customFormat = format
        customFormat = format
        refreshCustom()
    }

    fun saveCustomModel(id: String) {
        settings.customModel = id
        customModel = settings.customModel
        if (provider == Provider.Custom && id.isNotBlank()) settings.setModel(Provider.Custom, id.trim())
        refreshCustom()
    }

    private fun refreshCustom() {
        if (provider != Provider.Custom) return
        models = DefaultModels.of(Provider.Custom)
        model = settings.model(Provider.Custom) ?: models.first().id
        loadModels()
    }

    fun chooseModel(option: ModelOption) {
        model = option.id
        settings.setModel(provider, option.id)
    }

    fun saveKey(p: Provider, key: String) {
        settings.setKey(p, key)
        if (p == provider) loadModels()
    }

    fun hasKey(p: Provider) = settings.key(p).isNotBlank()

    fun chooseTheme(mode: ThemeMode) {
        theme = mode
        settings.theme = mode
    }

    fun toggleThinking(show: Boolean) {
        showThinking = show
        settings.showThinking = show
    }

    fun toggleWebSearch(on: Boolean) {
        webSearch = on
        settings.webSearch = on
    }

    fun toggleMemory(on: Boolean) {
        memoryOn = on
        settings.memoryOn = on
    }

    fun toggleNotify(on: Boolean) {
        notifyOnReply = on
        settings.notifyOnReply = on
    }

    fun modelLabel(): String = models.firstOrNull { it.id == model }?.label ?: model

    private fun loadModels() {
        val p = provider
        viewModelScope.launch {
            val list = Llm.models(p, settings.key(p))
            if (p != provider) return@launch
            models = list
            // Keep the saved choice if it still exists; otherwise take the first.
            val saved = settings.model(p)
            model = list.firstOrNull { it.id == saved }?.id ?: list.first().id
        }
    }

    companion object {
        /** The single chat where REA (reverse engineering) is active. */
        const val REVERSE_ENG_TITLE = "Reverse engineering"

        /** Arin's source repository, set when this build of Arin was made; changeable in Settings → GitHub. */
        const val DEFAULT_SELF_REPO = "poisaingti-spec/aiapp-261110-1752"
        const val DEFAULT_SELF_BRANCH = "arin"

        /** After REA is installed in the built-in Linux: have the model prove it works. */
        val REA_CHECK_PROMPT = """
            REA (Reverse Engineer Anything) was just installed in Arin's built-in Linux and connected as MCP tools. Check that it really works:
            1. Run rea --version in the terminal (Arin sets COLUMNS/LINES; without a terminal size REA's CLI crashes under PRoot).
            2. Use find_tools to look up analyze_javascript_application, then create a tiny test app (mkdir -p /root/rea-test && echo 'export function add(a,b){return a+b}' > /root/rea-test/app.js) and call that tool on /root/rea-test.
            3. Tell me what worked, what REA can do on this phone (static JavaScript/web bundles, APKs, Flutter, archives…), and what needs a desktop (Ghidra, IDA, Hopper, live Chrome). Only claim what the outputs show.
            Arin is the MCP client and REA is already connected: don't run rea setup and don't create or edit .claude.json or any other agent's config.
        """.trimIndent()

        /** What "Set up REA" asks the model to do, step by step in the terminal. */
        val REA_SETUP_PROMPT = """
            Set up REA (Reverse Engineer Anything, npm package rea-agents from github.com/morluto/rea) on this phone in Termux and connect it to Arin, then verify it works. Steps:
            1. Check what's installed: node -v, npm -v. REA needs Node.js ^22.23.4, ^24.22.1 or >=26.12.1. If Node is missing or too old, run pkg update -y, then pkg install -y nodejs (or nodejs-lts if that version fits better) and check again.
            2. Install REA's Linux prerequisite and REA itself: pkg install -y procps (REA needs a procps-compatible ps on PATH), then npm install --global rea-agents --omit=optional, and confirm with rea --version.
            3. Install only what REA needs for app and website work; skip the heavy native engines (Ghidra, IDA, Hopper, Frida): use npm install --global rea-agents --omit=optional. Don't run "rea setup" or edit .claude.json or other agents' configs: Arin connects to REA itself.
            4. Run termux-setup-storage only if ~/storage doesn't exist yet (I'll approve the prompt), so REA can read files I attach (they are saved in Download/Arin).
            5. Start REA through Arin's bridge (already saved at ~/.arin/mcp-bridge.mjs) and check it answers:
               (nohup env REA_MCP_OUTPUT_SCHEMA_PROFILE=compact node ~/.arin/mcp-bridge.mjs --port 8765 -- rea mcp > ~/.arin/rea.log 2>&1 &); sleep 6; curl -s http://127.0.0.1:8765/health
               then list its tools: curl -s -X POST http://127.0.0.1:8765/mcp -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}' | head -c 400; and curl -s -X POST http://127.0.0.1:8765/mcp -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | grep -o '"name":"[a-z_]*"' | wc -l
               If it fails, read ~/.arin/rea.log and fix the cause.
            6. Termux reports the platform as "android", which REA doesn't officially support; tools that launch other programs may refuse with an unsupported-host or capability_unavailable error. After the tools are listed, check one real analysis call works, for example: mkdir -p ~/rea-test && echo 'export function add(a,b){return a+b}' > ~/rea-test/app.js && rea analyze-javascript-application ~/rea-test --json | head -c 1500
            7. If REA refuses because of the host, set it up in Ubuntu inside Termux instead, where REA sees a normal Linux system: pkg install -y proot-distro && proot-distro install ubuntu, then inside it (proot-distro login ubuntu -- bash -lc '…') install Node.js 24 from NodeSource or nvm, procps and rea-agents, and start the bridge from there on the same port: proot-distro login ubuntu --bind ~/.arin:/root/.arin -- bash -lc 'nohup env REA_MCP_OUTPUT_SCHEMA_PROFILE=compact node /root/.arin/mcp-bridge.mjs --port 8765 -- rea mcp > /root/.arin/rea.log 2>&1 &'. Stop the earlier bridge first (pkill -f mcp-bridge.mjs). Then repeat the checks in steps 5 and 6.
            8. Tell me the result, including any tools that can't work on a phone. From my next message on, Arin connects to REA automatically and you can call its tools directly.
            Report each step's real output. Only say it works once a command shows it.
        """.trimIndent()
    }
}
