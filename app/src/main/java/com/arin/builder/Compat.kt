package com.arin.builder

import com.arin.data.Attachment
import com.arin.data.Attachments
import com.arin.data.Message
import com.arin.llm.Delta
import com.arin.llm.Llm
import com.arin.llm.LlmException
import com.arin.llm.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/*
 * Connects the app builder (ported from AI App) to Arin's own model code.
 */

/** How hard the builder works: more fix rounds at higher levels. */
enum class Effort(val label: String) { AUTO("Auto"), LOW("Low"), MEDIUM("Medium"), HIGH("High"), MAX("Max") }

/** A failed request with its HTTP status, so the builder can react to 401/403/429. */
class ProviderException(val code: Int, message: String) : Exception(message)

enum class Role { USER, ASSISTANT }

/** One turn of the builder's own conversation with the model. */
data class ChatMessage(val role: Role, val text: String, val attachments: List<Attachment> = emptyList())

/** Stream chunks starting with this character are the model's reasoning, not its answer. */
const val THOUGHT = "\u0001"

/**
 * Streams a reply from [provider] through Arin's [Llm]: answer text as-is, reasoning prefixed with [THOUGHT].
 * Photos and PDFs attached to the messages go to the model like in chat.
 */
fun streamModel(
    provider: Provider,
    apiKey: String,
    model: String,
    messages: List<ChatMessage>,
    system: String,
): Flow<String> = flow {
    val history = messages.map { m -> Message(fromUser = m.role == Role.USER, text = m.text, attachments = m.attachments) }
    val turns = Attachments.toTurns(provider, history)
    Llm.stream(provider, apiKey, model, system, turns).map { d ->
        when (d) {
            is Delta.Text -> d.text
            is Delta.Thinking -> THOUGHT + d.text
        }
    }.collect { emit(it) }
}

private fun LlmException.isTemporary(): Boolean {
    val m = message.orEmpty().lowercase()
    return m.contains("too many requests") || m.contains("having trouble") || m.contains("overloaded") ||
        m.contains("high demand") || m.contains("couldn't reach")
}

/**
 * Runs the request; if the service is busy before any text arrives, waits and tries again (2 s, 5 s, 10 s).
 * [fallback] can name another model for the last try.
 */
fun resilient(
    attempt: (model: String) -> Flow<String>,
    model: String,
    fallback: (suspend () -> String?)? = null,
    onRetry: (String) -> Unit = {},
): Flow<String> = flow {
    val waits = listOf(2_000L, 5_000L, 10_000L)
    var current = model
    for (i in 0..waits.size) {
        var emitted = false
        try {
            attempt(current).collect {
                emitted = true
                emit(it)
            }
            return@flow
        } catch (e: LlmException) {
            if (emitted || !e.isTemporary() || i == waits.size) throw e
            if (i == waits.lastIndex && fallback != null) {
                val other = fallback()
                if (other != null && other != current) {
                    onRetry("$current is busy — switching to $other")
                    current = other
                    continue
                }
            }
            onRetry("Service busy — retrying in ${waits[i] / 1000} s")
            delay(waits[i])
        }
    }
}

/** A different, usually faster model from the same provider, or null. */
suspend fun alternativeModel(provider: Provider, apiKey: String, current: String): String? {
    val ids = runCatching { Llm.models(provider, apiKey).map { it.id } }.getOrNull() ?: return null
    val fast = listOf("flash", "haiku", "mini", "sonnet")
    return ids.firstOrNull { id -> id != current && fast.any { id.contains(it) } }
}

/** True for an uploaded .zip (an Android Studio project to build). */
val Attachment.isZip: Boolean get() = name.endsWith(".zip", ignoreCase = true) || mime == "application/zip"

/** True for files with no readable text (pictures, fonts, sounds…), which the builder can add to the app. */
val Attachment.isBinary: Boolean get() = !hasText

/**
 * Reads a file picked in Build mode. Like chat attachments, but the original file is always kept
 * (in [Attachment.path]) so fonts, sounds, archives and other files can go into the app or be built.
 */
suspend fun importForBuild(
    context: android.content.Context,
    uri: android.net.Uri,
    @Suppress("UNUSED_PARAMETER") onProgress: (Float) -> Unit = {},
): Attachment = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val read = runCatching { Attachments.read(context, uri) }.getOrNull()
    val name = read?.name ?: (uri.lastPathSegment?.substringAfterLast('/') ?: "file")
    if (read != null && read.kind == Attachment.IMAGE) return@withContext read
    val dir = java.io.File(context.filesDir, "build-files").apply { mkdirs() }
    val copy = java.io.File(dir, java.util.UUID.randomUUID().toString() + "_" + name.replace('/', '_'))
    context.contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it, 64 * 1024) } }
        ?: throw IllegalStateException("\"$name\" couldn't be opened.")
    (read ?: Attachment(name = name, kind = Attachment.FILE)).copy(
        path = copy.absolutePath,
        mime = read?.mime?.ifBlank { null } ?: (context.contentResolver.getType(uri) ?: ""),
        size = copy.length(),
    )
}
