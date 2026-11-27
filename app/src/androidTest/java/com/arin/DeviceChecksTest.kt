package com.arin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arin.selffix.RuleIntegrity
import com.arin.terminal.Linux
import com.arin.terminal.TerminalSelfTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On the device itself: the real built-in Linux terminal (PRoot), and the model instructions of the running app.
 * Results are also written to files/ so the workflow can put them in the test report.
 */
@RunWith(AndroidJUnit4::class)
class DeviceChecksTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realTerminalOnThisDevice() = runBlocking {
        val status = File(ctx.filesDir, "terminal-selftest.txt")
        if (!Linux.supported()) { status.writeText("TERMINAL TEST: not run — this processor isn't supported by the built-in Linux"); return@runBlocking }
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        if (!Linux.installed(ctx)) {
            // Only Ubuntu itself is needed; the optional apt/Node.js step after it is stopped.
            val job = launch(Dispatchers.IO) { runCatching { Linux.install(ctx) { log += it } }.onFailure { log += "install failed: ${it.message}" } }
            withTimeoutOrNull(20 * 60_000L) { while (!Linux.installed(ctx) && job.isActive) delay(2_000) }
            job.cancel()
        }
        if (!Linux.installed(ctx)) {
            status.writeText("TERMINAL TEST: not run — Linux couldn't be installed here: " + log.takeLast(3).joinToString(" / "))
            return@runBlocking
        }
        val checks = TerminalSelfTest.run(ctx)
        val report = TerminalSelfTest.report(checks)
        status.writeText(report)
        assertTrue(report, checks.isNotEmpty() && checks.all { it.ok })
    }

    @Test fun runningAppUsesTheApprovedInstructions() {
        val status = File(ctx.filesDir, "rule-integrity.txt")
        val text = ctx.assets.open("instruction-baseline.txt").bufferedReader().readText()
        if (text.lines().any { it.trim() == "# RECORD" || it.startsWith("# RECORD ") }) { status.writeText("RULE INTEGRITY (device): baseline not recorded yet"); return }
        val diff = RuleIntegrity.differences(RuleIntegrity.parse(text), RuleIntegrity.digests(ctx))
        status.writeText("RULE INTEGRITY (device): " + if (diff.isEmpty()) "all ${RuleIntegrity.texts(ctx).size} instruction texts match the approved baseline" else "DIFFERENT: $diff")
        assertTrue("Instructions differ on the device: $diff", diff.isEmpty())
    }
}
