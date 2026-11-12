package com.arin.app

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.arin.chat.ChatScreen
import com.arin.chat.ChatViewModel
import com.arin.project.ProjectScreen
import com.arin.project.ProjectsScreen
import com.arin.settings.SettingsScreen

/** Every top-level screen. Chat is home. */
enum class Screen { Chat, Settings, Projects, Project, Build }

/** Owns which screen is showing. Screens never navigate themselves; they call back here. */
@Composable
fun AppShell(vm: ChatViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var backToProjects by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = screen != Screen.Chat) {
        screen = if (screen == Screen.Project && backToProjects) Screen.Projects else Screen.Chat
    }
    // Something arrived from outside (a share, the widget, a notification): show Chat.
    LaunchedEffect(Launch.count) { if (Launch.count > 0) screen = Screen.Chat }
    // Chat started a build: show it.
    LaunchedEffect(vm.openBuild) {
        if (vm.openBuild) {
            vm.openBuild = false
            screen = Screen.Build
        }
    }

    when (screen) {
        Screen.Chat -> ChatScreen(
            vm,
            onOpenSettings = { screen = Screen.Settings },
            onOpenProjects = { screen = Screen.Projects },
            onOpenProject = { p -> projectId = p.id; backToProjects = false; screen = Screen.Project },
            onOpenBuild = { screen = Screen.Build },
            startVoice = Launch.voicePending,
            onVoiceStarted = { Launch.voicePending = false },
        )
        Screen.Settings -> SettingsScreen(vm, onBack = { screen = Screen.Chat })
        Screen.Build -> com.arin.builder.BuildScreen(
            chat = vm,
            vm = androidx.lifecycle.viewmodel.compose.viewModel<com.arin.builder.BuildViewModel>(),
            onBack = { screen = Screen.Chat },
            onOpenSettings = { screen = Screen.Settings },
        )
        Screen.Projects -> ProjectsScreen(
            vm,
            onBack = { screen = Screen.Chat },
            onOpen = { p -> projectId = p.id; backToProjects = true; screen = Screen.Project },
        )
        Screen.Project -> ProjectScreen(
            vm,
            projectId = projectId.orEmpty(),
            onBack = { screen = if (backToProjects) Screen.Projects else Screen.Chat },
            onOpenChat = { screen = Screen.Chat },
        )
    }
}
