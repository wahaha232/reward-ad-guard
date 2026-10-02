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
    [string]$Adb    = 'D:\Claude\android-sdk\platform-tools\adb.exe',
    [string]$Target = 'com.rewardadguard.app'
)

$ErrorActionPreference = 'Stop'

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
