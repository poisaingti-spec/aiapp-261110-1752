package com.arin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.arin.app.AppShell
import com.arin.app.Launch
import com.arin.chat.ChatViewModel
import com.arin.design.ArinTheme

class MainActivity : ComponentActivity() {
    private val chat: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Opened from a share, the widget or a notification (and not a rotation replaying that).
        if (savedInstanceState == null) Launch.handle(intent, chat)
        setContent {
            ArinTheme(chat.theme) { AppShell(chat) }
        }
    }

    /** Arin was already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Launch.handle(intent, chat)
    }
}
