package com.arin

import com.arin.selffix.Orchestrator
import com.arin.selffix.Orchestrator.Approach
import com.arin.selffix.Orchestrator.ReqStatus
import com.arin.selffix.Orchestrator.Status
import com.arin.selffix.Outcome
import com.arin.selffix.ProjectGraph
import org.junit.Assert.*
import org.junit.Test

/** Tests A–L of the orchestration spec, with scripted inputs (no real provider). */
class OrchestratorTest {
    private val big = """
        1. Add a dark mode switch to the settings screen.
        2. Keep the existing light theme as the default choice.
        3. Do not change the chat screen layout at all.
        4. Save the chosen theme so it survives an app restart.
        5. Write a unit test for the theme preference storage.
    """.trimIndent()

    @Test fun a_objectivePreservation_everyRequirementGetsAStableId() {
        val l = Orchestrator.Ledger(Orchestrator.record("r1", "chatA", "p", big))
        assertEquals(listOf("R1", "R2", "R3", "R4", "R5"), l.requirements.map { it.id })
        assertTrue(l.task.restrictions.any { it.contains("Do not change the chat screen") })
        assertEquals(big, l.task.original)
        assertTrue(l.brief().lines().size == 5)
    }

    @Test fun b_heavyWorkGoesToTheMiddleModel() {
        val d = Orchestrator.diagnose(big)
        val plan = Orchestrator.choose(d, middle = true, helper = true)
        assertEquals(Approach.MIDDLE, plan.approach)
        assertTrue(plan.roles.any { it.startsWith("middle") })
    }

    @Test fun c_obviousSimpleTaskIsDoneDirectly() {
        val plan = Orchestrator.choose(Orchestrator.diagnose("What does versionCode mean?"), middle = true, helper = true)
        assertEquals(Approach.DIRECT, plan.approach)
        assertEquals(0, plan.budget.reviews)
    }

    @Test fun d_specialistTaskGetsTheHelperOnlyForTheHardPart() {
        val plan = Orchestrator.choose(Orchestrator.diagnose("Find the root cause of this intermittent race condition"), middle = false, helper = true)
        assertEquals(Approach.HELPER_SPECIALIST, plan.approach)
        assertTrue(plan.roles.any { it.contains("specialist part only") })
    }

    @Test fun e_keywordAloneIsNotARefusal() {
        // A permitted security question is classified by meaning, and a worker answer that explains it is a success.
        val v = Outcome.classify("Here is how to audit your own app's exported activities: run `adb shell dumpsys package` and check each exported flag.")
        assertEquals(Outcome.Kind.SUCCESSFUL_RESPONSE, v.kind)
    }

    @Test fun f_genuineRestrictionIsNeverEscalatedAround() {
        val plan = Orchestrator.choose(Orchestrator.diagnose(big), true, true)
        assertNull(Orchestrator.escalate(plan, Outcome.Kind.POLICY_OR_SAFETY_REFUSAL, setOf(plan.approach), true, true))
        assertNull(Orchestrator.escalate(plan, Outcome.Kind.AUTHORIZATION_REQUIRED, setOf(plan.approach), true, true))
        assertEquals(Status.REFUSED, Orchestrator.statusOf(Outcome.Verdict(Outcome.Kind.POLICY_OR_SAFETY_REFUSAL)))
    }

    @Test fun g_twoTabsKeepSeparateRecords() {
        val dir = createTempDir("orch")
        val a = Orchestrator.Ledger(Orchestrator.record("ra", "tabA", "wifi", "Review the Wi-Fi security settings of my own router config file."))
        val b = Orchestrator.Ledger(Orchestrator.record("rb", "tabB", "game", "Add a pause button to the Android game main screen."))
        Orchestrator.saveCheckpoint(java.io.File(dir, "tabA.txt"), a, null, "started")
        Orchestrator.saveCheckpoint(java.io.File(dir, "tabB.txt"), b, null, "started")
        val ra = Orchestrator.loadCheckpoint(java.io.File(dir, "tabA.txt"))!!
        val rb = Orchestrator.loadCheckpoint(java.io.File(dir, "tabB.txt"))!!
        assertTrue(ra.ledger.task.original.contains("Wi-Fi") && !ra.ledger.task.original.contains("game"))
        assertTrue(rb.ledger.task.original.contains("game") && !rb.ledger.task.original.contains("Wi-Fi"))
        assertEquals("tabA", ra.ledger.task.chatId); assertEquals("tabB", rb.ledger.task.chatId)
    }

    @Test fun i_recoveryChangesApproachAndNeverRepeatsIt() {
        val plan = Orchestrator.choose(Orchestrator.diagnose(big), true, true)
        val tried = mutableSetOf(plan.approach)
        val next = Orchestrator.escalate(plan, Outcome.Kind.TOOL_EXECUTION_FAILURE, tried, true, true)!!
        assertNotEquals(plan.approach, next.approach)
        tried += next.approach
        val third = Orchestrator.escalate(next, Outcome.Kind.TOOL_EXECUTION_FAILURE, tried, true, true)
        if (third != null) assertFalse(third.approach in tried)
        // Bounded: once retries run out, no more attempts.
        assertNull(Orchestrator.escalate(plan.copy(budget = plan.budget.copy(retries = 0)), Outcome.Kind.TOOL_EXECUTION_FAILURE, setOf(), true, true))
    }

    @Test fun j_unsupportedCompletionClaimIsRejected() {
        val l = Orchestrator.Ledger(Orchestrator.record("r", "c", "p", big))
        assertEquals(Status.NEEDS_REVIEW, Orchestrator.accept("Done! Everything is implemented.", Orchestrator.Evidence(), l).status)
        assertEquals(Status.NEEDS_REVIEW, Orchestrator.accept("I ran the tests and built the APK, all of it works fine for every requirement listed.", Orchestrator.Evidence(), l).status)
        assertEquals(Status.FAILED, Orchestrator.accept("Implemented.", Orchestrator.Evidence(commands = listOf("./gradlew test" to 1)), l).status)
        assertEquals(Status.FAILED, Orchestrator.accept("", Orchestrator.Evidence(), l).status)
        assertFalse(l.update("R1", ReqStatus.VERIFIED))  // VERIFIED needs evidence
        assertTrue(l.update("R1", ReqStatus.VERIFIED, "test ThemeTest passed"))
    }

    @Test fun j2_partialDeliverableIsIncomplete() {
        val l = Orchestrator.Ledger(Orchestrator.record("r", "c", "p", big))
        val a = Orchestrator.accept("Added a dark mode switch to the settings screen, wired to the theme.", Orchestrator.Evidence(), l)
        assertEquals(Status.INCOMPLETE, a.status)
        assertTrue(a.missing.contains("R5"))
    }

    @Test fun k_resourceEfficiency_simpleTaskNeverCostsMoreThanNeeded() {
        val simple = Orchestrator.choose(Orchestrator.diagnose("Rename the app title?"), true, true)
        assertTrue(Orchestrator.cost(simple.approach) <= Orchestrator.cost(Approach.MIDDLE))
        // Measured evidence: a cheaper approach that verifies just as often wins.
        val stats = Orchestrator.Stats()
        val d = Orchestrator.diagnose(big)
        repeat(4) { stats.record(d.type, Approach.MIDDLE, verified = false); stats.record(d.type, Approach.DIRECT, verified = true) }
        assertEquals(Approach.DIRECT, Orchestrator.choose(d, true, true, stats).approach)
        // Too little evidence (<3) never changes the choice.
        val few = Orchestrator.Stats(); few.record(d.type, Approach.DIRECT, true)
        assertEquals(Approach.MIDDLE, Orchestrator.choose(d, true, true, few).approach)
    }

    @Test fun l_resumeRestoresObjectiveRequirementsAndProgress() {
        val f = java.io.File(createTempDir("orch"), "t.txt")
        val l = Orchestrator.Ledger(Orchestrator.record("r9", "chat", "proj", big))
        l.update("R1", ReqStatus.IMPLEMENTED, "switch added\twith tab\nand newline")
        val plan = Orchestrator.choose(Orchestrator.diagnose(big), true, true)
        assertTrue(Orchestrator.saveCheckpoint(f, l, plan, "step 3 of 5"))
        val r = Orchestrator.loadCheckpoint(f)!!
        assertEquals(big, r.ledger.task.original)
        assertEquals("proj", r.ledger.task.projectId)
        assertEquals(l.requirements.map { it.id to it.text }, r.ledger.requirements.map { it.id to it.text })
        assertEquals(ReqStatus.IMPLEMENTED, r.ledger.requirements[0].status)
        assertEquals("switch added\twith tab\nand newline", r.ledger.requirements[0].evidence.single())
        assertEquals(plan.approach, r.approach); assertEquals("step 3 of 5", r.progress)
    }

    @Test fun statsPersistAcrossRestarts() {
        val f = java.io.File(createTempDir("orch"), "s.tsv")
        val s = Orchestrator.Stats(f); repeat(3) { s.record(Orchestrator.TaskType.DEBUG, Approach.MIDDLE, true, "deepseek-chat") }
        val again = Orchestrator.Stats(f)
        assertEquals(1.0, again.rate(Orchestrator.TaskType.DEBUG, Approach.MIDDLE)!!, 0.0)
        assertEquals(1.0, again.modelRate("deepseek-chat")!!, 0.0)
    }

    @Test fun projectGraphFindsDeclarationsDependentsAndRelevantFiles() {
        val g = ProjectGraph.build("demo", mapOf(
            "a/Coins.kt" to "package demo\nclass CoinWallet { fun addReward(n: Int) {} }\n",
            "a/Shop.kt" to "package demo\nclass Shop(val w: CoinWallet) { fun buy() { w.addReward(-1) } }\n",
            "a/Ui.kt" to "package demo\n@Composable fun ShopScreen(s: Shop) { s.buy() }\n",
            "README.md" to "CoinWallet",
        ))
        assertEquals(3, g.files.size)
        assertEquals(listOf("a/Coins.kt"), g.declaring("CoinWallet"))
        assertEquals(listOf("a/Shop.kt"), g.dependents("a/Coins.kt"))
        assertEquals(listOf("a/Shop.kt"), g.dependencies("a/Ui.kt"))
        assertEquals("a/Coins.kt", g.relevant("how is the CoinWallet reward added?").first())
        assertTrue(g.impact(listOf("a/Coins.kt")).contains("used by a/Shop.kt"))
    }

    // ---- Build 108 continuation: project map in the delegation path ----

    private fun project(): java.io.File {
        val root = createTempDir("proj")
        fun w(p: String, t: String) = java.io.File(root, p).apply { parentFile.mkdirs(); writeText(t) }
        w("build.gradle.kts", "plugins { }")
        repeat(40) { i -> w("app/src/main/java/demo/pay/Payment$i.kt", "package demo.pay\nclass PaymentStep$i { fun charge$i() {} }\n") }
        w("app/src/main/java/demo/video/VideoUploader.kt", "package demo.video\nclass VideoUploader(val p: VideoPicker) { fun upload(uri: String) { p.pick() } }\n")
        w("app/src/main/java/demo/video/VideoPicker.kt", "package demo.video\nclass VideoPicker { fun pick(): String = \"\" }\n")
        w("app/src/main/java/demo/ui/UploadScreen.kt", "package demo.ui\n@Composable fun UploadScreen(u: VideoUploader) { u.upload(\"x\") }\n")
        w("app/src/test/java/demo/VideoUploaderTest.kt", "package demo\nclass VideoUploaderTest { fun t() { VideoUploader(VideoPicker()).upload(\"a\") } }\n")
        w("app/build/generated/Junk.kt", "class ShouldNotBeRead")
        return root
    }

    @Test fun mapAcceptance_focusedContextRefreshAndVerification() {
        val root = project()
        val task = "Change the video upload so the VideoUploader rejects files over 100 MB and shows an error on the UploadScreen."
        val g = com.arin.selffix.ProjectMaps.forFolder("folder:/root/demo", root)!!
        assertEquals(45, g.files.size)                      // build output is skipped
        val note = g.focus(task)
        assertTrue(note.contains("VideoUploader.kt") && note.contains("UploadScreen.kt"))
        assertFalse(note.contains("Payment"))               // unrelated files are not sent
        assertTrue(note.contains("tests app/src/test/java/demo/VideoUploaderTest.kt"))
        // The Boss picks investigate-first for a change to an existing project, with no extra AI call.
        val plan = Orchestrator.choose(Orchestrator.diagnose(big), middle = true, helper = true, projectMap = true)
        assertEquals(Approach.INVESTIGATE_FIRST, plan.approach)
        val brief = Orchestrator.delegationBrief(task, plan, note)
        assertTrue(brief.startsWith(task) && brief.contains("PROJECT MAP") && brief.contains("ASSUMPTION"))
        // A result that cites no mapped file has no source evidence; one that does is checked normally.
        val l = Orchestrator.Ledger(Orchestrator.record("r", "c", "folder:/root/demo", task))
        val refs = g.relevant(task)
        assertEquals(Status.NEEDS_REVIEW, Orchestrator.accept("I changed the upload size limit and the error message now shows for big files.",
            Orchestrator.Evidence(requiredRefs = refs), l).status)
        assertEquals(Status.COMPLETED, Orchestrator.accept("FILE VideoUploader.kt, FUNCTION upload: added a 100 MB size check that rejects files over the limit. " +
            "FILE UploadScreen.kt: shows an error when the VideoUploader rejects a file. Verify with VideoUploaderTest.", Orchestrator.Evidence(requiredRefs = refs), l).status)
        // After a modification the map is refreshed: new version, changed file re-read, unchanged files reused.
        val before = g.files.getValue("app/src/main/java/demo/pay/Payment1.kt")
        val f = java.io.File(root, "app/src/main/java/demo/video/VideoUploader.kt")
        f.writeText(f.readText() + "\nfun checkSize(bytes: Long) = bytes <= 100_000_000\n"); f.setLastModified(f.lastModified() + 5_000)
        java.io.File(root, "app/src/main/java/demo/pay/Payment2.kt").delete()
        val g2 = com.arin.selffix.ProjectMaps.forFolder("folder:/root/demo", root)!!
        assertNotEquals(g.version, g2.version)
        assertTrue("checkSize" in g2.files.getValue("app/src/main/java/demo/video/VideoUploader.kt").functions)
        assertSame(before, g2.files.getValue("app/src/main/java/demo/pay/Payment1.kt"))
        assertFalse(g2.files.containsKey("app/src/main/java/demo/pay/Payment2.kt"))
        // Unchanged source → the same map (no re-scan of content).
        assertSame(g2, com.arin.selffix.ProjectMaps.forFolder("folder:/root/demo", root))
    }

    @Test fun mapsOfDifferentProjectsNeverMix() {
        val a = com.arin.selffix.ProjectMaps.forFiles("build:app.one", mapOf("A.kt" to "class Alpha { fun one() {} }"))
        val b = com.arin.selffix.ProjectMaps.forFiles("build:app.two", mapOf("B.kt" to "class Beta { fun two() {} }"))
        assertEquals(setOf("A.kt"), a.files.keys); assertEquals(setOf("B.kt"), b.files.keys)
        assertTrue(a.declaring("Beta").isEmpty() && b.declaring("Alpha").isEmpty())
    }

    @Test fun helperReviewVerdictAndEscalationToInvestigation() {
        assertFalse(Orchestrator.reviewFoundIssues("VERDICT: OK — the change is complete."))
        assertTrue(Orchestrator.reviewFoundIssues("VERDICT: ISSUES\n1. UploadScreen.kt never shows the error."))
        assertTrue(Orchestrator.reviewFoundIssues("Looks fine I guess"))   // no clear verdict → not accepted as a pass
        val q = Orchestrator.reviewQuestion("task X", "result Y", "PROJECT MAP ...")
        assertTrue(q.contains("task X") && q.contains("result Y") && q.contains("PROJECT MAP") && q.contains("VERDICT"))
        val plan = Orchestrator.choose(Orchestrator.diagnose(big), true, false, projectMap = true)
        val next = Orchestrator.escalate(plan.copy(approach = Approach.MIDDLE), Outcome.Kind.TOOL_EXECUTION_FAILURE, setOf(Approach.MIDDLE), true, false)!!
        assertEquals(Approach.INVESTIGATE_FIRST, next.approach)
    }
}
