# 项目概述（Product Overview）
## 产品名称
EyeOpener 实时语音翻译字幕助手（Android）

## 产品定位

一款基于实时语音识别 + 多引擎翻译 + 悬浮字幕Overlay的移动端工具，支持：

实时语音转字幕（ASR）
双语/单语悬浮显示
多翻译引擎切换（极速 / 高质量 / AI大模型）
离线 + 在线混合翻译
可扩展API翻译能力

## 核心用户场景

视频通话实时字幕
外语视频观看辅助
学习语言辅助工具


# 前端（Android App）需求设计
## UI结构
底部导航
- 字幕
- 助手
- 设置

## 字幕主界面（核心页面）
功能模块
- 悬浮字幕显示（Overlay）
实时显示识别文本
支持双语叠加
支持透明背景
支持拖动 / 缩放

- 翻译引擎切换
极速翻译（ML Kit）
精确模式
AI翻译（OpenAI / Claude / DeepSeek）

- 语言切换区域
支持搜索语言
支持语言列表左右对照


## 助手界面
- 功能
对话
- 语音输入
点击麦克风
ASR识别
自动填充输入框

## 设置界面
- 模块结构
界面语言（i18n）
本地设置
API设置
个性化设置
分享
反馈
检查更新

## 本地模型管理（重点）

- UI要求
显示下载进度（%）
实时状态刷新
删除按钮状态控制

- 约束规则
TranslationService运行时禁止删除模型
强制删除需弹窗提示：
“翻译进行中，无法删除”

- 数据清理
删除模型需同时：
删除 .onnx 文件
删除 .bpe 文件
清空数据库路径

## Overlay悬浮窗系统（样式见参考图悬浮字幕）
左上角有设置（响应个性化设置）、返回、关闭按钮
- 窗口能力
WindowManager悬浮窗
全屏覆盖支持
支持拖动
支持右下角缩放
- 字幕状态
partial（灰色临时字幕）
final（正式字幕）
- 状态持久化
位置记忆
大小记忆
启动恢复
- 动态响应
设置实时生效
UI无重启刷新

# 后端 / 核心引擎设计（On-device + API Hybrid）


### AI大模型翻译
- 支持API：
OpenAI Compatible API
Claude API
DeepSeek API



# 用户流程设计（User Journey）
```
安装APK
  ↓
开启翻译字幕
  ↓
选择翻译模式
  ↓
Mic输入  → ASR
  ↓
字幕生成
  ↓
翻译输出
  ↓
Overlay显示
```

# 权限系统设计
- 必需权限
悬浮窗权限（SYSTEM_ALERT_WINDOW）
麦克风权限
网络权限（AI翻译）
存储权限（模型下载）

# 前端开发需求总结
技术栈建议:
Kotlin + Jetpack Compose
WindowManager（Overlay）
Flow / LiveData 状态管理
Room（模型数据库）
WorkManager（下载任务）
