package com.arin

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a log of crashes and errors on the phone (Logs in the side panel), so they can be copied and
 * sent to get them fixed. The newest crash is also shown once when Arin opens again.
 */
object CrashLog {
    private const val MAX_ENTRIES = 40

    /** One saved log entry. */
    data class Entry(val file: File, val time: Long, val kind: String, val title: String, val text: String)

    private fun lastCrash(context: Context) = File(context.filesDir, "last-crash.txt")
    private fun dir(context: Context) = File(context.filesDir, "logs").apply { mkdirs() }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, error, "thread ${thread.name}", crash = true)
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Android 11+ remembers why the app last stopped. A freeze ("isn't responding"), running out of memory or a crash in
     * native code leave no Java error behind, so read them here when Arin starts and add them to Logs.
     */
    fun checkExitReasons(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
            val seen = prefs.getLong("seen_exit", 0L)
            val am = context.getSystemService(android.app.ActivityManager::class.java)
            val reasons = am.getHistoricalProcessExitReasons(context.packageName, 0, 5).filter { it.timestamp > seen }
            var newest = seen
            for (r in reasons.sortedBy { it.timestamp }) {
                newest = maxOf(newest, r.timestamp)
                val kind = when (r.reason) {
                    android.app.ApplicationExitInfo.REASON_ANR -> "Freeze (not responding)"
                    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "Out of memory"
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Too much battery or CPU"
                    android.app.ApplicationExitInfo.REASON_CRASH -> null // already logged with its full error
                    else -> null
                } ?: continue
                val trace = runCatching { r.traceInputStream?.bufferedReader()?.use { it.readText().take(10_000) } }.getOrNull().orEmpty()
                val details = "When: ${java.util.Date(r.timestamp)}\nDescription: ${r.description ?: "-"}\nMemory used: ${r.pss / 1024} MB\n\n$trace"
                save(context, "Crash", "$kind: ${r.description ?: ""}".take(160), header(context) + "\n" + details)
                if (!lastCrash(context).exists()) lastCrash(context).writeText("$kind\n$details".take(6_000))
            }
            prefs.edit().putLong("seen_exit", newest).apply()
        }
    }

    private fun header(context: Context) =
        "Arin ${ArinApp.versionLabel(context)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}"

    /** Writes [error] down: a crash (the app closed) or an error that was caught. */
    fun record(context: Context, error: Throwable, where: String = "", crash: Boolean = false) {
        runCatching {
            val text = buildString {
                appendLine(header(context))
                if (where.isNotBlank()) appendLine("Where: $where")
                appendLine(error.stackTraceToString().take(12_000))
            }
            val title = (error.javaClass.simpleName + ": " + (error.message ?: "")).take(160)
            save(context, if (crash) "Crash" else "Error", title, text)
            if (crash) lastCrash(context).writeText(text)
        }
    }

    /** Writes down a problem that isn't an exception: a failed build, a failed reply. */
    fun note(context: Context, kind: String, title: String, details: String) {
        runCatching { save(context, kind, title.take(160), header(context) + "\n" + details.take(12_000)) }
    }

    @Synchronized
    private fun save(context: Context, kind: String, title: String, text: String) {
        val d = dir(context)
        val time = System.currentTimeMillis()
        File(d, "$time-${kind.lowercase()}.txt").writeText("$kind\n$title\n$text")
        d.listFiles().orEmpty().sortedByDescending { it.name }.drop(MAX_ENTRIES).forEach { it.delete() }
    }

    /** Every saved entry, newest first. */
    fun entries(context: Context): List<Entry> = dir(context).listFiles().orEmpty().sortedByDescending { it.name }.mapNotNull { f ->
        runCatching {
            val lines = f.readText().split('\n', limit = 3)
            Entry(f, f.name.substringBefore('-').toLongOrNull() ?: f.lastModified(), lines[0], lines.getOrElse(1) { "" }, lines.getOrElse(2) { "" })
        }.getOrNull()
    }

    fun delete(entry: Entry) { entry.file.delete() }

    fun clear(context: Context) {
        dir(context).listFiles().orEmpty().forEach { it.delete() }
        lastCrash(context).delete()
    }

    fun format(e: Entry): String =
        "${e.kind} · ${SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.ENGLISH).format(Date(e.time))}\n${e.title}\n${e.text}"

    /** The newest crash, once (for the message shown when Arin opens). It stays in Logs. */
    fun takeReport(context: Context): String? = runCatching {
        val f = lastCrash(context)
        if (!f.exists()) null else f.readText().also { f.delete() }
    }.getOrNull()

    /** For coroutine scopes: an error inside a background job is logged instead of closing the app. */
    fun handler(context: () -> Context?) = kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
        context()?.let { record(it, e, "background job (kept running)") }
    }
}
