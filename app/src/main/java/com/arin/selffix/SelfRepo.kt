package com.arin.selffix

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Finds Arin's own source repository on the person's GitHub, so a self-update never pushes to a repository the
 * account can't write to (that is what "GitHub: Not Found" means while uploading).
 *
 * Order: the repository set in Settings if this account can push to it → the person's own repository that holds
 * Arin's source (found automatically) → the person's own fork of the set repository (made once, Actions switched on).
 * The repository found is saved, so the next update goes straight there.
 */
object SelfRepo {
    data class Found(val repo: String, val branch: String, val note: String)

    /** Words in the approval request for a new repository (the Arin Update tab shows Approve for it). */
    const val NEW_REPO = "Arin needs to create a new GitHub repository in your account"

    /** The owner tapped Approve: the next try may create the repository. */
    fun approveNewRepo(context: Context) = com.arin.data.Settings(context).setSecret("SELF_NEW_REPO_OK", "1")

    private const val API = "https://api.github.com"
    private const val MARK_FILE = "app/src/main/java/com/arin/ArinApp.kt"

    private suspend fun call(token: String, method: String, path: String, body: String? = null, accept: String = "application/vnd.github+json"): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder().url(if (path.startsWith("http")) path else API + path)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", accept)
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .method(method, body?.toRequestBody("application/json".toMediaType()) ?: if (method == "POST" || method == "PUT") "".toRequestBody() else null)
                    .build()
                com.arin.builder.Http.client.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
            }.getOrElse { 0 to (it.message ?: "network error") }
        }

    /** (can push, default branch) for owner/name, or null when the account can't see it. */
    private suspend fun info(token: String, full: String): Pair<Boolean, String>? {
        val (code, body) = call(token, "GET", "/repos/$full")
        if (code != 200) return null
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val push = o.optJSONObject("permissions")?.optBoolean("push", false) ?: false
        return push to o.optString("default_branch", "main")
    }

    private suspend fun branchExists(token: String, full: String, branch: String): Boolean =
        branch.isNotBlank() && call(token, "GET", "/repos/$full/branches/$branch").first == 200

    /** True when [full]@[branch] holds Arin's source (its main app file and its build workflow). */
    private suspend fun holdsArin(token: String, full: String, branch: String): Boolean =
        call(token, "GET", "/repos/$full/contents/$MARK_FILE?ref=$branch").first == 200 &&
            call(token, "GET", "/repos/$full/contents/.github/workflows/build-apk.yml?ref=$branch").first == 200

    /** The person's own repositories, most recently pushed first. */
    private suspend fun ownRepos(token: String): List<Pair<String, String>> {
        val (code, body) = call(token, "GET", "/user/repos?per_page=100&sort=pushed&affiliation=owner")
        if (code != 200) return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            if (o.optJSONObject("permissions")?.optBoolean("push", true) == false) return@mapNotNull null
            o.optString("full_name") to o.optString("default_branch", "main")
        }
    }

    /** Finds (and saves) the repository a self-update can push to, or says plainly why there is none. */
    suspend fun resolve(context: Context?, token: String, configured: String, branch: String, onStep: (String) -> Unit): Result<Found> {
        if (token.isBlank()) return Result.failure(IllegalStateException("Not signed in to GitHub. Sign in in Settings → GitHub."))
        val conf = configured.trim().removePrefix("https://github.com/").trimEnd('/')

        // 1. The repository in Settings, when this account can push to it.
        if (conf.contains('/')) {
            val i = info(token, conf)
            if (i != null && i.first) {
                val b = if (branchExists(token, conf, branch)) branch else i.second
                if (holdsArin(token, conf, b)) return Result.success(save(context, Found(conf, b, "")))
            }
            onStep(if (i == null) "Arin's repository in Settings ($conf) can't be found from your GitHub account. Looking for your own copy of Arin"
                else "Your GitHub account can't push to $conf. Looking for your own copy of Arin")
        }

        // 2. The person's own repository holding Arin's source (names with "arin" are tried first).
        val mine = ownRepos(token).sortedByDescending { it.first.substringAfter('/').contains("arin", true) }
        for ((full, def) in mine.take(40)) {
            val candidates = listOf(branch, def, "arin", "main").filter { it.isNotBlank() }.distinct()
            for (b in candidates) {
                if (branchExists(token, full, b) && holdsArin(token, full, b)) {
                    onStep("Found Arin's source in your repository $full ($b). Using it from now on")
                    return Result.success(save(context, Found(full, b, "Using your repository $full")))
                }
            }
        }

        // 3. Fork the set repository into the person's account, and switch its Actions on — ONLY after the owner
        //    approved creating a new repository (Arin Update tab → Approve). Never on its own.
        val approved = context?.let { com.arin.data.Settings(it).secret("SELF_NEW_REPO_OK") == "1" } == true
        if (conf.contains('/') && info(token, conf) != null && !approved) {
            return Result.failure(IllegalStateException(SelfUpdateRules.NEEDS_APPROVAL + NEW_REPO +
                " (your own copy of Arin's source, from $conf) so the update can be built. Approve it in the Arin Update tab."))
        }
        if (conf.contains('/') && info(token, conf) != null) {
            context?.let { com.arin.data.Settings(it).setSecret("SELF_NEW_REPO_OK", "") }
            onStep("No copy of Arin in your account yet: making your own copy (fork) of $conf")
            val (code, body) = call(token, "POST", "/repos/$conf/forks", JSONObject().put("default_branch_only", false).toString())
            if (code in 200..299) {
                val full = runCatching { JSONObject(body).optString("full_name") }.getOrNull().orEmpty()
                if (full.isNotBlank()) {
                    for (i in 0 until 20) { if (info(token, full) != null) break; delay(3_000) }
                    call(token, "PUT", "/repos/$full/actions/permissions", JSONObject().put("enabled", true).put("allowed_actions", "all").toString())
                    val b = if (branchExists(token, full, branch)) branch else info(token, full)?.second ?: "main"
                    onStep("Your copy $full is ready. If GitHub doesn't start the build, open $full → Actions once and allow workflows")
                    return Result.success(save(context, Found(full, b, "Made your own copy $full")))
                }
            }
        }
        return Result.failure(
            IllegalStateException(
                "Couldn't find a GitHub repository with Arin's source that your account can write to. " +
                    "Upload Arin's source to a repository in your account, then set it in Settings → GitHub (Arin's repository).",
            ),
        )
    }

    private fun save(context: Context?, f: Found): Found {
        context?.let {
            val s = com.arin.data.Settings(it)
            s.setSecret("SELF_REPO", f.repo)
            s.setSecret("SELF_BRANCH", f.branch)
        }
        return f
    }

    // ---- For the Arin Update discussion: let the model look at Arin's real code ----

    /** Paths of Arin's source files (Kotlin, manifest, Gradle), so the model knows what exists. */
    suspend fun fileList(token: String, full: String, branch: String): List<String> {
        val (code, body) = call(token, "GET", "/repos/$full/git/trees/$branch?recursive=1")
        if (code != 200) return emptyList()
        val arr = runCatching { JSONObject(body).optJSONArray("tree") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            o.optString("path").takeIf { o.optString("type") == "blob" && (it.endsWith(".kt") || it.endsWith(".kts") || it.endsWith(".xml") || it.endsWith(".md")) }
        }.filter { !it.startsWith("app/src/main/res/") || it.contains("values") }
    }

    /** One source file's text. */
    suspend fun readFile(token: String, full: String, branch: String, path: String): String? {
        val (code, body) = call(token, "GET", "/repos/$full/contents/${path.trimStart('/')}?ref=$branch", accept = "application/vnd.github.raw")
        return if (code == 200) body else null
    }
}
