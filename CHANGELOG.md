# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- README 顶部增加悬浮字幕效果截图
- 隐私政策（`PRIVACY.md`）
- 贡献指南（`CONTRIBUTING.md`）
- GitHub Actions CI（构建 + detekt 检查）
- Issue 模板（Bug Report / Feature Request）
- ktlint + detekt 代码静态检查
- domain 层单元测试

### Changed
- README 语言数口径修正为 41（Vosk 32 + Nemotron-only 9）
- 精简核心文件注释（5 头部 + 2 函数）
- 修复 `com.example.eye` 包名遗留（data/domain/engine consumer-rules.pro + push_x_asr_model.ps1）
- `.gitignore` 排除内部材料（参考图/code_review_report/PRD文档/eyeopener-website 等）

### Removed
- 删除旧包遗留文件 `app/src/main/java/com/example/eye/floatingsubtitleservice.kt`

## [1.0.0] - 2026-07-30

### Added
- 全局悬浮字幕（可拖拽/缩放/拖拽热区）
- 41 种语言 ASR：Sherpa-ONNX X-ASR（zh/en）+ Nemotron 3.5（26 语种）+ BN Vosk（孟加拉语）+ Vosk（33 语种兜底）
- 三档翻译引擎：LOCAL（ML Kit 离线）/ CLOUD（Papago/百度/DeepL/Azure/Google）/ AI（7 家 LLM Provider）
- AI 助手多轮对话
- 历史记录（Room 本地存储，收藏/导出）
- 个性化设置（颜色/字号/透明度/显示模式）
- 应用内声音采集（AudioPlaybackCapture，Android 10+）
- API Key AES/GCM 加密存储
- Silero VAD（assets 内置，32ms 窗口）
- 首次使用引导页
- 中英文双语 README

### Architecture
- MVVM + Clean Architecture（app / domain / engine / data 四模块）
- Jetpack Compose + Material 3 + Design Token
- Hilt 依赖注入
- Kotlin Coroutines + Flow
- Room + DataStore 本地存储

[Unreleased]: https://github.com/ztfang/eye/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/ztfang/eye/releases/tag/v1.0.0
