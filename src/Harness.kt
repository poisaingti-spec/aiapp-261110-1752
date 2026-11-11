import com.aiapp.builder.ProjectTemplate
import com.aiapp.data.Downloads
import com.aiapp.data.StudioProjectFiles
import java.io.File

val reply1 = """
Here is the project.
=== FILE: app/src/main/java/com/crafted/demo/MainActivity.kt ===
package com.crafted.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.crafted.demo.ui.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { HomeScreen(title = getString(R.string.welcome_title)) } }
    }
}
=== END FILE ===
=== FILE: app/src/main/java/com/crafted/demo/ui/HomeScreen.kt ===
package com.crafted.demo.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun HomeScreen(title: String) {
    Text(title + undefinedThing)
}
=== END FILE ===
=== FILE: app/src/main/java/com/crafted/demo/data/Repo.kt ===
package com.crafted.demo.data

import kotlinx.serialization.Serializable

@Serializable
data class Item(val id: Int, val name: String)

object Repo { fun items() = listOf(Item(1, "One"), Item(2, "Two")) }
=== END FILE ===
=== FILE: app/src/main/AndroidManifest.xml ===
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.crafted.demo">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:name=".DemoApp" android:label="@string/app_name" android:icon="@mipmap/ic_launcher" android:theme="@style/Theme.Demo" android:dataExtractionRules="@xml/data_extraction_rules">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <activity android:name=".SettingsActivity" android:exported="false" />
        <service android:name=".sync.SyncService" android:exported="false" />
        <receiver android:name=".BootReceiver" android:exported="false" />
        <provider android:name=".DemoProvider" android:authorities="com.crafted.demo.provider" android:exported="false" />
    </application>
</manifest>
=== END FILE ===
=== FILE: app/src/main/res/values/strings.xml ===
<resources><string name="app_name">Demo</string></resources>
=== END FILE ===
"""
val reply2 = """
Fixed the error.
=== FILE: app/src/main/java/com/crafted/demo/ui/HomeScreen.kt ===
package com.crafted.demo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import com.crafted.demo.R
import com.crafted.demo.data.Repo

@Composable
fun HomeScreen(title: String) {
    Column(Modifier.padding(16.dp)) {
        Text(title)
        Image(painterResource(R.drawable.ic_logo), contentDescription = null)
        AsyncImage(model = "https://example.com/a.png", contentDescription = null)
        Repo.items().forEach { Text(it.name) }
    }
}
=== END FILE ===
"""

/** What Downloads.compileChatToSourceZip hands over today for the latest reply only. */
fun packagerOutput(latest: String, app: String, pkg: String): MutableMap<String, ByteArray> {
    val out = linkedMapOf<String, ByteArray>()
    ProjectTemplate.files(app, pkg, emptyList()).forEach { (k, v) -> out[k] = v.toByteArray() }
    out["app/src/main/AndroidManifest.xml"] = ProjectTemplate.defaultManifest(app).toByteArray()
    val ex = LinkedHashMap(Downloads.extractCodeFilesFromText(latest))
    if (ex.values.none { it.contains("class MainActivity") }) {
        val first = ex.entries.first { it.key.endsWith(".kt") }
        ex.remove(first.key); ex["app/src/main/java/${pkg.replace('.', '/')}/MainActivity.kt"] = first.value
    }
    ex.forEach { (k, v) -> out[k] = v.toByteArray() }
    return out
}

fun main(args: Array<String>) {
    val proj = File(args[0])
    val calls = mutableListOf<String>()
    val env = StudioProjectFiles.Env(
        cacheDir = File(args[1]).apply { mkdirs() },
        openAsset = { null },
        http = { url -> StudioProjectFiles.httpGet(url).also { calls += "${if (it == null) "FAIL" else "ok  "} $url" } },
    )
    val files = packagerOutput(reply2, "DemoCraft", "com.crafted.demo")
    val tc = StudioProjectFiles.completeCore(env, files, listOf(reply1, reply2), "DemoCraft", "com.crafted.demo", imported = false)
    println("toolchain: $tc")
    proj.deleteRecursively()
    files.toSortedMap().forEach { (k, v) -> File(proj, k).apply { parentFile.mkdirs() }.writeBytes(v); println("  %-72s %6d".format(k, v.size)) }
    println("http calls:\n  " + calls.joinToString("\n  "))
    check(tc.online) { "version lookup did not work online" }
    check(File(proj, "gradle/wrapper/gradle-wrapper.jar").length() > 10_000) { "wrapper jar missing" }
}
