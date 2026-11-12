package com.arin.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.arin.llm.ApiFormat
import com.arin.llm.CustomApi
import com.arin.llm.Provider

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

    var theme: ThemeMode
        get() = prefs.getString("theme", null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
        set(value) = prefs.edit().putString("theme", value.name).apply()

    var showThinking: Boolean
        get() = prefs.getBoolean("show_thinking", true)
        set(value) = prefs.edit().putBoolean("show_thinking", value).apply()

    var webSearch: Boolean
        get() = prefs.getBoolean("web_search", true)
        set(value) = prefs.edit().putBoolean("web_search", value).apply()

    var memoryOn: Boolean
        get() = prefs.getBoolean("memory_on", true)
        set(value) = prefs.edit().putBoolean("memory_on", value).apply()

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

    fun key(provider: Provider): String = keys.getString(slot(provider), "").orEmpty()

    fun setKey(provider: Provider, value: String) = keys.edit().putString(slot(provider), value.trim()).apply()
}
