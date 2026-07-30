# 安全政策

## 报告安全漏洞

如你发现 EyeOpener 存在安全漏洞，请**不要**通过公开 Issue 上报，以免被恶意利用。

请通过以下方式私下联系维护者：
- GitHub Security Advisories：在仓库页面 `Security → Report a vulnerability` 提交
- 邮箱：zt-Fang@users.noreply.github.com

我们会在 **72 小时内**确认收到，并尽快给出修复时间表。

## 安全设计

EyeOpener 在设计上重视用户隐私：

- **API Key 本地加密**：所有翻译 / LLM 的 API Key 通过 Android Keystore + AES/GCM 加密后存入 DataStore，明文不会写入磁盘或日志。
- **离线优先**：支持纯本地 ASR（Vosk / Sherpa-ONNX）+ 本地翻译（ML Kit），不联网也能使用核心功能。
- **无远程上报**：项目不内置任何遥测 / 数据回传。

## 第三方依赖

引擎层依赖 sherpa-onnx、Vosk、Silero VAD、ML Kit 等第三方库。请保持其版本更新，相关声明见 `README.md` 第三方致谢章节。
