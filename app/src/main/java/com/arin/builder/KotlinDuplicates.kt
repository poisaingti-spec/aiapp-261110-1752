package com.arin.builder

/**
 * ARIN-PROTECTED: duplicate-guard — "two things have the same name" (Kotlin: Redeclaration / Conflicting declarations /
 * Conflicting overloads). Models often write the same function, class or value twice: once in MainActivity.kt and again
 * in its own file, or twice in one file after a fix is pasted in. Arin itself (no AI) keeps one copy — the one in its
 * own file (Greeting.kt for Greeting, Color.kt for colours, Theme.kt for the theme…), otherwise the one that was there
 * first — and removes the others. Overloads with different parameters are left alone, and so are `private` copies in
 * different files (those are allowed).
 */
object KotlinDuplicates {
    data class Decl(val file: String, val name: String, val kind: String, val signature: String, val private: Boolean,
                    val start: Int, val end: Int, val text: String)

    private val modifiers = "(?:(?:public|private|internal|protected|data|sealed|enum|abstract|open|inline|value|annotation|const|" +
        "override|suspend|tailrec|operator|infix|external|expect|actual|lateinit|inner|final|noinline|crossinline)\\s+)*"
    private val declStart = Regex("""^(?:@[\w.]+(?:\([^)]*\))?\s+)*$modifiers(fun|class|object|interface|val|var|typealias)\s+(?:<[^>]*>\s*)?([\w.<>?, ]+\.)?`?(\w+)`?""")

    private fun pkg(text: String) = Regex("""(?m)^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1).orEmpty()

    /** Top-level declarations of one file, with the lines they cover (annotations and doc comments included). */
    fun topLevel(file: String, text: String): List<Decl> {
        val lines = text.lines()
        val depth = KotlinSyntax.lineDepths(text)
        val out = mutableListOf<Decl>()
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (depth.getOrElse(i) { 0 } != 0 || l.isEmpty() || l[0].isWhitespace()) { i++; continue }
            val m = declStart.find(l)
            if (m == null) { i++; continue }
            val kind = m.groupValues[1]; val receiver = m.groupValues[2]; val name = m.groupValues[3]
            // Back over annotations and doc comments that belong to it.
            var s = i
            val floor = (out.lastOrNull()?.end ?: -1) + 1
            while (s > floor) {
                val p = lines[s - 1].trim()
                if (p.startsWith("@") || p.startsWith("/**") || p.startsWith("*") || p.startsWith("*/") || p.startsWith("//")) { s--; continue }
                // The end of a multi-line annotation such as @Preview(\n showBackground = true\n)
                if (depth.getOrElse(s - 1) { 0 } > 0 || p.startsWith(")")) {
                    var k = s - 1
                    while (k > floor && depth.getOrElse(k) { 0 } > 0) k--
                    if (lines[k].trim().startsWith("@")) { s = k; continue }
                }
                break
            }
            // Forward to where the declaration ends: depth back at 0 and the next line isn't a continuation.
            var e = i
            while (e + 1 < lines.size) {
                val next = lines[e + 1]
                val d = depth.getOrElse(e + 1) { 0 }
                if (d == 0 && (next.isBlank() || !next[0].isWhitespace() && !next.trimStart().startsWith(".") && !next.trimStart().startsWith("?") &&
                        !next.trimStart().startsWith(")") && !next.trimStart().startsWith("=") && !next.trimStart().startsWith(":"))) break
                e++
            }
            val body = lines.subList(s, e + 1).joinToString("\n")
            val sig = if (kind == "fun") receiver + name + "(" + paramTypes(lines.subList(i, e + 1).joinToString("\n"), name) + ")" else name
            out += Decl(file, name, kind, sig, Regex("""(^|\s)private\s""").containsMatchIn(l.substringBefore(kind + " ")), s, e, body)
            i = e + 1
        }
        return out
    }

    /** "fun f(a: Int, b: String = "x")" → "Int,String": what makes two functions the same to the compiler. */
    private fun paramTypes(decl: String, name: String): String {
        val open = decl.indexOf("$name(").takeIf { it >= 0 }?.plus(name.length) ?: return ""
        var d = 0; val sb = StringBuilder(); val parts = mutableListOf<String>()
        for (c in decl.substring(open + 1)) {
            when (c) {
                '(', '<', '[', '{' -> { d++; sb.append(c) }
                ')', '>', ']', '}' -> { if (d == 0 && c == ')') break; d--; sb.append(c) }
                ',' -> if (d == 0) { parts += sb.toString(); sb.clear() } else sb.append(c)
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) parts += sb.toString()
        return parts.map { p -> p.substringAfter(':', "").substringBefore('=').replace(Regex("""@\w+(\([^)]*\))?"""), "").replace(Regex("\\s+"), "") }
            .filter { it.isNotEmpty() }.joinToString(",")
    }

    /** Names the compiler reported as declared twice, from its error lines. */
    fun reported(errors: String): Set<String> = buildSet {
        Regex("""Redeclaration:?\s*\[?\s*(?:[\w ]*?)(?:class|object|interface|typealias|val|var|fun)\s+(?:[\w.]+\.)?`?(\w+)""").findAll(errors).forEach { add(it.groupValues[1]) }
        Regex("""Conflicting declarations:?\s*\[?\s*(?:[\w ]*?)(?:val|var|class|object)\s+(?:[\w.]+\.)?`?(\w+)""").findAll(errors).forEach { add(it.groupValues[1]) }
        Regex("""Conflicting overloads:?\s*\[?\s*(?:@\w+\s+)*(?:[\w ]*?)fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?`?(\w+)`?\(""").findAll(errors).forEach { add(it.groupValues[1]) }
    }

    fun isDuplicateError(line: String) = Regex("(?i)redeclaration|conflicting (declarations|overloads)").containsMatchIn(line)

    private fun home(d: Decl): Int {
        val base = d.file.substringAfterLast('/').removeSuffix(".kt")
        var s = 0
        if (base == d.name || d.name.endsWith("Preview") && base == d.name.removeSuffix("Preview")) s += 4
        if (d.file.contains("/theme/") && (
                d.name.endsWith("Theme") && base == "Theme" || d.name == "Typography" && base == "Type" ||
                d.name == "Shapes" && base == "Shape" || d.kind == "val" && base == "Color")) s += 4
        if (base == "MainActivity" && d.name == "MainActivity") s += 4
        return s
    }

    /**
     * Removes duplicate top-level declarations. [only]: just these names (from the compiler); empty = every
     * duplicate with the same signature in the same package. [changed]: files written in this build (the older copy
     * elsewhere is preferred). Returns the files that changed and a note per removal.
     */
    fun fix(files: Map<String, String>, only: Set<String> = emptySet(), changed: Set<String> = emptySet()): Pair<Map<String, String>, List<String>> {
        val kt = files.filterKeys { it.endsWith(".kt") }
        val all = kt.flatMap { (p, t) -> runCatching { topLevel(p, t) }.getOrDefault(emptyList()).map { pkg(t) to it } }
        val remove = mutableMapOf<String, MutableList<Decl>>()
        val notes = mutableListOf<String>()
        all.filter { only.isEmpty() || it.second.name in only }
            .groupBy { (p, d) -> p + "|" + (if (d.kind == "fun") "fun:" + d.signature else "type:" + d.name) }
            .values.filter { it.size > 1 }
            .forEach { group ->
                val decls = group.map { it.second }
                // private copies in different files are allowed by Kotlin
                if (decls.any { it.private } && decls.map { it.file }.distinct().size > 1) return@forEach
                // val/var with the same name as a class is a real conflict too, but only fix what is clearly a copy:
                // same kind of thing (fun/fun, class/class, val/val).
                if (decls.map { if (it.kind == "var") "val" else it.kind }.distinct().size > 1) return@forEach
                val keep = decls.maxWith(compareBy<Decl>({ home(it) }, { if (it.file in changed) 0 else 1 }).thenByDescending { kt.keys.indexOf(it.file) * 100_000 + it.start })
                decls.filter { it !== keep }.forEach { d ->
                    remove.getOrPut(d.file) { mutableListOf() } += d
                    notes += "removed the second copy of ${d.name} from ${d.file.substringAfterLast('/')} (kept the one in ${keep.file.substringAfterLast('/')})"
                }
            }
        val updated = remove.mapValues { (p, ds) ->
            val lines = kt.getValue(p).lines().toMutableList()
            ds.sortedByDescending { it.start }.forEach { d ->
                var e = d.end
                if (e + 1 < lines.size && lines[e + 1].isBlank()) e++
                for (k in e downTo d.start) lines.removeAt(k)
            }
            lines.joinToString("\n")
        }
        return updated to notes
    }
}
