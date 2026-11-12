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
    fun setUpRea() {
        toggleTerminal(true)
        if (mcpServers.none { it.name == com.arin.mcp.Mcp.REA.name }) saveServer(com.arin.mcp.Mcp.REA)
        val app = getApplication<Application>()
        newChat()
        viewModelScope.launch {
            // Put Arin's bridge in Termux first, so the model can start and test REA through it.
            if (com.arin.terminal.Termux.problem(app) == null) com.arin.mcp.Mcp.installBridge(app)
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
        val r = com.arin.terminal.Termux.run(
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

    fun send(text: String) {
        if (streaming) return
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
        if (messages.size == 1) title = makeTitle(text, files)
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
        val useTerminal = terminalOn
        val servers = mcpServers.filter { it.enabled }
        val memoryList = if (memoryOn) memories.toList() else emptyList()
        val memoryFlag = memoryOn
        val searchFlag = webSearch
        val useSearch = webSearch
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
                val system = Prompt.system(searchFlag, memoryFlag, memoryList, project, useTerminal, com.arin.mcp.Mcp.promptSection())
                var searches = 0
                while (true) {
                    val text = StringBuilder()
                    Llm.stream(p, key, m, system, turns).collect { d ->
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
                    val full = text.toString()
                    val cmd = if (useTerminal && ran.size < Prompt.MAX_COMMANDS) Prompt.command(full) else null
                    if (cmd != null) {
                        liveText = ""
                        val allowed = askToRun(cmd, tool = false)
                        val run = if (!allowed) {
                            com.arin.data.CommandRun(cmd, "The person chose not to run this command.", null, ran = false)
                        } else {
                            status = "Running: " + cmd.lineSequence().first().take(80)
                            val r = com.arin.terminal.Termux.run(app, cmd)
                            status = null
                            com.arin.data.CommandRun(cmd, r.forModel(), r.exitCode)
                        }
                        ran += run
                        liveCommands += run
                        turns += Turn(false, full)
                        turns += Turn(
                            true,
                            if (!run.ran) "I chose not to run that command. Continue without it, or suggest another way."
                            else "Command finished (exit code ${run.exitCode ?: "unknown"}). Output:\n```\n${run.output}\n```\n" +
                                "Continue the task: run the next command if needed, or answer when you're done.",
                        )
                        continue
                    }
                    val call = if (ran.size < Prompt.MAX_COMMANDS && com.arin.mcp.Mcp.connected().isNotEmpty()) Prompt.toolCall(full) else null
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
                                    val r = com.arin.mcp.Mcp.call(name, args)
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
                            else "Result of $name" + (if (run.exitCode != 0) " (error)" else "") + ":\n```\n${run.output}\n```\n" +
                                "Continue the task: call the next tool or command if needed, or answer when you're done.",
                        )
                        continue
                    }
                    val q = if (useSearch && searches < Prompt.MAX_SEARCHES) Prompt.searchQuery(full) else null
                    if (q == null) {
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
                answer = liveText // Stop pressed: keep whatever arrived.
            } catch (e: Throwable) {
                failure = e.message ?: "Something went wrong."
            } finally {
                val seconds = if (thinkStart > 0) ((System.currentTimeMillis() - thinkStart) / 1000).toInt() else 0
                if (memoryOn) Prompt.memories(answer).forEach { remember(it) }
                val shown = Prompt.clean(answer)
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
        if (messages.isEmpty()) return
        val id = chatId ?: java.util.UUID.randomUUID().toString().also { chatId = it }
        val chat = Chat(id = id, title = title, updated = System.currentTimeMillis(), messages = messages.toList(), projectId = chatProject?.id)
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
    fun newChat(project: Project? = null) {
        if (streaming) stop()
        chatId = null
        title = "New chat"
        chatProject = project
        messages.clear()
        Attachments.deleteFiles(pending.toList())
        pending.clear()
        editing = null
    }

    fun open(id: String) {
        if (id == chatId) return
        if (streaming) stop()
        viewModelScope.launch {
            val chat = withContext(Dispatchers.IO) { store.load(id) } ?: run {
                notice = "That chat couldn't be opened."
                return@launch
            }
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
        /** What "Set up REA" asks the model to do, step by step in the terminal. */
        val REA_SETUP_PROMPT = """
            Set up REA (Reverse Engineer Anything, npm package rea-agents from github.com/morluto/rea) on this phone in Termux and connect it to Arin, then verify it works. Steps:
            1. Check what's installed: node -v, npm -v. REA needs Node.js ^22.23.4, ^24.22.1 or >=26.12.1. If Node is missing or too old, run pkg update -y, then pkg install -y nodejs (or nodejs-lts if that version fits better) and check again.
            2. Install REA: npm install --global rea-agents, then confirm with rea --version.
            3. Install only what REA needs for app and website work; skip the heavy native engines (Ghidra, IDA, Hopper, Frida). Don't run the interactive "rea-agents setup" wizard: Arin connects to REA itself.
            4. Run termux-setup-storage only if ~/storage doesn't exist yet (I'll approve the prompt), so REA can read files I attach (they are saved in Download/Arin).
            5. Start REA through Arin's bridge (already saved at ~/.arin/mcp-bridge.mjs) and check it answers:
               (nohup env REA_MCP_OUTPUT_SCHEMA_PROFILE=compact node ~/.arin/mcp-bridge.mjs --port 8765 -- rea mcp > ~/.arin/rea.log 2>&1 &); sleep 6; curl -s http://127.0.0.1:8765/health
               then list its tools: curl -s -X POST http://127.0.0.1:8765/mcp -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"check","version":"1"}}}' | head -c 400; and curl -s -X POST http://127.0.0.1:8765/mcp -H 'content-type: application/json' -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | grep -o '"name":"[a-z_]*"' | wc -l
               If it fails, read ~/.arin/rea.log and fix the cause.
            6. Tell me the result. From my next message on, Arin connects to REA automatically and you can call its tools directly.
            Report each step's real output. Only say it works once a command shows it.
        """.trimIndent()
    }
}
