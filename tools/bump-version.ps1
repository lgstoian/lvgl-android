#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Bumps the release version in gradle.properties (the single source of truth).

.DESCRIPTION
    Updates lvgl.versionName and lvgl.versionCode. Both the app and the library
    read these properties, so this is the only place the version is defined.
    Run tools/size-report.ps1 and the test suite afterwards, then commit.

.PARAMETER Version
    The new semantic version, e.g. 1.0.1.

.PARAMETER VersionCode
    The new version code. Defaults to the current code + 1.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [int]$VersionCode = 0
)

$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path $PSScriptRoot -Parent
$Props = Join-Path $RepoRoot "gradle.properties"

if (-not (Test-Path $Props)) { throw "gradle.properties not found: $Props" }
if ($Version -notmatch '^\d+\.\d+\.\d+$') {
    throw "Version must be semantic (x.y.z), got '$Version'"
}

$lines = Get-Content $Props
$currentCode = 0
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '^\s*lvgl\.versionCode\s*=\s*(\d+)') {
        $currentCode = [int]$Matches[1]
        $lines[$i] = "lvgl.versionCode=$(if ($VersionCode -gt 0) { $VersionCode } else { $currentCode + 1 })"
    }
    elseif ($lines[$i] -match '^\s*lvgl\.versionName\s*=') {
        $lines[$i] = "lvgl.versionName=$Version"
    }
}

Set-Content -Path $Props -Value $lines
$newCode = (Get-Content $Props | Select-String '^\s*lvgl\.versionCode\s*=').ToString().Split('=')[1].Trim()
Write-Host "Version bumped to $Version (versionCode $newCode)" -ForegroundColor Green
