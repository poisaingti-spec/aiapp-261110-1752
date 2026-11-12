package com.aiapp.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.aiapp.llm.CustomEndpoint
import com.aiapp.llm.ProviderId

/** API keys, encrypted with a key held in the Android Keystore. Never leaves the phone. */
class ApiKeys(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "api_keys",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    init {
        CustomEndpoint.baseUrl = customBase()
        CustomEndpoint.manualModel = customModel()
    }

    /** The Custom API keeps one key per base URL, so switching sites keeps each site's key. */
    private fun prefKey(provider: ProviderId): String =
        if (provider == ProviderId.CUSTOM) "CUSTOM@${CustomEndpoint.baseUrl}" else provider.name

    fun get(provider: ProviderId): String = prefs.getString(prefKey(provider), "").orEmpty()

    fun set(provider: ProviderId, key: String) {
        prefs.edit().putString(prefKey(provider), key.trim()).apply()
    }

    fun customBase(): String = prefs.getString("extra_CUSTOM_BASE", null)
        ?.takeIf { it.isNotBlank() } ?: CustomEndpoint.DEFAULT

    fun setCustomBase(url: String) {
        val clean = CustomEndpoint.normalize(url).ifBlank { CustomEndpoint.DEFAULT }
        prefs.edit().putString("extra_CUSTOM_BASE", clean).apply()
        CustomEndpoint.baseUrl = clean
        CustomEndpoint.manualModel = customModel()
    }

    fun customModel(): String = prefs.getString("extra_CUSTOM_MODEL_${CustomEndpoint.baseUrl}", "").orEmpty()

    fun setCustomModel(id: String) {
        prefs.edit().putString("extra_CUSTOM_MODEL_${CustomEndpoint.baseUrl}", id.trim()).apply()
        CustomEndpoint.manualModel = id.trim()
    }

    /** Other secrets, such as the GitHub token Build Mode uses. */
    fun getExtra(name: String): String = prefs.getString("extra_$name", "").orEmpty()

    fun setExtra(name: String, value: String) {
        prefs.edit().putString("extra_$name", value.trim()).apply()
    }
}
