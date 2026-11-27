package com.arin

import com.arin.builder.KotlinFixes
import com.arin.builder.KotlinImports
import com.arin.builder.KotlinSyntax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fixes made from the compiler's own messages (error texts taken from real failed builds). */
class CompilerFixesTest {
    private val vm = "app/src/main/java/com/x/app/vm/MainVm.kt"
    private val loop = "app/src/main/java/com/x/app/engine/RepairLoop.kt"

    @Test fun readsGitHubAndPhoneErrorLines() {
        val gh = "build\tUNKNOWN STEP\t2026-11-24T01:24:30.0017578Z e: file:///home/runner/work/a/a/$vm:229:34 Unresolved reference 'Verdict'."
        val phone = "e: file:///root/projects/a/$vm:12:5 Function invocation 'first()' expected."
        val e = KotlinFixes.errors(gh + "\n" + phone)
        assertEquals(2, e.size); assertEquals(229, e[0].line); assertEquals(34, e[0].col)
        assertEquals(vm, KotlinFixes.pathOf(e[0].file, listOf(vm, loop)))
    }

    @Test fun missingBracketsAfterFunctionAreAdded() {
        val src = "package com.x.app.vm\n\nfun f(l: List<Int>) {\n    val a = l.first\n}\n"
        val r = KotlinFixes.fix(mapOf(vm to src), "e: file:///w/$vm:4:15 Function invocation 'first()' expected.")
        assertTrue(r.updated.getValue(vm).contains("val a = l.first()"))
    }

    @Test fun optInIsAddedAndMergedIntoAnExistingOne() {
        val src = "@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)\n\npackage com.x.app.vm\n\nfun f() {}\n"
        val msg = "e: file:///w/$vm:5:1 This declaration needs opt-in. Its usage must be marked with '@kotlin.ExperimentalStdlibApi' or '@OptIn(kotlin.ExperimentalStdlibApi::class)'"
        val out = KotlinFixes.fix(mapOf(vm to src), msg).updated.getValue(vm)
        assertTrue(out.startsWith("@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, kotlin.ExperimentalStdlibApi::class)"))
        assertEquals(1, Regex("@file:OptIn").findAll(out).count())
    }

    @Test fun statementCallOnNullableBecomesSafe() {
        val src = "package com.x.app.vm\n\nfun f(prev: Thread.UncaughtExceptionHandler?, t: Thread, e: Throwable) {\n    prev.uncaughtException(t, e)\n}\n"
        val out = KotlinFixes.fix(mapOf(vm to src), "e: file:///w/$vm:4:9 Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'Thread.UncaughtExceptionHandler?'.").updated.getValue(vm)
        assertTrue(out.contains("prev?.uncaughtException(t, e)"))
    }

    @Test fun valueUseOnNullableIsLeftToTheModels() {
        val src = "package com.x.app.vm\n\nfun f(s: String?) {\n    val n = s.length + 1\n}\n"
        assertTrue(KotlinFixes.fix(mapOf(vm to src), "e: file:///w/$vm:4:15 Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'String?'.").updated.isEmpty())
    }

    @Test fun screenFunctionGetsComposable() {
        val src = "package com.x.app.vm\n\nimport androidx.compose.material3.Text\n\nfun TopBar() {\n    Text(\"Hi\")\n}\n"
        val out = KotlinFixes.fix(mapOf(vm to src), "e: file:///w/$vm:6:5 @Composable invocations can only happen from the context of a @Composable function").updated.getValue(vm)
        assertTrue(out.contains("@Composable\nfun TopBar()")); assertTrue(out.contains("import androidx.compose.runtime.Composable"))
        assertTrue(KotlinSyntax.scan(out).ok)
    }

    @Test fun projectClassInsideAnotherClassIsImported() {
        val loopSrc = "package com.x.app.engine\n\nclass RepairLoop {\n    enum class Verdict { PASS, FAIL }\n    fun run() = Verdict.PASS\n}\n"
        val vmSrc = "package com.x.app.vm\n\nfun f(v: Verdict) = v == Verdict.PASS\n"
        val r = KotlinFixes.fix(mapOf(vm to vmSrc, loop to loopSrc), "e: file:///w/$vm:3:9 Unresolved reference 'Verdict'.")
        assertTrue(r.updated.getValue(vm).contains("import com.x.app.engine.RepairLoop.Verdict"))
    }

    @Test fun lazyListItemsImportIsAdded() {
        val src = "package a\n\n@Composable\nfun L(xs: List<String>) {\n    LazyColumn { items(xs) { Text(it) } }\n}\n"
        val (out, _) = KotlinImports.fix("L.kt", src, emptySet())
        assertTrue(out.contains("import androidx.compose.foundation.lazy.items"))
        assertFalse(out.contains("import androidx.compose.foundation.lazy.grid.items"))
    }

    @Test fun materialOptInMergesWithAnExistingFileOptIn() {
        val src = "@file:OptIn(kotlin.ExperimentalStdlibApi::class)\n\npackage a\n\n@Composable\nfun T() { TopAppBar(title = { Text(\"x\") }) }\n"
        val (out, _) = KotlinImports.fix("T.kt", src, emptySet())
        assertEquals(1, Regex("@file:OptIn").findAll(out).count())
        assertTrue(out.contains("androidx.compose.material3.ExperimentalMaterial3Api::class"))
    }

    @Test fun aboutLineAndTextBeforeThePackageAreRemoved() {
        val broken = "=== ABOUT: Checks whether the app is still running\npackage a\n\nclass P {\n    fun f() = 1\n}\n"
        assertTrue(KotlinSyntax.needsCleaning("P.kt", broken))
        assertTrue(KotlinSyntax.repair("P.kt", broken)!!.startsWith("package a"))
    }

    @Test fun xmlWithFencesAndTextIsCleaned() {
        val x = "Here is the layout:\n```xml\n<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"a\">A</string>\n</resources>\n```\nDone."
        assertEquals("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<resources>\n    <string name=\"a\">A</string>\n</resources>\n", KotlinSyntax.cleanXml(x))
    }
}
