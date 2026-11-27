package com.arin.selffix

/**
 * ARIN-PROTECTED: project-graph — a project-scoped map of one app's source (no AI): files, classes, functions, imports
 * and which file uses which. The Boss uses it to send executors to the few files that matter (targeted inspection)
 * and to see what a change could break (impact), instead of re-scanning the whole project. One graph per project id;
 * never mixed with another project.
 */
class ProjectGraph private constructor(val projectId: String, val files: Map<String, Node>, val version: String = "") {
    data class Node(val path: String, val pkg: String, val classes: List<String>, val functions: List<String>,
                    val imports: List<String>, val uses: Set<String>)

    /** Files that use something declared in [path] (who could break if it changes). */
    fun dependents(path: String): List<String> {
        val n = files[path] ?: return emptyList()
        val names = n.classes.toSet() + n.functions.toSet()
        return files.values.filter { it.path != path && it.uses.any { u -> u in names } }.map { it.path }.sorted()
    }

    /** Files that [path] relies on. */
    fun dependencies(path: String): List<String> {
        val n = files[path] ?: return emptyList()
        return files.values.filter { it.path != path && (it.classes + it.functions).any { d -> d in n.uses } }.map { it.path }.sorted()
    }

    /** Where a class or function is declared. */
    fun declaring(name: String): List<String> = files.values.filter { name in it.classes || name in it.functions }.map { it.path }

    /** The [max] files most related to [query]: declarations named in it first, then word matches in names. */
    fun relevant(query: String, max: Int = 6): List<String> {
        val words = Regex("""[A-Za-z_][A-Za-z0-9_]{2,}""").findAll(query).map { it.value }.toSet()
        val lower = words.map { it.lowercase() }.toSet()
        return files.values.map { n ->
            val decl = (n.classes + n.functions).count { it in words } * 5
            val name = lower.count { w -> n.path.lowercase().contains(w) } * 3
            val fuzzy = (n.classes + n.functions).count { d -> lower.any { w -> w.length > 3 && d.lowercase().contains(w) } }
            n.path to decl + name + fuzzy
        }.filter { it.second > 0 }.sortedByDescending { it.second }.take(max).map { it.first }
    }

    private fun isTest(p: String) = p.contains("/test/") || p.contains("/androidTest/") || p.substringAfterLast('/').substringBeforeLast('.').endsWith("Test")

    /** Test files that exercise something declared in [path]. */
    fun testsFor(path: String): List<String> = dependents(path).filter(::isTest)

    /**
     * Focused context for one task: only the files related to [task], each with what it declares, what it relies on,
     * who uses it and which tests cover it. Unrelated files are left out; at most [maxChars].
     */
    fun focus(task: String, max: Int = 6, maxChars: Int = 2_500): String {
        val hits = relevant(task, max)
        if (hits.isEmpty()) return ""
        val b = StringBuilder("PROJECT MAP (source version $version; ${hits.size} of ${files.size} files — only the ones related to this task):\n")
        for (p in hits) {
            val n = files[p] ?: continue
            val line = "- $p" + (if (n.classes.isNotEmpty()) " | declares ${n.classes.take(6).joinToString()}" else "") +
                (if (n.functions.isNotEmpty()) " | functions ${n.functions.take(8).joinToString()}" else "") +
                dependencies(p).take(5).let { if (it.isEmpty()) "" else " | uses ${it.joinToString()}" } +
                dependents(p).filterNot(::isTest).take(5).let { if (it.isEmpty()) "" else " | used by ${it.joinToString()}" } +
                testsFor(p).take(3).let { if (it.isEmpty()) " | no tests" else " | tests ${it.joinToString()}" } + "\n"
            if (b.length + line.length > maxChars) break
            b.append(line)
        }
        return b.toString()
    }

    /** Impact before a change: what to edit, what depends on it, what to re-test. */
    fun impact(paths: List<String>): String = paths.joinToString("\n") { p ->
        val d = dependents(p)
        "$p → used by ${if (d.isEmpty()) "nothing else" else d.joinToString()}"
    }

    companion object {
        private val classRe = Regex("""(?m)^\s*(?:(?:public|private|internal|protected|abstract|open|sealed|data|enum|inner|annotation|value)\s+)*(?:class|object|interface)\s+([A-Z][A-Za-z0-9_]*)""")
        private val funRe = Regex("""(?m)^\s*(?:(?:public|private|internal|protected|override|suspend|inline|operator|infix|tailrec|open)\s+)*fun\s+(?:<[^>]+>\s*)?(?:[A-Za-z0-9_.]+\.)?([a-zA-Z_][A-Za-z0-9_]*)\s*\(""")
        private val composableRe = Regex("""@Composable\s+(?:(?:private|internal)\s+)?fun\s+([A-Z][A-Za-z0-9_]*)""")
        private val pkgRe = Regex("""(?m)^\s*package\s+([\w.]+)""")
        private val importRe = Regex("""(?m)^\s*import\s+([\w.*]+)""")
        private val word = Regex("""\b[A-Za-z_][A-Za-z0-9_]{2,}\b""")
        private val noise = setOf("fun", "val", "var", "class", "object", "return", "import", "package", "private", "override", "String", "Int",
            "Boolean", "List", "Map", "Unit", "this", "null", "true", "false", "when", "else", "Long", "Double", "Any")

        fun isSource(p: String) = p.endsWith(".kt") || p.endsWith(".java") || p.endsWith(".kts")

        /** Builds the graph from source text (path → content). Only .kt / .java / .kts files are read. */
        fun build(projectId: String, sources: Map<String, String>, version: String = ""): ProjectGraph =
            ProjectGraph(projectId, sources.filterKeys(::isSource).map { (path, text) -> path to parse(path, text) }.toMap(), version)

        /** Graph from already-parsed nodes (incremental refresh). */
        fun of(projectId: String, nodes: Map<String, Node>, version: String) = ProjectGraph(projectId, nodes, version)

        fun parse(path: String, text: String): Node {
                val noComments = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ").replace(Regex("""//[^\n]*"""), " ")
                val classes = classRe.findAll(noComments).map { it.groupValues[1] }.distinct().toList()
                val funs = (funRe.findAll(noComments) + composableRe.findAll(noComments)).map { it.groupValues[1] }.distinct().toList()
                val own = (classes + funs).toSet()
                val uses = word.findAll(noComments).map { it.value }.filter { it !in own && it !in noise }.toSet()
                return Node(path, pkgRe.find(noComments)?.groupValues?.get(1).orEmpty(), classes, funs,
                    importRe.findAll(noComments).map { it.groupValues[1] }.toList(), uses)
        }
    }
}

/**
 * ARIN-PROTECTED: project-map — one current map per project id (never shared between projects). Refreshed on every use:
 * only files whose content (or size/time on disk) changed are re-read, removed files drop out, and the map records the
 * source version it describes, so a stale map never stands in for the current source.
 */
object ProjectMaps {
    private class Entry(val graph: ProjectGraph, val sig: Map<String, String>)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    private fun version(sig: Map<String, String>) = sig.entries.sortedBy { it.key }.joinToString("|") { it.key + "=" + it.value }
        .hashCode().toUInt().toString(16)

    private fun refresh(projectId: String, sig: Map<String, String>, read: (String) -> String): ProjectGraph {
        val old = cache[projectId]
        if (old != null && old.sig == sig) return old.graph
        val nodes = sig.keys.associateWith { p ->
            val prev = old?.graph?.files?.get(p)
            if (prev != null && old.sig[p] == sig[p]) prev else ProjectGraph.parse(p, read(p))
        }
        return ProjectGraph.of(projectId, nodes, version(sig)).also { cache[projectId] = Entry(it, sig) }
    }

    /** From source text held in memory (a build's files). */
    fun forFiles(projectId: String, files: Map<String, String>): ProjectGraph {
        val src = files.filterKeys(ProjectGraph::isSource)
        return refresh(projectId, src.mapValues { it.value.hashCode().toString(16) + ":" + it.value.length }) { src[it].orEmpty() }
    }

    /** From a project folder on disk (a chat's work folder); null when it holds no source files. */
    fun forFolder(projectId: String, root: java.io.File, maxFiles: Int = 600): ProjectGraph? {
        if (!root.isDirectory) return null
        val found = root.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".git", "node_modules", ".idea", ".cxx") }
            .filter { it.isFile && ProjectGraph.isSource(it.name) && it.length() < 400_000 }.take(maxFiles).toList()
        if (found.isEmpty()) return null
        val byPath = found.associateBy { it.relativeTo(root).path }
        return refresh(projectId, byPath.mapValues { "${it.value.lastModified()}:${it.value.length()}" }) { p ->
            runCatching { byPath.getValue(p).readText() }.getOrDefault("")
        }
    }

    fun invalidate(projectId: String) { cache.remove(projectId) }
}
