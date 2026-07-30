# AGENTS

- 用电报风格说话，允许短语，尽量少消耗tokens
- 新建文件开头、核心代码添加中文注释
- 长久解决不了的问题，联网搜索成功案例

## 代码规范

- 变量名和函数参数用camelCase
- 数据库表名用tb_前缀
- API路径用kebab-case
- 函数必须有类型注解，复杂逻辑必须有注释说明

## 一、技术栈约定

- 语言：Kotlin（优先），Java（仅兼容模块）
- UI框架：Jetpack Compose（必须优先使用）
- 架构：MVVM + Clean Architecture
- 并发：Kotlin Coroutines + Flow
- 依赖注入：Hilt
- 本地存储：Room / DataStore

---

## 二、UI设计

- 统一使用 Material 3。
- 统一使用 Design Token。
- 主色：科技蓝，蓝紫渐变
- 动效：微动效（fade + pulse）
- 需要接入数据部分先mock,等有了数据模型再接入
- 禁止硬编码宽高。
- 禁止写死像素。
- 所有颜色、圆角、间距必须来自 Theme。
- 优先响应式布局。
    适配：
    - 手机
    - 折叠屏
    - 平板
    - 悬浮窗 Overlay
---

## 三、模块划分规则
可参考
- UI Layer（Compose）
- ViewModel Layer
- Domain Layer（UseCase）
- Data Layer（Repository）
- Engine Layer（ASR / Translation / LLM）


---

## 四、开发约束

- 所有耗时任务必须使用 Coroutine + Dispatcher.IO

---

## 五、核心原则

- 核心逻辑必须可替换、可降级、可扩展


## 改动说明
先分析，再拆任务。
每完成一个核心模块 git 留存，中文命名，方便撤销。
重大改动，先说明，给建议，等指示。


# 代码审查
根据以下标准查看代码片段：
语法和样式：查找语法错误和与约定的偏差。
性能优化：提出更改建议以提高效率。
安全实践：检查漏洞和硬编码密钥（掩盖一半信息）。
错误处理：识别未处理的异常或错误。
代码质量：查找代码异味、不必要的复杂性或冗余代码。
Bug 检测：查找潜在 Bug 或逻辑错误

## 调试

核心模块要加logcat,方便后续定位和修改
cd D:\software\Android\eye
powershell -File scripts\dump_logs.ps1

