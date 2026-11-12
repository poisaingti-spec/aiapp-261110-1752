package com.arin.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** HTTP helpers. Every call is cancelled the moment its coroutine is (for example when Stop is pressed). */
object Http {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val jsonType = "application/json".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
            client.newCall(request).await().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw LlmException(errorMessage(resp.code, body))
                json.parseToJsonElement(body)
            }
        }

    /** POSTs [body] and emits the payload of each server-sent-event `data:` line. */
    fun sse(url: String, headers: Map<String, String>, body: String): Flow<String> = callbackFlow {
        val request = Request.Builder().url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Accept", "text/event-stream")
            .post(body.toRequestBody(jsonType))
            .build()
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                close(if (call.isCanceled()) null else LlmException(networkMessage(e)))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        close(LlmException(errorMessage(resp.code, resp.body?.string().orEmpty())))
                        return
                    }
                    try {
                        val source = resp.body!!.source()
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isNotEmpty()) trySendBlocking(data)
                        }
                        close()
                    } catch (e: IOException) {
                        close(if (call.isCanceled()) null else LlmException(networkMessage(e)))
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }

    fun parseObject(data: String): JsonObject? = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()

    private fun networkMessage(e: IOException): String =
        "Couldn't reach the server. Check your internet connection and try again." +
            (e.message?.let { "\n($it)" } ?: "")

    fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            when (val err = root["error"]) {
                is JsonObject -> (err["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> err.contentOrNull
                else -> null
            }
        }.getOrNull()
        val hint = when (code) {
            401, 403 -> "The API key was rejected. Check it in Settings."
            404 -> "That model or address isn't available. Pick another model, or check the base URL in Settings → Custom API."
            429 -> "Too many requests right now. Wait a moment and try again."
            in 500..599 -> "The service is having trouble. Try again shortly."
            else -> "Request failed (HTTP $code)."
        }
        return if (detail.isNullOrBlank()) hint else "$hint\n$detail"
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(LlmException(networkMessage(e))))
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
            }
        })
    }
}
