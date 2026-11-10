package com.aiapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aiapp.ui.AppTheme
import com.aiapp.ui.BuildScreen
import com.aiapp.ui.BuildViewModel
import com.aiapp.ui.ChatScreen
import com.aiapp.ui.ChatViewModel
import com.aiapp.ui.CodespaceScreen
import com.aiapp.ui.CreateScreen
import com.aiapp.ui.CreateViewModel
import com.aiapp.ui.HistoryScreen
import com.aiapp.ui.LogScreen
import com.aiapp.ui.ReverseScreen
import com.aiapp.ui.ReverseViewModel
import com.aiapp.ui.SettingsScreen
import com.aiapp.data.Log

class MainActivity : ComponentActivity() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // App hidden or memory low: drop the offline model and image cache (they reload when needed).
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            com.aiapp.ui.ImageLoader.clear()
            if (!com.aiapp.builder.BuildRunner.running) com.aiapp.llm.Providers.offlineIfLoaded?.release()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.init(applicationContext)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Log.e("CRASH", "Uncaught exception on ${thread.name}", e)
            prev?.uncaughtException(thread, e)
        }
        setContent {
            AppTheme {
                val vm: ChatViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf("chat") }
                BackHandler(enabled = screen != "chat") { screen = "chat" }
                val buildVm: BuildViewModel = viewModel()
                val createVm: CreateViewModel = viewModel()
                val reverseVm: ReverseViewModel = viewModel()
                when (screen) {
                    "chat" -> ChatScreen(
                        vm,
                        onOpenSettings = { screen = "settings" },
                        onOpenBuild = { screen = "build" },
                        onOpenHistory = { screen = "history" },
                        onOpenCreate = { screen = "create" },
                        onOpenReverse = { screen = "reverse" },
                        onOpenLog = { screen = "log" },
                        onOpenCode = { screen = "codespace" },
                    )
                    "history" -> HistoryScreen(vm, onBack = { screen = "chat" })
                    "log" -> LogScreen(onBack = { screen = "chat" })
                    "codespace" -> CodespaceScreen(onBack = { screen = "chat" })
                    "create" -> CreateScreen(createVm, onBack = { screen = "chat" }, onOpenSettings = { screen = "settings" })
                    "build" -> BuildScreen(vm, buildVm, onBack = { screen = "chat" }, onOpenSettings = { screen = "settings" })
                    "reverse" -> ReverseScreen(vm, reverseVm, onBack = { screen = "chat" })
                    else -> SettingsScreen(vm, onBack = { screen = "chat" })
                }
            }
        }
    }
}
