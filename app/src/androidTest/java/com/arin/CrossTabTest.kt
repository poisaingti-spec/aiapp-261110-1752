package com.arin

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arin.chat.ChatViewModel
import com.arin.data.ChatStore
import com.arin.data.Settings
import com.arin.llm.ApiFormat
import com.arin.llm.ModelOption
import com.arin.llm.Provider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Test A — two tabs use the SAME provider and model (a local stand-in for an OpenAI-compatible API such as DeepSeek)
 * at the same time, while the test switches between the tabs during streaming. Every request the "provider" receives
 * is recorded: no request may carry the other tab's task, and each answer must land in its own tab.
 */
@RunWith(AndroidJUnit4::class)
class CrossTabTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val bodies = CopyOnWriteArrayList<String>()
    private val active = AtomicInteger(0)
    private val maxActive = AtomicInteger(0)
    private lateinit var server: ServerSocket
    private val created = mutableListOf<String>()

    private fun startServer(): Int {
        server = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use { sock ->
                        val input = sock.getInputStream().buffered()
                        val head = StringBuilder()
                        while (!head.endsWith("\r\n\r\n")) { val b = input.read(); if (b < 0) return@use; head.append(b.toChar()) }
                        val len = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toInt() ?: 0
                        val body = ByteArray(len).also { var r = 0; while (r < len) { val n = input.read(it, r, len - r); if (n < 0) break; r += n } }.toString(Charsets.UTF_8)
                        val out = sock.getOutputStream()
                        if (!head.startsWith("POST") || !head.contains("/chat/completions")) {
                            val j = "{\"data\":[{\"id\":\"deepseek-test\"}]}"
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${j.length}\r\nConnection: close\r\n\r\n$j".toByteArray())
                            return@use
                        }
                        bodies += body
                        val now = active.incrementAndGet(); maxActive.updateAndGet { maxOf(it, now) }
                        try {
                            // Only the last user message decides the answer; the marker shows which tab asked.
                            val tag = Regex("TAB(ONE|TWO)-\\w+").findAll(body).lastOrNull()?.value ?: "NONE"
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
                            for (piece in listOf("Answer ", "for ", "REPLY-FOR-$tag", " done.")) {
                                Thread.sleep(700)
                                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"$piece\"}}]}\n\n".toByteArray()); out.flush()
                            }
                            out.write("data: [DONE]\n\n".toByteArray()); out.flush()
                        } finally { active.decrementAndGet() }
                    }
                }
            }
        }
        return server.localPort
    }

    @After fun tearDown() {
        runCatching { server.close() }
        val store = ChatStore(ctx); created.forEach { runCatching { store.delete(it) } }
    }

    private fun ui(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    @Test fun sameModelTwoTabsAtOnceNeverMix() {
        val port = startServer()
        val base = "http://127.0.0.1:$port/v1"
        lateinit var vm: ChatViewModel
        ui {
            vm = ChatViewModel(rule.activity.application)
            Settings(ctx).setKeyFor(base, "test-key-for-local-server")
            vm.setCustomBase(base, ApiFormat.OpenAI)
            vm.chooseProvider(Provider.Custom)
            vm.chooseModel(ModelOption("deepseek-test", "DeepSeek (test)"))
            com.arin.selffix.CreditSaver.setWorkerMode(ctx, false)
            vm.setClaudeHelper(false)
        }
        var a: String? = null; var b: String? = null
        ui { vm.newChat(); vm.send("TABONE-alpha design the level editor screen for my game") }
        ui { a = vm.chatId }
        Thread.sleep(300)
        ui { vm.newChat(); vm.send("TABTWO-bravo list the router settings I should review") }
        ui { b = vm.chatId }
        // Switch between the tabs while both are streaming.
        repeat(4) { Thread.sleep(400); ui { vm.open(a!!) }; Thread.sleep(400); ui { vm.open(b!!) } }
        // Wait for both replies to finish.
        val until = System.currentTimeMillis() + 90_000
        var running = true
        while (running && System.currentTimeMillis() < until) { Thread.sleep(500); ui { running = vm.runs.isNotEmpty() } }
        assertFalse("replies still running after 90 s", running)
        created += listOfNotNull(a, b)

        assertTrue("both tabs asked the provider", bodies.any { it.contains("TABONE") } && bodies.any { it.contains("TABTWO") })
        assertTrue("the two tabs really ran at the same time", maxActive.get() >= 2)
        for (body in bodies) assertFalse("a request carried both tabs' tasks: ${body.take(300)}", body.contains("TABONE") && body.contains("TABTWO"))

        Thread.sleep(1_000) // answers of a tab that wasn't open are saved in the background
        val store = ChatStore(ctx)
        val chatA = store.load(a!!)!!.messages.joinToString("\n") { it.text }
        val chatB = store.load(b!!)!!.messages.joinToString("\n") { it.text }
        assertTrue(chatA, chatA.contains("REPLY-FOR-TABONE")); assertFalse(chatA.contains("TABTWO"))
        assertTrue(chatB, chatB.contains("REPLY-FOR-TABTWO")); assertFalse(chatB.contains("TABONE"))
    }
}
