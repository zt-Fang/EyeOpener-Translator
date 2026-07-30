# 【学习工作赛道】EyeOpener —— 实时悬浮字幕翻译助手

**标签**：`学习工作`

**赛道**：TRAE AI 创造力大赛 · 学习工作赛道

**作品形态**：Android 原生 App（Kotlin + Jetpack Compose，已发布 Release APK）

---

## 一、Demo 简介

### 是什么

EyeOpener 是一款 Android 实时语音翻译字幕 App。它在 Zoom / Teams / 微信 /音视频通话、VOA / BBC / TED 精听时，**在屏幕任意应用上层浮出一块可拖拽可缩放的字幕条**，把外语实时转写并翻译成中文（或任意目标语言），让你「边听边看」。基于实时语音识别（ASR）+ 多引擎翻译 + 悬浮窗 Overlay，支持 33 语种离线识别、三档翻译引擎（极速/精确/AI智能），全程隐私优先，可纯离线运行。

### 面向谁

- **跨国音视频通话用户**：Zoom / Teams / 微信 / WhatsApp / Skype 与海外同事、客户、亲友通话，外语听不懂、说不顺，悬浮字幕实时双向翻译，破解跨语言沟通障碍
- **跨境协作的远程办公族**：英文会议听不清/跟不上节奏，悬浮字幕实时救命
- **备考雅思/托福/PTE 的学生**：看 VOA / BBC / TED 精听时，悬浮字幕边看边记
- **学二外的语言学习者**：33 语种离线 ASR 覆盖日韩法德西俄等主流语种
- **听力障碍 / 老年用户**：把外语视频的「声音」变成「文字」
- **注重隐私的用户**：所有 ASR/翻译可纯离线，API Key AES/GCM 加密本地存储

### 主要功能

1. **音视频通话实时翻译**：Zoom / Teams / 微信 / WhatsApp / Skype 通话中，悬浮字幕实时转写对方外语并翻译成你的母语，**支持「应用内声音」音频源（Android 10+ AudioPlaybackCapture）直接拾取通话音频**，无需外放、不受环境噪音干扰，破解跨语言沟通障碍
2. **三档翻译引擎，按场景切换**
   - 极速：实时 ASR + ML Kit 翻译，100-300ms 延迟，33 语种全覆盖，纯离线
   - 精确：中英文高精度识别 + ML Kit 翻译
   - 智能：实时 ASR + LLM API，支持上下文意译
3. **可拖拽、可缩放、可记忆的悬浮窗 Overlay**
   - 任意应用上层显示，拖动位置、右下角热区缩放
   - 位置 + 大小持久化（DataStore），下次启动恢复
   - 双语 / 仅原文 / 仅译文三种显示模式
4. **33 语种离线 ASR + 模型管理**
   - Vosk Small 模型真流式识别，Sherpa-ONNX 中英文高精度
   - 模型管理界面下载/删除/进度实时刷新，翻译进行中禁止删除模型

> **截图建议位置①**：主界面（底部导航：字幕 / 助手 / 设置）

> **截图建议位置④**：33 语种模型下载管理界面

---

## 二、Demo 创作思路

### 灵感来源

我是一名多语言学习爱好者，平时喜欢看外国视频，也经常和外国朋友音视频通话。但现实很骨感：看外语剧时听不清连读，和外国朋友通话时外语听不懂、说不顺，经常要切到翻译 App 手敲句子，节奏全断。于是我开始想：**能不能用 Android 自带的麦克风 + 端侧 ASR + 屏幕悬浮窗，做一台「软件版翻译耳机」，在看视频和跨语言通话里都能实时出字幕？** 。

### 想解决的问题

| # | 痛点 | 真实体感 |
|---|---|---|
| 1 | 跨国音视频通话听不懂 | Zoom / Teams / 微信 / WhatsApp 通话，对方说外语听不懂，靠手写翻译太慢、打断节奏 |
| 2 | 翻译耳机延迟太高 | 会议节奏快，1-3 秒延迟已错过关键信息 |
| 3 | 翻译耳机拾音混乱 | 开放工位/咖啡馆根本用不了 |
| 4 | 翻译耳机续航短 | 半天会议就 gg |
| 5 | Whisper 体积太大 | whisper-large-v3 3GB+，端侧跑不动 |
| 6 | 单一翻译引擎不够用 | 极速场景要快，复杂语境要准，两者不可兼得 |
| 7 | API Key 上传云端 | 用户尤其企业用户极其在意隐私 |

### 为什么做这个方向

- **赛道契合度高**：学习工作赛道强调「真实问题真实解决」，跨语言沟通是全球远程办公时代第一刚需
- **痛点真实**：以上痛点都是自己和身边朋友遇到的，不是为参赛虚构
- **可演示性强**：安装 APK → 打开 其他应用 → 开悬浮字幕 → 一行英文 1 秒内出中文，评委可立即验证
- **可延展性强**：后续可加会议纪要自动生成、双语字幕导出、说话人分离
- **人机协同典型**：架构选型、引擎对比、模型下载源调研都大量依赖 Trae AI 加速

---

## 三、Demo 体验地址

**体验方式**：下载 Release APK 直接安装

- **Release APK 下载**：https://github.com/zt-Fang/EyeOpener-Translator/releases

**快速上手**：
1. 下载 `EyeOpener.apk` 安装，授予麦克风 + 屏幕录制权限
2. 选择翻译模式（建议先试「极速」），下载对应语种模型
3. 点击「开启翻译字幕」，打开 YouTube / Netflix / Zoom 等任意 App
4. 悬浮字幕实时出现，可拖拽、缩放，下次启动自动恢复位置
5. **音视频通话场景**：设置 → 音频源 → 切换为「应用内声音」，再发起 Zoom / Teams / 微信 / WhatsApp 通话，对方外语实时转字幕翻译，无需外放扬声器

---

## 四、TRAE 实践过程

本项目是 Human-AI Co-creation 的典型范例。Trae 中国版内置大模型作为「数字架构师 + 分析顾问 + 审查专家」，在以下关键节点发挥了决定性作用。

### 4.1 关键任务 Session ID

| 序号 | 任务描述 | Session ID |
|---|---|---|
| ① | 代码审查和风险分析 | `[3154926737326379:a17cd4e30d8b63ef54fc79807a9d9976_6a44c40e22c9f7e1c46ba5d6.6a44c40f22c9f7e1c46ba5d9.6a44c40e22c9f7e1c46ba5d7:TRAE Work CN.0.1.30.no_sid.no_ppe.T(2026/7/1 15:38:55)]` |
| ② | WebRTC VAD 帧大小调试 | `[3154926737326379:ec1475e95ace9df853c7dfbf685a99f0_6a4b3ee8ef3c942c31122cc7.6a4b8aa6ef3c942c311232a4.6a4b8aa6ef3c942c311232a2:TRAE Work CN.0.1.30.no_sid.no_ppe.T(2026/7/6 18:59:50)]` |
| ③ | 架构调整 | `[3154926737326379:386a562a13a0498e68e68ab08bfdae8c_6a475d011cf8bfc8b1eae1a0.6a48aa8e9ebb4df3594b22a7.6a48aa8d9ebb4df3594b22a5:TRAE Work CN.0.1.30.no_sid.no_ppe.T(2026/7/4 14:39:10)]` |


### 4.2 开发关键步骤截图（不少于 3 张）

> 以下 3 张截图已保存至项目目录 `screenshots/` 下，在 Trae 论坛发帖时直接拖拽上传即可。

#### 截图 1：代码审查 —— Trae 发现 30+ 隐藏风险与编译错误

**对应步骤**：项目中期阶段，让 Trae 对全项目做代码审查，按 6 个维度排查风险：

| 维度 | 关键发现 |
|---|---|
| 语法和样式 | SubtitleViewModel 缺 ViewModel/Inject import |
| 安全实践 | LocalModelsScreen 引用未定义的 selected/colors |
| Bug 检测 | DownloadProgress 缺 modelName 字段 |

Trae Code 用时 5 分 11 秒完成全项目扫描，输出详细诊断报告，帮助我定位了编译阻断问题。

![代码审查截图](screenshots/screenshot-01-code-review.png)

#### 截图 2：VAD 分析与优化 —— 从「CPU 热点」到「编译通过」

**对应步骤**：音频引擎优化阶段，让 Trae 分析音频处理链路并给出优化建议：

| 问题 | Trae 建议 | 结果 |
|---|---|---|
| RMS 计算在 audio loop 中 | CPU 热点，建议优化 | 性能提升 |
| Flow collect + launch 过多 | 建议 `stateIn(scope)` 统一状态流 | 减少内存抖动 |
| Vosk partial + final 合并逻辑重复 | 严重问题，建议重构 | 消除重复逻辑 |
| debounce 位置错误 | 应该在「触发层」而非函数内部 | 避免误触发 |
| FINAL_MERGE_DELAY_MS 过长 | 建议改为 600ms | 降低翻译延迟 |
| restartAudioProcessing race condition | 加 mutex 保护 | 消除竞态 |
| StateFlow 初始化方式不安全 | 改用安全初始化模式 | 避免空指针 |
| Log overuse | 建议 `if (DEBUG)` 条件输出 | 减少日志开销 |
| 助手界面麦克风 | 改用 Vosk 本地识别，复用音频采集 | 不触发翻译，节省资源 |

Trae Code 用时 13 分 17 秒完成全部优化，**编译通过**。

![VAD分析截图](screenshots/screenshot-02-vad-analysis.png)

#### 截图 3：架构调整 —— 从 Whisper 30 秒延迟到 RTranslator 级实时字幕

**对应步骤**：架构重构阶段，让 Trae 把原有的 Whisper 架构改造为流式实时架构：

**改造前的问题**：

| 问题 | 影响 |
|---|---|
| 使用 Whisper tiny/base 模型 | 体积大、延迟高 |
| silence=2000ms 触发 ASR | 错误设计，等待静音才触发 |
| 32k samples 一次性推理 | 导致 30~40 秒延迟 |
| UI 只能显示最终结果 | 无法实时更新，体验极差 |
| VAD 不稳定 | 误判 speech/no speech |

**改造后的目标效果**：

- 字幕延迟 ≤ 1 秒（接近 RTranslator）
- 支持边说边出字幕（incremental subtitle）
- 支持持续修正字幕（overwrite）
- UI 实时刷新，不等待 final result

Trae Code 用时 14 分 21 秒完成 **RTranslator 级实时字幕系统改造**，输出架构问题分析对比表。

![架构调整截图](screenshots/screenshot-03-architecture-refactor.png)



### 4.3 Trae 在关键节点的赋能

1. **避免选错 ASR 引擎**：差点直接上 Whisper.cpp（1.5GB+ 中低端机跑不动），Trae 给出三档延迟/准确率/体积对比，改用 Vosk + Sherpa-ONNX 方案
2. **WebRTC VAD 帧大小陷阱**：第一版 VAD 帧大小传错完全不工作，Trae 指出仅支持 10/20/30ms 三档帧长，统一常量到 480 samples
3. **单向依赖架构**：Trae 建议拆成 app/domain/engine/data 4 个 Gradle 模块，domain 层强制纯 Kotlin 无 Android 依赖，后续单元测试和引擎替换零成本
4. **API Key 加密**：Trae 提示不能明文存 SharedPreferences，给出 AES/GCM + 12 字节随机 IV + Base64 存储标准方案，规避硬编码密钥和明文存储漏洞
5. **并发保护**：Trae 主动指出翻译运行中删除模型会 Crash，建议加状态守卫 + 弹窗提示

---

## 报名帖链接

`[待填写：附上已通过的社区报名帖链接]`
