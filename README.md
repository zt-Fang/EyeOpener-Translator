# EyeOpener - 实时字幕翻译助手

[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

Android 实时语音翻译应用，支持悬浮字幕、多引擎翻译（ML Kit / LLM）、多语种 ASR（Vosk / Sherpa-ONNX）。

## 功能特性

- 🎙️ **实时悬浮字幕**：全局悬浮窗，任意应用语音实时转写+翻译
- 🔄 **三档翻译引擎**：本地翻译（ML Kit 离线）/ 云端翻译（Papago/百度/DeepL/Azure）/ AI 翻译（LLM+助手）
- 🌐 **33 种语言识别**：Vosk 多语种 + Sherpa-ONNX 中英文高精度
- 📱 **响应式布局**：适配手机/折叠屏/平板/悬浮窗
- 🎨 **个性化定制**：字幕颜色、字号、透明度、显示模式
- 🤖 **AI 助手**：大模型聊天对话，支持语音输入
- 📝 **历史记录**：本地保存翻译记录，支持收藏/导出
- 🔒 **隐私优先**：离线模式可用，API Key 本地加密存储

## 项目结构

```
eye/
├── app/                          # 主应用模块（UI + Service + ViewModel）
│   ├── src/main/
│   │   ├── cpp/                  # C/C++ 原生代码
│   │   │   ├── audio/            # 音频处理（AGC 自动增益控制）
│   │   │   │   ├── audio_processor.c         # AGC 算法实现
│   │   │   │   ├── audio_processor.h         # 头文件
│   │   │   │   └── audio_processor_jni.cpp   # JNI 桥接层
│   │   │   ├── vad/              # WebRTC VAD 语音活动检测
│   │   │   │   ├── webrtc_vad.c            # WebRTC VAD C 实现
│   │   │   │   ├── webrtc_vad.h            # 头文件
│   │   │   │   └── vad_jni.cpp             # JNI 桥接层
│   │   │   ├── CMakeLists.txt    # CMake 构建脚本
│   │   │   └── native-lib.cpp    # 占位（库入口）
│   │   │
│   │   ├── java/io/github/ztfang/eye/
│   │   │   └── MainActivity.kt   # 主 Activity（含字幕屏/助手屏/设置屏 Composable）
│   │   │
│   │   ├── kotlin/io/github/ztfang/eye/
│   │   │   ├── EyeApplication.kt         # Application 入口（Hilt 初始化）
│   │   │   ├── FloatingSubtitleService.kt # 悬浮字幕前台 Service
│   │   │   ├── di/
│   │   │   │   └── AppModule.kt          # Hilt 依赖注入模块
│   │   │   ├── ui/
│   │   │   │   ├── components/           # 通用 UI 组件
│   │   │   │   │   ├── AssistantTopBar.kt     # 助手页顶部栏
│   │   │   │   │   ├── ChatBubble.kt          # 聊天气泡
│   │   │   │   │   ├── EngineCard.kt          # 翻译引擎卡片（本地/云端/AI）
│   │   │   │   │   ├── GlassCard.kt           # 玻璃拟态卡片容器
│   │   │   │   │   ├── GradientBackground.kt  # 渐变背景
│   │   │   │   │   ├── LanguageSwitcher.kt    # 语言切换器（源/目标+交换）
│   │   │   │   │   ├── MessageInputBar.kt     # 消息输入栏（含麦克风）
│   │   │   │   │   ├── OverlayToggleCard.kt   # 悬浮字幕开关大卡片
│   │   │   │   │   ├── SettingsCard.kt        # 设置项卡片容器
│   │   │   │   │   ├── SettingsRow.kt         # 设置行（图标+文字+箭头）
│   │   │   │   │   ├── SettingsTopBar.kt      # 设置页顶部栏
│   │   │   │   │   └── TopAppBar.kt           # 通用顶部栏
│   │   │   │   ├── screen/               # 单文件页面（历史记录）
│   │   │   │   │   └── HistoryScreen.kt       # 历史记录页
│   │   │   │   ├── screens/              # 多文件页面集合
│   │   │   │   │   ├── ApiSettingsScreen.kt   # API 设置（Provider/Key/URL/模型）
│   │   │   │   │   ├── LocalModelsScreen.kt   # 本地模型管理（下载/删除）
│   │   │   │   │   ├── OnboardingScreen.kt    # 首次使用引导页
│   │   │   │   │   └── PersonalizationScreen.kt # 个性化设置
│   │   │   │   └── theme/                # 主题 & Design Token
│   │   │   │       ├── Dimens.kt              # 统一尺寸常量
│   │   │   │       └── EyeTheme.kt            # Material 3 主题定义
│   │   │   ├── util/
│   │   │   │   └── PermissionHelper.kt   # 权限检查工具
│   │   │   └── viewmodel/
│   │   │       ├── SubtitleManager.kt    # 悬浮字幕核心管理器（ASR+翻译+状态）
│   │   │       ├── SettingsViewModel.kt  # 设置 ViewModel
│   │   │       └── AssistantViewModel.kt # 助手对话 ViewModel
│   │   │
│   │   ├── jniLibs/               # 预编译原生库
│   │   │   ├── arm64-v8a/
│   │   │   │   ├── libonnxruntime.so       # ONNX Runtime
│   │   │   │   └── libsherpa-onnx-jni.so   # Sherpa-ONNX JNI
│   │   │   └── armeabi-v7a/
│   │   │
│   │   ├── res/                    # 资源文件
│   │   │   ├── values/
│   │   │   │   ├── strings.xml          # 字符串资源
│   │   │   │   └── themes.xml           # 主题配置
│   │   │   ├── mipmap-*/ic_launcher.webp # 桌面图标（多密度）
│   │   │   └── xml/
│   │   │       ├── backup_rules.xml     # 备份规则
│   │   │       └── data_extraction_rules.xml # 数据提取规则
│   │   │
│   │   └── AndroidManifest.xml      # 清单文件
│   │
│   └── build.gradle.kts            # App 模块构建配置
│
├── domain/                       # 领域层（纯 Kotlin，无 Android 依赖）
│   └── src/main/kotlin/io/github/ztfang/eye/domain/
│       ├── model/                # 领域数据模型
│       │   ├── AudioData.kt           # 音频数据包装（PCM ShortArray）
│       │   ├── DisplayMode.kt         # 字幕显示模式（原文/译文/双语）
│       │   ├── HistoryRecord.kt       # 历史记录模型
│       │   ├── Language.kt            # 通用语言定义
│       │   ├── ModelCatalog.kt        # 模型目录配置
│       │   ├── ModelFileSpec.kt       # 模型文件规格
│       │   ├── ModelState.kt          # 模型下载状态
│       │   ├── SherpaOnnxModel.kt     # Sherpa-ONNX 模型配置
│       │   ├── SubtitleState.kt       # 字幕状态（源/目标语言/模式）
│       │   ├── SubtitleType.kt        # 字幕类型（原文/译文）
│       │   ├── SubtitleLine.kt        # 单条字幕行
│       │   ├── TranscriptionResult.kt # ASR 识别结果
│       │   ├── TranslationEngine.kt   # 翻译引擎枚举（LOCAL/CLOUD/AI）+ 云端 Provider
│       │   ├── TranslationResult.kt   # 翻译结果
│       │   └── VoskLanguage.kt        # Vosk 语言枚举（33 种）
│       ├── engine/               # 引擎接口（抽象）
│       │   ├── asr/AsrEngine.kt      # ASR 引擎接口
│       │   ├── translation/TranslationEngine.kt # 翻译引擎接口
│       │   └── vad/
│       │       ├── VADEngine.kt      # VAD 引擎接口
│       │       ├── VadResult.kt      # VAD 结果
│       │       └── VoiceSegment.kt   # 语音段
│       ├── repository/           # 仓库接口（抽象）
│       │   ├── HistoryRepository.kt   # 历史记录仓库
│       │   ├── ModelRepository.kt     # 模型仓库
│       │   └── SettingsRepository.kt  # 设置仓库
│       └── usecase/              # 用例层
│           ├── model/ModelManagementUseCase.kt # 模型管理用例
│           └── translation/TranslateUseCase.kt  # 翻译用例
│
├── engine/                       # 引擎层（ASR / VAD / 翻译 具体实现）
│   └── src/main/kotlin/io/github/ztfang/eye/engine/
│       ├── asr/
│       │   ├── VoskAsrEngine.kt       # Vosk 多语种流式 ASR
│       │   ├── SherpaOnnxAsrEngine.kt # Sherpa-ONNX 中英文流式 ASR
│       │   ├── VoskLanguageMap.kt     # Vosk 语言→模型名映射
│       │   └── NativeAudioProcessor.kt # 原生音频处理器（AGC）
│       ├── vad/
│       │   └── WebRtcVadEngine.kt     # WebRTC VAD 实现
│       ├── translation/
│       │   ├── mlkit/
│       │   │   └── MlKitTranslationEngine.kt  # ML Kit 翻译引擎（LOCAL）
│       │   ├── cloud/
│       │   │   ├── CloudTranslationEngine.kt  # 云端翻译路由器（CLOUD）
│       │   │   ├── PapagoTranslationEngine.kt # Papago（Naver）
│       │   │   ├── BaiduTranslationEngine.kt  # 百度翻译
│       │   │   ├── DeepLTranslationEngine.kt  # DeepL 翻译
│       │   │   └── AzureTranslationEngine.kt  # Azure AI 文本翻译
│       │   └── llm/
│       │       ├── LLMClient.kt              # LLM HTTP 客户端
│       │       ├── LLMTranslationEngine.kt   # LLM 翻译引擎（AI）
│       │       └── LLMProvider.kt            # Provider 枚举（7 家）
│       ├── AssetCopy.kt              # Assets 模型文件拷贝工具
│       └── ModelPreparer.kt          # 模型预加载/下载管理器
│
├── data/                         # 数据层（存储实现）
│   └── src/main/kotlin/io/github/ztfang/eye/data/
│       ├── local/
│       │   ├── crypto/
│       │   │   └── CryptoManager.kt     # AES/GCM 加密管理（API Key 加密）
│       │   ├── dao/
│       │   │   └── HistoryDao.kt        # 历史记录 DAO
│       │   ├── database/
│       │   │   └── AppDatabase.kt       # Room 数据库
│       │   ├── datastore/
│       │   │   └── SettingsDataStore.kt # DataStore 设置持久化
│       │   └── entity/
│       │       └── HistoryRecord.kt     # 历史记录实体
│       ├── repository/
│       │   ├── HistoryRepositoryImpl.kt   # 历史记录仓库实现
│       │   ├── ModelRepositoryImpl.kt     # 模型仓库实现
│       │   └── SettingsRepositoryImpl.kt  # 设置仓库实现
│       └── util/
│           ├── TarBzipExtractor.kt    # tar.bz2 解压工具
│           └── ZipExtractor.kt        # zip 解压工具
│
├── eyeopener-website/            # 官方网站（纯静态 HTML）
│   ├── pages/
│   │   ├── index.html           # 首页（下载/GitHub/评论）
│   │   └── guide.html           # 使用指南
│   └── assets/                  # 静态资源（CSS/图片）
│
├── gradle/                       # Gradle 配置
│   ├── libs.versions.toml       # 依赖版本目录（Version Catalog）
│   └── wrapper/                 # Gradle Wrapper
│
├── scripts/                      # 辅助脚本
│   ├── dump_logs.ps1            # Logcat 导出脚本
│   └── collect_logs.ps1         # 日志收集脚本
│
├── logs/                         # 日志输出目录
├── 参考图/                        # UI 设计参考截图
│
├── AGENTS.md                     # 开发规范 & 约束（必读）
├── PRD文档.md                    # 产品需求文档
├── build.gradle.kts              # 根构建配置
├── settings.gradle.kts           # 模块设置
└── gradle.properties             # Gradle 属性
```

## 架构

```
UI Layer (Compose)
    ↓
ViewModel / Manager Layer
    ↓
Domain Layer (Model + UseCase)     ← 纯 Kotlin，无 Android 依赖
    ↓
Data Layer (Repository Impl)      ← 数据存储（DataStore / Room）
    ↓
Engine Layer (ASR/VAD/Translation) ← 计算引擎
```

**单向依赖**：上层只调用下层，禁止反向调用。

## 核心数据流

```
麦克风 / AudioPlaybackCapture
    ↓
AudioRecord (16kHz/mono/16bit)
    ↓
每 30ms 一帧 (480 samples)
    ↓
VAD 检测（仅 UI 状态，不切流）
    ↓
ASR 引擎.feedAudio(ShortArray)
    ↓
final 结果（静音间隔触发）
    ↓
翻译引擎（ML Kit / LLM）
    ↓
悬浮窗显示（原文 + 译文）
```

**多语种切换**：主屏切换源语种时 → `ModelPreparer.prepareAsr(lang)` → 加载对应模型 → 重启音频采集

### 翻译引擎

| 引擎 | 翻译后端 | 特点 |
|------|----------|------|
| LOCAL 本地 | ML Kit 离线 | 响应最快，需 Google Play Services 下载翻译包 |
| CLOUD 云端 | Papago / 百度 / DeepL / Azure（4 选 1） | 多语种覆盖广，需对应 API Key |
| AI 大模型 | LLM API（OpenAI / Claude / DeepSeek 等 7 家） | 上下文感知 + 智能润色 + 助手，需 API Key |

**ASR 引擎与翻译引擎解耦**：ASR 仅由源语言决定，所有翻译引擎共用同一套 ASR 分流逻辑：

| 源语言 | ASR 引擎 | 说明 |
|--------|----------|------|
| zh / en | Sherpa-ONNX X-ASR | 中英混说优化，CER ~9.59%（未下载回退 Vosk） |
| bn | Sherpa-ONNX BN Vosk | 孟加拉语专用（未下载回退 Vosk） |
| Nemotron 30 语种 | Sherpa-ONNX Nemotron 3.5 | 多语种（未下载回退 Vosk） |
| 其他 | Vosk | 静默回退，无弹窗 |

若翻译引擎不支持某语种，上层静默不响应（不弹错误）。

### ASR 引擎

| 引擎 | 语种数 | 模型大小 | 延迟 | 特点 |
|------|--------|----------|------|------|
| Vosk Small | 33 | 30-100 MB | 100-300ms | 真流式，AAR 集成 |
| Sherpa-ONNX | 中/英 | 20-80 MB | 50-150ms | 高精度，ModelScope 托管 |

**Vosk 支持 33 种语言**：中文、英语、印度英语、德语、法语、西班牙语、葡萄牙语、俄语、土耳其语、越南语、意大利语、荷兰语、加泰罗尼亚语、阿拉伯语、希腊语、波斯语、菲律宾语、乌克兰语、哈萨克语、瑞典语、日语、世界语、印地语、捷克语、波兰语、乌兹别克语、韩语、塔吉克语、吉尔吉斯语、格鲁吉亚语、布列塔尼语、古吉拉特语、泰卢固语

**Sherpa-ONNX 支持**：中文（80MB）、英文（20MB）

模型文件结构：
```
models/vosk/<lang>/
├── am/           # 声学模型
├── conf/         # 配置文件
└── graph/        # 语言模型

models/sherpa-onnx/<model-id>/
├── encoder.int8.onnx   # 编码器
├── decoder.onnx        # 解码器
├── joiner.int8.onnx    # 连接层
└── tokens.txt          # 词表
```

### 模型下载源

| 模型类型 | 下载源 |
|----------|--------|
| Vosk ASR | alphacephei.com |
| Sherpa-ONNX | ModelScope（ZhaoChaoqun/sherpa-onnx-asr-models） |
| ML Kit 翻译 | Google Play Services（自动管理） |

### 音频参数

- **采样率**：16kHz / mono / 16bit PCM
- **帧大小**：480 samples (30ms) — 符合 WebRTC VAD 规范
- **VAD 静音阈值**：30 帧 = 0.9 秒（避免正常停顿被截断）
- **Final 合并延迟**：2000ms（平衡翻译质量与延迟）
- **音频输入源**：麦克风 / AudioPlaybackCapture（应用内声音，Android 10+）

### LLM Provider

| Provider | Base URL | 认证方式 |
|----------|----------|----------|
| OpenAI | `api.openai.com/v1` | `Authorization: Bearer {key}` |
| Claude | `api.anthropic.com/v1` | `x-api-key` + `anthropic-version` |
| DeepSeek | `api.deepseek.com/v1` | `Authorization: Bearer {key}` |
| 千问 | `dashscope.aliyuncs.com/compatible-mode/v1` | `Authorization: Bearer {key}` |
| MiniMax | `api.minimax.chat/v1` | `Authorization: Bearer {key}` |
| MiMo | `api.mimo.xiaomi.com/v1` | `Authorization: Bearer {key}` |
| Gemini | `generativelanguage.googleapis.com/v1beta/openai` | `Authorization: Bearer {key}` |

## 悬浮字幕

- **位置/大小**：可拖拽（非按钮区域），可缩放（右下角 24dp×24dp 热区）
- **初始尺寸**：宽 = 屏幕 60%，高 = 屏幕 20%
- **最小尺寸**：屏幕宽 × 25%，屏幕高 × 20%
- **显示模式**：仅原文 / 仅译文 / 双语
- **左侧边栏**：蓝绿色渐变（#4DD0E1 → #81C784）

## 历史记录

- **存储**：Room 数据库（tb_history 表）
- **功能**：收藏、删除、单条复制、批量导出（纯文本）
- **清除**：设置页一键清除全部

## 设置页面

- **API 设置**：Provider 选择、API Key、Base URL、模型名、连接测试
- **本地模型**：Vosk 33 语种 + Sherpa-ONNX 中英文，下载/删除/进度
- **个性化**：显示模式、字号（12-32sp 连续）、透明度、强调色、深色模式
- **历史记录**：查看/收藏/导出/清除
- **音频源**：麦克风 / 应用内声音（AudioPlaybackCapture）

## 原生库

| 库 | 用途 | 来源 |
|----|------|------|
| `eye_native` | VAD JNI + 音频处理 AGC | `app/src/main/cpp/` 编译 |
| Vosk Android AAR | 多语种流式 ASR | `com.alphacephei:vosk-android:0.3.75` |
| webrtc_vad | 语音活动检测 | `app/src/main/cpp/vad/webrtc_vad.c` |
| sherpa-onnx-jni | Sherpa-ONNX JNI 绑定 | 预编译 `app/src/main/jniLibs/` |
| onnxruntime | ONNX 推理引擎 | 预编译 `app/src/main/jniLibs/` |

## 关键设计决策

1. **ML Kit 翻译模型不由 App 管理** — 由 Google Play Services 管理，未下载时跳过翻译
2. **API Key 加密存储** — AES/GCM/NoPadding，12 字节随机 IV，Base64(IV+密文+Tag)
3. **桌面图标** — 直接 PNG 引用，不用 adaptive-icon XML（避免系统回退默认图标）
4. **VAD 不切流** — 只检测语音状态更新 UI，音频照常送 ASR
5. **ASR 引擎与翻译引擎解耦** — ASR 仅由源语言决定，所有翻译引擎共用同一套 ASR 分流逻辑
6. **partial 翻译已移除** — 仅 final 结果触发翻译，保证翻译质量
7. **不支持语种静默处理** — 翻译引擎不支持的语种直接跳过，不弹错误

## 构建

```bash
# Debug 构建
./gradlew assembleDebug

# Release 构建
./gradlew assembleRelease

# 查看依赖
./gradlew :app:dependencies
```

**环境要求**：
- Android Studio Ladybug +
- JDK 17+
- Android SDK 36
- minSdk 24 (Android 7.0)
- NDK（用于编译原生库）

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源，可自由用于个人与商业用途。

```
Copyright 2026 zt-Fang (EyeOpener)
```

本项目使用了以下优秀的开源项目，感谢其作者与贡献者：

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 端侧实时语音识别（Apache-2.0）
- [Vosk](https://alphacephei.com/vosk/) — 多语种离线语音识别（Apache-2.0）
- [Silero VAD](https://github.com/snakers4/silero-vad) — 语音活动检测（MIT）
- [ML Kit](https://developers.google.com/ml-kit) — 端侧机器翻译

> 注：第三方库与模型遵循其各自的开源协议。
