package com.arin.llm

import android.net.Uri
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** One search result. */
data class Hit(val title: String, val url: String, val snippet: String)

/** Web search with no key: DuckDuckGo first, Wikipedia as a fallback. */
object WebSearch {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun search(query: String): List<Hit> = withContext(Dispatchers.IO) {
        runCatching { duckDuckGo(query) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: runCatching { wikipedia(query) }.getOrNull().orEmpty()
    }

    /** Results with the readable text of the top pages added, ready to send to the model. */
    suspend fun searchForModel(query: String): Pair<List<Hit>, String> = coroutineScope {
        val hits = search(query).take(6)
        if (hits.isEmpty()) return@coroutineScope hits to "No results were found for \"$query\"."
        val pages = hits.take(2).map { h -> async(Dispatchers.IO) { runCatching { pageText(h.url) }.getOrNull() } }.awaitAll()
        val text = buildString {
            append("Search results for \"").append(query).append("\":\n\n")
            hits.forEachIndexed { i, h ->
                append("[").append(i + 1).append("] ").append(h.title).append('\n').append(h.url).append('\n')
                if (h.snippet.isNotBlank()) append(h.snippet).append('\n')
                pages.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { append("Page text: ").append(it).append('\n') }
                append('\n')
            }
        }
        hits to text
    }

    private fun duckDuckGo(query: String): List<Hit> {
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/")
            .header("User-Agent", UA)
            .post(FormBody.Builder().add("q", query).build())
            .build()
        val html = client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string().orEmpty() else "" }
        val links = Regex("""<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.groupValues[1] }.toList()
        return links.mapIndexedNotNull { i, m ->
            val url = realUrl(Html.fromHtml(m.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString())
            if (url.contains("duckduckgo.com/y.js")) return@mapIndexedNotNull null // ads
            Hit(plain(m.groupValues[2]), url, plain(snippets.getOrNull(i).orEmpty()))
        }
    }

    /** DuckDuckGo wraps links as //duckduckgo.com/l/?uddg=<real url>. */
    private fun realUrl(href: String): String {
        val full = if (href.startsWith("//")) "https:$href" else href
        val uddg = runCatching { Uri.parse(full).getQueryParameter("uddg") }.getOrNull()
        return uddg ?: full
    }

    private fun wikipedia(query: String): List<Hit> {
        val url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=" +
            Uri.encode(query)
        val body = client.newCall(Request.Builder().url(url).header("User-Agent", "Arin/0.3 (Android app)").build())
            .execute().use { it.body?.string().orEmpty() }
        val root = Http.json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val items = ((root["query"] as? JsonObject)?.get("search") as? JsonArray).orEmpty()
        return items.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val title = (o["title"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val snippet = plain((o["snippet"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            Hit("$title (Wikipedia)", "https://en.wikipedia.org/wiki/" + Uri.encode(title.replace(' ', '_')), snippet)
        }
    }

    /** The main text of a page, roughly, capped so several fit in a prompt. */
    private fun pageText(url: String): String {
        val html = client.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { resp ->
            val type = resp.header("Content-Type").orEmpty()
            if (!resp.isSuccessful || !type.contains("html")) return ""
            resp.body?.source()?.let { src -> src.request(400_000); src.buffer.clone().readUtf8() }.orEmpty()
        }
        val body = html
            .replace(Regex("""<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        return plain(body).replace(Regex("""\s+"""), " ").trim().take(3000)
    }

    private fun plain(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace('\n', ' ').trim()

    @Suppress("unused")
    private fun decode(s: String) = URLDecoder.decode(s, "UTF-8")
}
