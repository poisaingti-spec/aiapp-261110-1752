package com.arin.mcp

import android.content.Context
import com.arin.terminal.Shell
import com.arin.terminal.Termux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * An MCP server Arin can use: reached over HTTP (Streamable HTTP). Local stdio servers such as REA
 * run in Termux behind Arin's small bridge ([BRIDGE_ASSET]), which [startCommand] starts.
 */
@Serializable
data class McpServer(
    val name: String,
    val url: String,
    /** Shell command that starts the server in Termux when it isn't reachable. Optional. */
    val startCommand: String = "",
    /** Sent as "Authorization: Bearer …" for remote servers that need it. */
    val token: String = "",
    val enabled: Boolean = true,
)

/** One tool a server offers. */
data class McpTool(val server: String, val name: String, val description: String, val inputSchema: JsonElement?)

/** The result of a tool call, as text for the model. */
data class McpResult(val text: String, val isError: Boolean)

class McpException(message: String) : Exception(message)

/** Talks to one server: initialize, list tools, call tools. */
class McpClient(val server: McpServer) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    private val ids = AtomicLong(1)
    private var sessionId: String? = null
    private var protocol: String = PROTOCOL
    var instructions: String = ""
        private set
    var tools: List<McpTool> = emptyList()
        private set
    val ready: Boolean get() = tools.isNotEmpty()

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    private suspend fun rpc(method: String, params: JsonObject? = null, notification: Boolean = false): JsonObject? =
        withContext(Dispatchers.IO) {
            val id = if (notification) null else ids.getAndIncrement()
            val body = buildJsonObject {
                put("jsonrpc", "2.0")
                if (id != null) put("id", id)
                put("method", method)
                if (params != null) put("params", params)
            }.toString()
            val req = Request.Builder().url(server.url)
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", protocol)
                .apply {
                    sessionId?.let { header("Mcp-Session-Id", it) }
                    if (server.token.isNotBlank()) header("Authorization", "Bearer ${server.token.trim()}")
                }
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val resp = try {
                http.newCall(req).execute()
            } catch (e: java.io.IOException) {
                throw McpException("Can't reach ${server.name} at ${server.url} (${e.message ?: "no connection"}).")
            }
            resp.use { r ->
                r.header("Mcp-Session-Id")?.let { sessionId = it }
                if (notification) return@withContext null
                if (r.code == 401 || r.code == 403) throw McpException("${server.name} refused the connection (HTTP ${r.code}). Check its token in Settings → Tools.")
                if (!r.isSuccessful) throw McpException("${server.name} answered HTTP ${r.code}.")
                val type = r.header("Content-Type").orEmpty()
                val reply: JsonObject? = if (type.contains("text/event-stream")) {
                    // Read events until the one answering our id.
                    val source = r.body!!.source()
                    var found: JsonObject? = null
                    val data = StringBuilder()
                    while (found == null && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").trim())
                            line.isBlank() && data.isNotEmpty() -> {
                                val msg = runCatching { json.parseToJsonElement(data.toString()).jsonObject }.getOrNull()
                                data.clear()
                                if (msg != null && (msg["id"] as? JsonPrimitive)?.contentOrNull == id.toString()) found = msg
                            }
                        }
                    }
                    found
                } else {
                    val text = r.body?.string().orEmpty()
                    val el = runCatching { json.parseToJsonElement(text) }.getOrNull()
                    when (el) {
                        is JsonObject -> el
                        is JsonArray -> el.firstOrNull { (it as? JsonObject)?.get("id")?.let { v -> (v as? JsonPrimitive)?.contentOrNull } == id.toString() } as? JsonObject
                        else -> null
                    }
                }
                reply ?: throw McpException("${server.name} sent no answer.")
                (reply["error"] as? JsonObject)?.let { err ->
                    throw McpException((err["message"] as? JsonPrimitive)?.contentOrNull ?: "${server.name} returned an error.")
                }
                reply["result"] as? JsonObject ?: JsonObject(emptyMap())
            }
        }

    suspend fun connect() {
        sessionId = null
        val result = rpc(
            "initialize",
            buildJsonObject {
                put("protocolVersion", PROTOCOL)
                putJsonObject("capabilities") {}
                putJsonObject("clientInfo") { put("name", "arin"); put("version", "0.5") }
            },
        )!!
        (result["protocolVersion"] as? JsonPrimitive)?.contentOrNull?.let { protocol = it }
        instructions = (result["instructions"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        rpc("notifications/initialized", notification = true)
        val list = mutableListOf<McpTool>()
        var cursor: String? = null
        do {
            val page = rpc("tools/list", cursor?.let { c -> buildJsonObject { put("cursor", c) } })!!
            (page["tools"] as? JsonArray)?.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
                list += McpTool(server.name, name, (o["description"] as? JsonPrimitive)?.contentOrNull.orEmpty(), o["inputSchema"])
            }
            cursor = (page["nextCursor"] as? JsonPrimitive)?.contentOrNull
        } while (cursor != null)
        tools = list
    }

    suspend fun call(tool: String, args: JsonObject): McpResult {
        val result = try {
            rpc("tools/call", buildJsonObject { put("name", tool); put("arguments", args) })!!
        } catch (e: McpException) {
            // The session may have expired (e.g. the bridge restarted): reconnect once.
            connect()
            rpc("tools/call", buildJsonObject { put("name", tool); put("arguments", args) })!!
        }
        val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull == "true"
        val parts = (result["content"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> (o["text"] as? JsonPrimitive)?.contentOrNull
                "image" -> "[image: ${(o["mimeType"] as? JsonPrimitive)?.contentOrNull ?: "picture"}]"
                "resource", "resource_link" -> (o["resource"] as? JsonObject)?.let { r ->
                    (r["text"] as? JsonPrimitive)?.contentOrNull ?: (r["uri"] as? JsonPrimitive)?.contentOrNull
                } ?: (o["uri"] as? JsonPrimitive)?.contentOrNull
                else -> null
            }
        }
        val text = parts.joinToString("\n").ifBlank {
            result["structuredContent"]?.takeIf { it !is JsonNull }?.toString() ?: "(no output)"
        }
        return McpResult(text, isError)
    }

    companion object {
        const val PROTOCOL = "2025-06-18"
    }
}

/**
 * The person's MCP servers: stored in settings, connected when a reply needs them, with their
 * tools offered to the model.
 */
object Mcp {
    const val BRIDGE_ASSET = "mcp-bridge.mjs"
    /** Printed by a start command when the server's program isn't installed yet. */
    const val NOT_INSTALLED = "ARIN_SERVER_NOT_INSTALLED"
    private const val BRIDGE_PATH = "~/.arin/mcp-bridge.mjs"

    /** REA (Reverse Engineer Anything), run in Termux behind Arin's bridge. */
    val REA = McpServer(
        name = "rea",
        url = "http://127.0.0.1:8765/mcp",
        startCommand = "command -v rea >/dev/null 2>&1 || { echo $NOT_INSTALLED; exit 0; }; " +
            // rea.env holds the Android tool paths (REA_JADX_MCP_JAR, JAVA_HOME, REA_APKTOOL_COMMAND).
            "mkdir -p ~/.arin && (set -a; [ -f ~/.arin/rea.env ] && . ~/.arin/rea.env; set +a; nohup env UV_USE_IO_URING=0 GLIBC_TUNABLES=glibc.pthread.rseq=0 COLUMNS=120 LINES=40 REA_MCP_OUTPUT_SCHEMA_PROFILE=compact " +
            "node $BRIDGE_PATH --port 8765 -- rea mcp > ~/.arin/rea.log 2>&1 &) ; sleep 6; " +
            "curl -s http://127.0.0.1:8765/health || (echo 'REA did not start; last log lines:'; tail -20 ~/.arin/rea.log)",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val clients = HashMap<String, McpClient>()
    private val lock = Mutex()

    fun load(raw: String?): List<McpServer> =
        raw?.let { runCatching { json.decodeFromString<List<McpServer>>(it) }.getOrNull() }.orEmpty()

    fun save(servers: List<McpServer>): String = json.encodeToString(servers)

    /** Writes Arin's bridge script into Termux, so start commands can use it. */
    suspend fun installBridge(context: Context): Termux.Result {
        val script = context.assets.open(BRIDGE_ASSET).bufferedReader().use { it.readText() }
        return Shell.run(
            context,
            "mkdir -p ~/.arin && cat > $BRIDGE_PATH <<'ARIN_BRIDGE_EOF'\n$script\nARIN_BRIDGE_EOF\necho bridge installed",
            timeoutMs = 60_000,
        )
    }

    /**
     * Connects every enabled server that isn't connected yet. A server that can't be reached and has a
     * start command is started in Termux first (when [canStart]). Returns problems, one line each.
     */
    suspend fun connectAll(context: Context, servers: List<McpServer>, canStart: Boolean): List<String> = lock.withLock {
        val problems = mutableListOf<String>()
        clients.keys.retainAll(servers.filter { it.enabled }.map { it.name }.toSet())
        for (s in servers.filter { it.enabled }) {
            val existing = clients[s.name]
            if (existing != null && existing.server == s && existing.ready) continue
            val client = McpClient(s)
            val first = runCatching { client.connect() }
            if (first.isFailure && canStart && s.startCommand.isNotBlank() && Shell.problem(context) == null) {
                if (s.startCommand.contains(BRIDGE_PATH)) installBridge(context)
                val started = Shell.run(context, s.startCommand, timeoutMs = 120_000)
                if (started.stdout.contains(NOT_INSTALLED)) {
                    problems += "${s.name} isn't installed in Termux yet."
                } else {
                    var ok = false
                    for (i in 0 until 5) {
                        if (runCatching { client.connect() }.isSuccess) { ok = true; break }
                        delay(2_000)
                    }
                    if (!ok) problems += "${s.name}: started it in Termux, but it doesn't answer at ${s.url}. " +
                        (started.error ?: started.combined().takeLast(300))
                }
            } else if (first.isFailure) {
                problems += "${s.name}: " + (first.exceptionOrNull()?.message ?: "not reachable")
            }
            if (client.ready) clients[s.name] = client else clients.remove(s.name)
        }
        problems
    }

    fun connected(allowed: Set<String>? = null): List<McpClient> =
        clients.values.filter { it.ready && (allowed == null || it.server.name in allowed) }

    fun status(name: String): String? = clients[name]?.let { if (it.ready) "Connected · ${it.tools.size} tools" else null }

    fun forget(name: String) {
        clients.remove(name)
    }

    /** Finds a tool by name ("tool" or "server.tool"). */
    fun tool(name: String): McpTool? {
        val (srv, t) = if ('.' in name && connected().any { name.startsWith(it.server.name + ".") }) {
            name.substringBefore('.') to name.substringAfter('.')
        } else null to name
        return connected().filter { srv == null || it.server.name == srv }.flatMap { it.tools }.firstOrNull { it.name == t }
    }

    /** Tools whose name or description matches the words in [query], with their parameters. */
    fun find(query: String, limit: Int = 8): String {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 1 }
        val all = connected().flatMap { it.tools }
        val scored = all.map { t ->
            val hay = (t.name.replace('_', ' ') + " " + t.description).lowercase()
            t to words.sumOf { w -> (if (t.name.contains(w)) 3 else 0) + (if (hay.contains(w)) 1 else 0) }
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(limit)
        if (scored.isEmpty()) return "No tools match \"$query\". Try other words, or pick from the tool names listed in your instructions."
        return scored.joinToString("\n\n") { (t, _) -> describe(t) }
    }

    fun describe(t: McpTool): String {
        val schema = t.inputSchema?.toString().orEmpty()
        return "Tool ${t.name} (server ${t.server}): ${t.description.take(600)}\nParameters (JSON Schema): " +
            (if (schema.length > 4_000) schema.take(4_000) + "…" else schema.ifBlank { "{}" })
    }

    suspend fun call(context: Context, name: String, args: JsonObject): McpResult {
        val t = tool(name) ?: return McpResult("There is no tool called \"$name\". Use find_tools to look it up.", true)
        val client = clients[t.server] ?: return McpResult("${t.server} isn't connected.", true)
        return try {
            client.call(t.name, args)
        } catch (e: Exception) {
            // The server may have stopped (e.g. restarted for new settings): start it again and retry once.
            val s = client.server
            if (s.startCommand.isNotBlank() && Shell.problem(context) == null) {
                runCatching {
                    Shell.run(context, s.startCommand, timeoutMs = 120_000)
                    var ok = false
                    for (i in 0 until 5) {
                        if (runCatching { client.connect() }.isSuccess) { ok = true; break }
                        delay(2_000)
                    }
                    if (ok) return client.call(t.name, args)
                }
            }
            McpResult("The call failed: ${e.message ?: e.javaClass.simpleName}", true)
        }
    }

    /** The part of the instructions that tells the model which tools exist and how to call them. */
    fun promptSection(allowed: Set<String>? = null): String {
        val list = connected(allowed)
        if (list.isEmpty()) return ""
        return buildString {
            append("Tools (MCP): you can use tools from connected MCP servers, the same way Claude Code does. ")
            append("To call one, write as the LAST thing in your reply:\n")
            append("<tool name=\"TOOL_NAME\">{\"argument\": \"value\"}</tool>\n")
            append("and stop. The app runs it and sends you the result. The body must be one JSON object (use {} for none). ")
            append("If you don't know a tool's parameters, first call <tool name=\"find_tools\">a few keywords</tool>, which returns ")
            append("matching tools with their descriptions and parameters. One tool call at a time; read each result before the next. ")
            append("Tools run in the same Linux as your terminal commands; attached files are at the paths given in the messages. ")
            append("Arin itself is the MCP client and already connected these servers: never run `rea setup` or other agent setup ")
            append("wizards, and don't create or edit Claude Code, Cursor or other agents' MCP configs (.claude.json, mcp.json). ")
            append("Base claims on what the tools return.\n")
            list.forEach { c ->
                append("\nServer \"").append(c.server.name).append("\" (").append(c.tools.size).append(" tools)")
                if (c.instructions.isNotBlank()) append(". Its guidance: ").append(c.instructions.take(1_200))
                append("\nTool names: ").append(c.tools.joinToString(", ") { it.name }).append('\n')
            }
            append('\n')
        }
    }
}
