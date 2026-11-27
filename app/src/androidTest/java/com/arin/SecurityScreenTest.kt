package com.arin

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arin.chat.ChatViewModel
import com.arin.security.SecurityScreen
import com.arin.security.TAB_NAMES
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opens the Security screen and every one of its tabs; a layout crash fails the test. */
@RunWith(AndroidJUnit4::class)
class SecurityScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun vm(): ChatViewModel { var v: ChatViewModel? = null; rule.runOnUiThread { v = ChatViewModel(rule.activity.application) }; return v!! }

    private fun visitAllTabs() {
        for (name in TAB_NAMES) {
            rule.onNodeWithText(name).performScrollTo().performClick()
            rule.waitForIdle()
            rule.onNodeWithTag("security-content").assertExists()
        }
    }

    @Test fun everyTabOpensWithoutCrashing() {
        val vm = vm()
        rule.setContent { com.arin.design.ArinTheme(vm.theme) { SecurityScreen(vm, onBack = {}) } }
        visitAllTabs()
    }

    /** Even inside a parent that gives unlimited height (the cause of the reported crash), it must not crash. */
    @Test fun noCrashInsideAnUnboundedParent() {
        val vm = vm()
        rule.setContent { Column(Modifier.verticalScroll(rememberScrollState())) { SecurityScreen(vm, onBack = {}) } }
        visitAllTabs()
    }
}
