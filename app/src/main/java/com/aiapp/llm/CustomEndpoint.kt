package com.aiapp.llm

/**
 * The "Custom API" provider: any OpenAI-compatible service (Token Harbor, OpenRouter, DeepSeek,
 * Groq, Together, Mistral, xAI and many more). The user picks a base URL; each URL keeps its own key.
 */
object CustomEndpoint {

    data class Preset(val name: String, val baseUrl: String, val keyPage: String)

    val presets = listOf(
        Preset("Token Harbor", "https://tokenharbor.ai/v1", "tokenharbor.ai/dashboard/api-keys"),
        Preset("OpenRouter", "https://openrouter.ai/api/v1", "openrouter.ai/keys"),
        Preset("DeepSeek", "https://api.deepseek.com/v1", "platform.deepseek.com/api_keys"),
        Preset("Groq", "https://api.groq.com/openai/v1", "console.groq.com/keys"),
        Preset("Together", "https://api.together.xyz/v1", "api.together.ai/settings/api-keys"),
        Preset("Mistral", "https://api.mistral.ai/v1", "console.mistral.ai/api-keys"),
        Preset("xAI (Grok)", "https://api.x.ai/v1", "console.x.ai"),
        Preset("Moonshot (Kimi)", "https://api.moonshot.ai/v1", "platform.moonshot.ai/console/api-keys"),
        Preset("Fireworks", "https://api.fireworks.ai/inference/v1", "fireworks.ai/account/api-keys"),
        Preset("Cerebras", "https://api.cerebras.ai/v1", "cloud.cerebras.ai"),
        Preset("NVIDIA", "https://integrate.api.nvidia.com/v1", "build.nvidia.com"),
    )

    const val DEFAULT = "https://tokenharbor.ai/v1"

    /** Current base URL. Set from saved settings by [com.aiapp.data.ApiKeys]. */
    @Volatile
    var baseUrl: String = DEFAULT

    /** Model id typed by the user, for services without a model list. */
    @Volatile
    var manualModel: String = ""

    /**
     * Cleans up what the user pasted: adds https://, drops a trailing slash or a pasted
     * "/chat/completions" or "/models", and adds "/v1" when only a site name was given.
     */
    fun normalize(input: String): String {
        var url = input.trim().trimEnd('/')
        if (url.isEmpty()) return ""
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
        for (tail in listOf("/chat/completions", "/models", "/completions")) {
            if (url.endsWith(tail)) url = url.removeSuffix(tail)
        }
        url = url.trimEnd('/')
        val path = url.substringAfter("://").substringAfter('/', "")
        if (path.isEmpty()) url += "/v1"
        return url
    }

    fun host(url: String = baseUrl): String = url.substringAfter("://").substringBefore('/')

    fun presetFor(url: String = baseUrl): Preset? = presets.firstOrNull { it.baseUrl == url }

    /** A short name for messages: the preset name, or the site's host. */
    fun label(url: String = baseUrl): String = presetFor(url)?.name ?: host(url)
}
