package com.arin.app

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arin.CrashLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Crashes and errors saved on the phone, ready to copy or share so they can be fixed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var version by remember { mutableStateOf(0) }
    val entries = remember(version) { CrashLog.entries(context) }
    var open by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val time = remember { SimpleDateFormat("d MMM, HH:mm", Locale.ENGLISH) }

    fun share(text: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Arin log").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, "Share log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    val all = entries.joinToString("\n\n----------\n\n") { CrashLog.format(it) }.take(60_000)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logs") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    "Crashes and errors are saved here. If something goes wrong, tap Copy all (or Share) and send it, so it can be fixed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = entries.isNotEmpty(), onClick = { clipboard.setText(AnnotatedString(all)); note = "All logs copied" }) { Text("Copy all") }
                    TextButton(enabled = entries.isNotEmpty(), onClick = { share(all) }) { Text("Share") }
                    TextButton(enabled = entries.isNotEmpty(), onClick = { confirmClear = true }) { Text("Clear") }
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            if (entries.isEmpty()) {
                item { Text("No crashes or errors so far.", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 24.dp)) }
            }
            items(entries, key = { it.file.name }) { e ->
                val expanded = open == e.file.name
                Surface(
                    color = if (e.kind == "Crash") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().clickable { open = if (expanded) null else e.file.name },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${e.kind} · ${time.format(Date(e.time))}", style = MaterialTheme.typography.labelMedium)
                        Text(e.title, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) 6 else 2)
                        if (expanded) {
                            SelectionContainer {
                                Text(e.text.take(8_000), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                            }
                            Row {
                                TextButton(onClick = { clipboard.setText(AnnotatedString(CrashLog.format(e))); note = "Log copied" }) { Text("Copy") }
                                TextButton(onClick = { share(CrashLog.format(e)) }) { Text("Share") }
                                TextButton(onClick = { CrashLog.delete(e); version++ }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear all logs?") },
            confirmButton = { TextButton(onClick = { CrashLog.clear(context); version++; confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
