#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Reports release APK sizes and enforces the per-ABI / universal budget.

.DESCRIPTION
    Prints each release APK's size plus its largest entries, and fails if a
    per-ABI APK exceeds the ABI budget or the universal APK exceeds the
    universal budget. Wired into CI so a size regression blocks the build.

    Requires only PowerShell and the .NET ZipFile API (no Android tooling).

.PARAMETER ApkDir
    Directory containing the release APKs, relative to the repo root.

.PARAMETER AbiBudgetMb
    Maximum size of a single-ABI APK in MB.

.PARAMETER UniversalBudgetMb
    Maximum size of the universal APK in MB.
#>
[CmdletBinding()]
param(
    [string]$ApkDir = "app/build/outputs/apk/release",
    [double]$AbiBudgetMb = 4.0,
    [double]$UniversalBudgetMb = 6.0
)

$RepoRoot = Split-Path $PSScriptRoot -Parent
$ApkDir = Join-Path $RepoRoot $ApkDir

if (-not (Test-Path $ApkDir)) {
    Write-Error "APK directory not found: $ApkDir"
    exit 1
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$apks = Get-ChildItem $ApkDir -Filter *.apk | Sort-Object Name
if ($apks.Count -eq 0) {
    Write-Error "No APKs found in $ApkDir"
    exit 1
}

$failures = @()
foreach ($apk in $apks) {
    $mb = [math]::Round($apk.Length / 1MB, 2)
    $isUniversal = $apk.Name -like "*universal*"
    $budget = if ($isUniversal) { $UniversalBudgetMb } else { $AbiBudgetMb }
    $status = if ($mb -le $budget) { "OK" } else { "OVER BUDGET" }
    "{0,-44} {1,7} MB  (budget {2} MB)  {3}" -f $apk.Name, $mb, $budget, $status
    if ($mb -gt $budget) {
        $failures += "$($apk.Name) is $mb MB (budget $budget MB)"
    }

    $zip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
    $zip.Entries |
        Where-Object { $_.Length -gt 0 } |
        Sort-Object Length -Descending |
        Select-Object -First 6 |
        ForEach-Object { "    {0,-42} {1,10:N0} bytes" -f $_.FullName, $_.Length }
    $zip.Dispose()
}

if ($failures.Count -gt 0) {
    Write-Host "`nSIZE BUDGET EXCEEDED" -ForegroundColor Red
    $failures | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    exit 1
}
Write-Host "`nSIZE OK" -ForegroundColor Green
