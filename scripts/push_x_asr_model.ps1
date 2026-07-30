# 推送 X-ASR-zh-en-960ms 模型到设备用于 PoC 验证
# 用法：
#   1. 先本地克隆模型：git lfs install; git clone https://www.modelscope.ai/Gilgamesh-J/X-ASR-zh-en.git
#   2. 修改下方 $localModelDir 为本地 chunk-960ms-model 目录路径
#   3. 设备连接后执行：powershell -File scripts\push_x_asr_model.ps1

param(
    # 本地 X-ASR 960ms 模型目录（含 encoder/decoder/joiner/tokens 4 文件）
    [string]$localModelDir = "D:\models\X-ASR-zh-en\deployment\models\chunk-960ms-model",
    # 应用包名
    [string]$packageName = "io.github.ztfang.eye",
    # 设备端模型目录名（与 SherpaOnnxModel.X_ASR_ZH_EN_960MS.modelId 一致）
    [string]$deviceModelId = "x-asr-zh-en-960ms"
)

$ErrorActionPreference = "Stop"

# 校验本地模型目录
if (-not (Test-Path $localModelDir)) {
    Write-Error "本地模型目录不存在: $localModelDir"
    Write-Host "请先克隆模型："
    Write-Host "  git lfs install"
    Write-Host "  git clone https://www.modelscope.ai/Gilgamesh-J/X-ASR-zh-en.git"
    exit 1
}

# 校验 4 个必需文件
$requiredFiles = @("encoder-960ms.onnx", "decoder-960ms.onnx", "joiner-960ms.onnx", "tokens.txt")
foreach ($f in $requiredFiles) {
    $path = Join-Path $localModelDir $f
    if (-not (Test-Path $path)) {
        Write-Error "缺少必需文件: $path"
        exit 1
    }
}

# 校验 adb
$adb = (Get-Command adb -ErrorAction SilentlyContinue)
if (-not $adb) {
    Write-Error "未找到 adb，请确认 Android SDK platform-tools 已加入 PATH"
    exit 1
}

# 校验设备
$devices = adb devices
if ($devices -match "device$") {
    Write-Host "[OK] 设备已连接" -ForegroundColor Green
} else {
    Write-Error "未检测到已连接设备，请先 adb connect 或插上 USB"
    exit 1
}

# 构造设备端目标目录
$deviceBase = "/data/data/$packageName/files/models/sherpa-onnx/$deviceModelId"

Write-Host ""
Write-Host "=== 推送 X-ASR 模型 ===" -ForegroundColor Cyan
Write-Host "本地: $localModelDir"
Write-Host "设备: $deviceBase"
Write-Host ""

# 创建目录（需 root，普通设备可改用 run-as）
adb shell "mkdir -p $deviceBase" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "[警告] 直接 mkdir 失败，尝试 run-as 模式（适用于 debug 构建）" -ForegroundColor Yellow
    $deviceBase = "/data/data/$packageName/files/models/sherpa-onnx/$deviceModelId"
    adb shell "run-as $packageName mkdir -p files/models/sherpa-onnx/$deviceModelId" 2>$null
}

# 推送 4 个文件
foreach ($f in $requiredFiles) {
    $localPath = Join-Path $localModelDir $f
    $size = (Get-Item $localPath).Length / 1MB
    Write-Host ("推送 {0} ({1:N1} MB)..." -f $f, $size) -NoNewline
    adb push $localPath "$deviceBase/$f" 2>&1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Write-Host " [OK]" -ForegroundColor Green
    } else {
        Write-Host " [失败]" -ForegroundColor Red
        Write-Error "推送失败，停止"
        exit 1
    }
}

Write-Host ""
Write-Host "=== 验证文件 ===" -ForegroundColor Cyan
adb shell "ls -la $deviceBase/"

Write-Host ""
Write-Host "=== 推送完成 ===" -ForegroundColor Green
Write-Host "下一步："
Write-Host "  1. 在 app 设置中选择 [精确模式]"
Write-Host "  2. 源语言选 [中文]"
Write-Host "  3. 开始录音，验证识别效果（关注中英混说 + 标点输出）"
Write-Host ""
Write-Host "如需查看 logcat："
Write-Host "  powershell -File scripts\dump_logs.ps1"
