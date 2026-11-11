# REA evidence for `app.apk`
Produced by REA 6.8.0 (github.com/morluto/rea) with its JADX provider. The APK was read, never run.

## Package
- package `com.aiapp`, version 0.33.0 (38), minSdk 26, targetSdk 35
- classes in APK: 22507, resources: 185
- permissions (8): INTERNET, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, POST_NOTIFICATIONS, RECORD_AUDIO, REQUEST_INSTALL_PACKAGES, WAKE_LOCK, com.aiapp.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION

## Components declared in the manifest (5)
- activity: `com.aiapp.MainActivity` (exported; actions: MAIN)
- provider: `androidx.core.content.FileProvider` (authorities: com.aiapp.files)
- service: `com.aiapp.builder.BuildService`
- provider: `androidx.startup.InitializationProvider` (authorities: com.aiapp.androidx-startup)
- receiver: `androidx.profileinstaller.ProfileInstallReceiver` (exported; actions: INSTALL_PROFILE, SKIP_FILE, SAVE_PROFILE, BENCHMARK_OPERATION)

## Signing
- v2: C=US, O=Android, CN=Android Debug (SHA-256 c20029dd049c5a2b…)

## Runtime
- Not a Flutter app (no Dart snapshot found).

## The app's own classes (102 top-level under `com.aiapp`; 22507 classes in the whole APK)
MainActivity, R, builder.BuildAgent, builder.BuildEvent, builder.BuildRequest, builder.BuildResult, builder.BuildRunner, builder.BuildService, builder.GitHubClient, builder.GitHubLogin, builder.OldSaved, builder.Project, builder.ProjectTemplate, builder.ReaEngine, builder.SavedProject, builder.TimelineItem, builder.ZipImport, data.ApiKeys, data.ApkInstaller, data.AttachmentStore, data.ChatStore, data.Conversation, data.Downloads, data.Log, data.StudioProjectFiles, llm.AnthropicProvider, llm.Attachment, llm.AttachmentContent, llm.ChatMessage, llm.ContextCompactor, llm.Effort, llm.FreeCloudProvider, llm.GeminiFiles, llm.GeminiProvider, llm.Http, llm.LlmProvider, llm.MediaItem, llm.MediaSearch, llm.ModelInfo, llm.ModelRanking, llm.ModelsKt, llm.OfflineCatalog, llm.OfflineModel, llm.OfflineProvider, llm.OpenAiProvider, llm.OpenModelsProvider, llm.ProviderException, llm.ProviderId, llm.Providers, llm.RetryKt, llm.Role, llm.WebTools, media.CaptionOverlay, media.MediaGen, media.VideoEditSpec, media.VideoEditor, media.VideoMetadata, media.ZoomKeyframe, reverse.ApkEdit, reverse.ApkReport, reverse.CraftAnythingEngine, reverse.EditPlan, reverse.PlannedEdit, reverse.ReaClient, reverse.ReaCloud, reverse.ReaConfig, reverse.ReaTool, reverse.RebuildResult, reverse.ReconstructWorkflow, reverse.ReverseAgent, reverse.ReverseCiRunner, reverse.ReverseEngine, reverse.ReverseReconstructRunner, reverse.SignatureInfo, reverse.SigningKey, reverse.SmaliEngine, reverse.SplitApk, reverse.TermList, ui.Block, ui.BuildScreenKt, ui.BuildViewModel, ui.ChatScreenKt, ui.ChatViewModel, ui.CodespaceScreenKt, ui.CodespaceSession, ui.CreateScreenKt, ui.CreateTabMode, ui.CreateViewModel, ui.GeminiLive, ui.HistoryScreenKt, ui.ImageLoader, ui.InstalledAppItem, ui.LogScreenKt, ui.MarkdownKt, ui.MediaViewsKt, ui.ReverseScreenKt, ui.ReverseViewModel, ui.SettingsScreenKt, ui.ThemeKt, ui.VoiceController, ui.VoiceScreenKt, ui.VoiceState

## Decompiled entry point: `com.aiapp.MainActivity.onCreate`
```java
    @Override // androidx.activity.ComponentActivity, androidx.core.app.ComponentActivity, android.app.Activity
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log log = Log.INSTANCE;
        Context applicationContext = getApplicationContext();
        Intrinsics.checkNotNullExpressionValue(applicationContext, "getApplicationContext(...)");
        log.init(applicationContext);
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() { // from class: com.aiapp.MainActivity$$ExternalSyntheticLambda0
            @Override // java.lang.Thread.UncaughtExceptionHandler
            public final void uncaughtException(Thread thread, Throwable th) {
                MainActivity.onCreate$lambda$0(prev, thread, th);
            }
        });
        ComponentActivityKt.setContent$default(this, null, ComposableSingletons$MainActivityKt.INSTANCE.getLambda$250324684$app_debug(), 1, null);
    }
```

## Members of 11 key classes
### `com.aiapp.MainActivity`
- methods (3): <init>(): void; onCreate(Bundle): void; onTrimMemory(int): void
### `com.aiapp.builder.BuildService`
- fields: ACTION_STOP: String, CHANNEL: String, CHANNEL_DONE: String, Companion: BuildService$Companion, DONE_ID: int, PROGRESS_ID: int, job: Job, scope: CoroutineScope, wakeLock: PowerManager$WakeLock
- methods (12): <init>(): void; createChannel(): void; doneNotification(String, BuildResult): Notification; friendly(Throwable): String; notify(int, Notification): void; onBind(Intent): IBinder; onDestroy(): void; onStartCommand(Intent, int, int): int; openAppIntent(): PendingIntent; progressNotification(String, Float): Notification; progressNotification$default(BuildService, String, Float, int, Object): Notification; startInForeground(Notification): void
### `com.aiapp.builder.GitHubClient`
- fields: Companion: GitHubClient$Companion, api: String, jsonType: MediaType, timestamp: Regex, token: String
- methods (20): <init>(String): void; blobFromFile(String, File, Function1<? super Long, Unit>, Continuation<? super String>): Object; blobFromFile$default(GitHubClient, String, File, Function1, Continuation, int, Object): Object; call(String, String, JsonElement, Continuation<? super JsonElement>): Object; call$default(GitHubClient, String, String, JsonElement, Continuation, int, Object): Object; callOnce(String, String, JsonElement, Continuation<? super JsonElement>): Object; callOnce$default(GitHubClient, String, String, JsonElement, Continuation, int, Object): Object; commit(GitHubClient$Repo, Map<String, byte[]>, String, Ma
… (cut)
### `com.aiapp.data.ApiKeys`
- fields: Companion: ApiKeys$Companion, activeKeyIndex: ConcurrentHashMap<ProviderId, Integer>, limitResetCounter: int, prefs: SharedPreferences
- methods (10): <init>(Context): void; allKeys(ProviderId): List<String>; get(ProviderId): String; getExtra(String): String; getRaw(ProviderId): String; resetLimitFlags(ProviderId): void; resetLimitFlags$default(ApiKeys, ProviderId, int, Object): void; rotateKey(ProviderId): String; set(ProviderId, String): void; setExtra(String, String): void
### `com.aiapp.reverse.ReaClient`
- fields: builtInAndroidTools: List<ReaTool>, config: ReaConfig
- methods (9): <init>(): void; <init>(ReaConfig): void; <init>(ReaConfig, int, DefaultConstructorMarker): void; callTool(String, JsonObject, Continuation<? super String>): Object; connect(Continuation<? super String>): Object; getBuiltInAndroidTools(): List<ReaTool>; listTools(Continuation<? super List<ReaTool>>): Object; runOnDeviceTool(String, File, String, Continuation<? super String>): Object; runOnDeviceTool$default(ReaClient, String, File, String, Continuation, int, Object): Object
### `com.aiapp.ui.BuildScreenKt`
- fields: resumeWords: Regex
- methods (15): ApkDownload(String, Composer, int): void; ApkDownload$install(Context, MutableState<String>, File): void; BuildComposer(ChatViewModel, BuildViewModel, Function0<Unit>, Composer, int): void; BuildComposer$ready(ChatViewModel): String; BuildScreen(ChatViewModel, BuildViewModel, Function0<Unit>, Function0<Unit>, Composer, int): void; CodeViewer(String, String, Function0<Unit>, Composer, int): void; EmptyState(ChatViewModel, Modifier, Composer, int): void; EventRow(BuildEvent, boolean, Composer, int): void; FileList(List<String>, Function1<? super String, Unit>, Function0<Unit>, Composer, int): void; ProjectList(Buil
… (cut)
### `com.aiapp.ui.BuildViewModel`
- fields: app: Application
- methods (10): <init>(Application): void; deleteProject(String): void; getProject(): Project; getRunning(): boolean; getTimeline(): List<TimelineItem>; newProject(): void; openProject(String): boolean; projects(): List<SavedProject>; start(BuildRequest): void; stop(): void
### `com.aiapp.ui.ChatScreenKt`
- methods (14): AttachmentChip(Attachment, Function0<Unit>, Composer, int): void; ChatScreen(ChatViewModel, Function0<Unit>, Function0<Unit>, Function0<Unit>, Function0<Unit>, Function0<Unit>, Function0<Unit>, Function0<Unit>, Composer, int): void; ChatScreen$exitVoice(VoiceController, MutableState<Boolean>, MutableState<GeminiLive>): void; ChatScreen$newLive(ChatViewModel): GeminiLive; ChatScreen$startListening(VoiceController, MutableState<String>, MutableState<Boolean>, ChatViewModel): void; Composer(ChatViewModel, String, Function1<? super String, Unit>, Function0<Unit>, Function0<Unit>, Function0<Unit>, Composer, int): void
… (cut)
### `com.aiapp.ui.ChatViewModel`
- fields: Companion: ChatViewModel$Companion, MAX_WEB_STEPS: int, SYSTEM_PROMPT: String, VOICE_PROMPT: String, attachProgress: SnapshotStateMap<String, Float>, attaching$delegate: MutableState, buildCommand: Regex, buildWords: Regex, conversationId: String, downloadErrors: SnapshotStateMap<String, String>, downloadJobs: Map<String, Job>, downloads: SnapshotStateMap<String, Float>, effort
… (cut)
- methods (110): <init>(Application): void; addOfflineModel(String): String; addVoiceTurn(String, String): void; applyReverseBuildCommand(ApkReport, File, String, Continuation<? super Unit>): Object; attach(List<? extends Uri>): void; buildZip(Attachment): void; cancelDownload(OfflineModel): void; chooseLiveVoice(String): void; customRouterKey(): String; customRouterModel(): String; customRouterUrl(): String; deleteChat(String): void; deleteOffline(OfflineModel): void; deviceRamGb(): int; downloadOffline(OfflineModel): void; finishStream(): void; flushStream(): void; friendly(Throwable): String; geminiKey(): String; getAttachProg
… (cut)
### `com.aiapp.ui.CodespaceScreenKt`
- methods (2): CodespaceScreen(Function0<Unit>, Composer, int): void; CodespaceScreen$load(MutableState<String>, String): void
### `com.aiapp.ui.CreateScreenKt`
- methods (1): CreateScreen(CreateViewModel, Function0<Unit>, Function0<Unit>, Composer, int): void

## Limits of this evidence
- Server-side logic is not inside an APK and is not in this evidence.
- Static decompiler observations do not establish runtime behavior; the APK is never executed.
- Method signatures use provider display types; DEX prototypes come from frozen JADX metadata. Display names may be aliases, and overload indices are local to this engine and artifact.
- Class inventories use parsed metadata before code generation, including synthetic members that may be omitted from decompiled source. Source positions remain unknown.
- Incoming reference totals count JADX using nodes. Direct invoke entries within a using method report raw DEX code-unit offsets and method indexes, but coverage is limited to those nodes and directly matched invokes; it does not establish ev…

(Collected in 243 s.)

## Network, servers and secrets (grep of the decompiled code)
These are the servers and requests the original app has in its code. The new app should use the SAME base URLs and endpoints and the SAME request shape. Note: a server may still reject a third-party client that cannot pass the official app's signature check, certificate pinning or per-user login.

### Backend hosts (34)
- `excalidraw.com` (8×) — in the app's own code
- `generativelanguage.googleapis.com` (8×) — in the app's own code
- `en.wikipedia.org` (5×) — in the app's own code
- `api.openai.com` (5×) — in the app's own code
- `duckduckgo.com` (5×) — in the app's own code
- `commons.wikimedia.org` (3×) — in the app's own code
- `www.youtube-nocookie.com` (2×) — in the app's own code
- `i.ytimg.com` (2×) — in the app's own code
- `developer.android.com` (2×) — in the app's own code
- `dl.google.com` (2×) — in the app's own code
- `raw.githubusercontent.com` (2×) — in the app's own code
- `url` (2×) — in the app's own code
- `api.llm7.io` (2×) — in the app's own code
- `opencode.ai` (2×) — in the app's own code
- `text.pollinations.ai` (2×) — in the app's own code
- `huggingface.co` (2×) — in the app's own code
- `www.youtube.com` (2×) — in the app's own code
- `api.radox.ai` (1×) — in the app's own code
- `chatgpt.com` (1×) — in the app's own code
- `api.bazaarlink.ai` (1×) — in the app's own code
- `ai-gateway.kilo.ai` (1×) — in the app's own code
- `api.kilo.ai` (1×) — in the app's own code
- `oai.endpoints.kepler.ai.cloud.ovh.net` (1×) — in the app's own code
- `gen.pollinations.ai` (1×) — in the app's own code
- `gateway.vlm.run` (1×) — in the app's own code
- `api.zydit.in` (1×) — in the app's own code
- `kilocode.ai` (1×) — in the app's own code
- `pollinations.ai` (1×) — in the app's own code
- `openrouter.ai` (1×) — in the app's own code
- `api.anthropic.com` (1×) — in the app's own code
- `full.url` (1×) — in the app's own code
- `direct.file.url` (1×) — in the app's own code
- `html.duckduckgo.com` (1×) — in the app's own code
- `www.magelang.com` (1×)

### Full URLs seen in code (42)
- https://generativelanguage.googleapis.com/v1beta
- https://en.wikipedia.org/wiki/
- https://commons.wikimedia.org/w/api.php?action=query&format=json&generator=search&gsrnamespace=6&gsrlimit=
- https://generativelanguage.googleapis.com/v1beta/models?pageSize=500
- https://i.ytimg.com/vi/
- https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=5&srsearch=
- https://api.openai.com/v1
- https://duckduckgo.com/i.js?l=wt-wt&o=json&q=
- https://generativelanguage.googleapis.com/v1beta/models/
- https://www.youtube-nocookie.com/embed/
- https://api.radox.ai/v1
- https://chatgpt.com/codex
- https://developer.android.com/build/shrink-code
- https://dl.google.com/android/maven2/com/android/tools/build/gradle/maven-metadata.xml
- https://developer.android.com/build/releases/about-agp
- https://raw.githubusercontent.com/gradle/gradle/
- https://dl.google.com/android/maven2
- https://raw.githubusercontent.com/gradle/gradle/$TAG
- https://api.bazaarlink.ai/v1/chat/completions
- https://ai-gateway.kilo.ai/api/v1/chat/completions
- https://api.kilo.ai/api/gateway/chat/completions
- https://api.llm7.io/v1/chat/completions
- https://opencode.ai/inference/openai/v1/chat/completions
- https://opencode.ai/zen/v1/chat/completions
- https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions
- https://gen.pollinations.ai/v1/chat/completions
- https://text.pollinations.ai/openai
- https://gateway.vlm.run/v1/openai/chat/completions
- https://api.zydit.in/v3/chat/completions
- https://api.llm7.io/v1/models
- https://text.pollinations.ai/models
- https://huggingface.co/api/models/
- https://generativelanguage.googleapis.com/upload/v1beta/files
- https://openrouter.ai/api/v1
- https://api.anthropic.com/v1
- https://html.duckduckgo.com/html/?q=
- https://duckduckgo.com/?q=
- https://www.youtube.com/results?search_query=
- https://www.youtube.com/watch?v=
- https://api.openai.com/v1/models
- https://api.openai.com/v1/images/generations
- https://api.openai.com/v1/images/edits

### Base-URL / endpoint constants (23)
- `CODEX = https://chatgpt.com/codex`
- `raw = https://raw.githubusercontent.com/gradle/gradle/`
- `BAZAAR_URL = https://api.bazaarlink.ai/v1/chat/completions`
- `KILO_MIRROR_URL = https://ai-gateway.kilo.ai/api/v1/chat/completions`
- `KILO_URL = https://api.kilo.ai/api/gateway/chat/completions`
- `LLM7_URL = https://api.llm7.io/v1/chat/completions`
- `OPENCODE_INFERENCE_URL = https://opencode.ai/inference/openai/v1/chat/completions`
- `OPENCODE_ZEN_URL = https://opencode.ai/zen/v1/chat/completions`
- `OVH_URL = https://oai.endpoints.kepler.ai.cloud.ovh.net/v1/chat/completions`
- `POLLINATIONS_GEN_URL = https://gen.pollinations.ai/v1/chat/completions`
- `POLLINATIONS_URL = https://text.pollinations.ai/openai`
- `VLM_RUN_URL = https://gateway.vlm.run/v1/openai/chat/completions`
- `ZYDIT_URL = https://api.zydit.in/v3/chat/completions`
- `str5 = https://huggingface.co/api/models/`
- `url = https://huggingface.co/`
- `BASE = https://generativelanguage.googleapis.com/v1beta`
- `UPLOAD = https://generativelanguage.googleapis.com/upload/v1beta/files`
- `base = https://openrouter.ai/api/v1`
- `base = https://generativelanguage.googleapis.com/v1beta`
- `base = https://api.anthropic.com/v1`
- `base = https://api.openai.com/v1`
- `GEMINI = https://generativelanguage.googleapis.com/v1beta`
- `OPENAI = https://api.openai.com/v1`

### Endpoint paths in the app's code (2)
`/repos/`, `/src/main/res/`

### Request headers and auth scheme (21)
`Bearer `×10, `x-goog-api-key`×6, `openai-fast`×5, `openai-large`×3, `X-GitHub-Api-Version`×2, `openai-reasoning`×2, `x-opencode-session`×2, `x-session-id`×2, `X-Goog-Upload-Command`×2, `Bearer public`×1, `Bearer unused`×1, `Bearer vlmrun`×1, `X-Title`×1, `X-Goog-Upload-Protocol`×1, `X-Goog-Upload-Header-Content-Length`×1, `X-Goog-Upload-Header-Content-Type`×1, `x-goog-upload-url`×1, `X-Goog-Upload-Offset`×1, `X-OpenRouter-Title`×1, `x-api-key`×1, `anthropic-version`×1

### Candidate baked-in keys (1) — masked; the real value is in the APK
- `DEFAULT_CLIENT_ID` = Ov23li…trSN
A key found here is in the APK and can be read from it; a key fetched at runtime or hidden in native code is not here and usually cannot be recovered.

(Scanned 4189 decompiled files.)
