#Requires -Version 5.1
<#
.SYNOPSIS
    Unattended remote test run. Start it, walk away, read one report file later.

.DESCRIPTION
    A thin wrapper around the two existing test scripts. It exists because both
    of them were written to be watched: auto_test.ps1 prints to the console and
    returns an exit code but writes NO file, and field_test.ps1 writes a file but
    stops at PHASE 0 if the phone is asleep or unauthorised.

    Neither behaviour is wrong; it just means "run it while I am not at the
    computer" does not work today. This script fixes exactly that and nothing
    else:

      1. WAITS for a device instead of failing immediately. A phone that is
         rebooting, re-authorising or waking up is the normal case when nobody
         is standing there to reseat the cable. It polls for up to -WaitSeconds
         and distinguishes "no device" from "unauthorized", because those need
         different human actions.

      2. RUNS BOTH scripts and captures everything. Output goes to disk both as
         it happens (console.txt) and per step (raw stdout/stderr files), so a
         crash mid-run still leaves the earlier output readable.

      3. NEVER STOPS EARLY ON A FAILURE. A failing step records its exit code and
         the run continues. A partial report that names the failing step is worth
         more than a truncated one, and aborting on the first red line is exactly
         what makes unattended runs useless.

      4. WRITES ONE INDEX FILE. Details land in one directory; INDEX.txt at its
         root is the only file that needs to come back.

    It deliberately does NOT reimplement any check. Every result it reports is
    copied from the child scripts, so the three can never disagree about what
    "PASS" means.

    It records `git rev-parse HEAD` and the APK build time, because the most
    common way a field report wastes an hour is testing a build that is not the
    one under discussion. See known-issues.md, "The installed APK can be stale".

.PARAMETER WaitSeconds
    How long to wait for a usable device before giving up. Default 600 (10 min).

.PARAMETER Serial
    Target device when several are attached (adb -s).

.PARAMETER Adb
    Path to adb.exe. Auto-discovered when omitted.

.PARAMETER OutDir
    Where to write the run directory. Defaults to TOOLS\remote_test_reports.

.PARAMETER SkipInstall
    Passed through to auto_test.ps1; also implies field_test.ps1 -NoInstall.

.PARAMETER Only
    Run a subset: 'auto', 'field', or 'both' (default).

.PARAMETER Apk
    APK to install in the auto step. Defaults to the debug APK path.

.EXAMPLE
    .\TOOLS\remote_test.ps1
    The intended use: start it, leave, come back to one report directory.

.EXAMPLE
    .\TOOLS\remote_test.ps1 -Only field -WaitSeconds 120
    Only the field test, and do not wait long for a phone.

.EXAMPLE
    .\TOOLS\remote_test.ps1 -Serial 2311DRK48G -SkipInstall
#>
[CmdletBinding()]
param(
    [int]$WaitSeconds = 600,
    [string]$Serial,
    [string]$Adb,
    [string]$OutDir,
    [switch]$SkipInstall,
    [ValidateSet('auto', 'field', 'both')][string]$Only = 'both',
    [string]$Apk
)

$ErrorActionPreference = 'Continue'   # a failing step must not abort the run

# =============================================================================
#  Reporting. Every message goes to the console and to console.txt, so the file
#  is the complete record regardless of what the terminal did.
# =============================================================================

function Say {
    param([string]$Message = '', [string]$Color = 'Gray')
    Write-Host $Message -ForegroundColor $Color
    if ($script:SayFile) {
        # AppendAllText rather than a stream: the file must stay complete even if
        # this process is killed, which is the whole point of an unattended run.
        [System.IO.File]::AppendAllText(
            $script:SayFile, "$Message`r`n", (New-Object System.Text.UTF8Encoding($false)))
    }
}

function Section {
    param([string]$Title)
    Say ''
    Say ('=' * 78) 'DarkCyan'
    Say "  $Title" 'Cyan'
    Say ('=' * 78) 'DarkCyan'
}

function Add-Step {
    param([string]$Name, [string]$Status, [string]$Detail = '')
    [void]$script:Steps.Add([pscustomobject]@{ Name = $Name; Status = $Status; Detail = $Detail })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'DarkGray' } }
    Say ("  [{0,-4}] {1}" -f $Status, $Name) $color
    if ($Detail) {
        foreach ($line in ($Detail -split "`r?`n")) { Say "          $line" 'DarkGray' }
    }
}

# =============================================================================
#  adb discovery - same candidate order as the other two scripts, so all three
#  agree about which adb is in play.
# =============================================================================

function ConvertTo-ArgToken {
    <#
      Quotes one argument for a Windows command line, following the same rules the
      C runtime uses (CommandLineToArgvW): wrap in double quotes only when the
      argument is empty or contains a space/tab, and double any backslashes that
      immediately precede a closing quote.

      This is needed because Windows PowerShell 5.1's ProcessStartInfo has NO
      ArgumentList property (verified: PSVersion 5.1.26100, "ArgumentList supported:
      False"); only the .Arguments STRING exists. Joining arguments by hand is
      therefore unavoidable, and the repo path contains spaces
      ("D:\Visual Studio Code\..."), so the quoting is not hypothetical.

      Identical to the helper in field_test.ps1 on purpose: two scripts that quote
      differently would disagree about which file they tested.
    #>
    param([string]$Value)
    if ($null -eq $Value -or $Value -eq '') { return '""' }
    if ($Value -notmatch '[ \t]') { return $Value }

    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    $backslashes = 0
    foreach ($ch in $Value.ToCharArray()) {
        if ($ch -eq '\') {
            $backslashes++
            [void]$sb.Append($ch)
        } elseif ($ch -eq '"') {
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

function Get-AdbPath {
    param([string]$Explicit, [string]$RepoRoot)
    $candidates = @()

    # Bundled copy first-class: TOOLS\platform-tools is how this repo ships adb to
    # a machine with no SDK, and it must win over a stale PATH entry.
    if ($RepoRoot) { $candidates += (Join-Path $RepoRoot 'TOOLS\platform-tools\adb.exe') }

    if ($Explicit)             { $candidates += $Explicit }
    if ($env:ADB)              { $candidates += $env:ADB }
    if ($env:ANDROID_HOME)     { $candidates += (Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe') }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe') }
    $candidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
    $candidates += (Join-Path $env:USERPROFILE  'AppData\Local\Android\Sdk\platform-tools\adb.exe')
    $candidates += 'C:\Program Files\Android\Android Studio\platform-tools\adb.exe'

    foreach ($c in $candidates) {
        if ($c -and (Test-Path -LiteralPath $c)) { return $c }
    }
    $onPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }
    return $null
}

function Invoke-AdbRaw {
    <#
      Runs adb and returns stdout+stderr as one string.

      Redirected through a process rather than captured by PowerShell, because
      PowerShell's native-command handling mangles adb output badly enough that a
      device-state check can read the wrong thing. See known-issues.md, "A
      temp-file pattern that works".
    #>
    param([string[]]$Arguments, [int]$TimeoutSeconds = 30)
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = $script:AdbPath
    $psi.Arguments              = ($Arguments -join ' ')
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    $psi.UseShellExecute        = $false
    $psi.CreateNoWindow         = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $so = $proc.StandardOutput.ReadToEndAsync()
    $se = $proc.StandardError.ReadToEndAsync()
    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
        try { $proc.Kill() } catch { }
        return "TIMEOUT after ${TimeoutSeconds}s"
    }
    return (($so.Result + "`n" + $se.Result) -replace "`r`n", "`n").Trim()
}


$script:Steps   = New-Object System.Collections.ArrayList

# =============================================================================
#  Locate the repo, the tools, and create this run's directory
# =============================================================================

$repoRoot = if ([string]::IsNullOrWhiteSpace($PSScriptRoot)) {
    (Get-Location).Path
} else {
    (Split-Path -Parent $PSScriptRoot)
}

$autoScript  = Join-Path $PSScriptRoot 'auto_test.ps1'
$fieldScript = Join-Path $PSScriptRoot 'field_test.ps1'
if (-not $OutDir) { $OutDir = Join-Path $PSScriptRoot 'remote_test_reports' }

# One directory per invocation: two runs must never overwrite each other, because
# the slower one is usually the one that matters.
$stamp  = Get-Date -Format 'yyyyMMdd_HHmmss'
$runDir = Join-Path $OutDir "run_$stamp"
$null   = New-Item -ItemType Directory -Force -Path $runDir
$script:SayFile = Join-Path $runDir 'console.txt'

Say "Reward Ad Guard - unattended remote test" 'White'
Say "  started   : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Say "  repo      : $repoRoot"
Say "  run dir   : $runDir"

# The build under test must be pinned in the report. A field result that cannot
# be tied to a commit is the single most expensive kind of report.
$headRev = (& git -C $repoRoot rev-parse --short HEAD 2>&1) -join ' '
$headRev = "$headRev".Trim()
Say "  git HEAD  : $headRev"

# =============================================================================
#  Preflight: adb, then wait for a device that is actually usable
# =============================================================================

Section 'PREFLIGHT - adb and device'

$script:AdbPath = Get-AdbPath -Explicit $Adb -RepoRoot $repoRoot
if (-not $script:AdbPath) {
    Say '  No adb.exe found. Install platform-tools, add them to PATH, set $env:ADB,' 'Red'
    Say '  or drop adb.exe into TOOLS\platform-tools\.' 'Red'
    Add-Step 'adb available' 'FAIL' 'adb.exe not found in any known location.'
    # Nothing else can run; fall through to the report so the failure is written
    # down rather than only printed.
    $script:Device = $null
} else {
    Say "  adb       : $script:AdbPath"
    Add-Step 'adb available' 'PASS' $script:AdbPath
}

$script:Device = $null
if ($script:AdbPath) {
    $sel = if ($Serial) { @('-s', $Serial) } else { @() }

    # Waiting, not failing. The person who would reseat the cable is not here, and
    # a phone rebooting or showing the authorisation dialog will come up on its
    # own given a few seconds.
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    $lastSeen = 'no device'
    $tick     = 0
    while ((Get-Date) -lt $deadline) {
        $tick++
        $state = Invoke-AdbRaw -Arguments (@('devices') + $sel)

        # `\s+device(\s|$)` matches the state token only, never "unauthorized"
        # or "offline": the state is a whole whitespace-delimited token, so the
        # trailing group is what stops a serial ending in "device" (or an
        # unauthorised line) from reading as ready. It must NOT anchor with `$` -
        # `adb devices -l` appends "product:... model:... transport_id:...", and an
        # anchored pattern silently reports a connected phone as absent.
        $ready = [regex]::Match($state, '(?m)^(\S+)\s+device(\s|$)')
        if ($ready.Success) {
            $script:Device = $ready.Groups[1].Value
            break
        }

        if ($state -match 'unauthorized') {
            $lastSeen = 'unauthorized'
            if ($tick -eq 1) {
                # Said once, not every poll: this is the one state that needs a
                # human, so repeating it 200 times only buries the message.
                Say '  Device is UNAUTHORIZED - accept the "Allow USB debugging" prompt' 'Yellow'
                Say '  on the phone. Waiting, since nobody is at the keyboard.' 'Yellow'
            }
        } elseif ($state -match 'offline') {
            $lastSeen = 'offline'
        } elseif ($state -match 'more than one device') {
            # Genuinely ambiguous, and no amount of waiting resolves it: the caller
            # must name one with -Serial.
            Say '  More than one device is attached. Re-run with -Serial <id>.' 'Red'
            Add-Step 'Sole device attached' 'FAIL' 'Multiple devices; -Serial is required.'
            $script:Device = $null
            break
        } else {
            $lastSeen = 'no device'
        }

        Start-Sleep -Seconds 3
    }

    if ($script:Device) {
        Add-Step 'Device reachable' 'PASS' "$($script:Device) (waited ~$($tick * 3)s)"
    } elseif ($lastSeen -ne 'no device' -or $tick -gt 0) {
        Add-Step 'Device reachable' 'FAIL' "Gave up after ${WaitSeconds}s; last state: $lastSeen"
    }
}

$script:SayFile = $null


# =============================================================================
#  Step runner
# =============================================================================

function Invoke-ChildScript {
    <#
      Runs one of the existing test scripts and captures its output to disk.

      The exit code is returned, never thrown: a red step is data, not a reason
      to stop the run.

      ### Why System.Diagnostics.Process and not Start-Process -PassThru

      Measured on this machine: `Start-Process ... -PassThru` returns an object
      whose `ExitCode` property reads as EMPTY even after WaitForExit returns
      (and `$p.HasExited` is True). Every step then recorded "exit=" with no
      number, which is precisely the kind of unverifiable report this wrapper
      exists to prevent. The .NET Process class returns the real code (7 in the
      probe). Do not "simplify" this back to Start-Process.
    #>
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Script,
        # Defaults to empty rather than Mandatory: PowerShell rejects an explicit
        # `@()` for a Mandatory [string[]] with "empty array is not allowed", and
        # the ad-free auto_test path legitimately passes no extra arguments.
        [string[]]$Arguments = @(),
        [int]$TimeoutSeconds = 1800,
        [string]$WorkDir
    )

    $tag   = $Name -replace '[^A-Za-z0-9]', '_'
    $oFile = Join-Path $runDir "$tag.stdout.txt"
    $eFile = Join-Path $runDir "$tag.stderr.txt"

    Say ''
    Say "  --- $Name ---" 'Cyan'
    Say "  $([System.IO.Path]::GetFileName($Script)) $($Arguments -join ' ')" 'DarkGray'

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = 'powershell.exe'
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    $psi.UseShellExecute        = $false
    $psi.CreateNoWindow         = $true
    if ($WorkDir) { $psi.WorkingDirectory = $WorkDir }

    # Windows PowerShell 5.1: ProcessStartInfo has no ArgumentList, so the command
    # line must be quoted by hand. Every token goes through ConvertTo-ArgToken -
    # including the script path, which contains spaces on this machine.
    $tokens = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $Script) + $Arguments
    $psi.Arguments = (($tokens | ForEach-Object { ConvertTo-ArgToken $_ }) -join ' ')

    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    $started = Get-Date
    [void]$proc.Start()

    # Read both streams asynchronously into files. Reading them sequentially
    # deadlocks when a child fills the stderr pipe while we are still draining
    # stdout, which is exactly what a chatty failing child does.
    $oTask = $proc.StandardOutput.ReadToEndAsync()
    $eTask = $proc.StandardError.ReadToEndAsync()

    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
        try { $proc.Kill() } catch { }
        Add-Step $Name 'FAIL' "Timed out after ${TimeoutSeconds}s; see $tag.stdout.txt"
        Say "  TIMEOUT after ${TimeoutSeconds}s" 'Red'
        return -1
    }

    $stdout = [string]$oTask.Result
    $stderr = [string]$eTask.Result
    [System.IO.File]::WriteAllText($oFile, $stdout, (New-Object System.Text.UTF8Encoding($false)))
    [System.IO.File]::WriteAllText($eFile, $stderr, (New-Object System.Text.UTF8Encoding($false)))

    $code    = $proc.ExitCode
    $elapsed = [int]((Get-Date) - $started).TotalSeconds

    # Echo the child's output into console.txt so that one file tells the story
    # without needing the sibling raw files.
    foreach ($text in @($stdout, $stderr)) {
        if ($text -and $text.Trim().Length -gt 0) {
            foreach ($line in ($text -replace "`r`n", "`n" -split "`n")) {
                Say "    $line" 'Gray'
            }
        }
    }

    # A child that cannot even be started reports no exit code; that is a FAIL,
    # not a pass-by-default.
    if ($code -eq 0) {
        Add-Step $Name 'PASS' "exit=0 elapsed=${elapsed}s"
    } else {
        Add-Step $Name 'FAIL' "exit=$code elapsed=${elapsed}s"
    }
    return $code
}

# =============================================================================
#  Run. A failed step never stops the run: the report must name the failing step
#  and still contain everything after it.
# =============================================================================

$proceed = [bool]$script:Device

if ($proceed -and ($Only -eq 'auto' -or $Only -eq 'both')) {
    Section 'STEP 1 - auto_test.ps1 (device visible, service bound, settings, ad-free synthetic run)'
    $a = @()
    if ($Serial)         { $a += @('-Serial', $Serial) }
    if ($script:AdbPath) { $a += @('-Adb', $script:AdbPath) }
    if ($Apk)            { $a += @('-ApkPath', $Apk) }
    if ($SkipInstall)    { $a += '-SkipInstall' }
    # Exit 2 = adb missing, exit 1 = a blocker. Both are already recorded, and both
    # are survivable - the field test may still say something useful.
    [void](Invoke-ChildScript -Name 'auto_test' -Script $autoScript -Arguments $a)
}

if ($proceed -and ($Only -eq 'field' -or $Only -eq 'both')) {
    Section 'STEP 2 - field_test.ps1 (pre-flight, evidence, settings, report file)'
    $f = @('-ReportDir', $runDir)
    if ($Serial)         { $f += @('-Serial', $Serial) }
    if ($script:AdbPath) { $f += @('-Adb', $script:AdbPath) }
    if ($Apk)            { $f += @('-ApkPath', $Apk) }
    if ($SkipInstall)    { $f += '-NoInstall' }
    [void](Invoke-ChildScript -Name 'field_test' -Script $fieldScript -Arguments $f)
}

# =============================================================================
#  Extra artefact: the device's own database, which is the authoritative record
#  of what the guard actually did (known-issues.md, "The on-device database is
#  the primary evidence"). Pulled as BYTES via `exec-out`, never as text - a
#  text-mode copy silently corrupts it.
# =============================================================================

if ($proceed) {
    Section 'ARTEFACT - on-device database'
    $dbOut = Join-Path $runDir 'reward_ad_guard.db'
    $args  = @('exec-out', 'run-as', 'com.rewardadguard.app', 'cat', 'databases/reward_ad_guard.db')
    if ($Serial) { $args = @('-s', $Serial) + $args }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = $script:AdbPath
    $psi.Arguments              = ($args -join ' ')
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute        = $false
    $psi.CreateNoWindow         = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $psi
    [void]$proc.Start()
    $fs = [System.IO.File]::Create($dbOut)
    try {
        $proc.StandardOutput.BaseStream.CopyTo($fs)
    } finally {
        $fs.Close()
    }
    [void]$proc.WaitForExit(60000)

    $len   = (Get-Item -LiteralPath $dbOut).Length
    # The SQLite magic is the only proof the pull worked: a refusal from run-as
    # writes a few dozen bytes of text that otherwise looks like a real file.
    $magic = ''
    if ($len -ge 16) {
        $bytes = [byte[]]::new(16)
        $fh = [System.IO.File]::OpenRead($dbOut)
        try { [void]$fh.Read($bytes, 0, 16) } finally { $fh.Close() }
        $magic = [System.Text.Encoding]::ASCII.GetString($bytes)
    }

    if ($magic.StartsWith('SQLite format 3')) {
        Add-Step 'database pulled' 'PASS' "$len bytes, valid SQLite header"
    } elseif ($len -eq 0) {
        Add-Step 'database pulled' 'SKIP' 'Empty: the app has never created its DB, or run-as refused silently.'
    } else {
        Add-Step 'database pulled' 'FAIL' "$len bytes, not a SQLite file - run-as was refused."
    }
}

# =============================================================================
#  INDEX.txt - the only file that needs to come back. Everything above is
#  summarised here so nobody has to guess which file matters.
# =============================================================================

Section 'REPORT'

$indexPath = Join-Path $runDir 'INDEX.txt'
$sb = New-Object System.Text.StringBuilder
$w  = { param($s) [void]$sb.AppendLine($s) }

& $w 'Reward Ad Guard - unattended remote test'
& $w ('=' * 60)
& $w "started     : $stamp"
& $w "finished    : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
& $w "git HEAD    : $headRev"
& $w "computer    : $env:COMPUTERNAME"
& $w "adb         : $script:AdbPath"
& $w "device      : $($script:Device)"
& $w "only        : $Only"
& $w "waitSeconds : $WaitSeconds"
& $w ''

# The pinned build, because an unverifiable report is the expensive kind.
if ($Apk) {
    $apkForTime = $Apk
} else {
    $apkForTime = Join-Path $repoRoot 'app\build\outputs\apk\debug\reward-ad-guard-debug.apk'
}
if (Test-Path -LiteralPath $apkForTime) {
    $fi = Get-Item -LiteralPath $apkForTime
    & $w "APK         : $($fi.FullName)"
    & $w "APK built   : $($fi.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))  ($([int]($fi.Length / 1KB)) KB)"
} else {
    & $w "APK         : NOT FOUND at $apkForTime"
    & $w '              (the install step, if any, used whatever was already on the phone)'
}
& $w ''

& $w 'STEPS'
& $w ('-' * 60)
if ($script:Steps.Count -eq 0) {
    & $w '  (none ran)'
}
foreach ($s in $script:Steps) {
    & $w ("  [{0,-4}] {1}" -f $s.Status, $s.Name)
    if ($s.Detail) {
        foreach ($line in ($s.Detail -split "`r?`n")) { & $w "          $line" }
    }
}
& $w ''

& $w 'CHILD REPORT FILES'
& $w ('-' * 60)
$children = @(Get-ChildItem -LiteralPath $runDir -File |
    Where-Object { $_.Name -like 'reward_ad_guard_field_test_*.txt' } |
    Sort-Object LastWriteTime)
if ($children.Count -eq 0) {
    & $w '  field_test wrote no report (it stops at PHASE 0 when the phone is not ready).'
}
foreach ($c in $children) { & $w "  $($c.Name)  ($([int]($c.Length / 1KB)) KB)" }
& $w ''
& $w '  Read that .txt first: it is the PASS/FAIL table. This INDEX only says'
& $w '  which step ran and with what exit code.'
& $w ''

$failCount = @($script:Steps | Where-Object { $_.Status -eq 'FAIL' }).Count
& $w 'VERDICT'
& $w ('-' * 60)
if (-not $script:Device) {
    & $w '  NO DEVICE - nothing was tested. Check the cable, the RSA prompt and'
    & $w '  that USB debugging is still on, then re-run.'
} elseif ($failCount -eq 0) {
    & $w '  All steps completed with exit 0. Now read the child report for the'
    & $w '  per-check PASS/FAIL detail - a green exit code is not a green feature.'
} else {
    & $w "  $failCount step(s) FAILED - see STEPS above."
}

[System.IO.File]::WriteAllText($indexPath, $sb.ToString(), (New-Object System.Text.UTF8Encoding($true)))

Say ''
Say "  Run directory: $runDir" 'White'
Say "  >>> Send this file back: $indexPath" 'Green'
Say "      (plus the reward_ad_guard_field_test_*.txt beside it)" 'Green'
Say ''

if (-not $script:Device) { exit 2 }
if ($failCount -gt 0)    { exit 1 }
exit 0

