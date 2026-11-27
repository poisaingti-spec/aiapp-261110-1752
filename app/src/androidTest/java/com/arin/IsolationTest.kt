package com.arin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arin.builder.ProjectMemory
import com.arin.data.Chat
import com.arin.data.ChatStore
import com.arin.data.Message
import com.arin.data.Settings
import com.arin.llm.Turn
import com.arin.selffix.ChatMemory
import com.arin.selffix.Delegation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Conversation isolation and the delegation firewall, on a real device. Synthetic chats and test credentials only. */
@RunWith(AndroidJUnit4::class)
class IsolationTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val a = Chat(id = "iso-test-A", title = "Tab A", messages = List(24) { i -> Message(fromUser = i % 2 == 0, text = "ALPHA-PRIVATE-$i about my holiday plans") })
    private val b = Chat(id = "iso-test-B", title = "Tab B", messages = List(24) { i -> Message(fromUser = i % 2 == 0, text = "BRAVO-$i about the calculator app") })
    private val secret = "sk-test-ISOLATION0123456789ABCDEFGHIJ"

    @After fun cleanUp() {
        ChatStore(ctx).run { runCatching { delete(a.id) }; runCatching { delete(b.id) } }
        ChatMemory.forget(ctx, a.id); ChatMemory.forget(ctx, b.id)
        Settings(ctx).setSecret("isolation_test", "")
    }

    private fun turns(c: Chat) = c.messages.map { Turn(it.fromUser, it.text) }

    @Test fun tabAMessagesNeverAppearInTabBPrompt() = runBlocking {
        val store = ChatStore(ctx); store.save(a); store.save(b)
        ChatMemory.compact(ctx, a.id, turns(a), "holiday") { q -> "SUMMARY: " + q.take(300) }
        val sentForB = ChatMemory.compact(ctx, b.id, turns(store.load(b.id)!!), "calculator") { q -> "SUMMARY: " + q.take(300) }
        assertFalse(sentForB.any { it.text.contains("ALPHA-PRIVATE") })
    }

    @Test fun tabBHistoryCantBeReadWithTabAId() {
        val store = ChatStore(ctx); store.save(a); store.save(b)
        val loaded = store.load(a.id)!!
        assertTrue(loaded.messages.all { it.text.startsWith("ALPHA") })
        assertFalse(loaded.messages.any { it.text.contains("BRAVO") })
    }

    @Test fun restartDoesNotMixConversations() {
        ChatStore(ctx).run { save(a); save(b) }
        val fresh = ChatStore(ctx) // a new store, as after an app restart
        assertTrue(fresh.load(b.id)!!.messages.none { it.text.contains("ALPHA") })
        assertTrue(fresh.load(a.id)!!.messages.none { it.text.contains("BRAVO") })
    }

    @Test fun cachedMemoryIsPerConversation() = runBlocking {
        ChatMemory.compact(ctx, a.id, turns(a), "holiday") { "MEMO-OF-A holiday" }
        val forB = ChatMemory.compact(ctx, b.id, turns(b), "calculator") { "MEMO-OF-B calculator" }
        assertFalse(forB.any { it.text.contains("MEMO-OF-A") })
    }

    @Test fun workerAndHelperGetNoTranscriptAndNoSecrets() {
        Settings(ctx).setSecret("isolation_test", secret)
        val task = "Fix the crash in MainActivity.kt\nUser: ALPHA-PRIVATE my holiday plans\nUse key $secret"
        val (toWorker, _) = Delegation.gate(ctx, "worker", task, tab = b.id)
        val (toHelper, _) = Delegation.gate(ctx, "helper:Claude", task, tab = b.id)
        for (t in listOf(toWorker, toHelper)) {
            assertFalse(t.contains("ALPHA-PRIVATE")); assertFalse(t.contains(secret)); assertTrue(t.contains("MainActivity.kt"))
        }
    }

    @Test fun theOwnersOwnWordsNeverReachWorkerOrHelper() {
        val ownerWords = listOf("ALPHA please build a diary app for my wife Meera with a pink lock screen and her birthday reminder")
        val task = "TASK: build a diary app for my wife Meera with a pink lock screen and her birthday reminder\nUse Room for storage."
        for (dest in listOf("worker", "helper:Claude")) {
            val (sent, id) = Delegation.gate(ctx, dest, task, tab = a.id, ownerTexts = ownerWords)
            assertFalse(sent.contains("Meera")); assertFalse(sent.contains("birthday reminder"))
            assertTrue(sent.contains("Use Room for storage."))
            val rec = Delegation.recent(ctx).first { it.optString("id") == id && it.has("to") }
            assertEquals(1, rec.getInt("ownerPassagesRemoved")); assertFalse(rec.toString().contains("Meera"))
        }
    }

    @Test fun relevantRequirementsAreSharedOthersAreNot() {
        val mem = ProjectMemory.of(ctx, "test.isolation.app")
        mem.setRequirements("- The dark theme toggle must stay on the settings screen\n- Export notes to PDF from the notes list")
        val (brief, _) = Delegation.gate(ctx, "worker", "Fix the dark theme toggle on the settings screen", project = "test.isolation.app",
            requirements = mem.requirementList())
        assertTrue(brief.contains("dark theme toggle must stay"))
        assertFalse(brief.contains("Export notes to PDF"))
    }

    @Test fun auditRecordNamesWhatWasSharedWithoutTheContent() {
        Settings(ctx).setSecret("isolation_test", secret)
        val (_, id) = Delegation.gate(ctx, "worker", "e: MainActivity.kt:3:1 Unresolved reference 'x'\nUser: ALPHA-PRIVATE\nkey $secret", tab = a.id, project = "p1")
        Delegation.finish(ctx, id, "done")
        val rec = Delegation.recent(ctx).first { it.optString("id") == id && it.has("to") }
        assertEquals("worker", rec.getString("to")); assertEquals(a.id, rec.getString("tab")); assertEquals("p1", rec.getString("project"))
        assertTrue(rec.getJSONArray("files").toString().contains("MainActivity.kt"))
        assertTrue(rec.getJSONArray("shared").toString().contains("build errors"))
        assertTrue(rec.getInt("secretsHidden") >= 1); assertEquals(1, rec.getInt("transcriptLinesRemoved"))
        val raw = rec.toString()
        assertFalse(raw.contains("ALPHA-PRIVATE")); assertFalse(raw.contains(secret))
        assertNotNull(Delegation.recent(ctx).firstOrNull { it.optString("id") == id && it.optString("status") == "done" })
    }
}
