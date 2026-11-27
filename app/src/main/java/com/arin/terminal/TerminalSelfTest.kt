package com.arin.terminal

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * ARIN-PROTECTED: terminal-self-test — checks the real built-in Linux terminal on this phone (Settings → Linux →
 * "Test the terminal"), and the same code runs in the emulator test: working folders persist per task, two chats'
 * folders stay separate, commands run at the same time without their output or exit codes mixing, a missing folder is
 * handled, and the retry limit works. Uses a scratch folder under /root and removes it afterwards.
 */
object TerminalSelfTest {
    data class Check(val name: String, val ok: Boolean, val detail: String)

    suspend fun run(c: Context): List<Check> {
        if (!Linux.installed(c)) return listOf(Check("Linux is installed", false, "Install Linux first (Settings → Linux)."))
        val base = "/root/arin-selftest-" + System.currentTimeMillis()
        val out = mutableListOf<Check>()
        fun add(name: String, ok: Boolean, detail: String) { out += Check(name, ok, detail.take(300)) }
        val setup = Linux.run(c, "mkdir -p $base/chatA/game $base/chatB && echo A > $base/chatA/only-in-A.txt && echo B > $base/chatB/only-in-B.txt && echo ready", 60_000L)
        if (setup.exitCode != 0) {
            // Classify the failure with evidence: the shell failing at start-up with ENOSYS ("Function not implemented")
            // before any of the test's own commands means PRoot can't run on this kernel — not a working-folder defect.
            val all = setup.combined()
            val atStart = Regex("""(shell-init|/dev/null|cannot duplicate pipe)[^\n]*Function not implemented""").containsMatchIn(all)
            return listOf(Check("Scratch folders", false, (if (atStart) "ENVIRONMENT: PRoot cannot run on this kernel (system calls fail before the script starts). " else "") +
                environment(c) + " — " + all))
        }
        try {
            // 1) a `cd` holds for the next command of the same task
            val r1 = Linux.run(c, "cd game", 60_000L, workDir = "$base/chatA")
            val r2 = Linux.run(c, "pwd", 60_000L, workDir = r1.cwd)
            add("Working folder carries over (cd game, then pwd)", r1.exitCode == 0 && r1.cwd == "$base/chatA/game" && r2.stdout.trim() == "$base/chatA/game",
                "cd ended in ${r1.cwd}; next command ran in ${r2.stdout.trim()}")

            // 2) two chats at the same time: own folders, own output, own exit codes
            val t0 = System.currentTimeMillis()
            val (a, b) = coroutineScope {
                val ja = async { Linux.run(c, "sleep 2; ls; pwd; exit 3", 60_000L, workDir = "$base/chatA") }
                val jb = async { Linux.run(c, "sleep 2; ls; pwd", 60_000L, workDir = "$base/chatB") }
                ja.await() to jb.await()
            }
            val took = System.currentTimeMillis() - t0
            add("Chat A sees only its own folder", a.stdout.contains("only-in-A.txt") && !a.stdout.contains("only-in-B.txt") && a.stdout.contains("$base/chatA"),
                a.stdout.trim().replace("\n", " · "))
            add("Chat B sees only its own folder", b.stdout.contains("only-in-B.txt") && !b.stdout.contains("only-in-A.txt") && b.stdout.contains("$base/chatB"),
                b.stdout.trim().replace("\n", " · "))
            add("Exit codes don't mix (A: 3, B: 0)", a.exitCode == 3 && b.exitCode == 0, "A ended with ${a.exitCode}, B with ${b.exitCode}")
            add("Both ran at the same time", took < 3_800, "both 2-second commands finished in ${took} ms")

            // 3) a folder that no longer exists
            val m = Linux.run(c, "pwd", 60_000L, workDir = "$base/gone")
            add("A missing folder is reported", m.exitCode == 0 && m.combined().contains("no longer exists") && m.stdout.trim() == Linux.HOME,
                "ran in ${m.stdout.trim()}; message: ${m.stderr.lineSequence().firstOrNull { it.contains("no longer") }.orEmpty()}")

            // 4) the retry limit with real failures
            val g = RetryGuard()
            repeat(2) { val f = Linux.run(c, "cat no-such-file.txt", 60_000L, workDir = "$base/chatB"); g.record("cat no-such-file.txt", "$base/chatB", f.exitCode == 0, f.combined()) }
            val blocked = g.check("cat no-such-file.txt", "$base/chatB")
            g.changed()
            add("The same failing command isn't repeated a third time", blocked != null && g.check("cat no-such-file.txt", "$base/chatB") == null,
                blocked?.take(160) ?: "not blocked")
        } finally {
            runCatching { Linux.run(c, "rm -rf $base", 60_000L) }
        }
        return out
    }

    fun report(checks: List<Check>): String =
        "Terminal test: ${checks.count { it.ok }} of ${checks.size} passed\n" + checks.joinToString("\n") { (if (it.ok) "PASS " else "FAIL ") + it.name + " — " + it.detail }

    /** Where the test ran: Android version, kernel, CPU type and PRoot mode (for telling emulator limits from app defects). */
    fun environment(c: Context): String = "Android API ${android.os.Build.VERSION.SDK_INT}, kernel ${System.getProperty("os.version")}, " +
        "ABI ${android.os.Build.SUPPORTED_ABIS.firstOrNull()}, emulator=${android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu")}, " +
        "PRoot ${if (Linux.noSeccomp(c)) "compatible" else "fast"} mode"
}
