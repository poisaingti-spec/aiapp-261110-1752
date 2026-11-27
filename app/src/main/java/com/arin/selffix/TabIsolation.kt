package com.arin.selffix

/**
 * ARIN-PROTECTED-RULE: tab-isolation — set by the owner. Every task stays in the tab (chat) where the owner gave it.
 * A tab follows ONLY the task the owner gave in that tab: no task, plan or file from another tab or chat is brought in.
 *
 *  - Past chats are not searched or listed, unless the owner's own message in this tab asks for an earlier chat.
 *  - Memory keeps only lasting facts and preferences about the owner — never tasks or projects.
 *  - Each chat has its own model session (the Claude Code harness never shares one between chats).
 *
 * No self-update, model, tool or app may remove or weaken this rule unless the owner approves it.
 */
object TabIsolation {
    const val RULE = "TAB RULE (strict, from the owner): this tab has its OWN task — only what the person asked in THIS " +
        "conversation. Never continue, mention or work on a task, app, plan or file from another tab or chat (for example a " +
        "build asked for in the Build tab or in another chat), even if you remember it or see it in memory or on disk. If " +
        "something seems to belong to another tab, ignore it and do only this tab's task.\n"

    /** Lasting facts only: told with the memory instruction. */
    const val MEMORY_RULE = "Only remember lasting facts about the person or how they like answers — never a task, app, " +
        "project, file or what you are working on (each tab keeps its own task). "

    private val asksPast = Regex(
        "(?i)\\b(other|another|previous|earlier|last|old|past|that)\\s+(chat|tab|conversation|session)s?\\b|" +
            "\\b(remember|recall|we (discussed|talked|did)|you (said|suggested|told me)|continue where|from before)\\b"
    )

    /** True only when the owner's own message in this tab asks about an earlier chat. */
    fun asksPastChats(ownerMessage: String): Boolean = asksPast.containsMatchIn(ownerMessage)
}
