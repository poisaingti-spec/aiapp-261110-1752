package com.arin.selffix

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Asked in every tab once the code is written: where to build it. [onPick] gets "github", "phone", "codespace",
 * "chat" (GitHub, followed in a chat), "buildtab" (GitHub, followed in the Build tab) or "auto".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhereToBuild(onPick: (String) -> Unit, showChat: Boolean = true, showBuildTab: Boolean = true, extra: @Composable () -> Unit = {}) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Where should I build it?", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { onPick("github") }) { Text("On GitHub") }
            OutlinedButton(onClick = { onPick("phone") }) { Text("On this phone") }
            OutlinedButton(onClick = { onPick("codespace") }) { Text("In a Codespace") }
            if (showChat) OutlinedButton(onClick = { onPick("chat") }) { Text("Show it in a chat") }
            if (showBuildTab) OutlinedButton(onClick = { onPick("buildtab") }) { Text("In the Build tab") }
            OutlinedButton(onClick = { onPick("auto") }) { Text("Let Arin decide") }
            extra()
        }
    }
}
