# EyeOpener 修复采纳记录

> 对应报告：`docs/code-review-perf-and-bugs.md`（24 项）
> 本次工作：**采纳全部修复** + **清理无用代码（含已放弃的"太阳月亮"手动主题模式）**
> 验证：`:app:compileDebugKotlin` 与 `testDebugUnitTest` 均 **BUILD SUCCESSFUL**

---

## 一、实时链路（每帧 30ms 热路径）

| 编号 | 问题 | 处理 |
|---|---|---|
| P0-1 | `audioProcessingJob` 从未赋值，3 处 `cancel()` 是空操作 | ✅ 在 `startAudioProcessing` / `restartAudioProcessing` / `reloadAsrModel` 三处真正登记句柄；`restartAudioProcessing` 先取 `previousJob` 再启动新协程，避免"新协程取消自己" |
| P0-2 | `isRecording` 缺 `@Volatile` | ✅ `isRecording` / `audioRecord` / `inputModeEngine` 均加 `@Volatile` |
| P0-3 | 每帧重复解析 ASR 引擎类型（4 次/帧） | ✅ 进循环前缓存 `resolvedEngine` / `resolvedIsVosk`，仅 `inputModeEngine` 变化时重解析 |
| P0-4 | `updateSourceText()` 每次 partial 全量拷贝列表 | ✅ 抽出 `buildDisplayLines()`，去掉每帧 `toMutableList()` 整表拷贝 |
| P0-5 | Vosk `decodeAndGetResult()` 纯重复劳动 | ✅ Vosk 实现直接 `return ""`；循环内 `if (!resolvedIsVosk)` 才调用 |
| P0-6 | VAD 软静音提交取到"上一句"缓冲 | ✅ 新增 `currentPartialText` + `currentLineText()` 作为唯一真源；软静音提交当前显示行 |
| P0-7 | `handleFinalResult` 归档后不清缓冲 → 新 partial 拼接上一句 | ✅ final 处理不再把 text 写回 buffer；`archiveCurrentLine()` 与显示口径统一 |
| P1-8 | 热路径逐帧日志 | ✅ `handlePartialResult` / `handleFinalResult` / `VoskAsrEngine` 的日志用 `BuildConfig.DEBUG` 包裹；`TranslateUseCase.execute()` 的 3 处全文日志（partial 翻译每秒多次经过）同样包裹 |
| P1-9 | `SileroVadEngine` 每帧分配数组 | ✅ `normalized` / `windowBuffer` 提升为成员复用 |
| P1-10 | `SherpaOnnxAsrEngine.feedAudio` 每帧分配数组 | ✅ `floatBuffer` 成员复用，归一化移入 `synchronized` |
| P1-22 | `restartAudioProcessing` 释放 AudioRecord 未加 `runCatching` | ✅ 与 `reloadAsrModel` 统一 `runCatching`，旧实例先快照再置 null |

### 额外发现（不在原报告内）

**字幕 StringBuilder 状态存在跨线程竞态。** `currentSourceBuffer` / `historyLines` / `lastPartialTranslateLength` 原本同时被
**IO 录音线程**（软静音提交）与 **Main 的 ASR 流线程**（`handleFinalResult`）读写。
处理：所有缓冲清理动作收敛到 `scope.launch {}`（Main），热路径只做线程内读取。

---

## 二、UI 与交互

| 编号 | 问题 | 处理 |
|---|---|---|
| P1-11 | 助手流式回复 O(n²) 拼接 + 无节流 | ✅ `StringBuilder` 累积 + `STREAM_UI_MIN_INTERVAL_MS = 100L` 合并刷新 + 收尾强制刷新 |
| P1-12 | 历史记录无索引 | ✅ `@Entity(indices = [Index("timestamp")])`，**AppDatabase 1→2 + `MIGRATION_1_2`**，已导出 `2.json` |
| P1-13 | 导出在主线程做文件 IO | ✅ 两个导出函数改 `suspend`，拼接与写盘整体 `withContext(Dispatchers.IO)` |
| P2-14 | 临时 `CoroutineScope` 而非 `rememberCoroutineScope()` | ✅ 三处全部改为随组件生命周期取消 |
| P2-15 | composable 体内取 `System.currentTimeMillis()` | ✅ 导出文件名在点击时生成一次并存入 state，展示与写盘一致 |
| P2-16 | `runtimeError` 每次 Toast 并立刻清空 | ✅ 相同文案 5s 窗口内只弹一次（`TOAST_DEDUP_WINDOW_MS`） |

> **关于 LIMIT 的取舍**：历史页的"收藏"筛选与记录计数都在客户端基于**全量**列表计算
> （`records.filter { it.isFavorite }`、`records.size`）。若给 `getAllRecords()` 加 `LIMIT`，
> 收藏筛选会静默丢记录、计数会失真。因此本轮**只加索引、不加 LIMIT**；真正的分页需要
> 同步改造 DAO（收藏筛选下推到 SQL）+ UI 分页加载，属独立改动。

---

## 三、数据层与网络

| 编号 | 问题 | 处理 |
|---|---|---|
| P1-17 | 任意设置变更触发 3 次 Keystore 解密 | ✅ `SettingsDataStore.decryptCached()` 按密文串缓存（`ConcurrentHashMap`，上限 32 条自动清空） |
| P1-18 | 协程里 `Thread.sleep` 退避 | ✅ `downloadOneFile` 改 `suspend` + `delay(waitMs)` |
| P1-19 | `resolveApiKey` 每次编译正则 | ✅ 提到 companion `WHITESPACE_REGEX` |
| P1-20 | `isConfigLevelError` 裸子串匹配 HTTP 码 | ✅ 改用带语境的 `HTTP_STATUS_ERROR_REGEX`（要求 `api error` / `http` / `status` / `code` 前缀） |
| P1-21 | Zip Slip 路径穿越 | ✅ 新增 `data/util/SafePath.kt` 的 `resolveEntryFile()`，两个解压器统一 canonicalPath 校验 |
| P2-24 | 两个独立 OkHttpClient | ✅ `LLMClient` 改为注入共享 `OkHttpClient` 并 `newBuilder()` 派生 → **共享连接池与线程池**，仅覆盖读超时 |
| — | 顺带加固 | ✅ `HttpLoggingInterceptor` 加 `redactHeader`（Authorization / X-Naver-* / Ocp-Apim-* / api-key），HEADERS 级日志不再泄露凭据 |

---

## 四、清理的无用代码

### 用户点名的"太阳月亮模式"残留
- `app/.../ui/theme/EyeTheme.kt`：删除 `darkTheme: Boolean? = null` 参数，以及引用 DataStore `isDarkMode`
  键的过期 KDoc。改为**始终跟随系统** `isSystemInDarkTheme()`。
- 全仓库已无 `isDarkMode` / sun / moon 任何引用（唯一调用点 `MainActivity.kt` 一直是 `EyeTheme { ... }` 无参调用）。

### 其余死代码
| 位置 | 删除内容 | 判定依据 |
|---|---|---|
| `SubtitleManager` | `polishVoiceInput()` | UI 侧注释明确"不润色"，从未接线 |
| `SubtitleManager` | `_translationResult` / `translationResult` | 无任何订阅者 |
| `SubtitleState` | `asrEngineType` 字段 | 无写入点，永远是默认值（P2-23） |
| `domain/model/TranscriptionResult.kt` | 整个文件 | whisper 残留，零引用 |
| `HistoryDao` / `HistoryRepository(Impl)` | `getFavoriteRecords` / `getRecordById` | 零调用点（收藏筛选在 UI 侧做） |
| `ModelPreparer` | `copyFromAssetsIfPresent()` | 私有未使用 |
| `domain` VAD 包 | `AudioData.kt`、`VoiceSegment.kt`、`VadResult.noSpeech` / `audioData`、`VADEngine.isVoice` / `detectVoiceSegments` / `reset` | 重写 VAD 接口后全部失效 |
| `AsrEngine` | `isEndpoint()` | 无调用点 |
| `SileroVadEngine` | `SPEECH_START_FRAMES` / `SOFT_SILENCE_FRAMES` / `SUBTITLE_COMMIT_FRAMES` / `STOP_ASR_FRAMES` | 迁移到 `SubtitleManager` 后未清理 |

---

## 五、验证

### 5.1 编译与单测

```bash
# 本机 java 不在 PATH 上，用 Android Studio 自带 JBR（JDK 21）
JAVA_HOME="D:/software/Android/android/jbr" ./gradlew :app:compileDebugKotlin --offline   # BUILD SUCCESSFUL
JAVA_HOME="D:/software/Android/android/jbr" ./gradlew testDebugUnitTest --offline         # BUILD SUCCESSFUL
JAVA_HOME="D:/software/Android/android/jbr" ./gradlew assembleDebug --offline              # BUILD SUCCESSFUL
```

Room schema 已导出：`data/schemas/io.github.ztfang.eye.data.local.database.AppDatabase/2.json`，
其中索引名 `index_tb_history_timestamp` 与 `MIGRATION_1_2` 的 SQL **完全一致**（Room 会在迁移后
校验实际 schema，名称不一致会抛异常）。

新增单测 `data/src/test/.../SafePathTest.kt`（6 例，全通过）：覆盖普通相对路径、单层/深层上跳、
绝对路径、destDir 自身条目、名字含 `..` 但不越界等场景。

### 5.2 真机实测（vivo V2505A / Android 16 / API 36 / arm64-v8a）

**关键前提**：设备上原有安装包是用 **Android Debug 证书**签名的，因此可以直接用
`adb install -r` 覆盖安装 debug 构建而**保留用户数据** —— 这样才真正跑到了
`MIGRATION_1_2`，而不是一次全新装库。

| 验证项 | 方法 | 结果 |
|---|---|---|
| 覆盖安装保数据 | `apksigner` 比对签名一致后 `adb install -r` | ✅ Success |
| 启动无崩溃 | 启动后 grep logcat FATAL / AndroidRuntime / Room / SQLiteException | ✅ 无 |
| **数据库迁移真实执行** | `run-as` 拉出 `eye.db`，Python sqlite3 检查 | ✅ **见下** |
| 历史数据未丢失 | 历史页 UI + SQL count | ✅ **12 records**（与迁移前一致） |
| 索引被查询计划采用 | `EXPLAIN QUERY PLAN` | ✅ `SCAN tb_history USING INDEX index_tb_history_timestamp` |
| 音频管线可启动 | 点浮窗开关 → 检查前台服务类型 | ✅ `types=0x00000080`（MICROPHONE） |
| **取消→重启无死锁**（P0-1/P0-2/P1-22 回归） | 连续 4 次切换源语言（每次触发 ASR 重载 + 音频重启） | ✅ 4/4 成功，PID 全程不变，无 ANR |
| 重启后录音仍活跃 | `dumpsys audio` 录音会话 | ✅ `source=VOICE_RECOGNITION, 1ch 16000Hz, silenced:false, pack=io.github.ztfang.eye` |
| 停止后资源释放 | force-stop → 查活跃录音会话 | ✅ 无本应用会话 |

数据库迁移的原始输出：

```
[1] PRAGMA user_version = 2                                    PASS
[2] PRAGMA index_list(tb_history) = [(0,'index_tb_history_timestamp',0,'c',0)]
    索引列 = ['timestamp']                                     PASS
[3] tb_history 记录数 = 12                                     PASS
[4] EXPLAIN QUERY PLAN: SCAN tb_history USING INDEX index_tb_history_timestamp   PASS
总体: ALL PASS
```

> **说明**：该设备 ROM 屏蔽了应用层 logcat（缓冲区里拿不到本应用的 `Log.i`），因此
> 日志相关的改动（P1-8）无法在本机做运行时证明，只能确认编译通过与代码路径正确。
> 音频/数据库这些有系统级观测手段的项，均用 `dumpsys` / sqlite 做了客观验证。

### 5.3 真实语音端到端链路（内容正确性）

**探针选择**：每一句 final 都会经 `appendTranslationResult()` 写入 `tb_history`，因此
**历史表就是最好的观测点** —— 拉出 DB 就能看到「麦克风 → VAD → ASR → 翻译」的真实产物。

实测确实拿到了真实语音产物（设备语言设置为中文，ASR 走 X-ASR zh-en）：

```
id=13  23:24:04  '啊'
id=14  23:24:07  '， 听见我说话吗 ？'
```

对全部 14 条历史记录做重复行检测：

| 检查项 | 结果 |
|---|---|
| 完全相同的原文出现多次（P0-6 的"同一句出现两行"） | ✅ **无** |
| 全链路可产出（麦克风拾音 → 转写 → 翻译 → 落库） | ✅ 是 |

**结论**：P0-6 描述的"重复行"症状在真实语音下**未复现**；P0-7 的"历史不累积"也未复现
（`id` 持续递增，多句依次成行）。

#### 由实测数据发现的新问题（本轮已修）

同一批数据暴露了一个**原报告未覆盖**的问题：14 条记录里有 **3 条是纯标点垃圾行**：

```
id=3  16:26:39  '，'
id=8  16:37:54  '。'
id=11 22:21:10  '？'
```

根因：ASR 在纯噪声/静音段会吐只含标点的 final，而 `handleFinalResult()` 里
`if (text.isNotBlank()) translate(text)` 的守卫用 `isNotBlank()` —— **全角标点不是空白**，
于是照样触发一次完整翻译请求，并把垃圾写进历史。

修复：新增 `String.hasMeaningfulContent()`（`any { it.isLetterOrDigit() }`；CJK 汉字在
`Character.isLetterOrDigit` 下为 true，中文不受影响），在 `handleFinalResult()` **最前面**
拦截纯标点 final。放在最前面的原因：VAD 软静音提交也走同一个函数，
一处判断同时覆盖「引擎 final」与「VAD 软静音」两条入口。

> 该修复的运行时效果需要一段较长真实会话才能观察到（依赖噪声恰好产出标点 final），
> 本轮仅完成编译与安装验证。

### 5.4 debug 与 release 的实际差异

```
JAVA_HOME="D:/software/Android/android/jbr" ./gradlew assembleRelease --offline   # BUILD SUCCESSFUL (9m24s)
```

**R8 全量压缩 + 资源缩减 + lintVital 全部通过**，说明本轮改动在 release 路径上无问题
（这是第一次让改动走 R8，之前只验证过 debug）。

| 维度 | debug | release |
|---|---|---|
| APK 体积 | 110,673,772 B（106 MB） | **84,253,650 B（81 MB）**，小 24% |
| native 库占比 | — | **76.6 MB / 84.3 MB ≈ 91%** |
| R8 minify / 资源缩减 | 关 | **开**（`proguard-android-optimize.txt` + `proguard-rules.pro`） |
| `debuggable` | true（可 `run-as`） | false（**本轮最初就是因此读不到数据库**） |
| 签名 | 自动用 `~/.android/debug.keystore` | **未签名**（未配置 `signingConfigs`） |
| `BuildConfig.DEBUG` | true → 热路径日志全打印 | false → 日志被裁剪 |
| 运行时性能 | 慢（无优化、无内联） | 快（R8 优化 + 内联 + 去符号） |

**为什么体积只差 24%**：包体 91% 是 native 库（`libonnxruntime.so` 25.8 MB、
`libtranslate_jni.so` 15.5 MB、`libvosk.so` 10.0 MB …），R8 只能压 DEX/资源，
压不动 `.so`。这也是本项目"release 相比 debug 提升有限"的原因。

#### 顺带发现：32 位 ABI 缺 Sherpa/ONNX 运行库

解压 release APK 后对比两个 ABI 的 native 库清单：

| 库 | arm64-v8a | armeabi-v7a | 来源 |
|---|---|---|---|
| `libonnxruntime.so` | 25.8 MB | **缺失** | 手工提交进仓库 |
| `libsherpa-onnx-jni.so` | 4.7 MB | **缺失** | 手工提交进仓库 |
| `libvosk.so` | 10.0 MB | 8.99 MB | `vosk-android` AAR |
| `libtranslate_jni.so` | 15.5 MB | 11.2 MB | ML Kit translate AAR |
| `libeye_native.so` | 9.8 KB | 7.2 KB | 本项目 CMake（**已随死代码清理移除，见第六节第 8 条**） |

**根因（不是依赖不提供，而是仓库里就没有）**：
sherpa-onnx 的 native 库是**手工拷进仓库**的，只提交了 arm64 变体：

```
engine/src/main/jniLibs/arm64-v8a/libonnxruntime.so        (25.8 MB)
engine/src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so    (4.7 MB)
```

**`engine/src/main/jniLibs/armeabi-v7a/` 目录根本不存在**。
`engine/build.gradle.kts` 里的 `sherpa_onnx.aar` 依赖是**注释掉的**，所以没有别的地方补这两个库。
Kotlin 绑定（`engine/src/main/kotlin/com/k2fsa/sherpa/onnx/*.kt`）也是 vendored 源码。

**后果（比"功能缺失"更严重）**：

1. `com.k2fsa.sherpa.onnx.OnlineRecognizer` 的 companion `init {}` 会执行
   `System.loadLibrary("sherpa-onnx-jni")`，在**类初始化时**触发。
2. 32 位设备上 loader 只找 `armeabi-v7a/`，找不到 → 抛 **`UnsatisfiedLinkError`**。
3. `UnsatisfiedLinkError` 继承 `LinkageError` → `Error`，**不是 `Exception`**。
4. 而 `SherpaOnnxAsrEngine.init()` 里 `OnlineRecognizer(...)` 的构造被包在
   `catch (e: Exception)`（`SherpaOnnxAsrEngine.kt:151`）里 —— **捕获不到这个 Error**，
   它会逃出 `withContext`、逃出 `scope.launch`，最终**导致进程崩溃**。
5. 受影响语言 = 所有走 Sherpa 的语言：zh/en（X-ASR）+ bn（BN）+ 26 个 Nemotron 语言
   ≈ **41 种里的 29 种**。而路由是"严格唯一引擎、不跨引擎 fallback"，
   所以 zh/en 在 32 位设备上不是降级、而是**直接崩**。

**实际影响面**：仅限真正的 32 位设备（2018 年后绝大多数手机是 arm64，本机测试设备就是
arm64-v8a，不受影响）。但目前 APK 允许装到 32 位设备上，装完主语言就崩 —— 比"装不上"更糟。

**修复选项**：

| 方案 | 做法 | 代价 |
|---|---|---|
| A. 补 32 位库 | 下载 sherpa-onnx Android release，把 armeabi-v7a 的 `libonnxruntime.so` + `libsherpa-onnx-jni.so` 放进 `engine/src/main/jniLibs/armeabi-v7a/` | APK 增大 ~15-20 MB；保留 32 位支持 |
| B. 放弃 32 位 | 从 `abiFilters` 移除 `armeabi-v7a` | 32 位设备改为**装不上**（`INSTALL_FAILED_NO_MATCHING_ABIS`）；APK 减小约 19 MB |
| C. 代码加固 | 把引擎初始化处的 `catch (e: Exception)` 改成 `catch (e: Throwable)` | 无体积代价；缺库时降级为错误提示而非崩溃 |

> C 与 A/B 不冲突，建议无论选 A 还是 B 都做 C（`Error` 逃逸导致崩溃这个模式本身就该修）。

#### 已实施：B + C

**B — 仅保留 arm64-v8a**（`app/build.gradle.kts` 与 `engine/build.gradle.kts` 的 `abiFilters`）：

| | 之前 | 之后 | 变化 |
|---|---|---|---|
| debug APK | 105.5 MB | **86.1 MB** | **−19.4 MB（−18.4%）** |
| release APK | 80.4 MB | **60.9 MB** | **−19.4 MB（−24.2%）** |
| 包内 ABI 目录 | arm64-v8a + armeabi-v7a | **仅 arm64-v8a** | — |

**C — native 库加载失败不再崩溃**。三处引擎初始化都把 `catch (e: Exception)` 换成
`catch (e: Throwable)`，并新增 `engine/.../NativeLoadGuard.kt` 提供
`Throwable.isCoroutineCancellation()`：`catch (Throwable)` 会把 `CancellationException`
也捕进来，必须显式放行，否则会把协程取消误当成"模型加载失败"吞掉、破坏结构化并发。

| 文件 | 位置 | 触发 native 加载的语句 |
|---|---|---|
| `SherpaOnnxAsrEngine.kt` | `init()` | `OnlineRecognizer(...)` → `loadLibrary("sherpa-onnx-jni")` |
| `SileroVadEngine.kt` | `init()` | `Vad(...)` → 同一个库。**该函数在 App 启动阶段就被调用，所以这一处漏掉等于"一开就崩"** |
| `VoskAsrEngine.kt` | `init()` | `Model(modelPath)` → JNA 加载 `libvosk.so` |

改后行为：缺库时 `init()` 返回 `Result.failure`，由 `SubtitleManager` 走已有的
`if (initResult.isFailure)` 分支，把原因写进 `_runtimeError` 展示给用户，而不是崩溃。

**验证**：`:engine:compileDebugKotlin`、`assembleDebug`、`assembleRelease` 全部 BUILD SUCCESSFUL；
真机（arm64-v8a）安装后 `primaryCpuAbi=arm64-v8a`、无 `UnsatisfiedLinkError`、无崩溃，
主界面正常渲染，数据库仍为 v2 / 14 条记录 / 索引在位。

---

## 六、遗留与注意事项

1. **设备上现在是 debug 构建**。仓库里的 release APK（`app/release/EyeOpener.apk`、
   `app/build/outputs/apk/release/EyeOpener.apk`）经 `apksigner` 校验是 **未签名**的，
   Gradle 也未配置 `signingConfigs`。原先装的是"用 debug 证书手工签名的 release 包"。
   若要换回 release 版，需用同样的 debug 证书重新签名后 `install -r`（签名一致则数据保留）。
2. **P0-6 / P0-7 的重复行症状已在真实语音下验证未复现**（见 5.3）。但"悬浮窗同时显示
   3 行历史"这一点需要**目视确认**，本轮未截取到（浮窗内容无法用 `uiautomator` dump，
   而截图需要恰好有语音输入的瞬间）。
3. **纯标点 final 过滤（5.3）** 的运行时效果需要较长真实会话观察，本轮仅验证编译与安装。
4. **语句切分观感**：实测中出现 `'啊'` + `'， 听见我说话吗 ？'` 这样一句拆成两行、
   且第二行以逗号开头的情况。这是 VAD 软静音（约 480ms 停顿）在句中触发提交所致，
   属新的提交语义下的正常表现，但"行首标点"观感可以再优化（本轮未动，避免过度裁剪标点）。
5. **P1-12 未加 `LIMIT`**（原因见第二节）。真正的分页需同步改造 DAO 与 UI，属独立改动。
6. **P1-8 的运行时效果**：`BuildConfig.DEBUG` 是编译期常量，debug 包必然打印日志，
   因此只有 release 包才能观察到日志消失；本机 ROM 又屏蔽 logcat，故仅静态确认。
7. **32 位 ABI 问题已按"放弃 32 位 + 代码加固"处理完毕**（见 5.4）：`abiFilters` 只留
   arm64-v8a（release APK 80.4 → 60.9 MB），三处引擎初始化的 `catch (Exception)` 改为
   `catch (Throwable)` 并放行协程取消，缺 native 库时降级为错误提示而非崩溃。
   **若将来要重新支持 32 位**，需要补 `engine/src/main/jniLibs/armeabi-v7a/` 下的
   `libonnxruntime.so` + `libsherpa-onnx-jni.so`，并把 `abiFilters` 加回去。
8. **`NativeAudioProcessor` 死代码已清理完毕**：该类全项目无任何实例化点，只有
   `app/proguard-rules.pro` / `engine/consumer-rules.pro` 的 keep 规则引用它
   （所以 R8 裁不掉，一直白占体积）。已一并删除：

   | 删除项 | 位置 |
   |---|---|
   | Kotlin 类 | `engine/src/main/kotlin/.../asr/NativeAudioProcessor.kt` |
   | native 源码 + CMake | `engine/src/main/cpp/`（含 `audio_processor.c/.h`、`audio_processor_jni.cpp`、`native-lib.cpp`、`CMakeLists.txt`） |
   | 构建配置 | `engine/build.gradle.kts` 的 `externalNativeBuild` |
   | ProGuard keep 规则 | `app/proguard-rules.pro` 与 `engine/consumer-rules.pro` 各 2 条 |
   | 陈旧构建缓存 | `engine/.cxx/` |
   | 文档同步 | `README.md` / `README.en.md` 的原生库表与 NDK 前置条件 |

   连带效果：`libeye_native.so` 不再被打包，engine 模块不再有 native 构建步骤，
   且少了一处 `System.loadLibrary` 崩溃面。**删除前已备份到
   `.workbuddy/backup-native-cleanup-20260914/`** —— 因为本仓库 `.git/refs` 缺失、
   git 不可用，无法依赖版本历史找回。

9. **仓库 git 已修复（2026-09-14）**：此前 `git status` 报 `fatal: not a git repository`，
   根因是 **`.git/refs/` 目录缺失** —— git 的 `is_git_directory()` 要求 HEAD + `objects/` +
   `refs/` 三者齐全，缺 `refs/` 就直接判定"不是仓库"，报错极具误导性。
   已从完好的 reflog（`.git/logs/**` 未受损）恢复各 ref：

   | ref | 指向 |
   |---|---|
   | `refs/heads/main` | `c415e068`（style: 收敛 ktlint 违规…） |
   | `refs/heads/workbuddy/main-db0bc25e` | `beceb62c` |
   | `refs/remotes/origin/main` | `c415e068` |

   ⚠️ **最新 commit `f47b3aff`（chore: 放宽 ktlint）的对象已丢失**（pack 停留在 9/7 14:19，
   之后的对象没落盘），故 HEAD 只能落到其父提交 `c415e068`。
   好消息：`f47b3aff` 的**改动内容仍完整保留在工作区**（`.editorconfig`、`build.gradle.kts` 已 staged，
   以及 `AndroidManifest.xml` / `FloatingSubtitleService.kt` / `floating_subtitle_layout.xml` /
   `strings.xml` / `LLMTranslationEngine.kt` / `MlKitTranslationEngine.kt` 等未提交改动），
   提交一次即可一并固化。

   `.gitignore` 覆盖完好（`build/`、`app/build/`、`engine/.cxx/`、`.workbuddy/`、
   `local.properties`、`*.apk` 均已忽略），但 **`.workbuddy-ai/` 未被忽略**，提交时需留意。
