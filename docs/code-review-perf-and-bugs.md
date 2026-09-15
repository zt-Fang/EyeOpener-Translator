# EyeOpener 代码审查：性能瓶颈与潜在 Bug

> 审查范围：`app/`、`domain/`、`data/`、`engine/` 四个模块的 Kotlin 源码（不含 build 产物与测试）。
> 重点：实时音频链路（AudioRecord → VAD → ASR → 翻译 → 悬浮窗）以及数据层。
> 标注约定：**P0** = 会导致功能异常或卡死；**P1** = 明确性能损耗 / 稳定性风险；**P2** = 健壮性、可维护性。

---

## 一、实时链路（每帧 30ms 执行的热路径）

### P0-1 `audioProcessingJob` 从未被赋值，三处 `cancel()` 全是空操作

- 位置：`SubtitleManager.kt:1259`（声明）、`:630`、`:1382`、`:1685`（调用）
- 事实：全项目搜索 `audioProcessingJob`，只有声明与 3 次 `?.cancel()`，**没有任何赋值点**。
- 影响链：
  1. `stopAudioProcessing()` / `restartAudioProcessing()` / `reloadAsrModel()` 都指望它来终止录音循环，实际是 no-op。
  2. 录音循环真正的退出条件是 `isRecording == false`（`while (isRecording)`）。
  3. 而 `isRecording` 未加 `@Volatile`（见 P0-2），停止信号存在不被观察到的可能。
  4. 录音循环整体跑在 `audioMutex.withLock { ... }` 内部（见 `startAudioProcessing`），一旦循环不退出，`restartAudioProcessing` 的 `withLock` 将**永久阻塞**，该协程泄漏且音频再也起不来。
- 修复：`audioProcessingJob = scope.launch(Dispatchers.IO) { audioMutex.withLock { startAudioProcessingLocked() } }`；或删除无效的 `cancel()` 调用，只保留标志位 + mutex 语义，避免误导。

### P0-2 热路径关键标志 `isRecording` 缺少 `@Volatile`

- 位置：`SubtitleManager.kt:1258`（`private var isRecording = false`）、`:1257`（`audioRecord`）
- 事实：写方在 `Dispatchers.IO`（录音协程、`stopAudioProcessing`、`restartAudioProcessing`）；读方分散在 `Dispatchers.Main.immediate`（`ensureModelsLoaded`、`observeModelDownloads`、`audioSource.collect`、`stopVoiceInput`）以及 IO 录音循环。
- 影响：无内存屏障，JIT 可能把 `while (isRecording)` 优化为寄存器读 → 停止指令延迟生效甚至不生效（release 构建风险更高）。
- 佐证：同文件其它跨线程共享字段（`cachedModelStates`、`lastFinalTimeMs`、`lastResolvedEngine`）都标了 `@Volatile`，唯独这个最关键的漏了。
- 修复：`@Volatile private var isRecording = false`（`audioRecord` 同理）。

### P0-3 每帧重复解析 ASR 引擎类型

- 位置：`SubtitleManager.kt:1590`、`:1616`（`isVoskMode` 算 2 次）、`:1583`、`:1611-1612`（`currentAsrEngine` 取 2 次）
- 事实：`currentAsrEngineType` → `resolveAsrEngine` → `AsrRoutingTable.engineFor` → `forLanguage`（map 查找，未命中还会 `new AsrRoute`）。30ms/帧 ≈ 33Hz，每帧 4 次。
- 修复：进入 `while (isRecording)` 前缓存 `val engine = currentAsrEngine`、`val isVosk = engine === voskAsrEngine`；语种切换时通过 `reloadAsrModel` 重新进入循环即可自然刷新。

### P0-4 `updateSourceText()` 每次 partial 全量拷贝列表并新建对象

- 位置：`SubtitleManager.kt:733-744`
- 事实：`historyLines.toMutableList()` → `add` → 可能 `takeLast` → 构造 `SubtitleLine` → `current.copy(lines = ...)`。Vosk 的 partial 每帧都发，等于 33 次/秒的整表拷贝 + 多对象分配，直接喂给 GC。
- 修复：`MAX_HISTORY_LINES` 只有 3，用固定容量数组/环形缓冲就地更新，或 `ArrayDeque` + `subList` 复用；避免每帧 `toMutableList()`。

### P0-5 Vosk 模式下 `decodeAndGetResult()` 是纯重复劳动

- 位置：`SubtitleManager.kt:1611-1612` 调用；`VoskAsrEngine.kt:132-166` 实现
- 事实：`VoskAsrEngine.feedAudio()` 内部已经 `getPartialResult()` + 正则解析 + `tryEmit(partial)`；循环紧接着又调 `decodeAndGetResult()`，Vosk 实现里**再** `getPartialResult()` + 正则解析一次，返回值被丢弃。且两者都 `synchronized(lock)`，串行执行。
- 修复：Vosk 的 `decodeAndGetResult()` 直接 `return ""`；或在循环里按引擎分支跳过。

### P0-6 VAD 软静音提交取的是"上一句"缓冲 → 重复行 / 当前句永不提交

- 位置：`SubtitleManager.kt:1591-1597`（触发）与 `:697-745`、`:581-595`（缓冲写入点）
- 事实：`currentSourceBuffer` **只**在 `updateSourceText(isFinal = true)` 里被写入（`:705-706`）；partial 分支（`:709-722`）从不更新它。
- 后果：VAD 480ms 软静音时 `handleFinalResult(currentSourceBuffer.toString())` 拿到的是**上一句 final 的文本**（或首次为空串），不是当前正在显示的 partial。
  - 首句场景：buffer 为空 → 条件不成立 → 该句永不提交。
  - 非首句场景：把上一句 archive 一次、又作为新 final 行显示一次 → 同一句出现两行。
- 修复：软静音应提交"当前行正在显示的文本"，即 `currentSourceBuffer + 最近一次 partial`；建议显式维护一个 `currentLineText` 作为唯一真源。

### P0-7 `handleFinalResult` 归档后不清空缓冲 → 新 partial 拼接上一句

- 位置：`SubtitleManager.kt:598-618`
- 事实：顺序为 `if (buffer.isNotEmpty()) archiveCurrentLine()`（清空 buffer）→ `updateSourceText(text, isFinal = true)`（`:705-706` **重新把 text 写回 buffer**）。
- 后果：
  - 下一帧 partial 的显示文本 = `上一句 final + 新 partial`（`:727`）。
  - 更严重的是 Sherpa 路径下 buffer 在 final 时通常为空（partial 不写 buffer）→ `archiveCurrentLine()` 从不触发 → 历史行永不累积 → 上一句被新 partial **整行覆盖**，注释里承诺的"两行交替 / 历史保留 3 行"实际不生效。
- 修复：明确 buffer 语义只取其一（partial 累积 or final 累积），并让 archive 与显示口径一致；final 处理完把 buffer 置空，而不是写回。

### P1-8 热路径上的逐帧日志

- 位置：`SubtitleManager.kt:582`、`:599`（每帧 `Log.d` 完整文本）；`VoskAsrEngine.kt:141`、`:147`（每 partial `Log.d` 完整文本）；`TranslateUseCase.kt:24`、`:43`、`:45`（每次翻译 `Log.i` 完整文本，partial 翻译同样走这里）
- 事实：作者已经为 `resolveAsrEngine` 的日志刷屏做过抑制（`SubtitleManager.kt:384-401` 注释明确写"实测一次会话刷出 1900+ 行，严重拖慢整机"），但上述几处漏了。
- 修复：改为 `if (BuildConfig.DEBUG)` 包裹，或降级为不带正文的计数日志。

### P1-9 `SileroVadEngine.processAudio` 每帧分配数组

- 位置：`SileroVadEngine.kt:147-150`（每帧 `FloatArray(480)`）、`:160`（每窗口 `FloatArray(512)`）
- 事实：约 33 次/秒 + 15 次/秒的小数组分配，全部落在录音线程上。
- 修复：把归一化缓冲与窗口缓冲提升为成员变量复用。

### P1-10 `SherpaOnnxAsrEngine.feedAudio` 每帧分配数组

- 位置：`SherpaOnnxAsrEngine.kt:202-205`
- 事实：`FloatArray(samples.size) { samples[i] / 32768.0f }`，33 次/秒。
- 修复：成员缓冲 + 循环赋值复用。

---

## 二、UI 与交互性能

### P1-11 助手流式回复：O(n²) 拼接 + 整表拷贝 + 无节流重组

- 位置：`AssistantViewModel.kt:78-96`
- 事实：每个 token 都执行 `accumulatedText += token`（String 不可变，逐 token 拼接是 O(n²) 分配）、`_messages.value.toMutableList()`（复制整个消息列表）、`_messages.value = updated`（StateFlow 每 token 发一次 → Compose 每 token 重组一次）。
- 对照：`SubtitleManager.translateWithPolishAndContext` 已经做了 `STREAM_UI_MIN_INTERVAL_MS = 100L` 的节流（`:921-924`），助手侧没有。
- 修复：`StringBuilder` 累积 + 100ms 合并刷新。

### P1-12 历史记录：无索引 + 无分页 + 每次插入全量重发

- 位置：`HistoryRecord.kt:7-15`（`timestamp` 无 `@Index`）、`HistoryDao.kt:13-14`（`ORDER BY timestamp DESC` 无 LIMIT）、`SubtitleManager.kt:1026-1036`（每句 final 都 insert）
- 事实：每完成一句翻译就写库 → Room 的 `getAllRecords()` Flow 全量重发 → `HistoryScreen` 全量重组。记录上千条时 `ORDER BY` 还会退化为全表扫描 + 排序。
- 修复：`@Entity(tableName = "tb_history", indices = [Index("timestamp")])`；列表加 `LIMIT` / Paging；或只在历史页可见时订阅。

### P1-13 导出功能在主线程做文件 IO

- 位置：`HistoryScreen.kt:526`（`exportRecord`）、`:552`（`exportAllRecords`）
- 事实：两者由 `onClick` 直接调用，在主线程拼接字符串、`FileOutputStream.write`，随后 `startActivity`。`exportAllRecords` 的数据量随记录数线性增长 → ANR 风险。
- 修复：切到 `Dispatchers.IO`，完成后回主线程 Toast / 起分享。

### P2-14 历史页使用临时 `CoroutineScope` 而非 `rememberCoroutineScope()`

- 位置：`HistoryScreen.kt:152`、`:163`、`:223`
- 事实：`CoroutineScope(Dispatchers.IO).launch { ... }` 与组件生命周期无关、无法取消；组件销毁后仍可能写库。
- 修复：改用 `rememberCoroutineScope()`。

### P2-15 composable 体内调用 `System.currentTimeMillis()`

- 位置：`HistoryScreen.kt:195`
- 事实：每次重组都重新取值，弹窗中显示的文件名会跳动；实际写盘用的是 `exportAllRecords` 内部另取的时间戳（`:550`），两者不一致。
- 修复：在确认导出时生成文件名并保存到状态。

### P2-16 `LaunchedEffect(runtimeError)` 每次错误都 Toast 并立刻清空

- 位置：`MainActivity.kt:743-748`
- 事实：悬浮窗也订阅同一个 `runtimeError` 来常驻显示失败原因（`FloatingSubtitleService.kt:283-295`），MainActivity 一清，悬浮窗文案同步消失。流式中断会写入 `"译文可能不完整（流式连接中断）"`（`SubtitleManager.kt:943`），连续多句时可反复弹 Toast。
- 修复：错误对象带序号/时间戳做 Toast 去重；区分"仅 Toast"与"需常驻悬浮窗"两类错误。

---

## 三、数据层与网络

### P1-17 任意设置变更都会触发 3 次 Keystore 解密

- 位置：`SettingsDataStore.kt:47-51`（三个 `crypto.decrypt` 的 `map`）、`CryptoManager.kt:45-63`
- 事实：`dataStore.data` 是单一共享流，任何 key 变化都会让所有 `.map {}` 重跑。`openAiKey` / `claudeKey` / `cloudTranslationApiKey` 各自执行一次 Android Keystore AES-GCM 解密（Binder/IPC 级开销）。
- 触发场景：悬浮窗拖拽/缩放结束写 `overlayX/Y/Width/Height`（`FloatingSubtitleService.kt:617-635`）、个性化滑杆写 `fontSize/backgroundTransparency`（`SettingsDataStore.kt:126-136`）——每次写入都连带 3 次 Keystore 解密。
- 修复：按密文串做解密结果缓存（`ConcurrentHashMap<String, String>`）；或在 `map` 前加 `distinctUntilChanged()`。

### P1-18 `ModelRepositoryImpl` 在协程里用阻塞 `Thread.sleep` 退避

- 位置：`ModelRepositoryImpl.kt:494`（`Thread.sleep(waitMs)`，最长 `3s × 4` 次）
- 事实：`downloadOneFile` 处于 `Dispatchers.IO`，阻塞 sleep 会占住一个 IO 线程。
- 修复：改为 `kotlinx.coroutines.delay(waitMs)`（需要把函数提升为 `suspend`，或把重试循环移到挂起上下文）。

### P1-19 `LLMClient.resolveApiKey` 每次调用都编译正则

- 位置：`LLMClient.kt:738`（`replace(Regex("[\\r\\n\\s]+"), "")`）
- 事实：翻译每句都会走 `translate` / `chatStream` → `resolveApiKey` → 新建 `Regex`。
- 修复：提到 companion 常量 `private val WS_REGEX = Regex("[\\r\\n\\s]+")`。

### P1-20 `isConfigLevelError` 用裸子串匹配 HTTP 码

- 位置：`LLMClient.kt:859-872`（`"401"`、`"403"`、`"404"`、`"429"`）
- 事实：响应体里任何含这几个数字的文本（token 计数、模型名、时间戳）都会被判为"配置级错误"，从而跳过非流式降级。
- 修复：匹配 `"HTTP 401"` / `"error 401"` / `"status 401"`，或先解析 JSON 的 `error` 字段再判断。

### P1-21 Zip/Tar 解压存在路径穿越（Zip Slip）

- 位置：`ZipExtractor.kt:39`、`TarBzipExtractor.kt:51`
- 事实：`File(destDir, entryName)` 直接用压缩包内条目名拼路径，未做归一化校验。条目名含 `../` 时可写出 `filesDir` 之外。下载虽有 host 白名单（`ModelRepositoryImpl.kt:1168-1182`），但模型包本身无签名/校验和（`:619` 注释明确"不做魔数校验"）。
- 修复：`val out = File(destDir, entryName).canonicalFile; require(out.path.startsWith(destDir.canonicalPath + File.separator))`。

### P1-22 `restartAudioProcessing` 释放 AudioRecord 未加 `runCatching`

- 位置：`SubtitleManager.kt:1383-1384`
- 事实：`audioRecord?.stop()` / `release()` 未包裹异常处理；对比 `reloadAsrModel`（`:631-632`）都包了 `runCatching`。AudioRecord 在非 start 状态下 release 会抛 `IllegalStateException`，导致整个重启协程中断，音频再也起不来。
- 修复：与 `reloadAsrModel` 保持一致，统一 `runCatching`。

### P2-23 `SubtitleState.asrEngineType` 是死字段

- 位置：`SubtitleState.kt:32`
- 事实：全项目没有任何写入点，永远是默认 `AsrEngineType.VOSK`。UI 若读它会显示错误引擎。
- 修复：移除，或在语种/引擎切换时同步。

### P2-24 两个独立的 OkHttpClient

- 位置：`AppModule.kt:93`（读超时 60s，供云端翻译）、`LLMClient.kt:222`（读超时 30s，供 LLM）
- 事实：连接池与线程池各一份，两者无法复用连接。
- 修复：统一为一个（超时按调用点覆盖），或至少共享 `ConnectionPool` 与 `Dispatcher`。

---

## 四、优先级建议（按投入产出排序）

| 顺序 | 条目 | 理由 |
|---|---|---|
| 1 | P0-2 `isRecording` 加 `@Volatile` | 一行改动，消除停止失效/卡死风险 |
| 2 | P0-1 修正 `audioProcessingJob` | 消除死代码与阻塞隐患 |
| 3 | P0-6 / P0-7 缓冲语义统一 | 直接修掉"重复行 / 历史不累积"的用户可见 Bug |
| 4 | P0-3 / P0-5 循环内缓存与去重 | 立竿见影降低每帧开销 |
| 5 | P0-4 / P1-9 / P1-10 消除每帧分配 | 降低 GC 压力与音频线程抖动 |
| 6 | P1-11 助手流式节流 | 长回复卡顿的主要来源 |
| 7 | P1-12 历史索引 + 分页 | 记录量大后最明显的卡顿点 |
| 8 | P1-17 Keystore 解密缓存 | 拖拽/调参时的隐性卡顿 |
| 9 | P1-21 Zip Slip 校验 | 安全加固，成本低 |
| 10 | P1-13 / P2-14 / P2-16 UI 侧收尾 | 消除 ANR 与协程泄漏 |

---

## 五、说明

- P0-6 / P0-7 基于对 `currentSourceBuffer` 全部读写点的静态追踪得出，**建议先用一次真机 Vosk 会话验证**（观察日志 `handleFinalResult` 的 `text` 与 `currentSourceBuffer` 是否一致、历史行是否累积）再动手改。
- 其余条目均可直接从代码确认。
- 未覆盖：Compose 重组层面的细粒度问题、native（`eye_native`）实现、`com/k2fsa/sherpa/onnx` 第三方绑定代码。
