package com.aiapp.llm

import com.aiapp.data.Log
import kotlinx.coroutines.flow.Flow
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

    private val base = "https://text.pollinations.ai"
    private val referrer = "aiapp"

    private val fallback = listOf(
        ModelInfo("openai", "OpenAI (GPT, free)"),
        ModelInfo("openai-large", "OpenAI large (free)"),
        ModelInfo("mistral", "Mistral (free)"),
        ModelInfo("deepseek", "DeepSeek (free)"),
        ModelInfo("llama", "Llama (free)"),
    )

    override suspend fun listModels(apiKey: String): List<ModelInfo> = runCatching {
        val raw = Http.getJsonArray("$base/models", emptyMap())
        val models = raw.mapNotNull { el ->
            when (el) {
                is JsonPrimitive -> el.contentOrNull?.let { ModelInfo(it, it) }
                is JsonObject -> {
                    val name = el["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val type = el["type"]?.jsonPrimitive?.contentOrNull
                    if (type != null && type != "chat" && type != "text") return@mapNotNull null
                    val desc = el["description"]?.jsonPrimitive?.contentOrNull ?: name
                    ModelInfo(name, desc)
                }
                else -> null
            }
        }
        models.ifEmpty { fallback }
    }.getOrElse {
        Log.w("FreeCloud", "listModels failed: ${it.message}")
        fallback
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> {
        val body = buildJsonObject {
            put("model", model.ifBlank { "openai" })
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
        return Http.sse("$base/openai", mapOf("Content-Type" to "application/json"), body).mapNotNull { data -> parse(data) }
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
