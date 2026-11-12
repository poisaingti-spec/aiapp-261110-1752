package com.aiapp.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * An OpenAI-compatible service serving chat models.
 *
 * - [ProviderId.OPEN]: OpenRouter, limited to the biggest open-weight models (Kimi, DeepSeek, GLM, Qwen…).
 * - [ProviderId.CUSTOM]: any OpenAI-compatible base URL the user enters (Token Harbor, DeepSeek, Groq,
 *   Together, Mistral, xAI…), showing every chat model that service offers. See [CustomEndpoint].
 */
class OpenModelsProvider(
    override val id: ProviderId = ProviderId.OPEN,
    private val baseUrl: () -> String = { "https://openrouter.ai/api/v1" },
    /** Model-id prefixes to keep; empty keeps every chat model. */
    private val openVendors: List<String> = listOf(
        "moonshotai/", "deepseek/", "z-ai/", "qwen/", "minimax/", "meta-llama/", "mistralai/",
        "google/gemma", "openai/gpt-oss", "nvidia/", "microsoft/phi", "stepfun", "baidu/", "xiaomi/",
    ),
) : LlmProvider {

    private val base get() = baseUrl()

    private fun headers(key: String): Map<String, String> {
        val h = mutableMapOf("Authorization" to "Bearer $key")
        if (base.contains("openrouter.ai")) h["X-OpenRouter-Title"] = "AI App"
        return h
    }

    companion object {
        fun custom() = OpenModelsProvider(
            id = ProviderId.CUSTOM,
            baseUrl = { CustomEndpoint.baseUrl },
            openVendors = emptyList(),
        )
    }

    /** Ids that are clearly not chat models (only filtered for the Custom API, which lists everything). */
    private val notChat = listOf(
        "embed", "whisper", "tts", "transcribe", "moderation", "rerank", "dall-e", "gpt-image",
        "imagen", "flux", "stable-diffusion", "sdxl", "guard",
    )

    /** Uncensored re-releases are left out (blueprint §10). */
    private val excluded = listOf("uncensored", "abliterated", "dolphin", "venice")

    override suspend fun listModels(apiKey: String): List<ModelInfo> {
        val manual = if (id == ProviderId.CUSTOM) CustomEndpoint.manualModel else ""
        val data = try {
            Http.getJson("$base/models", headers(apiKey))["data"] as? JsonArray
        } catch (e: ProviderException) {
            // Some services have no model list: use the model id typed in Settings instead.
            if (manual.isNotBlank() && e.code != 401 && e.code != 403) null else throw e
        }
        val listed = data.orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val lower = id.lowercase()
            if (openVendors.isNotEmpty() && openVendors.none { lower.startsWith(it) }) return@mapNotNull null
            if (excluded.any { lower.contains(it) }) return@mapNotNull null
            if (openVendors.isEmpty() && notChat.any { lower.contains(it) }) return@mapNotNull null
            val outputs = ((o["architecture"] as? JsonObject)?.get("output_modalities") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (outputs != null && "text" !in outputs) return@mapNotNull null
            ModelInfo(id, (o["name"] as? JsonPrimitive)?.contentOrNull ?: id)
        }
        return if (manual.isNotBlank() && listed.none { it.id == manual }) listOf(ModelInfo(manual, manual)) + listed else listed
    }

    private fun effortValue(effort: Effort): String? = when (effort) {
        Effort.LOW -> "low"
        Effort.MEDIUM -> "medium"
        Effort.HIGH -> "high"
        Effort.MAX -> "xhigh"
        Effort.AUTO -> null
    }

    override fun stream(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        effort: Effort,
        system: String,
    ): Flow<String> {
        val level = effortValue(effort)
        val attempts = mutableListOf<() -> Flow<String>>()
        if (level != null) attempts += { request(apiKey, model, messages, system, level) }
        if (level == "xhigh") attempts += { request(apiKey, model, messages, system, "high") }
        attempts += { request(apiKey, model, messages, system, null) }
        // Stricter servers answer an unknown reasoning setting with 422 instead of 400.
        return fallbackChain(attempts, retryOn = setOf(400, 422))
    }

    private fun request(
        apiKey: String,
        model: String,
        messages: List<ChatMessage>,
        system: String,
        effort: String?,
    ): Flow<String> {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
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
            if (effort != null) put("reasoning_effort", effort)
            // OpenRouter-only switch; other gateways may reject unknown fields with HTTP 400.
            if (base.contains("openrouter.ai")) put("include_reasoning", true)
        }.toString()

        return Http.sse("$base/chat/completions", headers(apiKey), body).mapNotNull { data -> parse(data) }
    }

    private fun parse(data: String): String? {
        val o = Http.json.parseToJsonElement(data).jsonObject
        (o["error"] as? JsonObject)?.let { err ->
            throw ProviderException(500, (err["message"] as? JsonPrimitive)?.contentOrNull ?: "Model error")
        }
        val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val delta = choice["delta"] as? JsonObject ?: return null
        val content = (delta["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        // OpenRouter uses "reasoning"; DeepSeek-style services use "reasoning_content".
        val reasoning = ((delta["reasoning"] ?: delta["reasoning_content"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
        return when {
            content.isNotEmpty() -> content
            reasoning.isNotEmpty() -> thought(reasoning)
            else -> null
        }
    }
}
