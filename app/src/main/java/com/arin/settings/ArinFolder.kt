package com.arin.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.arin.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * ARIN-PROTECTED: arin-folder — one folder for everything Arin saves, in Android's protected app folder:
 * Internal storage → Android → data → com.arin.app → files → Arin. Normal file managers can't delete inside it, and
 * Android removes it when Arin is uninstalled. If it goes missing, Arin makes it again. Chats, settings, keys and
 * sign-ins live in Arin's private storage, so nothing important is lost even if this folder were deleted.
 */
object ArinFolder {
    val SUBS = listOf("Builds", "Downloads", "Attachments", "Chats", "Logs", "Temp")

    fun root(c: Context): File = File(c.getExternalFilesDir(null) ?: c.filesDir, "Arin").apply { mkdirs() }
    fun sub(c: Context, name: String): File = File(root(c), name).apply { mkdirs() }
    fun ensure(c: Context) { SUBS.forEach { sub(c, it) } }
    fun pathForPeople(c: Context): String = root(c).absolutePath

    fun size(f: File): Long = if (!f.exists()) 0 else if (f.isFile) f.length() else (f.listFiles() ?: emptyArray()).sumOf { size(it) }

    fun readable(b: Long): String = when {
        b >= 1L shl 30 -> "%.1f GB".format(b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> "%.1f MB".format(b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> "%.0f KB".format(b / 1024.0)
        else -> "$b B"
    }

    /** What a cleanup would delete: (label, files). Never chats, settings, keys, sign-ins, Linux tools or the newest builds. */
    fun junk(c: Context): List<Pair<String, List<File>>> {
        val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
        val hourAgo = System.currentTimeMillis() - 3_600_000L // anything newer may still be in use
        fun kids(d: File?): List<File> = d?.listFiles()?.toList() ?: emptyList()
        val temp = (kids(sub(c, "Temp")) + kids(c.cacheDir) + kids(c.externalCacheDir))
            .filter { it.lastModified() < hourAgo && (it.name != "apks" || it.lastModified() < weekAgo) }.toList()
        val logs = (kids(sub(c, "Logs")) + kids(File(c.filesDir, "logs")))
            .filter { it.lastModified() < weekAgo }.toList()
        // Builds: keep the newest file of each app (by its name without version numbers).
        val builds = sub(c, "Builds").walkTopDown().filter { it.isFile }.toList()
            .groupBy { it.name.replace(Regex("[-_ .]?v?\\d+(\\.\\d+)*"), "").lowercase() }
            .values.flatMap { g -> g.sortedByDescending { it.lastModified() }.drop(1) }
        val vision = kids(File(c.filesDir, "vision-cache")).filter { it.lastModified() < weekAgo }.toList()
        return listOf("Temporary files and caches" to temp, "Logs older than a week" to logs, "Older copies of builds" to builds,
            "Old picture/video readings" to vision).filter { it.second.isNotEmpty() }
    }

    /** Leftovers inside Arin's Linux (finished sessions, caches). Claude Code, Node, Gradle and your files stay. */
    const val LINUX_CLEAN = "rm -rf /root/.arin/h/* /root/.arin/helper-files /root/.arin/helper-run /root/.arin/ccm /tmp/* /root/.cache/pip " +
        "/root/.npm/_cacache /var/cache/apt/archives/*.deb 2>/dev/null; apt-get clean >/dev/null 2>&1; echo ok"

    fun deleteAll(files: List<File>): Long { var n = 0L; files.forEach { n += size(it); it.deleteRecursively() }; return n }

    /** A backup of chats and settings, saved to Download (only when you tap Back up), to restore after reinstalling. */
    suspend fun backup(c: Context): String = withContext(Dispatchers.IO) {
        val name = "Arin-backup-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".zip"
        // MediaStore.Downloads exists from Android 10 (API 29); older phones (Arin runs from Android 8) save the backup in
        // Arin's own Download folder instead.
        var oldFile: File? = null
        val out: java.io.OutputStream = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            newDownload(c, name) ?: return@withContext "Couldn't create the backup file."
        } else {
            val f = File(c.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: c.filesDir, name)
            oldFile = f
            f.outputStream()
        }
        val skip = setOf("linux", "vision-cache", "build-files", "logs")
        out.use { os ->
            ZipOutputStream(os).use { zip ->
                fun add(dir: File, prefix: String) {
                    dir.listFiles().orEmpty().forEach { f ->
                        if (prefix == "files/" && f.name in skip) return@forEach
                        if (f.isDirectory) add(f, "$prefix${f.name}/") else {
                            zip.putNextEntry(ZipEntry("$prefix${f.name}")); f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                    }
                }
                add(c.filesDir, "files/")
                add(File(c.applicationInfo.dataDir, "shared_prefs"), "shared_prefs/")
            }
        }
        oldFile?.let { "Saved to ${it.absolutePath} — keep it to restore after reinstalling." }
            ?: "Saved to Download/$name — keep it to restore after reinstalling."
    }

    /** A new file in the phone's Download folder (Android 10+, through MediaStore). */
    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private fun newDownload(c: Context, name: String): java.io.OutputStream? {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download")
        }
        val uri = c.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return c.contentResolver.openOutputStream(uri)
    }

    /** Restores a backup over the current data. Arin then closes; open it again. */
    suspend fun restore(c: Context, uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            c.contentResolver.openInputStream(uri)!!.use { input ->
                ZipInputStream(input).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val target = when {
                            e.name.startsWith("files/") -> File(c.filesDir, e.name.removePrefix("files/"))
                            e.name.startsWith("shared_prefs/") -> File(File(c.applicationInfo.dataDir, "shared_prefs"), e.name.removePrefix("shared_prefs/"))
                            else -> null
                        }
                        if (target != null && !e.isDirectory && !e.name.contains("..")) {
                            target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) }
                        }
                        e = zip.nextEntry
                    }
                }
            }
            true
        }.getOrDefault(false)
    }
}

private fun open(c: Context, f: File) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(c, c.packageName + ".files", f)
        val type = if (f.name.endsWith(".apk")) "application/vnd.android.package-archive"
            else android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Settings → Storage: sizes, Free up space (shows what goes, deletes only after Confirm), backup and restore. */
@Composable
fun StorageSection(vm: ChatViewModel) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var sizes by remember { mutableStateOf<List<Pair<String, Long>>>(emptyList()) }
    var linuxSize by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    var plan by remember { mutableStateOf<List<Pair<String, List<File>>>?>(null) }
    var oldChats by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    LaunchedEffect(tick) {
        sizes = withContext(Dispatchers.IO) { ArinFolder.SUBS.map { it to ArinFolder.size(ArinFolder.sub(c, it)) } }
        linuxSize = runCatching {
            if (com.arin.terminal.Linux.installed(c)) com.arin.terminal.Shell.run(c, "du -sh / --exclude=/proc --exclude=/sys --exclude=/dev 2>/dev/null | tail -1 | cut -f1", 120_000).stdout.trim() else ""
        }.getOrDefault("")
    }
    val restorePicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = if (ArinFolder.restore(c, uri)) "Restored. Arin will close now — open it again." else "That file couldn't be restored."
            if (status.startsWith("Restored")) { kotlinx.coroutines.delay(2500); android.os.Process.killProcess(android.os.Process.myPid()) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Everything Arin saves is in one protected folder:", style = small)
        Text("Internal storage → Android → data → com.arin.app → files → Arin", style = MaterialTheme.typography.bodyMedium)
        Text("File managers can't delete inside it, Arin remakes it if it goes missing, and Android removes it when you uninstall Arin.",
            style = small, color = dim)
        sizes.forEach { (n, b) -> Row(Modifier.fillMaxWidth()) { Text(n, Modifier.weight(1f), style = small); Text(ArinFolder.readable(b), style = small) } }
        if (linuxSize.isNotBlank()) Row(Modifier.fillMaxWidth()) { Text("Linux (Claude Code, tools)", Modifier.weight(1f), style = small); Text(linuxSize, style = small) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(oldChats, { oldChats = it })
            Text("Also delete conversations older than 30 days (pinned ones stay)", style = small)
        }
        Button(onClick = { scope.launch { plan = withContext(Dispatchers.IO) { ArinFolder.junk(c) } } }) { Text("🧹 Free up space") }
        OutlinedButton(onClick = { scope.launch { status = ArinFolder.backup(c) } }) { Text("Back up chats and settings") }
        OutlinedButton(onClick = { restorePicker.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore a backup") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Custom APIs use the Claude harness (Claude Code's tools)", Modifier.weight(1f), style = small)
            var on by remember { mutableStateOf(com.arin.claudecode.Harness.customOn(c)) }
            Switch(on, { on = it; com.arin.claudecode.Harness.setCustomOn(c, it) })
        }
        if (status.isNotBlank()) Text(status, style = small, color = MaterialTheme.colorScheme.primary)
        // Files: what's in the Arin folder (newest first). Tap to open, install or share.
        val files = remember(tick, sizes) { ArinFolder.root(c).walkTopDown().filter { it.isFile }.sortedByDescending { it.lastModified() }.take(25).toList() }
        if (files.isNotEmpty()) Text("Files in the Arin folder (tap to open)", style = MaterialTheme.typography.titleSmall)
        files.forEach { f ->
            Row(Modifier.fillMaxWidth().clickable { open(c, f) }) {
                Text(f.parentFile?.name + "/" + f.name, Modifier.weight(1f), style = small, maxLines = 1)
                Text(ArinFolder.readable(f.length()), style = small, color = dim)
            }
        }
    }
    plan?.let { p ->
        val cutoff = System.currentTimeMillis() - 30 * 86_400_000L
        val chats = if (oldChats) vm.chats.filter { it.updated < cutoff && !it.pinned } else emptyList()
        val total = p.sumOf { (_, f) -> f.sumOf { ArinFolder.size(it) } }
        AlertDialog(
            onDismissRequest = { plan = null },
            title = { Text("Free up space") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.forEach { (label, files) -> Text("• $label: ${ArinFolder.readable(files.sumOf { ArinFolder.size(it) })}", style = small) }
                    Text("• Finished Claude sessions and caches inside Linux", style = small)
                    if (chats.isNotEmpty()) Text("• ${chats.size} conversation(s) older than 30 days", style = small)
                    Text("About ${ArinFolder.readable(total)} or more will be freed.", style = MaterialTheme.typography.bodyMedium)
                    Text("Kept: your other chats, settings, API keys, Claude accounts, Arin's signing key, Linux and its tools, " +
                        "and the newest build of every app.", style = small, color = dim)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val files = p.flatMap { it.second }
                    plan = null
                    scope.launch {
                        val freed = withContext(Dispatchers.IO) { ArinFolder.deleteAll(files) }
                        runCatching { com.arin.claudecode.Harness.stopAll() }
                        if (com.arin.terminal.Linux.installed(c)) runCatching { com.arin.terminal.Shell.run(c, ArinFolder.LINUX_CLEAN, 180_000) }
                        chats.forEach { vm.delete(it.id) }
                        ArinFolder.ensure(c)
                        status = "Freed ${ArinFolder.readable(freed)}" + (if (chats.isNotEmpty()) " and removed ${chats.size} old conversation(s)" else "") + " ✓"
                        tick++
                    }
                }) { Text("Confirm") }
            },
            dismissButton = { TextButton(onClick = { plan = null }) { Text("Cancel") } },
        )
    }
}
