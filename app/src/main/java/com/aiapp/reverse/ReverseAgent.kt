package com.aiapp.reverse

import kotlinx.serialization.json.Json

/**
 * Turns a plain-language instruction into a concrete [EditPlan] the engine can apply on-device.
 *
 * The model is given the decoded APK facts plus the app's real string resources, and asked to return
 * a small JSON plan of the edits that achieve the user's change. This covers the edits that are safe
 * and reliable to apply on a phone: visible text (string resources), app name, version and package.
 * Deeper logic changes (smali/bytecode) are out of scope here and are flagged for the rea backend.
 *
 * For apps the user is authorized to modify (their own, open-source, security research, modding).
 */
object ReverseAgent {

    private val json = Json { ignoreUnknownKeys = true }

    val SYSTEM =
        "You modify an existing Android APK on the user's phone for apps they are authorized to change. " +
        "You are given the decoded app and its string resources. Decide the smallest set of edits that " +
        "achieve the user's instruction, and reply with ONLY a JSON object, no prose, in this shape:\n" +
        "{\"summary\":\"one line\",\"edits\":[{\"type\":\"setString\",\"name\":\"res_name\",\"value\":\"new text\"}," +
        "{\"type\":\"rename\",\"label\":\"New App Name\"},{\"type\":\"version\",\"name\":\"1.2\",\"code\":12}," +
        "{\"type\":\"repackage\",\"pkg\":\"com.new.id\"}]}\n" +
        "Only use string resource names that appear in the provided list. If the change needs code/logic " +
        "edits you cannot express with these edit types, return an empty edits array and say so in summary."

    fun buildPrompt(report: ApkReport, strings: Map<String, String>, instruction: String): String = buildString {
        appendLine("DECODED APP:")
        appendLine(report.toBriefing())
        appendLine()
        appendLine("STRING RESOURCES (name = value):")
        strings.entries.take(400).forEach { (k, v) -> appendLine("$k = ${v.replace("\n", " ").take(200)}") }
        appendLine()
        appendLine("USER INSTRUCTION: $instruction")
        appendLine()
        appendLine("Reply with ONLY the JSON plan.")
    }

    /** Extracts the first JSON object from the model's reply and decodes it into an [EditPlan]. */
    fun parsePlan(reply: String): EditPlan {
        val text = reply.substringAfter("```json", reply).substringBefore("```").let {
            if (it.isBlank() || !it.contains("{")) reply else it
        }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return EditPlan(summary = "The model didn't return a usable plan.")
        return runCatching { json.decodeFromString<EditPlan>(text.substring(start, end + 1)) }
            .getOrElse { EditPlan(summary = "Couldn't read the model's plan: ${it.message}") }
    }
}
