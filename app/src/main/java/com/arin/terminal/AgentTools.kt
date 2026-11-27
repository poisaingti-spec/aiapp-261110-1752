package com.arin.terminal

import android.content.Context
import android.util.Base64

/**
 * Claude Code-style tools the model can use from the chat: install any program it needs, and read, write
 * and edit files precisely. Everything runs through [Shell] (Arin's built-in Linux, else Termux).
 */
object AgentTools {

    /** Makes sure a terminal exists, installing Arin's built-in Linux if needed. Returns a problem, or null. */
    suspend fun ensureShell(context: Context, log: (String) -> Unit): String? {
        if (Shell.problem(context) == null) return null
        if (!Linux.supported()) return "No terminal is available on this phone. Set up Termux in Settings → Terminal."
        log("Setting up Arin's Linux (first time only, a few minutes)…")
        return runCatching { Linux.install(context) { log(it) }; null }
            .getOrElse { "Couldn't set up Linux: ${it.message}" }
    }

    /**
     * Installs each named tool, trying the system package manager, then pip, then npm. Names can carry a
     * manager prefix to pick one: "apt:ffmpeg", "pip:requests", "npm:typescript".
     */
    suspend fun install(context: Context, spec: String): Termux.Result {
        val names = spec.split(Regex("[\\s,]+")).map { it.trim() }.filter { it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9@:/._+=-]+")) }
        if (names.isEmpty()) return Termux.Result("", "No tool names given.", 1, null)
        val linux = Shell.usesLinux(context)
        val apt = if (linux) "apt-get install -y -qq --no-install-recommends" else "pkg install -y"
        val update = if (linux) "apt-get update -qq" else "pkg update -y"
        val script = buildString {
            if (linux) appendLine(Linux.DPKG_REPAIR.trim())
            appendLine("ok=0; bad=0; updated=0")
            appendLine("try_sys(){ $apt \"$1\" >/tmp/arin-inst.log 2>&1 && return 0; if [ \$updated = 0 ]; then $update >/dev/null 2>&1; updated=1; $apt \"$1\" >/tmp/arin-inst.log 2>&1 && return 0; fi; return 1; }")
            appendLine("try_pip(){ (command -v pip3 >/dev/null || try_sys python3-pip) && pip3 install -q --break-system-packages \"$1\" >/tmp/arin-inst.log 2>&1 || pip3 install -q \"$1\" >/tmp/arin-inst.log 2>&1; }")
            appendLine("try_npm(){ (command -v npm >/dev/null || try_sys nodejs) && npm install -g --no-fund --no-audit \"$1\" >/tmp/arin-inst.log 2>&1; }")
            names.forEach { raw ->
                val (mgr, n) = if (':' in raw && raw.substringBefore(':') in setOf("apt", "pkg", "pip", "npm"))
                    raw.substringBefore(':') to raw.substringAfter(':') else "" to raw
                val tries = when (mgr) {
                    "apt", "pkg" -> listOf("sys")
                    "pip" -> listOf("pip")
                    "npm" -> listOf("npm")
                    else -> listOf("sys", "pip", "npm")
                }
                appendLine("echo '── $n'")
                if (mgr.isEmpty()) appendLine("if command -v '$n' >/dev/null 2>&1; then echo \"already installed: \$(command -v '$n')\"; ok=\$((ok+1)); else")
                else appendLine("if false; then :; else")
                append("  if ")
                append(tries.joinToString(" || ") { "try_$it '$n'" })
                appendLine("; then echo 'installed $n'; ok=\$((ok+1)); else echo 'FAILED $n:'; tail -5 /tmp/arin-inst.log; bad=\$((bad+1)); fi")
                appendLine("fi")
            }
            appendLine("echo \"── done: \$ok ok, \$bad failed\"; [ \$bad = 0 ]")
        }
        return Shell.run(context, script, timeoutMs = 30 * 60_000L)
    }

    /** Reads a text file with line numbers, [from] (1-based) for [count] lines. */
    suspend fun read(context: Context, path: String, from: Int = 1, count: Int = 400, workDir: String? = null): Termux.Result {
        val p = quote(path)
        return Shell.run(context,
            "if [ -d $p ]; then ls -la $p; elif [ -f $p ]; then total=\$(wc -l < $p); " +
                "awk -v s=$from -v n=$count 'NR>=s && NR<s+n { printf \"%6d  %s\\n\", NR, \$0 }' $p | cut -c1-2000; " +
                "echo \"(lines $from-\$(( $from + $count - 1 < total ? $from + $count - 1 : total )) of \$total)\"; else echo 'No such file: '$p; exit 1; fi",
            timeoutMs = 60_000L, workDir = workDir)
    }

    /** Creates or replaces a file with [content], making its folders. */
    suspend fun write(context: Context, path: String, content: String, workDir: String? = null): Termux.Result {
        val p = quote(path)
        val b64 = Base64.encodeToString(content.toByteArray(), Base64.NO_WRAP)
        return Shell.run(context,
            "mkdir -p \"\$(dirname $p)\" && printf '%s' '$b64' | base64 -d > $p && echo \"wrote \$(wc -c < $p) bytes, \$(wc -l < $p) lines to \"$p",
            timeoutMs = 120_000L, workDir = workDir)
    }

    /**
     * Replaces [old] with [new] in a file. [old] must appear exactly once (like Claude Code's edit), so the
     * change lands where the model meant it.
     */
    suspend fun edit(context: Context, path: String, old: String, new: String, workDir: String? = null): Termux.Result {
        val p = quote(path)
        val got = Shell.run(context, "[ -f $p ] && base64 -w0 $p || { echo 'No such file: '$p >&2; exit 1; }", timeoutMs = 60_000L, workDir = workDir)
        if (got.exitCode != 0) return got
        val text = runCatching { String(Base64.decode(got.stdout.trim(), Base64.DEFAULT)) }.getOrNull()
            ?: return Termux.Result("", "Couldn't read $path.", 1, null)
        var found = 0
        if (old.isNotEmpty()) {
            var i = text.indexOf(old)
            while (i >= 0 && found < 2) { found++; i = text.indexOf(old, i + 1) }
        }
        return when {
            found == 0 -> Termux.Result("", "The <old> text was not found in $path. Read the file again and copy the exact text (spaces and line breaks matter).", 1, null)
            found > 1 -> Termux.Result("", "The <old> text appears more than once in $path. Include more surrounding lines so it matches only once.", 1, null)
            else -> write(context, path, text.replaceFirst(old, new), workDir).let { w ->
                if (w.exitCode == 0) Termux.Result("edited $path (replaced 1 place)", "", 0, null) else w
            }
        }
    }

    private fun quote(path: String): String = "'" + path.trim().replace("'", "'\\''") + "'"
}
