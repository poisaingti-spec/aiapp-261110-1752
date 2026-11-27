package com.arin.selffix

import android.content.Context
import com.arin.builder.ChatMessage
import com.arin.builder.Role
import com.arin.builder.THOUGHT
import com.arin.builder.streamModel
import com.arin.data.Attachment
import com.arin.data.Message
import com.arin.llm.Provider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lets EVERY model read images and videos, even models that can't see (DeepSeek, most Custom API models, free models).
 * For such a model, Arin looks at each picture or video itself and gives the model a full written account instead:
 *  - every piece of text in it, read on the phone (works offline, perfect for screenshots and error messages);
 *  - what is in it, described by a model that can see (your Gemini / ChatGPT / Claude key, a saved Custom API whose
 *    model can see, or your Claude Code login) — and, if none is available, the objects recognised on the phone;
 *  - for a video: its length and size, and frames from across the whole clip, each read and described.
 * Descriptions are kept, so the same file is never looked at twice. Models that can see still get the image itself.
 */
object VisionBridge {
    private val visionNames = Regex(
        """(?i)(vision|[-_/]vl\b|\bvl[-_]|-vl-|gpt-4o|gpt-4\.1|gpt-5|gpt-6|\bo3\b|\bo4|gemini|claude|llava|pixtral|qwen[\w.\-]*vl|""" +
            """glm-4\.?\dv|glm-4v|minicpm-v|internvl|llama-4|llama-3\.2-\d+b-vision|grok-\d[\w.\-]*vision|grok-4|kimi-vl|""" +
            """phi-4-multimodal|gemma-3|mistral-medium-3|mistral-small-3\.[12]|omni)""",
    )

    /** True if [model] on [provider] can look at images itself. */
    fun canSee(provider: Provider, model: String): Boolean = when (provider) {
        Provider.Claude, Provider.Gemini, Provider.ChatGPT, Provider.ClaudeCode -> true
        Provider.Free -> false
        Provider.Custom -> visionNames.containsMatchIn(model)
    }

    fun isVideo(a: Attachment): Boolean =
        a.mime.startsWith("video/") || a.name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v")

    private fun isImage(a: Attachment) = a.kind == Attachment.IMAGE ||
        a.mime.startsWith("image/") && a.mime != "image/svg+xml" ||
        a.name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic")

    /**
     * A chat's messages ready for [model]: for a model that can't see, each picture and video is replaced by its full
     * written account; videos are always described (no model watches a video file sent by name).
     */
    suspend fun prepare(context: Context, provider: Provider, model: String, messages: List<Message>, onStatus: (String) -> Unit = {}): List<Message> {
        val sees = canSee(provider, model)
        if (messages.none { m -> m.attachments.any { isVideo(it) || (!sees && isImage(it)) } }) return messages
        return messages.map { m ->
            if (!m.fromUser || m.attachments.none { isVideo(it) || (!sees && isImage(it)) }) m
            else {
                val (keep, text) = prepareFiles(context, provider, model, m.attachments, onStatus)
                m.copy(text = m.text + text, attachments = keep)
            }
        }
    }

    /** The same for one message's files: the files the model still gets, and the text to add. */
    suspend fun prepareFiles(
        context: Context,
        provider: Provider,
        model: String,
        files: List<Attachment>,
        onStatus: (String) -> Unit = {},
    ): Pair<List<Attachment>, String> {
        val sees = canSee(provider, model)
        val keep = mutableListOf<Attachment>()
        val add = StringBuilder()
        for (a in files) {
            when {
                isVideo(a) -> {
                    onStatus("Watching the video ${a.name}")
                    add.append("\n\n[Video ${a.name} — watched for you by Arin, since you can't open video files:\n")
                        .append(describe(context, a, onStatus)).append("\n]")
                    keep += a
                }
                !sees && isImage(a) -> {
                    onStatus("Looking at ${a.name}")
                    add.append("\n\n[Picture ${a.name} — looked at for you by Arin, since this model can't see images. ")
                        .append("Treat this as seeing it yourself:\n").append(describe(context, a, onStatus)).append("\n]")
                }
                else -> keep += a
            }
        }
        return keep to add.toString()
    }

    // ---- looking at a file ----

    private fun cacheFile(context: Context, a: Attachment): java.io.File {
        val key = (a.path + "|" + a.size + "|" + a.name).hashCode().toUInt().toString(16)
        return java.io.File(java.io.File(context.filesDir, "vision-cache").apply { mkdirs() }, "$key.txt")
    }

    /** The full written account of a picture or video (cached). */
    suspend fun describe(context: Context, a: Attachment, onStatus: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val cache = cacheFile(context, a)
        if (cache.exists() && cache.length() > 0) return@withContext cache.readText()
        val text = runCatching { if (isVideo(a)) describeVideo(context, a, onStatus) else describeImage(context, a, onStatus) }
            .getOrElse { "Couldn't look at ${a.name}: ${it.message}" }
        runCatching { cache.writeText(text) }
        text
    }

    /** Owner's strict rule: their files go only to the main model (see [eye]). */
    private const val OWNER_FILES_ONLY_TO_MAIN = true

    private suspend fun describeImage(context: Context, a: Attachment, onStatus: (String) -> Unit): String {
        val f = java.io.File(a.path)
        if (!f.exists()) return "(The file is no longer on the phone.)"
        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)
        val sb = StringBuilder()
        if (bmp != null) sb.append("Size: ${bmp.width}×${bmp.height} pixels.\n")
        onStatus("Reading the text in ${a.name}")
        val ocr = bmp?.let { readText(it) }.orEmpty()
        sb.append(if (ocr.isNotBlank()) "ALL TEXT IN THE PICTURE (read exactly, line by line):\n$ocr\n" else "No text in the picture.\n")
        onStatus("Describing ${a.name}")
        val seen = eye(context, listOf(imageAttachment(f, a.mime)),
            "Describe this image completely for someone who can't see it: what it is (photo, screenshot of an app or a " +
                "website, error message, chart, document…), everything visible, layout, colours, buttons, icons, any " +
                "people or objects, and quote ALL text exactly. If it is a screenshot of an error, say which app and what " +
                "the error says.")
        if (!seen.isNullOrBlank()) sb.append("WHAT IT SHOWS:\n").append(seen.trim()).append('\n')
        else {
            val labels = bmp?.let { labels(it) }.orEmpty()
            if (labels.isNotBlank()) sb.append("Recognised on the phone: $labels\n")
            sb.append("(Read on the phone only: your files are shown only to your main model, never to another model.)\n")
        }
        return sb.toString().trim()
    }

    private suspend fun describeVideo(context: Context, a: Attachment, onStatus: (String) -> Unit): String {
        val f = java.io.File(a.path)
        if (!f.exists()) return "(The file is no longer on the phone.)"
        val r = android.media.MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            val ms = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val sb = StringBuilder("Length: ${ms / 1000} s, size: ${w}×${h}.\n")
            val count = when { ms <= 0 -> 1; ms < 10_000 -> 4; ms < 60_000 -> 8; else -> 12 }
            val dir = java.io.File(context.cacheDir, "video-frames").apply { mkdirs() }
            val frames = mutableListOf<Pair<Long, java.io.File>>()
            for (i in 0 until count) {
                val t = if (count == 1) 0L else ms * (2 * i + 1) / (2 * count)
                onStatus("Watching the video: frame ${i + 1} of $count")
                val bmp = r.getFrameAtTime(t * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: continue
                val out = java.io.File(dir, "${a.name.hashCode()}-$i.jpg")
                out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
                val ocr = readText(bmp)
                sb.append("At ${t / 1000} s").append(if (ocr.isNotBlank()) " — text on screen: ${ocr.replace('\n', ' ').take(600)}" else "").append('\n')
                frames += t to out
            }
            onStatus("Describing what happens in the video")
            val seen = eye(context, frames.map { imageAttachment(it.second, "image/jpeg") },
                "These are ${frames.size} frames taken in order across a ${ms / 1000}-second video (at " +
                    frames.joinToString { "${it.first / 1000}s" } + "). Describe what the video shows and what happens " +
                    "from start to end, frame by frame, including all text on screen.")
            if (!seen.isNullOrBlank()) sb.append("WHAT HAPPENS:\n").append(seen.trim())
            else {
                frames.firstOrNull()?.second?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }?.let { labels(it) }
                    ?.takeIf { it.isNotBlank() }?.let { sb.append("Recognised on the phone: $it\n") }
                sb.append("(Read on the phone only: your files are shown only to your main model, never to another model.)")
            }
            frames.forEach { it.second.delete() }
            sb.toString().trim()
        } finally {
            runCatching { r.release() }
        }
    }

    private fun imageAttachment(f: java.io.File, mime: String) =
        Attachment(name = f.name, kind = Attachment.IMAGE, path = f.absolutePath, mime = mime.ifBlank { "image/jpeg" }, size = f.length())

    // ---- on the phone: text and objects (ML Kit, offline) ----

    private fun readText(bmp: android.graphics.Bitmap): String = runCatching {
        val rec = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        val res = com.google.android.gms.tasks.Tasks.await(rec.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)))
        rec.close()
        res.text
    }.getOrDefault("")

    private fun labels(bmp: android.graphics.Bitmap): String = runCatching {
        val lab = com.google.mlkit.vision.label.ImageLabeling.getClient(com.google.mlkit.vision.label.defaults.ImageLabelerOptions.DEFAULT_OPTIONS)
        val res = com.google.android.gms.tasks.Tasks.await(lab.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0)))
        lab.close()
        res.filter { it.confidence > 0.6f }.joinToString { it.text }
    }.getOrDefault("")

    // ---- a model that can see, from what the person already has ----

    private suspend fun eye(context: Context, images: List<Attachment>, ask: String): String? {
        // ARIN-PROTECTED: owner-words-only-to-main — the person's pictures and videos are shown only to their main model.
        // When it can't see, they are read on the phone (text recognition and labels, no AI service): never sent to
        // another model (Gemini, ChatGPT, Claude, a Custom API or the Claude Code login) to be described.
        if (OWNER_FILES_ONLY_TO_MAIN) return null
        if (images.isEmpty()) return null
        val s = com.arin.data.Settings(context)
        val system = "You describe images precisely and completely for another AI model that cannot see them."
        suspend fun via(p: Provider, key: String, model: String, ep: com.arin.llm.CustomApi.Endpoint? = null): String? = runCatching {
            val sb = StringBuilder()
            streamModel(p, key, model, listOf(ChatMessage(Role.USER, ask, images)), system, ep).collect { if (!it.startsWith(THOUGHT)) sb.append(it) }
            sb.toString().trim().ifBlank { null }
        }.getOrNull()
        fun def(p: Provider) = com.arin.llm.DefaultModels.of(p).firstOrNull()?.id.orEmpty()
        s.key(Provider.Gemini).takeIf { it.isNotBlank() }?.let { k -> via(Provider.Gemini, k, def(Provider.Gemini))?.let { return it } }
        s.key(Provider.ChatGPT).takeIf { it.isNotBlank() }?.let { k -> via(Provider.ChatGPT, k, def(Provider.ChatGPT))?.let { return it } }
        s.key(Provider.Claude).takeIf { it.isNotBlank() }?.let { k -> via(Provider.Claude, k, "claude-sonnet-5-5")?.let { return it } }
        // A saved Custom API whose model can see.
        s.savedApis.filter { visionNames.containsMatchIn(it.model) }.forEach { api ->
            val ep = com.arin.llm.CustomApi.Endpoint(api.baseUrl, runCatching { com.arin.llm.ApiFormat.valueOf(api.format) }.getOrDefault(com.arin.llm.ApiFormat.OpenAI))
            via(Provider.Custom, s.apiKey(api), api.model, ep)?.let { return it }
        }
        // The Claude Code login (Claude reads image files itself).
        return runCatching { viaClaudeCode(context, images, ask) }.getOrNull()
    }

    private suspend fun viaClaudeCode(context: Context, images: List<Attachment>, ask: String): String? {
        if (!com.arin.terminal.Linux.installed(context) || !com.arin.claudecode.ClaudeCode.connected(context)) return null
        val root = com.arin.terminal.Linux.rootfs(context)
        val dir = java.io.File(root, "root/.arin/vision").apply { deleteRecursively(); mkdirs() }
        val paths = images.mapIndexed { i, a ->
            val f = java.io.File(dir, "img$i.jpg"); java.io.File(a.path).copyTo(f, overwrite = true); "/root/.arin/vision/img$i.jpg"
        }
        val prompt = "$ask\n\nThe images are these files (read each with the Read tool): ${paths.joinToString(", ")}"
        val b64 = android.util.Base64.encodeToString(prompt.toByteArray(), android.util.Base64.NO_WRAP)
        val home = "/root/.arin/vision/home"
        val r = com.arin.terminal.Shell.run(
            context,
            "mkdir -p $home; echo '{\"hasCompletedOnboarding\":true}' > $home/.claude.json; cd /root/.arin/vision; " +
                "echo '$b64' | base64 -d > prompt.txt; unset ANTHROPIC_API_KEY ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN ANTHROPIC_MODEL; " +
                "export CLAUDE_CODE_OAUTH_TOKEN=\$(cat /root/.cc_token 2>/dev/null) DISABLE_AUTOUPDATER=1; " +
                "HOME=$home timeout 600 claude -p --output-format text --allowedTools 'Read' < prompt.txt 2>&1 | tail -c 12000; " +
                "cd /root; rm -rf /root/.arin/vision",
            12 * 60_000L,
        )
        return r.stdout.trim().takeIf { it.isNotBlank() && !it.contains("Invalid API key", true) && !it.contains("not logged in", true) }
    }
}
