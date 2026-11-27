package com.arin

import com.arin.terminal.Linux
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Test F — terminal working folders: the exact script Arin runs is executed with bash, for two "tabs" with their own
 * folders, running at the same time.
 */
class LinuxScriptTest {
    private val bash = File("/bin/bash")
    private val home = createTempDir("home")

    private fun run(cmd: String, workDir: String?): Triple<Int?, String, String?> {
        val cwd = File.createTempFile("cwd", ".txt").apply { delete() }
        val done = "__DONE__"
        val script = File.createTempFile("arin", ".sh").apply { writeText(Linux.script(cmd, done, workDir, cwd.absolutePath, home.absolutePath)) }
        val p = ProcessBuilder(bash.absolutePath, script.absolutePath).start()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        val rc = err.lines().firstOrNull { it.startsWith(done) }?.removePrefix(done)?.trim()?.toIntOrNull()
        return Triple(rc, out + err.lines().filterNot { it.startsWith(done) }.joinToString("\n"), cwd.takeIf { it.exists() }?.readText()?.trim())
    }

    @Test fun cdHoldsForTheNextCommandOfTheSameTask() {
        assumeTrue(bash.canExecute())
        val proj = File(createTempDir("tabA"), "game").apply { mkdirs() }
        val (rc, _, cwd) = run("cd ${proj.absolutePath}", null)
        assertEquals(0, rc); assertEquals(proj.canonicalPath, File(cwd!!).canonicalPath)
        val (rc2, out2, _) = run("pwd", cwd)
        assertEquals(0, rc2); assertEquals(proj.canonicalPath, File(out2.trim()).canonicalPath)
    }

    @Test fun twoTabsAtTheSameTimeNeverMix() {
        assumeTrue(bash.canExecute())
        val a = createTempDir("tabA"); val b = createTempDir("tabB")
        File(a, "only-in-a.txt").writeText("A"); File(b, "only-in-b.txt").writeText("B")
        var resA: Triple<Int?, String, String?>? = null; var resB: Triple<Int?, String, String?>? = null
        val ta = Thread { resA = run("sleep 0.3; ls; pwd", a.absolutePath) }
        val tb = Thread { resB = run("sleep 0.3; ls; pwd", b.absolutePath) }
        ta.start(); tb.start(); ta.join(); tb.join()
        val ra = resA!!; val rb = resB!!
        assertTrue(ra.second.contains("only-in-a.txt")); assertTrue(!ra.second.contains("only-in-b.txt"))
        assertTrue(rb.second.contains("only-in-b.txt")); assertTrue(!rb.second.contains("only-in-a.txt"))
        assertEquals(a.canonicalPath, File(ra.third!!).canonicalPath); assertEquals(b.canonicalPath, File(rb.third!!).canonicalPath)
    }

    @Test fun failuresKeepTheirExitCode() {
        assumeTrue(bash.canExecute())
        assertEquals(1, run("false", null).first)
        assertEquals(3, run("echo hi; exit 3", null).first)
        // pipefail: a failure before "| head" is not hidden
        assertTrue((run("ls /nonexistent-folder-xyz | head -1", null).first ?: 0) != 0)
    }

    @Test fun aMissingFolderIsReportedAndHomeIsUsed() {
        assumeTrue(bash.canExecute())
        val (rc, out, cwd) = run("pwd", "/nonexistent/folder/abc")
        assertEquals(0, rc); assertTrue(out.contains("no longer exists"))
        assertEquals(home.canonicalPath, File(cwd!!).canonicalPath)
    }

    @Test fun quotesInFolderNamesAreSafe() {
        assumeTrue(bash.canExecute())
        val odd = File(createTempDir("quote"), "it's a \$HOME `x`").apply { mkdirs() }
        val (rc, out, _) = run("pwd", odd.absolutePath)
        assertEquals(0, rc); assertEquals(odd.canonicalPath, File(out.trim()).canonicalPath)
    }
}
