package com.arin.builder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.arin.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException

/** Runs Build Mode in the background with a notification, so closing the app doesn't stop a build. */
class BuildService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.arin.CrashLog.handler { this })

    /** Progress updates arrive for every word the model writes; the notification only needs a few a second. */
    private var lastProgressNote = 0L
    /** One job per running build session, so several builds run side by side. */
    private val jobs = mutableMapOf<String, Job>()
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var inForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val id = intent.getStringExtra(EXTRA_SESSION)
            if (id == null) jobs.values.toList().forEach { it.cancel() } else jobs[id]?.cancel()
            return START_NOT_STICKY
        }
        val session = BuildRunner.session(intent?.getStringExtra(EXTRA_SESSION))
        val request = session?.pending
        if (session == null || request == null || jobs[session.id]?.isActive == true) {
            if (jobs.isEmpty()) stopSelf()
            return START_NOT_STICKY
        }
        session.pending = null
        createChannel()
        // Every startForegroundService() needs a startForeground() soon after, even when already in the foreground.
        startInForeground(progressNotification(if (jobs.isEmpty()) "Starting…" else "Starting another build…"))
        inForeground = true
        // Keep the CPU and network awake while building, even with the screen off.
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Arin:build")
                .apply { acquire(2 * 60 * 60 * 1000L) }
        }
        val appName = request.project?.appName ?: request.appName
        session.running = true
        jobs[session.id] = scope.launch(com.arin.selffix.TabScope("build:" + session.id)) {
            // The agent parses big model replies and project files: keep that work off the main thread,
            // or the screen freezes and Android closes the app ("isn't responding").
            // ARIN-PROTECTED: never give up — when an attempt fails, research the error on the internet and try another
            // method (resume, start fresh, the phone…), exactly the same request, until it is done. Stop ends it.
            var attemptReq: BuildRequest = request
            var attempt = 0
            var waitingFor: String? = null
            var lastAdvice = ""
            var lastErrorSig = ""
            var sameErrorRuns = 0
            fun errKey(t: String) = t.lowercase().replace(Regex("[0-9]+"), "").replace(Regex("\\s+"), " ").take(300)
            var result: BuildResult
            while (true) {
                result = try { kotlinx.coroutines.withContext(Dispatchers.Default) {
                    BuildAgent(
                        attemptReq,
                        context = this@BuildService,
                        takeSteering = { generateSequence { session.steering.poll() }.toList() },
                        hasSteering = { session.steering.isNotEmpty() },
                        emit = { event ->
                            com.arin.selffix.Activity.log(if (request.selfUpdate || request.project?.selfUpdate == true) "arin-update" else "build",
                                (if (event.kind == BuildEvent.Kind.ERROR) "⚠️ " else "") + event.text)
                            scope.launch {
                                runCatching {
                                    session.timeline += TimelineItem.Event(event.copy(text = event.text.take(6_000)))
                                    BuildRunner.save(this@BuildService, session)
                                    notify(PROGRESS_ID, progressNotification(prefix(appName) + event.text.take(200)))
                                }.onFailure { com.arin.CrashLog.record(this@BuildService, it, "build step") }
                            }
                        },
                        onProject = { p ->
                            scope.launch {
                                runCatching {
                                    session.project = p
                                    BuildRunner.save(this@BuildService, session)
                                }.onFailure { com.arin.CrashLog.record(this@BuildService, it, "saving the build") }
                            }
                        },
                        onProgress = { value, text ->
                            val now = System.currentTimeMillis()
                            if (value == null || now - lastProgressNote > 300) {
                                lastProgressNote = now
                                scope.launch {
                                    runCatching {
                                        session.progress = value
                                        session.progressText = text.take(300)
                                        if (value != null) notify(PROGRESS_ID, progressNotification(prefix(appName) + text.take(200).ifBlank { "Working…" }, value))
                                    }
                                }
                            }
                        },
                    ).run()
                } } catch (e: CancellationException) {
                    BuildResult(false, "Stopped.")
                } catch (e: Throwable) {
                    com.arin.CrashLog.record(this@BuildService, e, "build")
                    BuildResult(false, friendly(e), log = e.stackTraceToString().take(2_000))
                }
                // Directions sent after the agent's last step: carry on with them instead of stopping.
                if (result.success && session.steering.isNotEmpty()) {
                    val more = generateSequence { session.steering.poll() }.toList().joinToString("\n")
                    session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.STEP, "Carrying on with your direction: ${more.take(160)}"))
                    val codeOnly = result.message.startsWith(BuildAgent.CODE_READY)
                    attemptReq = if (codeOnly) request.copy(
                        // The code wasn't built yet: write it again with the direction included (nothing else changes).
                        description = request.description + "\n\nTHE OWNER'S DIRECTIONS ADDED WHILE WORKING:\n" + more,
                        project = null, resume = false,
                    ) else request.copy(
                        description = more, project = session.project ?: request.project, resume = false, stopAfterWriting = false,
                        requirements = "", retryHint = "",
                    )
                    continue
                }
                if (result.success || result.message == "Stopped." || !com.arin.selffix.AutoFix.UNTIL_FIXED) break
                attempt++
                val errText = result.message + "\n" + result.log.take(2_000)
                // Unlimited tries (changed with the owner's permission): pause and ask when the SAME error has failed 5
                // times in a row. "Retry" carries on from where it stopped.
                val sig = com.arin.selffix.CreditSaver.signature(errText).ifBlank { errKey(errText) }
                sameErrorRuns = if (sig == lastErrorSig) sameErrorRuns + 1 else 1
                lastErrorSig = sig
                if (sameErrorRuns >= 5) {
                    result = BuildResult(false, "⏸ Paused: the same error failed 5 times in a row, so Arin stopped to save your " +
                        "credit. Tap Retry to keep trying (it carries on from here), or tell it what to change.\n\n" +
                        com.arin.selffix.CreditSaver.trimErrors(errText, 12), result.repoUrl)
                    break
                }
                // The same error as last time while waiting for the person: just check again quietly (no new research).
                val sameAsBefore = waitingFor != null && errKey(errText) == waitingFor
                val advice = if (sameAsBefore) lastAdvice else runCatching {
                    com.arin.selffix.ErrorDoctor.diagnose(
                        this@BuildService,
                        if (request.selfUpdate || request.project?.selfUpdate == true) "the Arin Update tab, updating Arin" else "Build mode, building an app",
                        errText,
                        com.arin.selffix.ErrorDoctor.Brain(request.provider, request.apiKey, request.model, request.endpoint()),
                    ) { s -> session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.WEB, s)) }
                }.getOrNull().orEmpty()
                // ARIN-PROTECTED: ai-helper during builds — with the helper on, a failed attempt also gets its solution.
                val helperFix = if (sameAsBefore || com.arin.selffix.CreditSaver.on(this@BuildService) || !com.arin.selffix.ClaudeHelper.enabled(this@BuildService, request.helperTab.ifBlank { if (request.selfUpdate) "arin-update" else "build" }) ||
                    com.arin.selffix.ClaudeHelper.route(this@BuildService) == null) "" else runCatching {
                    com.arin.selffix.ClaudeHelper.ask(
                        context = this@BuildService,
                        // Only the error and the research — never the person's request or files (helper-sees-only-the-boss).
                        question = "A build of an Android app failed (attempt $attempt). I am the building model (the boss) and ask you for the solution: " +
                            "the real cause and exactly what to change.\n\nError:\n" + errText.take(4_000) + "\n\nResearched on the internet:\n" + advice.take(3_000) +
                            "\n\nWrite to the model.",
                        source = request.helperTab.ifBlank { if (request.selfUpdate) "arin-update" else "build" },
                    ) { s -> session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.THINKING, s)) }
                }.getOrNull().orEmpty().takeIf { !it.contains("couldn't be reached") && !it.contains("isn't connected") && !it.contains("switched off") }.orEmpty()
                if (helperFix.isNotBlank()) session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.THINKING,
                    "🟣 The AI helper gave the solution; the model applies it in the next attempt"))
                val needsApproval = result.message.startsWith(com.arin.selffix.SelfUpdateRules.NEEDS_APPROVAL)
                val needsYou = needsApproval || Regex("""(?i)by itself\??\**\s*:?\s*\**\s*no\b""").containsMatchIn(advice)
                if (!sameAsBefore) {
                    if (needsApproval) session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.STEP, result.message))
                    if (advice.isNotBlank()) session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.STEP, "🔎 How to fix (researched on the internet):\n$advice"))
                }
                // ARIN-PROTECTED: ask-before-building — the owner's strict rule (only the owner can change it): Arin never starts
                // a build by itself. A failed build stops here with the researched fix and ASKS; it builds again only when the
                // owner says so ("try again" / Retry).
                if (true) {
                    result = result.copy(message = result.message +
                        (if (advice.isNotBlank()) "\n\n🔎 How to fix (researched on the internet):\n" + advice.take(1_500) else "") +
                        (if (helperFix.isNotBlank()) "\n\n🟣 The helper's solution is ready to apply." else "") +
                        "\n\n❓ Should I build again with this fix? Say \"try again\" (or tap Retry). I won't start a new build by myself.")
                    break
                }
                // Short waits: 10 s while waiting for the person, otherwise 10–30 s between other methods.
                val waitS = if (needsYou) 10L else minOf(10L * attempt, 30L)
                if (!sameAsBefore) session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.ERROR,
                    if (needsYou) "Waiting for you: please do the steps shown. Arin checks again every 10 seconds and carries on by itself (press Stop to end)."
                    else "Trying again with another method (attempt ${attempt + 1}) in $waitS s. Press Stop to end."))
                waitingFor = if (needsYou) errKey(errText) else null
                lastAdvice = advice
                try { kotlinx.coroutines.delay(waitS * 1000) } catch (e: CancellationException) { result = BuildResult(false, "Stopped."); break }
                // Same request, same model and API (never switched); fresh keys in case the person just fixed them.
                val st = com.arin.data.Settings(this@BuildService)
                val proj = session.project
                // Resume from where it stopped (everything done so far is saved), never from the beginning.
                val resume = proj != null && (proj.lastSha != null || proj.pendingPaths.isNotEmpty() || proj.files.isNotEmpty())
                attemptReq = request.copy(
                    apiKey = st.key(request.provider).ifBlank { request.apiKey },
                    githubToken = st.secret("GITHUB").ifBlank { request.githubToken },
                    selfRepo = if (request.selfUpdate) st.secret("SELF_REPO").ifBlank { request.selfRepo } else request.selfRepo,
                    selfBranch = if (request.selfUpdate) st.secret("SELF_BRANCH").ifBlank { request.selfBranch } else request.selfBranch,
                    project = when {
                        request.selfUpdate && !resume -> request.project
                        proj != null -> if (resume) proj else proj.copy(round = 0, pendingPaths = emptyList())
                        else -> request.project
                    },
                    resume = resume,
                    retryHint = (request.retryHint + "\n\nAttempt $attempt failed: " + errText.take(1_500) + "\nResearched fix:\n" + advice.take(3_000) +
                        (if (helperFix.isNotBlank()) "\nThe AI helper's solution (apply it):\n" + helperFix.take(3_000) else "")).takeLast(8_000),
                )
            }
            // After every build: the APK and the source code go to a free file site (one after another until one works),
            // the direct links are given, and both are downloaded to the phone (Download/Arin).
            if (result.success && !result.message.startsWith(BuildAgent.CODE_READY) && (result.apkUrl != null || result.phoneInstallPath != null)) {
                runCatching {
                    val ctx = this@BuildService
                    val say: (String) -> Unit = { t -> session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.WEB, t)) }
                    val proj = session.project
                    val safe = (proj?.appName ?: request.appName).replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-').ifEmpty { "app" }
                    say("Getting the APK and the source code ready to share")
                    val apk = result.phoneInstallPath?.let { java.io.File(it) }?.takeIf { it.exists() }
                        ?: result.apkUrl?.let { com.arin.selffix.FileShare.fetch(ctx, it, "$safe.apk") }
                    val src = if (proj != null && proj.owner != BuildAgent.LOCAL_OWNER) {
                        com.arin.selffix.FileShare.repoZip(ctx, attemptReq.githubToken, proj.owner, proj.repo, proj.lastSha ?: proj.branch, safe)
                    } else proj?.let { p ->
                        // Built only on the phone: zip the project's source.
                        java.io.File(java.io.File(ctx.cacheDir, "share").apply { mkdirs() }, "$safe-source.zip").also { z ->
                            java.util.zip.ZipOutputStream(z.outputStream()).use { zip ->
                                p.files.forEach { (path, text) -> zip.putNextEntry(java.util.zip.ZipEntry("$safe/$path")); zip.write(text.toByteArray()); zip.closeEntry() }
                            }
                        }
                    }
                    val lines = mutableListOf<String>()
                    apk?.let { f -> com.arin.selffix.FileShare.upload(f, "APK", say)?.let { s ->
                        lines += "• APK: ${s.url} (${s.host}, ${s.keeps})"; com.arin.selffix.FileShare.downloadToPhone(ctx, s.url, "$safe.apk") } }
                    src?.let { f -> com.arin.selffix.FileShare.upload(f, "source code", say)?.let { s ->
                        lines += "• Source code: ${s.url} (${s.host}, ${s.keeps})"; com.arin.selffix.FileShare.downloadToPhone(ctx, s.url, "$safe-source.zip") } }
                    if (lines.isNotEmpty()) {
                        val text = "📥 Direct downloads (also downloading to your phone now, into Download/Arin):\n" + lines.joinToString("\n")
                        say(text)
                        result = result.copy(message = result.message + "\n\n" + text)
                    } else say("Every free file site failed right now; the GitHub link above still works.")
                }
            }
            session.timeline += TimelineItem.Result(result.success, result.message, result.apkUrl, result.repoUrl, result.explanation)
            if (!result.success && result.message != "Stopped.") {
                com.arin.CrashLog.note(this@BuildService, "Build failed", "$appName: ${result.message}", result.log)
            }
            if (!result.success && result.log.isNotBlank()) {
                session.timeline += TimelineItem.Event(BuildEvent(BuildEvent.Kind.ERROR, "Why it failed:\n" + result.log.take(3_000)))
            }
            session.running = false
            session.progress = null
            BuildRunner.saveNow(this@BuildService, session)
            val finished = session.project
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                runCatching { BuildRunner.onFinished?.invoke(result, finished, session.id) }
            }
            notify(DONE_ID + (session.id.hashCode() and 0xffff), doneNotification(appName, result))
            jobs.remove(session.id)
            if (jobs.isEmpty()) {
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                stopForeground(STOP_FOREGROUND_REMOVE)
                inForeground = false
                stopSelf()
            } else {
                notify(PROGRESS_ID, progressNotification("${jobs.size} build${if (jobs.size == 1) "" else "s"} still running"))
            }
        }
        return START_NOT_STICKY
    }

    /** With several builds running, each notification line says which app it's about. */
    private fun prefix(appName: String) = if (jobs.size > 1) "${appName.ifBlank { "App" }}: " else ""

    override fun onDestroy() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        scope.cancel()
        BuildRunner.sessions.values.forEach { it.running = false }
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, n)
        }
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App builds", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished builds", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun notify(id: Int, n: Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(text: String, value: Float? = null): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BuildService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(if (BuildRunner.runningCount > 1) "Building ${BuildRunner.runningCount} apps" else "Building your app")
            .setContentText(text)
            .setOngoing(true)
            .apply { if (value != null) setProgress(100, (value * 100).toInt().coerceIn(0, 100), value < 0f) }
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), if (BuildRunner.runningCount > 1) "Stop all" else "Stop", stop,
            ).build())
            .build()
    }

    private fun doneNotification(appName: String, r: BuildResult): Notification {
        val tap = r.apkUrl?.let {
            PendingIntent.getActivity(this, 2, Intent(Intent.ACTION_VIEW, Uri.parse(it)), PendingIntent.FLAG_IMMUTABLE)
        } ?: openAppIntent()
        return Notification.Builder(this, CHANNEL_DONE)
            .setSmallIcon(if (r.success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (r.success) "${appName.ifBlank { "Your app" }} is ready" else "${appName.ifBlank { "Your app" }} didn't build")
            .setContentText(if (r.success && r.apkUrl != null) "Tap to download the APK" else r.message)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
    }

    private fun friendly(t: Throwable): String = when {
        t is ProviderException && t.code == 401 -> "A key or token was rejected. Check Settings. (${t.message})"
        t is ProviderException && t.code == 403 ->
            "Not allowed: ${t.message}. Your GitHub token needs the public_repo and workflow scopes."
        t is ProviderException && t.code == 429 -> "Rate limit reached. Wait a moment and try again."
        t is IOException -> "Network error. Check your connection and try again."
        else -> t.message ?: "Something went wrong."
    }

    companion object {
        private const val CHANNEL = "builds"
        private const val CHANNEL_DONE = "builds_done"
        private const val PROGRESS_ID = 41
        private const val DONE_ID = 42
        private const val ACTION_STOP = "com.arin.STOP_BUILD"

        const val EXTRA_SESSION = "session"

        /**
         * Starts [request] in [session] (the Build tab's by default). Other sessions keep running: several builds
         * can run at once. Does nothing if this session is already building.
         */
        fun start(context: Context, request: BuildRequest, session: BuildSession = BuildRunner.current) {
            if (session.running) return
            BuildRunner.sessions[session.id] = session
            session.pending = request
            session.lastRequest = request
            session.timeline += TimelineItem.User(request.description)
            session.running = true
            BuildRunner.save(context, session)
            context.startForegroundService(Intent(context, BuildService::class.java).putExtra(EXTRA_SESSION, session.id))
        }

        /** Stops one build ([sessionId]), or every build when it's null. Progress so far stays saved. */
        fun stop(context: Context, sessionId: String? = null) {
            context.startService(Intent(context, BuildService::class.java).setAction(ACTION_STOP)
                .apply { if (sessionId != null) putExtra(EXTRA_SESSION, sessionId) })
        }
    }
}
