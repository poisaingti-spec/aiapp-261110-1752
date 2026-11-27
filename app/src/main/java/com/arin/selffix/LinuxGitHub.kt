package com.arin.selffix

import android.content.Context

/**
 * Lets commands in Arin's Linux use the person's GitHub sign-in when a task needs it: `git clone/pull/push` of their
 * private repositories and the GitHub API (`$GH_TOKEN`). The token stays in a file only root in Linux can read.
 */
object LinuxGitHub {
    private const val ENV = "root/.arin/github.env"
    private const val HELPER = "root/.arin/git-credential-github"

    /** Writes the sign-in for Linux (or removes it when signed out). Cheap; safe to call before every command. */
    fun sync(context: Context, token: String) {
        runCatching {
            val root = com.arin.terminal.Linux.rootfs(context)
            if (!java.io.File(root, "bin").exists()) return
            java.io.File(root, "root/.arin").mkdirs()
            val env = java.io.File(root, ENV)
            if (token.isBlank()) { env.delete(); return }
            val text = "export GH_TOKEN='${token.replace("'", "")}' GITHUB_TOKEN='${token.replace("'", "")}'\n"
            if (!env.exists() || env.readText() != text) {
                env.writeText(text)
                env.setReadable(false, false); env.setReadable(true, true)
            }
            val helper = java.io.File(root, HELPER)
            if (!helper.exists()) {
                helper.writeText("#!/bin/sh\n[ \"\$1\" = get ] || exit 0\n. /root/.arin/github.env\necho username=x-access-token\necho \"password=\$GH_TOKEN\"\n")
                helper.setExecutable(true, false)
            }
            // git reads this on every run: GitHub over https uses the helper above.
            val gitconfig = java.io.File(root, "root/.gitconfig")
            val cur = if (gitconfig.exists()) gitconfig.readText() else ""
            if (!cur.contains("git-credential-github")) {
                gitconfig.appendText("\n[credential \"https://github.com\"]\n\thelper = /root/.arin/git-credential-github\n")
            }
        }
    }

    /** What the model is told, when GitHub is available in Linux. */
    fun note(token: String): String = if (token.isBlank()) "" else
        "\n\nGitHub in Linux: the person is signed in to GitHub, and commands in Arin's Linux can use it when a task needs " +
            "it — git clone/pull/push of their repositories works over https, and the GitHub API works with " +
            "curl -H \"Authorization: Bearer \$GH_TOKEN\" https://api.github.com/… (or install gh with apt-get install -y gh). " +
            "Run `. /root/.arin/github.env` first in a command that needs \$GH_TOKEN. Use Linux, the terminal and GitHub " +
            "whenever the task needs them, also in the middle of working something out."
}
