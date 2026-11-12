package com.arin.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arin.chat.ChatViewModel
import com.arin.design.ArinColors
import kotlin.math.PI
import kotlin.math.sin

/**
 * Full-screen voice conversation. Speak, and Arin answers aloud; tap the rings to interrupt
 * or to start listening again. Everything said is saved in the current chat.
 */
@Composable
fun VoiceMode(vm: ChatViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var spokenUpTo by remember { mutableIntStateOf(0) }
    // Set when the person interrupts: the cut-off reply is saved but not read out.
    var skipNext by remember { mutableStateOf(false) }
    val voice = remember {
        VoiceController(
            context,
            onHeard = { heard -> vm.send(heard) },
            onInterrupt = { if (vm.streaming) { skipNext = true; vm.stop() } },
        )
    }

    DisposableEffect(Unit) {
        voice.rate = vm.settings.voiceRate
        voice.start()
        onDispose {
            voice.close()
            vm.stop()
        }
    }
    // Speak the reply sentence by sentence as it streams in.
    LaunchedEffect(Unit) {
        snapshotFlow { vm.streaming to vm.liveText }.collect { (streaming, text) ->
            if (streaming && text.isEmpty() && spokenUpTo != 0) spokenUpTo = 0
            if (streaming) {
                if (text.isEmpty()) voice.replyStarted()
                val (sentences, next) = VoiceController.nextSentences(text, spokenUpTo, final = false)
                sentences.forEach { voice.say(it) }
                spokenUpTo = next
            }
        }
    }
    LaunchedEffect(Unit) {
        var seen = vm.lastFinished
        snapshotFlow { vm.lastFinished }.collect { msg ->
            if (msg == null || msg === seen) return@collect
            seen = msg
            if (skipNext) {
                skipNext = false
                spokenUpTo = 0
                return@collect
            }
            if (msg.failed) voice.say(msg.text) else {
                val (rest, _) = VoiceController.nextSentences(msg.text, spokenUpTo.coerceAtMost(msg.text.length), final = true)
                rest.forEach { voice.say(it) }
            }
            spokenUpTo = 0
            voice.replyFinished()
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = ArinColors.Night, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth()) {
                    IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Icon(Icons.Filled.Close, contentDescription = "End voice mode", tint = androidx.compose.ui.graphics.Color.White)
                    }
                }
                Spacer(Modifier.weight(1f))
                Rings(
                    state = voice.state,
                    level = voice.level,
                    modifier = Modifier.size(260.dp).clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { voice.tap() },
                )
                Spacer(Modifier.height(36.dp))
                Text(
                    when (voice.state) {
                        VoiceState.Starting -> "Getting ready…"
                        VoiceState.Listening -> "Listening"
                        VoiceState.Thinking -> if (vm.status != null) "Searching…" else "Thinking…"
                        VoiceState.Speaking -> "Tap to interrupt"
                        VoiceState.Paused -> "Tap to talk"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = androidx.compose.ui.graphics.Color.White,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    voice.error ?: voice.partial,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (voice.error != null) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color(0xFFB7C2CF),
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Uses your phone's speech recognition and voice. Replies are saved in this chat.",
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color(0xFF6E7887),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Arin's voice visual: three soft rings that breathe while thinking and swell with sound. */
@Composable
private fun Rings(state: VoiceState, level: Float, modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "rings")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val loud by animateFloatAsState(if (state == VoiceState.Listening) level else 0f, tween(120), label = "loud")
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val base = size.minDimension * 0.22f
        for (i in 0 until 3) {
            val wave = sin((phase + i / 3f) * 2 * PI).toFloat()
            val swing = when (state) {
                VoiceState.Speaking -> 0.10f + 0.06f * wave
                VoiceState.Thinking -> 0.05f * wave
                VoiceState.Listening -> 0.03f * wave + loud * 0.25f
                else -> 0.02f * wave
            }
            val r = base * (1f + i * 0.45f) * (1f + swing)
            drawCircle(
                brush = Brush.sweepGradient(listOf(ArinColors.Teal, ArinColors.Violet, ArinColors.Teal), center = c),
                radius = r,
                center = c,
                alpha = 0.9f - i * 0.25f,
                style = Stroke(width = (10f - i * 2.5f).dp.toPx()),
            )
        }
        drawCircle(
            brush = Brush.radialGradient(listOf(ArinColors.Teal.copy(alpha = 0.55f), ArinColors.Night), center = c, radius = base),
            radius = base * (0.85f + loud * 0.3f),
            center = c,
        )
    }
}

@Suppress("unused")
private val spacing = Arrangement.Center
