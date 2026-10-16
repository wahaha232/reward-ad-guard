<#
.SYNOPSIS
  Reports which accessibility services MIUI has actually BOUND (not just enabled).

.DESCRIPTION
  On MIUI/HyperOS the value of `secure enabled_accessibility_services` only records
  the user's *intent*. The system separately binds the service process, and it can
  refuse to do so. `dumpsys accessibility` exposes both lists:

      Bound services:{...}      <- actually running and receiving events
      Binding services:{}       <- currently connecting

  A service that is "enabled" but absent from BOTH lists is dead: the app's
  AccessibilityService callbacks never fire, so the app correctly reports
  未連線 even though the Settings toggle says 已啟用. Always check here before
  blaming the app.

  Exit code 0 if the requested package is bound, 1 otherwise.
#>
[CmdletBinding()]
param(
    [string]$Adb    = '',
    [string]$Target = 'com.rewardadguard.app'
)

$ErrorActionPreference = 'Stop'

# adb lives in a different place on every machine, so resolve it instead of
# hard-coding one developer's path (ISS-06). Mirrors auto_test.ps1's Resolve-Adb.
function Resolve-Adb {
    param([string]$Explicit)

    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:ADB) { $candidates += $env:ADB }

    $repoRoot = if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) {
        (Get-Location).Path
    } else {
        (Split-Path -Parent $PSScriptRoot)
    }
    $props = Join-Path $repoRoot 'local.properties'
    if (Test-Path $props) {
        $sdkLine = Select-String -Path $props -Pattern '^\s*sdk\.dir\s*=' | Select-Object -First 1
        if ($sdkLine) {
            $sdk = ($sdkLine.Line -split '=', 2)[1].Trim() -replace '\\\\', '\'
            if ($sdk) { $candidates += (Join-Path $sdk 'platform-tools\adb.exe') }
        }
    }

    foreach ($envName in 'ANDROID_HOME', 'ANDROID_SDK_ROOT') {
        $sdk = [Environment]::GetEnvironmentVariable($envName)
        if ($sdk) { $candidates += (Join-Path $sdk 'platform-tools\adb.exe') }
    }

    $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    $candidates += 'adb'

    foreach ($candidate in $candidates) {
        if ($candidate -eq 'adb') {
            if (Get-Command adb -ErrorAction SilentlyContinue) { return 'adb' }
        } elseif (Test-Path $candidate) {
            return $candidate
        }
    }
    return $null
}

$Adb = Resolve-Adb -Explicit $Adb
if (-not $Adb) {
    Write-Host 'ERROR: adb.exe could not be found.' -ForegroundColor Red
    Write-Host '  Install Android platform-tools, add them to PATH,' -ForegroundColor DarkGray
    Write-Host '  set $env:ADB, or pass -Adb "C:\path\to\adb.exe".' -ForegroundColor DarkGray
    exit 1
}

function Get-Section {
    param([string]$Text, [string]$Name)
    # dumpsys wraps long values across lines, so collapse whitespace first.
    $flat = ($Text -replace '\s+', ' ')
    $m = [regex]::Match($flat, [regex]::Escape($Name) + ':\{(?<body>[^}]*)\}')
    if (-not $m.Success) { return @() }
    $body = $m.Groups['body'].Value.Trim()
    if ($body -eq '') { return @() }
    # Services inside the braces are separated by ", Service[" once flattened.
    return [regex]::Split($body, ', (?=Service\[)') |
        Where-Object { $_.Trim() -ne '' } |
        ForEach-Object { $_.Trim().TrimStart(',').Trim() }
}

$dump = & $Adb shell dumpsys accessibility 2>&1 | Out-String

$bound   = Get-Section $dump 'Bound services'
$binding = Get-Section $dump 'Binding services'

# The global list is followed by one block per user; take the union so a service
# bound only in a secondary user profile still counts.
$bound = @($bound)
$binding = @($binding)

Write-Host "Bound services   : $($bound.Count)"
$bound | ForEach-Object { Write-Host "  + $($_ -replace ',.*$', '')" }
Write-Host "Binding services : $($binding.Count)"
$binding | ForEach-Object { Write-Host "  ~ $($_ -replace ',.*$', '')" }

$enabledRaw = (& $Adb shell settings get secure enabled_accessibility_services 2>&1 | Out-String).Trim()
Write-Host ""
Write-Host "Enabled (intent) : $enabledRaw"
Write-Host ""

$isBound = $false
foreach ($s in $bound) {
    if ($s -like "*$Target*") { $isBound = $true }
}
$isEnabled = $enabledRaw -like "*$Target*"

Write-Host ("enabled={0}  bound={1}" -f $isEnabled, $isBound)

if ($isBound) {
    Write-Host "OK: $Target is bound and receiving accessibility events." -ForegroundColor Green
    exit 0
}
if ($isEnabled) {
    Write-Host "PROBLEM: $Target is listed as enabled but NOT bound." -ForegroundColor Red
    Write-Host "  The Settings toggle will read 已啟用 while the app shows 未連線." -ForegroundColor Yellow
    Write-Host "  Fix: toggle it OFF then ON from Settings > 輔助功能 > 下載的應用程式," -ForegroundColor Yellow
    Write-Host "       or try: adb shell settings put secure accessibility_enabled 0; then 1" -ForegroundColor Yellow
} else {
    Write-Host "NOT ENABLED: $Target is not in enabled_accessibility_services." -ForegroundColor Yellow
}
exit 1
