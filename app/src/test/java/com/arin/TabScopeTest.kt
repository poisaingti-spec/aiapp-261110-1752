package com.arin

import com.arin.selffix.TabScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

/** Strict tab isolation: every tab's work carries its own id; untagged work never shares one. */
class TabScopeTest {
    @Test fun eachTabSeesOnlyItsOwnId() = runBlocking {
        val a = async(TabScope("chat:A")) { TabScope.current() }
        val b = async(TabScope("build:7")) { withContext(kotlinx.coroutines.Dispatchers.Default) { TabScope.current() } }
        val u = async(TabScope("arin-update")) { TabScope.current() }
        assertEquals("chat:A", a.await()); assertEquals("build:7", b.await()); assertEquals("arin-update", u.await())
    }

    @Test fun untaggedWorkNeverSharesASession() = runBlocking {
        val x = TabScope.current(); Thread.sleep(1); val y = TabScope.current()
        assertTrue(x.startsWith("oneoff-")); assertNotEquals(x, y)
    }
}
