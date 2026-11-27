package com.arin.builder

/**
 * ARIN-PROTECTED: compiler-fixes — fixes Arin makes from the compiler's own error lines, without AI (each one taken
 * from real failed builds):
 *  - "Function invocation 'first()' expected"            → adds the missing ()
 *  - "This declaration needs opt-in … '@X'" / "This … API is experimental" → adds @file:OptIn(X::class)
 *  - "Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver" → `?.` on a plain statement call
 *  - "@Composable invocations can only happen from the context of a @Composable function" → marks the screen function
 *    (a function named like a screen, e.g. TopBar()) @Composable
 *  - "Unresolved reference 'X'" where the project itself declares X in another package or inside a class → imports it
 * Anything not clearly fixable this way is left to the models.
 */
object KotlinFixes {
    data class Err(val file: String, val line: Int, val col: Int, val msg: String, val raw: String)
    data class Result(val updated: Map<String, String>, val fixed: Set<Err>, val notes: List<String>)

    private val errLine = Regex("""^e:\s+(?:file://)?(\S+?\.kts?):\s*(?:(\d+):(\d+)|\((\d+),\s*(\d+)\))?:?\s+(.*)$""")

    /** The compiler's error lines ("e: …/X.kt:12:5 message"). */
    fun errors(log: String): List<Err> = log.lines().mapNotNull { raw ->
        val l = raw.substringAfter("##[error]").trim().let { if (it.startsWith("e: ")) it else raw.trim().substringAfter("\t").let { t -> Regex("e: .*").find(t)?.value ?: "" } }
        val m = errLine.find(l) ?: return@mapNotNull null
        val line = (m.groupValues[2].ifBlank { m.groupValues[4] }).toIntOrNull() ?: 0
        val col = (m.groupValues[3].ifBlank { m.groupValues[5] }).toIntOrNull() ?: 0
        Err(m.groupValues[1], line, col, m.groupValues[6].trim(), raw)
    }.distinctBy { "${it.file}:${it.line}:${it.col}:${it.msg}" }

    /** The project file an error path points at ("/home/runner/…/app/src/main/java/a/B.kt" → "app/src/main/java/a/B.kt"). */
    fun pathOf(errPath: String, files: Collection<String>): String? =
        files.filter { errPath == it || errPath.endsWith("/$it") }.maxByOrNull { it.length }
            ?: files.filter { it.substringAfterLast('/') == errPath.substringAfterLast('/') }.singleOrNull()

    /** Adds `@file:OptIn(fq::class)`, merged into an existing @file:OptIn(...) line (the annotation can't repeat). */
    fun addFileOptIn(text: String, fq: String): String {
        if (text.contains("$fq::class") || Regex("""@file:OptIn\([^)]*\b${Regex.escape(fq.substringAfterLast('.'))}::class""").containsMatchIn(text)) return text
        val existing = Regex("""(?m)^@file:OptIn\((.*)\)\s*$""").find(text)
        return if (existing != null) text.replaceRange(existing.range, "@file:OptIn(${existing.groupValues[1].trim()}, $fq::class)")
        else "@file:OptIn($fq::class)\n\n$text"
    }

    private val experimentalApis = listOf(
        Regex("(?i)material3? API is experimental|ExperimentalMaterial3Api") to "androidx.compose.material3.ExperimentalMaterial3Api",
        Regex("(?i)foundation API is experimental|ExperimentalFoundationApi") to "androidx.compose.foundation.ExperimentalFoundationApi",
        Regex("(?i)layout API is experimental|ExperimentalLayoutApi") to "androidx.compose.foundation.layout.ExperimentalLayoutApi",
        Regex("(?i)animation API is experimental|ExperimentalAnimationApi") to "androidx.compose.animation.ExperimentalAnimationApi",
        Regex("(?i)ExperimentalComposeUiApi") to "androidx.compose.ui.ExperimentalComposeUiApi",
    )

    private fun lineStart(t: String, line: Int): Int {
        var at = 0
        repeat(line - 1) { val n = t.indexOf('\n', at); if (n < 0) return -1; at = n + 1 }
        return at
    }

    /** Project declarations by simple name → fully qualified names (top level and one level inside classes/objects). */
    fun index(files: Map<String, String>): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        for ((p, t) in files) {
            if (!p.endsWith(".kt")) continue
            val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(t)?.groupValues?.get(1).orEmpty()
            val top = runCatching { KotlinDuplicates.topLevel(p, t) }.getOrDefault(emptyList())
            top.forEach { d -> out.getOrPut(d.name) { mutableListOf() } += listOf(pkg, d.name).filter { it.isNotEmpty() }.joinToString(".") }
            val lines = t.lines(); val depth = KotlinSyntax.lineDepths(t)
            for (d in top.filter { it.kind in setOf("class", "object", "interface") }) {
                for (i in d.start..d.end) {
                    if (depth.getOrElse(i) { 0 } != 1) continue
                    val m = Regex("""^\s*(?:(?:private|internal|public|protected|data|sealed|enum|abstract|open|inner|value|annotation)\s+)*(class|object|interface)\s+(\w+)""").find(lines[i]) ?: continue
                    if (lines[i].contains("private ")) continue
                    out.getOrPut(m.groupValues[2]) { mutableListOf() } += listOf(pkg, d.name, m.groupValues[2]).filter { it.isNotEmpty() }.joinToString(".")
                }
            }
        }
        return out
    }

    fun fix(files: Map<String, String>, log: String): Result {
        val errs = errors(log)
        val work = files.toMutableMap()
        val fixed = mutableSetOf<Err>(); val notes = mutableListOf<String>()
        val idx by lazy { index(files) }
        // Bottom-up per file so line numbers stay right while editing.
        for (e in errs.sortedWith(compareByDescending<Err> { it.line }.thenByDescending { it.col })) {
            val p = pathOf(e.file, work.keys) ?: continue
            val t = work[p] ?: continue
            val name = p.substringAfterLast('/')
            val updated: String? = when {
                // 1) .first → .first()
                Regex("""Function invocation '(\w+)\(\)' expected""").find(e.msg) != null -> {
                    val fn = Regex("""Function invocation '(\w+)\(\)' expected""").find(e.msg)!!.groupValues[1]
                    val ls = lineStart(t, e.line); if (ls < 0) null else {
                        val le = t.indexOf('\n', ls).let { if (it < 0) t.length else it }
                        val line = t.substring(ls, le)
                        val at = (e.col - 1).takeIf { it >= 0 && line.startsWith(fn, it) }
                            ?: Regex("""(?<=\.)$fn\b(?!\s*[({])""").findAll(line).singleOrNull()?.range?.first
                        if (at == null || line.substring(at + fn.length).trimStart().startsWith("(")) null
                        else t.substring(0, ls) + line.substring(0, at + fn.length) + "()" + line.substring(at + fn.length) + t.substring(le)
                    }?.also { notes += "added the missing () after $fn in $name" }
                }
                // 2) opt-in needed
                e.msg.contains("needs opt-in", true) || e.msg.contains("is experimental", true) || e.msg.contains("must be marked with", true) -> {
                    val fq = Regex("""marked with '@([\w.]+)'""").find(e.msg)?.groupValues?.get(1)
                        ?: experimentalApis.firstOrNull { it.first.containsMatchIn(e.msg) }?.second
                    fq?.let { addFileOptIn(t, it).takeIf { n -> n != t } }?.also { notes += "allowed the experimental API ${fq.substringAfterLast('.')} in $name" }
                }
                // 3) nullable receiver: `x.call()` as a plain statement → `x?.call()`
                e.msg.startsWith("Only safe (?.) or non-null asserted (!!.) calls are allowed") -> {
                    val ls = lineStart(t, e.line); if (ls < 0) null else {
                        val le = t.indexOf('\n', ls).let { if (it < 0) t.length else it }
                        val line = t.substring(ls, le)
                        val c = (e.col - 1).coerceIn(0, maxOf(0, line.length - 1))
                        val dot = when {
                            c > 0 && line[c - 1] == '.' -> c - 1
                            line.getOrNull(c) == '.' -> c
                            else -> line.indexOf('.', c).takeIf { it > 0 && line.substring(c, it).all { ch -> ch.isLetterOrDigit() || ch == '_' } }
                        }
                        // Only a statement such as `previous.uncaughtException(t, e)` (its value isn't used): `?.` is safe.
                        val stmt = dot != null && Regex("""^\s*[\w.]+$""").matches(line.substring(0, dot)) &&
                            line.trimEnd().let { it.endsWith(")") || it.endsWith("}") } && dot > 0 && line[dot - 1] != '?' && line[dot - 1] != '!'
                        if (!stmt) null else t.substring(0, ls) + line.substring(0, dot!!) + "?" + line.substring(dot) + t.substring(le)
                    }?.also { notes += "made a call on a value that can be empty safe (?.) in $name" }
                }
                // 4) screen function missing @Composable
                e.msg.contains("@Composable invocations can only happen from the context of a @Composable function") -> {
                    val lines = t.lines()
                    val errIndent = lines.getOrNull(e.line - 1)?.takeWhile { it == ' ' }?.length ?: 0
                    var i = e.line - 2; var fnAt = -1
                    while (i >= 0) {
                        val l = lines[i]
                        val ind = l.takeWhile { it == ' ' }.length
                        if (ind < errIndent && Regex("""^\s*(?:(?:private|internal|public|override)\s+)*fun\s+(\w+)\s*\(""").containsMatchIn(l)) { fnAt = i; break }
                        if (ind < errIndent && Regex("""^\s*(class|object|interface)\b""").containsMatchIn(l)) break
                        i--
                    }
                    val fnName = if (fnAt >= 0) Regex("""fun\s+(\w+)""").find(lines[fnAt])!!.groupValues[1] else ""
                    val annotated = fnAt >= 0 && (lines[fnAt].contains("@Composable") || (1..3).any { k -> lines.getOrNull(fnAt - k)?.trim()?.startsWith("@Composable") == true })
                    // A lambda inside an already-@Composable function can't be fixed this way (left to the models).
                    if (fnAt < 0 || annotated || !fnName[0].isUpperCase() || lines[fnAt].contains("override ")) null
                    else {
                        val ind = lines[fnAt].takeWhile { it == ' ' }
                        val out = lines.toMutableList(); out.add(fnAt, "$ind@Composable")
                        val s = out.joinToString("\n")
                        (if (Regex("""(?m)^import androidx\.compose\.runtime\.(Composable|\*)\s*$""").containsMatchIn(s)) s
                        else KotlinImports.addImports(s, listOf("androidx.compose.runtime.Composable"))).also { notes += "marked $fnName() as a screen function (@Composable) in $name" }
                    }
                }
                // 5) a class/object the project declares elsewhere
                Regex("""^Unresolved reference:?\s*'?`?(\w+)`?'?\.?$""").find(e.msg) != null -> {
                    val sym = Regex("""^Unresolved reference:?\s*'?`?(\w+)""").find(e.msg)!!.groupValues[1]
                    val pkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(t)?.groupValues?.get(1).orEmpty()
                    val cands = idx[sym].orEmpty().filter { fq -> fq.substringBeforeLast('.') != pkg }.distinct()
                    val fq = cands.singleOrNull()
                    if (fq == null || sym.first().isLowerCase() || Regex("""(?m)^\s*import\s+[\w.]+\.$sym\s*$""").containsMatchIn(t)) null
                    else KotlinImports.addImports(t, listOf(fq)).also { notes += "imported $sym (declared in your project) in $name" }
                }
                else -> null
            }
            if (updated != null && updated != t) { work[p] = updated; fixed += e }
        }
        return Result(work.filter { (k, v) -> files[k] != v }, fixed, notes.distinct())
    }

    /** Follow-on errors that disappear when the error that caused them is fixed. */
    fun isCascade(msg: String) = Regex("""(?i)^(Unresolved reference '?it'?|Cannot infer type|Overload resolution ambiguity|Unresolved reference '?(second|first|component\d)'? on receiver of type 'Nothing')""").containsMatchIn(msg)
}
