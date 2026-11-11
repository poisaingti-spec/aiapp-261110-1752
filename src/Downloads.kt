package com.aiapp.data
object Downloads {
    fun extractCodeFilesFromText(text: String): Map<String, String> {
        val out = linkedMapOf<String, String>()

        // 1. === FILE: path === ... === END FILE ===
        val markerRegex = Regex("===\\s*FILE:\\s*([^=\\n]+?)\\s*===\\s*\\n([\\s\\S]*?)(?:===\\s*END FILE\\s*===|$)")
        markerRegex.findAll(text).forEach { m ->
            val path = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
                .removePrefix("```kotlin").removePrefix("```java").removePrefix("```xml").removePrefix("```kts").removePrefix("```")
                .removeSuffix("```").trim()
            if (path.isNotBlank() && body.isNotBlank()) {
                out[path] = body
            }
        }

        // 2. <file path="...">...</file>
        val xmlTagRegex = Regex("<file\\s+path=[\"']([^\"']+)[\"']\\s*>([\\s\\S]*?)</file>", RegexOption.IGNORE_CASE)
        xmlTagRegex.findAll(text).forEach { m ->
            val path = m.groupValues[1].trim()
            val body = m.groupValues[2].trim()
                .removePrefix("```kotlin").removePrefix("```java").removePrefix("```xml").removePrefix("```kts").removePrefix("```")
                .removeSuffix("```").trim()
            if (path.isNotBlank() && body.isNotBlank()) {
                out[path] = body
            }
        }
        if (out.isNotEmpty()) return out

        // 3. Standard Markdown fenced code blocks
        val fenceRegex = Regex("```([a-zA-Z0-9_-]*)\\s*\\n([\\s\\S]*?)```")
        var ktCounter = 1
        fenceRegex.findAll(text).forEach { m ->
            val lang = m.groupValues[1].trim().lowercase()
            val rawBody = m.groupValues[2].trim()
            if (rawBody.isBlank()) return@forEach

            // Skip pure ASCII directory trees like:
            // CalculatorApp/
            // ├── settings.gradle.kts
            if (rawBody.contains("├──") || rawBody.contains("└──") || rawBody.lines().size < 3 && !rawBody.contains("{") && !rawBody.contains("=")) {
                return@forEach
            }

            // Look at the 2 lines immediately preceding the code block in `text` for a filename hint
            val beforeText = text.substring(0, m.range.first).trimEnd()
            val prevLines = beforeText.lines().takeLast(2).joinToString(" ")
            val pathFromHeading = Regex("([a-zA-Z0-9_./-]+\\.(?:kt|kts|java|xml|gradle|properties|toml|pro))")
                .findAll(prevLines).lastOrNull()?.groupValues?.get(1)

            // Or look at the first line inside the code block for a comment like `// FILE: ...` or `// app/src/...`
            val firstLine = rawBody.lines().firstOrNull()?.trim().orEmpty()
            val pathFromComment = Regex("^(?://|#|<!--)\\s*(?:FILE:\\s*|path:\\s*)?([a-zA-Z0-9_./-]+\\.(?:kt|kts|java|xml|gradle|properties|toml|pro))", RegexOption.IGNORE_CASE)
                .find(firstLine)?.groupValues?.get(1)

            val cleanedBody = if (pathFromComment != null) {
                rawBody.lines().drop(1).joinToString("\n").trim()
            } else {
                rawBody
            }

            val explicitPath = pathFromComment ?: pathFromHeading
            if (explicitPath != null) {
                out[explicitPath] = cleanedBody
                return@forEach
            }

            // Otherwise infer from language or content
            when {
                lang in setOf("kotlin", "kt") || cleanedBody.contains("import androidx.compose") || cleanedBody.contains("class MainActivity") || cleanedBody.contains("@Composable") -> {
                    val className = Regex("\\b(?:class|object)\\s+([A-Za-z0-9_]+)").find(cleanedBody)?.groupValues?.get(1)
                    val fileName = when {
                        cleanedBody.contains("class MainActivity") || cleanedBody.contains("setContent") -> "MainActivity.kt"
                        className != null -> "$className.kt"
                        ktCounter == 1 -> "MainActivity.kt"
                        else -> "Screen$ktCounter.kt"
                    }
                    ktCounter++
                    out[fileName] = cleanedBody
                }
                lang == "xml" || cleanedBody.startsWith("<?xml") || cleanedBody.startsWith("<manifest") || cleanedBody.startsWith("<resources") -> {
                    val fileName = when {
                        cleanedBody.contains("<manifest") -> "app/src/main/AndroidManifest.xml"
                        cleanedBody.contains("<resources") -> "app/src/main/res/values/strings.xml"
                        else -> "app/src/main/res/layout/activity_main.xml"
                    }
                    out[fileName] = cleanedBody
                }
                lang in setOf("kts", "gradle") || cleanedBody.contains("rootProject.name") || cleanedBody.contains("compileSdk") -> {
                    val fileName = when {
                        cleanedBody.contains("rootProject.name") -> "settings.gradle.kts"
                        cleanedBody.contains("compileSdk") || cleanedBody.contains("applicationId") -> "app/build.gradle.kts"
                        else -> "build.gradle.kts"
                    }
                    out[fileName] = cleanedBody
                }
            }
        }
        return out
    }

}
