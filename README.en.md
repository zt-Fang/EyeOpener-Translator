# EyeOpener — Real-time Floating Subtitle Translator

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Languages](https://img.shields.io/badge/Language-41-blue)](#supported-languages)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-green)](#download)

Turn speech in any app into subtitles you can actually read.

EyeOpener is a real-time speech translation app for Android. The original speech and its translation appear as a floating subtitle layer on top of any app — videos, livestreams, meetings, online classes, games. Translation can run fully offline, through your own cloud provider, or via an LLM for context-aware polish. With the offline engines, it works with networking completely off.

> 中文版 README：[README.md](README.md)

![Floating subtitle demo](docs/screenshots/overlay.png)

## What you can do with it

**Watch videos and livestreams in other languages**
YouTube, foreign news, TED talks, unsubtitled shows — audio keeps playing, subtitles keep up.

**Attend meetings and online classes**
Foreign-language calls, lectures and courses, with the translation right next to what you're hearing.

**Play games**
Foreign-language dialogue and voice chat from other regions, without leaving the game.

**Listen to podcasts and audiobooks**
No picture needed — audio alone works just fine.

The floating subtitle layer doesn't belong to any particular player. It sits above the system: **whichever app you use, it works.**

## Features

### Floating subtitles

- Global overlay, shown on top of any app
- Draggable and resizable — position and size are up to you
- Three display modes: source only / translation only / bilingual
- Adjustable colour, font size and opacity

### Speech recognition

- 41 languages supported
- **Code-switching** between Chinese and English within a single sentence, punctuation included
- Works from the microphone, or captures in-app audio on Android 10+ so you don't have to play it out loud
- Recognition models download on demand — only the languages you actually use

### Translation

Three tiers, switchable at any time:

| Engine | Requires | Good for |
|---|---|---|
| **Local** | Nothing | Fastest response, works offline |
| **Cloud** | Your own API key | Broadest language coverage |
| **AI** | Your own API key | Best quality, understands context |

### AI assistant

A dedicated chat page built on the same AI pipeline — ask it to explain, rewrite or summarise.

### History

Stored locally, with favourites and export.

### Privacy

- **No account required**
- API keys are encrypted with AES/GCM and stay on your device
- No tracking, no telemetry
- With the local/offline engines, it can run without any network access at all

## Supported languages

41 languages in total, covered by four recognition engines:

| Recognition engine | Languages | Notes |
|---|---|---|
| X-ASR | Chinese / English | Tuned for code-switching, punctuated |
| Nemotron 3.5 | 26 languages | Multilingual long-form speech |
| BN Vosk | Bengali | Dedicated model |
| Vosk | 32 languages (incl. en-IN) | Lightweight offline, covers minor languages |

The recognition engine is chosen automatically from the source language — you don't have to pick one.

## Download

Two options, pick either:

| Platform | Link | Notes |
|----------|------|-------|
| GitHub Releases | <https://github.com/zt-Fang/EyeOpener-Translator/releases> | Source + APK, published first |
| Lanzou Cloud | <https://eyeopener.lanzoul.com/b01d72jymf> | Extraction code `7856`, faster in mainland China |

Requirements: Android 7.0 or newer, arm64-v8a and armeabi-v7a both supported.

## Getting started

1. Open the app and choose your source and target language
2. Download the matching recognition model when prompted (first time for each language)
3. Turn on the floating subtitle switch and grant the overlay and microphone permissions
4. Switch to any app — the subtitles follow along

## Roadmap

### In progress

- **Removing the Google dependency from offline translation** — so local translation also works where Google services are unavailable
- **More export formats** — SRT / VTT for archiving and further editing
- **Background failure alerts** — failures currently only surface while the app is in the foreground; notification feedback is planned

### Planned

- **Custom glossary** — industry terms, names and proper nouns translated your way
- **Translation feedback** — flag unsatisfying translations to keep improving the prompts

### Exploring

- Splitting the package per CPU architecture to reduce download size
- More recognition models for minor languages

> The roadmap shifts with feedback — tell us what you want in Issues.

## Build

```bash
./gradlew assembleDebug      # Debug build
./gradlew assembleRelease    # Release build
```

Requirements: Android Studio Ladybug+ / JDK 17+ / Android SDK 36.

## Contact

- Email: 874047656@qq.com
- GitHub Issues: <https://github.com/zt-Fang/EyeOpener-Translator/issues>

## Acknowledgements

This project builds on these excellent open-source projects:

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — on-device real-time ASR (Apache-2.0)
- [Vosk](https://alphacephei.com/vosk/) — multilingual offline ASR (Apache-2.0)
- [Silero VAD](https://github.com/snakers4/silero-vad) — voice activity detection (MIT)
- [X-ASR](https://github.com/Gilgamesh-J/X-ASR) — zh/en code-switch ASR (Apache-2.0)
- [Nemotron 3.5 ASR](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) — multilingual ASR (OpenMDW-1.1)

Third-party SDK:

- [ML Kit](https://developers.google.com/ml-kit) — on-device translation (proprietary; requires Google Play Services)

## License

[Apache License 2.0](LICENSE)

```
Copyright 2026 zt-Fang (EyeOpener)
```

Third-party libraries and models follow their respective licenses.
