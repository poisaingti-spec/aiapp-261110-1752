package com.arin

import com.arin.builder.KotlinImports
import com.arin.builder.KotlinSyntax
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The no-AI build repairs from 1.15.1–1.15.4. */
class BuildGuardsTest {
    private val good = "package a\n\nclass A {\n    fun f() {\n        val s = \"x { \${1 + 2} }\"\n        val c = '}'\n    }\n}\n"

    @Test fun balancedFileIsOk() = assertTrue(KotlinSyntax.scan(good).ok)
    @Test fun strayBraceIsRepaired() = assertEquals(good.trim(), KotlinSyntax.repair("A.kt", good + "}\n")!!.trim())
    @Test fun missingBraceIsRepaired() = assertEquals(good.trim(), KotlinSyntax.repair("A.kt", good.removeSuffix("}\n"))!!.trim())
    @Test fun fencesAndProseAreRemoved() =
        assertEquals(good.trim(), KotlinSyntax.repair("A.kt", "Here is the fix:\n```kotlin\n$good```\nThat should work.")!!.trim())

    @Test fun importsInTheMiddleMoveToTheTop() {
        val broken = "package a\n\nimport x.Y\n\nclass A {\n    fun f() {}\nimport androidx.compose.material.icons.filled.Star\n}\n"
        assertTrue(KotlinSyntax.misplacedImports(broken))
        val fixed = KotlinSyntax.repair("A.kt", broken)!!
        assertFalse(KotlinSyntax.misplacedImports(fixed))
        assertTrue(fixed.indexOf("import androidx.compose.material.icons.filled.Star") < fixed.indexOf("class A"))
    }

    @Test fun missingComposeImportsAreAdded() {
        val src = "package a\n\n@Composable\nfun Home() {\n    var n by remember { mutableStateOf(0) }\n    Text(\"\$n\", Modifier.padding(8.dp))\n}\n"
        val (out, _) = KotlinImports.fix("Home.kt", src, emptySet())
        listOf("androidx.compose.runtime.Composable", "androidx.compose.runtime.remember", "androidx.compose.runtime.mutableStateOf",
            "androidx.compose.runtime.getValue", "androidx.compose.runtime.setValue", "androidx.compose.material3.Text",
            "androidx.compose.ui.Modifier", "androidx.compose.foundation.layout.padding", "androidx.compose.ui.unit.dp")
            .forEach { assertTrue("missing $it", out.contains("import $it")) }
    }

    @Test fun projectOwnNamesAreNotImported() {
        val src = "package a\n\n@Composable\nfun Home() { Text(\"x\") }\n"
        val (out, _) = KotlinImports.fix("Home.kt", src, setOf("Text"))
        assertFalse(out.contains("import androidx.compose.material3.Text"))
    }
}

class DuplicateGuardTest {
    private val main = "package com.example.app\n\nval Purple80 = Color(0xFFD0BCFF)\n\n/** Says hello. */\n@Composable\n" +
        "fun Greeting(name: String, modifier: Modifier = Modifier) {\n    Text(\"Hello\")\n}\n\n@Composable\nfun Greeting(count: Int) { Text(\"x\") }\n"
    private val greet = "package com.example.app\n\n@Composable\nfun Greeting(name: String, modifier: Modifier = Modifier) {\n    Text(\"Hi\")\n}\n"
    private val color = "package com.example.app\n\nval Purple80 = Color(0xFFD0BCFF)\n"
    private val files = linkedMapOf("a/MainActivity.kt" to main, "a/Greeting.kt" to greet, "a/ui/theme/Color.kt" to color)

    @Test fun copiesAreRemovedFromTheWrongFileAndOverloadsKept() {
        val (out, notes) = com.arin.builder.KotlinDuplicates.fix(files)
        val m = out.getValue("a/MainActivity.kt")
        org.junit.Assert.assertEquals(2, notes.size)
        org.junit.Assert.assertFalse(m.contains("Purple80")); org.junit.Assert.assertFalse(m.contains("Says hello"))
        org.junit.Assert.assertFalse(m.contains("fun Greeting(name"))
        org.junit.Assert.assertTrue(m.contains("fun Greeting(count: Int)"))
        org.junit.Assert.assertTrue(com.arin.builder.KotlinSyntax.scan(m).ok)
        org.junit.Assert.assertFalse(out.containsKey("a/Greeting.kt")); org.junit.Assert.assertFalse(out.containsKey("a/ui/theme/Color.kt"))
    }

    @Test fun privateCopiesInDifferentFilesAreAllowed() {
        val (out, _) = com.arin.builder.KotlinDuplicates.fix(mapOf("a/A.kt" to "package a\n\nprivate fun helper() = 1\n", "a/B.kt" to "package a\n\nprivate fun helper() = 1\n"))
        org.junit.Assert.assertTrue(out.isEmpty())
    }

    @Test fun compilerMessagesAreRead() = org.junit.Assert.assertEquals(setOf("Greeting", "Purple80", "MainActivity"),
        com.arin.builder.KotlinDuplicates.reported("e: x.kt:1:1 Conflicting overloads: [fun Greeting(name: String): Unit]\n" +
            "e: y.kt:3:5 Conflicting declarations: [val Purple80: Color]\ne: z.kt:1:1 Redeclaration: class MainActivity : ComponentActivity"))

    @Test fun rImportIsAddedOutsideTheMainPackage() {
        val src = "package com.example.app.ui\n\n@Composable\nfun T() { Text(stringResource(R.string.app_name)) }\n"
        val (out, _) = com.arin.builder.KotlinImports.fix("T.kt", src, emptySet(), rPackage = "com.example.app")
        org.junit.Assert.assertTrue(out.contains("import com.example.app.R"))
    }
}
