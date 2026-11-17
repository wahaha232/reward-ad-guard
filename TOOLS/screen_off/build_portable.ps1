# 打包攜帶版：exe + adb（platform-tools 三個檔案）+ 使用說明，壓成 zip。
# 用法：powershell -ExecutionPolicy Bypass -File build_portable.ps1 [-Out D:\Claude] [-PlatformTools <資料夾>]
param(
    [string]$Out = 'D:\Claude',
    [string]$PlatformTools = ''
)
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $here 'build_exe.ps1')

if (-not $PlatformTools) {
    $adb = (Get-Command adb -ErrorAction SilentlyContinue)
    $PlatformTools = if ($adb) { Split-Path -Parent $adb.Source } else { 'D:\Claude\android-sdk\platform-tools' }
}
foreach ($f in 'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll') {
    if (-not (Test-Path (Join-Path $PlatformTools $f))) { throw "找不到 $f（$PlatformTools）" }
}

$name = '手機螢幕開關_攜帶版'
$dir = Join-Path $Out $name
if (Test-Path $dir) { Remove-Item $dir -Recurse -Force }
New-Item -ItemType Directory -Path (Join-Path $dir 'platform-tools') | Out-Null
Copy-Item (Join-Path $here '手機螢幕開關.exe') $dir
foreach ($f in 'adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll') { Copy-Item (Join-Path $PlatformTools $f) (Join-Path $dir 'platform-tools') }

$readme = @"
手機螢幕開關（攜帶版）
======================

只關掉 Android 手機的螢幕面板，手機照常運作（像 scrcpy 的「關閉螢幕」）。
收取獎勵等自動化程式在螢幕關閉時照樣截圖、點擊，手機也不會鎖定。

使用方式
1. 手機：開發人員選項 > 開啟「無線偵錯」（或用 USB 連線並允許偵錯）。
   第一次在這台電腦使用無線偵錯，需要先配對：
     platform-tools\adb.exe pair <手機顯示的配對位址> 再輸入配對碼
2. 雙擊「手機螢幕開關.exe」。
   - 上方顯示連線狀態；沒連上時，在位址欄輸入手機「無線偵錯」頁的 IP:連接埠，按「連線」。
   - 按「關閉螢幕」或「開啟螢幕」，成功後視窗 2 秒自動關閉。
3. 要讓螢幕亮回來，請用「開啟螢幕」。不要按手機電源鍵：
   系統仍當螢幕是亮的，按電源鍵會讓手機真的休眠並鎖定。

命令列
  手機螢幕開關.exe off [IP:連接埠]
  手機螢幕開關.exe on  [IP:連接埠]
  （結束代碼 0 = 成功）

需求
- Windows 10 / 11（內建 .NET Framework 4.x，不必另外安裝）
- 手機 Android 10 以上（已在 Android 16 / HyperOS 實測）
- platform-tools 資料夾請和 exe 放在一起（內含 adb）
"@
Set-Content -Path (Join-Path $dir '使用說明.txt') -Value $readme -Encoding UTF8

$zip = Join-Path $Out "$name.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Compress-Archive -Path $dir -DestinationPath $zip
Write-Host "portable: $dir"
Write-Host "zip:      $zip"
