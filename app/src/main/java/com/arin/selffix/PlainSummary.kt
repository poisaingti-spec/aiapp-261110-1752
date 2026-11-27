package com.arin.selffix

import com.arin.builder.BuildEvent
import com.arin.builder.BuildSession
import com.arin.builder.TimelineItem

/** What the AI is doing on an update, in plain English (no stack traces, no jargon). */
object PlainSummary {

    /** A technical error turned into one plain sentence. */
    fun plainError(raw: String): String {
        val first = raw.lines().map { it.trim() }.firstOrNull { it.isNotBlank() && !it.startsWith("at ") && !it.startsWith("Why it failed") }.orEmpty()
        val t = first.substringAfter("Exception: ").ifBlank { first }
        return when {
            t.contains("GitHub: Not Found", true) -> "GitHub couldn't find Arin's repository, or your account can't write to it."
            t.contains("Bad credentials", true) || t.contains("bad token", true) -> "Your GitHub sign-in has expired. Sign in again in Settings → GitHub."
            t.contains("rate limit", true) -> "GitHub is limiting requests for a while. Try again in a few minutes."
            t.contains("Unable to resolve host", true) || t.contains("timeout", true) -> "The phone lost its internet connection."
            t.contains("401") || t.contains("invalid api key", true) -> "The model's API key was refused. Check it in Settings."
            t.contains("429") || t.contains("Too Many Requests", true) -> "The model's service is busy. Try again shortly."
            else -> t.take(220)
        }
    }

    private fun plainStep(e: BuildEvent): String {
        val t = e.text.lineSequence().first().trim()
        return when (e.kind) {
            BuildEvent.Kind.WEB -> if (t.startsWith("Reading")) "Reading a web page about the problem" else "Searching the internet for a solution"
            BuildEvent.Kind.FILE -> "Writing a change: " + t.substringAfter(": ").substringBefore(" (").take(80)
            BuildEvent.Kind.THINKING -> "Thinking about how to make the change"
            BuildEvent.Kind.BUILD -> "Building the new version of Arin on GitHub (takes a few minutes)"
            BuildEvent.Kind.ERROR -> when {
                t.startsWith("Waiting for you") -> "Waiting for you — do the steps below; it checks again every 10 seconds and carries on by itself"
                t.startsWith("Trying again") -> t.substringBefore(" Press Stop")
                else -> "Found a problem and is fixing it: " + plainError(t)
            }
            BuildEvent.Kind.LIBRARY -> "Adding a library Arin needs"
            BuildEvent.Kind.DOWNLOAD -> "Downloading a file it needs"
            else -> t.take(140)
        }
    }

    /** A few short lines: what is happening now, and what has been done so far. */
    fun of(session: BuildSession?): String {
        if (session == null) return ""
        val items = session.timeline.toList()
        val start = items.indexOfLast { it is TimelineItem.User }.coerceAtLeast(0)
        val run = items.drop(start)
        val events = run.filterIsInstance<TimelineItem.Event>().map { it.event }
        val result = run.lastOrNull { it is TimelineItem.Result } as? TimelineItem.Result
        val searches = events.count { it.kind == BuildEvent.Kind.WEB }
        val parts = events.count { it.kind == BuildEvent.Kind.FILE }
        val fixes = events.count { it.kind == BuildEvent.Kind.ERROR && it.text.startsWith("Build failed") || it.kind == BuildEvent.Kind.ERROR && it.text.startsWith("Phone build failed") }
        val done = buildList {
            if (events.any { it.text.startsWith("Read ") && it.text.contains("source files") }) add("read Arin's code")
            if (parts > 0) add("wrote $parts change${if (parts == 1) "" else "s"}")
            if (searches > 0) add("searched the internet $searches time${if (searches == 1) "" else "s"}")
            if (fixes > 0) add("fixed build errors $fixes time${if (fixes == 1) "" else "s"}")
        }
        return buildString {
            when {
                session.running -> {
                    val now = events.lastOrNull()?.let { plainStep(it) } ?: "Starting"
                    append("Now: ").append(now)
                }
                result?.success == true && result.message.startsWith(com.arin.builder.BuildAgent.CODE_READY) ->
                    append("The code is written. Choose where to build it.")
                result?.success == true -> append("Finished: the new version of Arin is ready. Tap Install to update.")
                result != null -> {
                    val why = events.lastOrNull { it.kind == BuildEvent.Kind.ERROR }?.text ?: result.message
                    append("Stopped: ").append(plainError(result.message.ifBlank { why }))
                }
                else -> append("Waiting to start")
            }
            if (done.isNotEmpty()) append("\nSo far it has ").append(done.joinToString(", ")).append('.')
        }
    }
}
