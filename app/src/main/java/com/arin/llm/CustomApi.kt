package com.arin.llm

/** The request format a custom service speaks. */
enum class ApiFormat(val label: String) {
    OpenAI("OpenAI format"),
    Anthropic("Claude (Anthropic) format"),
}

/**
 * The "Custom API" provider: your own base URL, key and model. Works with any OpenAI-compatible
 * service (Token Harbor, OpenRouter, DeepSeek, Groq, Together, Mistral, xAI…) and with
 * Claude-compatible gateways that speak the Anthropic Messages format.
 * Values are loaded from [com.arin.data.Settings].
 */
object CustomApi {

    data class Preset(val name: String, val baseUrl: String, val format: ApiFormat, val keyPage: String)

    val presets = listOf(
        Preset("Token Harbor", "https://tokenharbor.ai/v1", ApiFormat.OpenAI, "tokenharbor.ai/dashboard/api-keys"),
        Preset("OpenRouter", "https://openrouter.ai/api/v1", ApiFormat.OpenAI, "openrouter.ai/keys"),
        Preset("DeepSeek", "https://api.deepseek.com/v1", ApiFormat.OpenAI, "platform.deepseek.com/api_keys"),
        Preset("Groq", "https://api.groq.com/openai/v1", ApiFormat.OpenAI, "console.groq.com/keys"),
        Preset("Together", "https://api.together.xyz/v1", ApiFormat.OpenAI, "api.together.ai/settings/api-keys"),
        Preset("Mistral", "https://api.mistral.ai/v1", ApiFormat.OpenAI, "console.mistral.ai/api-keys"),
        Preset("xAI (Grok)", "https://api.x.ai/v1", ApiFormat.OpenAI, "console.x.ai"),
        Preset("Moonshot (Kimi)", "https://api.moonshot.ai/v1", ApiFormat.OpenAI, "platform.moonshot.ai/console/api-keys"),
        Preset("Fireworks", "https://api.fireworks.ai/inference/v1", ApiFormat.OpenAI, "fireworks.ai/account/api-keys"),
        Preset("Cerebras", "https://api.cerebras.ai/v1", ApiFormat.OpenAI, "cloud.cerebras.ai"),
        Preset("NVIDIA", "https://integrate.api.nvidia.com/v1", ApiFormat.OpenAI, "build.nvidia.com"),
    )

    const val DEFAULT_BASE = "https://tokenharbor.ai/v1"

    @Volatile var baseUrl: String = DEFAULT_BASE
    @Volatile var format: ApiFormat = ApiFormat.OpenAI
    /** A model id typed by the person, for services with no model list or unlisted models. */
    @Volatile var manualModel: String = ""

    /**
     * Cleans up a pasted address: adds https://, drops a trailing slash or a pasted endpoint path
     * (/chat/completions, /messages, /models). For the OpenAI format, adds /v1 when only a site name
     * was given.
     */
    fun normalize(input: String, format: ApiFormat): String {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        for (tail in listOf("/chat/completions", "/completions", "/messages", "/models")) {
            if (url.endsWith(tail)) url = url.removeSuffix(tail).trimEnd('/')
        }
        val path = url.substringAfter("://").substringAfter('/', "")
        if (path.isEmpty() && format == ApiFormat.OpenAI) url += "/v1"
        return url
    }

    /** Base with the version segment the Anthropic format needs ("…/v1"). */
    private fun anthropicBase(): String = if (baseUrl.endsWith("/v1")) baseUrl else "$baseUrl/v1"

    fun chatUrl(): String = when (format) {
        ApiFormat.OpenAI -> "$baseUrl/chat/completions"
        ApiFormat.Anthropic -> "${anthropicBase()}/messages"
    }

    fun modelsUrl(): String = when (format) {
        ApiFormat.OpenAI -> "$baseUrl/models"
        ApiFormat.Anthropic -> "${anthropicBase()}/models"
    }

    fun host(url: String = baseUrl): String = url.substringAfter("://").substringBefore('/')

    fun presetFor(url: String = baseUrl): Preset? = presets.firstOrNull { it.baseUrl == url }

    /** A short name for the service: the preset name, or the site's address. */
    fun label(url: String = baseUrl): String = presetFor(url)?.name ?: host(url)
}
