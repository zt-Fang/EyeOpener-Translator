# Collect logcat in real-time to a timestamped file
# Usage: powershell -File scripts\collect_logs.ps1
# Press Ctrl+C to stop

$ErrorActionPreference = "Stop"
$outDir = "logs"

if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

$ts = Get-Date -Format "yyyyMMdd_HHmmss"
$logFile = Join-Path $outDir "logcat_$ts.txt"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host " Logcat Collector" -ForegroundColor Cyan
Write-Host " Output: $logFile" -ForegroundColor Cyan
Write-Host " Press Ctrl+C to stop" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

adb logcat -c

# Filter to relevant tags only
$tags = @(
    "VoskAsrEngine:I",
    "SubtitleManager:I",
    "TranslateUseCase:I",
    "MlKitTranslationEngine:I",
    "NllbTranslationEngine:I",
    "ModelPreparer:I",
    "SettingsDataStore:I",
    "ModelRepositoryImpl:I",
    "FloatingSubtitleService:D",
    "AndroidRuntime:E"
)

$filter = ($tags -join " ") + " *:E"

Start-Process -FilePath "adb" -ArgumentList "logcat","-v","time",$filter `
    -RedirectStandardOutput $logFile -NoNewWindow -Wait
