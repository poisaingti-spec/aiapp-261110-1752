package com.arin.terminal

/**
 * ARIN-PROTECTED: bounded-retry — within one task (one reply of one chat), the exact same command in the same folder
 * is not run again after it failed [maxSame] times with nothing changed in between: the model is told to fix the
 * cause first (read the error, change a file or the command) or try another way. A file written or edited, or a
 * package installed, counts as a change, so a corrected retry always runs. Never shared between chats.
 */
class RetryGuard(private val maxSame: Int = 2) {
    private val fails = HashMap<String, Int>()
    private val lastError = HashMap<String, String>()

    private fun key(cmd: String, cwd: String?) = cwd.orEmpty() + "\u0000" + cmd.trim().replace(Regex("\\s+"), " ")

    /** null = run it; otherwise the reason it isn't run again (for the model). */
    @Synchronized fun check(cmd: String, cwd: String?): String? {
        val k = key(cmd, cwd)
        val n = fails[k] ?: 0
        if (n < maxSame) return null
        return "Not run again: this exact command already failed $n times in this task (in ${cwd ?: "the home folder"}), " +
            "last error: ${lastError[k].orEmpty().take(300)}. Fix the cause first (read the error, change a file or the " +
            "command), or try another way."
    }

    @Synchronized fun record(cmd: String, cwd: String?, ok: Boolean, error: String = "") {
        val k = key(cmd, cwd)
        if (ok) { fails.remove(k); lastError.remove(k) } else { fails[k] = (fails[k] ?: 0) + 1; lastError[k] = error }
    }

    /** Something changed (a file was written or edited, a package installed): failed commands may be tried again. */
    @Synchronized fun changed() { fails.clear(); lastError.clear() }
}
