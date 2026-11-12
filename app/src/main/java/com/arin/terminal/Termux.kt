package com.arin.terminal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs shell commands in Termux (a Linux terminal app for Android) and returns their output.
 * This is how Arin installs and runs tools such as npm packages, Python scripts or git.
 *
 * One-time setup, done by the person:
 * 1. Install Termux from F-Droid or GitHub (the Play Store version is outdated).
 * 2. In Termux, allow other apps to run commands: see [SETUP_COMMAND].
 * 3. In Arin, grant the "Run commands in Termux environment" permission.
 *
 * Uses Termux's RunCommandService: https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
 */
object Termux {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val HOME = "/data/data/com.termux/files/home"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /** Pasted into Termux once, so it accepts commands from Arin. */
    const val SETUP_COMMAND =
        "mkdir -p ~/.termux && grep -qs '^allow-external-apps' ~/.termux/termux.properties || " +
            "echo 'allow-external-apps=true' >> ~/.termux/termux.properties; termux-reload-settings; " +
            "pkg update -y && pkg install -y nodejs-lts python git; echo 'Arin setup done'"

    const val DOWNLOAD_URL = "https://f-droid.org/packages/com.termux/"

    /** What the model is shown at most, per command (start and end are kept). */
    private const val MAX_OUTPUT_FOR_MODEL = 16_000

    data class Result(val stdout: String, val stderr: String, val exitCode: Int?, val error: String?) {
        val ok: Boolean get() = error == null && exitCode == 0

        /** Combined output for showing and for the model. */
        fun combined(): String = buildString {
            if (error != null) append("Error: ").append(error).append('\n')
            if (stdout.isNotBlank()) append(stdout.trimEnd()).append('\n')
            if (stderr.isNotBlank()) append(stderr.trimEnd()).append('\n')
            if (isEmpty()) append("(no output)")
        }.trimEnd()

        fun forModel(): String {
            val all = combined()
            if (all.length <= MAX_OUTPUT_FOR_MODEL) return all
            val head = all.take(4_000)
            val tail = all.takeLast(MAX_OUTPUT_FOR_MODEL - 4_000)
            return "$head\n…(${all.length - MAX_OUTPUT_FOR_MODEL} characters of output skipped)…\n$tail"
        }
    }

    private val counter = AtomicInteger(1000)
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<Result>>()

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Why commands can't run right now, or null when everything is set up. */
    fun problem(context: Context): String? = when {
        !installed(context) -> "Termux isn't installed. Install it from F-Droid ($DOWNLOAD_URL), then set it up in Arin's Settings → Terminal."
        !permitted(context) -> "Arin doesn't have permission to use Termux yet. Open Settings → Terminal and tap \"Allow Arin to use Termux\"."
        else -> null
    }

    /**
     * Runs [command] with bash in Termux's home folder (or [workdir]) and waits for it to finish,
     * at most [timeoutMs]. Login shell, so packages installed with pkg, npm -g or pip are on the PATH.
     */
    suspend fun run(context: Context, command: String, workdir: String = HOME, timeoutMs: Long = 15 * 60_000L): Result {
        problem(context)?.let { return Result("", "", null, it) }
        val id = counter.incrementAndGet()
        val done = CompletableDeferred<Result>()
        waiting[id] = done
        try {
            val back = Intent(context, TermuxResultReceiver::class.java).putExtra(EXTRA_ID, id)
            val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, id, back, flags)
            val intent = Intent("com.termux.RUN_COMMAND").apply {
                setClassName(PACKAGE, "com.termux.app.RunCommandService")
                putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Arin: " + command.take(60))
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
            } catch (e: Exception) {
                return Result(
                    "", "", null,
                    "Couldn't reach Termux (${e.message ?: e.javaClass.simpleName}). Open Termux once, keep Arin open, and try again.",
                )
            }
            return try {
                withTimeout(timeoutMs) { done.await() }
            } catch (e: TimeoutCancellationException) {
                Result(
                    "", "", null,
                    "No answer from Termux after ${timeoutMs / 60_000} min. The command may still be running in Termux, " +
                        "or Termux isn't accepting commands yet: run the setup command from Settings → Terminal in Termux.",
                )
            }
        } finally {
            waiting.remove(id)
        }
    }

    internal fun deliver(id: Int, bundle: Bundle?) {
        val target = waiting[id] ?: return
        if (bundle == null) {
            target.complete(Result("", "", null, "Termux sent back no result."))
            return
        }
        val errMsg = bundle.getString("errmsg")?.takeIf { it.isNotBlank() }
        val exit = if (bundle.containsKey("exitCode")) bundle.getInt("exitCode") else null
        target.complete(
            Result(
                stdout = bundle.getString("stdout").orEmpty(),
                stderr = bundle.getString("stderr").orEmpty(),
                exitCode = exit,
                error = errMsg,
            ),
        )
    }

    internal const val EXTRA_ID = "arin_command_id"
}

/** Receives each command's result from Termux. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(Termux.EXTRA_ID, -1)
        Termux.deliver(id, intent.getBundleExtra("result"))
    }
}
