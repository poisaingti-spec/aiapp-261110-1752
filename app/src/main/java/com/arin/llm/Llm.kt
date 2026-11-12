package com.arin.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Talks to every provider. Free models, Gemini and ChatGPT share the OpenAI chat format;
 * Claude uses the Anthropic Messages format.
 */
object Llm {
    private const val FREE_BASE = "https://text.pollinations.ai"
    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/openai"
    private const val OPENAI_BASE = "https://api.openai.com/v1"
    private const val ANTHROPIC_BASE = "https://api.anthropic.com/v1"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    fun stream(provider: Provider, key: String, model: String, system: String, turns: List<Turn>): Flow<Delta> {
        if (provider.needsKey && key.isBlank()) {
            throw LlmException("Add your ${provider.label} API key in Settings, or switch to Free models.")
        }
        if (model.isBlank()) throw LlmException("Pick a model first, or type a model id in Settings → Custom API.")
        return when (provider) {
            Provider.Free -> openAiStream("$FREE_BASE/openai", emptyMap(), model, system, turns, extra = true)
            Provider.Gemini -> openAiStream("$GEMINI_BASE/chat/completions", bearer(key), model, system, turns)
            Provider.ChatGPT -> openAiStream("$OPENAI_BASE/chat/completions", bearer(key), model, system, turns)
            Provider.Claude -> anthropicStream("$ANTHROPIC_BASE/messages", anthropicHeaders(key), key, model, system, turns)
            Provider.Custom -> when (CustomApi.format) {
                ApiFormat.OpenAI -> openAiStream(CustomApi.chatUrl(), bearer(key), model, system, turns)
                ApiFormat.Anthropic -> anthropicStream(CustomApi.chatUrl(), customAnthropicHeaders(key), key, model, system, turns)
            }
        }
    }

    /** The provider's current models, newest-looking first; falls back to the built-in list. */
    suspend fun models(provider: Provider, key: String): List<ModelOption> {
        val fallback = DefaultModels.of(provider)
        if (provider.needsKey && key.isBlank()) return fallback
        val found = runCatching {
            when (provider) {
                Provider.Free -> freeModels()
                Provider.Claude -> listIds("$ANTHROPIC_BASE/models", anthropicHeaders(key), "data", "id", "display_name")
                Provider.Gemini -> listIds("$GEMINI_BASE/models", bearer(key), "data", "id", null)
                    .filter { it.id.contains("gemini") }
                    .map { ModelOption(it.id.removePrefix("models/"), it.label.removePrefix("models/")) }
                Provider.ChatGPT -> listIds("$OPENAI_BASE/models", bearer(key), "data", "id", null)
                    .filter { m -> m.id.startsWith("gpt-") && listOf("audio", "realtime", "image", "tts", "transcribe", "search").none { it in m.id } }
                    .sortedByDescending { it.id }
                Provider.Custom -> customModels(key)
            }
        }.getOrNull().orEmpty()
        if (provider == Provider.Custom) {
            // Keep the typed model id at the top even when the service lists others.
            val manual = CustomApi.manualModel
            if (manual.isNotBlank() && found.none { it.id == manual }) return listOf(ModelOption(manual, manual)) + found
        }
        return found.ifEmpty { fallback }
    }

    private fun bearer(key: String) = mapOf("Authorization" to "Bearer $key")

    private fun anthropicHeaders(key: String) = mapOf("x-api-key" to key, "anthropic-version" to ANTHROPIC_VERSION)

    /** Claude-compatible gateways accept either header; send both. */
    private fun customAnthropicHeaders(key: String) = anthropicHeaders(key) + bearer(key)

    /** Ids that are clearly not chat models. */
    private val notChat = listOf(
        "embed", "whisper", "tts", "transcribe", "moderation", "rerank", "dall-e", "gpt-image",
        "imagen", "flux", "stable-diffusion", "sdxl", "guard",
    )

    private suspend fun customModels(key: String): List<ModelOption> {
        val headers = if (CustomApi.format == ApiFormat.Anthropic) customAnthropicHeaders(key) else bearer(key)
        return listIds(CustomApi.modelsUrl(), headers, "data", "id", if (CustomApi.format == ApiFormat.Anthropic) "display_name" else "name")
            .filter { m -> notChat.none { m.id.lowercase().contains(it) } }
    }

    private suspend fun listIds(url: String, headers: Map<String, String>, arrayKey: String, idKey: String, labelKey: String?): List<ModelOption> {
        val root = Http.getJson(url, headers) as? JsonObject ?: return emptyList()
        val items = root[arrayKey] as? JsonArray ?: return emptyList()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o[idKey] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val label = labelKey?.let { (o[it] as? JsonPrimitive)?.contentOrNull } ?: id
            ModelOption(id, label)
        }
    }

    private suspend fun freeModels(): List<ModelOption> {
        val list = Http.getJson("$FREE_BASE/models") as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = (o["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            // Only models open to everyone with no key.
            val tier = (o["tier"] as? JsonPrimitive)?.contentOrNull
            if (tier != null && tier != "anonymous") return@mapNotNull null
            val output = (o["output_modalities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (output != null && "text" !in output) return@mapNotNull null
            if ((o["uncensored"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
            val desc = (o["description"] as? JsonPrimitive)?.contentOrNull?.substringBefore(" (")
            ModelOption(name, "${desc ?: name} (free)")
        }
    }

    private fun openAiStream(url: String, headers: Map<String, String>, model: String, system: String, turns: List<Turn>, extra: Boolean = false): Flow<Delta> {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            if (extra) {
                put("private", true) // keep prompts out of the free service's public feed
                put("referrer", "arin-android")
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                turns.forEach { t ->
                    addJsonObject {
                        put("role", if (t.fromUser) "user" else "assistant")
                        if (!t.hasMedia) {
                            put("content", t.text)
                        } else {
                            // OpenAI-style parts: text, images as data URLs, PDFs as files.
                            putJsonArray("content") {
                                addJsonObject { put("type", "text"); put("text", t.text) }
                                t.images.forEach { img ->
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", "data:${img.mime};base64,${img.base64}") }
                                    }
                                }
                                t.pdfs.forEach { pdf ->
                                    addJsonObject {
                                        put("type", "file")
                                        putJsonObject("file") {
                                            put("filename", pdf.name)
                                            put("file_data", "data:application/pdf;base64,${pdf.base64}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }.toString()
        return Http.sse(url, headers, body).mapNotNull { data ->
            val o = Http.parseObject(data) ?: return@mapNotNull null
            (o["error"] as? JsonObject)?.let { err ->
                throw LlmException((err["message"] as? JsonPrimitive)?.contentOrNull ?: "The model returned an error.")
            }
            val choice = (o["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@mapNotNull null
            val delta = choice["delta"] as? JsonObject ?: return@mapNotNull null
            val text = (delta["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val thinking = ((delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive)?.contentOrNull.orEmpty()
            when {
                text.isNotEmpty() -> Delta.Text(text)
                thinking.isNotEmpty() -> Delta.Thinking(thinking)
                else -> null
            }
        }
    }

    private fun anthropicStream(url: String, headers: Map<String, String>, key: String, model: String, system: String, turns: List<Turn>): Flow<Delta> {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 8192)
            put("stream", true)
            put("system", system)
            putJsonArray("messages") {
                turns.forEach { t ->
                    addJsonObject {
                        put("role", if (t.fromUser) "user" else "assistant")
                        if (!t.hasMedia) {
                            put("content", t.text)
                        } else {
                            // Anthropic content blocks: documents and images first, then the question.
                            putJsonArray("content") {
                                t.pdfs.forEach { pdf ->
                                    addJsonObject {
                                        put("type", "document")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", "application/pdf")
                                            put("data", pdf.base64)
                                        }
                                        put("title", pdf.name)
                                    }
                                }
                                t.images.forEach { img ->
                                    addJsonObject {
                                        put("type", "image")
                                        putJsonObject("source") {
                                            put("type", "base64")
                                            put("media_type", img.mime)
                                            put("data", img.base64)
                                        }
                                    }
                                }
                                addJsonObject { put("type", "text"); put("text", t.text) }
                            }
                        }
                    }
                }
            }
        }.toString()
        return Http.sse(url, headers, body).mapNotNull { data ->
            val o = Http.parseObject(data) ?: return@mapNotNull null
            when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
                "error" -> {
                    val err = o["error"] as? JsonObject
                    throw LlmException((err?.get("message") as? JsonPrimitive)?.contentOrNull ?: "Claude returned an error.")
                }
                "content_block_delta" -> {
                    val d = o["delta"] as? JsonObject ?: return@mapNotNull null
                    when ((d["type"] as? JsonPrimitive)?.contentOrNull) {
                        "text_delta" -> (d["text"] as? JsonPrimitive)?.contentOrNull?.let { Delta.Text(it) }
                        "thinking_delta" -> (d["thinking"] as? JsonPrimitive)?.contentOrNull?.let { Delta.Thinking(it) }
                        else -> null
                    }
                }
                else -> null
            }
        }
    }
}
