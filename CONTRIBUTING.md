# 贡献指南

感谢关注 EyeOpener！欢迎通过 Issue / Pull Request 贡献代码或反馈问题。

## 行为准则

请保持友善、尊重所有贡献者。技术讨论对事不对人。

## 提交 Issue

- Bug 报告：使用 `Bug Report` 模板，附 logcat、设备型号、Android 版本、复现步骤
- 功能建议：使用 `Feature Request` 模板，说明使用场景与期望行为
- 模型问题：注明 ASR 引擎（X-ASR / Nemotron 3.5 / Vosk / BN Vosk）、源语言、目标语言

logcat 导出：
```powershell
cd D:\software\Android\eye
powershell -File scripts\dump_logs.ps1
```

## 提交 Pull Request

### 分支命名

- 新功能：`feat/简短描述`（如 `feat/add-french-vad`）
- Bug 修复：`fix/简短描述`
- 文档：`docs/简短描述`
- 重构：`refactor/简短描述`

### Commit 信息

遵循 [Conventional Commits](https://www.conventionalcommits.org/)：

```
<type>(<scope>): <subject>

<body>
```

- `type`：feat / fix / docs / refactor / test / chore / perf
- `scope`：可选，模块名（asr / translation / vad / ui / data / engine）
- `subject`：祈使句，首字母小写，不加句号

示例：
```
feat(asr): 支持孟加拉语 BN Vosk 模型切换
fix(overlay): 修复拖拽时窗口超出屏幕边界
docs: 更新 README 语言数至 41
```

### 代码规范

- 语言：Kotlin 优先，Java 仅兼容模块
- 风格：[Google Kotlin Style Guide](https://developer.android.com/kotlin/style-guide)
- 命名：变量/函数 camelCase，数据库表 `tb_` 前缀，API 路径 kebab-case
- 类型注解：函数必须有，复杂逻辑必须有注释
- 耗时任务：Coroutine + `Dispatchers.IO`
- UI：Jetpack Compose + Material 3 + Design Token，禁止硬编码宽高/像素/颜色
- 中文注释：核心模块开头、关键代码必须有，简洁明了

### 提交前检查

```bash
./gradlew assembleDebug        # 必须编译通过
./gradlew detekt               # 代码静态检查
./gradlew test                 # 单元测试
```

CI 会自动执行以上检查，未通过禁止合并。

### 改动说明

参考 `AGENTS.md`：
- 先分析，再拆任务
- 每完成一个核心模块 git 留存，中文命名，方便撤销
- 重大改动先开 Issue 讨论，等指示再实施

## 开发环境

- Android Studio Ladybug+
- JDK 17+
- Android SDK 36
- NDK（编译原生库）
- minSdk 24 (Android 7.0)

## 模块结构

```
app/      UI + Service + ViewModel
domain/   纯 Kotlin 领域层（Model + UseCase + 接口）
engine/   ASR / VAD / Translation 实现
data/     Room / DataStore / Repository 实现
```

**单向依赖**：上层只调用下层，禁止反向调用。Domain 层禁止依赖 Android。

## 测试

- domain 层：纯 Kotlin，必须补单元测试
- engine 层：mock Repository 测试引擎逻辑
- UI 层：暂不强制，重大交互建议补 Compose UI 测试

## 许可

提交的代码默认以 [Apache License 2.0](LICENSE) 开源。
