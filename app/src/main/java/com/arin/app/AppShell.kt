package com.arin.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
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
enum class Screen { Chat, Settings, Projects, Project, Build, Logs, FreeTokens, ClaudeCode, MovieMaker, ArinUpdate, Security }

/** Owns which screen is showing. Screens never navigate themselves; they call back here. */
@Composable
fun AppShell(vm: ChatViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var backToProjects by rememberSaveable { mutableStateOf(false) }
    var settingsFromClaudeCode by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = screen != Screen.Chat) {
        screen = when {
            screen == Screen.Project && backToProjects -> Screen.Projects
            screen == Screen.Settings && settingsFromClaudeCode -> Screen.ClaudeCode
            else -> Screen.Chat
        }
        if (screen != Screen.Settings) settingsFromClaudeCode = false
    }
    // Something arrived from outside (a share, the widget, a notification): show Chat.
    LaunchedEffect(Launch.count) { if (Launch.count > 0) screen = Screen.Chat }
    // Chat handed a script to Movie Maker: show it.
    // Talking to the AI helper from a tab without a message box: the chat opens.
    LaunchedEffect(vm.openChat) {
        if (vm.openChat) {
            vm.openChat = false
            screen = Screen.Chat
        }
    }
    LaunchedEffect(vm.openMovie) {
        if (vm.openMovie) {
            vm.openMovie = false
            screen = Screen.MovieMaker
        }
    }
    // Chat started a build: show it.
    LaunchedEffect(vm.openBuild) {
        if (vm.openBuild) {
            vm.openBuild = false
            screen = Screen.Build
        }
    }

    // Paint the theme background behind every screen. Without this, screens that are a bare Column
    // (Movie Maker, Claude Code, Free tokens, Logs) show the window's dark background under the
    // light theme's dark text, so the text can't be seen.
    androidx.compose.runtime.CompositionLocalProvider(com.arin.selffix.LocalAiHelperVm provides vm) { // ARIN-PROTECTED: ai-helper in every tab
    androidx.compose.material3.Surface(
        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
        color = androidx.compose.material3.MaterialTheme.colorScheme.background,
        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
    ) {
    // Each tab has its own AI helper switch.
    val helperTab = when (screen) {
        Screen.Chat, Screen.Project, Screen.Projects -> "chat"
        Screen.Build -> "build"
        Screen.ArinUpdate -> "arin-update"
        Screen.ClaudeCode -> "claude-code"
        Screen.MovieMaker -> "movie"
        Screen.Security -> "security"
        else -> screen.name.lowercase()
    }
    androidx.compose.runtime.LaunchedEffect(helperTab) { vm.enterModelTab(helperTab) } // each tab keeps its own model
    androidx.compose.runtime.CompositionLocalProvider(com.arin.selffix.LocalHelperTab provides helperTab) {
    when (screen) {
        Screen.Chat -> ChatScreen(
            vm,
            onOpenSettings = { settingsFromClaudeCode = false; screen = Screen.Settings },
            onOpenProjects = { screen = Screen.Projects },
            onOpenProject = { p -> projectId = p.id; backToProjects = false; screen = Screen.Project },
            onOpenBuild = { screen = Screen.Build },
            onOpenLogs = { screen = Screen.Logs },
            onOpenFree = { screen = Screen.FreeTokens },
            onOpenClaudeCode = { screen = Screen.ClaudeCode },
            onOpenMovieMaker = { screen = Screen.MovieMaker },
            onOpenSecurity = { screen = Screen.Security },
            onOpenArinUpdate = { screen = Screen.ArinUpdate },
            startVoice = Launch.voicePending,
            onVoiceStarted = { Launch.voicePending = false },
        )
        Screen.Settings -> SettingsScreen(
            vm,
            onBack = { screen = if (settingsFromClaudeCode) Screen.ClaudeCode else Screen.Chat; settingsFromClaudeCode = false },
            openClaudeCode = settingsFromClaudeCode,
        )
        Screen.Logs -> LogsScreen(onBack = { screen = Screen.Chat }, vm = vm)
        Screen.FreeTokens -> com.arin.free.FreeTokensScreen(vm, onBack = { screen = Screen.Chat })
        Screen.ClaudeCode -> com.arin.claudecode.ClaudeCodeScreen(
            vm,
            onBack = { screen = Screen.Chat },
            onOpenSettings = { settingsFromClaudeCode = true; screen = Screen.Settings },
        )
        Screen.MovieMaker -> com.arin.movie.MovieMakerScreen(vm, onBack = { screen = Screen.Chat })
        Screen.Security -> com.arin.security.SecurityScreen(vm, onBack = { screen = Screen.Chat })
        Screen.ArinUpdate -> com.arin.selffix.ArinUpdateScreen(vm, onBack = { screen = Screen.Chat })
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
    } // helper tab
    }
    // Downloads and installs show their progress on every screen.
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().statusBarsPadding().padding(top = 60.dp), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        com.arin.selffix.InstallProgressPill()
    }
    }
}
