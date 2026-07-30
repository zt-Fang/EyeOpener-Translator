# Dump current logcat buffer to timestamped files (FULL + FILTERED)
# Usage: powershell -File scripts\dump_logs.ps1

$ErrorActionPreference = 'Stop'
$outDir = 'logs'
$adb    = 'd:\software\android sdk\platform-tools\adb.exe'

if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }

$ts         = Get-Date -Format 'yyyyMMdd_HHmmss'
$fullFile   = Join-Path $outDir ('logcat_' + $ts + '_FULL.txt')
$filterFile = Join-Path $outDir ('logcat_' + $ts + '_FILTERED.txt')

Write-Host 'Exporting logcat buffer...' -ForegroundColor Cyan
Write-Host ('  FULL    : ' + $fullFile)
Write-Host ('  FILTERED: ' + $filterFile)

# ---------------- adb & device check ----------------
if (-not (Test-Path $adb)) {
    Write-Host ('[ERROR] adb not found: ' + $adb) -ForegroundColor Red
    exit 1
}
[array]$devList = @(& $adb devices 2>&1)
$devCount = 0
foreach ($line in $devList) {
    if ($line -match "\tdevice$") { $devCount++ }
}
Write-Host ('  adb devices: online = ' + $devCount)
foreach ($line in $devList) {
    if ($line.Trim().Length -gt 0) { Write-Host ('    ' + $line) }
}
if ($devCount -lt 1) {
    Write-Host '[ERROR] No Android device. Connect via USB with USB debugging enabled.' -ForegroundColor Red
    exit 1
}
if ($devCount -gt 1) {
    Write-Host '[WARN] Multiple devices. First device used. Set env ANDROID_SERIAL to choose.' -ForegroundColor Yellow
}

# ---------------- core TAGs ----------------
$coreTags = @(
    'MainActivity'
    'FloatingSubtitleService'
    'SubtitleManager'
    'ModelRepository'
    'SettingsViewModel'
    'LocalModelsScreen'
    'ASREngine'
    'TranslationEngine'
    'LLMService'
    'NativeAudio'
    'VadEngine'
    'PermissionHelper'
    'ModelPreparer'
    'SubtitleOverlay'
    'AppUpdateMgr'
)

$escapedTags = ($coreTags | ForEach-Object { [regex]::Escape($_) }) -join '|'
$tagPattern  = '(?i)(^|\s|:|\t)(' + $escapedTags + ')(\s|:|$)'

# 1) Pull full logcat
& $adb logcat -d -v time 2>&1 | Out-File -FilePath $fullFile -Encoding utf8

$fullLen = (Get-Item $fullFile).Length
Write-Host ('  adb pull done. raw size = ' + $fullLen + ' bytes')
if ($fullLen -lt 50) {
    Write-Host '[WARN] Pulled logcat <50 bytes. Reproduce the bug first on the phone.' -ForegroundColor Yellow
    'EMPTY_LOGCAT_OR_NO_DEVICE' | Out-File -FilePath $filterFile -Encoding utf8
    exit 0
}

# 2) Filter
$lines  = @(Get-Content -Path $fullFile -Encoding UTF8)
$result = New-Object System.Collections.Generic.List[string]
$prevTs = $false
foreach ($line in $lines) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $isTs = ($line -match '^\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}')

    $hit = $false
    if ($line -match $tagPattern)                       { $hit = $true }
    elseif ($line -match '(?i)\s+[EFW]\s+')             { $hit = $true }
    elseif ($line -match '(?i)\b(AndroidRuntime|DEBUG)\b') { $hit = $true }
    elseif ($prevTs -and -not $isTs)                     { $hit = $true }

    if ($hit) { [void]$result.Add($line) }
    $prevTs = $isTs
}
$result | Out-File -FilePath $filterFile -Encoding utf8

# 3) Summary
function KBof($p) {
    if (Test-Path $p) {
        $kb = [double]((Get-Item $p).Length) / 1024.0
        return ($kb.ToString('0.0').PadLeft(7))
    }
    return '   0.0'
}

Write-Host ''
Write-Host 'Done.' -ForegroundColor Green
$line1 = 'FULL    : ' + $fullFile   + '  (' + (KBof $fullFile)   + ' KB, ' + $lines.Count  + ' lines)'
$line2 = 'FILTERED: ' + $filterFile + '  (' + (KBof $filterFile) + ' KB, ' + $result.Count + ' lines)'
Write-Host $line1
Write-Host $line2
Write-Host ''
Write-Host ('Core TAGs (' + $coreTags.Count + '). Search these in FILTERED file:') -ForegroundColor Cyan
Write-Host '  [INIT_STATE_FIX] [INIT_SHERPA_FIX] [INIT_VOSK_FIX]   -> app start, DOWNLOADING/ERROR residual fix'
Write-Host '  [START] sherpa-files  -> download started'
Write-Host '  [STATE_CHANGE]       -> Data layer state transitions'
Write-Host '  [RESULT] [FAIL]      -> VM layer download result / exception'
Write-Host '  [ALL_MODELS]         -> VM layer allModels Flow snapshot'
Write-Host '  [CLEANUP]            -> VM layer removed from progressMap'
Write-Host '  [UI_SHERPA] [UI_VOSK]-> UI layer per-card final decision'
