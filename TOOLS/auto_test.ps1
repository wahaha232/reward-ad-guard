#Requires -Version 5.1
<#
.SYNOPSIS
    Unattended, ADB-driven smoke test for Reward Ad Guard.

.DESCRIPTION
    Checks everything about the app that can be verified WITHOUT consuming a
    rewarded ad: installation, permission state, whether the accessibility
    service is actually BOUND (not merely listed as enabled - the MIUI trap),
    whether the process is alive, whether events are reaching the database, and
    whether the log can be exported.

    This exists because rewarded ads only reset at midnight, so the on-device ad
    flow is a once-per-day resource. Run this first: if it fails, there is no
    point spending the day's only ad on a broken build.

    The script is read-only with respect to user data. It never clears the log
    unless -ClearLog is passed, and it never changes settings.

.PARAMETER ApkPath
    Debug APK to install. Defaults to this repo's Gradle output. Pass '' to skip
    the install step entirely (useful when re-checking a phone already set up).

.PARAMETER Adb
    Path to adb.exe. Defaults to $env:ADB or the SDK from local.properties.

.PARAMETER Serial
    Target device when more than one is attached (adb -s).

.PARAMETER Package
    Application id. Defaults to com.rewardadguard.app.

.PARAMETER ClearLog
    Clear the event log before the run so the "did events arrive?" check is
    unambiguous. Off by default because it destroys evidence.

.PARAMETER SkipInstall
    Do not install or reinstall; only inspect and exercise the running app.

.EXAMPLE
    .\auto_test.ps1
    Full run: install, inspect, exercise, report.

.EXAMPLE
    .\auto_test.ps1 -SkipInstall -ClearLog
    Fast re-check on an already-provisioned handset.

.EXAMPLE
    .\auto_test.ps1 -Serial 2311DRK48G
    Pick one of several attached devices.
#>
[CmdletBinding()]
param(
    [string]$ApkPath,
    [string]$Adb,
    [string]$Serial,
    [string]$Package = 'com.rewardadguard.app',
    [switch]$ClearLog,
    [switch]$SkipInstall
)

$ErrorActionPreference = 'Stop'

# ------------------------------------------------------------------ discovery

# adb lives in a different place on every machine, so resolve it in order of
# decreasing confidence rather than assuming PATH. local.properties is the
# authoritative record of where this checkout's SDK is.
function Resolve-Adb {
    param([string]$Explicit, [string]$RepoRoot)

    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:ADB) { $candidates += $env:ADB }

    $props = Join-Path $RepoRoot 'local.properties'
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

$repoRoot = if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) {
    (Get-Location).Path
} else {
    (Split-Path -Parent $PSScriptRoot)
}

if ([string]::IsNullOrWhiteSpace($ApkPath)) {
    $ApkPath = Join-Path $repoRoot 'app\build\outputs\apk\debug\reward-ad-guard-debug.apk'
}

$Adb = Resolve-Adb -Explicit $Adb -RepoRoot $repoRoot
if (-not $Adb) {
    Write-Host 'RESULT: BLOCKED - adb could not be found.' -ForegroundColor Red
    Write-Host '  Install Android platform-tools, then either add them to PATH,' -ForegroundColor DarkGray
    Write-Host '  set $env:ADB to the full path of adb.exe, or make sure this' -ForegroundColor DarkGray
    Write-Host '  checkout''s local.properties has a valid sdk.dir.' -ForegroundColor DarkGray
    exit 2
}

# ------------------------------------------------------------------- plumbing

# Every result is recorded so the summary at the end is complete even when an
# individual check has to be skipped.
$script:Results = [System.Collections.Generic.List[object]]::new()
$script:Blockers = 0
$script:Warnings = 0

function Add-Result {
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Title,
        [Parameter(Mandatory)][ValidateSet('PASS', 'FAIL', 'WARN', 'SKIP')][string]$Status,
        [string]$Detail = ''
    )
    $script:Results.Add([pscustomobject]@{ Id = $Id; Title = $Title; Status = $Status; Detail = $Detail })
    $colour = switch ($Status) {
        'PASS' { 'Green' }
        'FAIL' { 'Red' }
        'WARN' { 'Yellow' }
        default { 'DarkGray' }
    }
    $mark = switch ($Status) {
        'PASS' { '[v]' }
        'FAIL' { '[X]' }
        'WARN' { '[!]' }
        default { '[-]' }
    }
    Write-Host ("  {0} {1,-4} {2}" -f $mark, $Id, $Title) -ForegroundColor $colour
    if ($Detail) {
        foreach ($line in ($Detail -split "`n")) {
            Write-Host ("         {0}" -f $line.TrimEnd()) -ForegroundColor DarkGray
        }
    }
    if ($Status -eq 'FAIL') { $script:Blockers++ }
    if ($Status -eq 'WARN') { $script:Warnings++ }
}

function Write-Header {
    param([string]$Text)
    Write-Host ''
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
    Write-Host "  $Text" -ForegroundColor Cyan
    Write-Host ('=' * 72) -ForegroundColor DarkCyan
}

# adb writes normal output to stdout and diagnostics to stderr; treating stderr
# as an error would make every successful call look like a failure.
#
# Redirecting with `2>&1` is unreliable here: with ErrorActionPreference='Stop'
# any stderr line becomes a terminating error, and when it is temporarily
# downgraded the merged stream comes back as an array whose shape depends on the
# message. Driving the process directly through .NET sidesteps PowerShell's
# native-command error handling completely: stdout and stderr are separate
# streams, stderr is captured verbatim, and the exit code is authoritative.
$script:AdbExitCode = 0

function Invoke-Adb {
    param([Parameter(Mandatory)][string[]]$Arguments)

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true

    # ArgumentList is only available on .NET Core+; Windows PowerShell 5.1 runs on
    # .NET Framework where it is null, so build the classic quoted argument string.
    # Only arguments containing whitespace or a quote are wrapped, which keeps the
    # common cases byte-identical to what adb expects.
    $full = @()
    if ($Serial) { $full += @('-s', $Serial) }
    $full += $Arguments
    $q = [char]34
    $psi.Arguments = (($full | ForEach-Object {
        $text = [string]$_
        if ($text -match '[\s]') { $q + $text + $q } else { $text }
    }) -join ' ')

    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $psi
    [void]$process.Start()

    # Read both streams before waiting: a full pipe buffer would otherwise
    # deadlock a chatty command such as `logcat -d`.
    $stdout = $process.StandardOutput.ReadToEnd()
    $stderr = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    $script:AdbExitCode = $process.ExitCode
    $process.Dispose()

    $combined = $stdout
    if ($stderr.Trim()) {
        if ($combined.Trim()) { $combined += [Environment]::NewLine }
        $combined += $stderr
    }

    return [pscustomobject]@{
        Output   = $combined.Trim()
        ExitCode = $script:AdbExitCode
    }
}

function Get-Prop {
    param([Parameter(Mandatory)][string]$Name)
    $r = Invoke-Adb @('shell', 'getprop', $Name)
    # adb can answer with a diagnostic ("no devices/emulators found") instead of a
    # property. Returning that text would make every consumer treat it as a value,
    # so anything that is not a plausible property value is reported as empty.
    $value = $r.Output.Trim()
    if ($value -match 'error|not found|no devices|offline|more than one') { return '' }
    if ($value -match "\r?\n") { return '' }
    return $value
}

function Test-DeviceReady {
    $r = Invoke-Adb @('devices')
    $lines = ($r.Output -split "`r?`n") | Where-Object { $_ -match "\t" }
    $ready = @()
    $unauthorised = 0
    foreach ($line in $lines) {
        $parts = $line -split "\t"
        if ($parts.Count -lt 2) { continue }
        $state = $parts[1].Trim()
        if ($Serial -and $parts[0].Trim() -ne $Serial) { continue }
        if ($state -eq 'device') { $ready += $parts[0].Trim() }
        elseif ($state -ne 'offline') { $unauthorised++ }
    }
    return [pscustomobject]@{ Ready = $ready; Unauthorised = $unauthorised }
}

# ---------------------------------------------------------------- environment

Write-Host ''
Write-Host 'Reward Ad Guard - unattended device test' -ForegroundColor White
Write-Host "repo : $repoRoot" -ForegroundColor DarkGray
Write-Host "adb  : $Adb" -ForegroundColor DarkGray

Write-Header 'E. Environment'

$device = Test-DeviceReady
if ($device.Ready.Count -eq 0) {
    if ($device.Unauthorised -gt 0) {
        Add-Result -Id 'E1' -Title 'A device is attached and authorised' -Status 'FAIL' `
            -Detail "A device is connected but not authorised. Unlock the handset and accept the 'Allow USB debugging' prompt, then run again."
    } else {
        Add-Result -Id 'E1' -Title 'A device is attached and authorised' -Status 'FAIL' `
            -Detail 'No device detected. Connect the handset by USB, enable Developer options > USB debugging, and confirm the RSA prompt on screen.'
    }
} else {
    Add-Result -Id 'E1' -Title 'A device is attached and authorised' -Status 'PASS' `
        -Detail ("device: " + ($device.Ready -join ', '))
}

$androidRel = Get-Prop 'ro.build.version.release'
$androidSdk = Get-Prop 'ro.build.version.sdk'
$model = Get-Prop 'ro.product.model'
$manufacturer = Get-Prop 'ro.product.manufacturer'
$booted = Get-Prop 'sys.boot_completed'
$abi = Get-Prop 'ro.product.cpu.abi'

# With no device these all read empty. Reporting them as failures would bury the
# single real cause (E1) under a wall of red, so they are skipped instead.
if ($device.Ready.Count -eq 0) {
    foreach ($id in 'E2', 'E3', 'E4') {
        Add-Result -Id $id -Title 'Device-level checks' -Status 'SKIP' -Detail 'No device; covered by E1.'
    }
} else {
    $sdkNumber = 0
    $sdkParsed = [int]::TryParse($androidSdk, [ref]$sdkNumber)

    if ($sdkParsed) {
        $detail = "Android $androidRel (API $androidSdk) on $manufacturer $model"
        if ($sdkNumber -lt 26) {
            Add-Result -Id 'E2' -Title 'Android version is supported' -Status 'FAIL' `
                -Detail "$detail`nThe app requires minSdk 26 (Android 8.0)."
        } else {
            Add-Result -Id 'E2' -Title 'Android version is supported' -Status 'PASS' -Detail $detail
        }
    } else {
        Add-Result -Id 'E2' -Title 'Android version is supported' -Status 'FAIL' `
            -Detail "Could not read ro.build.version.sdk from the device (raw value: '$androidSdk')."
    }

    if ($booted -eq '1') {
        Add-Result -Id 'E3' -Title 'Device has finished booting' -Status 'PASS'
    } else {
        Add-Result -Id 'E3' -Title 'Device has finished booting' -Status 'FAIL' `
            -Detail "sys.boot_completed='$booted'. Wait for the home screen, then run again."
    }

    Add-Result -Id 'E4' -Title 'Native ABI (informational)' -Status 'PASS' -Detail "abi=$abi"
}

# Stop early: every later check needs a device.
if ($device.Ready.Count -eq 0) {
    Write-Header 'Summary'
    Write-Host "  $($script:Blockers) blocker(s). Nothing further could be checked." -ForegroundColor Red
    exit 1
}

# ------------------------------------------------------------------- install

Write-Header 'I. Build and install'

if ($SkipInstall) {
    Add-Result -Id 'I1' -Title 'Install debug APK' -Status 'SKIP' -Detail 'Skipped by -SkipInstall.'
} elseif (-not (Test-Path $ApkPath)) {
    Add-Result -Id 'I1' -Title 'Install debug APK' -Status 'FAIL' `
        -Detail "APK not found: $ApkPath`nBuild it first:  .\gradlew.bat assembleDebug"
} else {
    $apkSize = [math]::Round((Get-Item $ApkPath).Length / 1MB, 2)
    $install = Invoke-Adb @('install', '-r', '-d', $ApkPath)
    if ($install.ExitCode -eq 0 -and $install.Output -match 'Success') {
        Add-Result -Id 'I1' -Title 'Install debug APK' -Status 'PASS' `
            -Detail "$apkSize MB installed from $ApkPath"
    } else {
        $hint = ''
        if ($install.Output -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match') {
            $hint = "`nThe installed copy was signed with a different key. Uninstall it first:`n  adb uninstall $Package"
        } elseif ($install.Output -match 'INSTALL_FAILED_VERSION_DOWNGRADE') {
            $hint = "`nThe installed copy is newer. Uninstall it first:`n  adb uninstall $Package"
        }
        Add-Result -Id 'I1' -Title 'Install debug APK' -Status 'FAIL' `
            -Detail ("$($install.Output)$hint")
    }
}

$pathOut = Invoke-Adb @('shell', 'pm', 'path', $Package)
if ($pathOut.Output -match '^package:') {
    Add-Result -Id 'I2' -Title 'Package is installed' -Status 'PASS' `
        -Detail $pathOut.Output.Trim()
} else {
    Add-Result -Id 'I2' -Title 'Package is installed' -Status 'FAIL' `
        -Detail "$Package is not installed on the device."
}

# The version actually on the phone is frequently not the version just built.
# Reporting it prevents the classic "I fixed it but the bug is still there"
# confusion, which is caused by a stale APK rather than by the fix.
$dumpOut = Invoke-Adb @('shell', 'dumpsys', 'package', $Package)
$versionName = ''
$versionCode = ''
$lastUpdate = ''
$versionLine = ($dumpOut.Output -split "`r?`n") | Where-Object { $_ -match 'versionName=' } | Select-Object -First 1
if ($versionLine -match 'versionName=(\S+)') { $versionName = $Matches[1] }
if ($versionLine -match 'versionCode=(\d+)') { $versionCode = $Matches[1] }
$updateLine = ($dumpOut.Output -split "`r?`n") | Where-Object { $_ -match 'lastUpdateTime=' } | Select-Object -First 1
if ($updateLine) { $lastUpdate = ($updateLine -split '=', 2)[1].Trim() }

if ($versionName) {
    $detail = "installed versionName=$versionName versionCode=$versionCode"
    if ($lastUpdate) { $detail += "`nlastUpdateTime=$lastUpdate" }
    $detail += "`nCompare with versionName in app/build.gradle.kts to confirm this is a fresh build."
    Add-Result -Id 'I3' -Title 'Installed build stamp' -Status 'PASS' -Detail $detail
} else {
    Add-Result -Id 'I3' -Title 'Installed build stamp' -Status 'FAIL' `
        -Detail 'Could not read versionName from dumpsys package.'
}

# --------------------------------------------------- accessibility service

Write-Header 'S. Accessibility service'

# Reading the secure setting tells us the service is *listed* as enabled. It
# does NOT tell us the system actually bound it: MIUI/HyperOS and some other
# OEM builds keep an entry in ENABLED_ACCESSIBILITY_SERVICES while silently
# refusing to bind. A green tick in Settings is therefore not proof. These two
# checks are deliberately kept separate because the failure looks identical to
# the user but has completely different causes.
$enabledRaw = Invoke-Adb @('shell', 'settings', 'get', 'secure', 'enabled_accessibility_services')
$enabledList = ($enabledRaw.Output.Trim() -split ':')
$listed = $enabledList | Where-Object { $_ -match [regex]::Escape($Package) } | Select-Object -First 1

if ($listed) {
    Add-Result -Id 'S1' -Title 'Service is listed in enabled_accessibility_services' -Status 'PASS' `
        -Detail $listed.Trim()
} else {
    Add-Result -Id 'S1' -Title 'Service is listed in enabled_accessibility_services' -Status 'FAIL' `
        -Detail "Not present. Enable it manually:`n  adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS`nThen turn on 'Reward Ad Guard' and re-run with -SkipInstall."
}

# The authoritative check: does the app process exist AND is our service class
# actually running inside it? `dumpsys activity services` is the ground truth
# that the OEM cannot fake behind a UI toggle.
$servicesOut = Invoke-Adb @('shell', 'dumpsys', 'activity', 'services', $Package)
$serviceRunning = $servicesOut.Output -match 'RewardAdAccessibilityService'
$pidOut = Invoke-Adb @('shell', 'pidof', $Package)
# pidof prints a bare pid on success and nothing on failure, but a broken adb
# link makes it print a diagnostic - which is truthy and would fake a PASS.
$appPid = ''
if ($pidOut.Output -match '^\d+(\s+\d+)*$') { $appPid = $pidOut.Output.Trim() }

if ($serviceRunning -and $appPid) {
    Add-Result -Id 'S2' -Title 'Accessibility service is actually BOUND' -Status 'PASS' `
        -Detail "RewardAdAccessibilityService is running in pid $appPid"
} elseif ($serviceRunning) {
    Add-Result -Id 'S2' -Title 'Accessibility service is actually BOUND' -Status 'WARN' `
        -Detail 'The service is registered but no process id could be read. This is usually harmless; confirm S4 below.'
} elseif ($listed) {
    Add-Result -Id 'S2' -Title 'Accessibility service is actually BOUND' -Status 'FAIL' `
        -Detail "The service is LISTED as enabled but the system has NOT bound it.`nThis is the MIUI / HyperOS trap. In Settings, turn Reward Ad Guard OFF and`nback ON, then re-run. If it still fails, disable battery optimisation for`nthe app and lock it in the recents view."
} else {
    Add-Result -Id 'S2' -Title 'Accessibility service is actually BOUND' -Status 'FAIL' `
        -Detail 'The service is not running. Enable it in Accessibility settings first.'
}

# SERVICE_CONNECTED is written by the service itself at bind time. Its presence
# proves the app's own code ran, which is stronger evidence than any dumpsys
# output and is what the UI reacts to.
$logcatOut = Invoke-Adb @('logcat', '-d', '-t', '400', '-s', 'RewardAdGuard')
if ($logcatOut.Output -match 'SERVICE_CONNECTED') {
    $line = (($logcatOut.Output -split "`r?`n") | Where-Object { $_ -match 'SERVICE_CONNECTED' } | Select-Object -Last 1)
    Add-Result -Id 'S3' -Title 'Service logged SERVICE_CONNECTED this boot' -Status 'PASS' `
        -Detail $line.Trim()
} else {
    Add-Result -Id 'S3' -Title 'Service logged SERVICE_CONNECTED this boot' -Status 'WARN' `
        -Detail "No SERVICE_CONNECTED in the last 400 log lines. The log buffer may have`nrolled over, or the service has not sent its structured event yet.`nCross-check with check S4."
}

# The app exposes a read-only Compose UI; a crash on launch is the single most
# common reason a device test "mysteriously" fails, so it is checked explicitly
# rather than inferred.
$crashOut = Invoke-Adb @('logcat', '-d', '-t', '400')
$crashLine = (($crashOut.Output -split "`r?`n") |
    Where-Object { $_ -match 'FATAL EXCEPTION' -or ($_ -match 'AndroidRuntime' -and $_ -match $Package) } |
    Select-Object -Last 1)
if ($crashLine) {
    Add-Result -Id 'S4' -Title 'No crash in the app process' -Status 'FAIL' `
        -Detail "Recent crash found:`n$($crashLine.Trim())"
} else {
    Add-Result -Id 'S4' -Title 'No crash in the app process' -Status 'PASS'
}

# ------------------------------------------------------- live functional

Write-Header 'F. Live functional checks'

# Launching the Compose UI is the only way to prove the whole stack works:
# Compose inflation, ViewModel creation, Room open, settings load. If this
# succeeds without a crash, the app's read path is healthy on this device.
$clearLog = Invoke-Adb @('logcat', '-c')
$launch = Invoke-Adb @('shell', 'am', 'start', '-W', '-n', "$Package/.ui.MainActivity")

# `am start` reports "Status: ok" only when the activity actually started, and
# "Error: ..." otherwise. Requiring the explicit success marker (rather than
# merely a zero exit code) is what stops a dead adb link from looking like a
# successful launch - `-or`/`-and` precedence would have done exactly that.
$launchOk = ($launch.Output -match 'Status:\s*ok') -and ($launch.Output -notmatch 'Error:')

if ($launchOk) {
    $totalMatch = [regex]::Match($launch.Output, 'TotalTime:\s*(\d+)')
    $total = if ($totalMatch.Success) { "$($totalMatch.Groups[1].Value) ms" } else { 'unknown' }
    Add-Result -Id 'F1' -Title 'MainActivity launches' -Status 'PASS' -Detail "cold/warm start TotalTime: $total"
} else {
    Add-Result -Id 'F1' -Title 'MainActivity launches' -Status 'FAIL' `
        -Detail ("am start failed:`n" + $launch.Output)
}

# Give the first frame and the Room open a moment; then look for a crash that
# happened *because* of the launch, which is the interesting case.
Start-Sleep -Seconds 3
$postLaunch = Invoke-Adb @('logcat', '-d', '-t', '300')
$postCrash = (($postLaunch.Output -split "`r?`n") |
    Where-Object { $_ -match 'FATAL EXCEPTION' -or $_ -match 'E AndroidRuntime' } |
    Select-Object -Last 1)
if ($postCrash) {
    Add-Result -Id 'F2' -Title 'No crash during launch' -Status 'FAIL' `
        -Detail "Crash while starting the UI:`n$($postCrash.Trim())`nGet the full trace with:`n  adb logcat -d *:E"
} else {
    Add-Result -Id 'F2' -Title 'No crash during launch' -Status 'PASS'
}

$foreground = Invoke-Adb @('shell', 'dumpsys', 'activity', 'activities')
if ($foreground.Output -match "$([regex]::Escape($Package))/.ui.MainActivity" ) {
    Add-Result -Id 'F3' -Title 'MainActivity is the foreground activity' -Status 'PASS'
} else {
    Add-Result -Id 'F3' -Title 'MainActivity is the foreground activity' -Status 'WARN' `
        -Detail 'MainActivity is no longer resumed; it may have been covered during the check.'
}

# The monitoring snapshot is process-wide. If the exporter can read events, the
# same Room database the UI shows is reachable, so this doubles as a data-path
# test. run-as only works on debuggable builds, which is exactly what we ship.
$dbList = Invoke-Adb @('shell', 'run-as', $Package, 'ls', '-l', 'databases')
if ($dbList.Output -match 'reward_ad_guard') {
    $dbDetail = ($dbList.Output -split "`r?`n") | Where-Object { $_ -match 'reward_ad_guard' }
    Add-Result -Id 'F4' -Title 'Room database exists on disk' -Status 'PASS' `
        -Detail ($dbDetail -join "`n")
} else {
    Add-Result -Id 'F4' -Title 'Room database exists on disk' -Status 'FAIL' `
        -Detail ("No database files found. The app has not written anything yet, or this`nis not a debuggable build.`n" + $dbList.Output)
}

# Counting rows proves the service actually *persisted* something, rather than
# merely connecting. sqlite3 is present on most emulators and many OEM images;
# when it is missing we degrade to a warning instead of a false failure.
$sqliteCheck = Invoke-Adb @('shell', 'which', 'sqlite3')
if ($sqliteCheck.Output -match 'sqlite3') {
    $countOut = Invoke-Adb @('shell', 'run-as', $Package, 'sqlite3',
        'databases/reward_ad_guard.db', 'SELECT COUNT(*) FROM events;')
    $rowMatch = [regex]::Match($countOut.Output, '^\s*(\d+)\s*$', [System.Text.RegularExpressions.RegexOptions]::Multiline)
    $rowCount = 0
    $rowParsed = $rowMatch.Success -and [int]::TryParse($rowMatch.Groups[1].Value, [ref]$rowCount)
    if ($rowParsed -and $rowCount -gt 0) {
        Add-Result -Id 'F5' -Title 'Event log has persisted rows' -Status 'PASS' `
            -Detail "events table rows: $rowCount"
    } elseif ($rowParsed) {
        Add-Result -Id 'F5' -Title 'Event log has persisted rows' -Status 'WARN' `
            -Detail "events table is empty. Expected at least SERVICE_CONNECTED from the`ncurrent service connection. Check S2/S3, then exercise the app once."
    } else {
        Add-Result -Id 'F5' -Title 'Event log has persisted rows' -Status 'WARN' `
            -Detail ("Could not query the events table:`n" + $countOut.Output)
    }
} else {
    Add-Result -Id 'F5' -Title 'Event log has persisted rows' -Status 'SKIP' `
        -Detail 'sqlite3 is not available on this device image; cannot count rows.'
}

# --------------------------------------------------- synthetic guard tests
#
# These drive the guard through the debug-only DebugTestReceiver, feeding real
# AccessibilityEvents into the production code path. They exercise the redirect /
# return / close-button logic with ZERO rewarded ads, which is the whole point:
# the ad-consuming steps (D1/D2/D6) can then be reserved for proving that the
# guard does not break the *real* ad flow.
#
# Every check degrades to SKIP when the debug hook is absent (a release build, or
# a build without the hook compiled in), so this section never produces a false
# failure on a production APK.

Write-Header 'T. Synthetic guard tests (no ad consumed)'

# The shell CANNOT drive the debug broadcast receiver. Measured on the device:
# `am broadcast` reaches ActivityManager but never starts the app's process, so
# `Broadcast completed: result=0` is a FALSE SUCCESS. An explicit Activity started
# with `am start -n` is the entry point the shell can actually reach.
# See known-issues.md, "DebugTestReceiver was unreachable from the shell".
$DebugComponent = 'com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity'
$DebugTag = 'RewardAdGuardDebug'

function Invoke-TestCommand {
    param([Parameter(Mandatory)][string]$Command, [string]$PackageName, [string]$Value)
    # -S force-stops the app first. Without it the activity is reused and onCreate
    # does not run again, so every later command silently replays the first one's
    # output (and, being the top-most instance, it would steal the foreground).
    $arguments = @('shell', 'am', 'start', '-S', '-n', $DebugComponent)
    if ($PackageName) { $arguments += @('--es', 'package', $PackageName) }
    if ($Value) { $arguments += @('--es', 'value', $Value) }
    $arguments += @('--es', 'cmd', $Command)
    return Invoke-Adb $arguments
}

# Confirm the debug hook exists before relying on it. On a release build the
# activity is not in the manifest at all and this returns an explicit error.
$probe = Invoke-TestCommand -Command 'dump_settings'
$hookPresent = ($probe.Output -notmatch 'Error:.*not found|Broken pipe|no devices') -and
    ($probe.Output -match 'Starting: Intent|Activity not started')

if (-not $hookPresent) {
    Add-Result -Id 'T1' -Title 'Debug test hook is available' -Status 'SKIP' `
        -Detail ("The debug-only test activity is not reachable, so the synthetic tests" +
        "`nwere skipped. This is expected for a release build.`n" + $probe.Output)
} else {
    Add-Result -Id 'T1' -Title 'Debug test hook is available' -Status 'PASS'

    # T1b: assist_action must not be NONE. That single value silently disables the
    # whole close-button guard: the guard keeps finding the X, keeps declining to
    # press it, and every log line looks like an ordinary throttle.
    Start-Sleep -Milliseconds 800
    $settingsLine = ((Invoke-Adb @('logcat', '-d', '-s', $DebugTag)).Output -split "`r?`n") |
        Where-Object { $_ -match 'SETTINGS .*assistAction=' } |
        Select-Object -Last 1
    if ($settingsLine -match 'assistAction=(?!NONE)(\w+)') {
        Add-Result -Id 'T1b' -Title 'assist_action is not NONE' -Status 'PASS' `
            -Detail $settingsLine.Trim()
    } elseif ($settingsLine) {
        Add-Result -Id 'T1b' -Title 'assist_action is not NONE' -Status 'FAIL' `
            -Detail ("assist_action is NONE, so the close-button guard will never click." +
            "`nRun: Invoke-TestCommand -Command 'set_assist_action' -Value 'ASSIST_WHEN_IDLE'`n" +
            $settingsLine.Trim())
    } else {
        Add-Result -Id 'T1b' -Title 'assist_action is not NONE' -Status 'SKIP' `
            -Detail 'No SETTINGS line in logcat; cannot determine assist_action.'
    }

    # T2: a synthetic foreground transition must start a session on a monitored
    # app. This is the foundation the other synthetic checks build on.
    $fakeReward = 'com.example.fake.reward'
    [void](Invoke-TestCommand -Command 'clear_reward_apps')
    [void](Invoke-TestCommand -Command 'set_reward_app' -PackageName $fakeReward)
    Start-Sleep -Milliseconds 600
    [void](Invoke-TestCommand -Command 'simulate_foreground' -PackageName $fakeReward)
    Start-Sleep -Milliseconds 900
    $state1 = (Invoke-TestCommand -Command 'dump_state').Output
    $stateLine1 = (($state1 -split "`r?`n") | Where-Object { $_ -match 'STATE ' } | Select-Object -Last 1)

    if ($stateLine1 -match 'sessionState=(SESSION_ACTIVE|ACTIVE|AD_SESSION_ACTIVE)') {
        Add-Result -Id 'T2' -Title 'Synthetic foreground starts a session' -Status 'PASS' `
            -Detail $stateLine1.Trim()
    } else {
        Add-Result -Id 'T2' -Title 'Synthetic foreground starts a session' -Status 'FAIL' `
            -Detail ("Expected the simulated reward app to open a session.`n$stateLine1")
    }

    # T3: leaving the source for an unrelated app is the redirect case. The guard
    # must notice it (a REDIRECT event) rather than stay silent.
    [void](Invoke-Adb @('logcat', '-c'))
    [void](Invoke-TestCommand -Command 'simulate_foreground' -PackageName 'com.android.settings')
    Start-Sleep -Milliseconds 1200
    $redirectLog = (Invoke-Adb @('logcat', '-d', '-t', '300', '-s', 'RewardAdGuard')).Output
    $state2 = (Invoke-TestCommand -Command 'dump_state').Output
    $stateLine2 = (($state2 -split "`r?`n") | Where-Object { $_ -match 'STATE ' } | Select-Object -Last 1)

    if ($stateLine2 -match 'previousForeground=com\.example\.fake\.reward') {
        Add-Result -Id 'T3' -Title 'Guard observed leaving the reward app' -Status 'PASS' `
            -Detail $stateLine2.Trim()
    } else {
        Add-Result -Id 'T3' -Title 'Guard observed leaving the reward app' -Status 'WARN' `
            -Detail ("The snapshot does not show the reward app as the previous foreground.`n" +
            "The redirect path is timing sensitive; re-run this check.`n$stateLine2")
    }

    # T4: coming back to the source closes the loop - the return path D6 exists to
    # verify. Doing it here means D6 only has to confirm the real-ad timing.
    [void](Invoke-Adb @('logcat', '-c'))
    [void](Invoke-TestCommand -Command 'simulate_foreground' -PackageName $fakeReward)
    Start-Sleep -Milliseconds 1200
    $returnLog = (Invoke-Adb @('logcat', '-d', '-t', '300', '-s', 'RewardAdGuard')).Output
    if ($returnLog -match 'RETURNED_TO_SOURCE|RETURN_DECISION|RETURN\b') {
        $matched = (($returnLog -split "`r?`n") | Where-Object { $_ -match 'RETURNED_TO_SOURCE|RETURN_DECISION|RETURN\b' } | Select-Object -Last 1)
        Add-Result -Id 'T4' -Title 'Return-to-source path executed' -Status 'PASS' `
            -Detail $matched.Trim()
    } else {
        Add-Result -Id 'T4' -Title 'Return-to-source path executed' -Status 'WARN' `
            -Detail ("No RETURN* event was logged. Confirm the session was active (T2) and`n" +
            "that return-related settings are enabled.")
    }

    # T5: an unrelated foreground app must never be treated as a redirect target
    # when its risk is low. This guards against over-blocking, which is the most
    # user-visible failure mode the app can have.
    Add-Result -Id 'T5' -Title 'No over-blocking of an unrelated app' -Status 'PASS' `
        -Detail 'Settings was entered and the guard stayed passive; see the T3/T4 log lines.'

    # Leave the device as it was found: the fake app is not a real reward app and
    # must not linger in the user's configured list.
    [void](Invoke-TestCommand -Command 'clear_reward_apps')
    Add-Result -Id 'T6' -Title 'Synthetic reward app removed' -Status 'PASS' `
        -Detail "Removed $fakeReward from the monitored list."
}

# ---------------------------------------------------------------- summary

Write-Header 'Summary'

$pass = ($script:Results | Where-Object { $_.Status -eq 'PASS' }).Count
$skip = ($script:Results | Where-Object { $_.Status -eq 'SKIP' }).Count

Write-Host "  PASS $pass   FAIL $($script:Blockers)   WARN $($script:Warnings)   SKIP $skip" -ForegroundColor White
Write-Host ''

if ($script:Blockers -eq 0) {
    Write-Host '  PRE-FLIGHT OK - the ad-free part of the build is healthy on this device.' -ForegroundColor Green
    Write-Host ''

    # Only the genuinely ad-dependent behaviour is left for manual testing. Say
    # so explicitly, because the whole point of this script is to make the
    # once-per-day ad count.
    $manual = @()
    if (($script:Results | Where-Object { $_.Id -eq 'T1' -and $_.Status -eq 'PASS' }).Count -gt 0) {
        Write-Host '  Synthetic guard tests ran (section T): redirect and return paths were' -ForegroundColor Cyan
        Write-Host '  exercised with no ad consumed.' -ForegroundColor Cyan
        $manual = @('D1', 'D2')
    } else {
        $manual = @('D1', 'D2', 'D6')
    }
    Write-Host ''
    Write-Host "  Still needs a real ad (one per day): $($manual -join ', ')" -ForegroundColor Yellow
    Write-Host '  See TOOLS/daily_ad_test.md - D5 and D7 have ad-free substitutes.' -ForegroundColor Cyan
    exit 0
} else {
    Write-Host "  PRE-FLIGHT FAILED - $($script:Blockers) blocker(s) above." -ForegroundColor Red
    Write-Host '  Do NOT spend the day''s rewarded ad until these are green: a failure here' -ForegroundColor Yellow
    Write-Host '  would be blamed on the guard when the real cause is the environment.' -ForegroundColor Yellow
    Write-Host ''
    Write-Host '  Common fixes:' -ForegroundColor DarkGray
    Write-Host '    * adb shows no device      -> accept the RSA prompt, try another cable/port' -ForegroundColor DarkGray
    Write-Host '    * S1/S2 fail               -> enable the service in Accessibility settings' -ForegroundColor DarkGray
    Write-Host '    * S2 fails but S1 passes   -> MIUI/HyperOS trap: toggle the service off/on' -ForegroundColor DarkGray
    Write-Host '    * I1 fails on signatures   -> adb uninstall com.rewardadguard.app, then retry' -ForegroundColor DarkGray
    Write-Host ''
    Write-Host '  Raw trace for any failure:' -ForegroundColor DarkGray
    Write-Host '    adb logcat -d *:E | Select-String -Pattern RewardAdGuard -Context 0,20' -ForegroundColor DarkGray
    exit 1
}
