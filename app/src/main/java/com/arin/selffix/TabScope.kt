package com.arin.selffix

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * ARIN-PROTECTED: strict-tab-scope — every tab's work (each chat, each build, Arin Update, Security) runs tagged with its
 * own tab id. Helper sessions, model sessions and research caches read the tag, so nothing is ever shared between tabs —
 * even a call that forgot to pass its tab gets its own fresh session instead of a shared one.
 */
class TabScope(val id: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TabScope> {
        /** This coroutine's tab, or a one-off id (never shared) when the work isn't tagged. */
        suspend fun current(): String = kotlin.coroutines.coroutineContext[Key]?.id ?: ("oneoff-" + System.nanoTime())
    }
}
