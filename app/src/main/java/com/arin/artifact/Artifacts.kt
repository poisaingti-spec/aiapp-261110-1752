package com.arin.artifact

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arin.design.LocalArinExtras
import com.arin.design.MarkdownText

/** A piece of a reply worth opening on its own: a web page, an SVG, a document or a long program. */
data class Artifact(val title: String, val language: String, val content: String) {
    val kind: Kind = when (language.lowercase()) {
        "html", "htm" -> Kind.Web
        "svg" -> Kind.Svg
        "markdown", "md" -> Kind.Document
        else -> Kind.Code
    }

    enum class Kind(val label: String) { Web("Web page"), Svg("Image"), Document("Document"), Code("Code") }

    val hasPreview: Boolean get() = kind != Kind.Code
}

object Artifacts {
    private val fence = Regex("""```([\w+#.-]*)[^\n]*\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    /** Shorter code stays inline in the reply. */
    private const val MIN_CODE_LINES = 15

    fun find(reply: String): List<Artifact> = fence.findAll(reply).mapNotNull { m ->
        val lang = m.groupValues[1].lowercase()
        val body = m.groupValues[2]
        val lines = body.lines().size
        val isWeb = lang in setOf("html", "htm") || (lang.isEmpty() && body.trimStart().startsWith("<!DOCTYPE", ignoreCase = true))
        val isSvg = lang == "svg" || (lang in setOf("xml", "") && body.trimStart().startsWith("<svg"))
        val isDoc = lang in setOf("markdown", "md") && lines >= 5
        when {
            isWeb -> Artifact(titleOf(body, "Web page"), "html", body)
            isSvg -> Artifact("Image", "svg", body)
            isDoc -> Artifact(titleOf(body, "Document"), "markdown", body)
            lines >= MIN_CODE_LINES -> Artifact(lang.ifEmpty { "Code" }.replaceFirstChar { it.uppercase() } + " · $lines lines", lang, body)
            else -> null
        }
    }.toList()

    private fun titleOf(body: String, fallback: String): String {
        Regex("""<title>(.*?)</title>""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].trim().take(60) }
        Regex("""^#\s+(.+)$""", RegexOption.MULTILINE).find(body)?.let { return it.groupValues[1].trim().take(60) }
        return fallback
    }
}

/** A card under a reply that opens the artifact. */
@Composable
fun ArtifactCard(a: Artifact, onOpen: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when (a.kind) {
                        Artifact.Kind.Web -> "</>"
                        Artifact.Kind.Svg -> "◆"
                        Artifact.Kind.Document -> "¶"
                        Artifact.Kind.Code -> "{ }"
                    },
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    a.kind.label + if (a.hasPreview) " · tap to preview" else " · tap to open",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Full-screen viewer: Preview (live web page, image or formatted document) and Code, with Copy and Share. */
@Composable
fun ArtifactViewer(a: Artifact, onClose: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var tab by remember(a) { mutableIntStateOf(if (a.hasPreview) 0 else 1) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
                    Text(a.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { clipboard.setText(AnnotatedString(a.content)) }) { Text("Copy") }
                    TextButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, a.title)
                            .putExtra(Intent.EXTRA_TEXT, a.content)
                        context.startActivity(Intent.createChooser(send, "Share ${a.title}"))
                    }) { Text("Share") }
                }
                if (a.hasPreview) {
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Preview") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Code") })
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        tab == 1 -> CodeView(a.content)
                        a.kind == Artifact.Kind.Document ->
                            SelectionContainer {
                                MarkdownText(a.content, Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp))
                            }
                        a.kind == Artifact.Kind.Svg -> WebPreview(
                            "<html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                                "<style>body{margin:0;display:flex;align-items:center;justify-content:center;min-height:100vh;background:#fff}" +
                                "svg{max-width:100%;height:auto}</style></head><body>${a.content}</body></html>",
                        )
                        else -> WebPreview(a.content)
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeView(code: String) {
    val extras = LocalArinExtras.current
    SelectionContainer {
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.fillMaxSize()
                .background(extras.codeBackground)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(14.dp),
        )
    }
}

/** Runs the page in a WebView with scripts on, no file access, and links opening in the browser. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun WebPreview(html: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        val url = request.url
                        if (url.scheme == "http" || url.scheme == "https") {
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                            return true
                        }
                        return false
                    }
                }
                loadDataWithBaseURL("https://arin.local/", html, "text/html", "utf-8", null)
            }
        },
        update = { },
    )
}
