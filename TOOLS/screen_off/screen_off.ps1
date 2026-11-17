<#
.SYNOPSIS
  手機螢幕關閉／開啟（像 scrcpy 的關螢幕）：只關面板，手機照常運作。
.DESCRIPTION
  自動收取在螢幕關掉時照樣截圖、點擊，手機不會鎖定。
  要亮回來請執行「開啟」。不要按電源鍵：系統仍當螢幕是亮的，按電源鍵會讓手機真的休眠並鎖定，收取會暫停。
  執行一次就結束，不常駐。
.EXAMPLE
  powershell -ExecutionPolicy Bypass -File screen_off.ps1 off
  powershell -ExecutionPolicy Bypass -File screen_off.ps1 on -Serial 192.168.68.100:5555
#>
param(
    [Parameter(Position = 0)][ValidateSet('off', 'on')][string]$Action = 'off',
    [string]$Serial = ''
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$dex = Join-Path $here 'screenoff.dex'
$remote = '/data/local/tmp/screenoff.dex'

function Find-Adb {
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    foreach ($p in @("$env:ANDROID_HOME\platform-tools\adb.exe", "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe", 'D:\Claude\android-sdk\platform-tools\adb.exe')) {
        if ($p -and (Test-Path $p)) { return $p }
    }
    throw '找不到 adb，請安裝 platform-tools 或設定 ANDROID_HOME。'
}

$adb = Find-Adb
if (-not (Test-Path $dex)) { throw "找不到 $dex" }

# 手機：指定的、或目前連著的那一台（記住上次成功的位址，連不上時試著連回去）
$state = Join-Path $env:LOCALAPPDATA 'screen_off_last_device.txt'
if (-not $Serial) {
    $devices = & $adb devices | Select-String -Pattern "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] }
    if (@($devices).Count -eq 0 -and (Test-Path $state)) {
        $last = (Get-Content $state -Raw).Trim()
        if ($last) { & $adb connect $last | Out-Null; Start-Sleep -Seconds 1
            $devices = & $adb devices | Select-String -Pattern "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] } }
    }
    $devices = @($devices)
    if ($devices.Count -eq 0) { throw '沒有連線中的手機，請先開無線偵錯並 adb connect。' }
    if ($devices.Count -gt 1) { throw ('連著多台手機，請用 -Serial 指定：' + ($devices -join ', ')) }
    $Serial = $devices[0]
} elseif ($Serial -match ':') {
    & $adb connect $Serial | Out-Null
}

# 手機上的程式與這裡的不同（大小不同）才重新推上去
$localSize = (Get-Item $dex).Length
$remoteSize = (& $adb -s $Serial shell "stat -c %s $remote 2>/dev/null").Trim()
if ($remoteSize -ne "$localSize") {
    & $adb -s $Serial push $dex $remote | Out-Null
}

$out = & $adb -s $Serial shell "CLASSPATH=$remote app_process / ScreenOff $Action" 2>&1
$text = ($out | Out-String).Trim()
if ($text -match 'OK (off|on)') {
    if ($Action -eq 'on') {
        # HyperOS turns the panel on without its backlight: nudge the brightness and put it back.
        $level = (& $adb -s $Serial shell settings get system screen_brightness).Trim()
        $mode = (& $adb -s $Serial shell settings get system screen_brightness_mode).Trim()
        if ($level -match '^\d+$') {
            $v = [int]$level
            & $adb -s $Serial shell settings put system screen_brightness_mode 0 | Out-Null
            & $adb -s $Serial shell settings put system screen_brightness $(if ($v -lt 235) { $v + 20 } else { $v - 20 }) | Out-Null
            Start-Sleep -Milliseconds 600
            & $adb -s $Serial shell settings put system screen_brightness $v | Out-Null
            if ($mode -match '^[01]$') { & $adb -s $Serial shell settings put system screen_brightness_mode $mode | Out-Null }
        }
    }
    Set-Content -Path $state -Value $Serial -Encoding ASCII
    if ($Action -eq 'off') { Write-Host "手機螢幕已關閉（$Serial）：手機照常運作。要亮回來請執行「手機螢幕開啟」（不要按電源鍵，按了手機會休眠鎖定）。" }
    else { Write-Host "手機螢幕已開啟（$Serial）。" }
    exit 0
}
Write-Host "失敗（$Serial）：$text"
exit 1
