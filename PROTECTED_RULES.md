# Arin — protected rules

These rules were set by Arin's owner. **No self-update, model, tool or other app may remove, weaken or bypass them.**
Only the owner can change them. If a feature truly needs a protected rule changed, the model must **ask the owner's
approval first** (an Approve / Keep the rule card in the **Arin Update** tab), and changes it only after **Approve**.
A self-update that tries to change a protected rule without approval is held back and shown for approval.

How they are protected:

- Every line or file that belongs to a rule is marked `ARIN-PROTECTED`. During a self-update, any change that
  removes or edits a marked line is undone automatically (`selffix/SelfUpdateRules.kt`).
- Self-updates can never touch `.github/`, the signing key, the Gradle wrapper or `settings.gradle.kts`.
- The GitHub build checks that the rules are still in place ("Check protected rules are still in place") and
  refuses to build an APK if one is missing.

## 1. Research-first auto-fix (`selffix/AutoFix.kt`)

- On **any** error (build, phone build, crash, command, install, tool), Arin **first searches the internet**
  (several searches, and it reads the best pages), **then** applies the fix.
- It may download any tool, library or file the fix needs.
- It keeps going — search → fix → try again — **until the error is fixed**. Only the Stop button ends it early.
- When the same error comes back, it tries a different approach and asks Claude for help (if the Claude helper is on).

## 1b. Every tab: research first, never give up, exactly what was asked (`selffix/ErrorDoctor.kt`)

- In **every tab** (chats, Build, Arin Update, Claude Code, Movie Maker), when something fails, Arin first searches
  the internet for that error, then explains in plain English what went wrong.
- If Arin can fix it, it does, and keeps trying **other methods** until the request is complete (builds retry by
  themselves; chats carry on by themselves). Only Stop ends it.
- If **you** must do something (a key, a sign-in, a permission), it tells you exactly how, step by step, and builds
  check again every 10 seconds so they carry on by themselves once you've done it.
- It always does **exactly what you asked**: a different method may be used, but your request is never changed,
  shortened or dropped.

## 2. The Custom API is never changed automatically

Arin always uses the API and model you picked. It never switches your Custom API address, key or model on its own
(also not when the service is busy).

## 3. The Arin Update tab updates only Arin (`selffix/ArinUpdateScreen.kt`, `selffix/SelfUpdateRules.kt`)

- It only changes Arin itself, in Arin's own source repository. It never builds a new or different app.
- The app id stays `com.arin.app` and the signing key stays the same; the version number always goes up.
  So every update installs **over** the Arin you have installed (chats, settings and logins kept).
- Before installing, Arin checks the APK is Arin, signed with the same key, and not older — otherwise it blocks it.
- The tab uses all of Arin's features: internet search, reading pages, downloads, your GitHub and the Claude helper.

## New GitHub repositories only with your approval

Arin never creates a new GitHub repository for an update on its own. If one is needed (for example your own copy of
Arin's source), the Arin Update tab shows **Create a new GitHub repository? → Approve / Keep**, and Arin waits.

## Everything is saved; Resume carries on

- The Arin Update discussion, Movie Maker's conversation and every chat stay on the phone until **Clear all chats**.
- **Resume** (Arin Update, Build mode, chat builds, chat replies, Claude Code, Movie Maker) continues from where the work
  stopped — changes already made and uploaded are kept, nothing starts from the beginning.

## 4. AI helper in every tab (protected: `selffix/AiHelperUi.kt`, `selffix/ClaudeHelper.kt`)

The AI helper — its on/off switch and **Talk to Claude / ChatGPT / Gemini / Custom API** — is available in **every
tab** (a helper button in each screen's top bar, also in new empty chats, Build's file and project screens and voice
mode, plus the chat ⋮ menu). No update may remove, hide or change it; the GitHub build refuses to build if it's
missing from any screen. Only you can switch it on or off, by tapping. Changing it needs your approval.

### How the AI helper works

- **Your model always reads your whole message first.** In "Talk to …" mode, the tab's own model reads the message and
  drafts its answer; then it takes the helper's help (the helper corrects and fills gaps); then the model gives you the
  final answer. In Build mode the model writes the code first, then the helper reviews it and the model fixes what it found.

- **On:** any model (DeepSeek, ChatGPT, a Custom API, free models) can ask the chosen helper for help with
  `<ask_helper>…</ask_helper>`, and Arin asks it on its own when an error keeps coming back. **Off:** every model
  works on its own.
- The helper can be **Claude** (Claude Code Pro login or a Claude API key), **ChatGPT**, **Gemini**, or **any model of a
  Custom API you already saved**. "Talk to Custom API…" lists your saved APIs, then all of that API's models, to choose
  the assistant.
- The switch and "Talk to…" are in the ⋮ menu of every chat (next to Clear all chats) and in the helper button at the
  top of every other tab.
- **The helper only assists and forgets:** each question is asked fresh, nothing is kept afterwards. Claude Code as
  helper runs in a throwaway folder and home that are deleted afterwards, so the Claude Code tab's own conversation is
  never touched. **The tab's own model remembers everything** discussed in that tab, including the helper's answers.
- The tab's model and your Custom API are never changed by the helper.

## helper-never-takes-over
The model the person picks for a tab is the boss. The AI helper (Claude, GPT, Gemini or any Custom API) only advises it:
it finds causes, researches fixes and suggests next steps, as private notes to the boss. It never answers the person,
never does the boss's work in its place, and never changes the boss's API, key or model. Only the owner can change this.
(selffix/ClaudeHelper.kt, chat/ChatViewModel.kt, selffix/ArinUpdateChat.kt)

## helper-sees-only-the-boss
Only the model the person picked sees the person's messages and files. The AI helper never receives them: the boss
model describes what it needs in its own words, and Arin only passes the boss's own steps, drafts, code and error
output to the helper. Only the owner can change this. (selffix/ClaudeHelper.kt ask(), chat/ChatViewModel.kt,
selffix/ArinUpdateChat.kt, builder/BuildAgent.kt, builder/BuildService.kt)

## 5. Team of three in every tab (`selffix/TeamWork.kt`, `selffix/Worker.kt`)

When worker mode is on, every tab (chats, Reverse engineering/REA, Rebuild, Build, Arin Update) works as one team:

- **Main model (boss)** — only gives short directions and checks results. It does not write the commands or long
  files itself.
- **Middle model (worker, e.g. GPT-6 Luna)** — writes the commands, new files and research summaries from the boss's
  directions. It **never sees your chat, the files/code or any command output**. Arin removes code blocks from
  directions before the worker sees them.
- **Helper (e.g. Claude)** — helps the worker when it is stuck, with the same limits.
- **Arin itself (no AI)** runs the commands and shortens every result before the boss reads it.

No self-update may remove this, or let the worker or helper see the chat, files or results, without the owner's approval.

## 6. A summary after every task, in every tab — STRICT (`selffix/TaskSummary.kt`)

Set by the owner. After every task in every tab (chats, Reverse engineering, Rebuild, Build, Arin Update and all
others), Arin ends with:

- **Summary — what I did:** the main things done and their result (and anything that failed).
- **What to do next:** the next step(s) for the owner.

The model is told to write it. If it doesn't, Arin itself adds one from the steps it ran (no AI, no credit). The
Build tab shows it on every build result.

**No one may change or remove this rule — no self-update, model, tool, app or assistant (including Claude) — unless
the owner says so directly.** The GitHub build refuses to make an APK if it is missing.

## 7. Every task stays in its own tab (`selffix/TabIsolation.kt`)

Set by the owner. Each tab (each chat, Reverse engineering, Rebuild, Build, Arin Update, Claude Code…) follows ONLY the
task the owner gave in that tab:

- Other chats are not listed or searched, unless the owner's own message in that tab asks for an earlier chat.
- Memory keeps only lasting facts and preferences about the owner — never tasks, apps or projects.
- Every chat has its own model session; one chat never reuses another chat's session.
- Every model is told: never continue or mention a task from another tab.

No self-update, model, tool or app may remove or weaken this without the owner's approval.

## 8. Every APK and build tool, newest versions, in every tab (`terminal/Linux.kt`, `assets/apk-toolkit-setup.sh`)

Arin's Linux carries the full toolchain to compile source code and to decompile, change, rebuild, sign and package
APKs: JDK 21, Gradle (latest), Kotlin compiler (latest), Android SDK (command-line tools, platform, build-tools with
apksigner/d8/r8), native ARM aapt2/aidl/zipalign, arin-build, apktool, APKEditor, jadx, dex2jar (smali/baksmali),
bundletool, uber-apk-signer and androguard. Each install looks up the newest releases and updates older ones
(`arin-tools update`). Every tab installs them automatically the first time a command needs them, and every model is
told they are there.

## Explain after every coding (`builder/BuildAgent.kt`, `selffix/TaskSummary.kt`)

- In **every tab** (chats, Build, Arin Update and all others), after any code is written or changed, Arin explains it
  in detail in plain English for someone who isn't a programmer: what it does, each part, and how it works.
- Then it gives **suggestions** to change, improve or update it, and offers to make them or to build.
- Your own model (the boss) writes the explanation; the worker and helper never see the code for it.

## Complete project that compiles (`builder/BuildAgent.kt`, `builder/ProjectTemplate.kt`)

- When coding, Arin includes **every file** needed to compile the app into an APK in Android Studio and on GitHub: all code,
  the manifest, and every resource the code uses.
- Before building, Arin checks (no AI) that every resource the code uses exists, and has the missing ones written first.
- Libraries always use their newest **stable** release (never alpha, beta or RC, which break builds with
  "checkDebugAarMetadata"/"compileSdk" errors); saved pre-release versions are moved to stable automatically.
- Only the owner can change this rule.

## Tested before handover (`builder/BuildAgent.kt`, `builder/ProjectTemplate.kt`)

- **Every app, in every tab** — apps Arin makes, uploaded projects and Arin's own updates — is installed on an Android
  emulator, opened, and tapped around before any APK, install file or download link is given.
- Every model in every tab is told this too: an APK made any other way (terminal, rebuild, phone) is never shared untested.
- It happens **automatically on every build** — you never have to ask for it, and there is no switch to turn it off.
- A phone-only build is not handed over (a phone can't run an emulator): say "build on github" to have it tested.
- If it crashes, closes by itself or won't install, Arin fixes it like a build error and builds and tests again.
- The APK download link is given **only after the app opens and runs without crashing**. If the test can't run, or it
  still crashes after the fix rounds, no link is given — say "try again" to keep going.
- Only the owner can change this rule.

## Exactly what was asked (`selffix/TaskSummary.kt`, given to every model in every tab)

- Arin makes exactly the app or change the owner asked for, with every feature and detail. It never builds a different,
  smaller or simplified app, and never drops a feature to make an error go away.
- If something truly can't be done, it says so and asks instead of quietly changing it.
- Only the owner can change this rule.

## Heavy work off the main model (`builder/BuildAgent.kt`, `selffix/Worker.kt`)

- With worker mode on, your main model only directs and inspects (short). It never writes code.
- The workers write everything, in your order; if all refuse, the helper writes it. Explanations are written by the worker.
- If no worker or helper can be reached, Arin stops and says so instead of spending your main model's credit.
- Only the owner can change this rule.

## Main model uses as little credit as possible (`llm/Llm.kt`, `chat/ChatViewModel.kt`)

- With worker mode on, your main model goes straight to its API (never through the Claude Code harness), thinks as
  little as the service allows (DeepSeek's paid thinking off), remembers the whole chat through a running memory note (see below), and writes short
  replies (directions and checks). The worker and helper do the heavy work.
- Only the owner can change this rule.

## The main model remembers everything, cheaply (`selffix/ChatMemory.kt`)

- Each message, your main model gets: the chat's first message, a running **memory** of everything older (every request,
  requirement, decision, name, number, file, link and unfinished task), older messages that match your new message
  **word for word**, and the latest 8 messages in full.
- The memory is updated in batches from only the new messages (written by the offline model when you have one, free;
  otherwise briefly by your main model). The worker and helper never see the chat. Nothing is deleted from the chat.
- Only the owner can change this rule.

## Never stuck on the same error (`builder/BuildAgent.kt`)

- Arin reads the line that says what is really wrong (not Gradle's "What went wrong:" header) for the search and the fix.
- Every fix round is remembered and shown to the model, so a fix that didn't work isn't repeated.
- When the same error comes back: Arin fixes build-tool versions by itself (KSP, library versions), searches with new
  words, sends every file in full, and tells the model to change method completely (never by dropping a feature).
- Only the owner can change this rule.

## Engineering completion engine (`builder/BuildAgent.kt`, `selffix/TaskSummary.kt`)

- Every build error: your main model sends the helper (Claude) ONE whole-problem consultation — the full error report with
  the root-cause line, the code at every error site, toolchain versions, libraries, project files and what was already
  tried — and asks for root cause, exact changes, compatibility, regressions and a test. The fix is then applied, rebuilt
  and the new error compared with the old one ("Progress: the previous error is fixed").
- If the helper declines or can't answer, that is noted, nothing useful is lost, the same request is never sent again,
  and work carries on (web research + worker). No request is ever disguised to get around a refusal.
- A task ledger (COMPLETED / IN PROGRESS / BLOCKED / TESTING / VERIFIED) is shown in the steps and with every result.
- No build or test is ever reported as successful unless it really was.
- Only the owner can change this rule.

## Memory per tab (`chat/ChatViewModel.kt`)

- "Remember our past conversation" means only THIS tab's own conversation (its whole saved history), never another tab or what was built there.
- Only the owner can change this rule.

## Never builds by itself (`builder/BuildService.kt`, `selffix/TaskSummary.kt`)

- Arin never starts, restarts or retries a build on its own. A build starts only when the owner asks in their own words
  (or taps Build / Retry). If a build seems needed, the model asks first.
- A failed build stops with the researched fix and asks "Should I build again?".
- Only the owner can change this rule.

## Smart thinking for the main model (`builder/BuildAgent.kt`, `chat/ChatViewModel.kt`)

- In worker mode your main model (DeepSeek) keeps its FULL thinking power for big problems and puzzles: planning a new
  app, debugging, "why" questions, errors, a build error that keeps coming back, long requests.
- Routine steps (short directions, quick checks, simple replies) use little thinking to save credit.
- Either way it writes little; the worker and helper do the heavy work. All other rules still apply.
- Only the owner can change this rule.

## Claude uses as little of your limit as possible (`claudecode/ClaudeCodeModel.kt`, `selffix/CreditSaver.kt`)

- Claude gets only Arin's own short instructions (not Claude Code's long ones and tool list) and only the tools a task needs.
- Easy helper questions go to Claude Haiku; hard ones (build-error diagnosis) to the model you picked.
- Only the owner can change this rule.

## Master orchestration plan (`builder/ProjectMemory.kt`, `builder/BuildAgent.kt`, `selffix/TaskSummary.kt`)

- **Persistent project memory** (a file per project on the phone, no AI): original objective, every later request, a
  requirements checklist with statuses, every build error with each fix tried and the fix that worked, every build and
  emulator test, every commit (rollback points) and every changed file. It survives closing the app, model changes and
  expired sessions. An error fixed before is fixed the same way again; a fix that failed before is not repeated.
- **Requirements checklist with honest statuses** on every result: ⬜ pending, 🟡 implemented but not verified,
  ✅ verified (built and opened on the emulator without crashing), ⛔ blocked, ❓ needs you.
- **Change-impact analysis** (no AI): every file that uses the broken or changed code is found and given to the fixer and
  the helper, so related files are fixed together.
- **Plain-English plan** at the start of every build: what I understood, what Arin will do, how the models help, what's next.
- **Orchestration rule** for the main model: understand → analyse → split → delegate (worker codes, helper investigates;
  each gets only what its task needs, never a misleading description) → review → integrate → test → check every
  requirement → deliver.
- All earlier rules still apply — in particular, Arin never starts a build by itself.
- Only the owner can change this rule.
- **Watchdog** (ordinary code, no AI): if your main model stops answering during a build, the work is saved, Arin waits
  and asks again twice, then stops with everything saved so "try again" carries on from there.
- **Budgets per build:** at most 15 web researches and 8 helper consultations (changeable), so nothing runs away with credit.
- **Safe rollback:** say "roll back" or "undo last change" in the app's tab: its files go back to before the last change
  with a new commit on top (nothing deleted, can be undone). It never builds by itself afterwards.

## Security workstation and verified releases

- **Security** (in the side menu): APK analysis, Web/API check and Network check — all Arin's own code on the phone, no AI
  credit, nothing uploaded. Scanning someone else's site or device requires confirming you own it or have permission;
  every target is written to a scope log with its report (`files/security/`). Checks are passive/ordinary connections:
  no attacks, password guessing, packet injection or Wi-Fi cracking. "Ask Arin to fix" hands a report to a new chat,
  which asks before building.
- **Release gating:** every release (Arin and every app Arin builds) is published as NOT VERIFIED and is marked verified
  and newest only when the emulator test passes.
- **Signing:** private key from GitHub Secrets when set; otherwise the bundled (public) key so updates keep working —
  see SIGNING.md for the one-time safe migration.
- **Security Agent** (first tab of Security): plain-English requests ("analyse this APK", "check example.com", "audit my
  Wi-Fi", "collect evidence", "fix my project") are understood by Arin's own rules (no AI credit) and run the right check.
  Simple mode shows what was found and what to do; Expert mode shows the full report.
- **Vulnerability intelligence:** software versions seen in websites and device services are looked up in the US NVD,
  with CVSS scores and a 🚨 flag for anything on CISA's "exploited in the wild" list.
- **Evidence:** files copied to a private store, hashed (SHA-256/SHA-1/MD5), typed, with a chain-of-custody log and an
  integrity check.
- Not included on purpose: injection/IDOR/access-control attacks, packet capture, Wi-Fi attacks, or the AI installing and
  running attack tools against other people's systems.

## Error resolution and build recovery (1.15.1)
- **toolchain-guard** — before every build Arin (no AI) checks the toolchain: a KSP version that doesn't fit Kotlin 2.4.20 (e.g. 1.5.30-1.0.0) is replaced with KSP 2.3.13; Room's compiler runs only through KSP at room-runtime's version (no kapt, no duplicates); duplicate libraries are merged; new apps keep minSdk 26 / targetSdk 37.
- **github-auth-check** — GitHub access is diagnosed once before a build: invalid/expired token, missing repo/workflow permission, wrong owner or name, private repo without access, missing branch. Bad credentials are never retried; tokens are never shown.
- **research-fallback** — an empty answer, "no usable files", a failed git/search command (exit 128) or a helper usage limit is recorded, and the work moves to the next source; the same failed request is not repeated in that build.
- **batch-fix** — the fixing model is told to fix every listed error in one pass.
- **git-in-repo** — the helper's work folder is a git repository, and the helper is told not to repeat a failed command.
- **syntax-guard** (1.15.2, imports-in-the-middle fix 1.15.4) — before every build Arin (no AI) checks the brackets of every changed Kotlin file and removes ``` fences / explanation text; stray or missing closing braces are repaired directly. A syntax error from the compiler gets the broken file(s) repaired whole, all in one batch, and every rewrite or piece-fix is bracket-checked before it is used (a fix that would break the file is rejected).
- **import-guard** (1.15.3) — before every build Arin (no AI) adds the imports models forget (Compose, Material 3, icons, lifecycle, coroutines, Room, navigation, Android, `by` delegates) and the Material 3 opt-in. When the compiler reports only missing imports or a syntax error, Arin fixes it itself and rebuilds without asking any model.

## Privacy, conversation isolation and tests (1.16.0)
- **delegation-firewall** — every request to the worker/middle model (e.g. Claude) and the helper goes through `selffix/Delegation.kt`: saved API keys/tokens and common secret formats are redacted, conversation-transcript lines (User:/Assistant: …) are removed, only the project requirements relevant to the task are added, and an audit record (id, destination, tab, project, file names, kinds of information shared, secrets hidden, status — never the content) is kept in `delegation-audit.jsonl`. The main model alone sees the conversation.
- **bounded-scroll** — the Security screen's scrolling area always has a finite height, so it can't crash with "measured with an infinity maximum height". Tabs have full beginner-friendly names in a sideways-scrolling tab row.
- **tests-gate** — a release is marked verified only if the app opens and survives the tap test AND the JVM unit tests and the on-emulator tests (conversation isolation, delegation firewall, every Security tab) pass.
- **duplicate-guard** (1.21.0) — "two things have the same name" (Redeclaration / Conflicting declarations / Conflicting overloads) is fixed by Arin itself before every build and when the compiler reports it: one copy is kept (the one in its own file, e.g. Greeting.kt, Color.kt, Theme.kt; otherwise the older one) and the other copies are removed. Real overloads (different parameters) and private copies in different files are left alone. Also adds `import <app>.R` where it's missing.

## Your words only to the main model, and more build fixes (1.22.0)
- **owner-words-only-to-main** — the person's own messages and files go only to the main model (the one they picked). In worker mode the main model writes a short brief in its own words (one lean call per new request, kept in the project memory) and workers/the helper build from that brief; the gateway removes any passage copied from the person's messages before anything reaches the worker or helper (code and error logs stay), and the person's pictures and videos are never sent to another model to be described (read on the phone only when the main model can't see). The audit record counts removed passages.
- **compiler-fixes** — fixes Arin makes from the compiler's own messages, without AI: missing () after a function, experimental-API opt-ins (merged into one @file:OptIn), `?.` on a statement call on a value that can be empty, @Composable on a screen function, imports of classes the project declares elsewhere, LazyColumn/Grid `items` imports, "=== ABOUT:" lines and text left around Kotlin/XML files, and a FILE block missing its END line. Only the errors left over go to the models — all of them at once (up to 8 files per round).
- **api-index** — the models fixing an error get the real signatures of the project classes the failing code calls, so fixes match the code that exists.

## Reliability: tabs, refusals, working folders, requirements (1.23.0)
- **rule-baseline** — `.github/protected-baseline.txt` lists every protected rule marker and the file it lives in (166 when created). The build fails if any of them disappears; only the owner updates the baseline.
- **per-run-workdir** — each chat's commands, file reads and writes run in that chat's own working folder (where its last command ended; kept per chat across restarts). A `cd` holds for the next command of the same task and never moves another chat. A folder that no longer exists is reported.
- **bounded-retry** — within one task, the exact same command in the same folder isn't run a third time after failing twice with nothing changed in between; the model is told to fix the cause first.
- **outcome-classes** — every worker and helper answer is classified (success, policy/safety refusal, authorization required, misunderstood, needs information, technical limitation, tool failure, network/provider failure, incomplete, unclassified refusal) by where a refusal sits, whether the requested work is there and what failed — so a refusal is never counted as done work and a technical failure is never called a refusal. The main model is told who answered, what kind of answer it was and the stated reason, and decides; genuine safety/authorization refusals are respected (no rewording to get around them).
- **refusal-provenance** — the delegation audit records, from the real answers only: role, provider/model, outcome class, the stated reason (secrets redacted), time taken, and every worker's try.
- **worker-results** — a worker run returns a structured result (who, answer, outcome, every attempt) instead of an empty string; an empty answer is a technical failure, not a refusal. Each chat/task has its own worker conversation key (it was one shared "worker" key for every tab); builds have their own per app.
- **requirement-coverage** — every requirement the owner wrote must survive: requests the chat-memory summary dropped are kept word for word, and the main model's brief for workers is checked and completed (one extra lean call only if something is missing).

## Model collaboration and verification (1.24.0)
- **refusal-review** — when the middle model (worker) declines in chat: its exact answer, who said it and the stated reason are kept; a genuine safety or authorization refusal is final (never re-sent in other words, even if the helper disagrees); otherwise the helper gives an independent assessment and the boss decides — clarify and ask again, a permitted alternative, or stop. A helper that declines is recorded as declining and the boss decides without it. Bounded (2 rounds, 2 helper calls); every message attributed to the model that wrote it.
- **rule-integrity** — the exact instruction texts the main, middle and helper models receive at runtime are fingerprinted (SHA-256) in `app/src/main/assets/instruction-baseline.txt`. A unit test fails the build if any changed, disappeared or was replaced; the running app checks the same at start-up and on the test device.
- **rule-baseline (stronger)** — `.github/protected-baseline.txt` now fingerprints each protected rule's whole comment block, so an edited or swapped rule fails the build, not only a deleted one.
- **terminal-self-test** — Settings → Linux → "Test the terminal" checks the phone's real terminal: working folders carry over, chats stay separate, simultaneous commands don't mix output or exit codes, a missing folder is handled, and the retry limit works. The same test runs on the emulator.

## 1.25.0 — Orchestration upgrade
- orchestrator: Boss's no-AI decision layer — immutable task record + requirement ledger (R1…), diagnosis, first-choice approach and adaptive budget, evidence-based escalation (never the same approach twice; never around a genuine safety/authorization refusal), outcome statistics from verified results only, per-run log and resumable checkpoint.
- run-context: each reply's identity captured once at its start.
- evidence-check: a worker's "done" is checked against its task before the boss accepts it.
- project-graph: project-scoped map of files, classes, functions and dependents (library; not yet used by the chat flow).
- proot-fallback: PRoot's compatible mode is used only where the kernel rejects the fast mode.
- strict-tab-scope (1.25.1): every tab's work (each chat, each build, Arin Update, Security) runs tagged with its own id; helper sessions, harness sessions and the research cache are keyed by it, and untagged calls get a one-off session — nothing is shared between tabs.

## 1.26.0 — Project map connected
- project-map: one current map per project id; only changed files are re-read, deleted files drop out, each map records its source version.
- project-map-in-chat: when a chat has a project folder, the worker (and the helper, when asked) gets only the related files from the map; investigate-first tasks must cite mapped files as evidence; a plan with review gets one independent helper review.
- project-map-in-build: Arin-side only (the build worker still never sees the project): a fixed piece that removes a class/function other files use is rejected.
