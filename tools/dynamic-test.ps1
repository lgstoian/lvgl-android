#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Dynamic test harness for the LVGL Android Framework sample app.

.DESCRIPTION
    Builds the debug APK, boots the emulator (if needed), installs the app,
    walks every sample screen, taps a showcase tile, captures screenshots +
    logcat, and asserts:
      - no FATAL / SIGSEGV / JNI error / ANR across the whole walk
      - every Activity launched successfully
      - LVGL initialised and the showcase screen built its tiles
    Exits non-zero on any failure so it can gate CI.

    Cross-platform: runs on Windows and on ubuntu-latest CI runners (pwsh).

    Requires: JAVA_HOME, ANDROID_HOME set; emulator AVD "test_avd".
#>
[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$ScreenshotDir = "screenshots/dynamic"
)

$RepoRoot = Split-Path $PSScriptRoot -Parent
if (-not $ApkPath) { $ApkPath = Join-Path $RepoRoot "app/build/outputs/apk/debug/app-universal-debug.apk" }
$ScreenshotDir = Join-Path $RepoRoot $ScreenshotDir

$isWindows = ($IsWindows -or ($env:OS -eq "Windows_NT"))
$adbName = if ($isWindows) { "adb.exe" } else { "adb" }
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools/$adbName" } else { $adbName }
$gradlew = if ($isWindows) { ".\gradlew.bat" } else { "./gradlew" }

$Script:Failures = @()
function Fail($msg) { $Script:Failures += $msg; Write-Host "FAIL: $msg" -ForegroundColor Red }
function Fatal($msg) { Fail $msg; throw $msg }

# Run a native command without letting a stderr warning become a terminating
# error under $ErrorActionPreference = "Stop" (PowerShell 5.1 quirk).
function Invoke-Native {
    param([scriptblock]$Action)
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try { return & $Action } finally { $ErrorActionPreference = $prev }
}

# --- Build ---
Write-Host "==> Building debug APK"
Push-Location $RepoRoot
try {
    Invoke-Native { & $gradlew assembleDebug --no-daemon 2>&1 | Out-Null }
} finally { Pop-Location }
# ABI splits: prefer the universal APK, but accept any debug APK.
if (-not (Test-Path $ApkPath)) {
    $found = Get-ChildItem (Split-Path $ApkPath -Parent) -Filter *.apk -ErrorAction SilentlyContinue |
        Sort-Object Length -Descending | Select-Object -First 1
    if ($found) { $ApkPath = $found.FullName }
}
if (-not (Test-Path $ApkPath)) { throw "APK not produced: $ApkPath" }

# --- Ensure emulator ---
$dev = Invoke-Native { & $adb devices 2>&1 }
if ("$dev" -notmatch "emulator-\d+\s+device") {
    Write-Host "==> Starting emulator"
    $emulator = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "emulator/emulator" } else { "emulator" }
    Invoke-Native { & $emulator -avd test_avd -no-window -no-audio -gpu swiftshader_indirect | Out-Null }
    $booted = $false
    for ($i = 0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 5
        $b = Invoke-Native { & $adb shell getprop sys.boot_completed 2>&1 }
        if ("$b" -match "1") { $booted = $true; break }
    }
    if (-not $booted) { throw "Emulator failed to boot" }
}

# --- Install & launch ---
New-Item -ItemType Directory -Force -Path $ScreenshotDir | Out-Null
$install = Invoke-Native { & $adb install -r $ApkPath 2>&1 }
if ("$install" -notmatch "Success") { Fatal "adb install failed: $install" }

function Start-Activity($component) {
    $out = Invoke-Native { & $adb shell am start -n $component 2>&1 }
    if ("$out" -notmatch "Starting:") { Fail "am start failed for $component : $out" }
    Start-Sleep -Seconds 8
    $name = $component.Split('/')[-1]
    Invoke-Native { & $adb shell screencap -p "/sdcard/$name.png" 2>&1 | Out-Null }
    Invoke-Native { & $adb pull "/sdcard/$name.png" (Join-Path $ScreenshotDir "$name.png") 2>&1 | Out-Null }
}

Invoke-Native { & $adb logcat -c 2>&1 | Out-Null }
Start-Activity "com.lvgl.sample/.MainActivity"

$activities = @(
    "com.lvgl.sample/.WidgetShowcaseActivity",
    "com.lvgl.sample/.LayoutShowcaseActivity",
    "com.lvgl.sample/.InteractiveDemoActivity",
    "com.lvgl.sample/.CameraSampleActivity",
    "com.lvgl.sample/.GameSampleActivity",
    "com.lvgl.sample/.LauncherSampleActivity",
    "com.lvgl.sample/.showcase.DemoShowcaseActivity",
    "com.lvgl.sample/.AppDemoActivity"
)
foreach ($a in $activities) { Start-Activity $a }

# --- Tap a showcase tile (slow press so LVGL's input read period sees it) ---
Invoke-Native { & $adb shell am start -n "com.lvgl.sample/.showcase.DemoShowcaseActivity" 2>&1 | Out-Null }
Start-Sleep -Seconds 8
Invoke-Native { & $adb shell input swipe 290 520 290 520 300 2>&1 | Out-Null }
Start-Sleep -Seconds 3
Invoke-Native { & $adb shell screencap -p /sdcard/showcase-window.png 2>&1 | Out-Null }
Invoke-Native { & $adb pull /sdcard/showcase-window.png (Join-Path $ScreenshotDir "showcase-window.png") 2>&1 | Out-Null }

# --- Assertions ---
$logFile = Join-Path $ScreenshotDir "logcat.txt"
Invoke-Native { & $adb logcat -d 2>&1 | Out-File -FilePath $logFile }
$log = Get-Content $logFile -Raw
if ($log -match "FATAL|SIGSEGV|JNI DETECTED ERROR|start timeout|ANR in com\.lvgl") {
    Fail "Crash/ANR detected in logcat"
}
if ($log -notmatch "LVGL initialised") { Fail "LVGL did not initialise" }
if ($log -notmatch "Showcase built: 4 tiles") { Fail "Showcase screen did not build its tiles" }

# Layout lint: every screen reports overlaps/out-of-bounds/clipped text with
# this tag after its first refresh. Any warning fails the walk.
$lint = Invoke-Native { & $adb logcat -d -s LVGLLayout 2>&1 }
if ("$lint" -match "LVGLLayout") {
    $lint.ToString().Split("`n") | Select-Object -First 12 | ForEach-Object { Write-Host "  $_" }
    Fail "Layout lint reported issues (see LVGLLayout above)"
}

if ($Script:Failures.Count -gt 0) {
    Write-Host "`n==> DYNAMIC TEST FAILED ($($Script:Failures.Count) failures)" -ForegroundColor Red
    exit 1
}
Write-Host "`n==> DYNAMIC TEST PASSED" -ForegroundColor Green
