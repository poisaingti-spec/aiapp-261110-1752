package com.arin

import android.content.Context
import android.os.Build
import java.io.File

/**
 * Saves the details of a crash to a file, so the next time Arin opens it can show what went wrong
 * (and the person can copy it and send it to get the crash fixed).
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, error, "thread ${thread.name}")
            previous?.uncaughtException(thread, error)
        }
    }

    /** Writes [error] down; also used for errors that were caught but should never happen. */
    fun record(context: Context, error: Throwable, where: String = "") {
        runCatching {
            val text = buildString {
                appendLine("Arin ${ArinApp.versionLabel(context)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
                if (where.isNotBlank()) appendLine("Where: $where")
                appendLine(error.stackTraceToString().take(12_000))
            }
            file(context).writeText(text)
        }
    }

    /** The saved report, once: it's deleted after it has been read. */
    fun takeReport(context: Context): String? = runCatching {
        val f = file(context)
        if (!f.exists()) null else f.readText().also { f.delete() }
    }.getOrNull()

    /** For coroutine scopes: an error inside a background job is recorded instead of closing the app. */
    fun handler(context: () -> Context?) = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        context()?.let { record(it, e, "background job (kept running)") }
    }
}
