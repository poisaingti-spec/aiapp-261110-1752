package com.arin.builder

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One line in the build summary. */
private data class SummaryStep(val text: String, val kind: BuildEvent.Kind)

/** The build's steps, short and in plain words: the newest of each run of thinking/progress lines is enough. */
private fun summarize(timeline: List<TimelineItem>): List<SummaryStep> {
    val out = mutableListOf<SummaryStep>()
    timeline.forEach { t ->
        when (t) {
            is TimelineItem.Event -> {
                val text = t.event.text.lines().first().trim().take(220)
                if (text.isEmpty()) return@forEach
                if (t.event.kind == BuildEvent.Kind.THINKING && out.lastOrNull()?.kind == BuildEvent.Kind.THINKING) out.removeAt(out.lastIndex)
                out += SummaryStep(text, t.event.kind)
            }
            is TimelineItem.Result -> out += SummaryStep(
                (if (t.success) "Done: " else "Failed: ") + t.message.take(200),
                if (t.success) BuildEvent.Kind.DONE else BuildEvent.Kind.ERROR,
            )
            else -> {}
        }
    }
    return out
}

/** Steps that do something (write a file, compile, add a library): shown bold with a terminal mark, like a command. */
private fun isAction(kind: BuildEvent.Kind) =
    kind == BuildEvent.Kind.FILE || kind == BuildEvent.Kind.BUILD || kind == BuildEvent.Kind.LIBRARY || kind == BuildEvent.Kind.DOWNLOAD

/**
 * A sheet listing what the build has done so far, one step per line on a timeline, with the current step at the
 * bottom (it shimmers while the build runs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildSummarySheet(onClose: () -> Unit, sessionId: String? = null) {
    val s = BuildRunner.session(sessionId) ?: BuildRunner.current
    StepsSummarySheet(
        steps = summarize(s.timeline.toList()).map { it.text to it.kind },
        running = s.running,
        doing = s.progressText,
        onClose = onClose,
        emptyText = "No build steps yet.",
    )
}

/**
 * The summary sheet used everywhere (Build tab, chat builds, and chat replies that run commands, install tools or
 * edit files): one step per line on a timeline, with the current step at the bottom shimmering while work runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepsSummarySheet(
    steps: List<Pair<String, BuildEvent.Kind>>,
    running: Boolean,
    doing: String,
    onClose: () -> Unit,
    emptyText: String = "No steps yet.",
    title: String = "Summary",
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val list = rememberLazyListState()
    // Maximise: everything the models are doing, word for word (Claude's thinking, every search, command and file).
    var full by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    // A clock for "working for 2 min 10 s · last activity 4 s ago".
    val opened = androidx.compose.runtime.remember { System.currentTimeMillis() }
    var now by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) { while (running) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1_000) } }
    LaunchedEffect(steps.size) { if (steps.isNotEmpty()) list.animateScrollToItem(steps.size + if (running) 0 else -1) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                Text(if (full) "What's happening — live" else title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
                androidx.compose.material3.TextButton(onClick = { full = !full }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text(if (full) "Minimise" else "Maximise")
                }
            }
            if (full) {
                val feed = LiveFeed.lines
                val feedList = rememberLazyListState()
                LaunchedEffect(feed.size) { if (feed.isNotEmpty()) feedList.scrollToItem(feed.lastIndex) }
                val fmt = androidx.compose.runtime.remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }
                if (running) Text(activityLine(now, opened), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 20.dp))
                LazyColumn(state = feedList, modifier = Modifier.fillMaxWidth().heightIn(min = 500.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) {
                    if (feed.isEmpty()) item { Text("Nothing yet — lines appear here as soon as a model starts working.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    itemsIndexed(feed) { _, l ->
                        Text(
                            fmt.format(java.util.Date(l.time)) + "  " + l.who + "  " + l.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = when {
                                l.who.startsWith("Claude") -> Color(0xFFD97757)
                                l.who == "…" -> MaterialTheme.colorScheme.onSurfaceVariant
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                return@Column
            }
            if (steps.isEmpty() && !running) {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)) {
                itemsIndexed(steps) { i, s ->
                    val last = i == steps.lastIndex
                    StepRow(SummaryStep(s.first, s.second), current = false, lineBelow = !last || running)
                }
                if (running) {
                    item {
                        StepRow(SummaryStep((doing.takeIf { it.isNotBlank() }?.take(120) ?: "Working") + "\n" + activityLine(now, opened), BuildEvent.Kind.STEP), current = true, lineBelow = false)
                    }
                }
            }
        }
    }
}

/** "Working for 2 min 10 s · last activity 4 s ago — Claude: searching…" (never just "Working"). */
private fun activityLine(now: Long, opened: Long): String {
    fun ago(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s" }
    val last = LiveFeed.lastAt
    return "⏱ open ${ago(now - opened)}" + if (last > 0) " · last activity ${ago(now - last)} ago — " + LiveFeed.lastLine.take(110) else ""
}

@Composable
private fun StepRow(s: SummaryStep, current: Boolean, lineBelow: Boolean) {
    val action = isAction(s.kind)
    val line = MaterialTheme.colorScheme.outlineVariant
    val accent = Color(0xFFD97757)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // The timeline: a mark for the step, and a line down to the next one.
        Column(Modifier.width(36.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (action) 4.dp else 10.dp))
            when {
                action -> Box(
                    Modifier.size(30.dp).border(1.dp, line, RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(">_", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                else -> Box(
                    Modifier.size(12.dp).background(
                        when {
                            current -> accent
                            s.kind == BuildEvent.Kind.ERROR -> MaterialTheme.colorScheme.error
                            s.kind == BuildEvent.Kind.DONE -> Color(0xFF2E9E6B)
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                        CircleShape,
                    ),
                )
            }
            if (lineBelow) Box(Modifier.width(1.5.dp).weight(1f).background(if (current) accent else line))
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).padding(top = 4.dp, bottom = 18.dp)) {
            if (current) {
                ShimmerText(s.text)
            } else {
                Text(
                    s.text,
                    style = if (action) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                    fontWeight = if (action) FontWeight.SemiBold else FontWeight.Normal,
                    color = when (s.kind) {
                        BuildEvent.Kind.ERROR -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}

/** Text with a light band sweeping across it, for the step that is happening now. */
@Composable
private fun ShimmerText(text: String) {
    val base = MaterialTheme.colorScheme.onSurface
    val shine = MaterialTheme.colorScheme.outlineVariant
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val brush = Brush.linearGradient(
        colors = listOf(base, base, shine, base, base),
        start = androidx.compose.ui.geometry.Offset(-600f + 1600f * x, 0f),
        end = androidx.compose.ui.geometry.Offset(-200f + 1600f * x, 0f),
    )
    Text(text, style = MaterialTheme.typography.bodyLarge.merge(TextStyle(brush = brush)))
}
