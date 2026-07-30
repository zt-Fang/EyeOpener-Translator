# EyeOpener 代码审查报告

> 范围：app / data / domain / engine 四个模块共 80 个 Kotlin/Java 源文件
> 标准：语法样式 · 性能 · 安全 · 错误处理 · 代码质量 · Bug
> 日期：2026-07-10

## 总评

整体工程质量中上：密钥存储走 Android Keystore + AES/GCM（无明文硬编码），窗口生命周期（`FloatingSubtitleService`）和流式降级处理较稳健。主要问题集中在 **架构分层违规（UI 层直接做网络）、硬编码颜色/dp 违反 Theme 约定、大量裸 `catch`/部分 `!!` 与 `printStackTrace`**。

---

## 1. 安全实践 — 良好

- ✅ **未发现硬编码密钥/Token**。API Key 经 `CryptoManager`（Android Keystore + AES/GCM，IV 随机）加密后存 DataStore（`SettingsDataStore.kt:48-49,76-81`）。
- ✅ `LLMClient` 日志只打印 `provider`/`url`，不打印 `apiKey`（`LLMClient.kt:118,157,181`）。
- ⚠️ `CryptoManager.decrypt()`（`:68-87`）捕获**所有** `Exception` 静默返回 `""`。密钥轮换或数据损坏时所有密钥被悄悄清空，掩盖真实故障。建议至少 `Log.w(TAG, "解密失败", e)` 以便排查。

## 2. 错误处理

| 位置 | 问题 | 建议 |
|---|---|---|
| `ModelRepositoryImpl.kt:544` | `e.printStackTrace()` 而非 `Log`，违反 AGENTS「核心模块加 logcat」 | 改为 `Log.e(TAG, "解析 state.json 失败: ${modelDir.name}", e)` |
| `MainActivity.kt:1355` `doCheckUpdate` | 裸 `catch (e: Exception)` 只 Toast，未记录异常 | 加 `Log.e(TAG, "检查更新失败", e)` |
| `ApiSettingsScreen.kt:397` `testApi` | 吞异常且**未 `conn.disconnect()`**，连接泄漏 | 用 `try/finally { conn.disconnect() }` |
| `SubtitleManager.kt:989-1014` | `saveMediaProjectionToken` 多处 catch 仅静默/Log | 统一异常策略，关键失败应上抛或置错误态 |

## 3. Bug 检测（含潜在）

1. **`SubtitleManager.kt:138`** `resolveSherpaModelId(sourceLanguage)!!`
   当前分支已保证非空，但 `!!` 是潜在 NPE 炸弹——一旦 `when` 分支逻辑改动即崩。建议：
   ```kotlin
   val modelId = resolveSherpaModelId(sourceLanguage)
   // when 中：modelId != null → SHERPA_ONNX；else → VOSK
   // 用 modelId 而非 !!
   ```
2. **`ApiSettingsScreen.kt:384-394` `testApi`**：写 `conn.outputStream` 未 `use{}`/未 flush；且**无 `connectTimeout`/`readTimeout`**，网络慢时 UI 测试可能长时间挂起。
3. **`MainActivity.kt:1328` `doCheckUpdate`**：直接 `conn.inputStream`，非 2xx 会抛 `IOException`（拿不到错误体）；`json.getJSONArray("assets")` 无 assets 时抛异常走失败分支。应先判 `responseCode == 200` 再读流。
4. **`MainActivity.kt:1388` `startDownloadApk`**：无限 `while(true)` 轮询 `DownloadManager`，仅 `SUCCESSFUL/FAILED` 退出；若 `cursor` 长期为 `null`（查询异常）会**永久轮询**。建议加最大重试/超时上限。
5. **`LLMClient.kt:193-203` `chatStream` 降级**：流式已 emit 部分 token 后失败→降级非流式会再次 emit **完整**回复，造成「部分+完整」重复输出。建议用 `emitted` 标志去重，或捕获流式解析异常而非整体降级。

## 4. 性能优化

- `LLMClient` 每次 `translate/chat/chatStream` 多次 `.first()` 冷 collect DataStore（`llmUrl`/`llmModel`/`openAiKey`/`llmProvider`）。可用 `combine` 一次性取齐或短时缓存，减少 DataStore 读取（低优先级）。
- `SettingsDataStore` 每实例 `new CryptoManager()`，而 `CryptoManager.init` 访问 Keystore。建议单例/复用，避免重复 Keystore 交互。
- `ApiSettingsScreen` 与 `MainActivity` 用裸 `HttpURLConnection`，未复用 `LLMClient` 的 `OkHttpClient`（连接池/超时策略不统一）。建议抽统一网络层。

## 5. 代码质量（架构违规 · 重点）

- 🔴 **`MainActivity.kt`（1712 行）**：把更新检查/下载/安装网络逻辑（`doCheckUpdate`/`startDownloadApk`/`installApk`，`MainActivity.kt:1318-1451`）写成 Composable **局部函数**，直接在 UI 层做网络 IO，严重违反 AGENTS「MVVM + Clean / UI·ViewModel·Domain·Data·Engine 分层」。局部函数每次重组重建、不可单测。→ 下沉到 ViewModel/UseCase。
- 🟠 `SubtitleManager.kt`（1502 行）职责过载（语音输入、字幕、浮窗同步、MediaProjection、个性化）。建议按职责拆分。
- 🟡 `FloatingSubtitleService`（900 行）偏重，但窗口生命周期处理得当（专门 catch `BadTokenException`/`IllegalArgumentException`），相对可接受。
- 🟡 provider→URL/header 路由逻辑分散在 `LLMClient` 与 `ApiSettingsScreen.testApi`（重复写 `Authorization`/`x-api-key` header）。建议抽 `LLMRequestBuilder` 复用。

## 6. 语法和样式

- 🔴 **硬编码颜色**：`PersonalizationScreen.kt`、`ApiSettingsScreen.kt` 等大量 `Color(0xFF1A73E8)`（科技蓝）直接写死，违反 AGENTS「**所有颜色必须来自 Theme**」。虽有 `Dimens.kt`（尺寸 token），但缺颜色 token。建议建 `EyeColors`/Theme 颜色并全局引用。
- 🔴 **硬编码 dp**：`8.dp`/`1.dp`/`22.dp`/`44.dp` 等散落（如 `PersonalizationScreen.kt:295,451,459,520`），违反「**禁止写死像素/硬编码宽高**」，应归入 `Dimens`。
- 🟡 **文件名**：AGENTS 要求 `snake_case`，实际全部 `PascalCase`（如 `SubtitleManager.kt`）。这其实符合 Kotlin 惯例——建议**修订 AGENTS 而非改文件**。
- 🟡 **函数类型注解**：AGENTS「函数必须有类型注解」。多处表达式体省略返回类型（如 `suspend fun setOpenAiKey(key: String) = ...`）。建议公共函数显式返回类型。
- ✅ Room 实体 `@Entity(tableName = "tb_history")` 符合 `tb_` 前缀约定（`HistoryRecord.kt:6`）。

---

## 建议优先级

1. **P0（架构）**：`MainActivity` 网络逻辑下沉到 ViewModel/UseCase。
2. **P1（安全/稳定）**：`CryptoManager.decrypt` 补日志；`ModelRepositoryImpl` 的 `printStackTrace` 改 `Log`；`testApi` 补 `disconnect()` 与超时。
3. **P2（质量）**：`SubtitleManager.kt:138` 去掉 `!!`；`startDownloadApk` 轮询加退出上限；`chatStream` 去重。
4. **P3（样式）**：建立颜色 token、把散落 `dp` 收进 `Dimens`；统一网络层。
