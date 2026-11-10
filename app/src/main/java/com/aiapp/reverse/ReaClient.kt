package com.aiapp.reverse

import com.aiapp.llm.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Client for a remote `github.com/morluto/rea` MCP server, started on the user's own PC/server with:
 *
 *     npx -y rea-agents@latest mcp --http --host 0.0.0.0 --port 8765
 *
 * rea serves streamable HTTP at `/mcp`: JSON-RPC requests are POSTed and replies come back as a
 * single Server-Sent-Events `data:` line. The server assigns an `mcp-session-id` on `initialize`
 * which every later request must echo.
 *
 * rea does the heavy desktop-only analysis a phone can't: native binaries, IL2CPP/Unity, Flutter
 * snapshots, full JADX/Apktool decompilation, adb device pulls. Its Android tools take a file
 * `path` on the machine running rea, so the APK must already be on that machine (e.g. pulled over
 * adb with rea's own `pull_adb_package`).
 */
class ReaClient(private val config: ReaConfig) {

    private val json = Json { ignoreUnknownKeys = true }
    private var sessionId: String? = null
    private var nextId = 1

    private fun endpoint(): String = config.baseUrl.trimEnd('/').let {
        if (it.endsWith("/mcp")) it else "$it/mcp"
    }

    /** Opens a session and returns the server's advertised name/version string. */
    suspend fun connect(): String = withContext(Dispatchers.IO) {
        val (body, headers) = post(
            buildJsonObject {
                put("jsonrpc", "2.0"); put("id", nextId++); put("method", "initialize")
                putJsonObject("params") {
                    put("protocolVersion", "2025-06-18")
                    putJsonObject("capabilities") {}
                    putJsonObject("clientInfo") { put("name", "AI App"); put("version", "1") }
                }
            },
            withSession = false,
        )
        sessionId = headers["mcp-session-id"] ?: headers["Mcp-Session-Id"]
        // Complete the handshake; rea needs the initialized notification before tools/list.
        post(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }, notify = true)
        val info = body?.get("result")?.jsonObject?.get("serverInfo")?.jsonObject
        val name = info?.get("name")?.jsonPrimitive?.contentOrNull ?: "rea"
        val version = info?.get("version")?.jsonPrimitive?.contentOrNull ?: ""
        "$name $version".trim()
    }

    /** Lists the tools the server exposes (229 on a full desktop install). */
    suspend fun listTools(): List<ReaTool> = withContext(Dispatchers.IO) {
        val (body, _) = post(buildJsonObject { put("jsonrpc", "2.0"); put("id", nextId++); put("method", "tools/list") })
        val tools = body?.get("result")?.jsonObject?.get("tools") as? JsonArray ?: return@withContext emptyList()
        tools.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            ReaTool(name, o["description"]?.jsonPrimitive?.contentOrNull.orEmpty())
        }
    }

    /** Calls one tool and returns its textual content blocks joined together. */
    suspend fun callTool(name: String, arguments: JsonObject): String = withContext(Dispatchers.IO) {
        val (body, _) = post(buildJsonObject {
            put("jsonrpc", "2.0"); put("id", nextId++); put("method", "tools/call")
            putJsonObject("params") { put("name", name); put("arguments", arguments) }
        })
        val result = body?.get("result")?.jsonObject
        val error = body?.get("error")?.jsonObject
        if (error != null) return@withContext "rea error: " + (error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString())
        val content = result?.get("content") as? JsonArray ?: return@withContext result?.toString().orEmpty()
        content.joinToString("\n") { block ->
            val o = block.jsonObject
            o["text"]?.jsonPrimitive?.contentOrNull ?: o.toString()
        }
    }

    // ---------- transport ----------

    private fun post(payload: JsonObject, withSession: Boolean = true, notify: Boolean = false): Pair<JsonObject?, Map<String, String>> {
        val req = Request.Builder()
            .url(endpoint())
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json, text/event-stream")
            .apply {
                if (withSession) sessionId?.let { addHeader("mcp-session-id", it); addHeader("mcp-protocol-version", "2025-06-18") }
                if (config.headerName.isNotBlank()) addHeader(config.headerName, config.headerValue)
            }
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val headers = resp.headers.toMap()
            if (notify) return null to headers
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && text.isBlank()) throw IllegalStateException("rea HTTP ${resp.code}")
            return parseSse(text) to headers
        }
    }

    /** rea frames replies as SSE; pull the JSON out of the `data:` line(s). */
    private fun parseSse(text: String): JsonObject? {
        val data = text.lineSequence()
            .filter { it.startsWith("data:") }
            .joinToString("\n") { it.removePrefix("data:").trim() }
            .ifBlank { text.trim() }
        return runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
    }

    private fun okhttp3.Headers.toMap(): Map<String, String> =
        (0 until size).associate { name(it).lowercase() to value(it) }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
