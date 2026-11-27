package com.arin.security

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Evidence handling (no AI): a picked file is copied into Arin's private evidence store, hashed (SHA-256, SHA-1, MD5),
 * its type and metadata recorded, and every action written to a chain-of-custody log. "Verify" re-hashes the stored copy
 * to prove it hasn't changed.
 */
object Forensics {
    private fun dir(c: Context) = File(c.filesDir, "security/evidence").apply { mkdirs() }
    private fun log(c: Context) = File(dir(c), "custody.jsonl")

    private fun hashes(f: File): Map<String, String> {
        val algs = listOf("SHA-256", "SHA-1", "MD5").associateWith { MessageDigest.getInstance(it) }
        f.inputStream().use { i -> val b = ByteArray(1 shl 16); while (true) { val n = i.read(b); if (n < 0) break; algs.values.forEach { it.update(b, 0, n) } } }
        return algs.mapValues { (_, d) -> d.digest().joinToString("") { "%02x".format(it) } }
    }

    private fun type(f: File): String {
        val h = f.inputStream().use { i -> ByteArray(8).also { i.read(it) } }
        fun s(vararg b: Int) = b.indices.all { h.getOrNull(it)?.toInt()?.and(0xFF) == b[it] }
        return when {
            s(0x50, 0x4B, 0x03, 0x04) -> if (f.name.endsWith(".apk", true)) "APK (zip)" else "ZIP container (zip/apk/docx/jar)"
            s(0x7F, 0x45, 0x4C, 0x46) -> "ELF executable / native library"
            s(0x64, 0x65, 0x78, 0x0A) -> "Android DEX code"
            s(0x25, 0x50, 0x44, 0x46) -> "PDF"
            s(0x89, 0x50, 0x4E, 0x47) -> "PNG image"
            s(0xFF, 0xD8, 0xFF) -> "JPEG image"
            s(0x4D, 0x5A) -> "Windows executable (PE)"
            s(0x1F, 0x8B) -> "gzip"
            s(0x53, 0x51, 0x4C, 0x69) -> "SQLite database"
            else -> "unknown / text"
        }
    }

    private fun custody(c: Context, action: String, item: String, sha: String, note: String = "") =
        log(c).appendText(JSONObject().put("time", java.util.Date().toString()).put("action", action).put("item", item).put("sha256", sha).put("note", note).toString() + "\n")

    /** Collects [uri] as evidence; returns a Markdown record. */
    fun collect(c: Context, uri: Uri, onStep: (String) -> Unit = {}): String {
        var name = "evidence"; var size = -1L
        c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) ?: name }
                cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cur.getLong(it) }
            }
        }
        onStep("Copying $name into the evidence store")
        val stamp = System.currentTimeMillis()
        val f = File(dir(c), "$stamp-" + name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        c.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        onStep("Hashing")
        val h = hashes(f)
        custody(c, "collected", f.name, h.getValue("SHA-256"), "from $uri")
        return buildString {
            appendLine("# Evidence record — $name")
            appendLine("- Collected: ${java.util.Date(stamp)} (stored privately as `${f.name}`)")
            appendLine("- Size: ${f.length()} bytes" + if (size >= 0 && size != f.length()) " (source said $size)" else "")
            appendLine("- Type: ${type(f)}")
            h.forEach { (k, v) -> appendLine("- $k: `$v`") }
            appendLine("\n_Chain of custody: logged. Tap \"Verify evidence\" any time to re-hash the stored copies and prove nothing changed._")
            if (type(f).startsWith("APK")) appendLine("\n_Next step: analyse it in the APK tab._")
        }
    }

    /** Re-hashes every stored item and compares with the custody log. */
    fun verifyAll(c: Context): String {
        val lines = runCatching { log(c).readLines() }.getOrDefault(emptyList()).mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val collected = lines.filter { it.optString("action") == "collected" }
        if (collected.isEmpty()) return "No evidence collected yet."
        return buildString {
            appendLine("# Evidence integrity check — ${java.util.Date()}")
            collected.forEach { e ->
                val f = File(dir(c), e.optString("item"))
                val now = if (f.exists()) hashes(f).getValue("SHA-256") else null
                val ok = now == e.optString("sha256")
                appendLine("- ${if (ok) "✅ unchanged" else if (now == null) "❌ MISSING" else "❌ CHANGED"} — ${e.optString("item")}")
                custody(c, "verified", e.optString("item"), now ?: "missing", if (ok) "match" else "MISMATCH")
            }
            appendLine("\n## Custody log (latest 30)")
            runCatching { log(c).readLines() }.getOrDefault(emptyList()).takeLast(30).forEach { l ->
                runCatching { JSONObject(l) }.getOrNull()?.let { appendLine("- ${it.optString("time")} · ${it.optString("action")} · ${it.optString("item")} · ${it.optString("sha256").take(16)}…") }
            }
        }
    }
}
