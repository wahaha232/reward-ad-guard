<#
.SYNOPSIS
    On-device verification checklist for Reward Ad Guard.

.DESCRIPTION
    Drives an ADB-connected handset through the manual steps that cannot be
    covered by JVM unit tests (accessibility service, real ad flow, log export).

    The script is interactive-friendly: every step prints what to do and what to
    look for, then waits for Enter. Steps can be skipped.

    Nothing is changed on the device without being announced, and nothing
    requires root.

.PARAMETER ApkPath
    Path to the debug APK. Defaults to the Gradle output of this repo.

.PARAMETER Adb
    Path to adb.exe. Defaults to "adb" from PATH.

.PARAMETER NonInteractive
    Print the checklist only, without waiting for Enter at each step.

.PARAMETER Preflight
    Run the environment checks (adb, device, APK, install gate) and exit
    without walking the checklist. Exits 1 when the device is not ready.

.EXAMPLE
    .\device_test_checklist.ps1
.EXAMPLE
    .\device_test_checklist.ps1 -Preflight
.EXAMPLE
    .\device_test_checklist.ps1 -NonInteractive
.EXAMPLE
    .\device_test_checklist.ps1 -ApkPath "C:\out\reward-ad-guard-debug.apk"
#>

[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$Adb = 'adb',
    [switch]$NonInteractive,
    [switch]$Preflight
)

$ErrorActionPreference = 'Stop'

# Xiaomi / HyperOS rejects `adb install` with
#   INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
# until the handset owner has allowed the MIUI installer once. The blocking gate
# is com.miui.securitycenter.permission.GLOBAL_PACKAGEINSTALLER (prot=signature),
# which the adb shell uid can never hold, so no adb toggle or pm flag helps.
# Observed on 2026-10-02 (2311DRK48G / HyperOS 3): after the user installed the
# APK once from the device UI, `adb install -r` started succeeding and has kept
# working. So the advice below is a one-time bootstrap, not a permanent block.
$script:InstallRestrictedHint = @(
    'This handset refused the install with INSTALL_FAILED_USER_RESTRICTED.',
    'On Xiaomi/HyperOS the MIUI installer is gated by',
    'com.miui.securitycenter.permission.GLOBAL_PACKAGEINSTALLER (prot=signature),',
    'which the adb shell uid can never hold - so no adb toggle or pm flag helps.',
    'Bootstrap the install from the handset once:',
    '  1. adb push <apk> /sdcard/Download/reward-ad-guard-debug.apk',
    '  2. on the phone open Files > Download and tap the APK',
    '  3. allow install from unknown sources and confirm',
    'After that first device-side install, retry `adb install -r` - it usually works.'
)

# Default the APK path to this repo's Gradle debug output. Doing this here (and
# not in the param block) keeps the default usable when the script is dot-sourced
# or invoked from an interactive session where $PSScriptRoot is empty.
if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $repoRoot = if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) { (Get-Location).Path } else { (Split-Path -Parent $PSScriptRoot) }
    $ApkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\reward-ad-guard-debug.apk'
}
$script:Passed = 0
$script:Failed = 0
$script:Skipped = 0

function Write-Header {
    param([string]$Text)
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
}

function Write-Step {
    param([string]$Id, [string]$Title, [string[]]$Actions, [string]$Expected)
    Write-Host ''
    Write-Host "[$Id] $Title" -ForegroundColor Yellow
    foreach ($a in $Actions) { Write-Host "     - $a" }
    Write-Host "     EXPECT: $Expected" -ForegroundColor Green
}

function Read-Verdict {
    param([string]$Id)
    if ($NonInteractive) {
        Write-Host "     (non-interactive: skipped)" -ForegroundColor DarkGray
        $script:Skipped++
        return
    }
    $answer = Read-Host "     [$Id] result? (p)ass / (f)ail / (s)kip"
    switch -Regex ($answer) {
        '^(p|pass)$'          { Write-Host '     PASS' -ForegroundColor Green;  $script:Passed++ }
        '^(f|fail)$'          { Write-Host '     FAIL' -ForegroundColor Red;    $script:Failed++ }
        default               { Write-Host '     SKIP' -ForegroundColor DarkGray; $script:Skipped++ }
    }
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$LetFail)
    try {
        & $Adb @Arguments 2>&1 | ForEach-Object { Write-Host "       $_" -ForegroundColor DarkGray }
    } catch {
        if (-not $LetFail) { throw }
        Write-Host "       adb failed: $_" -ForegroundColor DarkGray
    }
}

# Runs the checks that gate every later step: adb reachable, a device visible,
# the APK present, and - the one that actually bites on Xiaomi - whether the
# handset will accept an install at all. Returns $true when the checklist has a
# chance of progressing past step A1.
function Invoke-Preflight {
    param([string]$ApkPath, [string]$Adb)

    $ok = $true

    if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) {
        Write-Host '  [X] adb not found on PATH (pass -Adb <path>)' -ForegroundColor Red
        return $false
    }
    Write-Host '  [v] adb found' -ForegroundColor Green

    $deviceLines = & $Adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice' }
    if (-not $deviceLines) {
        Write-Host '  [X] no authorised device (accept the RSA prompt on the handset)' -ForegroundColor Red
        return $false
    }
    Write-Host ("  [v] device: {0}" -f ($deviceLines -join ', ').Trim()) -ForegroundColor Green

    $sdk = (& $Adb shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
    $rel = (& $Adb shell getprop ro.build.version.release 2>&1 | Out-String).Trim()
    $mdl = (& $Adb shell getprop ro.product.model 2>&1 | Out-String).Trim()
    Write-Host ("  [v] Android {0} (API {1}) on {2}" -f $rel, $sdk, $mdl) -ForegroundColor Green

    if (-not (Test-Path $ApkPath)) {
        Write-Host ("  [X] APK missing: {0}" -f $ApkPath) -ForegroundColor Red
        Write-Host '      build it with: gradle assembleDebug --offline' -ForegroundColor DarkGray
        return $false
    }
    $apk = Get-Item $ApkPath
    Write-Host ("  [v] APK {0:N2} MB" -f ($apk.Length / 1MB)) -ForegroundColor Green

    # Probe the install gate without mutating the device. `pm install-create`
    # creates an empty staged session, then we abandon it. NOTE: on Xiaomi/HyperOS
    # this can succeed while the real install still fails, because the stubborn
    # check (com.miui.securitycenter.permission.GLOBAL_PACKAGEINSTALLER) is only
    # evaluated when a session is actually committed. Treat a pass as "probably
    # fine" and a failure as definitive.
    $probe = & $Adb shell pm install-create -r 2>&1 | Out-String
    if ($probe -match 'INSTALL_FAILED_USER_RESTRICTED') {
        Write-Host '  [X] the handset refuses adb installs (INSTALL_FAILED_USER_RESTRICTED)' -ForegroundColor Red
        foreach ($line in $script:InstallRestrictedHint) { Write-Host "      $line" -ForegroundColor Yellow }
        $ok = $false
    } elseif ($probe -match 'Success') {
        if ($probe -match '\[(\d+)\]') {
            & $Adb shell pm install-abandon $Matches[1] 2>&1 | Out-Null
        }
        Write-Host '  [v] the handset accepts adb install sessions' -ForegroundColor Green
    } else {
        Write-Host '  [ ] could not probe the install gate (continuing)' -ForegroundColor DarkGray
    }

    # A staged session can be created on HyperOS and still be rejected on commit,
    # so always state where the APK has to be side-loaded from if it fails.
    Write-Host '      if A1 later fails here: adb push the APK to /sdcard/Download' -ForegroundColor DarkGray
    Write-Host '      and install it from Files on the handset (see DEVELOPMENT_REPORT.md 5.1)' -ForegroundColor DarkGray

    return $ok
}

# Adb types into whatever IME is active. On a handset whose keyboard is set to a
# Chinese transliteration mode, `adb shell input text com.m104` produces
# `com歐元扮黑與歐元扮黑與歐元半`. Disable the IME around the injection instead of
# fighting the escaping. Restore it afterwards so the operator is not left
# without a keyboard.
function Invoke-AdbText {
    param(
        [Parameter(Mandatory = $true)][string]$Text,
        [string]$Ime = 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'
    )
    $disabled = $false
    try {
        & $Adb shell ime disable $Ime 2>&1 | Out-Null
        $disabled = $true
        & $Adb shell input text $Text 2>&1 | Out-Null
    } finally {
        if ($disabled) { & $Adb shell ime enable $Ime 2>&1 | Out-Null }
    }
}

function Get-BoundAccessibilityServices {
    <#
      Returns the services the system has ACTUALLY bound (running and receiving
      events), as opposed to merely listed in enabled_accessibility_services.

      Why this exists: on MIUI/HyperOS the secure setting records the user's
      INTENT while a vendor policy decides whether to bind. A service can sit in
      `Enabled services` forever without ever appearing in `Bound services`, in
      which case its callbacks never fire. `dumpsys accessibility` exposes both:

          Bound services:{...}    <- really running
          Binding services:{}     <- connecting right now

      dumpsys wraps long lines, so collapse whitespace before matching.
    #>
    $raw = (& $Adb shell dumpsys accessibility 2>&1 | Out-String)
    $flat = ($raw -replace '\s+', ' ')
    $m = [regex]::Match($flat, 'Bound services:\{(?<body>[^}]*)\}')
    if (-not $m.Success) { return @() }
    $body = $m.Groups['body'].Value.Trim()
    if ($body -eq '') { return @() }
    return @([regex]::Split($body, ', (?=Service\[)') | Where-Object { $_.Trim() -ne '' })
}

function Test-RewardAdGuardServiceBound {
    <#
      True only when the service is genuinely bound. Prints a clear diagnosis
      when it is enabled-but-unbound, because that state is easy to mistake for
      an app bug: the app shows 未連線 while the Settings toggle says 已啟用.
    #>
    $bound = Get-BoundAccessibilityServices
    $isBound = $false
    foreach ($s in $bound) {
        if ($s -like '*com.rewardadguard.app*') { $isBound = $true }
    }
    if ($isBound) { return $true }

    Write-Host ''
    Write-Host '  [!!] The accessibility service is NOT bound by the system.' -ForegroundColor Red
    Write-Host '       The Settings toggle may still read 已啟用; the app will show 未連線.' -ForegroundColor Yellow
    Write-Host '       Known cause: MIUI/HyperOS only binds accessibility services it' -ForegroundColor Yellow
    Write-Host '       approves. Verify with TOOLS\check_a11y_binding.ps1, and test the' -ForegroundColor Yellow
    Write-Host '       guard on an AOSP device or emulator if this ROM refuses to bind.' -ForegroundColor Yellow
    Write-Host '       See DEVELOPMENT_REPORT.md section 8.2.' -ForegroundColor Yellow
    Write-Host ('       Bound right now: {0}' -f (($bound | ForEach-Object { $_ -replace ',.*$', '' }) -join ' | ')) -ForegroundColor Gray
    Write-Host ''
    return $false
}

# Re-assert the accessibility binding. `am force-stop` drops the service from
# Bound services while leaving it in Enabled services, and Android will not
# rebind it on its own - this makes the state consistent again.
function Enable-RewardAdGuardService {
    # NOTE: never run `adb shell settings put secure enabled_accessibility_services
    # <one-package>` by hand. That SETTER REPLACES the whole list and would drop
    # every other accessibility service on the device (TalkBack, MIUI services,
    # remote-support tools...). Always read the current value, append, and write
    # the merged list back - which is what this function does.
    #
    # Caveat observed on MIUI: the write can be accepted by Settings yet still not
    # produce a binding. Always confirm with Test-RewardAdGuardServiceBound below.
    $service = 'com.rewardadguard.app/com.rewardadguard.app.service.RewardAdAccessibilityService'
    $current = (& $Adb shell settings get secure enabled_accessibility_services 2>&1 | Out-String).Trim()
    if ($current -notmatch [regex]::Escape('com.rewardadguard.app/')) {
        $merged = if ([string]::IsNullOrWhiteSpace($current) -or $current -eq 'null') {
            $service
        } else {
            "$current`:$service"
        }
        & $Adb shell settings put secure enabled_accessibility_services $merged 2>&1 | Out-Null
    }
    & $Adb shell settings put secure accessibility_enabled 1 2>&1 | Out-Null

    # Give the system a moment, then report honestly whether it actually bound.
    Start-Sleep -Seconds 3
    [void](Test-RewardAdGuardServiceBound)
}

# ---------------------------------------------------------------- preflight
Write-Header 'Reward Ad Guard - device test checklist'

try {
    $adbVersion = & $Adb version 2>&1 | Select-Object -First 1
    Write-Host "adb         : $adbVersion" -ForegroundColor Gray
} catch {
    Write-Warning "adb was not found ('$Adb'). Install platform-tools or pass -Adb <path>."
    Write-Host 'The checklist below can still be followed manually.' -ForegroundColor DarkGray
}

if (Test-Path $ApkPath) {
    $apk = Get-Item $ApkPath
    Write-Host ("apk         : {0}" -f $apk.FullName) -ForegroundColor Gray
    Write-Host ("apk size    : {0:N2} MB" -f ($apk.Length / 1MB)) -ForegroundColor Gray
} else {
    Write-Warning "APK not found at '$ApkPath'. Build it with: gradle assembleDebug --offline"
}

if (-not $NonInteractive -and (Get-Command $Adb -ErrorAction SilentlyContinue)) {
    $devices = & $Adb devices 2>&1 | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice' }
    if (-not $devices) {
        Write-Warning 'No authorised device detected.'
        Write-Host 'Connect a handset with USB debugging enabled, then confirm the RSA prompt.' -ForegroundColor DarkGray
    } else {
        Write-Host ("device      : {0}" -f ($devices -join ', ').Trim()) -ForegroundColor Gray
    }
}

if ($Preflight) {
    Write-Header 'Preflight'
    $ready = Invoke-Preflight -ApkPath $ApkPath -Adb $Adb
    Write-Host ''
    if ($ready) {
        Write-Host 'Preflight: ready - run the checklist without -Preflight.' -ForegroundColor Green
        exit 0
    }
    Write-Host 'Preflight: not ready - fix the [X] items above first.' -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- install
Write-Header 'A. Installation'

Write-Step -Id 'A1' -Title 'Install / upgrade the debug build' -Actions @(
    'Install the APK with a fresh activity stack.',
    "Command: adb install -r `"$ApkPath`"",
    'Xiaomi/HyperOS refuses every adb install path on some ROMs (verified on',
    '  2311DRK48G / Android 16 / HyperOS 3): the MIUI installer is gated by',
    '  com.miui.securitycenter.permission.GLOBAL_PACKAGEINSTALLER (prot=signature),',
    '  which the adb shell uid can never hold. No toggle or pm flag changes this.',
    'Fallback that works: install from the handset UI.',
    "  {0} push `"{1}`" /sdcard/Download/reward-ad-guard-debug.apk" -f $Adb, $ApkPath
) -Expected 'Success (or "Performing Streamed Install ... Success")'
if (-not $NonInteractive -and (Get-Command $Adb -ErrorAction SilentlyContinue) -and (Test-Path $ApkPath)) {
    $go = Read-Host '     Install now? (y/n)'
    if ($go -match '^(y|yes)$') {
        $installOutput = & $Adb install -r $ApkPath 2>&1 | Out-String
        foreach ($line in ($installOutput -split "`r?`n" | Where-Object { $_ -ne '' })) {
            Write-Host "       $line" -ForegroundColor DarkGray
        }
        if ($installOutput -match 'INSTALL_FAILED_USER_RESTRICTED') {
            Write-Host ''
            foreach ($line in $script:InstallRestrictedHint) { Write-Host "     $line" -ForegroundColor Yellow }
            Write-Host ''
            Write-Host '     Workaround without the toggle: copy the APK to the handset and open it there.' -ForegroundColor Yellow
            Write-Host ("       {0} push `"{1}`" /sdcard/Download/reward-ad-guard-debug.apk" -f $Adb, $ApkPath) -ForegroundColor DarkGray
            Write-Host '       then tap the APK in Files and allow install from unknown sources.' -ForegroundColor DarkGray
        }
    }
}
Read-Verdict -Id 'A1'

Write-Step -Id 'A2' -Title 'Launch the app' -Actions @(
    'Open Reward Ad Guard from the launcher.',
    'Command: adb shell am start -n com.rewardadguard.app/.ui.MainActivity'
) -Expected 'Main screen renders with no crash and no ANR'
if (-not $NonInteractive -and (Get-Command $Adb -ErrorAction SilentlyContinue)) {
    $go = Read-Host '     Launch now? (y/n)'
    if ($go -match '^(y|yes)$') {
        Invoke-Adb -Arguments @('shell', 'am', 'start', '-n', 'com.rewardadguard.app/.ui.MainActivity') -LetFail
    }
}
Read-Verdict -Id 'A2'

# ---------------------------------------------------------------- permissions
Write-Header 'B. Permissions and service binding'

Write-Step -Id 'B1' -Title 'Notification permission (Android 13+)' -Actions @(
    'Grant the notification permission when prompted, or enable it in App info.'
) -Expected 'The persistent protection notification is visible in the shade'
Read-Verdict -Id 'B1'

Write-Step -Id 'B2' -Title 'Enable the accessibility service' -Actions @(
    'Settings > Accessibility > Installed apps > Reward Ad Guard > On.',
    'Command to open the screen: adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS'
) -Expected 'The service toggles on and stays on after leaving Settings'
Read-Verdict -Id 'B2'

Write-Step -Id 'B3' -Title 'Service survives' -Actions @(
    'Switch to another app for ~30 seconds, then return to Reward Ad Guard.'
) -Expected 'The service is still listed as enabled; the notification is still present'
Read-Verdict -Id 'B3'

# ---------------------------------------------------------------- configuration
Write-Header 'C. Configuration'

Write-Step -Id 'C1' -Title 'Select at least one reward app' -Actions @(
    'Open the Reward apps list and tick one installed app that shows rewarded ads.'
) -Expected 'The app appears in the monitored list and the home screen reflects it'
Read-Verdict -Id 'C1'

Write-Step -Id 'C2' -Title 'Settings are persisted' -Actions @(
    'Toggle external redirect protection, close-button assistance and the redirect policy.',
    'Close the app from Recents and reopen it (prefer this over force-stop).',
    'NOTE: am force-stop also UNBINDS the accessibility service. It stays in',
    '      Enabled services but leaves Bound services, so re-enable it before',
    '      judging anything. See section 6.1 of DEVELOPMENT_REPORT.md.'
) -Expected 'Every toggle and dropdown keeps the value you chose'
Read-Verdict -Id 'C2'

Write-Step -Id 'C3' -Title 'Unselected apps are ignored' -Actions @(
    'Open an app that is NOT in the monitored list and trigger an ad.'
) -Expected 'No session is created and no event is logged for that app'
Read-Verdict -Id 'C3'

# ---------------------------------------------------------------- core flow
Write-Header 'D. Core protection flow (spec test 55)'

Write-Step -Id 'D1' -Title 'Normal ad watch still completes' -Actions @(
    'Open the monitored reward app and start a rewarded ad.',
    'Watch it to the end.',
    'Tap the [X] / close control when it appears.'
) -Expected 'The ad closes and the reward is granted - the guard must NOT interrupt this'
Read-Verdict -Id 'D1'

Write-Step -Id 'D2' -Title 'Store -> Back -> Ad -> X still works' -Actions @(
    'Tap something in the reward app that legitimately opens the store.',
    'Press Back to return to the reward app.',
    'Watch another ad and close it with [X].'
) -Expected 'The reward is still granted; at most an observation event is logged, no block'
Read-Verdict -Id 'D2'

Write-Step -Id 'D3' -Title 'External redirect is detected' -Actions @(
    'Wait for (or trigger) an ad that jumps to a browser / store / another app.',
    'Note the destination package in the log.'
) -Expected 'A redirect event is recorded with the source and destination package and a risk score'
Read-Verdict -Id 'D3'

Write-Step -Id 'D4' -Title 'Redirect is blocked / user is returned' -Actions @(
    'Set the redirect policy to Block after grace (or Block immediately).',
    'Trigger the same external redirect again.'
) -Expected 'The user is brought back to the reward app quickly; a block event is logged'
Read-Verdict -Id 'D4'

Write-Step -Id 'D5' -Title 'Log-only policy never blocks' -Actions @(
    'Set the redirect policy to Log only.',
    'Trigger an external redirect again.'
) -Expected 'The redirect happens, an event is logged, the user is NOT pulled back'
Read-Verdict -Id 'D5'

Write-Step -Id 'D6' -Title 'Close-button assistance' -Actions @(
    'Enable close-button assistance and open an ad with a small [X] in a corner.',
    'Watch the log for a close-detection entry.'
) -Expected 'A close-detection event is logged with the match method (e.g. SYMBOL_TEXT) and a bounds label'
Read-Verdict -Id 'D6'

Write-Step -Id 'D7' -Title 'Full-screen CTA is not mistaken for [X]' -Actions @(
    'Find an ad whose whole screen is a clickable Close / CTA.'
) -Expected 'No close-detection event with a whole-screen target; the CTA is rejected'
Read-Verdict -Id 'D7'

# ---------------------------------------------------------------- logging
Write-Header 'E. Logging, statistics and export'

Write-Step -Id 'E1' -Title 'Events appear in the log screen' -Actions @(
    'Open the Log tab after the tests above.'
) -Expected 'Session, redirect, block, return and close-detection entries are listed with severity colours'
Read-Verdict -Id 'E1'

Write-Step -Id 'E2' -Title 'Session summary is complete' -Actions @(
    'Find the last SESSION_SUMMARY entry.'
) -Expected 'It reports redirects, blocked, returns, closeDetect and the maximum risk'
Read-Verdict -Id 'E2'

Write-Step -Id 'E3' -Title 'Per-app statistics' -Actions @(
    'Open the statistics / per-app section on the home screen.'
) -Expected 'Counters are attributed to the reward app labels you selected'
Read-Verdict -Id 'E3'

Write-Step -Id 'E4' -Title 'Export CSV' -Actions @(
    'Export the log as CSV and open the file.'
) -Expected 'A usable CSV with a header row; the share sheet appears with type text/csv'
Read-Verdict -Id 'E4'

Write-Step -Id 'E5' -Title 'Export JSON and TXT' -Actions @(
    'Repeat the export for JSON and TXT.'
) -Expected 'Both files open; JSON parses; the share intent type matches the format'
Read-Verdict -Id 'E5'

Write-Step -Id 'E6' -Title 'Export from outside the UI' -Actions @(
    'Trigger the export broadcast (LogExportReceiver) or the notification action.'
) -Expected 'The export runs and the result is shared without opening the app'
Read-Verdict -Id 'E6'

Write-Step -Id 'E7' -Title 'Log survives a restart' -Actions @(
    'Force-stop the app: adb shell am force-stop com.rewardadguard.app',
    'Reopen it and re-enable the accessibility service if it unbonded (section 6.1).'
) -Expected 'Previous events and sessions are still listed (Room persistence)'
Read-Verdict -Id 'E7'

# ---------------------------------------------------------------- robustness
Write-Header 'F. Robustness'

Write-Step -Id 'F1' -Title 'Heavy app switching' -Actions @(
    'Open and close several apps quickly while a monitored app is running.'
) -Expected 'The guard stays responsive; an ad-session guess is logged at most as POSSIBLE_AD_SESSION / ATTEMPTED'
Read-Verdict -Id 'F1'

Write-Step -Id 'F2' -Title 'Reward app disabled mid-session' -Actions @(
    'While a monitored app is running, disable it in the Reward apps list.'
) -Expected 'The session ends cleanly (REWARD_APP_DISABLED) and monitoring stops'
Read-Verdict -Id 'F2'

Write-Step -Id 'F3' -Title 'Notification permission revoked' -Actions @(
    'Revoke the notification permission while the service runs.'
) -Expected 'No crash; monitoring degrades gracefully'
Read-Verdict -Id 'F3'

Write-Step -Id 'F4' -Title 'Accessibility service disabled' -Actions @(
    'Turn the accessibility service off in Settings.'
) -Expected 'The UI reflects the disabled state; no crash, no stale "protected" indicator'
Read-Verdict -Id 'F4'

Write-Step -Id 'F5' -Title 'Orientation change and split screen' -Actions @(
    'Rotate the device and enter split screen while a monitored app is in front.'
) -Expected 'No crash; detection continues (window change counts may rise)'
Read-Verdict -Id 'F5'

Write-Step -Id 'F6' -Title 'No ANR under sustained load' -Actions @(
    'Use the monitored app for 10+ minutes with ads and redirects.'
) -Expected 'No "isn''t responding" dialog; adb logcat shows no FATAL EXCEPTION'
Read-Verdict -Id 'F6'

if (Get-Command $Adb -ErrorAction SilentlyContinue) {
    Write-Host ''
    Write-Host 'Optional: watch for crashes in another terminal with' -ForegroundColor DarkGray
    Write-Host '  adb logcat -v brief AndroidRuntime:E RewardAdGuard:V *:S' -ForegroundColor DarkGray
}

# ---------------------------------------------------------------- summary
Write-Header 'Summary'
Write-Host ("  passed  : {0}" -f $script:Passed)  -ForegroundColor Green
Write-Host ("  failed  : {0}" -f $script:Failed)  -ForegroundColor Red
Write-Host ("  skipped : {0}" -f $script:Skipped) -ForegroundColor DarkGray
Write-Host ''
Write-Host 'Reminder: the JVM suite (74 tests) covers the pure decision logic.' -ForegroundColor Gray
Write-Host 'This checklist covers everything that needs a real device.' -ForegroundColor Gray

if ($script:Failed -gt 0) { exit 1 }
exit 0
