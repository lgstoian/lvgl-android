#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Dynamic test harness for the LVGL Android Framework sample app.

.DESCRIPTION
    Builds the debug APK (release runs use a prebuilt signed APK), installs the
    app, walks every sample screen, taps a showcase tile, captures screenshots +
    logcat, and asserts:
      - no crash or ANR in the sample app across the whole walk
      - every Activity launched successfully
      - LVGL initialised and the showcase screen built its tiles
      - no LVGLLayout warnings
      - the installed package versionName matches the APK under test
    Exits non-zero on any failure so it can gate CI.

    -Variant (<Alias: ExpectedVariant>) selects and enforces the build variant.
    It is inferred from -ApkPath when that points at an apk/<variant>/ folder,
    and defaults to debug for local runs. Explicit release runs never fall back
    to an arbitrary APK. -SmokeOnly walks only MainActivity and the showcase
    screen for a fast release-regression check.

    Cross-platform: runs on Windows and on ubuntu-latest CI runners (pwsh).

    Requires: JAVA_HOME, ANDROID_HOME set; emulator AVD "test_avd".
#>
[CmdletBinding()]
param(
    [string]$ApkPath,
    [Alias("ExpectedVariant")]
    [ValidateSet("debug", "release")]
    [string]$Variant,
    [string]$ScreenshotDir = "screenshots/dynamic",
    [switch]$SmokeOnly
)

$RepoRoot = Split-Path $PSScriptRoot -Parent

# $IsWindows is a read-only automatic variable in PowerShell 7, and PowerShell
# variables are case-insensitive: assigning to $isWindows also fails on Linux.
# Derive a distinctly named variable instead.
$runningOnWindows = ($PSVersionTable.Platform -eq 'Win32NT') -or ($env:OS -eq 'Windows_NT')
$adbName = if ($runningOnWindows) { "adb.exe" } else { "adb" }
$adb = if ($env:ANDROID_HOME) { Join-Path $env:ANDROID_HOME "platform-tools/$adbName" } else { $adbName }
$gradlew = if ($runningOnWindows) { ".\gradlew.bat" } else { "./gradlew" }

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

# --- Resolve and validate the artifact under test (before touching a device) ---
$variantFromPath = $null
if ($ApkPath -and $ApkPath -match '[\\/]apk[\\/](debug|release)[\\/]') { $variantFromPath = $Matches[1] }
$variantSpecified = -not [string]::IsNullOrWhiteSpace($Variant)
if (-not $variantSpecified) { $Variant = if ($variantFromPath) { $variantFromPath } else { "debug" } }
$strictVariant = $variantSpecified -or ($null -ne $variantFromPath)

if ($variantFromPath -and $variantFromPath -ne $Variant) {
    Fatal "APK path '$ApkPath' is a $variantFromPath artifact, but -Variant $Variant was requested"
}

if ($ApkPath) {
    if (-not [System.IO.Path]::IsPathRooted($ApkPath)) { $ApkPath = Join-Path $RepoRoot $ApkPath }
} else {
    $ApkPath = Join-Path $RepoRoot "app/build/outputs/apk/$Variant/app-universal-$Variant.apk"
}
$ScreenshotDir = Join-Path $RepoRoot $ScreenshotDir

Write-Host "==> Variant under test: $Variant"
Write-Host "==> APK under test: $ApkPath"
if ($SmokeOnly) { Write-Host "==> Mode: release/static smoke (trimmed walk)" } else { Write-Host "==> Mode: full screen walk" }

if (-not $strictVariant -or $Variant -eq "debug") {
    # Backward compatibility for local debug runs: build debug first. Release
    # artifacts are built and signed by the release workflow, never here.
    if ($Variant -eq "debug") {
        Write-Host "==> Building debug APK"
        Push-Location $RepoRoot
        try {
            Invoke-Native { & $gradlew assembleDebug --no-daemon 2>&1 | Out-Null }
        } finally { Pop-Location }
    }
}

if (-not (Test-Path $ApkPath)) {
    if ($strictVariant -and $Variant -eq "release") {
        Fatal "Release APK not found at '$ApkPath'. Explicit release runs never accept a different APK."
    }
    # Local debug compatibility: a renamed split APK used to be accepted.
    $found = Get-ChildItem (Split-Path $ApkPath -Parent) -Filter *.apk -ErrorAction SilentlyContinue |
        Sort-Object Length -Descending | Select-Object -First 1
    if ($found) { $ApkPath = $found.FullName }
}
if (-not (Test-Path $ApkPath)) { Fatal "APK not produced: $ApkPath" }

function Get-ApkVersionName([string]$apk) {
    $aaptName = if ($runningOnWindows) { "aapt.exe" } else { "aapt" }
    $aapt = $null
    if ($env:ANDROID_HOME) {
        $aapt = Get-ChildItem (Join-Path $env:ANDROID_HOME "build-tools") -Recurse -File `
            -Filter $aaptName -ErrorAction SilentlyContinue |
            Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $aapt) {
        $found = Get-Command $aaptName -ErrorAction SilentlyContinue
        if ($found) { $aapt = $found.Source }
    }
    if (-not $aapt) { Fatal "aapt ($aaptName) not found under ANDROID_HOME/build-tools" }
    $badging = Invoke-Native { & $aapt dump badging $apk 2>&1 }
    $match = [regex]::Match("$badging", "versionName='([^']*)'")
    if (-not $match.Success) { Fatal "Cannot read versionName from APK: $apk" }
    return $match.Groups[1].Value
}

$artifactVersion = Get-ApkVersionName $ApkPath

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
# On the unaccelerated CI emulator `adb install` occasionally returns a broken
# pipe while the package service is busy. Retry a healthy device rather than
# reporting a false app failure; a signature mismatch still forces a reinstall.
$install = ""
for ($installAttempt = 1; $installAttempt -le 3; $installAttempt++) {
    $install = Invoke-Native { & $adb install -r $ApkPath 2>&1 }
    if ("$install" -match "Success") { break }
    Write-Host "==> install attempt $installAttempt failed; retrying: $install"
    if ("$install" -match "signature|SIGNATURE|UPDATE_INCOMPATIBLE|VERSION_DOWNGRADE") {
        Invoke-Native { & $adb uninstall com.lvgl.sample 2>&1 | Out-Null }
    }
    Start-Sleep -Seconds 5
}
if ("$install" -notmatch "Success") { Fatal "adb install failed: $install" }

# The installed package must be the artifact we just tested; a stale install or
# a wrong split would otherwise make the walk pass against different bits.
$dumpsys = Invoke-Native { & $adb shell dumpsys package com.lvgl.sample 2>&1 }
$versionMatch = [regex]::Match("$dumpsys", "\bversionName=([^\s]+)")
if (-not $versionMatch.Success) { Fatal "Cannot read installed versionName from: $dumpsys" }
$installedVersion = $versionMatch.Groups[1].Value.Trim()
Write-Host "==> versionName: artifact='$artifactVersion', installed='$installedVersion'"
if ($installedVersion -ne $artifactVersion) {
    Fatal "Installed versionName '$installedVersion' does not match APK '$artifactVersion' ($ApkPath)"
}

# Grant the optional CAMERA permission up front. Without it the camera sample
# pops a runtime permission dialog in the middle of the walk, which pauses the
# app and derails the screen sequence.
Invoke-Native { & $adb shell pm grant com.lvgl.sample android.permission.CAMERA 2>&1 | Out-Null }

$screenIds = @{
    "com.lvgl.sample/.WidgetShowcaseActivity" = 1
    "com.lvgl.sample/.LayoutShowcaseActivity" = 2
    "com.lvgl.sample/.InteractiveDemoActivity" = 3
    "com.lvgl.sample/.CameraSampleActivity" = 4
    "com.lvgl.sample/.GameSampleActivity" = 5
    "com.lvgl.sample/.LauncherSampleActivity" = 6
    "com.lvgl.sample/.showcase.DemoShowcaseActivity" = 7
    "com.lvgl.sample/.AppDemoActivity" = 1000
}

function Start-Activity($component, $shotName = "") {
    # A fresh process per screen keeps the software emulator from stalling
    # System UI after nine back-to-back launches and avoids black captures.
    Invoke-Native { & $adb shell am force-stop com.lvgl.sample 2>&1 | Out-Null }
    Start-Sleep -Seconds 2
    $screenId = $screenIds[$component]
    if ($screenId) {
        # Secondary activities are internal (android:exported=false). The
        # exported MainActivity forwards SMOKE_SCREEN to the real target via an
        # explicit Intent, so each activity's own lifecycle is under test.
        $out = Invoke-Native {
            & $adb shell am start -n com.lvgl.sample/.MainActivity --ei SMOKE_SCREEN $screenId 2>&1
        }
    } else {
        $out = Invoke-Native { & $adb shell am start -n $component 2>&1 }
    }
    if ("$out" -notmatch "Starting:") { Fail "am start failed for $component : $out" }
    Start-Sleep -Seconds 12
    if (-not $shotName) { $shotName = $component.Split('/')[-1] }
    Invoke-Native { & $adb shell screencap -p "/sdcard/$shotName.png" 2>&1 | Out-Null }
    Invoke-Native { & $adb pull "/sdcard/$shotName.png" (Join-Path $ScreenshotDir "$shotName.png") 2>&1 | Out-Null }
}

$allActivities = @(
    "com.lvgl.sample/.WidgetShowcaseActivity",
    "com.lvgl.sample/.LayoutShowcaseActivity",
    "com.lvgl.sample/.InteractiveDemoActivity",
    "com.lvgl.sample/.CameraSampleActivity",
    "com.lvgl.sample/.GameSampleActivity",
    "com.lvgl.sample/.LauncherSampleActivity",
    "com.lvgl.sample/.showcase.DemoShowcaseActivity",
    "com.lvgl.sample/.AppDemoActivity"
)
$activities = if ($SmokeOnly) { @("com.lvgl.sample/.showcase.DemoShowcaseActivity") } else { $allActivities }

Invoke-Native { & $adb logcat -c 2>&1 | Out-Null }
Start-Activity "com.lvgl.sample/.MainActivity"
foreach ($a in $activities) { Start-Activity $a }

# --- Tap a showcase tile (slow press so LVGL's input read period sees it) ---
Start-Activity "com.lvgl.sample/.showcase.DemoShowcaseActivity" "showcase-window"
Invoke-Native { & $adb shell input swipe 290 520 290 520 300 2>&1 | Out-Null }
Start-Sleep -Seconds 3
Invoke-Native { & $adb shell screencap -p /sdcard/showcase-window.png 2>&1 | Out-Null }
Invoke-Native { & $adb pull /sdcard/showcase-window.png (Join-Path $ScreenshotDir "showcase-window.png") 2>&1 | Out-Null }

# --- Assertions ---
$logFile = Join-Path $ScreenshotDir "logcat.txt"
Invoke-Native { & $adb logcat -d 2>&1 | Out-File -FilePath $logFile }
$log = Get-Content $logFile -Raw
# Only a crash or ANR *in the sample app* is a failure. Broad markers such as
# "FATAL" or "start timeout" also match system-process noise on an
# unaccelerated CI emulator - the Bluetooth stack logs
# "AdapterState: Start Timeout Delay: 4000" and then SIGABRTs in its own
# process, which is not an app defect and failed an otherwise healthy walk.
# DynamicHarnessGuardTest keeps this pattern honest.
$crashPattern = 'ANR in com\.lvgl\.sample|>>> com\.lvgl\.sample <<<|Process: com\.lvgl\.sample, PID|pid \d+ \(com\.lvgl\.sample\)'
$lines = $log.ToString().Split("`n")
$hits = @()
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match $crashPattern) { $hits += $i }
}
if ($hits.Count -gt 0) {
    Write-Host "Crash/ANR lines from logcat ($($hits.Count) matching lines, first 4 with context):"
    $shown = @{}
    foreach ($i in ($hits | Select-Object -First 4)) {
        $from = [Math]::Max(0, $i - 4)
        $to = [Math]::Min($lines.Count - 1, $i + 8)
        for ($j = $from; $j -le $to; $j++) {
            if (-not $shown.ContainsKey($j)) { Write-Host "  $($lines[$j])"; $shown[$j] = $true }
        }
        Write-Host "  ---"
    }
    Fail "Crash/ANR detected in logcat ($($hits.Count) matching lines)"
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
Write-Host "`n==> DYNAMIC TEST PASSED ($Variant, $ApkPath)" -ForegroundColor Green
