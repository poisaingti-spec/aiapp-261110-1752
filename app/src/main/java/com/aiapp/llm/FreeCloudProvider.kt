package com.aiapp.llm

import com.aiapp.data.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A free cloud model that needs NO API key and no sign-up, via Pollinations' open, OpenAI-compatible
 * text API (text.pollinations.ai). It proxies to strong hosted models (GPT/Mistral/etc.).
 *
 * Trade-offs to be honest about: it is rate-limited and best-effort (it can be slow or busy), and
 * prompts go to a third-party service — so for private app code, a free-keyed provider (Gemini /
 * OpenRouter) is better. This is the zero-setup option.
 */
class FreeCloudProvider : LlmProvider {
    override val id = ProviderId.FREE

    // Current endpoint first, classic host as a fallback (hosts/paths rotate).
    private val chatEndpoints = listOf(
        "https://gen.pollinations.ai/v1/chat/completions",
        "https://text.pollinations.ai/openai",
    )
    private val modelEndpoints = listOf(
        "https://text.pollinations.ai/models",
        "https://gen.pollinations.ai/v1/models",
    )
    private val referrer = "aiapp"

    // "openai-fast" is the model the keyless legacy endpoint still serves without a key.
    private val fallback = listOf(
        ModelInfo("openai-fast", "OpenAI fast (keyless)"),
        ModelInfo("openai", "OpenAI (GPT)"),
        ModelInfo("openai-large", "OpenAI large"),
        ModelInfo("mistral", "Mistral"),
        ModelInfo("deepseek", "DeepSeek"),
    )

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        for (url in modelEndpoints) {
            val models = runCatching {
                val raw = Http.getJsonArray(url, emptyMap())
                raw.mapNotNull { el ->
                    when (el) {
                        is JsonPrimitive -> el.contentOrNull?.let { ModelInfo(it, it) }
                        is JsonObject -> {
                            val name = (el["name"] ?: el["id"])?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                            val type = el["type"]?.jsonPrimitive?.contentOrNull
                            if (type != null && type != "chat" && type != "text") return@mapNotNull null
                            ModelInfo(name, el["description"]?.jsonPrimitive?.contentOrNull ?: name)
                        }
                        else -> null
                    }
                }
            }.getOrElse { Log.w("FreeCloud", "listModels $url failed: ${it.message}"); emptyList() }
            if (models.isNotEmpty()) return models
        }
        return fallback
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> {
        val body = buildJsonObject {
            put("model", model.ifBlank { "openai-fast" })
            put("stream", true)
            put("referrer", referrer)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                val withFiles = AttachmentContent.binaryIndices(messages)
                messages.forEachIndexed { i, m ->
                    addJsonObject {
                        put("role", if (m.role == Role.USER) "user" else "assistant")
                        put("content", AttachmentContent.openAi(m, i in withFiles))
                    }
                }
            }
        }.toString()
        val headers = mapOf("Content-Type" to "application/json")
        // Try each endpoint; fall through only while nothing has been emitted yet.
        return flow {
            var last: Throwable? = null
            for (url in chatEndpoints) {
                var emitted = false
                try {
                    Http.sse(url, headers, body).mapNotNull { parse(it) }.collect { emitted = true; emit(it) }
                    return@flow
                } catch (e: Exception) {
                    if (emitted) throw e
                    Log.w("FreeCloud", "stream $url failed: ${e.message}")
                    last = e
                }
            }
            Log.e("FreeCloud", "all endpoints failed: ${last?.message}")
            throw ProviderException(
                503,
                "Couldn't reach the keyless free cloud model (your network may block it, or the free " +
                    "service now needs a key). For a reliable free model, add a Gemini key in Settings " +
                    "(free at aistudio.google.com/apikey) and pick the Gemini provider.",
            )
        }
    }

    private fun parse(data: String): String? {
        val o = runCatching { Http.json.parseToJsonElement(data).jsonObject }.getOrNull() ?: return null
        (o["error"] as? JsonObject)?.let { err ->
            val msg = (err["message"] as? JsonPrimitive)?.contentOrNull ?: "Free cloud model error"
            Log.e("FreeCloud", msg)
            throw ProviderException(500, msg)
        }
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        return (delta["content"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    }
}
