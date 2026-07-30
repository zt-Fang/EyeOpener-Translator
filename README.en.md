# EyeOpener — Real-time Floating Subtitle Translator

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Languages](https://img.shields.io/badge/ASR-41%20languages-blue)](#language-support)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-green)](#build)
[![Architecture](https://img.shields.io/badge/Arch-MVVM%20%2B%20Clean-orange)](#architecture)

Android app for real-time speech translation: global floating subtitle + multi-engine ASR + multi-engine translation, offline-capable.

> 中文版 README：[README.md](README.md)

![Floating subtitle demo](docs/screenshots/overlay.png)

## Features

- 🎙️ **Global floating subtitle** — Real-time original + translated text over any app; draggable & resizable
- 🌐 **41 languages** — X-ASR (zh/en) + Nemotron 3.5 multilingual + Vosk fallback
- 🔄 **3 translation tiers** — Local (ML Kit offline) / Cloud (Papago/Baidu/DeepL/Azure/Google) / AI (LLM)
- 🤖 **AI assistant** — 7 LLM providers (OpenAI / Claude / DeepSeek / Qwen / MiniMax / MiMo / Gemini)
- 📱 **Responsive layout** — Phone / foldable / tablet / overlay
- 🎨 **Personalization** — Color, font size, opacity, display mode (source/translation/bilingual)
- 📝 **History** — Room local storage with favorites & export
- 🔒 **Privacy-first** — API keys AES/GCM encrypted; offline mode available

## Architecture

```
UI Layer (Jetpack Compose)
        ↓
ViewModel / SubtitleManager
        ↓
Domain Layer (Model + UseCase)      ← pure Kotlin, no Android deps
        ↓
Data Layer (Repository Impl)        ← DataStore / Room
        ↓
Engine Layer (ASR / VAD / Translation) ← Sherpa-ONNX / Vosk / ML Kit / LLM
```

**One-way dependency** — upper layers only call lower layers; reverse calls are forbidden.

## Project Structure

```
eye/
├── app/         # UI + Service + ViewModel (Compose)
├── domain/      # Pure Kotlin: Model + UseCase + interfaces
├── engine/      # ASR / VAD / Translation implementations
├── data/        # Room / DataStore / Repository implementations
├── docs/        # Docs and screenshots
└── scripts/     # Helpers (log collection, model push)
```

## Data Flow

```
Mic / AudioPlaybackCapture
    ↓ (16 kHz / mono / 16-bit PCM, 30 ms frames)
Silero VAD (UI state only; does not cut the stream)
    ↓
ASR Engine.feedAudio(ShortArray)
    ↓ (final result, triggered by silence gap)
Translation Engine (ML Kit / Cloud / LLM)
    ↓
Floating window (source + translation, scroll up keeping 10 lines)
```

## Language Support

**41 main languages** = Vosk 32 + Nemotron-only 9

| ASR Engine | Languages | Model Size | Notes |
|------------|-----------|------------|-------|
| Sherpa-ONNX X-ASR | zh / en (code-switch) | 161 MB | 960 ms streaming, CER ~9.59%, punctuated |
| Sherpa-ONNX Nemotron 3.5 | 26 langs | 685 MB | 320 ms streaming, per-stream language switch |
| Sherpa-ONNX BN Vosk | bn | 87 MB | Bengali Zipformer streaming |
| Vosk Small | 33 langs (incl. en-in variant) | 38–100 MB | 100–300 ms true streaming, lightweight fallback |

**ASR/Translation decoupling** — ASR is decided solely by source language; all translation engines share the same ASR routing:

| Source | ASR Engine | Fallback |
|--------|------------|----------|
| zh / en | X-ASR | Vosk |
| bn | BN Vosk | Vosk |
| Nemotron 26 langs | Nemotron 3.5 | Vosk (silent) |
| Others | Vosk | — |

**Nemotron-only 9** (require Nemotron 3.5 model): Danish, Norwegian (Bokmål), Bulgarian, Finnish, Croatian, Slovak, Hungarian, Romanian, Estonian.

### Translation Engines

| Engine | Backend | Notes |
|--------|---------|-------|
| LOCAL | ML Kit offline | Fastest; requires Google Play Services |
| CLOUD | Papago / Baidu / DeepL / Azure / Google | Broad coverage; requires API key |
| AI | 7 LLM providers | Context-aware polish + assistant |

Unsupported language pairs are silently skipped (no error popups).

### Model Sources

| Model | Source |
|-------|--------|
| Vosk ASR | alphacephei.com |
| X-ASR zh-en | ModelScope (bujidc) |
| Nemotron 3.5 | HuggingFace (csukuangfj2) |
| Sherpa-ONNX BN | GitHub Release (k2-fsa) |
| ML Kit translate | Google Play Services |

### Audio Parameters

- Sample rate: 16 kHz / mono / 16-bit PCM
- Frame size: 480 samples (30 ms)
- VAD: Silero VAD (32 ms / 512 samples window; bundled in assets)
- Input: Microphone / AudioPlaybackCapture (Android 10+)

## Floating Subtitle

- Drag: non-button area
- Resize: 24dp×24dp hot zone at bottom-right
- Initial size: width × 60%, height × 20%
- Minimum size: width × 25%, height × 20%
- Display modes: source only / translation only / bilingual
- Left sidebar: teal-green gradient (#4DD0E1 → #81C784)

## Settings

- **API**: Provider / Key / Base URL / Model / Connection test
- **Local models**: X-ASR / Nemotron 3.5 / BN Vosk / Vosk per-language — download/delete/progress
- **Personalization**: display mode, font size, opacity, accent color, dark mode
- **History**: view/favorite/export/clear
- **Audio source**: microphone / in-app audio

## Key Design Decisions

1. **ML Kit translation models managed by Google Play Services**, not by the app — skipped if unavailable
2. **API key encrypted at rest** — AES/GCM/NoPadding, 12-byte random IV, Base64(IV + ciphertext + tag)
3. **Launcher icon uses direct PNG reference** — avoids adaptive-icon XML falling back to default icon on some systems
4. **VAD does not cut the stream** — only updates UI state; audio continues to ASR
5. **ASR/Translation decoupled** — ASR routing depends only on source language
6. **Only `final` results trigger translation** — partial results are not translated, ensuring quality
7. **Silent handling of unsupported languages** — no error popups, just skip
8. **Translation versioning** — AtomicLong tracks versions; outdated results are discarded

## Build

```bash
./gradlew assembleDebug      # Debug build
./gradlew assembleRelease    # Release build
./gradlew :app:dependencies  # View dependency tree
```

**Requirements**:
- Android Studio Ladybug+
- JDK 17+
- Android SDK 36
- minSdk 24 (Android 7.0)
- NDK (for native library build)

## Native Libraries

| Library | Purpose | Source |
|---------|---------|--------|
| `eye_native` | AGC audio processing JNI | `app/src/main/cpp/` |
| sherpa-onnx-jni | Sherpa-ONNX JNI | prebuilt `jniLibs/` |
| onnxruntime | ONNX runtime | prebuilt `jniLibs/` |
| vosk-android | Vosk ASR | Maven |
| Silero VAD | Voice activity detection | `assets/silero_vad.onnx` |

## Acknowledgements

This project builds on these excellent open-source projects:

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — on-device real-time ASR (Apache-2.0)
- [Vosk](https://alphacephei.com/vosk/) — multilingual offline ASR (Apache-2.0)
- [Silero VAD](https://github.com/snakers4/silero-vad) — voice activity detection (MIT)
- [ML Kit](https://developers.google.com/ml-kit) — on-device translation
- [X-ASR](https://github.com/Gilgamesh-J/X-ASR) — zh/en code-switch ASR (Apache-2.0)
- [Nemotron 3.5 ASR](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) — multilingual ASR (OpenMDW-1.1)

## License

[Apache License 2.0](LICENSE)

```
Copyright 2026 zt-Fang (EyeOpener)
```

Third-party libraries and models follow their respective licenses.
