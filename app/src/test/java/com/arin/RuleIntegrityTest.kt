package com.arin

import com.arin.selffix.RuleIntegrity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The instructions the main, middle and helper models get must be exactly the owner-approved ones. */
class RuleIntegrityTest {
    private val file = File("src/main/assets/instruction-baseline.txt")

    @Test fun runtimeInstructionsMatchTheApprovedBaseline() {
        val now = RuleIntegrity.digests()
        val text = file.readText()
        if (text.lines().any { it.trim() == "# RECORD" || it.startsWith("# RECORD ") }) {
            // First recording only: write the fingerprints for the owner to approve (commit), then the check is strict.
            File("build/rule-integrity").mkdirs()
            File("build/rule-integrity/instruction-baseline.txt").writeText(now.entries.joinToString("\n", postfix = "\n") { "${it.key} ${it.value}" })
            return
        }
        val diff = RuleIntegrity.differences(RuleIntegrity.parse(text), now)
        assertTrue("Protected model instructions changed: $diff\nIf the owner approved this change, the new fingerprints are:\n" +
            now.entries.joinToString("\n") { "${it.key} ${it.value}" }, diff.isEmpty())
    }

    @Test fun coversAllThreeRoles() {
        val names = RuleIntegrity.texts().keys
        assertTrue(names.any { it.startsWith("main.") }); assertTrue(names.any { it.startsWith("middle.") }); assertTrue(names.any { it.startsWith("helper.") })
        RuleIntegrity.texts().forEach { (k, v) -> assertTrue("$k is empty", v.length > 100 || k.startsWith("review.")) }
    }

    @Test fun detectsChangedMissingAndReplacedInstructions() {
        val approved = RuleIntegrity.digests()
        val changed = approved.toMutableMap().apply { this["helper.system"] = RuleIntegrity.sha256(RuleIntegrity.texts().getValue("helper.system") + " (edited)") }
        assertEquals(listOf("helper.system changed"), RuleIntegrity.differences(approved, changed))
        val missing = approved - "middle.system"
        assertEquals(listOf("middle.system missing"), RuleIntegrity.differences(approved, missing))
        val replaced = (approved - "main.owner-rules") + ("main.owner-rules-v2" to "x")
        assertEquals(listOf("main.owner-rules missing", "main.owner-rules-v2 not approved yet"), RuleIntegrity.differences(approved, replaced))
    }
}
