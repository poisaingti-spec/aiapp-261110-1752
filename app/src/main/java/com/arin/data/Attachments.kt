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
import com.arin.llm.Image
import com.arin.llm.LlmException
import com.arin.llm.Pdf
import com.arin.llm.Provider
import com.arin.llm.Turn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Reads picked or shared files, keeps photos and PDFs in private storage, and prepares them for each model. */
object Attachments {
    /** Large text files are cut to this many characters so they fit in a model's context. */
    const val MAX_CHARS = 120_000

    /** Photos are shrunk so their longest side is at most this many pixels. */
    private const val MAX_IMAGE_SIDE = 1600

    private const val MAX_PDF_BYTES = 20L * 1024 * 1024

    /** Only the photos and PDFs in the last few messages that have them are sent again; older ones become a note. */
    private const val MEDIA_MESSAGES_SENT = 3

    /** Gemini gets PDFs as page images; this caps how many pages. */
    private const val MAX_PDF_PAGES_AS_IMAGES = 20

    private val textExtensions = setOf(
        "txt", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml", "toml", "ini", "log",
        "html", "htm", "css", "js", "ts", "tsx", "jsx", "kt", "kts", "java", "py", "rb", "go", "rs",
        "c", "h", "cpp", "hpp", "cs", "swift", "php", "sh", "sql", "gradle", "properties",
    )
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")

    class Unsupported(message: String) : Exception(message)

    private fun dir(context: Context) = File(context.filesDir, "attachments").apply { mkdirs() }

    suspend fun read(context: Context, uri: Uri): Attachment = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            mime.startsWith("image/") || ext in imageExtensions -> readImage(context, uri, name)
            mime == "application/pdf" || ext == "pdf" -> readPdf(context, uri, name)
            mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || ext in textExtensions ->
                readText(context, uri, name)
            else -> throw Unsupported("\"$name\" can't be read. Arin takes photos, PDFs, and text, CSV, JSON or code files.")
        }
    }

    private fun readText(context: Context, uri: Uri, name: String): Attachment {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = CharArray(MAX_CHARS + 1)
            val reader = input.bufferedReader()
            var total = 0
            while (total < buf.size) {
                val n = reader.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            String(buf, 0, total)
        } ?: throw Unsupported("\"$name\" couldn't be opened.")
        if (raw.contains('\u0000')) throw Unsupported("\"$name\" isn't a text file.")
        val text = if (raw.length > MAX_CHARS) raw.take(MAX_CHARS) + "\n…(file cut here: too long)" else raw
        return Attachment(name = name, text = text, kind = Attachment.TEXT)
    }

    private fun readImage(context: Context, uri: Uri, name: String): Attachment {
        val bitmap = decodeScaled(context, uri) ?: throw Unsupported("\"$name\" couldn't be opened as a photo.")
        val file = File(dir(context), "${UUID.randomUUID()}.jpg")
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

    private fun readPdf(context: Context, uri: Uri, name: String): Attachment {
        val file = File(dir(context), "${UUID.randomUUID()}.pdf")
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_PDF_BYTES) {
                        out.close(); file.delete()
                        throw Unsupported("\"$name\" is larger than 20 MB.")
                    }
                    out.write(buf, 0, n)
                }
            }
        } ?: throw Unsupported("\"$name\" couldn't be opened.")
        val pages = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
        }.getOrElse {
            file.delete()
            throw Unsupported("\"$name\" isn't a readable PDF (it may be password-protected).")
        }
        return Attachment(name = name, kind = Attachment.PDF, path = file.absolutePath, mime = "application/pdf", size = file.length(), pages = pages)
    }

    /** Removes the stored copies of photos and PDFs. */
    fun deleteFiles(items: List<Attachment>) {
        items.forEach { if (it.path.isNotEmpty()) File(it.path).delete() }
    }

    fun deleteAllFiles(context: Context) {
        dir(context).listFiles().orEmpty().forEach { it.delete() }
    }

    /**
     * Turns saved messages into what [provider] is sent. Text files go inline; photos and PDFs go
     * as the model's own image and document formats, only for the most recent messages that have them.
     */
    suspend fun toTurns(provider: Provider, messages: List<Message>, projectMedia: List<Attachment> = emptyList()): List<Turn> =
        withContext(Dispatchers.IO) {
            val turns = messageTurns(provider, messages)
            if (projectMedia.isEmpty()) return@withContext turns
            // A project's photos and PDFs go with the first message, so every reply can see them.
            if (provider == Provider.Free) {
                throw LlmException("This project has photos or PDFs, which free models can't read. Choose Claude, Gemini or ChatGPT in Settings.")
            }
            val first = turns.indexOfFirst { it.fromUser }
            if (first < 0) return@withContext turns
            val extra = encodeMedia(provider, projectMedia)
            turns.toMutableList().also { list ->
                val t = list[first]
                list[first] = t.copy(
                    text = "Project files: " + projectMedia.joinToString { it.name } + "\n\n" + t.text,
                    images = extra.first + t.images,
                    pdfs = extra.second + t.pdfs,
                )
            }
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

    private fun messageTurns(provider: Provider, messages: List<Message>): List<Turn> = run {
        val mediaIndices = messages.indices
            .filter { i -> messages[i].fromUser && messages[i].attachments.any { it.isMedia } }
            .takeLast(MEDIA_MESSAGES_SENT)
            .toSet()
        if (provider == Provider.Free && mediaIndices.isNotEmpty()) {
            throw LlmException(
                "Free models can't read photos or PDFs. Add a Claude, Gemini or ChatGPT key in Settings, " +
                    "choose it, then tap Retry.",
            )
        }
        messages.mapIndexed { i, m ->
            if (!m.fromUser) return@mapIndexed Turn(false, m.text)
            val textFiles = m.attachments.filter { it.kind == Attachment.TEXT }
            val media = m.attachments.filter { it.isMedia }
            val sendMedia = i in mediaIndices
            val notes = if (!sendMedia && media.isNotEmpty()) media.joinToString("\n") { "[Earlier attachment: ${it.name}]" } else ""
            val text = listOf(promptWithFiles(m.text, textFiles), notes).filter { it.isNotBlank() }.joinToString("\n\n")
            if (!sendMedia) return@mapIndexed Turn(true, text)
            val (images, pdfs) = encodeMedia(provider, media)
            Turn(true, text.ifBlank { "Here's the attached file." }, images, pdfs)
        }
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
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { return it }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** The message text the model sees: what was typed, followed by each text file. */
    fun promptWithFiles(text: String, files: List<Attachment>): String {
        if (files.isEmpty()) return text
        val parts = files.joinToString("\n\n") { "<file name=\"${it.name}\">\n${it.text}\n</file>" }
        return if (text.isBlank()) parts else "$text\n\n$parts"
    }
}
