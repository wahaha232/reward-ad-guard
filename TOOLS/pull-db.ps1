<#
    Pull the app's SQLite database off the device without corrupting it.

    Why not `adb shell cat > file`: adb's shell streams the file through a PTY,
    which rewrites line endings and strips binary bytes - the result is a file
    sqlite3 rejects with "not a database". `adb exec-out` is the raw binary
    channel, and .NET writes the bytes verbatim. PowerShell must not see the
    bytes at all (it would split them into an array and re-encode), so the
    process writes straight to a file handle.

    Also pulls the DataStore preferences file, so an analysis sees the exact
    settings in force (ISS-11).

    Usage:  .\TOOLS\pull-db.ps1 [-OutDir <dir>] [-Adb <path-to-adb>]
#>
[CmdletBinding()]
param(
    [string]$OutDir = "$PSScriptRoot\..\.git\devicedump",
    [string]$Package = 'com.rewardadguard.app',
    [string]$Adb = ''
)

$ErrorActionPreference = 'Stop'

# adb lives in a different place on every machine, so resolve it instead of
# relying on PATH (ISS-11). Mirrors auto_test.ps1's Resolve-Adb.
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
    throw 'adb.exe not found. Pass -Adb, set $env:ADB, or add platform-tools to PATH.'
}

# Windows PowerShell 5.1 has no ternary operator, and Serial is an automatic
# variable that only exists when adb was started with -s, so it is checked by
# name rather than used directly.
$serial = $null
if (Get-Variable -Name Serial -ErrorAction SilentlyContinue) { $serial = $Serial }
$null = New-Item -ItemType Directory -Force -Path $OutDir

# Databases are in WAL mode, so the -wal and -shm siblings carry the most recent
# writes. All three must be copied together or the copy is inconsistent. The
# DataStore preferences file holds the settings.
$files = @(
    @{ remote = "/data/data/$Package/databases/reward_ad_guard.db";     name = 'reward_ad_guard.db' },
    @{ remote = "/data/data/$Package/databases/reward_ad_guard.db-wal"; name = 'reward_ad_guard.db-wal' },
    @{ remote = "/data/data/$Package/databases/reward_ad_guard.db-shm"; name = 'reward_ad_guard.db-shm' },
    @{ remote = "/data/data/$Package/files/datastore/reward_ad_guard_settings.preferences_pb";
       name = 'reward_ad_guard_settings.preferences_pb' }
)

foreach ($f in $files) {
    $local = Join-Path $OutDir $f.name

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $Adb
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $args = @()
    if ($serial) { $args += @('-s', $serial) }
    $args += @('exec-out', 'run-as', $Package, 'cat', $f.remote)
    $psi.Arguments = ($args | ForEach-Object {
        if ([string]$_ -match '\s') { '"' + $_ + '"' } else { $_ }
    }) -join ' '

    $proc = [System.Diagnostics.Process]::Start($psi)
    try {
        # Read the raw bytes as bytes. Reading via StreamReader/BaseStream.CopyTo
        # would decode as UTF-16 (adb's exec-out on this device emits a BOM) and
        # silently corrupt the database. Memory is safe here: these files are
        # only a few MB.
        $ms = New-Object System.IO.MemoryStream
        $proc.StandardOutput.BaseStream.CopyTo($ms)
        [System.IO.File]::WriteAllBytes($local, $ms.ToArray())
        $ms.Dispose()
    } finally {
        if (-not $proc.HasExited) { $proc.WaitForExit() }
    }

    $size = (Get-Item $local).Length
    if ($size -eq 0) {
        # The DataStore file is only created once a setting has been written, so a
        # 0-byte read is a warning for it, not a hard failure.
        Write-Warning "$($f.name) : 0 bytes (exit $($proc.ExitCode))"
    } else {
        Write-Host ("{0,-44} {1,10} bytes" -f $f.name, $size)
    }
    $proc.Dispose()
}

# Copying a WAL database leaves a stale -shm, which confuses sqlite3 into
# thinking another process holds the lock. Removing it lets the -wal replay.
$shm = Join-Path $OutDir 'reward_ad_guard.db-shm'
if (Test-Path $shm) { Remove-Item $shm -Force }

Write-Host "`nPulled to $OutDir" -ForegroundColor Green
