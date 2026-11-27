package com.arin.selffix

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * After a build: puts the APK and the source code on a free file host and gives a direct download link. Hosts are tried
 * one after another — if one fails or its link doesn't really download, the next is tried — so a link always comes back
 * when any host works. The files are then downloaded to the phone (Download/Arin) automatically.
 */
object FileShare {
    data class Shared(val name: String, val host: String, val url: String, val keeps: String = "")

    private val http = com.arin.builder.Http.client.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.MINUTES).readTimeout(5, TimeUnit.MINUTES).build()

    /** A free host: its name, the largest file it takes, and how to upload (returns the link, or null). */
    private class Host(val name: String, val keeps: String, val maxBytes: Long, val upload: (File, RequestBody) -> String?)

    private fun part(field: String, f: File, body: RequestBody) =
        MultipartBody.Part.createFormData(field, f.name, body)

    private fun post(url: String, build: MultipartBody.Builder.() -> Unit): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply(build).build()
        val req = Request.Builder().url(url).header("User-Agent", "Arin-Android").post(body).build()
        return http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string()?.trim() else null }
    }

    // Longest-lasting links first. (file.io isn't used: its link dies after one download, and the phone downloads it.)
    private val hosts = listOf(
        Host("catbox.moe", "kept permanently", 200L shl 20) { f, b ->
            post("https://catbox.moe/user/api.php") { addFormDataPart("reqtype", "fileupload"); addPart(part("fileToUpload", f, b)) }
                ?.takeIf { it.startsWith("https://") }
        },
        Host("0x0.st", "kept 30 days or more", 512L shl 20) { f, b -> post("https://0x0.st") { addPart(part("file", f, b)) }?.takeIf { it.startsWith("http") } },
        Host("litterbox.catbox.moe", "kept 72 hours", 1L shl 30) { f, b ->
            post("https://litterbox.catbox.moe/resources/internals/api.php") {
                addFormDataPart("reqtype", "fileupload"); addFormDataPart("time", "72h"); addPart(part("fileToUpload", f, b))
            }?.takeIf { it.startsWith("https://") }
        },
        Host("uguu.se", "kept 3 hours", 128L shl 20) { f, b ->
            post("https://uguu.se/upload") { addPart(part("files[]", f, b)) }?.let { t ->
                JSONObject(t).optJSONArray("files")?.optJSONObject(0)?.optString("url")
            }
        },
        Host("tmpfiles.org", "kept 1 hour", 100L shl 20) { f, b ->
            post("https://tmpfiles.org/api/v1/upload") { addPart(part("file", f, b)) }?.let { t ->
                // tmpfiles.org/123/name → the direct link is tmpfiles.org/dl/123/name
                JSONObject(t).optJSONObject("data")?.optString("url")?.replace("tmpfiles.org/", "tmpfiles.org/dl/")?.replace("http://", "https://")
            }
        },
    )

    /** A request body that reports upload progress. */
    private fun counting(f: File, label: String): RequestBody = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = f.length()
        override fun writeTo(sink: okio.BufferedSink) {
            val total = f.length()
            var sent = 0L
            var last = 0L
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sink.write(buf, 0, n)
                    sent += n
                    val now = System.currentTimeMillis()
                    if (now - last > 500 || sent == total) { last = now; InstallProgress.bytes("Uploading $label", sent, total) }
                }
            }
        }
    }

    /** True when [url] really serves a file (not an error page). */
    private fun works(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "Arin-Android").get().header("Range", "bytes=0-0").build())
            .execute().use { r -> r.isSuccessful && !r.header("Content-Type").orEmpty().startsWith("text/html") }
    }.getOrDefault(false)

    /** Uploads [f] to the first free host that works; [onStep] says what it's doing. Null if every host failed. */
    suspend fun upload(f: File, label: String, onStep: (String) -> Unit): Shared? = withContext(Dispatchers.IO) {
        for (h in hosts) {
            if (f.length() > h.maxBytes) continue
            onStep("Uploading the $label to ${h.name} (${"%.1f".format(f.length() / 1e6)} MB)…")
            val url = runCatching { h.upload(f, counting(f, label)) }.getOrNull()
            if (url != null && works(url)) {
                onStep("Uploaded the $label to ${h.name} ✓")
                return@withContext Shared(f.name, h.name, url, h.keeps)
            }
            onStep("${h.name} didn't work for the $label — trying the next free site")
        }
        null
    }

    /** Downloads [url] into Arin's folder (Builds) with Android's downloader (shows its own notification). */
    fun downloadToPhone(context: Context, url: String, name: String) {
        runCatching {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
            val req = android.app.DownloadManager.Request(android.net.Uri.parse(url))
                .setTitle(name)
                .setDescription("Saved by Arin")
                .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                // Into Arin's own protected folder (Android → data → com.arin.app → files → Arin → Builds), not scattered.
                .setDestinationInExternalFilesDir(context, null, "Arin/Builds/$name")
            if (name.endsWith(".apk")) req.setMimeType("application/vnd.android.package-archive")
            dm.enqueue(req)
        }
    }

    /** The source of a GitHub repository as a zip, downloaded to the phone (works for private repositories). */
    suspend fun repoZip(context: Context, token: String, owner: String, repo: String, ref: String, name: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val out = File(File(context.cacheDir, "share").apply { mkdirs() }, "$name-source.zip")
            val req = Request.Builder().url("https://api.github.com/repos/$owner/$repo/zipball/$ref")
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }.build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body!!.byteStream().use { i -> out.outputStream().use { i.copyTo(it) } }
                out
            }
        }.getOrNull()
    }

    /** The APK at [url], downloaded to the phone's cache for uploading. */
    suspend fun fetch(context: Context, url: String, name: String): File? = withContext(Dispatchers.IO) {
        runCatching {
            val out = File(File(context.cacheDir, "share").apply { mkdirs() }, name)
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) return@use null
                r.body!!.byteStream().use { i -> out.outputStream().use { i.copyTo(it) } }
                out
            }
        }.getOrNull()
    }
}
