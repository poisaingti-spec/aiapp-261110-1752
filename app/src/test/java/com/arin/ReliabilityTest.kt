package com.arin

import com.arin.selffix.Outcome
import com.arin.selffix.Outcome.Kind
import com.arin.selffix.Requirements
import com.arin.terminal.RetryGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Test C — refusal and failure classification (simulated answers). */
class OutcomeTest {
    private fun kind(r: String, task: String = "", e: Throwable? = null, code: Boolean = false) = Outcome.classify(r, task, e, code).kind

    @Test fun genuineSafetyRefusal() = assertEquals(Kind.POLICY_OR_SAFETY_REFUSAL,
        kind("I can't help with that. Writing malware to steal passwords is harmful and against my guidelines."))

    @Test fun authorizationRequired() = assertEquals(Kind.AUTHORIZATION_REQUIRED,
        kind("I can't scan that network until you confirm you're authorized to test it. Do you have the owner's written permission?"))

    @Test fun misunderstoodRequest() = assertEquals(Kind.MISUNDERSTOOD_OBJECTIVE,
        kind("I'm sorry, but I'm not sure what you mean by the tracker — did you mean the habit tracker or the GPS tracker?"))

    @Test fun needsMoreInformation() = assertEquals(Kind.INSUFFICIENT_INFORMATION,
        kind("Could you please provide the MainActivity.kt file so I can see where the error is?"))

    @Test fun technicalLimitation() = assertEquals(Kind.TECHNICAL_LIMITATION,
        kind("I'm unable to browse the internet, so I can't open that page for you."))

    @Test fun networkFailureIsNotARefusal() {
        assertEquals(Kind.NETWORK_OR_PROVIDER_FAILURE, kind("", e = java.io.IOException("timeout")))
        assertEquals(Kind.NETWORK_OR_PROVIDER_FAILURE, kind("Claude couldn't be reached (HTTP 529 overloaded). Continue on your own."))
        assertFalse(Kind.NETWORK_OR_PROVIDER_FAILURE.refusal)
    }

    @Test fun emptyAndCutOffAnswersAreIncomplete() {
        assertEquals(Kind.INCOMPLETE_RESPONSE, kind("   "))
        assertEquals(Kind.INCOMPLETE_RESPONSE, kind("=== FILE: app/A.kt ===\npackage a\nfun x() {", code = true))
    }

    @Test fun realWorkIsSuccessEvenWithASideNote() {
        val work = "=== FILE: app/A.kt ===\npackage a\nfun pay() = 1\n=== END FILE ===\nI can't add real card processing without your payment provider's keys."
        val v = Outcome.classify(work, "write the payment screen", null, true)
        assertEquals(Kind.SUCCESSFUL_RESPONSE, v.kind); assertTrue(v.partial)
        assertEquals(Kind.SUCCESSFUL_RESPONSE, kind("Here is the fix:\n```kotlin\nval a = 1\n```"))
    }

    @Test fun refusalIsNeverReportedAsDone() {
        val v = Outcome.classify("I won't help with that request.", "write code", null, true)
        assertTrue(v.kind.refusal); assertFalse(v.kind.ok)
    }

    @Test fun bossIsToldWhoAndWhyAndToRespectGenuineRefusals() {
        val note = Outcome.forBoss("Helper", "Claude (sonnet)", Outcome.Verdict(Kind.POLICY_OR_SAFETY_REFUSAL, "It's against my guidelines."))
        assertTrue(note.contains("Claude (sonnet)")); assertTrue(note.contains("POLICY_OR_SAFETY_REFUSAL"))
        assertTrue(note.contains("don't reword the task to get around it"))
        assertEquals("", Outcome.forBoss("Helper", "Claude", Outcome.Verdict(Kind.SUCCESSFUL_RESPONSE)))
    }
}

/** Test G — bounded retries: the same failed command isn't repeated without a change. */
class RetryGuardTest {
    @Test fun sameFailedCommandIsBlockedUntilSomethingChanges() {
        val g = RetryGuard(maxSame = 2)
        assertNull(g.check("gradle build", "/root/app"))
        g.record("gradle build", "/root/app", ok = false, error = "e: Unresolved reference")
        assertNull(g.check("gradle build", "/root/app"))
        g.record("gradle build", "/root/app", ok = false, error = "e: Unresolved reference")
        val why = g.check("gradle build", "/root/app")
        assertNotNull(why); assertTrue(why!!.contains("failed 2 times")); assertTrue(why.contains("Unresolved reference"))
        assertNull("another folder is another command", g.check("gradle build", "/root/other"))
        g.changed() // a file was edited
        assertNull(g.check("gradle build", "/root/app"))
    }

    @Test fun separateRunsDontShareLimits() {
        val tabA = RetryGuard(); val tabB = RetryGuard()
        repeat(2) { tabA.record("make", "/root/x", false) }
        assertNotNull(tabA.check("make", "/root/x")); assertNull(tabB.check("make", "/root/x"))
    }
}

/** Test E — requirements survive summaries and briefs. */
class RequirementsTest {
    private val asked = "Build a notes app. Notes must be saved offline in a local database. Add a dark theme switch in settings. " +
        "Export every note as a PDF file to the Downloads folder."

    @Test fun aBriefThatKeepsEverythingPasses() {
        val brief = "1. Notes app, notes saved offline in a local database (Room).\n2. Settings screen has a dark theme switch.\n" +
            "3. Each note can be exported as a PDF file into Downloads."
        assertTrue(Requirements.uncovered(Requirements.lines(asked), brief).isEmpty())
    }

    @Test fun aDroppedRequirementIsFound() {
        val brief = "1. Notes app, notes saved offline in a local database.\n2. Settings screen has a dark theme switch."
        val missing = Requirements.uncovered(Requirements.lines(asked), brief)
        assertEquals(1, missing.size); assertTrue(missing[0].contains("PDF"))
    }
}
