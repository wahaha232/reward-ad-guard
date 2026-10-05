<#
    Pull the app's SQLite database off the device without corrupting it.

    Why not `adb shell cat > file`: adb's shell streams the file through a PTY,
    which rewrites line endings and strips binary bytes - the result is a file
    sqlite3 rejects with "not a database". `adb exec-out` is the raw binary
    channel, and .NET writes the bytes verbatim. PowerShell must not see the
    bytes at all (it would split them into an array and re-encode), so the
    process writes straight to a file handle.

    Usage:  .\TOOLS\pull-db.ps1 [-OutDir <dir>]
#>
[CmdletBinding()]
param(
    [string]$OutDir = "$PSScriptRoot\..\.git\devicedump",
    [string]$Package = 'com.rewardadguard.app'
)

$ErrorActionPreference = 'Stop'

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) { throw 'adb not found on PATH.' }

# Windows PowerShell 5.1 has no ternary operator, and Serial is an automatic
# variable that only exists when adb was started with -s, so it is checked by
# name rather than used directly.
$serial = $null
if (Get-Variable -Name Serial -ErrorAction SilentlyContinue) { $serial = $Serial }
$null = New-Item -ItemType Directory -Force -Path $OutDir

# Databases are in WAL mode, so the -wal and -shm siblings carry the most
# recent writes. All three must be copied together or the copy is inconsistent.
$files = @('reward_ad_guard.db', 'reward_ad_guard.db-wal', 'reward_ad_guard.db-shm')
$dir = "/data/data/$Package/databases"

foreach ($name in $files) {
    $remote = "$dir/$name"
    $local = Join-Path $OutDir $name

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'adb'
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $args = @()
    if ($serial) { $args += @('-s', $serial) }
    $args += @('exec-out', 'run-as', $Package, 'cat', $remote)
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
        Write-Warning "$name : 0 bytes (exit $($proc.ExitCode))"
    } else {
        Write-Host ("{0,-32} {1,10} bytes" -f $name, $size)
    }
    $proc.Dispose()
}

# Copying a WAL database leaves a stale -shm, which confuses sqlite3 into
# thinking another process holds the lock. Removing it lets the -wal replay.
$shm = Join-Path $OutDir 'reward_ad_guard.db-shm'
if (Test-Path $shm) { Remove-Item $shm -Force }

Write-Host "`nPulled to $OutDir" -ForegroundColor Green
