# EyeOpener - 实时悬浮字幕翻译

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Languages](https://img.shields.io/badge/Language-41-blue)](#支持的语言)
[![Platform](https://img.shields.io/badge/Android-7.0%2B-green)](#下载安装)

把任意 App 里的外语语音，变成看得懂的字幕。

EyeOpener 是一个 Android 实时语音翻译应用。识别与翻译的结果会以悬浮字幕的形式显示在任何应用之上——视频、直播、会议、网课、游戏都可以。翻译可选本地离线、自备的云端通道，或大模型上下文润色；配合离线引擎还能完全断网使用。

> README in English: [README.en.md](README.en.md)

![Floating subtitle demo](docs/screenshots/overlay.png)

## 使用场景

**看外语视频和直播**
YouTube、外语新闻、TED、生肉剧集，原声照常播放，字幕实时跟上。

**开会与上网课**
外语会议、讲座、线上课程，边听边看译文，不用事后翻记录。

**玩游戏**
外语配音的剧情、海外服语音沟通，不用切出游戏就能看懂。

**听播客与有声书**
只有音频没有画面也没关系，照样能转成字幕。

悬浮字幕不属于某一个播放器，它覆盖在系统层：**你用哪个 App 都行**。

## 功能特性

### 悬浮字幕

- 全局覆盖，显示在任意应用之上
- 可拖拽、可缩放，位置与大小随你调整
- 显示模式三选一：只看原文 / 只看译文 / 双语对照
- 颜色、字号、透明度可调

### 语音识别

- 支持 41 种语言
- **中英混说**可识别——一句话里中英夹杂也能处理，且自带标点
- 既能用麦克风收音，也能捕获设备内部音频（Android 10+），不外放也能翻译
- 识别模型按需下载，用到哪种语言才下哪一个

### 翻译

三档引擎随时切换：

| 引擎 | 依赖 | 适合 |
|---|---|---|
| **本地离线** | 无 | 响应最快，不联网也能跑 |
| **云端通道** | 自备 API Key | 语种覆盖广 |
| **AI 大模型** | 自备 API Key | 质量最好，能理解上下文 |

### AI 助手

内置独立对话页，走同一条 AI 链路，可以让它解释、改写、总结。

### 历史记录

本地保存，支持收藏与导出。

### 隐私

- **无需注册账号**
- API Key 使用 AES/GCM 加密，只存在你自己的设备上
- 无埋点、无统计上报
- 配合本地/离线引擎，可以全程不联网

## 支持的语言

共 41 种语言，由四套识别引擎覆盖：

| 识别引擎 | 覆盖语言 | 说明 |
|---|---|---|
| X-ASR | 中文 / 英文 | 中英混说优化，自带标点 |
| Nemotron 3.5 | 26 种 | 多语种长句场景 |
| BN Vosk | 孟加拉语 | 专项优化 |
| Vosk | 32 种（含英印变体） | 轻量离线，覆盖小语种 |

识别引擎由源语言自动决定，不需要手动挑选。

## 下载安装

提供两种方式，任选其一：

| 平台 | 链接 | 说明 |
|------|------|------|
| GitHub Releases | <https://github.com/zt-Fang/EyeOpener-Translator/releases> | 源码 + APK，第一时间更新 |
| 蓝奏云 | <https://eyeopener.lanzoul.com/b01d72jymf> | 提取码 `7856`，国内访问更快 |

系统要求：Android 7.0 及以上，支持 arm64-v8a 与 armeabi-v7a。

## 快速上手

1. 打开应用，选择源语言与目标语言
2. 首次使用某个语言时，按提示下载对应的识别模型
3. 打开「悬浮字幕」开关，授予悬浮窗与录音权限
4. 切到任意 App，字幕就会跟着走

## 路线图

### 正在做

- **本地翻译摆脱 Google 依赖**：让离线翻译在无法使用 Google 服务的环境下也能正常工作
- **字幕导出格式扩展**：支持导出 SRT / VTT，方便存档与二次加工
- **后台异常提示**：悬浮字幕在后台失败时目前只有前台可见提示，计划补齐通知栏反馈

### 计划中

- **自定义术语表**：行业词汇、人名、专有名词按自己指定的译法走
- **翻译质量反馈**：对不满意的译文直接标记，用于持续优化提示词

### 探索中

- 安装包按 CPU 架构拆分，降低下载体积
- 更多小语种识别模型

> 路线图会随反馈调整，欢迎在 Issues 里提出你想要的方向。

## 构建

```bash
./gradlew assembleDebug      # Debug 构建
./gradlew assembleRelease    # Release 构建
```

环境要求：Android Studio Ladybug+ / JDK 17+ / Android SDK 36。

## 联系方式

- 邮箱：t874047656@gmail.com
- GitHub Issues：<https://github.com/zt-Fang/EyeOpener-Translator/issues>

## 致谢

本项目基于以下优秀的开源项目构建：

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 端侧实时语音识别（Apache-2.0）
- [Vosk](https://alphacephei.com/vosk/) — 多语种离线语音识别（Apache-2.0）
- [Silero VAD](https://github.com/snakers4/silero-vad) — 语音活动检测（MIT）
- [X-ASR](https://github.com/Gilgamesh-J/X-ASR) — 中英混说 ASR（Apache-2.0）
- [Nemotron 3.5 ASR](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b) — 多语种 ASR（OpenMDW-1.1）

第三方 SDK：

- [ML Kit](https://developers.google.com/ml-kit) — Google 端侧机器翻译（闭源，需 Google Play Services）

## 开源协议

[Apache License 2.0](LICENSE)

```
Copyright 2026 zt-Fang (EyeOpener)
```

第三方库与模型遵循其各自协议。
