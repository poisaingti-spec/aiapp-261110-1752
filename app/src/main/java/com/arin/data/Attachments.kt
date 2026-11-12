package com.arin.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Xml
import com.arin.llm.Image
import com.arin.llm.LlmException
import com.arin.llm.Pdf
import com.arin.llm.Provider
import com.arin.llm.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.Writer
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Reads picked or shared files of any type and size, keeps what's needed in private storage, and
 * prepares them for each model.
 *
 * - Photos are shrunk and sent as images.
 * - PDFs are sent as documents when the model takes them, and their text is extracted too, so very
 *   large PDFs and models without PDF support still work.
 * - Word, Excel, PowerPoint, OpenDocument, EPUB, RTF, ZIP and any text or code file become text.
 * - Anything else (audio, video, apps…) is attached by name, type and size.
 *
 * Extracted text lives in a file, not in the chat, so there is no size limit. Each request includes as
 * much as the model can take; when a file is bigger, the parts most related to the question are sent.
 */
object Attachments {
    /** Photos are shrunk so their longest side is at most this many pixels. */
    private const val MAX_IMAGE_SIDE = 1600

    /** PDFs above this size or page count are sent as extracted text instead of the PDF itself. */
    private const val MAX_NATIVE_PDF_BYTES = 30L * 1024 * 1024
    private const val MAX_NATIVE_PDF_PAGES = 100

    /** Only the photos and PDFs in the last few messages that have them are sent again; older ones become a note. */
    private const val MEDIA_MESSAGES_SENT = 3

    /** Gemini gets PDFs as page images; this caps how many pages. */
    private const val MAX_PDF_PAGES_AS_IMAGES = 20

    /** Characters kept in the chat as a preview of a text file; the full text stays on disk. */
    private const val PREVIEW_CHARS = 2_000

    /** Each entry inside a ZIP is read up to this many bytes. */
    private const val MAX_ZIP_ENTRY_BYTES = 4L * 1024 * 1024

    /** How much file text one request may carry, by provider (characters, roughly 4 per token). */
    private fun textBudget(provider: Provider): Int = when (provider) {
        Provider.Free -> 40_000
        Provider.Custom -> 150_000
        Provider.Claude, Provider.Gemini, Provider.ChatGPT -> 400_000
    }

    private val textExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "log",
        "html", "htm", "css", "scss", "js", "mjs", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs",
        "c", "h", "cpp", "hpp", "cc", "cs", "swift", "php", "sh", "bat", "ps1", "sql", "gradle", "properties",
        "srt", "vtt", "tex", "bib", "r", "m", "dart", "lua", "pl", "scala", "vue", "svelte", "env", "cfg", "conf",
    )
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    private val zipDocExtensions = setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub", "zip", "jar", "apk")

    class Unsupported(message: String) : Exception(message)

    private fun dir(context: Context) = File(context.filesDir, "attachments").apply { mkdirs() }

    private fun newFile(context: Context, ext: String) = File(dir(context), "${UUID.randomUUID()}.$ext")

    // ---------------------------------------------------------------- reading

    suspend fun read(context: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val size = displaySize(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            mime.startsWith("image/") && mime != "image/svg+xml" || ext in imageExtensions -> readImage(context, uri, name)
            mime == "application/pdf" || ext == "pdf" -> readPdf(context, uri, name)
            ext in setOf("docx", "xlsx", "pptx", "odt", "ods", "odp", "epub") -> readZipDocument(context, uri, name, ext, mime, size)
            ext == "zip" || mime == "application/zip" -> readZipDocument(context, uri, name, "zip", mime, size)
            ext == "rtf" || mime == "application/rtf" || mime == "text/rtf" -> readRtf(context, uri, name, mime, size)
            mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || ext in textExtensions ->
                readText(context, uri, name, mime, size)
            mime.startsWith("audio/") || mime.startsWith("video/") || ext in zipDocExtensions -> otherFile(name, mime, size)
            looksLikeText(context, uri) -> readText(context, uri, name, mime, size)
            else -> otherFile(name, mime, size)
        }
    }

    /** True if the start of the file has no zero bytes and decodes as text. */
    private fun looksLikeText(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(8192)
            val n = input.read(buf).coerceAtLeast(0)
            if (n == 0) return@use true
            if ((0 until n).any { buf[it].toInt() == 0 }) return@use false
            val s = String(buf, 0, n, Charsets.UTF_8)
            val bad = s.count { it == '\uFFFD' || (it.isISOControl() && it !in "\n\r\t\u000C") }
            bad < n / 50 + 1
        } ?: false
    }.getOrDefault(false)

    /** A file Arin can't read inside: kept by name, type and size only (not copied). */
    private fun otherFile(name: String, mime: String, size: Long) =
        Attachment(name = name, kind = Attachment.FILE, mime = mime, size = size)

    /** Streams any text file to private storage: no size limit. */
    private fun readText(context: Context, uri: Uri, name: String, mime: String, size: Long): Attachment {
        val file = newFile(context, "txt")
        val chars = context.contentResolver.openInputStream(uri)?.use { input ->
            file.bufferedWriter().use { w -> copyText(input, w) }
        } ?: throw Unsupported("\"$name\" couldn't be opened.")
        return textAttachment(name, mime, size, file, chars)
    }

    private fun copyText(input: InputStream, w: Writer): Long {
        val reader = input.bufferedReader()
        val buf = CharArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = reader.read(buf)
            if (n < 0) break
            // Zero characters would break the request; drop them.
            for (i in 0 until n) if (buf[i] == '\u0000') buf[i] = ' '
            w.write(buf, 0, n)
            total += n
        }
        return total
    }

    private fun textAttachment(name: String, mime: String, size: Long, file: File, chars: Long, kind: String = Attachment.TEXT): Attachment {
        if (chars == 0L) {
            file.delete()
            throw Unsupported("\"$name\" has no readable text.")
        }
        return Attachment(
            name = name,
            text = preview(file),
            kind = kind,
            mime = mime,
            size = if (size > 0) size else file.length(),
            textPath = file.absolutePath,
            chars = chars,
        )
    }

    private fun preview(file: File): String = file.bufferedReader().use { r ->
        val buf = CharArray(PREVIEW_CHARS)
        val n = r.read(buf).coerceAtLeast(0)
        String(buf, 0, n)
    }

    private fun readImage(context: Context, uri: Uri, name: String): Attachment {
        val bitmap = decodeScaled(context, uri) ?: throw Unsupported("\"$name\" couldn't be opened as a photo.")
        val file = newFile(context, "jpg")
        file.outputStream().use { out ->
            // JPEG has no transparency: paint transparent parts white instead of black.
            val flat = if (bitmap.hasAlpha()) {
                Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { b ->
                    android.graphics.Canvas(b).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
                }
            } else bitmap
            flat.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        return Attachment(name = name, kind = Attachment.IMAGE, path = file.absolutePath, mime = "image/jpeg", size = file.length())
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also turns the photo the right way up.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = minOf(1f, MAX_IMAGE_SIDE.toFloat() / maxOf(w, h))
                decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_IMAGE_SIDE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()

    private fun copyToFile(context: Context, uri: Uri, name: String, ext: String): File {
        val file = newFile(context, ext)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
        } ?: throw Unsupported("\"$name\" couldn't be opened.")
        return file
    }

    private fun readPdf(context: Context, uri: Uri, name: String): Attachment {
        val file = copyToFile(context, uri, name, "pdf")
        val pages = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
        }.getOrNull()
        // The text, for big PDFs and for models that can't read PDFs. Scanned pages have none.
        val textFile = newFile(context, "txt")
        val chars = runCatching { PdfText.extract(context, file, textFile) }.getOrDefault(0L)
        if (chars == 0L) textFile.delete()
        if (pages == null && chars == 0L) {
            file.delete()
            throw Unsupported("\"$name\" isn't a readable PDF (it may be password-protected).")
        }
        return Attachment(
            name = name,
            kind = Attachment.PDF,
            path = file.absolutePath,
            mime = "application/pdf",
            size = file.length(),
            pages = pages ?: 0,
            text = if (chars > 0) preview(textFile) else "",
            textPath = if (chars > 0) textFile.absolutePath else "",
            chars = chars,
        )
    }

    private fun readRtf(context: Context, uri: Uri, name: String, mime: String, size: Long): Attachment {
        val file = newFile(context, "txt")
        val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.ISO_8859_1) }
            ?: throw Unsupported("\"$name\" couldn't be opened.")
        val text = raw
            .replace(Regex("""\\par[d]?\b"""), "\n")
            .replace(Regex("""\\'([0-9a-fA-F]{2})""")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("""\\[a-zA-Z]+-?\d* ?"""), "")
            .replace(Regex("""[{}]"""), "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
        file.writeText(text)
        return textAttachment(name, mime, size, file, text.length.toLong())
    }

    /** Office files, OpenDocument, EPUB and ZIP archives: all ZIPs of XML or text. */
    private fun readZipDocument(context: Context, uri: Uri, name: String, ext: String, mime: String, size: Long): Attachment {
        val tmp = File(context.cacheDir, "${UUID.randomUUID()}.zip")
        val out = newFile(context, "txt")
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
                ?: throw Unsupported("\"$name\" couldn't be opened.")
            val zip = runCatching { ZipFile(tmp) }.getOrElse { throw Unsupported("\"$name\" is damaged or isn't a $ext file.") }
            zip.use { z ->
                out.bufferedWriter().use { w ->
                    when (ext) {
                        "docx" -> OfficeText.docx(z, w)
                        "xlsx" -> OfficeText.xlsx(z, w)
                        "pptx" -> OfficeText.pptx(z, w)
                        "odt", "ods", "odp" -> OfficeText.openDocument(z, w)
                        "epub" -> OfficeText.epub(z, w)
                        else -> OfficeText.archive(z, w, MAX_ZIP_ENTRY_BYTES)
                    }
                }
            }
            val chars = out.length() // close enough: used only for display and budgeting
            return textAttachment(name, mime, size, out, chars)
        } catch (e: Unsupported) {
            out.delete()
            throw e
        } catch (e: Throwable) {
            out.delete()
            throw Unsupported("\"$name\" couldn't be read (${e.message ?: e.javaClass.simpleName}).")
        } finally {
            tmp.delete()
        }
    }

    /** Removes the stored copies of photos, PDFs and extracted text. */
    fun deleteFiles(items: List<Attachment>) {
        items.forEach {
            if (it.path.isNotEmpty()) File(it.path).delete()
            if (it.textPath.isNotEmpty()) File(it.textPath).delete()
        }
    }

    fun deleteAllFiles(context: Context) {
        dir(context).listFiles().orEmpty().forEach { it.delete() }
    }

    // ---------------------------------------------------------------- sending

    /** Whether [a] goes to [provider] as an image or PDF (true), or as text (false). */
    private fun sendsNatively(provider: Provider, a: Attachment): Boolean = when (a.kind) {
        Attachment.IMAGE -> true
        Attachment.PDF -> when {
            provider == Provider.Free -> false
            a.path.isEmpty() || !File(a.path).exists() -> false
            provider == Provider.Gemini -> a.pages in 1..MAX_PDF_PAGES_AS_IMAGES || a.textPath.isEmpty()
            else -> a.size <= MAX_NATIVE_PDF_BYTES && a.pages <= MAX_NATIVE_PDF_PAGES || a.textPath.isEmpty()
        }
        else -> false
    }

    /**
     * Turns saved messages into what [provider] is sent. Text goes inline (trimmed to fit, keeping the
     * parts closest to the question); photos and PDFs go as the model's own formats, only for the most
     * recent messages that have them. [projectFiles] go with the first message.
     */
    suspend fun toTurns(provider: Provider, messages: List<Message>, projectFiles: List<Attachment> = emptyList()): List<Turn> =
        withContext(Dispatchers.IO) {
            val question = messages.lastOrNull { it.fromUser }?.text.orEmpty()
            val mediaIndices = messages.indices
                .filter { i -> messages[i].fromUser && messages[i].attachments.any { sendsNatively(provider, it) } }
                .takeLast(MEDIA_MESSAGES_SENT)
                .toSet()
            if (provider == Provider.Free) {
                val photos = (messages.flatMap { it.attachments } + projectFiles).any { it.kind == Attachment.IMAGE }
                if (photos) {
                    throw LlmException(
                        "Free models can't see photos. Add a key in Settings (Claude, Gemini, ChatGPT or a Custom API), choose it, then tap Retry.",
                    )
                }
            }

            // Share the text budget across every file that will go as text, newest first.
            val textFiles = messages.withIndex().filter { it.value.fromUser }.flatMap { (i, m) ->
                m.attachments.filter { a -> a.hasText && !(i in mediaIndices && sendsNatively(provider, a)) }.map { i to it }
            } + projectFiles.filter { it.hasText && !sendsNatively(provider, it) }.map { -1 to it }
            val budgets = shareBudget(textFiles.map { it.second }, textBudget(provider))

            val turns = messages.mapIndexed { i, m ->
                if (!m.fromUser) return@mapIndexed Turn(false, m.text)
                val sendMedia = i in mediaIndices
                val asText = m.attachments.filter { a -> a.hasText && !(sendMedia && sendsNatively(provider, a)) }
                val media = m.attachments.filter { sendsNatively(provider, it) }
                val notes = buildList {
                    m.attachments.forEach { a ->
                        when {
                            a.kind == Attachment.FILE -> add(fileNote(a))
                            a.hasText || (sendMedia && sendsNatively(provider, a)) -> {}
                            sendsNatively(provider, a) -> add("[Earlier attachment: ${a.name}]")
                            else -> add("[Attachment \"${a.name}\" has no text this model can read.]")
                        }
                    }
                }.joinToString("\n")
                val body = promptWithFiles(m.text, asText, budgets, question.ifBlank { m.text })
                val text = listOf(body, notes).filter { it.isNotBlank() }.joinToString("\n\n")
                if (!sendMedia || media.isEmpty()) return@mapIndexed Turn(true, text.ifBlank { "Here's the attached file." })
                val (images, pdfs) = encodeMedia(provider, media)
                Turn(true, text.ifBlank { "Here's the attached file." }, images, pdfs)
            }

            if (projectFiles.isEmpty()) return@withContext turns
            val first = turns.indexOfFirst { it.fromUser }
            if (first < 0) return@withContext turns
            val projectText = projectFiles.filter { it.hasText && !sendsNatively(provider, it) }
            val projectMedia = projectFiles.filter { sendsNatively(provider, it) }
            val projectNotes = projectFiles.filter { it.kind == Attachment.FILE }.joinToString("\n") { fileNote(it) }
            val (images, pdfs) = encodeMedia(provider, projectMedia)
            turns.toMutableList().also { list ->
                val t = list[first]
                val head = listOf(
                    "Project files: " + projectFiles.joinToString { it.name },
                    promptWithFiles("", projectText, budgets, question),
                    projectNotes,
                ).filter { it.isNotBlank() }.joinToString("\n\n")
                list[first] = t.copy(text = head + "\n\n" + t.text, images = images + t.images, pdfs = pdfs + t.pdfs)
            }
        }

    private fun fileNote(a: Attachment): String =
        "[Attached file \"${a.name}\"" +
            (if (a.mime.isNotBlank()) ", ${a.mime}" else "") +
            (if (a.size > 0) ", ${readableSize(a.size)}" else "") +
            ". Arin can't read inside this kind of file, so only its name, type and size are known.]"

    /** Gives each file an equal share of [total]; files smaller than their share pass the rest on. */
    private fun shareBudget(files: List<Attachment>, total: Int): Map<Attachment, Int> {
        val result = HashMap<Attachment, Int>()
        var left = total
        val sorted = files.distinct().sortedBy { it.chars }
        sorted.forEachIndexed { idx, a ->
            val share = left / (sorted.size - idx)
            val give = minOf(a.chars.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), share).coerceAtLeast(2_000)
            result[a] = give
            left = (left - give).coerceAtLeast(0)
        }
        return result
    }

    private fun encodeMedia(provider: Provider, media: List<Attachment>): Pair<List<Image>, List<Pdf>> {
        val images = mutableListOf<Image>()
        val pdfs = mutableListOf<Pdf>()
        media.forEach { a ->
            val f = File(a.path)
            if (!f.exists()) return@forEach
            when (a.kind) {
                Attachment.IMAGE -> images += Image(a.mime.ifEmpty { "image/jpeg" }, base64(f.readBytes()))
                Attachment.PDF ->
                    if (provider == Provider.Gemini) images += pdfPagesAsImages(f)
                    else pdfs += Pdf(a.name, base64(f.readBytes()))
            }
        }
        return images to pdfs
    }

    private fun pdfPagesAsImages(file: File): List<Image> = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until minOf(r.pageCount, MAX_PDF_PAGES_AS_IMAGES)).map { p ->
                    r.openPage(p).use { page ->
                        val scale = 1400f / page.width
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val out = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
                        bmp.recycle()
                        Image("image/jpeg", base64(out.toByteArray()))
                    }
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun base64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun displayName(context: Context, uri: Uri): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun displaySize(context: Context, uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
        } ?: 0L
    }.getOrDefault(0L)

    fun readableSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }

    // ---------------------------------------------------------------- text that fits

    /**
     * The message text the model sees: what was typed, followed by each file's text. A file bigger
     * than its share of the budget is cut to its start plus the passages that best match [question].
     */
    fun promptWithFiles(
        text: String,
        files: List<Attachment>,
        budgets: Map<Attachment, Int> = emptyMap(),
        question: String = text,
    ): String {
        if (files.isEmpty()) return text
        val parts = files.joinToString("\n\n") { a ->
            val limit = budgets[a] ?: 100_000
            val body = fit(a, limit, question)
            "<file name=\"${a.name}\">\n$body\n</file>"
        }
        return if (text.isBlank()) parts else "$text\n\n$parts"
    }

    /** The full text of [a], or the parts of it that fit in [limit] characters. */
    private fun fit(a: Attachment, limit: Int, question: String): String {
        val file = a.textPath.takeIf { it.isNotEmpty() }?.let(::File)
        if (file == null || !file.exists()) {
            // Older chats keep the text inline.
            return if (a.text.length <= limit) a.text else a.text.take(limit) + "\n…(file cut here: too long)"
        }
        if (a.chars in 1..limit.toLong()) return file.readText()
        return relevantParts(file, limit, question, a.chars)
    }

    /**
     * Reads [file] in chunks and keeps the opening chunk plus the chunks sharing the most words with
     * [question], in their original order, until [limit] characters are used.
     */
    private fun relevantParts(file: File, limit: Int, question: String, totalChars: Long): String {
        val chunkSize = 4_000
        val words = question.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        data class Chunk(val index: Int, val score: Int)
        val scores = ArrayList<Chunk>()
        file.bufferedReader().use { r ->
            val buf = CharArray(chunkSize)
            var index = 0
            while (true) {
                val n = r.read(buf)
                if (n <= 0) break
                val s = String(buf, 0, n).lowercase()
                val score = if (words.isEmpty()) 0 else words.sumOf { w -> countOf(s, w) }
                scores += Chunk(index++, score)
            }
        }
        val maxChunks = (limit / chunkSize).coerceAtLeast(1)
        val keep = HashSet<Int>()
        keep += 0
        scores.filter { it.score > 0 }.sortedByDescending { it.score }.forEach { if (keep.size < maxChunks) keep += it.index }
        // Nothing matched: fill with the beginning of the file.
        var next = 1
        while (keep.size < maxChunks && next < scores.size) keep += next++
        val out = StringBuilder()
        out.append("(This file has about ${totalChars / 1000}k characters, more than fits. Showing the start and the parts that best match the question; \"…\" marks skipped text.)\n")
        file.bufferedReader().use { r ->
            val buf = CharArray(chunkSize)
            var index = 0
            var skipped = false
            while (true) {
                val n = r.read(buf)
                if (n <= 0) break
                if (index in keep) {
                    if (skipped) out.append("\n…\n")
                    out.append(buf, 0, n)
                    skipped = false
                } else {
                    skipped = true
                }
                index++
            }
            if (skipped) out.append("\n…")
        }
        return out.toString()
    }

    private fun countOf(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val i = haystack.indexOf(needle, from)
            if (i < 0) return count
            count++
            from = i + needle.length
        }
    }
}

/** Text from Office, OpenDocument, EPUB and ZIP files, written straight to a file. */
private object OfficeText {

    private val breakTags = setOf(
        "p", "w:p", "a:p", "text:p", "text:h", "br", "w:br", "text:line-break", "tr", "w:tr", "table:table-row",
        "div", "li", "h1", "h2", "h3", "h4", "h5", "h6", "title",
    )
    private val tabTags = setOf("w:tab", "text:tab", "table:table-cell", "td", "th", "w:tc")

    /** Writes the text of an XML (or XHTML) stream, with line breaks at paragraph ends. */
    private fun xmlText(input: InputStream, w: Writer) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        runCatching { parser.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) }
        parser.setInput(input, null)
        var skip = 0
        while (true) {
            val event = runCatching { parser.next() }.getOrElse { return }
            when (event) {
                XmlPullParser.END_DOCUMENT -> return
                XmlPullParser.START_TAG -> {
                    val n = parser.name
                    if (n == "script" || n == "style") skip++
                    if (n in tabTags) w.write("\t")
                    if (n == "br" || n == "w:br" || n == "text:line-break") w.write("\n")
                }
                XmlPullParser.END_TAG -> {
                    val n = parser.name
                    if (n == "script" || n == "style") skip--
                    if (n in breakTags && n != "br") w.write("\n")
                }
                XmlPullParser.TEXT -> if (skip <= 0) w.write(parser.text)
            }
        }
    }

    fun docx(z: ZipFile, w: Writer) {
        val doc = z.getEntry("word/document.xml") ?: error("no document inside")
        z.getInputStream(doc).use { xmlText(it, w) }
        listOf("word/footnotes.xml", "word/endnotes.xml").forEach { name ->
            z.getEntry(name)?.let { e -> w.write("\n"); z.getInputStream(e).use { xmlText(it, w) } }
        }
    }

    fun pptx(z: ZipFile, w: Writer) {
        val slides = z.entries().toList().filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            .sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 }
        slides.forEachIndexed { i, e ->
            w.write("--- Slide ${i + 1} ---\n")
            z.getInputStream(e).use { xmlText(it, w) }
            w.write("\n")
        }
        if (slides.isEmpty()) error("no slides inside")
    }

    fun xlsx(z: ZipFile, w: Writer) {
        val shared = ArrayList<String>()
        z.getEntry("xl/sharedStrings.xml")?.let { e ->
            z.getInputStream(e).use { input ->
                val p = Xml.newPullParser().apply { setInput(input, null) }
                val sb = StringBuilder()
                var inSi = false
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> if (p.name == "si") { inSi = true; sb.clear() }
                        XmlPullParser.TEXT -> if (inSi) sb.append(p.text)
                        XmlPullParser.END_TAG -> if (p.name == "si") { inSi = false; shared += sb.toString() }
                    }
                }
            }
        }
        val sheetNames = sheetNames(z)
        val sheets = z.entries().toList().filter { it.name.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            .sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) error("no sheets inside")
        sheets.forEachIndexed { i, e ->
            w.write("--- Sheet: ${sheetNames.getOrNull(i) ?: "Sheet ${i + 1}"} ---\n")
            z.getInputStream(e).use { input ->
                val p = Xml.newPullParser().apply { setInput(input, null) }
                var type = ""
                var value = StringBuilder()
                var inValue = false
                var firstInRow = true
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    when (p.eventType) {
                        XmlPullParser.START_TAG -> when (p.name) {
                            "row" -> firstInRow = true
                            "c" -> { type = p.getAttributeValue(null, "t").orEmpty(); value = StringBuilder() }
                            "v", "t" -> inValue = true
                        }
                        XmlPullParser.TEXT -> if (inValue) value.append(p.text)
                        XmlPullParser.END_TAG -> when (p.name) {
                            "v", "t" -> inValue = false
                            "c" -> {
                                val v = value.toString()
                                val cell = if (type == "s") v.toIntOrNull()?.let { shared.getOrNull(it) } ?: v else v
                                if (!firstInRow) w.write("\t")
                                w.write(cell.replace('\n', ' '))
                                firstInRow = false
                            }
                            "row" -> w.write("\n")
                        }
                    }
                }
            }
            w.write("\n")
        }
    }

    private fun sheetNames(z: ZipFile): List<String> = runCatching {
        val e = z.getEntry("xl/workbook.xml") ?: return emptyList()
        z.getInputStream(e).use { input ->
            val p = Xml.newPullParser().apply { setInput(input, null) }
            val names = ArrayList<String>()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "sheet") p.getAttributeValue(null, "name")?.let { names += it }
            }
            names
        }
    }.getOrDefault(emptyList())

    fun openDocument(z: ZipFile, w: Writer) {
        val e = z.getEntry("content.xml") ?: error("no content inside")
        z.getInputStream(e).use { xmlText(it, w) }
    }

    fun epub(z: ZipFile, w: Writer) {
        val pages = z.entries().toList()
            .filter { !it.isDirectory && it.name.lowercase().let { n -> n.endsWith(".xhtml") || n.endsWith(".html") || n.endsWith(".htm") } }
            .sortedBy { it.name }
        if (pages.isEmpty()) error("no pages inside")
        pages.forEach { e -> z.getInputStream(e).use { xmlText(it, w) }; w.write("\n") }
    }

    /** A plain ZIP: lists everything, then includes the text of each readable file. */
    fun archive(z: ZipFile, w: Writer, maxEntryBytes: Long) {
        val entries = z.entries().toList()
        w.write("Contents of the archive (${entries.size} items):\n")
        entries.forEach { w.write("- ${it.name}${if (!it.isDirectory && it.size >= 0) " (${Attachments.readableSize(it.size)})" else ""}\n") }
        entries.filter { !it.isDirectory }.forEach { e ->
            val head = z.getInputStream(e).use { s -> ByteArray(8192).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } }
            if (head.isEmpty() || head.any { it.toInt() == 0 }) return@forEach
            w.write("\n<file name=\"${e.name}\">\n")
            z.getInputStream(e).use { s ->
                val reader = s.bufferedReader()
                val buf = CharArray(64 * 1024)
                var total = 0L
                while (total < maxEntryBytes) {
                    val n = reader.read(buf)
                    if (n < 0) break
                    w.write(buf, 0, n)
                    total += n
                }
                if (total >= maxEntryBytes) w.write("\n…(cut here: too long)")
            }
            w.write("\n</file>\n")
        }
    }
}

/** PDF text, page by page, without loading the whole document into memory. */
private object PdfText {
    @Volatile private var ready = false

    fun extract(context: Context, pdf: File, out: File): Long {
        if (!ready) {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
            ready = true
        }
        var total = 0L
        com.tom_roush.pdfbox.pdmodel.PDDocument.load(pdf, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            out.bufferedWriter().use { w ->
                for (page in 1..doc.numberOfPages) {
                    stripper.startPage = page
                    stripper.endPage = page
                    val t = stripper.getText(doc)
                    if (t.isNotBlank()) {
                        w.write("--- Page $page ---\n")
                        w.write(t)
                        w.write("\n")
                        total += t.length
                    }
                }
            }
        }
        return total
    }
}
