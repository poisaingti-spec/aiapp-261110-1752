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
fun BuildSummarySheet(onClose: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val steps = summarize(BuildRunner.timeline.toList())
    val running = BuildRunner.running
    val list = rememberLazyListState()
    LaunchedEffect(steps.size) { if (steps.isNotEmpty()) list.animateScrollToItem(steps.size + if (running) 0 else -1) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(min = 300.dp)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                Text("Summary", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.Center))
            }
            if (steps.isEmpty()) {
                Text(
                    "No build steps yet.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp),
                )
            }
            LazyColumn(state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp)) {
                itemsIndexed(steps) { i, s ->
                    val last = i == steps.lastIndex
                    StepRow(s, current = false, lineBelow = !last || running)
                }
                if (running) {
                    item {
                        val doing = BuildRunner.progressText.takeIf { it.isNotBlank() }?.take(120) ?: "Working"
                        StepRow(SummaryStep(doing, BuildEvent.Kind.STEP), current = true, lineBelow = false)
                    }
                }
            }
        }
    }
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
