# AI App

Android AI assistant (Kotlin + Jetpack Compose). Chat with Claude, Gemini or ChatGPT using your own API keys, with a model picker and Low/Medium/High/Max effort levels.

## Install

Every push to `main` builds a debug APK in GitHub Actions and publishes it as the latest release:
**Releases → latest → `AIApp-N.apk`**. Each build installs over the previous one.

## Setup in the app

Settings → paste your API keys (stored encrypted on the phone, never in this repo).

**Custom API:** connect any OpenAI-compatible site. In Settings → Custom API, tap a preset
(Token Harbor, OpenRouter, DeepSeek, Groq, Together, Mistral, xAI, Moonshot, Fireworks, Cerebras, NVIDIA)
or paste a base URL such as `https://example.com/v1`, then save that site's key. Each site keeps its own
key and model. Pick "Custom API" in the model picker to chat with it.

## Status

v0.1: streaming chat, 3 providers, model picker, effort levels.
Next: conversation history, file upload, voice with interruptions, image/video generation, app-building agent.
