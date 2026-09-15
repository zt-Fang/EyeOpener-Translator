# v1.3.3

修掉「VPN 已开却提示本地翻译模型未就绪」这条链路上的全部误判，同时解决 release 包本地翻译必崩、悬浮窗「设置」按钮点不动、深色模式下文字不可读等问题。

---

## 修复

### 本地离线翻译（ML Kit）

- **选「云端翻译」不再去下载本地离线模型**：原先 `LOCAL, CLOUD` 合并处理，选云端也会触发本地模型下载，失败就弹「翻译模型未就绪」，纯属误伤
- **下载超时 60s → 180s，并新增 120s 轮询确认窗口**：GMS 的模型下载不随协程取消而停止，实测 60s 报失败后模型仍在后台下载完成——旧逻辑把「正在下载」误判成「失败」
- **可达性探测补全真实数据源**：原来只探 `dl.google.com`，但它只是重定向器；ML Kit 的实际链路是 `dl.google.com` → `redirector.gvt1.com` → `rN---sn-*.gvt1-cn.com`。两个域名的分流结果可以完全相反，只探入口会得出「网络没问题」的错误结论。现并行探测两者，失败详情里都写出
- **探测结果带出耗时**：两个域名都可能判定为「可达」，但实测 `dl.google.com` 走代理 2045ms、`redirector.gvt1.com` 直连 179ms，差一个数量级却完全看不出来。现在文案里写明「耗时 xxxxms」
- **不再把本地 DNS 结果说成实际出口**：TUN/VPN 下 `InetAddress.getByName` 返回的只是本地解析结果，实际路由由代理规则决定。实测本地解析到 `211.95.34.129` 但实际走的是日本节点，原文案会误导用户去乱改代理配置。措辞统一改为「本地解析为」，并注明不代表实际出口
- **DNS 解析加 5s 超时**：原先阻塞在下载之前，DNS 不响应会白等数秒
- **不支持的语言对单独分类**：原先归入「未知」，弹窗只给泛化提示，而重试永远不会成功。现在明确提示「改用云端或 AI 翻译」并隐藏重试按钮
- **诊断文案去掉 Markdown 与成因分析**：诊断入口是纯文本渲染，`**加粗**` 会把星号原样显示；分流规则这类成因分析对用户没有操作价值，只保留「探测结果 + 说明 + 原始错误」
- **失败弹窗收敛成人话**：从「标题 + 三四行长文 + 两个按钮」缩到「标题 + 一行 + 两个按钮」。原先正文里塞着 `dl.google.com`、解析到的 IP、`Timed out waiting for 300000 ms` 这类内容，用户看不懂，开发者又只能去日志里翻

### 崩溃与稳定性

- **Release 包本地翻译必崩**：`proguard-rules.pro` 里 ML Kit 的 keep 规则写成了不存在的包名 `com.google.mlkit.translate.**`（真实为 `com.google.mlkit.nl.translate.**`），规则命中 0 个类，R8 把 ML Kit 公开 API 全删了。debug 包不混淆所以无法复现，只在 release 暴露
- **native 库加载失败不再导致进程崩溃**：`System.loadLibrary` 失败抛的是 `Error`，原 `catch (Exception)` 捕不到
- **补全 armeabi-v7a 32 位 native 库，恢复双 ABI**：此前 32 位设备启动即崩 `UnsatisfiedLinkError`
- **`MainActivity` 改 `singleTask`**：原先默认 `standard`，悬浮窗反复拉起会无限堆叠 Activity 实例并丢失导航栈

### 导航

- **深链冷/热启动统一**：新增 `onNewIntent()`，读取后即从 intent 移除。此前 extra 残留导致每次重建都重复导航，实测切换 6 次后要按 6 次返回键才能退出设置页
- **悬浮窗「设置」按钮点不动**：应用存活时携带 extra 启动只切前台、不跳转

### 性能

- 录音循环缓存引擎解析结果，去掉每帧重复的 map 查找
- VAD / Sherpa 复用帧缓冲，消除每帧数组分配
- 助手流式回复改 `StringBuilder` + 100ms 合并刷新，去掉 O(n²) 拼接
- Keystore 解密结果按密文缓存，拖拽/调参不再反复触发 IPC 解密
- 导出功能移出主线程
- 历史表加 `timestamp` 索引（数据库 v1 → v2）
- `LLMClient` 补 `@Singleton`，连接池与线程池不再被多注入点各自复制
- 热路径全文日志用 `BuildConfig.DEBUG` 包裹

### 其它

- VAD 软静音改为提交「当前显示行」，修复重复行、首句永不提交、历史行不累积
- Vosk 不再重复解析同一份 partial
- 过滤 ASR 在噪声/静音段吐出的纯标点 final（实测出现过 `"？"` / `"。"` 三条垃圾行，每条都会白跑一次翻译请求）
- API 配置「保存」与「连通性测试」解耦：原先测试非 200 就永不落盘，用户以为已保存、DataStore 实为空，直接表现为「翻译没反应」
- AI 流式超时改为首 token 超时（TTFT 15s），替换原先包住整条流的 7s 总上限——旧逻辑会在长句上中途掐断，并把半截译文当成功结果写入历史
- AI 失败按超时 / 401 / 403 / 404 / 429 分类给出可行动提示
- Zip / Tar 解压增加路径穿越（Zip Slip）校验
- `isRecording` / `audioRecord` / `inputModeEngine` 加 `@Volatile`，消除停止信号不被观察的卡死路径
- `audioProcessingJob` 正确登记句柄，停止/重启不再依赖空操作的 `cancel()`

---

## 增加

- **「设置 → 翻译模型诊断」入口**：查看最近一次离线模型失败的技术细节，可清空记录
- **`GoogleReachabilityChecker`**：以 TLS 证书校验判定 Google 服务可达性（真 Google 的证书由 Google Trust Services 签发且 SAN 覆盖目标域名，劫持地址无法伪造），不依赖任何特定代理工具，也无需维护 IP 段列表
- **离线模型下载的非模态状态条**：不确定进度圈 + 已等待时长，超过 30s 自动提示「网络较慢，下载仍在继续」
- **悬浮窗紧凑状态提示**：下载中显示「下载中 · 1m12s」，失败显示「离线翻译模型未就绪」
- **失败原因分类**：`TranslationPrepFailureKind`（GMS 不可用 / 网络不可达 / 下载未完成 / 语言对不支持 / 未分类），分类给用户看一句人话
- `PRIVACY.md`、`CONTRIBUTING.md`、Issue 模板（Bug Report / Feature Request）
- GitHub Actions CI（构建 + detekt 检查）
- ktlint + detekt 静态检查
- domain 层单元测试；engine 模块新增诊断文案单测（11 例，钉死「必须写出本地解析 IP」「必须覆盖入口与数据源域名」「不得出现 Markdown 标记」等契约）
- README 顶部悬浮字幕效果截图

---

## 移除

- **深色模式（收敛为仅浅色）**：删除 `res/values-night/themes.xml` 与 `DarkColors` 全套配色。应用内本无深色开关，深色仅由系统设置被动触发，而 58 处硬编码 `Color.White` 与深色 ColorScheme 组合会产出「浅色文字压浅色底」的不可读界面
- **「离线翻译模型已就绪」Toast**：下载完成是预期结果，字幕正常出现即是信号，成功提示属于噪音
- **写进 `runtimeError` 的「翻译模型未就绪」长文案**：那条通道在主屏是 Toast，会把整段技术细节弹给用户，既与弹窗重复又看不懂
- **自研 JNI 死代码**：`NativeAudioProcessor`（全项目无实例化点）+ `engine/src/main/cpp/` + CMake 目标 `eye_native` + `externalNativeBuild` 配置 + 两处 ProGuard keep 规则
- 旧包遗留 `com/example/eye/floatingsubtitleservice.kt`
- 已放弃的应用内手动深色模式残留、`polishVoiceInput()`、`AudioData`、`VoiceSegment`、`TranscriptionResult`、`AsrEngine.isEndpoint()` 等无用代码

---

## 已知限制

- 网络环境差时模型下载仍可能超时（真机实测：冰岛语 154s 成功，威尔士语 300s 超时）。超时会给出对应分类的提示，重试通常可恢复。
- 本地离线翻译依赖 Google Play 服务，且模型由 GMS 进程下载，不走 App 自身网络栈——代理软件需要覆盖 Google 的下载域名。

实测环境：vivo V2505A / Android 16，Wi-Fi + Clash Meta TUN 模式。
