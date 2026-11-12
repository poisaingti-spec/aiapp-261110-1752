package com.arin.builder

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.arin.terminal.Linux
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compiles a project into an APK in the built-in Linux (JDK, Gradle, Android SDK installed from
 * Settings → Linux). Build mode uses it to check and fix each version on the phone before GitHub:
 * errors come back in seconds or minutes instead of a GitHub round trip.
 */
object LocalBuild {

    sealed class Outcome {
        /** Compiled. [apkPath] is a copy in the phone's Download/Arin folder (or app storage). */
        data class Ok(val apkPath: String, val size: Long) : Outcome()
        /** The code didn't compile; [log] holds the errors. */
        data class Errors(val log: String) : Outcome()
        /** The phone couldn't run the build (memory, download, tools); GitHub builds it instead. */
        data class Skipped(val reason: String) : Outcome()
    }

    fun available(context: Context?): Boolean = context != null && Linux.androidBuildReady(context)

    private fun dir(context: Context, slug: String) = File(Linux.rootfs(context), "root/projects/$slug")

    /** Whether a full copy of this project is already on the phone. */
    fun hasCopy(context: Context, slug: String) = File(dir(context, slug), "settings.gradle.kts").exists()

    /** Writes (or, for null, deletes) project files in the phone copy. */
    suspend fun write(context: Context, slug: String, files: Map<String, ByteArray?>) = withContext(Dispatchers.IO) {
        val root = dir(context, slug).apply { mkdirs() }
        files.forEach { (path, bytes) ->
            val f = File(root, path)
            if (!f.canonicalPath.startsWith(root.canonicalPath)) return@forEach
            if (bytes == null) f.delete() else {
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            }
        }
    }

    suspend fun writeFiles(context: Context, slug: String, files: Map<String, File>) = withContext(Dispatchers.IO) {
        val root = dir(context, slug).apply { mkdirs() }
        files.forEach { (path, src) ->
            val f = File(root, path)
            if (!f.canonicalPath.startsWith(root.canonicalPath) || !src.exists()) return@forEach
            f.parentFile?.mkdirs()
            src.copyTo(f, overwrite = true)
        }
    }

    private val codeError = Regex("""(^e: |error:|Unresolved reference|AAPT|resource .* not found|Android resource linking failed|Manifest merger failed|Compilation error)""", RegexOption.IGNORE_CASE)
    private val envError = Regex("""(OutOfMemoryError|Java heap space|Could not resolve|Could not GET|Could not download|UnknownHost|timed out|Connection reset|daemon disappeared|Killed|Bad system call|cannot execute binary|Exec format error|No such file or directory: .*aapt2|SDK location not found|licen[cs]e)""", RegexOption.IGNORE_CASE)

    /** Runs the build and sorts the result into compiled / code errors / couldn't build here. */
    suspend fun build(context: Context, slug: String, appName: String, onLine: (String) -> Unit): Outcome {
        val r = Linux.run(context, "arin-build /root/projects/$slug", timeoutMs = 40 * 60_000L) { line ->
            if (line.startsWith("> Task") || line.startsWith("BUILD")) onLine(line.take(120))
        }
        val out = r.combined()
        if (r.exitCode == 0) {
            val path = Regex("""ARIN_APK=(\S+)""").find(out)?.groupValues?.get(1)
            val apk = path?.let { File(Linux.rootfs(context), it.removePrefix("/")) }
            if (apk == null || !apk.exists()) return Outcome.Skipped("The build said it worked, but no APK was found.")
            return withContext(Dispatchers.IO) { save(context, apk, appName) }
        }
        if (r.error != null) return Outcome.Skipped(r.error)
        val errors = GitHubClient.extractErrors(out.substringAfter("---- errors ----", out).lines())
            .ifBlank { out.lines().filter { it.isNotBlank() }.takeLast(30).joinToString("\n") }
        return if (codeError.containsMatchIn(errors) && !envError.containsMatchIn(errors.lines().take(6).joinToString("\n"))) {
            Outcome.Errors(errors.take(6_000))
        } else {
            Outcome.Skipped(errors.lines().firstOrNull { it.isNotBlank() && !it.startsWith("*") }?.take(200) ?: "Unknown problem")
        }
    }

    /** Copies the APK to Download/Arin so it can be installed and shared. */
    private fun save(context: Context, apk: File, appName: String): Outcome {
        val name = appName.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "app" } + "-phone-build.apk"
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Arin")
            }
            val uri = runCatching { context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { out -> apk.inputStream().use { it.copyTo(out) } }
                return Outcome.Ok("/storage/emulated/0/Download/Arin/$name", apk.length())
            }
        }
        val f = File(context.getExternalFilesDir(null), name)
        apk.copyTo(f, overwrite = true)
        return Outcome.Ok(f.absolutePath, apk.length())
    }
}
