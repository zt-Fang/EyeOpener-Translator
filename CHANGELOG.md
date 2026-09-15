# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 格式，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.3.3] - 2026-09-15

### Added
- README 顶部增加悬浮字幕效果截图
- 隐私政策（`PRIVACY.md`）
- 贡献指南（`CONTRIBUTING.md`）
- GitHub Actions CI（构建 + detekt 检查）
- Issue 模板（Bug Report / Feature Request）
- ktlint + detekt 代码静态检查
- domain 层单元测试
- `GoogleReachabilityChecker`：Google 服务可达性探测。判据为 **TLS 证书校验**——真 Google 的
  证书由 Google Trust Services 签发且 SAN 覆盖目标域名，被污染/劫持的地址无法伪造，因此
  既不依赖任何特定代理工具，也无需维护 Google IP 段列表。区分「可达 / 有响应但不是 Google /
  完全连不上」三态，用于把"下载失败"还原成可行动的诊断
- `TranslationPrepPhase`（engine 模块）：翻译模型准备阶段（IDLE / CHECKING / DOWNLOADING /
  READY），驱动 UI 的非模态下载状态条
- `TranslationPrepException` / `TranslationPrepFailureKind`（engine 模块）：离线翻译模型失败的
  **分类**（GMS 不可用 / 网络不可达 / 下载未完成 / 未分类）。分类给用户看一句人话，
  技术细节（域名 / IP / 证书结论 / 原始异常）单独承载，进日志与诊断入口
- 「设置 → 翻译模型诊断」：呈现最近一次离线模型失败的技术细节，可清空记录
- release 签名改为由仓库根目录的 `keystore.properties` 驱动（已 gitignore，不落密文）：
  提供该文件即启用签名，缺失时保持未签名，CI 仍可只做构建验证

### Changed
- README 语言数口径修正为 41（Vosk 32 + Nemotron-only 9）
- 精简核心文件注释（5 头部 + 2 函数）
- 修复 `com.example.eye` 包名遗留（data/domain/engine consumer-rules.pro + push_x_asr_model.ps1）
- `.gitignore` 排除内部材料（参考图/code_review_report/PRD文档/eyeopener-website 等）
- 实时链路性能与稳定性修复（详见 `docs/code-review-fixes-applied.md`）：
  - `audioProcessingJob` 正确登记句柄，停止/重启/重载不再依赖空操作的 `cancel()`
  - `isRecording` / `audioRecord` / `inputModeEngine` 加 `@Volatile`，消除停止信号不被观察的卡死路径
  - 录音循环缓存引擎解析结果，去掉每帧重复的 map 查找
  - VAD 软静音改为提交"当前显示行"，修复重复行 / 首句永不提交 / 历史行不累积
  - Vosk `decodeAndGetResult()` 不再重复解析同一份 partial
  - VAD / Sherpa 引擎复用帧缓冲，消除每帧数组分配
  - 助手流式回复改 `StringBuilder` + 100ms 合并刷新，去掉 O(n²) 拼接
  - 历史表加 `timestamp` 索引（数据库 v1→v2 + `MIGRATION_1_2`）
  - Keystore 解密结果按密文缓存，拖拽/调参不再触发多次 IPC 级解密
  - 导出功能移出主线程；历史页改用 `rememberCoroutineScope()`
  - Zip / Tar 解压增加 canonicalPath 校验，修复路径穿越（Zip Slip）
  - `LLMClient` 复用共享 `OkHttpClient` 的连接池与线程池；日志拦截器对凭据做脱敏
  - 热路径全文日志（含 `TranslateUseCase`）统一用 `BuildConfig.DEBUG` 包裹
  - 过滤 ASR 在噪声/静音段吐出的纯标点 final（实测历史中出现过 `"？"`/`"。"`/`" ，"` 三条
    垃圾行，且每条都会白跑一次翻译请求）
  - **ABI 策略：arm64-v8a + armeabi-v7a 双支持**。中途曾一度仅保留 arm64-v8a
    （32 位 native 库缺失会让 32 位设备启动即崩 `UnsatisfiedLinkError`），随后补全 32 位
    native 库（`engine/src/main/jniLibs/armeabi-v7a/` 下的 `libonnxruntime.so` +
    `libsherpa-onnx-jni.so`）恢复双 ABI；engine 初始化处的 `catch (Throwable)` 作为兜底保留，
    防止未来任何 native 库缺失或加载异常导致进程崩溃
  - **native 库加载失败不再崩溃**：`SherpaOnnxAsrEngine` / `SileroVadEngine` / `VoskAsrEngine`
    的初始化由 `catch (e: Exception)` 改为 `catch (e: Throwable)`（并放行 `CancellationException`）。
    `System.loadLibrary` 失败抛的是 `Error`，原写法捕不到 → 会逃出协程导致进程崩溃
  - **Release 包本地翻译必崩（`NoClassDefFoundError`）修复**：`app/proguard-rules.pro` 的
    ML Kit keep 规则写成了不存在的包名 `com.google.mlkit.translate.**`（真实为
    `com.google.mlkit.nl.translate.**` + `com.google.mlkit.common.**`），规则命中 0 个类 →
    R8 把 ML Kit 公开 API 全删。debug 包因 `isMinifyEnabled=false` 无法复现，只在 release 暴露。
    已在 `app/proguard-rules.pro` 与 `engine/consumer-rules.pro` 补入真实包名规则
    （`nl.translate` / `nl.languageid` / `common`，另加 `vision.text` 兜底）
  - `MainActivity` 增加 `android:launchMode="singleTask"`：此前为默认 `standard`，悬浮窗以
    `NEW_TASK | CLEAR_TOP` 反复拉起会无限堆叠 Activity（实测 26 个实例）并丢失导航栈
  - 深链导航改为冷/热启动统一：新增 `onNewIntent()`，读取后立即从 intent 删除 `navigate_to`。
    此前 extra 残留在 intent 且未声明 `configChanges`，导致每次 Activity 重建都重复导航、
    导航栈越堆越深（实测切 6 次深色后需按 6 次返回键才能退出设置页）；同时应用存活时携带该
    extra 启动不跳转 —— 悬浮窗「设置」按钮点不动，只把应用切到前台
  - API 配置「保存」与「连通性测试」解耦：保存只做本地非空校验、不再要求联网测试返回 200
    （此前 `testApi` 非 200 则配置永不落盘，用户以为已保存、实则 DataStore 为空，直接表现为
    「翻译没反应」）；测试改为独立可选按钮，并补 10s 连接 / 15s 读超时，失败时回显响应体前 200 字
  - AI 流式超时改为**首 token 超时（TTFT，15s）**，替换原先包住整条 `chatStream` 的 7s 总时长
    上限（旧逻辑会在长句/慢模型上中途掐断，并把半截译文当成功结果写入历史）
  - AI 失败原因可见化：按超时 / 401 / 403 / 404 / 429 分类给出可行动文案；流式已吐出内容时
    不再降级重试（避免同一行叠加重复译文）；配置级错误直接上抛、不做无意义的降级
  - `LLMClient` 补 `@Singleton`：内部 `OkHttpClient` 的连接池与线程池不再被多个注入点各自复制
  - **深色模式收敛为「仅浅色」**：`EyeTheme` 固定浅色 ColorScheme，XML 父主题改为
    `Theme.Material3.Light.NoActionBar`。应用内本无任何深色开关（设置页各分组均与主题无关），
    深色仅由系统设置被动触发；而 `GradientBackground` 与 58 处 `Color.White` 硬编码浅色，与
    深色 ColorScheme 组合会产生「浅色文字压浅色底」的不可读界面（真机上标题 / 章节名 /
    提示语几乎不可见）
  - **本地翻译（ML Kit）模型下载链路修复**（真机 vivo V2505A / Android 16 实测）：
    - `ModelPreparer` 的 CLOUD 分支不再预热 ML Kit。原先 `LOCAL, CLOUD ->` 合并处理，导致
      选「云端翻译」也会去下载本地离线模型，失败即弹「翻译模型未就绪」（纯误伤）；现改为
      只校验云端 API Key（新增 `CloudTranslationEngine.validateConfig()`）
    - 下载超时 60s → **180s**，并新增 **120s 轮询窗口**。GMS 的模型下载**不随协程取消而停止**，
      实测 60s 超时报失败后模型仍在后台下载完成，原逻辑把「正在下载」误判为「失败」
    - 新增离线模型下载的**非模态状态条**。ML Kit 无下载进度回调，故用「不确定进度圈 +
      已等待时长」呈现；超过 30s 自动切换为「网络较慢，下载仍在继续」
    - `MlKitTranslationEngine` 抽出 `preparePair()` / `downloadAndWait()` /
      `isModelDownloadedFresh()`（绕过进程缓存直查 GMS），不再用 `translate("hello")` 蹭下载路径
    - 冷启动预热改用 DataStore 的当前语言值。原先读 `_subtitleState` 快照，init 阶段各
      collect 执行顺序不定，可能用默认 en/zh 下载与用户设置无关的语言对
    - 实测踩坑记录：`dl.google.com` 被解析到国内 IP（211.95.34.129，0.55s 返回 404）而
      `play.googleapis.com` 正常时，ML Kit 模型下载必然挂死超时——代理分流规则需覆盖
      Google 的下载域名，`GoogleReachabilityChecker` 会把这种情况直接指出来
    - **提示文案精简：弹窗只留一句人话，技术细节移出**
      - 新增 `TranslationPrepException` / `TranslationPrepFailureKind`（engine 模块）：
        把失败原因从"一个长字符串"改为「分类 + 技术细节」两段。分类给用户看，
        细节（下载域名 / 解析到的 IP / 证书结论 / 原始异常）进日志与诊断入口
      - 失败弹窗从「标题 + 三四行长文 + 提示句 + 两个按钮」缩到「标题 + 一行 + 两个按钮」。
        原先正文里塞着 `dl.google.com`、解析到的 IP、`Timed out waiting for 300000 ms`
        这类内容，用户看不懂，开发者又只能在日志里翻
      - 新增「设置 → 翻译模型诊断」入口：呈现最近一次失败的技术细节，可清空
      - **移除「离线翻译模型已就绪」Toast**：下载完成是预期结果，字幕正常出现即是信号，
        成功提示属于噪音（尤其用户已切到别的 App 时）
      - 移除写进 `runtimeError` 的「翻译模型未就绪」长文案——那条通道在主屏是 Toast，
        会把整段技术细节弹出来，既与弹窗重复又是用户看不懂的内容。随之删除已无写入方的
        `clearTranslationModelError()` 与 `TRANSLATION_MODEL_ERROR_PREFIX`
      - 悬浮窗状态提示文案改紧凑：下载中显示「下载中 · 1m12s」（原「离线翻译模型下载中，
        已等待 1 分 12 秒」），失败显示「离线翻译模型未就绪」。优先级：失败 > 其它错误 > 下载中

### Removed
- 删除 `res/values-night/themes.xml`（内容与 `values/` 版逐项相同的空壳资源）——深色模式彻底下线
- `EyeTheme` 移除深色分支：`DarkColors` 配色、`isSystemInDarkTheme()` 判定、`darkColorScheme` /
  `dynamicDarkColorScheme` 引用，以及随之失去引用的 `EyeTeal80` / `EyeAmber80` / `NeutralVariantDark`
- 删除旧包遗留文件 `app/src/main/java/com/example/eye/floatingsubtitleservice.kt`
- 清理无用代码：已放弃的应用内手动深色模式（太阳/月亮）残留、`polishVoiceInput()`、
  `translationResult`、`SubtitleState.asrEngineType`、`TranscriptionResult`、
  `HistoryDao` 收藏/按 id 查询链、`AudioData`、`VoiceSegment`、`AsrEngine.isEndpoint()` 等
- 删除自研 JNI 死代码：`NativeAudioProcessor`（全项目无实例化点）+ `engine/src/main/cpp/`
  （CMake 目标 `eye_native`）+ `externalNativeBuild` 配置 + 两处 ProGuard keep 规则 +
  `engine/.cxx/` 缓存；README 的原生库表与 NDK 前置条件同步更新

### Fixed
- 代码审查 + 真机回归发现的问题：
  - **可达性探测的结论与下载结果脱节（本次最关键）**：探测目标只有 `dl.google.com`，
    但 ML Kit 的模型数据**不是**从它传的——实测完整链路是
    `dl.google.com`（重定向器，根路径返回 404）→ `redirector.gvt1.com`
    → `rN---sn-*.gvt1-cn.com`（真正的数据源）。两个域名的分流结果可以完全相反：
    入口命中 `DomainKeyword(google)` 走了代理，数据源命中 `GeoIP(cn)` 被放走直连。
    只探入口会得出"网络没问题"的错误结论，把用户引向无意义的重试。
    现新增 `checkAll()` 并行探测入口与数据源，失败详情里两条都写出
  - **把本地解析 IP 写成"解析为"会误导**：TUN/VPN 代理下 `InetAddress.getByName`
    返回的只是本地 DNS 结果，实际出口由代理规则决定。实测本地解析 `211.95.34.129`
    而实际走的是日本节点——用户据此会误判成"分流没生效"去乱改配置。
    文案统一改为"本地解析为"，并追加说明澄清该 IP 不代表实际出口
  - **`Reachable` 丢失解析 IP**：原为 `data object`，改为携带 `host` / `resolvedIp`
    的 `data class`，失败详情里始终写出本地解析结果（在非 TUN 代理场景下仍是有效线索）
  - **探测结果不含耗时，无法定位瓶颈**：实测两个域名都 TLS 通过、都判定 `Reachable`，
    但 `dl.google.com` 走代理用了 2045ms、`redirector.gvt1.com` 直连只用 179ms——
    相差一个数量级却完全看不出来。现给三种探测结果加上 `elapsedMs`，文案里写出
    "耗时 xxxxms"（未测量时不显示，避免 0ms 噪音）
  - **诊断文案去掉 Markdown 与成因分析**：诊断弹窗是纯文本渲染，原文案里的
    `**本地 DNS**` 会把星号原样显示给用户；同时分流规则的成因分析（`GeoIP(cn)`、
    `gvt1-cn.com` 等）对用户没有操作价值，只保留"两个域名的探测结果 +
    本地解析 IP 不代表实际出口 + 原始错误"。成因分析留在 CHANGELOG 与代码注释里
  - **DNS 解析不受超时约束**：`resolveHost` 是阻塞调用且在 `downloadAndWait` 里**下载前**
    同步执行，DNS 不响应时会白等数秒把下载往后推。现包在 5s 的 `withTimeoutOrNull` 内，
    超时返回 null（该结果只影响提示文案，不阻断主流程）
  - **不支持的语言对被归入 `UNKNOWN`**：弹窗只显示泛化的"模型未能就绪，可重试"，而重试
    永远不会成功（改动前反而会显示"ML Kit 不支持语言对 X → Y"，属于回退）。新增
    `UNSUPPORTED_LANGUAGE` 分类，弹窗给出"请改用云端或 AI 翻译"并**隐藏重试按钮**
  - `dismissTranslationModelError()` 只重置 `status` 未清 `failureKind`，会残留过期分类
  - `GoogleReachabilityChecker.describe()` 的 `Intercepted` 分支在解析不到 IP 时会输出
    `dl.google.com但对方不是 Google`（域名与后续文字之间缺分隔符），已补逗号
- **真机回归实测数据**（vivo V2505A / Android 16；Wi-Fi + Clash Meta TUN 模式）：
  - 完整下载链路耗时：单个语言模型（冰岛语）**154s** 完成
    （超时窗口 180s + 120s 轮询确认，已用掉 86%）
  - Clash 实际规则命中（`adb logcat` 可见）：`dl.google.com` →
    `DomainKeyword(google)` → 代理节点；`redirector.gvt1.com` → `GeoIP(cn)` → DIRECT；
    `rN---sn-*.gvt1-cn.com`（真正的数据源）→ `GeoIP(cn)` → **DIRECT**
  - 并行探测耗时对比：`dl.google.com` 走代理 **2045ms** / `redirector.gvt1.com` 直连
    **179ms**——延迟维度上代理慢一个数量级
  - 但带宽维度代理并不差：`curl` 经代理下载 3MB 实测 **1.17MB/s**，
    而模型下载全程平均仅约 200KB/s → 瓶颈更可能在数据源那一侧
  - 本地 DNS 把两个域名都解析为 `211.95.34.129`，但 `dl.google.com` 实际走代理——
    再次印证"本地解析 IP 不等于实际出口"

### Tests
- 新增 `engine` 模块单元测试基础设施（此前该模块既无测试目录也无测试依赖）：
  `engine/src/test/kotlin/io/github/ztfang/eye/engine/network/GoogleReachabilityDescribeTest.kt`
  共 11 个用例，覆盖三种探测结果的文案契约，把这几条不变量钉死：
  「`Reachable` 必须写出本地解析 IP 且标明它来自本地 DNS」、
  「探测列表必须同时覆盖入口域名与真正的数据源域名」、
  「有耗时时必须写出、未测量时不得出现 0ms 噪音」、
  「纯文本渲染，不得包含 Markdown 标记」

## [1.3.0] - 2026-09-07

> 说明：本段落为事后补录——原 CHANGELOG 只记录了 `[1.3.2]` 与 `[1.0.0]`，
> 中间版本未写。以下条目依据 git 提交历史整理，可能不完整。
> 另外 `[1.3.1]` 这个版本**从未发布**（无对应提交与 tag），其链接定义已移除。

### Added
- 自定义服务商、下载中途取消、模型状态自愈与精准 logcat
- 模型列表支持从服务商 API 实时拉取 + 服务商下拉无缝拼接 UI

### Fixed
- 模型卡片假下载——`isAvailable` 改为状态与目录同时满足
- 放宽下载读超时并增加断点续传重试
- ASR 路由抽源 + 智谱/OpenRouter provider + P0 修复

### Changed
- 清理死代码 + ASR 弹窗精准化 + CI 收紧
- 仓库瘦身：移除开发辅助脚本与站点代码；README 中英文同步

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

[Unreleased]: https://github.com/zt-Fang/EyeOpener-Translator/compare/v1.3.3...HEAD
[1.3.3]: https://github.com/zt-Fang/EyeOpener-Translator/compare/v1.3.2...v1.3.3
[1.3.2]: https://github.com/zt-Fang/EyeOpener-Translator/releases/tag/v1.3.2
[1.3.1]: https://github.com/zt-Fang/EyeOpener-Translator/releases/tag/v1.3.1
[1.3.0]: https://github.com/zt-Fang/EyeOpener-Translator/releases/tag/v1.3.0
[1.0.0]: https://github.com/zt-Fang/EyeOpener-Translator/releases/tag/v1.0.0
