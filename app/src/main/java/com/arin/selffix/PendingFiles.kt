package com.arin.selffix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Files waiting to be sent, in ONE compact row however many there are (swipe sideways to see them all), so the message
 * box always stays on screen. [files] = (name, size in bytes).
 */
@Composable
fun PendingFiles(files: List<Pair<String, Long>>, onRemove: (Int) -> Unit, onRemoveAll: () -> Unit, modifier: Modifier = Modifier) {
    if (files.isEmpty()) return
    Column(modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val total = files.sumOf { it.second }
            Text(
                "📎 ${files.size} file${if (files.size == 1) "" else "s"}" + (if (total > 0) " · " + size(total) else "") +
                    if (files.size > 2) "  (swipe to see all)" else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (files.size > 1) TextButton(onClick = onRemoveAll) { Text("Remove all") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(files) { i, (name, bytes) ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                        Column(Modifier.widthIn(max = 160.dp)) {
                            Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (bytes > 0) Text(size(bytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onRemove(i) }) { Text("✕") }
                    }
                }
            }
        }
    }
}

private fun size(b: Long) = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.1f MB".format(b / 1e6)
    else -> "${(b / 1000).coerceAtLeast(1)} KB"
}
