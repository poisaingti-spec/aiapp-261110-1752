package com.arin.design

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders the Markdown models usually write: headings, lists, quotes, bold, italic,
 * inline code and fenced code blocks (with a Copy button).
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is Block.Code -> CodeBlock(b.language, b.code)
                is Block.Heading -> Text(
                    inline(b.text),
                    style = when (b.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 4.dp),
                )
                is Block.Item -> Row(Modifier.padding(start = (b.indent * 14).dp)) {
                    Text(b.marker, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text(" ", style = MaterialTheme.typography.bodyLarge)
                    Text(inline(b.text), style = MaterialTheme.typography.bodyLarge)
                }
                is Block.Quote -> Row {
                    androidx.compose.foundation.layout.Box(
                        Modifier.width(3.dp).padding(vertical = 2.dp)
                            .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(2.dp)),
                    ) { Text(" ") }
                    Text(
                        inline(b.text),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                }
                is Block.Rule -> androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp))
                is Block.Paragraph -> Text(inline(b.text), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    val extras = LocalArinExtras.current
    Column(
        Modifier.fillMaxWidth()
            .background(extras.codeBackground, RoundedCornerShape(12.dp)),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                language.ifBlank { "code" },
                style = MaterialTheme.typography.labelMedium,
                color = extras.subtle,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }) { Text("Copy") }
        }
        Text(
            code,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        )
    }
}

private sealed interface Block {
    data class Code(val language: String, val code: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Item(val marker: String, val text: String, val indent: Int) : Block
    data class Quote(val text: String) : Block
    data class Paragraph(val text: String) : Block
    data object Rule : Block
}

private val numbered = Regex("""^(\s*)(\d+)[.)]\s+(.*)$""")
private val bulleted = Regex("""^(\s*)[-*+]\s+(.*)$""")
private val heading = Regex("""^(#{1,6})\s+(.*)$""")

private fun parseBlocks(text: String): List<Block> {
    val out = mutableListOf<Block>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim())
        para.clear()
    }
    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                out += Block.Code(lang, code.toString())
            }
            trimmed.isEmpty() -> flush()
            trimmed == "---" || trimmed == "***" -> { flush(); out += Block.Rule }
            heading.matches(trimmed) -> {
                flush()
                val m = heading.find(trimmed)!!
                out += Block.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            bulleted.matches(line) -> {
                flush()
                val m = bulleted.find(line)!!
                out += Block.Item("•", m.groupValues[2], m.groupValues[1].length / 2)
            }
            numbered.matches(line) -> {
                flush()
                val m = numbered.find(line)!!
                out += Block.Item("${m.groupValues[2]}.", m.groupValues[3], m.groupValues[1].length / 2)
            }
            trimmed.startsWith(">") -> { flush(); out += Block.Quote(trimmed.removePrefix(">").trim()) }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flush()
    return out
}

/** Bold, italic, inline code and links (shown as their text). */
@Composable
private fun inline(text: String): AnnotatedString {
    val codeBg = LocalArinExtras.current.codeBackground
    val link = MaterialTheme.colorScheme.primary
    return remember(text, codeBg, link) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val rest = text.substring(i)
                when {
                    rest.startsWith("**") && rest.indexOf("**", 2) > 2 -> {
                        val end = rest.indexOf("**", 2)
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(2, end)) }
                        i += end + 2
                    }
                    rest.startsWith("`") && rest.indexOf('`', 1) > 1 -> {
                        val end = rest.indexOf('`', 1)
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, fontSize = 14.sp)) {
                            append(" ${rest.substring(1, end)} ")
                        }
                        i += end + 1
                    }
                    (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 2 && !rest[1].isWhitespace() &&
                        rest.indexOf(rest[0], 1) > 1 -> {
                        val end = rest.indexOf(rest[0], 1)
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(rest.substring(1, end)) }
                        i += end + 1
                    }
                    rest.startsWith("[") && rest.indexOf("](") > 0 && rest.indexOf(')', rest.indexOf("](")) > 0 -> {
                        val mid = rest.indexOf("](")
                        val end = rest.indexOf(')', mid)
                        withStyle(SpanStyle(color = link)) { append(rest.substring(1, mid)) }
                        i += end + 1
                    }
                    else -> {
                        append(text[i])
                        i++
                    }
                }
            }
        }
    }
}
