package com.arin.llm

/** Where replies come from. [Free] needs no key; the others use the person's own API key. */
enum class Provider(val label: String, val needsKey: Boolean, val keyHint: String) {
    Free("Free models", needsKey = false, keyHint = ""),
    Claude("Claude", needsKey = true, keyHint = "console.anthropic.com → API keys"),
    Gemini("Gemini", needsKey = true, keyHint = "aistudio.google.com → Get API key"),
    ChatGPT("ChatGPT", needsKey = true, keyHint = "platform.openai.com → API keys"),
    Custom("Custom API", needsKey = true, keyHint = "your service's dashboard"),
}

/** One model a provider offers. */
data class ModelOption(val id: String, val label: String)

/** A turn sent to a model, with any photos and PDFs already encoded as base64. */
data class Turn(
    val fromUser: Boolean,
    val text: String,
    val images: List<Image> = emptyList(),
    val pdfs: List<Pdf> = emptyList(),
) {
    val hasMedia: Boolean get() = images.isNotEmpty() || pdfs.isNotEmpty()
}

class Image(val mime: String, val base64: String)

class Pdf(val name: String, val base64: String)

/** A piece of a streaming reply: visible text, or the model's reasoning. */
sealed interface Delta {
    data class Text(val text: String) : Delta
    data class Thinking(val text: String) : Delta
}

/** A failed request, with a message that can be shown to the person as it is. */
class LlmException(message: String) : Exception(message)

/** Built-in model lists, used until (or when) the provider's own list can't be loaded. */
object DefaultModels {
    val byProvider: Map<Provider, List<ModelOption>> = mapOf(
        Provider.Free to listOf(ModelOption("openai", "GPT-OSS (free)")),
        Provider.Claude to listOf(
            ModelOption("claude-sonnet-5-5", "Claude Sonnet 5.5"),
            ModelOption("claude-opus-5-5", "Claude Opus 5.5"),
            ModelOption("claude-haiku-5-5", "Claude Haiku 5.5"),
        ),
        Provider.Gemini to listOf(
            ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash"),
            ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro"),
        ),
        Provider.ChatGPT to listOf(
            ModelOption("gpt-5", "GPT-5"),
            ModelOption("gpt-5-mini", "GPT-5 mini"),
        ),
    )

    /** Built-in list for [provider]. The Custom API uses the model id typed in Settings. */
    fun of(provider: Provider): List<ModelOption> =
        if (provider == Provider.Custom) {
            val m = CustomApi.manualModel
            if (m.isNotBlank()) listOf(ModelOption(m, m)) else listOf(ModelOption("", "No model yet: add one in Settings"))
        } else {
            byProvider.getValue(provider)
        }
}
