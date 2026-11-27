package com.arin.selffix

// ARIN-PROTECTED-RULE: self-update-only-arin
// ─────────────────────────────────────────────────────────────────────────────────────────────
// STRICT RULE for the Arin Update tab and every self-update (set by the owner, protected):
//   • A self-update changes ONLY Arin itself, in Arin's own source repository. It never makes a new app.
//   • The app id stays com.arin.app and the signing key stays the same, so the new APK installs OVER the
//     Arin that is installed now (all chats, settings and logins kept).
//   • The version number always goes up.
//   • Lines and files marked "ARIN-PROTECTED" can't be removed or changed by an update, unless the owner
//     unlocks them in the Arin Update tab.
// ─────────────────────────────────────────────────────────────────────────────────────────────

object SelfUpdateRules {
    /** ARIN-PROTECTED: Arin's fixed app id. */
    const val APP_ID = "com.arin.app"
    const val MARKER = "ARIN-PROTECTED"

    /** Start of the step shown when an update needs a protected rule changed: the owner must approve it first. */
    const val NEEDS_APPROVAL = "🔒 Needs your approval: "

    /** ARIN-PROTECTED: files an update may never touch (signing key, build setup). */
    private val lockedFiles = listOf("app/arin-debug.keystore", "gradle/wrapper/gradle-wrapper.jar", "gradlew", "settings.gradle.kts")
    private const val GRADLE = "app/build.gradle.kts"

    /** Told to the model for every self-update. */
    const val RULE_FOR_MODEL = "\n\nSTRICT RULES FOR UPDATING ARIN (protected, from the owner):\n" +
        "- You are updating Arin ITSELF. Do not create a new app, project or repository.\n" +
        "- Keep applicationId = \"$APP_ID\", the namespace and the signingConfigs block exactly as they are, so the update " +
        "installs over the installed Arin. Never touch app/arin-debug.keystore or the .github folder.\n" +
        "- Keep every line and file marked $MARKER exactly as it is (it is a protected rule). Change only what is asked. " +
        "If the requested feature truly needs a protected rule changed, the owner must approve it first: don't change it " +
        "unless the change says the owner approved it.\n" +
        "- Do not change or remove any other existing feature or code unless the change asks for it.\n" +
        "- Research the internet first for anything you aren't sure of, download what you need, and keep fixing errors " +
        "until the build works.\n"

    /** True when the owner's own words ask to change a protected rule (checked only for text the owner typed). */
    fun asksToUnlock(ownerText: String): Boolean =
        Regex("""(?i)\b(change|remove|edit|modify|disable|delete|unlock|update|replace)\b[\s\S]{0,60}\b(protected|auto-?fix rule|autofix rule)""")
            .containsMatchIn(ownerText)

    private fun markedLines(text: String): List<String> = text.lines().map { it.trim() }.filter { it.contains(MARKER) }

    private fun versionCode(gradle: String): Int? = Regex("""versionCode\s*=\s*(\d+)""").find(gradle)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Enforces the rules on the files an update changed. [original] = Arin's source as read from GitHub,
     * [files]/[pending] = the agent's working copy and the changes it will upload. Returns notes for the model.
     */
    fun enforce(
        original: Map<String, String>,
        files: MutableMap<String, String>,
        pending: MutableMap<String, ByteArray?>,
        unlocked: Boolean,
    ): List<String> {
        val notes = mutableListOf<String>()
        fun restore(path: String, why: String) {
            // GitHub already has the original, so the change is simply not uploaded.
            val old = original[path]
            if (old != null) files[path] = old else files.remove(path)
            pending.remove(path)
            notes += why
        }
        for (path in pending.keys.toList()) {
            val new = files[path]
            when {
                path.startsWith(".github/") || lockedFiles.any { path == it } ->
                    restore(path, "Kept $path unchanged: it can't be changed by an Arin update (strict rule).")
                !unlocked && original[path]?.let { old -> old.contains("$MARKER-RULE") && new != old } == true ->
                    restore(path, "$NEEDS_APPROVAL" + "this update wants to change $path, which holds a protected rule. It was kept as it is; " +
                        "the owner must approve the change first (Arin Update tab → Approve).")
                !unlocked && original[path]?.let { old -> markedLines(old).any { line -> new == null || line !in markedLines(new) } } == true ->
                    restore(path, "$NEEDS_APPROVAL" + "this update wants to change a protected rule in $path. It was kept as it is; " +
                        "the owner must approve the change first (Arin Update tab → Approve). Meanwhile make the change without " +
                        "touching lines marked $MARKER, if that is possible.")
            }
        }
        // The app id and signing must stay, and the version must go up, so the update installs over this Arin.
        val oldGradle = original[GRADLE]
        val gradle = files[GRADLE]
        if (oldGradle != null && gradle != null) {
            var g = gradle
            if (!g.contains("applicationId = \"$APP_ID\"")) {
                g = g.replace(Regex("""applicationId\s*=\s*"[^"]*""""), "applicationId = \"$APP_ID\"")
                notes += "applicationId must stay $APP_ID (strict rule); it was put back."
            }
            if (oldGradle.contains("arin-debug.keystore") && !g.contains("arin-debug.keystore")) {
                g = oldGradle
                notes += "The signing setup in $GRADLE must stay (strict rule); that file was put back."
            }
            val before = versionCode(oldGradle)
            val now = versionCode(g)
            if (before != null && (now == null || now <= before)) {
                g = g.replace(Regex("""versionCode\s*=\s*\d+"""), "versionCode = ${before + 1}")
                val name = Regex("""versionName\s*=\s*"([^"]+)"""").find(g)?.groupValues?.get(1)
                val oldName = Regex("""versionName\s*=\s*"([^"]+)"""").find(oldGradle)?.groupValues?.get(1)
                if (name != null && name == oldName) {
                    val bumped = Regex("""(\d+)$""").find(name)?.let { m -> name.substring(0, m.range.first) + (m.value.toInt() + 1) } ?: "$name.1"
                    g = g.replace("versionName = \"$name\"", "versionName = \"$bumped\"")
                }
            }
            if (g != oldGradle) { files[GRADLE] = g; pending[GRADLE] = g.toByteArray() }
        } else if (oldGradle != null && gradle == null) {
            restore(GRADLE, "$GRADLE can't be deleted (strict rule).")
        }
        return notes
    }

    /** Before installing a self-update: it must be Arin (same app id and same signing key) and not older. Null = OK. */
    fun checkApk(context: android.content.Context, apk: java.io.File): String? {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val flags = android.content.pm.PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return "That file isn't a valid APK."
        if (info.packageName != context.packageName) {
            return "Blocked: this APK is a different app (${info.packageName}), not Arin. The Arin Update tab only installs updates of Arin."
        }
        @Suppress("DEPRECATION")
        val mine = runCatching { pm.getPackageInfo(context.packageName, flags) }.getOrNull()
        @Suppress("DEPRECATION")
        val newSig = info.signatures?.firstOrNull()?.toByteArray()
        @Suppress("DEPRECATION")
        val oldSig = mine?.signatures?.firstOrNull()?.toByteArray()
        if (newSig != null && oldSig != null && !newSig.contentEquals(oldSig)) {
            return "Blocked: this APK is signed with a different key, so it can't install over the current Arin."
        }
        @Suppress("DEPRECATION")
        val newCode = info.versionCode
        @Suppress("DEPRECATION")
        val oldCode = mine?.versionCode ?: 0
        if (newCode < oldCode) return "Blocked: this APK (version $newCode) is older than the installed Arin ($oldCode)."
        return null
    }
}
