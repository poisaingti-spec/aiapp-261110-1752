# Arin

An AI assistant for Android (Kotlin + Jetpack Compose) with its own design.

## Install

Every push to `main` builds an APK in GitHub Actions and publishes it as the latest release:
**Releases → latest → `Arin-N.apk`**. Each build installs over the previous one.

## What it does (version 0.3)

- **Chat:** replies stream in, with Stop, Copy, Retry, editing a sent message, and the model's reasoning when it shares it
- **Models:** free models by default with no key; optional Claude, Gemini and ChatGPT keys in Settings; model menu at the top
- **Custom API:** your own base URL, key and model (Settings → Custom API). Presets for Token Harbor, OpenRouter, DeepSeek, Groq, Together, Mistral, xAI, Moonshot, Fireworks, Cerebras and NVIDIA, or paste any address. Speaks the OpenAI format or the Claude (Anthropic) format; each address keeps its own key and model
- **Files of any type and size:** photos, PDFs, Word, Excel, PowerPoint, OpenDocument, EPUB, RTF, ZIP archives and any text or code file. Big files are kept on the phone and the parts that best match your question are sent when a file is larger than the model can take. Audio, video and other files are attached by name, type and size. Photos need a key (Claude, Gemini, ChatGPT or Custom API); free models get PDF text
- **Pictures and videos in chat:** ask to see something and Arin finds images or videos on the web and shows them in the reply; images the model links and YouTube or .mp4 links play right in the chat
- **Full internet access:** the model can read any page (`<fetch>`), download any file (`<download>`, saved into Linux or Download/Arin), search, and Build mode downloads files from any site by default
- **Arin updates itself:** ask for a feature in chat (or Settings → GitHub → Update Arin). Arin reads its own source from GitHub (this repo, `arin` branch), changes it, builds a new version with its workflow, fixes build errors and offers to install the update
- **Build mode (apps to APKs):** describe an app, or upload an Android Studio project (.zip), and Arin writes the code (Kotlin, Jetpack Compose, Material 3), puts it in a new repository on your GitHub, compiles it with GitHub Actions and feeds build errors back to the model until the APK builds; then install it from Arin. Start it from the menu (Build an app) or just ask in chat. Sign in with GitHub in Settings. Generated projects use AGP 9.5.0, Kotlin 2.4.20, Compose BOM 2026.08.00, Gradle 9.9.0, compileSdk 37 and JDK 21 (tested on GitHub Actions in November 2026)
- **Developer tools:** Settings → Linux → Install Java, Kotlin, Gradle adds OpenJDK 21, kotlinc 2.4.20 and Gradle 9.9.0 to the built-in Linux for compiling and running Java/Kotlin code on the phone
- **Steering:** send messages while Arin is working; they're added at its next step
- **Linux (built in):** Settings → Linux → Install Linux puts Ubuntu 24.04 inside Arin (PRoot, no root, no other apps) with Node.js 24, npm, git and Python. Commands and MCP servers run there; attached files are copied to `/root/files`. "Install Linux + REA" also installs REA and connects it. Termux remains an alternative
- **Terminal (Termux):** the model can run shell commands in Termux and read their output, so it can install and run tools (Node, npm, Python, git) and check that they work. Each command needs your OK unless you turn that off. Settings → Terminal has the one-time setup and a connection test
- **Tools (MCP):** connect MCP servers, local (in Termux, behind Arin's bridge `app/src/main/assets/mcp-bridge.mjs`) or remote (URL and token). The model finds and calls their tools, as in Claude Code
- **REA (Reverse Engineer Anything):** Settings → Tools → "Set up REA" installs REA in Termux, starts it and connects it. Attached files are also saved to Download/Arin so REA and terminal tools can open them
- **Projects:** chats grouped with shared instructions and files
- **Artifacts:** web pages, SVG images, documents and long code open in a viewer with a live preview, Copy and Share
- **Web search:** Arin searches when it needs current facts and lists its sources (switch in the + menu and Settings)
- **Memory:** lasting facts about you, saved on the phone, shown and editable in Settings
- **Voice mode:** tap the mic to talk; Arin answers aloud and you can tap to interrupt
- **Background replies:** a reply keeps going if you leave the app, with a notification when it's ready
- **Home-screen widget:** "Ask Arin" and a mic button
- **Share to Arin** from other apps; chats saved on the phone with search, rename, move and delete; light and dark themes

## Code layout

```
com.arin
├── ArinApp.kt        App-wide scope for replies, notification channels
├── MainActivity.kt   Starts the app, receives shares, widget and notification taps
├── app/              AppShell (screens), Launch (outside entry points), ReplyService
├── chat/             Chat screen, messages, view model
├── chatlist/         Side panel with projects and saved chats
├── project/          Projects list and project screen
├── artifact/         Finding artifacts in replies, and the viewer
├── voice/            Voice mode screen and speech controller
├── widget/           Home-screen widget
├── settings/         Settings screen
├── design/           Theme, Arin logo, Markdown, photo thumbnails
├── llm/              Providers, streaming, prompt and tools, web search
└── data/             Chats, projects, memory, settings, keys, attachments
```

## Third-party components

- PRoot for Android from [green-green-avk/proot](https://github.com/green-green-avk/proot) (GPL-2.0; source at that link), prebuilt by [green-green-avk/build-proot-android](https://github.com/green-green-avk/build-proot-android) (MIT). Shipped as `app/src/main/jniLibs/*/libproot*.so`; licences in `app/src/main/assets/`.
- Ubuntu base image and Node.js are downloaded on the phone from cdimage.ubuntu.com and nodejs.org when you tap Install Linux.

Arin targets Android API 28 (like Termux) because Android doesn't let apps targeting newer versions run programs from their own storage, which the built-in Linux needs.
