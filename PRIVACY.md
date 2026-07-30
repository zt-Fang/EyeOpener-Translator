# EyeOpener 隐私政策

最近更新：2026-07-30

本政策说明 EyeOpener（以下简称"本应用"）如何处理用户数据。本应用遵循"隐私优先"原则，**原始音频不上传服务器**。

## 1. 收集的数据

| 数据类型 | 用途 | 存储位置 | 是否上传 |
|---------|------|----------|----------|
| 麦克风音频流 | 实时语音转文字（ASR） | 仅设备内存 | 否 |
| 应用内音频（AudioPlaybackCapture） | 识别其他 App 播放的语音 | 仅设备内存 | 否 |
| 翻译文本（原文+译文） | 历史记录 | 设备本地 Room 数据库 | 仅翻译时按需发送给所选翻译引擎 |
| 翻译引擎 API Key | 调用第三方翻译服务 | 设备本地，AES/GCM 加密 | 仅 Key 本身按需发送给所选 Provider |
| LLM 对话内容 | AI 助手多轮对话 | 设备本地 Room 数据库 | 发送给用户配置的 LLM Provider |

**不收集**：本应用不收集设备标识符、位置、通讯录、相机、相册、浏览记录等与翻译功能无关的数据。

## 2. 第三方服务

翻译与识别功能由用户主动选择的第三方服务处理，本应用不主动连接任何第三方服务器：

| 服务 | 用途 | 数据流向 | 官方政策 |
|------|------|----------|----------|
| ML Kit Translate | 本地离线翻译（LOCAL 模式） | 模型由 Google Play Services 管理，文本不出设备 | [Google ML Kit 隐私](https://developers.google.com/ml-kit/privacy) |
| Papago / 百度 / DeepL / Azure / Google Translate | 云端翻译（CLOUD 模式） | 用户输入文本 + API Key 发送至对应服务商 | 见各服务商官网 |
| OpenAI / Anthropic Claude / DeepSeek / 通义千问 / MiniMax / 小米 MiMo / Google Gemini | AI 翻译+助手（AI 模式） | 用户输入文本 + API Key 发送至对应 LLM 服务商 | 见各服务商官网 |
| Sherpa-ONNX / Vosk / Silero VAD | 本地 ASR 与 VAD | 完全在设备内执行，不出设备 | — |
| alphacephei.com / ModelScope / HuggingFace / GitHub Release | 模型下载源 | 仅下载模型文件，不上传用户数据 | 见各站点政策 |

启用云端/AI 翻译时，原文文本会发送至用户选择的第三方服务商。**用户应自行评估该服务商的隐私政策**。

## 3. 数据存储与加密

- **API Key**：使用 AES/GCM/NoPadding 加密存储于 DataStore，12 字节随机 IV，Base64(IV + 密文 + Tag)
- **历史记录**：Room 数据库（`tb_history` 表），仅本地存储，不上传
- **设置项**：DataStore 本地存储
- **ASR 模型**：下载至 `filesDir/models/`，仅本地

## 4. 用户权利

- **清除历史**：设置 → 历史记录 → 清除全部，立即删除 Room 中所有记录
- **撤回权限**：系统设置 → 应用 → EyeOpener → 权限，撤回麦克风/录屏权限即停止音频采集
- **删除 API Key**：设置 → API 设置 → 清除 Key
- **卸载即清除**：卸载应用自动清除所有本地数据（Room / DataStore / 模型文件）
- **导出**：历史记录支持导出为纯文本，用户自行保管

## 5. 权限使用说明

| 权限 | 用途 | 触发时机 |
|------|------|----------|
| `RECORD_AUDIO` | 麦克风采集语音 | 用户开启悬浮字幕 |
| `SYSTEM_ALERT_WINDOW` | 显示全局悬浮字幕 | 用户开启悬浮字幕 |
| `POST_NOTIFICATIONS` (Android 13+) | 前台 Service 通知 | 启动字幕服务 |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | 后台持续识别 | 启动字幕服务 |
| MediaProjection（系统授权） | 应用内声音采集（AudioPlaybackCapture） | 用户选择"应用内声音"模式 |

## 6. 儿童隐私

本应用不面向 13 岁以下儿童，不主动收集儿童个人信息。

## 7. 政策变更

政策变更将在本页面更新，重大变更通过应用内首次启动弹窗提示用户再次确认。

## 8. 联系方式

- GitHub Issues：[github.com/ztfang/eye/issues](https://github.com/ztfang/eye/issues)
- Email：通过 GitHub 个人主页获取

## 9. 开源协议下的免责

本应用基于 Apache License 2.0 开源，按"现状"提供，不附任何明示或暗示担保。用户因使用第三方翻译/LLM 服务产生的任何数据泄露责任，由用户与对应服务商之间解决，与本应用无关。
