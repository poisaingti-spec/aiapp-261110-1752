package com.arin.terminal

import android.content.Context
import android.os.Build
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Linux built into Arin: an Ubuntu system in the app's own storage, run with PRoot (no root needed).
 * Commands, Node.js, npm and tools such as REA run inside it and see an ordinary Linux system.
 *
 * PRoot binaries ship as lib*.so files in the APK (jniLibs), built by
 * github.com/green-green-avk/build-proot-android from github.com/green-green-avk/proot (GPL-2.0).
 * Running programs from app storage needs targetSdk 28, the same as Termux.
 */
object Linux {
    const val HOME = "/root"

    /** Where attached files are copied, inside Linux. */
    const val FILES = "/root/files"

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .build()

    private fun base(context: Context) = File(context.filesDir, "linux")
    fun rootfs(context: Context) = File(base(context), "rootfs")
    private fun tmp(context: Context) = File(base(context), "tmp").apply { mkdirs() }
    private fun fake(context: Context) = File(base(context), "fake").apply { mkdirs() }
    private fun marker(context: Context) = File(base(context), "installed")
    private fun nodeMarker(context: Context) = File(base(context), "node-ready")

    fun installed(context: Context): Boolean = marker(context).exists() && File(rootfs(context), "bin").exists()
    fun nodeReady(context: Context): Boolean = installed(context) && nodeMarker(context).exists()

    /** Ubuntu's name for this phone's processor family, or null if PRoot isn't bundled for it. */
    private fun ubuntuArch(): String? = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a" -> "armhf"
        "x86_64" -> "amd64"
        else -> null
    }

    fun supported(): Boolean = ubuntuArch() != null

    private fun proot(context: Context) = File(context.applicationInfo.nativeLibraryDir, "libproot.so")

    // ------------------------------------------------------------------ install

    /**
     * Downloads Ubuntu 24.04 (about 30 MB), unpacks it, then installs Node.js 24, git, Python and
     * the basics. [log] receives progress lines. Takes a few minutes; safe to run again.
     */
    suspend fun install(context: Context, log: (String) -> Unit) = withContext(Dispatchers.IO) {
        val arch = ubuntuArch() ?: throw IllegalStateException("This phone's processor (${Build.SUPPORTED_ABIS.firstOrNull()}) isn't supported.")
        if (!proot(context).exists()) {
            throw IllegalStateException("PRoot is missing from this build of Arin (looked in ${context.applicationInfo.nativeLibraryDir}).")
        }
        if (!installed(context)) {
            log("Finding the latest Ubuntu 24.04 base image…")
            val listing = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
            val page = get(listing).use { it.body!!.string() }
            // Newest point release, e.g. ubuntu-base-24.04.3-base-arm64.tar.gz.
            val file = Regex("""ubuntu-base-24\.04(?:\.(\d+))?-base-$arch\.tar\.gz""").findAll(page)
                .maxByOrNull { it.groupValues[1].toIntOrNull() ?: 0 }?.value
                ?: throw IllegalStateException("Couldn't find the Ubuntu image for $arch at $listing")
            log("Downloading $file…")
            val root = rootfs(context)
            root.deleteRecursively()
            root.mkdirs()
            get(listing + file).use { resp ->
                val total = resp.body!!.contentLength()
                val counting = CountingStream(resp.body!!.byteStream()) { n ->
                    if (total > 0) log("Downloading Ubuntu… ${n * 100 / total}% of ${total / (1024 * 1024)} MB")
                }
                log("Unpacking Ubuntu…")
                extract(counting, root)
            }
            configure(context)
            marker(context).writeText(file)
            log("Ubuntu is unpacked.")
        } else {
            configure(context)
        }
        log("Installing basics with apt (this takes a few minutes)…")
        val script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends ca-certificates curl xz-utils procps git python3 python3-pip nano less unzip zip
            case "${'$'}(uname -m)" in aarch64) NA=arm64;; armv7l|armv8l) NA=armv7l;; x86_64) NA=x64;; *) echo "unknown arch"; exit 1;; esac
            if ! node -v 2>/dev/null | grep -q '^v24'; then
              V=${'$'}(curl -fsSL https://nodejs.org/dist/latest-v24.x/ | grep -o "node-v24[0-9.]*-linux-${'$'}NA.tar.xz" | head -1)
              echo "Installing ${'$'}V"
              curl -fsSL "https://nodejs.org/dist/latest-v24.x/${'$'}V" | tar -xJ -C /usr/local --strip-components=1
            fi
            echo "node ${'$'}(node -v), npm ${'$'}(npm -v)"
        """.trimIndent()
        val r = run(context, script, timeoutMs = 45 * 60_000L, onOutput = { line -> log(line) })
        if (r.exitCode != 0 || r.error != null) {
            throw IllegalStateException("Setting up Linux failed (exit ${r.exitCode}): ${r.error ?: r.combined().takeLast(600)}")
        }
        nodeMarker(context).writeText("ok")
        log("Linux is ready: Ubuntu 24.04 with Node.js, npm, git and Python.")
    }

    private fun devMarker(context: Context) = File(base(context), "devtools-ready")

    fun devToolsReady(context: Context): Boolean = installed(context) && devMarker(context).exists()

    /** Versions installed by [installDevTools] (checked November 2026). */
    const val KOTLINC_VERSION = "2.4.20"
    const val GRADLE_VERSION = "9.9.0"

    /**
     * Installs a Java/Kotlin toolchain: OpenJDK 21, the Kotlin compiler and Gradle, so Java and Kotlin
     * programs (and Gradle projects that don't need the Android SDK) build and run in Linux.
     */
    suspend fun installDevTools(context: Context, log: (String) -> Unit): Termux.Result {
        log("Installing Java 21, Kotlin $KOTLINC_VERSION and Gradle $GRADLE_VERSION…")
        val script = """
            set -e
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends openjdk-21-jdk-headless unzip zip
            mkdir -p /opt
            if [ ! -x /opt/kotlinc/bin/kotlinc ]; then
              curl -fsSL -o /tmp/kotlinc.zip https://github.com/JetBrains/kotlin/releases/download/v$KOTLINC_VERSION/kotlin-compiler-$KOTLINC_VERSION.zip
              rm -rf /opt/kotlinc && unzip -q /tmp/kotlinc.zip -d /opt && rm /tmp/kotlinc.zip
            fi
            if [ ! -x /opt/gradle-$GRADLE_VERSION/bin/gradle ]; then
              curl -fsSL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip
              unzip -q /tmp/gradle.zip -d /opt && rm /tmp/gradle.zip
            fi
            ln -sf /opt/kotlinc/bin/kotlinc /opt/kotlinc/bin/kotlin /usr/local/bin/
            ln -sf /opt/gradle-$GRADLE_VERSION/bin/gradle /usr/local/bin/gradle
            java -version 2>&1 | head -1
            kotlinc -version 2>&1 | tail -1
            gradle --version 2>/dev/null | grep -m1 Gradle
        """.trimIndent()
        val r = run(context, script, timeoutMs = 45 * 60_000L, onOutput = log)
        if (r.exitCode == 0 && r.error == null) devMarker(context).writeText("ok")
        return r
    }

    /** Script (inside Linux) that installs what REA needs to analyse APKs. */
    const val ANDROID_TOOLS_SCRIPT = "/root/.arin/setup-android-tools.sh"

    /**
     * REA analyses APKs through jadx-headless-mcp (a Java program, so it runs on ARM too) and decodes resources
     * with apktool; both need a full JDK. REA finds them through REA_JADX_MCP_JAR, JAVA_HOME and
     * REA_APKTOOL_COMMAND, written to ~/.arin/rea.env, which REA's start command loads.
     * JADX version and checksum are REA's pinned ones (docs/guides/android/android-analysis.md).
     */
    private val androidToolsScript = listOf(
        "#!/bin/bash",
        "# Installs JDK 21, JADX (headless MCP 0.7.1, REA's pinned version) and apktool for REA's Android tools.",
        "set -e",
        "export DEBIAN_FRONTEND=noninteractive",
        "if ! command -v javac >/dev/null 2>&1; then",
        "  apt-get update",
        "  apt-get install -y --no-install-recommends openjdk-21-jdk-headless",
        "fi",
        "mkdir -p /opt/rea-android",
        "cd /opt/rea-android",
        "JADX=jadx-headless-mcp-0.7.1-all.jar",
        "SUM=6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75",
        "if ! echo \"\$SUM  \$JADX\" | sha256sum -c - >/dev/null 2>&1; then",
        "  curl -fL -o \"\$JADX\" https://github.com/1013503897/jadx-headless-mcp/releases/download/v0.7.1/\$JADX",
        "  echo \"\$SUM  \$JADX\" | sha256sum -c -",
        "fi",
        "if [ ! -f apktool.jar ]; then",
        "  URL=\$(curl -fsSL https://api.github.com/repos/iBotPeaches/Apktool/releases/latest | grep -o 'https://[^\"]*\\.jar' | head -1)",
        "  [ -n \"\$URL\" ] || { echo \"Couldn't find the apktool download\"; exit 1; }",
        "  curl -fL -o apktool.jar \"\$URL\"",
        "fi",
        "printf '#!/bin/sh\\nexec java -jar /opt/rea-android/apktool.jar \"\$@\"\\n' > /usr/local/bin/apktool",
        "chmod +x /usr/local/bin/apktool",
        "JH=\$(dirname \"\$(dirname \"\$(readlink -f \"\$(command -v javac)\")\")\")",
        "mkdir -p /root/.arin",
        "{ echo \"REA_JADX_MCP_JAR=/opt/rea-android/\$JADX\"; echo \"JAVA_HOME=\$JH\"; echo \"REA_APKTOOL_COMMAND=/usr/local/bin/apktool\"; } > /root/.arin/rea.env",
        "java -version 2>&1 | head -1",
        "apktool --version 2>/dev/null | head -1",
        "echo \"Android tools ready: \$JADX, apktool, JAVA_HOME=\$JH\"",
        "# REA must restart to pick up the new settings; Arin starts it again on the next tool call.",
        "pkill -f mcp-bridge.mjs || true",
    ).joinToString("\n", postfix = "\n")

    /** Installs JDK, JADX and apktool so REA's APK tools work. */
    suspend fun installAndroidTools(context: Context, log: (String) -> Unit): Termux.Result {
        log("Installing REA's Android tools (JDK 21, JADX, apktool)…")
        configure(context)
        return run(context, "bash $ANDROID_TOOLS_SCRIPT", timeoutMs = 30 * 60_000L, onOutput = log)
    }

    /** Installs REA (npm package rea-agents) in Linux. */
    suspend fun installRea(context: Context, log: (String) -> Unit): Termux.Result {
        log("Installing REA (npm install -g rea-agents)…")
        return run(
            context,
            // --omit=optional skips frida's native add-on (needs a desktop, and its install crashes under PRoot).
            "npm install --global rea-agents --omit=optional --no-fund --no-audit 2>&1 | tail -15 && rea --version",
            timeoutMs = 30 * 60_000L,
            onOutput = log,
        )
    }

    /** Deletes the Linux system and everything in it. */
    suspend fun remove(context: Context) = withContext(Dispatchers.IO) {
        // PRoot may have made folders read-only; make them writable so they can be deleted.
        base(context).walkBottomUp().forEach { runCatching { it.setWritable(true) } }
        base(context).deleteRecursively()
    }

    private fun get(url: String): okhttp3.Response {
        val resp = http.newCall(Request.Builder().url(url).header("User-Agent", "Arin").build()).execute()
        if (!resp.isSuccessful) {
            resp.close()
            throw IllegalStateException("Download failed: HTTP ${resp.code} for $url")
        }
        return resp
    }

    private class CountingStream(private val inner: InputStream, private val onProgress: (Long) -> Unit) : InputStream() {
        private var count = 0L
        private var lastReport = 0L
        override fun read(): Int = inner.read().also { if (it >= 0) bump(1) }
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
        override fun close() = inner.close()
        private fun bump(n: Long) {
            count += n
            if (count - lastReport > 2L * 1024 * 1024) {
                lastReport = count
                onProgress(count)
            }
        }
    }

    /** Unpacks a .tar.gz, keeping symlinks and permissions; hard links become copies. */
    private suspend fun extract(input: InputStream, root: File) {
        val hardLinks = mutableListOf<Pair<File, String>>()
        TarArchiveInputStream(GZIPInputStream(input.buffered(256 * 1024))).use { tar ->
            while (true) {
                coroutineContext.ensureActive()
                val e: TarArchiveEntry = tar.nextEntry ?: break
                val name = e.name.removePrefix("./").trimEnd('/')
                if (name.isEmpty() || name.split('/').any { it == ".." }) continue
                val out = File(root, name)
                when {
                    e.isDirectory -> out.mkdirs()
                    e.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        runCatching { out.delete() }
                        runCatching { Os.symlink(e.linkName, out.absolutePath) }
                    }
                    e.isLink -> hardLinks += out to e.linkName.removePrefix("./")
                    e.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { tar.copyTo(it, 64 * 1024) }
                    }
                    else -> continue // device nodes, fifos: not possible or needed in app storage
                }
                if (!e.isSymbolicLink) runCatching { Os.chmod(out.absolutePath, e.mode and 0x1FF or (if (e.isDirectory) 0x1C0 else 0x180)) }
            }
        }
        hardLinks.forEach { (out, target) ->
            val src = File(root, target)
            if (src.isFile) {
                out.parentFile?.mkdirs()
                src.copyTo(out, overwrite = true)
                runCatching { Os.chmod(out.absolutePath, Os.stat(src.absolutePath).st_mode and 0x1FF) }
            }
        }
    }

    /** Settings that make Ubuntu work inside an app: DNS, hosts, apt without a sandbox user, a home folder. */
    private fun configure(context: Context) {
        val root = rootfs(context)
        File(root, "etc").mkdirs()
        File(root, "etc/resolv.conf").apply { runCatching { delete() } }.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        File(root, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(root, "etc/apt/apt.conf.d").mkdirs()
        File(root, "etc/apt/apt.conf.d/01-arin").writeText(
            "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\nDPkg::Use-Pty \"false\";\n",
        )
        File(root, "etc/profile.d").mkdirs()
        File(root, "etc/profile.d/arin.sh").writeText(
            "# Set by Arin: PRoot can't translate io_uring or handle glibc's rseq; both can crash Node/npm.\n" +
                "export UV_USE_IO_URING=0\nexport GLIBC_TUNABLES=glibc.pthread.rseq=0\n",
        )
        File(root, "root/.npmrc").takeIf { !it.exists() }?.writeText("fund=false\naudit=false\nupdate-notifier=false\n")
        File(root, "root/.arin").mkdirs()
        File(root, ANDROID_TOOLS_SCRIPT.removePrefix("/")).writeText(androidToolsScript)
        File(root, "root/files").mkdirs()
        File(root, "root/.arin").mkdirs()
        File(root, "tmp").mkdirs()
        runCatching { Os.chmod(File(root, "tmp").absolutePath, 0x1FF) }
        // Android hides some /proc files from apps; give programs harmless stand-ins.
        val f = fake(context)
        File(f, "loadavg").writeText("0.12 0.07 0.02 2/165 765\n")
        File(f, "uptime").writeText("7524.15 22341.88\n")
        File(f, "version").writeText("Linux version ${System.getProperty("os.version")} (arin@android) #1 SMP PREEMPT\n")
        File(f, "stat").writeText(
            "cpu  1957 0 2877 93280 262 342 254 87 0 0\ncpu0 31 0 226 12027 82 10 4 9 0 0\n" +
                "intr 63361 0 0 0\nctxt 38014093\nbtime 1694292441\nprocesses 26442\nprocs_running 1\nprocs_blocked 0\nsoftirq 75663 0 5903 6 25375 10774 0 243 11685 0 21677\n",
        )
        File(f, "vmstat").writeText("nr_free_pages 136341\nnr_zone_inactive_anon 4\nnr_zone_active_anon 0\n")
    }

    // ------------------------------------------------------------------ running

    /**
     * Runs [command] with bash in Linux (home /root, as root) and waits until it finishes, at most
     * [timeoutMs]. Background jobs it starts (nohup … &) keep running after it returns.
     */
    suspend fun run(
        context: Context,
        command: String,
        timeoutMs: Long = 15 * 60_000L,
        onOutput: ((String) -> Unit)? = null,
    ): Termux.Result = withContext(Dispatchers.IO) {
        if (!installed(context)) return@withContext Termux.Result("", "", null, "Linux isn't installed. Open Settings → Linux and tap Install Linux.")
        val root = rootfs(context)
        // Installs from older Arin versions get the newer fixes too.
        if (File(root, "etc/profile.d/arin.sh").let { !it.exists() || !it.readText().contains("rseq") } ||
            !File(root, ANDROID_TOOLS_SCRIPT.removePrefix("/")).exists()
        ) runCatching { configure(context) }
        val id = UUID.randomUUID().toString().replace("-", "").take(12)
        val done = "__ARIN_DONE_${id}__"
        // The command goes in a script file, so no quoting is needed. Its exit code is printed on a
        // marker line, so background jobs it leaves running don't keep us waiting.
        val script = File(root, "tmp/arin-$id.sh")
        // pipefail: a crash before "| head" must not look like success.
        script.writeText("cd $HOME 2>/dev/null\n(\nset -o pipefail\n$command\n)\nrc=$?\nprintf '\\n$done%s\\n' \"\$rc\" >&2\n")
        val cmd = mutableListOf(
            proot(context).absolutePath,
            "-0", "-r", root.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "${root.absolutePath}/tmp:/dev/shm",
            "--link2symlink",
            "-w", HOME,
        )
        listOf("loadavg", "stat", "uptime", "version", "vmstat").forEach { name ->
            if (!File("/proc/$name").canRead()) cmd += listOf("-b", "${File(fake(context), name).absolutePath}:/proc/$name")
        }
        cmd += listOf(
            "/usr/bin/env", "-i",
            "HOME=$HOME", "USER=root", "LANG=C.UTF-8", "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp", "CI=1", "DEBIAN_FRONTEND=noninteractive", "NPM_CONFIG_UPDATE_NOTIFIER=false",
            // Node's libuv uses io_uring for files; PRoot can't translate io_uring paths, which corrupts
            // memory (malloc(): invalid size, double free) in npm and REA. Use plain syscalls instead.
            "UV_USE_IO_URING=0",
            // glibc 2.35+ registers rseq, which PRoot doesn't handle; it can corrupt the heap.
            "GLIBC_TUNABLES=glibc.pthread.rseq=0",
            "NPM_CONFIG_FUND=false", "NPM_CONFIG_AUDIT=false",
            "/bin/bash", "-l", "/tmp/arin-$id.sh",
        )
        val pb = ProcessBuilder(cmd).directory(root)
        pb.environment().apply {
            clear()
            put("PROOT_TMP_DIR", tmp(context).absolutePath)
            put("PROOT_LOADER", File(context.applicationInfo.nativeLibraryDir, "libproot-loader.so").absolutePath)
            val l32 = File(context.applicationInfo.nativeLibraryDir, "libproot-loader32.so")
            if (l32.exists()) put("PROOT_LOADER_32", l32.absolutePath)
            put("PATH", "/system/bin")
            put("ANDROID_ROOT", "/system")
            put("ANDROID_DATA", "/data")
        }
        val process = try {
            pb.start()
        } catch (e: Exception) {
            return@withContext Termux.Result("", "", null, "Couldn't start Linux: ${e.message}")
        }
        val out = StringBuffer()
        val err = StringBuffer()
        val finished = kotlinx.coroutines.CompletableDeferred<Int?>()
        fun pump(stream: InputStream, into: StringBuffer, isErr: Boolean) = Thread {
            runCatching {
                stream.bufferedReader().forEachLine { line ->
                    if (isErr && line.startsWith(done)) {
                        finished.complete(line.removePrefix(done).trim().toIntOrNull())
                    } else if (!finished.isCompleted) {
                        if (into.length < 2_000_000) into.append(line).append('\n')
                        onOutput?.invoke(line)
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        pump(process.inputStream, out, false)
        pump(process.errorStream, err, true)
        Thread {
            val code = runCatching { process.waitFor() }.getOrNull()
            finished.complete(code)
        }.apply { isDaemon = true; start() }
        try {
            val code = withTimeout(timeoutMs) { finished.await() }
            Termux.Result(out.toString(), err.toString(), code, null)
        } catch (e: TimeoutCancellationException) {
            process.destroy()
            Termux.Result(out.toString(), err.toString(), null, "Stopped after ${timeoutMs / 60_000} min without finishing.")
        } catch (e: kotlinx.coroutines.CancellationException) {
            process.destroy() // Stop was pressed
            throw e
        } finally {
            runCatching { script.delete() }
        }
    }

    /** Copies an attached file into Linux (/root/files) and returns its path there, or null. */
    fun importFile(context: Context, input: InputStream, name: String): String? = runCatching {
        if (!installed(context)) return null
        val safe = name.replace('/', '_').ifBlank { "file" }
        val dir = File(rootfs(context), "root/files").apply { mkdirs() }
        var target = File(dir, safe)
        var n = 1
        while (target.exists()) {
            target = File(dir, safe.substringBeforeLast('.', safe) + " ($n)" + (if ('.' in safe) "." + safe.substringAfterLast('.') else ""))
            n++
        }
        target.outputStream().use { input.copyTo(it, 64 * 1024) }
        "$FILES/${target.name}"
    }.getOrNull()
}

/** Where commands run: Arin's built-in Linux when installed, otherwise Termux. */
object Shell {
    fun usesLinux(context: Context): Boolean = Linux.installed(context)

    fun home(context: Context): String = if (usesLinux(context)) Linux.HOME else Termux.HOME

    fun problem(context: Context): String? =
        if (usesLinux(context)) null
        else Termux.problem(context)?.let {
            "No terminal is set up. Open Settings → Terminal and tap Install Linux (built in), or set up Termux."
        }

    suspend fun run(context: Context, command: String, timeoutMs: Long = 15 * 60_000L): Termux.Result =
        if (usesLinux(context)) Linux.run(context, command, timeoutMs) else Termux.run(context, command, timeoutMs = timeoutMs)

    /** A line describing the environment, for the model. */
    fun describe(context: Context): String =
        if (usesLinux(context)) {
            "Ubuntu 24.04 Linux built into the Arin app (PRoot, ${System.getProperty("os.arch")}), you are root, home is /root. " +
                "Install packages with apt-get install -y; Node.js 24, npm, git and Python 3 are installed. " +
                "Files the person attaches are copied to ${Linux.FILES}. " +
                (if (Linux.devToolsReady(context)) "Developer tools are installed: Java 21 (javac, java), Kotlin (kotlinc), Gradle. " else "") +
                "Android APKs can't be compiled on the phone (Google ships no ARM build tools); use Build mode (<build>) for APKs. " +
                "If an REA Android/APK tool says JADX, a JDK or apktool is missing, run: bash ${Linux.ANDROID_TOOLS_SCRIPT} " +
                "(then call the tool again; REA restarts automatically). Other APK tools you can install yourself: " +
                "pip install androguard, or use unzip/apktool on files in /root/files."
        } else {
            "Termux on Android (${System.getProperty("os.arch")}, no root), home ~ = ${Termux.HOME}. Install packages with pkg install -y."
        }
}
