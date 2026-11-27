package com.arin.security

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Static security analysis of an APK, done on the phone by Arin's own code (no AI, no credit, nothing uploaded):
 * manifest flags, permissions, exported components, signing, libraries, secrets (always shown redacted), network
 * security config, WebView use and storage. The report is plain Markdown the owner can read, share or hand to the AI
 * to fix in their own app's source.
 */
object ApkAudit {
    data class Finding(val severity: String, val title: String, val detail: String)

    private val DANGEROUS_EXTRA = setOf(
        "android.permission.REQUEST_INSTALL_PACKAGES", "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.QUERY_ALL_PACKAGES",
        "android.permission.BIND_ACCESSIBILITY_SERVICE", "android.permission.BIND_DEVICE_ADMIN",
        "android.permission.WRITE_SETTINGS", "android.permission.READ_LOGS", "android.permission.PACKAGE_USAGE_STATS",
    )

    /** Secret patterns (shown redacted, never in full). */
    private val SECRETS = listOf(
        "Google API key" to Regex("""AIza[0-9A-Za-z_\-]{35}"""),
        "AWS access key" to Regex("""\b(AKIA|ASIA)[0-9A-Z]{16}\b"""),
        "Private key block" to Regex("""-----BEGIN (RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"""),
        "Slack token" to Regex("""xox[baprs]-[0-9A-Za-z-]{10,}"""),
        "Stripe secret key" to Regex("""\bsk_live_[0-9A-Za-z]{16,}"""),
        "GitHub token" to Regex("""\bgh[pousr]_[0-9A-Za-z]{30,}"""),
        "JSON Web Token" to Regex("""\beyJ[0-9A-Za-z_-]{10,}\.eyJ[0-9A-Za-z_-]{10,}\.[0-9A-Za-z_-]{10,}"""),
        "Firebase database URL" to Regex("""https://[a-z0-9-]+\.firebaseio\.com"""),
        "Hard-coded password/secret field" to Regex("""(?i)\b(password|passwd|secret|api_?key|client_secret)\s*[:=]\s*["'][^"'\s]{6,}["']"""),
    )

    private val LIBRARIES = listOf(
        "OkHttp" to "okhttp3/", "Retrofit" to "retrofit2/", "Firebase" to "com/google/firebase/", "Google Play Services" to "com/google/android/gms/",
        "Jetpack Compose" to "androidx/compose/", "Room database" to "androidx/room/", "Gson" to "com/google/gson/", "Glide" to "com/bumptech/glide/",
        "Facebook SDK" to "com/facebook/", "AppsFlyer" to "com/appsflyer/", "Adjust" to "com/adjust/sdk/", "OneSignal" to "com/onesignal/",
        "Unity Ads" to "com/unity3d/ads/", "AdMob" to "com/google/android/gms/ads/", "Flutter" to "io/flutter/", "React Native" to "com/facebook/react/",
        "Xamarin/.NET" to "mono/android/", "SQLCipher" to "net/sqlcipher/", "Kotlin coroutines" to "kotlinx/coroutines/",
    )

    fun redact(s: String): String = if (s.length <= 8) "****" else s.take(4) + "…" + "*".repeat(6) + "…" + s.takeLast(2)

    /** Analyses [apk] and returns a Markdown report. [onStep] shows progress. */
    fun analyse(c: Context, apk: File, onStep: (String) -> Unit = {}): String {
        val f = mutableListOf<Finding>()
        val pm = c.packageManager
        onStep("Reading the manifest")
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
            PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA or
            (if (android.os.Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES)
        @Suppress("DEPRECATION")
        val info: PackageInfo? = runCatching { pm.getPackageArchiveInfo(apk.absolutePath, flags) }.getOrNull()
        val sb = StringBuilder()
        sb.appendLine("# APK security report — ${apk.name}")
        sb.appendLine("_Static analysis on this phone. Secrets are shown redacted. Size: ${apk.length() / 1024} KB._\n")
        if (info == null) {
            sb.appendLine("⚠️ Android couldn't read this file as an APK (it may be split, damaged or not an APK). Only the file scan below ran.\n")
        } else {
            val ai = info.applicationInfo
            ai?.sourceDir = apk.absolutePath; ai?.publicSourceDir = apk.absolutePath
            @Suppress("DEPRECATION") val vc = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            sb.appendLine("## App")
            sb.appendLine("- Package: `${info.packageName}`  ·  version ${info.versionName} ($vc)")
            ai?.let { a ->
                sb.appendLine("- minSdk ${if (android.os.Build.VERSION.SDK_INT >= 24) a.minSdkVersion else "?"} · targetSdk ${a.targetSdkVersion}")
                if (a.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) f += Finding("HIGH", "App is debuggable", "android:debuggable=true lets anyone attach a debugger and read its memory. Turn it off for release builds.")
                if (a.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0) f += Finding("MEDIUM", "Backups allowed", "android:allowBackup=true lets app data be copied off the device with adb backup. Set it to false or use backup rules.")
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT >= 23 && a.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0 && a.targetSdkVersion >= 28)
                    f += Finding("MEDIUM", "Plain HTTP allowed", "usesCleartextTraffic=true: data can travel unencrypted. Use HTTPS only, or limit it with a network security config.")
                if (a.targetSdkVersion < 30) f += Finding("LOW", "Old targetSdk (${a.targetSdkVersion})", "Newer Android protections don't apply. Raise targetSdk.")
            }
            onStep("Checking permissions")
            val perms = info.requestedPermissions?.toList().orEmpty()
            val dangerous = perms.filter { p ->
                p in DANGEROUS_EXTRA || runCatching {
                    val pi = pm.getPermissionInfo(p, 0)
                    @Suppress("DEPRECATION")
                    (if (android.os.Build.VERSION.SDK_INT >= 28) pi.protection else pi.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS
                }.getOrDefault(false)
            }
            sb.appendLine("\n## Permissions (${perms.size})")
            if (perms.isEmpty()) sb.appendLine("- none")
            perms.forEach { p -> sb.appendLine("- " + (if (p in dangerous) "⚠️ " else "") + p.removePrefix("android.permission.")) }
            if (dangerous.size >= 6) f += Finding("MEDIUM", "Many sensitive permissions (${dangerous.size})", "Check each is really needed: " + dangerous.joinToString { it.substringAfterLast('.') })
            dangerous.filter { it in DANGEROUS_EXTRA }.forEach { f += Finding("HIGH", "Powerful permission: ${it.substringAfterLast('.')}", "This permission gives wide control over the device. Make sure it is needed and explained to users.") }

            onStep("Checking exported components")
            sb.appendLine("\n## Components")
            fun comps(kind: String, list: List<Pair<String, Boolean>>, perms: List<String?>) {
                val exported = list.mapIndexedNotNull { i, (n, e) -> if (e && perms.getOrNull(i) == null) n else null }
                sb.appendLine("- $kind: ${list.size} (exported without a permission: ${exported.size})")
                exported.take(12).forEach { sb.appendLine("  - `${it}`") }
                if (kind != "Activities" && exported.isNotEmpty())
                    f += Finding("MEDIUM", "$kind open to other apps (${exported.size})", "Exported without a protecting permission: " + exported.take(5).joinToString { it.substringAfterLast('.') } + ". Other apps can call them; add android:exported=false or a permission.")
            }
            val main = pm.getLaunchIntentForPackage(info.packageName)?.component?.className
            comps("Activities", info.activities?.map { it.name to (it.exported && it.name != main) }.orEmpty(), info.activities?.map { it.permission }.orEmpty())
            comps("Services", info.services?.map { it.name to it.exported }.orEmpty(), info.services?.map { it.permission }.orEmpty())
            comps("Receivers", info.receivers?.map { it.name to it.exported }.orEmpty(), info.receivers?.map { it.permission }.orEmpty())
            comps("Providers", info.providers?.map { it.name to it.exported }.orEmpty(), info.providers?.map { it.readPermission ?: it.writePermission }.orEmpty())

            onStep("Checking the signature")
            sb.appendLine("\n## Signing")
            val certs: List<ByteArray> = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { s -> (if (s.hasMultipleSigners()) s.apkContentsSigners else s.signingCertificateHistory).map { it.toByteArray() } }.orEmpty()
                else @Suppress("DEPRECATION") info.signatures?.map { it.toByteArray() }.orEmpty()
            }.getOrDefault(emptyList())
            if (certs.isEmpty()) { sb.appendLine("- Couldn't read the signature (unsigned or unreadable)."); f += Finding("HIGH", "No readable signature", "Android won't install an unsigned APK, and its origin can't be checked.") }
            certs.forEach { cert ->
                val sha = MessageDigest.getInstance("SHA-256").digest(cert).joinToString(":") { "%02X".format(it) }
                val x = runCatching { java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as java.security.cert.X509Certificate }.getOrNull()
                sb.appendLine("- SHA-256: `$sha`")
                x?.let {
                    sb.appendLine("- Subject: ${it.subjectX500Principal.name}  ·  valid until ${it.notAfter}")
                    if (it.subjectX500Principal.name.contains("Android Debug", true)) f += Finding("HIGH", "Signed with a debug key", "Debug keys are public knowledge; anyone can sign an 'update'. Sign releases with a private key.")
                    if (it.sigAlgName.contains("SHA1", true) || it.sigAlgName.contains("MD5", true)) f += Finding("MEDIUM", "Weak signature algorithm (${it.sigAlgName})", "Use SHA-256 or newer.")
                }
            }
        }

        onStep("Scanning the code and resources")
        val libs = linkedSetOf<String>()
        val secrets = mutableListOf<Pair<String, String>>()
        var webJs = false; var webBridge = false; var worldReadable = false; var sqlite = false; var nsc = false; var natives = 0
        val trustAll = mutableListOf<String>()
        runCatching {
            ZipFile(apk).use { z ->
                for (e in z.entries()) {
                    val n = e.name
                    if (n.startsWith("lib/") && n.endsWith(".so")) natives++
                    if (n.startsWith("res/xml/") && n.contains("network_security", true)) nsc = true
                    val scan = n.endsWith(".dex") || n.endsWith(".json") || n.endsWith(".properties") || n.endsWith(".xml") && !n.startsWith("res/") || n.startsWith("assets/") && e.size < 4_000_000
                    if (!scan || e.size > 64_000_000) continue
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    val text = asciiRuns(bytes)
                    if (n.endsWith(".dex")) {
                        LIBRARIES.forEach { (name, path) -> if (text.contains(path) || text.contains("L$path")) libs += name }
                        if (text.contains("setJavaScriptEnabled")) webJs = true
                        if (text.contains("addJavascriptInterface")) webBridge = true
                        if (text.contains("MODE_WORLD_READABLE") || text.contains("MODE_WORLD_WRITEABLE")) worldReadable = true
                        if (text.contains("SQLiteDatabase") || text.contains("androidx/room/")) sqlite = true
                        if (text.contains("ALLOW_ALL_HOSTNAME_VERIFIER") || text.contains("AllowAllHostnameVerifier")) trustAll += "allow-all hostname verifier"
                        if (text.contains("onReceivedSslError")) trustAll += "custom SSL-error handler in a WebView (check it doesn't call proceed())"
                    }
                    SECRETS.forEach { (label, rx) -> rx.findAll(text).take(5).forEach { m -> secrets += label to "$n: " + redact(m.value) } }
                    if (secrets.size > 40) break
                }
            }
        }.onFailure { sb.appendLine("\n⚠️ Couldn't open the APK as a zip: ${it.message}") }

        sb.appendLine("\n## Libraries found")
        sb.appendLine(if (libs.isEmpty()) "- none recognised" else libs.joinToString("\n") { "- $it" })
        if (natives > 0) sb.appendLine("- Native libraries: $natives `.so` file(s)")
        sb.appendLine("\n## Network, WebView, storage")
        sb.appendLine("- Network security config: " + if (nsc) "present" else "none")
        sb.appendLine("- WebView JavaScript: " + if (webJs) "used" else "not seen")
        sb.appendLine("- WebView JavaScript bridge (addJavascriptInterface): " + if (webBridge) "used" else "not seen")
        sb.appendLine("- Local database: " + if (sqlite) "yes (SQLite/Room)" else "not seen")
        if (webBridge) f += Finding("MEDIUM", "WebView JavaScript bridge", "addJavascriptInterface exposes app code to web pages. Only load trusted HTTPS pages in that WebView.")
        if (worldReadable) f += Finding("HIGH", "World-readable files", "MODE_WORLD_READABLE/WRITEABLE lets other apps read or change the app's files.")
        trustAll.distinct().forEach { f += Finding("HIGH", "TLS checks may be weakened", "Found a $it. Make sure certificates and host names are always verified.") }
        sb.appendLine("\n## Possible secrets (redacted)")
        if (secrets.isEmpty()) sb.appendLine("- none found")
        secrets.distinct().take(30).forEach { (k, v) -> sb.appendLine("- $k — `$v`") }
        if (secrets.isNotEmpty()) f += Finding("HIGH", "Secrets inside the APK (${secrets.distinct().size})", "Anything inside an APK can be extracted. Move keys to a server, restrict them, and rotate exposed ones.")

        val order = listOf("HIGH", "MEDIUM", "LOW")
        sb.insert(sb.indexOf("\n## ").let { if (it < 0) sb.length else it }, buildString {
            appendLine("\n## Summary: ${f.count { it.severity == "HIGH" }} high · ${f.count { it.severity == "MEDIUM" }} medium · ${f.count { it.severity == "LOW" }} low")
            f.sortedBy { order.indexOf(it.severity) }.forEach { appendLine("- **${it.severity}** — ${it.title}: ${it.detail}") }
            if (f.isEmpty()) appendLine("- No problems found by the static checks.")
        })
        sb.appendLine("\n---\n_Next step: tap \"Ask Arin to fix\" to fix these in your own app's source, or \"Decompile\" to read the code with jadx (needs the APK toolkit from Settings → Linux)._")
        return sb.toString()
    }

    /** Printable text runs (like the `strings` tool) from binary data. */
    private fun asciiRuns(b: ByteArray, min: Int = 6): String {
        val out = StringBuilder(b.size / 4)
        var start = -1
        for (i in b.indices) {
            val c = b[i].toInt() and 0xFF
            if (c in 0x20..0x7E) { if (start < 0) start = i }
            else { if (start >= 0 && i - start >= min) { out.append(String(b, start, i - start, Charsets.ISO_8859_1)).append('\n') }; start = -1 }
        }
        if (start >= 0 && b.size - start >= min) out.append(String(b, start, b.size - start, Charsets.ISO_8859_1))
        return out.toString()
    }
}
