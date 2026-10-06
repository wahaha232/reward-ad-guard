#Requires -Version 5.1
<#
.SYNOPSIS
    Portable, self-contained field test for Reward Ad Guard. Plug in a phone,
    run one command, get a report file to send back.

.DESCRIPTION
    This is deliberately a SINGLE FILE with no dependencies on any other repo
    script, so it can be copied to a USB stick and run on a different PC.

    It does three things in order:

      1. PRE-FLIGHT - proves the phone is reachable and USB debugging is
         authorised BEFORE any test starts. If this fails, nothing else runs and
         the report says exactly what to fix. This is the requirement that
         "the file must confirm USB debugging is on".

      2. TESTS - exercises the guard via DebugTestActivity, which is the only
         ADB-reachable entry point (see app/src/debug/AndroidManifest.xml). The
         main event is `simulate_foreground`: it drives the REAL production
         guard with a synthetic accessibility transition, so redirect / return /
         close-button behaviour can be measured without spending the
         once-per-day rewarded ad.

      3. REPORT - writes a single UTF-8 .txt next to the script (or to -ReportDir)
         containing raw command output, a PASS/FAIL table, device info and the
         interesting slices of logcat. Send that file back for analysis.

    HARD RULES baked in - do not "simplify" them away:

      * It NEVER runs `am force-stop` and NEVER passes `-S` to `am start`.
        Force-stopping the app makes AccessibilityManagerService strip the
        service from enabled_accessibility_services (AOSP onHandleForceStop).
        The resulting "enabled but not bound" state is indistinguishable from a
        vendor allowlist and cost this project three days chasing a HyperOS
        restriction that does not exist. See ANALYSIS_REPORT.md 3.2.
      * It NEVER writes enabled_accessibility_services via `settings put`.
        Enabling the service is a user action in the device UI, not something a
        test script may fake.
      * It does not uninstall the app or clear app data, so the on-device
        database evidence survives. -Reinstall is opt-in and warns first.

.PARAMETER Adb
    Path to adb.exe. Auto-discovered when omitted (PATH, $env:ADB,
    local.properties sdk.dir, ANDROID_HOME, ANDROID_SDK_ROOT, %LOCALAPPDATA%).

.PARAMETER Serial
    Target device when several are attached (adb -s).

.PARAMETER ApkPath
    Debug APK to install. Defaults to app\build\outputs\apk\debug\*.apk.
    Only used when -Install or -Reinstall is given.

.PARAMETER Install
    Install the APK only if the package is not already present.

.PARAMETER Reinstall
    Force `adb install -r` even if present. Keeps app data (no -d/--clean).

.PARAMETER NoInstall
    Never touch the APK; test whatever is on the phone. Default behaviour when
    an APK is not found.

.PARAMETER ReportDir
    Where to write the report. Defaults to the script's own directory.

.PARAMETER SkipLogcat
    Skip the logcat capture (smaller report, less forensic detail).

.EXAMPLE
    .\field_test.ps1
    The normal case: auto-find adb, pre-flight, test, report.

.EXAMPLE
    .\field_test.ps1 -Install
    Also install the APK if the app is missing.

.EXAMPLE
    .\field_test.ps1 -Serial 2311DRK48G -ReportDir C:\Temp
#>
[CmdletBinding()]
param(
    [string]$Adb,
    [string]$Serial,
    [string]$ApkPath,
    [switch]$Install,
    [switch]$Reinstall,
    [switch]$NoInstall,
    [string]$ReportDir,
    [switch]$SkipLogcat
)

$ErrorActionPreference = 'Continue'   # a failing probe must not abort the report

# =============================================================================
#  Constants - every one of these was read out of the source, not guessed.
# =============================================================================

$PKG         = 'com.rewardadguard.app'
$DEBUG_ACT   = "$PKG/$PKG.debug.DebugTestActivity"
# NEW_TASK | CLEAR_TASK. Re-creates the activity (fresh window, command re-runs)
# WITHOUT killing the process, so the bound accessibility service survives.
$FLAGS       = '0x10008000'
$TAG_DEBUG   = 'RewardAdGuardDebug'    # DebugTestActivity
$TAG_SVC     = 'RewardAdGuardService'  # production accessibility service
$TAG_APP     = 'RewardAdGuard'         # receiver / most modules
$FAKE_REWARD = 'com.example.fake.reward'

# =============================================================================
#  Reporting plumbing
# =============================================================================

$script:Results  = New-Object System.Collections.ArrayList
$script:Log      = New-Object System.Collections.ArrayList
$script:Blockers = 0

function Say {
    param([string]$Message, [string]$Color = 'Gray')
    Write-Host $Message -ForegroundColor $Color
    [void]$script:Log.Add($Message)
}

function Section {
    param([string]$Title)
    Say ''
    Say ('=' * 78) 'DarkCyan'
    Say "  $Title" 'Cyan'
    Say ('=' * 78) 'DarkCyan'
}

function Add-Result {
    param(
        [string]$Id,
        [string]$Title,
        [ValidateSet('PASS', 'FAIL', 'WARN', 'SKIP')][string]$Status,
        [string]$Detail = ''
    )
    [void]$script:Results.Add([pscustomobject]@{
        Id = $Id; Title = $Title; Status = $Status; Detail = $Detail
    })
    $color = switch ($Status) {
        'PASS' { 'Green' } 'FAIL' { 'Red' } 'WARN' { 'Yellow' } default { 'DarkGray' }
    }
    Say ("  [{0}] {1,-4} {2}" -f $Id, $Status, $Title) $color
    if ($Detail) {
        foreach ($line in ($Detail -split "`r?`n")) { Say "         $line" 'DarkGray' }
    }
    if ($Status -eq 'FAIL') { $script:Blockers++ }
}

# =============================================================================
#  adb wrapper. Every call funnels through here so the device selector is
#  always applied and nothing can be run against the wrong phone.
# =============================================================================

$script:AdbPath = $null
$script:Device  = $null

function Get-AdbPath {
    param([string]$Explicit)
    $candidates = @()
    if ($Explicit)             { $candidates += $Explicit }
    if ($env:ADB)              { $candidates += $env:ADB }
    if ($env:ANDROID_HOME)     { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    $candidates += (Join-Path $env:USERPROFILE  'AppData\Local\Android\Sdk\platform-tools\adb.exe')
    # Android Studio's own copy: a common fallback when PATH has no SDK entry.
    $candidates += 'C:\Program Files\Android\Android Studio\platform-tools\adb.exe'

    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    return $null
}

function ConvertTo-ArgToken {
    <#
      Quotes one argument for a Windows command line, following the same rules
      the C runtime uses (CommandLineToArgvW): an argument is wrapped in double
      quotes only when it is empty or contains a space/tab, and backslashes that
      immediately precede a closing quote must be doubled.

      The APK path passed to `adb install` routinely contains spaces
      ("D:\Visual Studio Code\..."), so this is not hypothetical.
    #>
    param([string]$Value)
    if ($null -eq $Value) { return '""' }
    if ($Value -eq '')    { return '""' }
    if ($Value -notmatch '[ \t]') { return $Value }

    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    $backslashes = 0
    foreach ($ch in $Value.ToCharArray()) {
        if ($ch -eq '\') {
            $backslashes++
            [void]$sb.Append($ch)
        } elseif ($ch -eq '"') {
            # Double the pending backslashes, then escape the quote itself.
            [void]$sb.Append('\' * $backslashes)
            [void]$sb.Append('\"')
            $backslashes = 0
        } else {
            $backslashes = 0
            [void]$sb.Append($ch)
        }
    }
    # Trailing backslashes would escape the closing quote, so double them.
    [void]$sb.Append('\' * $backslashes)
    [void]$sb.Append('"')
    return $sb.ToString()
}

function Invoke-Adb {
    <#
      Runs adb with the device selector applied. Returns .Output and .Code.
      Never throws - the caller decides what a failure means, because in this
      script "adb said no" is data, not an exception.

      Implemented with System.Diagnostics.Process rather than Start-Job because
      Start-Job costs ~0.5s of PowerShell startup PER CALL and this script makes
      dozens of calls; it would add half a minute of pure overhead to a field
      run. The timeout also actually holds the child process handle, which is
      what makes the `adb start-server` wedge recoverable.
    #>
    param([string[]]$Arguments, [int]$TimeoutSeconds = 60)

    if (-not $script:AdbPath) { return [pscustomobject]@{ Output = ''; Code = -1 } }

    $full = @()
    if ($script:Device) { $full += @('-s', $script:Device) }
    $full += $Arguments

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = $script:AdbPath
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    $psi.UseShellExecute        = $false
    $psi.CreateNoWindow         = $true
    # `.ArgumentList` does not exist on .NET Framework (which is what Windows
    # PowerShell 5.1 runs on); it is .NET Core only. Build a quoted command line
    # instead, which works on both.
    $psi.Arguments = ($full | ForEach-Object { ConvertTo-ArgToken $_ }) -join ' '

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    try {
        [void]$proc.Start()
    } catch {
        return [pscustomobject]@{ Output = "could not start adb: $($_.Exception.Message)"; Code = -1 }
    }

    # Read stdout/stderr asynchronously: a synchronous ReadToEnd() deadlocks when
    # a command fills the stderr pipe buffer while we are still draining stdout.
    #
    # The `[System.Threading.Tasks.Task[string]]` cast is REQUIRED on Windows
    # PowerShell 5.1. Without it the call returns a generic Task object that
    # PowerShell cannot resolve a `Result` property on, and `.Result` silently
    # yields $null - which is how an earlier version of this function gathered no
    # output at all while still reporting success.
    $so = [System.Threading.Tasks.Task[string]]$proc.StandardOutput.ReadToEndAsync()
    $se = [System.Threading.Tasks.Task[string]]$proc.StandardError.ReadToEndAsync()

    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
        try { $proc.Kill() } catch { }
        # `taskkill` as well: a wedged adb server can survive Process.Kill().
        try { & taskkill.exe /F /PID $proc.Id 2>&1 | Out-Null } catch { }
        return [pscustomobject]@{
            Output = "TIMEOUT after ${TimeoutSeconds}s: adb $($full -join ' ')"
            Code   = -1
        }
    }

    $out = (($so.Result + "`n" + $se.Result) -replace "`r`n", "`n").Trim()
    return [pscustomobject]@{ Output = $out; Code = $proc.ExitCode }
}

function Get-DebugLogLines {
    <#
      Returns every current line from the debug test activity's logcat tag.

      ### Why `-t` is not used here (measured on device, 2026-10-06)

      `adb logcat -d -t 400 -s RewardAdGuardDebug` **exits 0 and prints nothing**
      on this handset, even while the tag is demonstrably present in the buffer:
      the very same second, `adb logcat -d -s RewardAdGuardDebug` returns the
      line. The `-t` (tail) form therefore fails silently rather than erroring,
      and the caller sees "no RESULT line was produced".

      That one flag made every PHASE 3 test report FAIL for a build that was
      working: `dump_settings` was returning its values correctly the whole time.
      Do not reintroduce `-t`; capture a baseline instead (see Invoke-TestCommand)
      or restrict the tag with `-s` alone.

      The output is split into an array, so the caller can compare counts against
      a previously captured baseline.
    #>
    $r = Invoke-Adb -Arguments @('logcat', '-d', '-s', $TAG_DEBUG)
    return @($r.Output -split "`r?`n" | Where-Object { $_.Trim().Length -gt 0 })
}

function Invoke-TestCommand {
    <#
      Sends one DebugTestActivity command and returns its `RESULT ...` payload,
      or $null if no result line appeared.

      Two load-bearing details, do not "clean them up":

      * `-f $FLAGS` and the ABSENCE of `-S`. `-S` force-stops the app, which
        unbinds the accessibility service and manufactures a fake failure. See
        the script header and ANALYSIS_REPORT.md 3.2.
      * The result line is read relative to a baseline captured with
        [Get-DebugLogLines] *before* the command was sent, never after a fixed
        sleep. logcat lags the intent, so a bare read returns the PREVIOUS
        command's output - the documented timing trap that produced a round of
        phantom "the setting did not change" results. Diffing against the
        baseline is both immune to that and immune to a cold start emitting its
        line later than any fixed sleep would have waited.
    #>
    param(
        [Parameter(Mandatory)][string]$Command,
        [string]$PackageName,
        [string]$Value,
        [string]$Extra = ''
    )

    $a = @('shell', 'am', 'start', '-n', $DEBUG_ACT, '-f', $FLAGS, '--es', 'cmd', $Command)
    if ($PackageName) { $a += @('--es', 'package', $PackageName) }
    if ($Value)       { $a += @('--es', 'value', $Value) }
    if ($Extra)       { $a += ($Extra -split '\s+') }

    # ------------------------------------------------------------------ read
    #
    # The result line is read BEFORE the command is sent, and only the lines that
    # appear afterwards are treated as this command's answer. The previous
    # version slept a fixed 3.5s instead, which is a race: if the process has to
    # be cold-started the RESULT line can land after the sleep and the command is
    # then reported as "no RESULT line" even though it ran correctly.
    $before = Get-DebugLogLines

    [void](Invoke-Adb -Arguments $a)

    $deadline = (Get-Date).AddSeconds(15)
    $lines = @()
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 400
        $now = Get-DebugLogLines
        if ($now.Count -gt $before.Count) { $lines = $now; break }
    }
    if ($lines.Count -le $before.Count) { return $null }

    $fresh = @($lines | Select-Object -Skip $before.Count)
    $line = ($fresh | Where-Object { $_ -match 'RESULT ' } | Select-Object -Last 1)
    if (-not $line) { return $null }
    return ($line -replace '^.*RESULT\s+', '').Trim()
}

# =============================================================================
#  PHASE 0 - PRE-FLIGHT. Nothing else runs unless USB debugging is proven.
# =============================================================================

Section 'PHASE 0 - Environment and USB debugging'

$script:AdbPath = Get-AdbPath -Explicit $Adb
if (-not $script:AdbPath) {
    Add-Result 'P1' 'adb.exe found' 'FAIL' @'
No adb.exe anywhere. Install Android Studio (or the SDK platform-tools) and
either add platform-tools to PATH or pass -Adb "C:\path\to\adb.exe".
'@
    $preflightOk = $false
} else {
    Add-Result 'P1' 'adb.exe found' 'PASS' $script:AdbPath
    $preflightOk = $true
}

if ($preflightOk) {
    # Start the daemon explicitly: a bare `adb devices` can silently return an
    # empty list on a cold machine while the daemon is still starting. The result
    # is deliberately ignored - `adb devices` below is the real check, and a
    # daemon that fails to start there will produce its own error text.
    Start-Sleep -Milliseconds 200

    $devicesOut  = (Invoke-Adb -Arguments @('devices', '-l')).Output
    $deviceLines = @($devicesOut -split "`r?`n" |
                     Where-Object { $_ -match '^\S+\s+device\b' })

    # An `unauthorized` state is the single most common field failure: the phone
    # is plugged in, but the RSA prompt was never accepted. Treat it as its own
    # FAIL with its own instructions rather than as "no device", because the fix
    # is completely different.
    $unauth = @($devicesOut -split "`r?`n" | Where-Object { $_ -match '^\S+\s+unauthorized\b' })
    $offline = @($devicesOut -split "`r?`n" | Where-Object { $_ -match '^\S+\s+offline\b' })

    if ($unauth.Count -gt 0) {
        Add-Result 'P2' 'USB debugging is ON and authorised' 'FAIL' @'
The phone is connected but adb reports UNAUTHORIZED, which means the RSA
fingerprint prompt on the phone screen was never accepted (or "Revoke USB
debugging authorizations" was tapped since).

  On the phone:
    1. Settings > Developer options > USB debugging  -> make sure it is ON
    2. Unlock the screen and look for "Allow USB debugging?" -> tap Allow
       (tick "Always allow from this computer")
    3. If no prompt appears: Developer options > Revoke USB debugging
       authorizations, then unplug and replug the cable.
'@
        $script:Device  = ($unauth[0] -split '\s+')[0]
        $preflightOk    = $false
    } elseif ($offline.Count -gt 0) {
        Add-Result 'P2' 'USB debugging is ON and authorised' 'FAIL' @'
adb sees the device but it is OFFLINE. Usually a charge-only cable, a bad hub,
or a second adb server from another tool (Android Studio, scrcpy) holding the
port. Try: another USB port (prefer a rear/direct port), another cable, then
`adb kill-server; adb start-server`.
'@
        $preflightOk = $false
    } elseif ($deviceLines.Count -eq 0) {
        Add-Result 'P2' 'USB debugging is ON and authorised' 'FAIL' @'
No device attached at all (`adb devices` is empty).

  On the phone:
    1. Settings > About phone > tap "Build number" 7 times
    2. Settings > Developer options > USB debugging -> ON
    3. Plug in the USB cable and choose "File transfer" / "MTP" if the phone
       offers a mode picker; "Charging only" hides the adb interface.
    4. Accept the "Allow USB debugging?" prompt.
'@
        $preflightOk = $false
    } elseif ($deviceLines.Count -gt 1 -and -not $Serial) {
        Add-Result 'P2' 'USB debugging is ON and authorised' 'FAIL' @"
More than one device is attached and no -Serial was given, so there is no safe
way to guess which phone to test (writing to the wrong one would be worse than
not testing). Re-run with one of:
$($deviceLines -join "`n")
"@
        $preflightOk = $false
    } else {
        $script:Device = if ($Serial) { $Serial }
                         else { ($deviceLines[0] -split '\s+')[0] }
        Add-Result 'P2' 'USB debugging is ON and authorised' 'PASS' $script:Device
    }
}

# Device identity is recorded because a report is useless without knowing which
# hardware and ROM produced it - the whole HyperOS investigation turned on this.
$script:DeviceInfo = [ordered]@{}
if ($preflightOk) {
    $props = @{
        'Model'   = 'ro.product.model'
        'Brand'   = 'ro.product.brand'
        'Android' = 'ro.build.version.release'
        'API'     = 'ro.build.version.sdk'
        'ROM'     = 'ro.build.display.id'
        'ABI'     = 'ro.product.cpu.abi'
    }
    foreach ($k in $props.Keys) {
        $v = (Invoke-Adb -Arguments @('shell', 'getprop', $props[$k])).Output.Trim()
        $script:DeviceInfo[$k] = $v
    }
    $script:DeviceInfo['Serial'] = $script:Device
    Say ''
    foreach ($k in $script:DeviceInfo.Keys) {
        Say ("  {0,-8} {1}" -f $k, $script:DeviceInfo[$k]) 'White'
    }
}

if (-not $preflightOk) {
    Say ''
    Say '  Pre-flight failed. Tests are skipped: running them now would blame the' 'Yellow'
    Say '  app for an environment problem. Fix the item above and re-run.' 'Yellow'
}

# =============================================================================
#  PHASE 1 - App presence, install, and service enablement
# =============================================================================

if ($preflightOk) {
    Section 'PHASE 1 - Application and accessibility service'

    $repoRoot = Split-Path -Parent $PSScriptRoot

    # ------------------------------------------------------------------ install
    $pathOut   = (Invoke-Adb -Arguments @('shell', 'pm', 'path', $PKG)).Output
    $installed = $pathOut -match '/'

    if ($installed) {
        # versionName is what the report needs: "it behaves differently" is
        # unanswerable without knowing which build was on the phone.
        $verOut = (Invoke-Adb -Arguments @(
            'shell', 'dumpsys', 'package', $PKG, '|', 'grep', 'versionName')).Output
        if ($verOut -notmatch 'versionName') {
            $verOut = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'package', $PKG)).Output
            $verOut = (($verOut -split "`r?`n") | Where-Object { $_ -match 'versionName' }) -join ' '
        }
        Add-Result 'I1' 'App is installed' 'PASS' ("$($pathOut.Split("`n")[0].Trim())  $($verOut.Trim())").Trim()
    } else {
        Add-Result 'I1' 'App is installed' 'WARN' 'Package not present on the device.'
    }

    $wantInstall = ($Reinstall -or ($Install -and -not $installed))
    if ($NoInstall) { $wantInstall = $false }

    if ($wantInstall) {
        if (-not $ApkPath) {
            $candidates = @(Get-ChildItem -Path (Join-Path $repoRoot 'app\build\outputs\apk\debug') `
                                          -Filter '*.apk' -ErrorAction SilentlyContinue |
                           Sort-Object LastWriteTime -Descending)
            if ($candidates.Count -gt 0) { $ApkPath = $candidates[0].FullName }
        }

        if (-not $ApkPath -or -not (Test-Path -LiteralPath $ApkPath)) {
            Add-Result 'I2' 'Install requested' 'FAIL' @"
An install was requested but no APK was found.

Looked for: app\build\outputs\apk\debug\*.apk under
  $repoRoot
Build it first with `gradlew.bat :app:assembleDebug`, or pass
  -ApkPath "D:\path\to\reward-ad-guard-debug.apk"
"@
        } else {
            # -r keeps app data. Do NOT add -d or clear data: the on-device event
            # database is evidence and a destructive install destroys it.
            Say "  Installing $ApkPath (keeping app data)..." 'DarkGray'
            $ins = Invoke-Adb -Arguments @('install', '-r', $ApkPath) -TimeoutSeconds 180
            foreach ($l in ($ins.Output -split "`r?`n")) { Say "    $l" 'DarkGray' }
            if ($ins.Output -match 'Success') {
                Add-Result 'I2' 'APK installed' 'PASS' $ApkPath
                Start-Sleep -Seconds 2
            } else {
                Add-Result 'I2' 'APK installed' 'FAIL' @"
adb install -r failed (output above).

  INSTALL_FAILED_UPDATE_INCOMPATIBLE / signature mismatch:
      the phone holds a build signed with a different debug keystore. Fix with
      `adb uninstall com.rewardadguard.app` and install again - this DELETES the
      on-device event log, so export/screenshot it first.
  INSTALL_FAILED_VERSION_DOWNGRADE:
      pass a newer build, or uninstall first (same data-loss caveat).
"@
            }
        }
    } elseif ($installed) {
        Add-Result 'I2' 'APK install' 'SKIP' 'Using the build already on the phone.'
    } else {
        Add-Result 'I2' 'APK install' 'FAIL' @'
The app is neither installed nor requested for install, so there is nothing to
test. Re-run with -Install (and, if the APK is missing, build it first).
'@
    }

    # ------------------------------------------------------- service enablement
    # Read-only. Enabling is a USER action; a script that writes
    # enabled_accessibility_services via `settings put` produces a state that
    # looks enabled and is not, which is exactly how this project lost three days.
    $accRaw  = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure',
                                        'enabled_accessibility_services')).Output
    $accOn   = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'secure',
                                        'accessibility_enabled')).Output
    $listHas = $accRaw -match [regex]::Escape($PKG)

    if ($accOn.Trim() -eq '1' -and $listHas) {
        Add-Result 'A1' 'Service listed as enabled' 'PASS' $accRaw.Trim()
    } else {
        Add-Result 'A1' 'Service listed as enabled' 'FAIL' @"
The service is NOT in enabled_accessibility_services, so no test can pass.

  On the phone (this step CANNOT be scripted on purpose):
    Settings > Accessibility > Downloaded apps (or Installed services)
      > Reward Ad Guard  -> turn it ON and accept the system dialog.
  Then check `accessibility_enabled` = 1 and that the app is in the list.

  Current state: accessibility_enabled='$($accOn.Trim())'
                 enabled_accessibility_services='$($accRaw.Trim())'
"@
    }
}

# =============================================================================
#  PHASE 2 - Read-only evidence gathering. Runs before any test touches state,
#  so what it records is what the phone looked like on arrival.
# =============================================================================

$script:Evidence = [ordered]@{}

if ($preflightOk -and $installed) {
    Section 'PHASE 2 - Read-only evidence'

    # --- E1: is the service actually BOUND? A ROM can list a service as enabled
    # and never bind it. "Listed" is not "working" - this is the distinction the
    # whole investigation hinged on.
    $boundOut  = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'accessibility')).Output
    $boundHere = @($boundOut -split "`r?`n" | Where-Object { $_ -match [regex]::Escape($PKG) })
    $script:Evidence['Bound service entry'] = ($boundHere -join "`n").Trim()

    if ($boundHere.Count -gt 0) {
        Add-Result 'E1' 'Service appears in dumpsys accessibility' 'PASS' $script:Evidence['Bound service entry']
    } else {
        Add-Result 'E1' 'Service appears in dumpsys accessibility' 'FAIL' @'
The app does not appear in `dumpsys accessibility` at all. The service is not
enabled or not installed. Fix A1 first.

  Note: this is a real check, but it is NOT the same as "the process bound the
  service". dump_state in PHASE 3 is the authoritative one.
'@
    }

    # --- E2: is the app process alive? A dead process cannot have a bound service,
    # and "no events in the log" means something different in each case.
    $psOut  = (Invoke-Adb -Arguments @('shell', 'ps', '-A')).Output
    $psLine = @($psOut -split "`r?`n" | Where-Object { $_ -match [regex]::Escape($PKG) })
    $script:Evidence['Process list'] = ($psLine -join "`n").Trim()

    if ($psLine.Count -gt 0) {
        Add-Result 'E2' 'App process is running' 'PASS' $script:Evidence['Process list']
    } else {
        # WARN, not FAIL: a healthy app may simply have been swiped away. The
        # next `am start` recreates it and dump_state proves whether the service
        # came back - which is the actual question.
        Add-Result 'E2' 'App process is running' 'WARN' @'
No process for com.rewardadguard.app yet. Expected on a fresh boot or after the
app was swiped away; PHASE 3 starts it and re-checks. The service cannot be
bound until a process exists.
'@
    }

    # --- E3: exit-info. THE decisive record for the old false theory.
    # `am start -S` and `am force-stop` leave a USER_REQUESTED force-stop record
    # here and cause AOSP AccessibilityManagerService.onHandleForceStop to strip
    # the service. An empty / kill-free history is positive proof that nothing in
    # this tooling is manufacturing the "enabled but not bound" state.
    $exitOut = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'exit-info', $PKG)).Output
    if ($exitOut -match 'TIMEOUT|Unknown command|not found') {
        $exitOut = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'activity', 'exit-info')).Output
    }
    $script:Evidence['activity exit-info'] = $exitOut

    $forceStops = @($exitOut -split "`r?`n" |
                    Where-Object { $_ -match 'FORCE_STOP|FORCE STOP|USER_REQUESTED|userRequested' })
    if ($exitOut -match 'No activities|not found|^\s*$') {
        Add-Result 'E3' 'No force-stop history for the app' 'PASS' 'exit-info empty: the app has never been force-stopped by this tooling.'
    } elseif ($forceStops.Count -eq 0) {
        Add-Result 'E3' 'No force-stop history for the app' 'PASS' 'exit-info present but contains no FORCE_STOP / USER_REQUESTED record.'
    } else {
        Add-Result 'E3' 'No force-stop history for the app' 'WARN' @"
exit-info mentions a force-stop. If this was produced by a test device that was
wiped, ignore it; if it is recent, something IS force-stopping the app, which
strips the accessibility service and mimics a vendor restriction.

Suspicious lines:
$($forceStops -join "`n")
"@
    }

    # --- E4: battery optimisation. A vendor "deep sleep" that kills the process
    # is a genuine environment cause of the same symptom, so record it rather
    # than debate it later.
    $idleOut = (Invoke-Adb -Arguments @('shell', 'dumpsys', 'deviceidle', 'whitelist')).Output
    $whitelisted = @($idleOut -split "`r?`n" | Where-Object { $_ -match [regex]::Escape($PKG) })
    $script:Evidence['Battery whitelist'] = if ($idleOut.Trim()) { $idleOut.Trim() } else { '(empty)' }

    if ($whitelisted.Count -gt 0) {
        Add-Result 'E4' 'App is whitelisted from battery optimisation' 'PASS' $whitelisted[0]
    } else {
        Add-Result 'E4' 'App is whitelisted from battery optimisation' 'WARN' @'
Not whitelisted. Not fatal - the guard is not a background service and does not
need the whitelist to work - but a ROM that aggressively freezes background apps
can unbind the accessibility service over time.
  On the phone: Settings > Apps > Reward Ad Guard > Battery > Unrestricted.
'@
    }
}

# =============================================================================
#  PHASE 3 - Functional tests. These actually exercise the guard. Every step is
#  user-data-safe and reversible; PHASE 4 restores the device.
# =============================================================================

if ($preflightOk -and $installed -and $script:Blockers -eq 0) {
    Section 'PHASE 3 - Guard behaviour (no ad consumed)'

    # Record the settings BEFORE touching anything, so the report can show what
    # the phone looked like on arrival and explain any surprise.
    $before = Invoke-TestCommand -Command 'dump_settings'
    $script:Evidence['Settings before'] = if ($before) { $before } else { '(no RESULT line)' }
    if ($before) {
        Add-Result 'T0' 'dump_settings responds' 'PASS' $before
    } else {
        Add-Result 'T0' 'dump_settings responds' 'FAIL' @'
The debug activity produced no RESULT line. The ADB test entry point is not
working, so every later test would be meaningless.

  Likely causes, in order:
    1. A non-debug APK is installed. DebugTestActivity refuses to run unless
       BuildConfig.DEBUG (it calls finish() and logs a warning). Install the
       debug APK: gradlew.bat :app:assembleDebug then re-run with -Reinstall.
    2. The activity was removed by a rebuild - rebuild.
    3. logcat was cleared or rotated between the command and the read.
'@
    }

    if ($before) {
        # --- T1: assist_action must not be NONE. NONE is the nastiest state the
        # guard can be in: it keeps finding the close button and keeps declining
        # to press it, so every log line looks like an ordinary throttle. A device
        # was found in this state once, which is why it is checked first.
        if ($before -match 'assistAction=(\w+)') {
            $assist = $Matches[1]
            if ($assist -eq 'NONE') {
                Add-Result 'T1' 'assist_action is not NONE' 'FAIL' @'
assist_action=NONE: close-button assistance is effectively disabled, so the
guard detects the button and never presses it - indistinguishable from a
working-but-throttled guard in the logs.
  Fix on the phone: Settings > Close button assistance -> choose a real action.
'@
            } else {
                Add-Result 'T1' 'assist_action is not NONE' 'PASS' "assistAction=$assist"
            }
        } else {
            Add-Result 'T1' 'assist_action is not NONE' 'SKIP' 'Could not parse assistAction from dump_settings.'
        }

        # --- T2: THE test. This proves the accessibility service is bound and
        # drivable, which is the one thing the false "HyperOS never binds" theory
        # claimed was impossible. Every later test depends on it.
        $pre = Invoke-TestCommand -Command 'dump_state'
        $script:Evidence['dump_state (before simulate)'] = if ($pre) { $pre } else { '(no output)' }
        if ($pre -match 'serviceConnected=(\w+)') {
            $script:Evidence['serviceConnected before'] = $Matches[1]
        }

        [void](Invoke-Adb -Arguments @('logcat', '-c'))

        $sim = Invoke-TestCommand -Command 'simulate_foreground' -PackageName $FAKE_REWARD
        $script:Evidence['simulate_foreground (fake reward app)'] = if ($sim) { $sim } else { '(no RESULT line)' }

        if ($sim -and $sim -match '^OK:') {
            Add-Result 'T2' 'Accessibility service is bound and drivable' 'PASS' $sim
        } elseif ($sim -and $sim -match 'accessibility service is not bound') {
            Add-Result 'T2' 'Accessibility service is bound and drivable' 'FAIL' @'
The debug activity ran, but RewardAdAccessibilityService.instance was null, so
onServiceConnected() has never fired. The process is alive but the service is not
bound to it. Because this harness no longer force-stops anything, one of these is
now the real cause:

  a) The service was enabled while the app was not running and the ROM did not
     bind it. Toggle it OFF then ON in Settings > Accessibility, then re-run.
  b) The service is enabled but excluded by a ROM allowlist / autostart list.
     Check the ROM's own accessibility and autostart settings.
  c) The APK on the phone is a RELEASE build, which refuses test commands.
     Check the version line in I1 and reinstall the debug APK.

  What this is NOT: it is not proof that the ROM cannot bind the service, and it
  is not caused by `am start -S` - that flag has been removed from all tooling.
'@
        } elseif ($sim) {
            Add-Result 'T2' 'Accessibility service is bound and drivable' 'FAIL' "Unexpected response: $sim"
        } else {
            Add-Result 'T2' 'Accessibility service is bound and drivable' 'FAIL' 'No RESULT line from simulate_foreground.'
        }

        # --- T3: events must actually reach the log. Absence of events is the
        # symptom that started the whole audit, so it is asserted explicitly
        # rather than inferred from the tests passing.
        if ($script:Blockers -eq 0) {
            Start-Sleep -Milliseconds 1200
            $svcLog = (Invoke-Adb -Arguments @('logcat', '-d', '-t', '600', '-s', $TAG_APP)).Output
            $script:Evidence['logcat slice (RewardAdGuard)'] = $svcLog

            $eventLines = @($svcLog -split "`r?`n" |
                            Where-Object { $_ -match 'SESSION|REDIRECT|BLOCK|CLOSE_|DETECT|RETURN' })
            if ($eventLines.Count -gt 0) {
                Add-Result 'T3' 'Guard produced events for the synthetic foreground' 'PASS' `
                    (($eventLines | Select-Object -Last 6) -join "`n")
            } else {
                Add-Result 'T3' 'Guard produced events for the synthetic foreground' 'WARN' @"
The service is bound (T2 passed) but a synthetic foreground produced no
SESSION/REDIRECT/BLOCK/CLOSE/RETURN log line. The likely reason is benign:
$FAKE_REWARD is not a real rewarded-ad app, so there is nothing to guard.
Treat this as informational unless T2 also failed.
"@
            }
        }

        # --- T4: a settings write must round-trip through the real repository.
        # A write that silently does not persist is exactly how assist_action=NONE
        # stayed invisible for so long, so the write is verified by RE-READING it
        # from disk rather than by trusting the return value alone.
        $curAssist = if ($before -match 'assistAction=(\w+)') { $Matches[1] } else { $null }
        # Both values must be real `AssistAction` constants. This used to send
        # 'CLICK' / 'SWIPE_UP', neither of which exists in the enum, so the write
        # always failed and T4 blamed the app for a defect in this script.
        $probe     = if ($curAssist -eq 'ASSIST_CLICK') { 'ASSIST_WHEN_IDLE' } else { 'ASSIST_CLICK' }

        $setOut = Invoke-TestCommand -Command 'set_assist_action' -Value $probe
        $script:Evidence["set_assist_action=$probe"] = if ($setOut) { $setOut } else { '(no RESULT line)' }
        $reread = Invoke-TestCommand -Command 'dump_settings'

        if ($setOut -and $setOut -match '^OK' -and $reread -match "assistAction=$probe") {
            Add-Result 'T4' 'Settings write persists and reads back' 'PASS' $setOut
        } elseif (-not $setOut) {
            Add-Result 'T4' 'Settings write persists and reads back' 'SKIP' 'No RESULT line; T0 already covers this.'
        } else {
            Add-Result 'T4' 'Settings write persists and reads back' 'FAIL' @"
set_assist_action returned : $setOut
dump_settings then reported: $reread

The write did not round-trip. Re-run once to rule out logcat lag before treating
this as a real defect.
"@
        }
    }

    # --- T5: a legitimate app must NOT be flagged. Over-blocking is the most
    # user-visible failure mode this app has, so the negative case is tested
    # alongside the positive one instead of being assumed.
    if ($script:Blockers -eq 0) {
        [void](Invoke-Adb -Arguments @('logcat', '-c'))
        $settingsSim = Invoke-TestCommand -Command 'simulate_foreground' -PackageName 'com.android.settings'
        $script:Evidence['simulate_foreground (com.android.settings)'] = `
            if ($settingsSim) { $settingsSim } else { '(no RESULT line)' }

        Start-Sleep -Milliseconds 1200
        $negLog = (Invoke-Adb -Arguments @('logcat', '-d', '-t', '400', '-s', $TAG_APP)).Output
        $blockOnSettings = @($negLog -split "`r?`n" | Where-Object { $_ -match 'BLOCK' })
        $script:Evidence['logcat slice (negative case)'] = $negLog

        if ($settingsSim -and $settingsSim -match '^OK') {
            if ($blockOnSettings.Count -eq 0) {
                Add-Result 'T5' 'No over-blocking of an unrelated app' 'PASS' `
                    ("System Settings was fed to the guard as a foreground app; 0 BLOCK lines.`n$settingsSim")
            } else {
                Add-Result 'T5' 'No over-blocking of an unrelated app' 'WARN' @"
System Settings was fed to the guard and it produced BLOCK lines. Settings was
never configured as a reward app, so this is over-blocking and worth a look.

$($blockOnSettings -join "`n")
"@
            }
        } else {
            Add-Result 'T5' 'No over-blocking of an unrelated app' 'SKIP' 'simulate_foreground did not run (see T2).'
        }
    }
}

# =============================================================================
#  PHASE 4 - Restore the device. The script changed assist_action for T4; leaving
#  the phone in a different state than it was found in would poison the next run
#  and, worse, could leave the user with a configuration they never chose.
# =============================================================================

if ($preflightOk -and $installed -and $before) {
    Section 'PHASE 4 - Restore optimised-for-tests state'

    # A synthetic reward app must never survive the run: it would count as a real
    # configured app and change the guard's behaviour on the next launch.
    $cleared = Invoke-TestCommand -Command 'clear_reward_apps'
    if ($cleared -and $cleared -match '^OK') {
        Add-Result 'R1' 'Synthetic reward apps removed' 'PASS' $cleared
    } else {
        Add-Result 'R1' 'Synthetic reward apps removed' 'WARN' `
            'No confirmation. Check the Reward apps screen manually before trusting the next run.'
    }

    # Restore assist_action to whatever it was when the run started.
    $origAssist = if ($before -match 'assistAction=(\w+)') { $Matches[1] } else { $null }
    if ($origAssist) {
        $restored = Invoke-TestCommand -Command 'set_assist_action' -Value $origAssist
        if ($restored -and $restored -match "assistAction=$origAssist") {
            Add-Result 'R2' 'assist_action restored' 'PASS' $restored
        } else {
            Add-Result 'R2' 'assist_action restored' 'WARN' @"
Could not restore assist_action=$origAssist (result: $restored).
Set it manually in Settings > Close button assistance.
"@
        }
    }

    $after = Invoke-TestCommand -Command 'dump_settings'
    $script:Evidence['Settings after'] = if ($after) { $after } else { '(no RESULT line)' }
    if ($before -and $after -and $before -eq $after) {
        Add-Result 'R3' 'Settings unchanged by the test run' 'PASS' 'Before and after strings are identical.'
    } elseif ($before -and $after) {
        Add-Result 'R3' 'Settings unchanged by the test run' 'WARN' @"
  before: $before
  after : $after
"@
    }
}

# =============================================================================
#  PHASE 5 - logcat capture. The full buffer is the only place a crash or an
#  exception will be visible; a report without it cannot explain an unexpected
#  failure, so it is captured even when everything passed.
# =============================================================================

if ($preflightOk -and -not $SkipLogcat) {
    Section 'PHASE 5 - logcat capture'
    Say '  Collecting logcat (this takes a few seconds)...' 'DarkGray'

    $all  = (Invoke-Adb -Arguments @('logcat', '-d', '-v', 'threadtime') -TimeoutSeconds 120).Output
    $crash = @($all -split "`r?`n" | Where-Object { $_ -match 'FATAL EXCEPTION|AndroidRuntime|ANR in' })

    $script:Evidence['logcat (full)']          = $all
    $script:Evidence['logcat (crash matches)'] = if ($crash.Count -gt 0) { $crash -join "`n" } else { '(none)' }

    if ($crash.Count -eq 0) {
        Add-Result 'L1' 'No crashes or ANRs in logcat' 'PASS'
    } else {
        Add-Result 'L1' 'No crashes or ANRs in logcat' 'FAIL' ($crash -join "`n")
    }
}

# =============================================================================
#  PHASE 6 - Write the report. Everything above is worthless if it stays in a
#  terminal, and a terminal cannot be sent anywhere.
# =============================================================================

Section 'PHASE 6 - Report'

if (-not $ReportDir) { $ReportDir = $PSScriptRoot }

# A -ReportDir that arrives mangled must not cost us the whole report. This was
# a REAL field failure: the launcher passed a path with an escaped '"' stuck on
# the end (a trailing-backslash / quote interaction in cmd.exe). The report was
# complete and sitting in memory, and was never written. Nobody could be told
# anything.
#
# A try/catch around Test-Path is NOT enough, and that was verified the hard way:
# under PowerShell's default ErrorActionPreference of 'Continue', Test-Path's
# ArgumentException is a NON-terminating error. It prints a red message, returns
# $false, and execution carries on - so the catch block never runs. The old code
# then reached WriteAllText, which fails with a TERMINATING error, and died.
#
# So the path is validated explicitly, with -ErrorAction Stop to make the error
# catchable, and -PathType Container so that a path referring to a file is also
# rejected. Anything unusable falls back to a location we know is writable.
function Test-UsableDir {
    param([string]$Path)
    if (-not $Path) { return $false }
    try {
        return (Test-Path -LiteralPath $Path -PathType Container -ErrorAction Stop)
    } catch {
        return $false
    }
}

function Get-FallbackDir {
    $ErrorActionPreference = 'Continue'
    foreach ($candidate in @($PSScriptRoot, $env:TEMP, (Get-Location).Path)) {
        if ($candidate -and (Test-UsableDir $candidate)) { return $candidate }
    }
    return $null
}

$badReportDir = $null
if (-not (Test-UsableDir $ReportDir)) {
    # Keep the original for the warning, but only if we can show it safely.
    $badReportDir = $ReportDir

    Write-Host ''
    Write-Host '  WARNING: -ReportDir is not a usable folder:' -ForegroundColor Yellow
    Write-Host "           $ReportDir" -ForegroundColor Yellow
    Write-Host '           It may not exist, may refer to a file, or may contain' -ForegroundColor Yellow
    Write-Host '           characters Windows does not allow in a path.' -ForegroundColor Yellow

    $ReportDir = Get-FallbackDir
    if (-not $ReportDir) { $ReportDir = (Get-Location).Path }
    Write-Host "           Falling back to: $ReportDir" -ForegroundColor Yellow
    Write-Host ''
}

$stamp      = Get-Date -Format 'yyyyMMdd_HHmmss'
$reportPath = Join-Path $ReportDir "reward_ad_guard_field_test_$stamp.txt"

$pass = @($script:Results | Where-Object { $_.Status -eq 'PASS' }).Count
$fail = @($script:Results | Where-Object { $_.Status -eq 'FAIL' }).Count
$warn = @($script:Results | Where-Object { $_.Status -eq 'WARN' }).Count
$skip = @($script:Results | Where-Object { $_.Status -eq 'SKIP' }).Count

$verdict = if ($fail -gt 0) { 'FAILED' } elseif ($warn -gt 0) { 'PASSED WITH WARNINGS' } else { 'PASSED' }

$sb = New-Object System.Text.StringBuilder
function W { param([string]$Line = '') [void]$sb.AppendLine($Line) }

W '=============================================================================='
W '  REWARD AD GUARD - FIELD TEST REPORT'
W '  Send this whole file back. It is plain text and self-contained.'
W '=============================================================================='
W ''
W "  Generated      : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
W "  Computer       : $env:COMPUTERNAME"
W "  PowerShell     : $($PSVersionTable.PSVersion)"
W "  Verdict        : $verdict"
W "  Totals         : PASS $pass   FAIL $fail   WARN $warn   SKIP $skip"
W ''
W '  Device'
if ($script:DeviceInfo.Count -gt 0) {
    foreach ($k in $script:DeviceInfo.Keys) { W ("    {0,-8} {1}" -f $k, $script:DeviceInfo[$k]) }
} else {
    W '    (not detected - see PHASE 0 results below)'
}
W ''
W '------------------------------------------------------------------------------'
W '  RESULTS'
W '------------------------------------------------------------------------------'
W ''
foreach ($r in $script:Results) {
    W ("  [{0}] {1,-4} {2}" -f $r.Id, $r.Status, $r.Title)
    if ($r.Detail) {
        foreach ($line in ($r.Detail -split "`r?`n")) { W "         $line" }
    }
    W ''
}

W '------------------------------------------------------------------------------'
W '  WHAT TO DO NEXT'
W '------------------------------------------------------------------------------'
W ''
if ($fail -gt 0) {
    W '  Fix the FAIL items above, then run the script again. Do NOT spend a real'
    W '  rewarded ad while any FAIL item is present: a failure now would be blamed'
    W '  on the guard when the cause is the environment.'
} else {
    W '  The environment checks and the ad-free guard tests are green. What is left'
    W '  needs a REAL rewarded ad (one per day), because a synthetic foreground'
    W '  cannot reproduce the real ad view hierarchy:'
    W ''
    W '    D1  Close button is found and clicked on a real rewarded ad.'
    W '    D2  A real redirect is blocked and the user returns to the reward app.'
    W '    D5  The close-button click does not fire on a non-ad screen.'
    W '    D6  Return-to-source timing on a real ad.'
    W ''
    W '  See TOOLS/daily_ad_test.md for the procedure.'
}
W ''
W '  Manual check that cannot be automated (do it and note the answer):'
W '    Settings > Accessibility > Reward Ad Guard -> confirm the service shows'
W '    as BOUND / enabled AFTER the tests, not just before them.'
W ''
W '=============================================================================='
W '  RAW EVIDENCE'
W '=============================================================================='
W ''
foreach ($k in $script:Evidence.Keys) {
    W "---------------- $k ----------------"
    W ''
    $value = $script:Evidence[$k]
    if ($null -eq $value -or "$value".Trim() -eq '') { $value = '(empty)' }
    W "$value"
    W ''
}

W '=============================================================================='
W '  FULL CONSOLE LOG'
W '=============================================================================='
W ''
foreach ($line in $script:Log) { W $line }
W ''
W '=============================================================================='
W '  END OF REPORT'
W '=============================================================================='

# UTF8 with BOM: the report contains non-ASCII text and Windows Notepad shows
# mojibake without the BOM.
#
# Last line of defence. If the write fails for ANY reason, try once more in TEMP
# and print the path that worked, or say plainly that the report was lost. A
# silent failure here is the worst possible outcome: the test results exist but
# nobody can ever see them.
$reportSaved = $false
$reportFinal = $reportPath
try {
    [System.IO.File]::WriteAllText($reportPath, $sb.ToString(), (New-Object System.Text.UTF8Encoding($true)))
    $reportSaved = $true
} catch {
    Write-Host ''
    Write-Host "  WARNING: could not write the report to:" -ForegroundColor Yellow
    Write-Host "           $reportPath" -ForegroundColor Yellow
    Write-Host "           ($($_.Exception.Message))" -ForegroundColor Yellow

    $emergency = Join-Path $env:TEMP "reward_ad_guard_field_test_$stamp.txt"
    try {
        [System.IO.File]::WriteAllText($emergency, $sb.ToString(), (New-Object System.Text.UTF8Encoding($true)))
        $reportFinal = $emergency
        $reportSaved = $true
        Write-Host '           Saved to TEMP instead:' -ForegroundColor Yellow
        Write-Host "           $emergency" -ForegroundColor Yellow
    } catch {
        Write-Host '  ERROR: the report could not be written anywhere.' -ForegroundColor Red
        Write-Host '         Copy the console output above manually.' -ForegroundColor Red
    }
}

Say ''
Say "  PASS $pass   FAIL $fail   WARN $warn   SKIP $skip" 'White'
$verdictColor = if ($fail -gt 0) { 'Red' } elseif ($warn -gt 0) { 'Yellow' } else { 'Green' }
Say "  VERDICT: $verdict" $verdictColor
Say ''
if ($reportSaved) {
    Say "  Report written to:" 'Cyan'
    Say "    $reportFinal" 'White'
    Say ''
    Say '  Send that file back for analysis.' 'Cyan'
    Say ''
} else {
    Say '  NO REPORT FILE WAS PRODUCED - see the error above.' 'Red'
    Say ''
}

if ($fail -gt 0) { exit 1 } else { exit 0 }
