package com.arin.app

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arin.chat.ChatViewModel

/**
 * Handles how Arin was opened from outside: another app's Share button, the home-screen
 * widget, or a "reply finished" notification.
 */
object Launch {
    const val ACTION_NEW_CHAT = "com.arin.NEW_CHAT"
    const val ACTION_VOICE = "com.arin.VOICE"

    /** Goes up each time something arrives, so the shell switches to Chat. */
    var count by mutableIntStateOf(0)
        private set

    /** Set by the widget's mic button; Chat opens voice mode and clears it. */
    var voicePending by mutableStateOf(false)

    fun handle(intent: Intent?, chat: ChatViewModel) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> share(intent, chat)
            ACTION_NEW_CHAT -> { chat.newChat(); count++ }
            ACTION_VOICE -> { chat.newChat(); voicePending = true; count++ }
            else -> intent.getStringExtra(ReplyService.EXTRA_CHAT_ID)?.let { id -> chat.open(id); count++ }
        }
    }

    private fun share(intent: Intent, chat: ChatViewModel) {
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(stream(intent))
            else -> streams(intent)
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (uris.isEmpty() && text.isEmpty()) return
        if (uris.isNotEmpty()) chat.attach(uris)
        if (text.isNotEmpty()) chat.sharedText = text
        count++
    }

    @Suppress("DEPRECATION")
    private fun stream(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
}
