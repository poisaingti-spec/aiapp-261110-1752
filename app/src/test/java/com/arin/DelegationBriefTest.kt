package com.arin

import com.arin.selffix.Delegation.Brief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic data only: fake keys, fake conversations. */
class DelegationBriefTest {
    private val fakeKey = "sk-test-ABCDEFGHIJKLMNOPQRSTUV1234567890"
    private val fakeGh = "ghp_" + "a".repeat(36)

    @Test fun savedSecretsAreRedacted() {
        val (t, n) = Brief.redact("Use $fakeKey to call the API", listOf(fakeKey))
        assertFalse(t.contains(fakeKey)); assertTrue(t.contains(Brief.MASK)); assertTrue(n >= 1)
    }

    @Test fun secretFormatsAreRedactedEvenWhenNotSaved() {
        val text = "token $fakeGh\nAuthorization: Bearer abcdefghijklmnopqrstuvwxyz123456\nAPI_KEY=supersecretvalue123\n" +
            "val password = \"hunter2hunter2\"\nkey AIza" + "B".repeat(35) + "\n-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----"
        val (t, _) = Brief.redact(text)
        listOf(fakeGh, "abcdefghijklmnopqrstuvwxyz123456", "supersecretvalue123", "hunter2hunter2", "AIza" + "B".repeat(35), "MIIEow")
            .forEach { assertFalse("leaked: $it", t.contains(it)) }
    }

    @Test fun hiddenValuesComeBackInTheAnswerOnly() {
        val code = "val api = Api(key = \"$fakeKey\")\nval gh = \"$fakeGh\""
        val (masked, hidden) = Brief.redactMap(code, listOf(fakeKey))
        assertFalse(masked.contains(fakeKey)); assertFalse(masked.contains(fakeGh)); assertEquals(2, hidden.size)
        // the model answers with the masks; the owner's values come back
        val answer = masked.replace("Api(", "WeatherApi(")
        assertEquals(code.replace("Api(", "WeatherApi("), Brief.restore(answer, hidden))
    }

    @Test fun ordinaryCodeIsNotRedacted() {
        val code = "    val token = prefs.getString(\"token\", \"\")\n    fun login(user: User, password: String) = api.call(password)"
        assertEquals(code, Brief.redact(code).first)
    }

    @Test fun conversationTranscriptLinesAreRemoved() {
        val task = "Fix the crash in MainActivity.kt\nUser: my sister's birthday is on Friday, remind me\n" +
            "Assistant: Sure!\n**User:** something private\nKeep the dark theme."
        val (t, removed) = Brief.stripConversation(task)
        assertEquals(3, removed)
        assertFalse(t.contains("birthday")); assertFalse(t.contains("private"))
        assertTrue(t.contains("Fix the crash in MainActivity.kt")); assertTrue(t.contains("Keep the dark theme."))
    }

    @Test fun codeInsideFencesAndIndentedParametersStay() {
        val task = "Fix this:\n```\nUser: this is a string in code\n```\nfun f(\n    user: User,\n)"
        val (t, removed) = Brief.stripConversation(task)
        assertEquals(0, removed); assertTrue(t.contains("User: this is a string in code")); assertTrue(t.contains("user: User,"))
    }

    @Test fun onlyRelevantRequirementsAreShared() {
        val reqs = listOf("The dark theme toggle must stay on the settings screen", "Export notes to PDF from the notes list",
            "Never upload the user's photos")
        val picked = Brief.relevant(reqs, "Fix the dark theme toggle on the settings screen, it crashes")
        assertEquals(listOf(reqs[0]), picked)
    }

    @Test fun briefRecordsCategoriesAndFilesWithoutContent() {
        val b = Brief.build("e: file:///p/app/src/main/java/a/MainActivity.kt:3:1 Unresolved reference 'x'\nUser: private chat\nkey $fakeKey",
            listOf(fakeKey), listOf("MainActivity must keep the unresolved reference check"))
        assertFalse(b.text.contains("private chat")); assertFalse(b.text.contains(fakeKey))
        assertTrue(b.categories.contains("build errors")); assertTrue(b.files.any { it.endsWith("MainActivity.kt") })
        assertEquals(1, b.transcriptLinesRemoved); assertTrue(b.secretsHidden >= 1)
    }
}
