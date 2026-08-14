# EyeOpener — Real-time Floating Subtitle Translator

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Languages](https://img.shields.io/badge/ASR-41%20languages-blue)](#language-support)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-green)](#build)

Android app for real-time speech translation: global floating subtitle + multi-engine ASR + multi-engine translation, offline-capable.

> 中文版 README：[README.md](README.md)

![Floating subtitle demo](docs/screenshots/overlay.png)

## Features

- 🎙️ **Global floating subtitle** — Real-time original + translated text over any app; draggable & resizable
- 🌐 **41 languages** — X-ASR (zh/en) + Nemotron 3.5 multilingual + Vosk minor languages
- 🔄 **3 translation tiers** — Local (ML Kit offline) / Cloud (Papago/Baidu/DeepL/Azure/Google) / AI (LLM)
- 📱 **Responsive layout** — Phone / foldable / tablet / overlay
- 🎨 **Personalization** — Color, font size, opacity, display mode (source/translation/bilingual)
- 📝 **History** — Room local storage with favorites & export
- 🔒 **Privacy-first** — API keys AES/GCM encrypted; offline mode available

## Download

Two options, pick either:

| Platform | Link | Notes |
|----------|------|-------|
| GitHub Releases | <https://github.com/zt-Fang/EyeOpener-Translator/releases> | Source + APK, published first |
| Lanzou Cloud | <https://eyeopener.lanzoul.com/b01d72jymf> | Extraction code `7856`, faster in mainland China |


## Language Support

| ASR Engine | Languages | Model Size | Notes |
|------------|-----------|------------|-------|
| Sherpa-ONNX X-ASR | zh / en (code-switch) | 161 MB | 960 ms streaming, CER ~9.59%, punctuated |
| Sherpa-ONNX Nemotron 3.5 | 26 langs | 685 MB | 320 ms streaming, per-stream language switch |
| Sherpa-ONNX BN Vosk | bn | 87 MB | Bengali Zipformer streaming |
| Vosk Small | 33 langs (incl. en-in variant) | 38–100 MB | 100–300 ms true streaming, lightweight offline |

**ASR/Translation decoupling** — ASR is decided **solely by source language, with no fallback** — if the model is not downloaded, the app prompts you to download it instead of switching engines automatically. All translation engines share the same ASR routing:

| Source | ASR Engine |
|--------|------------|
| zh / en | X-ASR |
| bn | BN Vosk |
| Nemotron 26 langs | Nemotron 3.5 |
| Others | Vosk |

### Translation Engines

| Engine | Backend | Notes |
|--------|---------|-------|
| LOCAL | ML Kit offline | Fastest; requires Google Play Services |
| CLOUD | Papago / Baidu / DeepL / Azure / Google | Broad coverage; requires API key |
| AI | LLM providers | Context-aware polish + assistant |

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
