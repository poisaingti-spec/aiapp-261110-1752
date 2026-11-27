package com.arin.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arin.llm.ApiFormat
import com.arin.llm.CustomApi
import com.arin.llm.Provider
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

enum class ThemeMode(val label: String) { System("Match phone"), Light("Light"), Dark("Dark") }

/** App settings. API keys are encrypted with a key held in the Android Keystore and never leave the phone. */
class Settings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val keys: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context,
            "api_keys",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Keystore unavailable or its data damaged: start a fresh key store rather than crash.
        context.deleteSharedPreferences("api_keys")
        context.getSharedPreferences("api_keys_fallback", Context.MODE_PRIVATE)
    }

    init {
        CustomApi.baseUrl = customBaseUrl
        CustomApi.format = customFormat
        CustomApi.manualModel = customModel
    }

    // ---- Saved APIs: several custom services to switch between ----

    /** The person's saved APIs (name, base URL, format). Each URL keeps its own key and model. */
    var savedApis: List<SavedApi>
        get() = runCatching {
            kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                .decodeFromString<List<SavedApi>>(prefs.getString("saved_apis", "[]") ?: "[]")
        }.getOrDefault(emptyList())
        set(value) = prefs.edit().putString("saved_apis", kotlinx.serialization.json.Json.encodeToString(value)).apply()

    // ---- Custom API: your own base URL, format, key and model ----

    var customBaseUrl: String
        get() = prefs.getString("custom_base", null)?.takeIf { it.isNotBlank() } ?: CustomApi.DEFAULT_BASE
        set(value) {
            val clean = CustomApi.normalize(value, customFormat).ifBlank { CustomApi.DEFAULT_BASE }
            prefs.edit().putString("custom_base", clean).apply()
            CustomApi.baseUrl = clean
            CustomApi.manualModel = customModel
        }

    var customFormat: ApiFormat
        get() = prefs.getString("custom_format", null)?.let { runCatching { ApiFormat.valueOf(it) }.getOrNull() } ?: ApiFormat.OpenAI
        set(value) {
            prefs.edit().putString("custom_format", value.name).apply()
            CustomApi.format = value
        }

    /** Model id typed for the current base URL. */
    var customModel: String
        get() = prefs.getString("custom_model@$customBaseUrl", "").orEmpty()
        set(value) {
            prefs.edit().putString("custom_model@$customBaseUrl", value.trim()).apply()
            CustomApi.manualModel = value.trim()
        }

    /** Each custom base URL keeps its own key and chosen model. */
    private fun slot(provider: Provider): String =
        if (provider == Provider.Custom) "Custom@$customBaseUrl" else provider.name

    var provider: Provider
        get() = prefs.getString("provider", null)?.let { runCatching { Provider.valueOf(it) }.getOrNull() } ?: Provider.Free
        set(value) = prefs.edit().putString("provider", value.name).apply()

    fun model(provider: Provider): String? = prefs.getString("model_${slot(provider)}", null)

    fun setModel(provider: Provider, id: String) = prefs.edit().putString("model_${slot(provider)}", id).apply()

    /** The model each tab uses ("provider|model|apiId"): changing it in one tab never changes another. */
    fun tabSelection(tab: String): String? = prefs.getString("tabsel_$tab", null)
    fun setTabSelection(tab: String, v: String) = prefs.edit().putString("tabsel_$tab", v).apply()

    var theme: ThemeMode
        get() = prefs.getString("theme", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
        set(value) = prefs.edit().putString("theme", value.name).apply()

    var showThinking: Boolean
        get() = prefs.getBoolean("show_thinking", true)
        set(value) = prefs.edit().putBoolean("show_thinking", value).apply()

    var webSearch: Boolean
        get() = prefs.getBoolean("web_search", true)
        set(value) = prefs.edit().putBoolean("web_search", value).apply()

    /** MCP servers (JSON list), see [com.arin.mcp.Mcp]. */
    var mcpServers: List<com.arin.mcp.McpServer>
        get() = com.arin.mcp.Mcp.load(prefs.getString("mcp_servers", null))
        set(value) = prefs.edit().putString("mcp_servers", com.arin.mcp.Mcp.save(value)).apply()

    /** Let the model run commands in Termux. */
    var terminalOn: Boolean
        get() = prefs.getBoolean("terminal_on", false)
        set(value) = prefs.edit().putBoolean("terminal_on", value).apply()

    /** Ask before each command (on by default). */
    var terminalAsk: Boolean
        get() = prefs.getBoolean("terminal_ask", true)
        set(value) = prefs.edit().putBoolean("terminal_ask", value).apply()

    /** Custom instructions: who the person is, and how they want answers. */
    var aboutYou: String
        get() = prefs.getString("about_you", "") ?: ""
        set(value) = prefs.edit().putString("about_you", value).apply()

    var howToAnswer: String
        get() = prefs.getString("how_to_answer", "") ?: ""
        set(value) = prefs.edit().putString("how_to_answer", value).apply()

    /** Normal, Concise, Detailed or Formal. */
    var replyStyle: String
        get() = prefs.getString("reply_style", "Normal") ?: "Normal"
        set(value) = prefs.edit().putString("reply_style", value).apply()

    var memoryOn: Boolean
        get() = prefs.getBoolean("memory_on", true)
        set(value) = prefs.edit().putBoolean("memory_on", value).apply()

    /** After building an app, open it on GitHub's Android emulator and fix crashes. */
    var testApps: Boolean
        get() = prefs.getBoolean("test_apps", true)
        set(value) = prefs.edit().putBoolean("test_apps", value).apply()

    /** Save credits: send the model less of the old conversation and smaller tool outputs, keeping recent context. */
    var saveCredits: Boolean
        get() = prefs.getBoolean("save_credits", true)
        set(value) = prefs.edit().putBoolean("save_credits", value).apply()

    /** Think less: low reasoning effort (DeepSeek: thinking off), since thinking tokens are billed as output. */
    var thinkLess: Boolean
        get() = prefs.getBoolean("think_less", false)
        set(value) = prefs.edit().putBoolean("think_less", value).apply()

    var notifyOnReply: Boolean
        get() = prefs.getBoolean("notify_on_reply", true)
        set(value) = prefs.edit().putBoolean("notify_on_reply", value).apply()

    /** Whether Arin has already asked Android for permission to show notifications. */
    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(value) = prefs.edit().putBoolean("asked_notifications", value).apply()

    var voiceRate: Float
        get() = prefs.getFloat("voice_rate", 1.0f)
        set(value) = prefs.edit().putFloat("voice_rate", value).apply()

    /** Use a natural ChatGPT-style voice over the internet, falling back to the phone's voice when offline. */
    var voiceNatural: Boolean
        get() = prefs.getBoolean("voice_natural", true)
        set(value) = prefs.edit().putBoolean("voice_natural", value).apply()

    /** Which natural voice: nova, shimmer, alloy, echo, fable or onyx. */
    var voiceName: String
        get() = prefs.getString("voice_name", "nova") ?: "nova"
        set(value) = prefs.edit().putString("voice_name", value.trim()).apply()

    /** Voice engine: "free" (keyless) or "openai" (the real ChatGPT voice, uses [voiceKey]). */
    var voiceEngine: String
        get() = prefs.getString("voice_engine", "free") ?: "free"
        set(value) = prefs.edit().putString("voice_engine", value).apply()

    /** OpenAI key used only for the ChatGPT voice engine (kept with the other encrypted keys). */
    var voiceKey: String
        get() = secret("voice_openai")
        set(value) = setSecret("voice_openai", value)

    /** Let the person interrupt by talking while Arin is speaking (barge-in). Off by default:
     *  on many phones the mic hears Arin's own voice and would cut replies short. */
    var voiceBargeIn: Boolean
        get() = prefs.getBoolean("voice_barge_in", false)
        set(value) = prefs.edit().putBoolean("voice_barge_in", value).apply()

    // ---- Claude Code backend: the person's Pro login, or their own custom (Anthropic-compatible) API ----
    var ccBackend: String
        get() = prefs.getString("cc_backend", "pro") ?: "pro"
        set(value) = prefs.edit().putString("cc_backend", value).apply()

    /** Anthropic-compatible base URL, e.g. https://api.deepseek.com/anthropic */
    var ccCustomBase: String
        get() = prefs.getString("cc_custom_base", "") ?: ""
        set(value) = prefs.edit().putString("cc_custom_base", value.trim()).apply()

    var ccCustomModel: String
        get() = prefs.getString("cc_custom_model", "") ?: ""
        set(value) = prefs.edit().putString("cc_custom_model", value.trim()).apply()

    var ccCustomKey: String
        get() = secret("cc_custom")
        set(value) = setSecret("cc_custom", value)

    /** "anthropic" (used directly) or "openai" (through Arin's bridge), found when connecting. */
    var ccCustomKind: String
        get() = prefs.getString("cc_custom_kind", "anthropic") ?: "anthropic"
        set(value) = prefs.edit().putString("cc_custom_kind", value).apply()

    /** Model for the Claude Pro login (Claude Code's names: sonnet, opus, haiku). */
    var ccProModel: String
        get() = prefs.getString("cc_pro_model", "sonnet") ?: "sonnet"
        set(value) = prefs.edit().putString("cc_pro_model", value).apply()

    /** Models the Claude Code API listed when connecting (kept only for Claude Code, not the chat tab). */
    var ccCustomModels: List<String>
        get() = (prefs.getString("cc_custom_models", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(value) = prefs.edit().putString("cc_custom_models", value.joinToString("\n")).apply()

    // ---- Movie Maker (film studio) ----

    /** The person's own Hugging Face token: free ZeroGPU quota is counted against their account. Encrypted. */
    var movieHfToken: String
        get() = secret("movie_hf")
        set(value) = setSecret("movie_hf", value)

    /** Which Hugging Face Space does image-to-video. Spaces come and go, so it can be changed. */
    var movieSpace: String
        get() = prefs.getString("movie_space", null)?.takeIf { it.isNotBlank() } ?: com.arin.movie.MovieMaker.DEFAULT_SPACE
        set(value) = prefs.edit().putString("movie_space", value.trim()).apply()

    var movieSubtitles: Boolean
        get() = prefs.getBoolean("movie_subs", true)
        set(value) = prefs.edit().putBoolean("movie_subs", value).apply()

    /** Other secrets kept with the API keys, such as the GitHub token Build mode uses. */
    fun secret(name: String): String = keys.getString("extra_$name", "").orEmpty()

    fun setSecret(name: String, value: String) = keys.edit().putString("extra_$name", value.trim()).apply()

    fun key(provider: Provider): String = keys.getString(slot(provider), "").orEmpty()

    /** Every saved secret value (API keys, tokens) — used only to redact them from anything sent to another model. */
    fun secretValues(): List<String> = runCatching { keys.all.values.mapNotNull { (it as? String)?.trim()?.takeIf { v -> v.length >= 8 } } }.getOrDefault(emptyList())

    /** Key and model for a saved API's address, without switching to it. */
    fun keyFor(baseUrl: String): String = keys.getString("Custom@$baseUrl", "").orEmpty()

    fun setKeyFor(baseUrl: String, key: String) = keys.edit().putString("Custom@$baseUrl", key.trim()).apply()

    fun setModelFor(baseUrl: String, model: String) = prefs.edit()
        .putString("custom_model@$baseUrl", model.trim())
        .apply { if (model.isNotBlank()) putString("model_Custom@$baseUrl", model.trim()) }
        .apply()

    fun setKey(provider: Provider, value: String) = keys.edit().putString(slot(provider), value.trim()).apply()

    /** Key saved for one API (falls back to the key saved for its address, from older versions). */
    fun apiKey(api: SavedApi): String =
        keys.getString("Api@${api.slotId}", null)?.takeIf { it.isNotBlank() } ?: keyFor(api.baseUrl)

    fun setApiKey(api: SavedApi, key: String) = keys.edit().putString("Api@${api.slotId}", key.trim()).apply()

    /** Which saved API is in use (several may share one base URL). */
    var activeApiId: String
        get() = prefs.getString("active_api_id", "").orEmpty()
        set(value) = prefs.edit().putString("active_api_id", value).apply()
}


/** A custom API the person saved, to switch to with one tap. */
@kotlinx.serialization.Serializable
data class SavedApi(
    val name: String,
    val baseUrl: String,
    val format: String = "OpenAI",
    val model: String = "",
    /** Unique per saved API, so several can share one base URL (different keys or models). Blank = saved before ids. */
    val id: String = "",
    /** Part of the keyless/keyed "Free" pool (added in the Free tokens tab): routed under Free with fallover. */
    val free: Boolean = false,
) {
    /** Stable key for storage: the id, or the address for APIs saved by older versions. */
    val slotId: String get() = id.ifBlank { "url:$baseUrl" }
}
