# 設定共用簽章金鑰（Kilo / 新電腦 / 新 clone 執行一次即可）
#
# 用法（PowerShell，在 repo 根目錄）：
#   powershell -ExecutionPolicy Bypass -File TOOLS\setup_signing.ps1
#
# 做的事：確認金鑰檔存在 → 用 keytool 驗證憑證 SHA-256 是共用金鑰 →
# 把 rewardadguard.signing.* 寫進 local.properties（不進 git，.gitignore 已排除）。
# 沒有這個設定時 Gradle 會改用該電腦自己的 debug 金鑰，建出的 APK 無法覆蓋手機上的版本。
#
# 金鑰是 Android 標準 debug 金鑰（密碼為官方公開預設值 android），
# 備份與說明在 D:\Claude\keystore\KEYSTORE_INFO.txt。金鑰檔本身絕不能放進 repo。
#
# 結束碼：0 成功；2 找不到金鑰檔；3 找不到 keytool；4 指紋不符（不是共用金鑰）
param(
    [string]$Keystore = 'D:\Claude\keystore\android-debug-gpstracker-gpslocator.keystore',
    [string]$JavaHome = $(if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot' })
)
$ErrorActionPreference = 'Stop'
$Expected = '52:0F:63:9B:3A:03:C5:D2:B4:57:CE:DD:9F:34:DA:D6:B1:ED:71:3C:7C:9D:C5:64:09:56:49:B5:EC:43:9D:C8'
$Alias = 'androiddebugkey'
$Password = 'android'
$Root = Split-Path -Parent $PSScriptRoot
$Props = Join-Path $Root 'local.properties'

if (-not (Test-Path $Keystore)) {
    Write-Host "找不到金鑰檔：$Keystore（從另一台電腦的 D:\Claude\keystore 複製過來）"
    exit 2
}
$Keytool = Join-Path $JavaHome 'bin\keytool.exe'
if (-not (Test-Path $Keytool)) {
    Write-Host "找不到 keytool：$Keytool（用 -JavaHome 指定 JDK 17 路徑）"
    exit 3
}
$ErrorActionPreference = 'Continue'
$List = & $Keytool -list -v -keystore $Keystore -alias $Alias -storepass $Password 2>&1 | Out-String
$ErrorActionPreference = 'Stop'
$Match = [regex]::Match($List, 'SHA256:\s*([0-9A-F:]+)')
if (-not $Match.Success -or $Match.Groups[1].Value -ne $Expected) {
    Write-Host "金鑰指紋不符，不是共用金鑰（應為 $Expected），local.properties 未修改"
    exit 4
}

$Lines = @()
if (Test-Path $Props) {
    $Lines = @(Get-Content $Props -Encoding UTF8 | Where-Object { $_ -notmatch '^rewardadguard\.signing\.' })
}
# 新 clone 沒有 local.properties：一併補上 Android SDK 位置
if (-not ($Lines | Where-Object { $_ -match '^sdk\.dir=' })) {
    $Sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { 'D:\Claude\android-sdk' }
    if (Test-Path $Sdk) {
        $Lines = @("sdk.dir=" + $Sdk.Replace('\', '\\').Replace(':', '\:')) + $Lines
    } else {
        Write-Host "注意：找不到 Android SDK（$Sdk），請自行在 local.properties 加 sdk.dir"
    }
}
$StoreFile = $Keystore.Replace('\', '/')
$Lines += "rewardadguard.signing.storeFile=$StoreFile"
$Lines += "rewardadguard.signing.storePassword=$Password"
$Lines += "rewardadguard.signing.keyAlias=$Alias"
$Lines += "rewardadguard.signing.keyPassword=$Password"
[System.IO.File]::WriteAllLines($Props, [string[]]$Lines, (New-Object System.Text.UTF8Encoding $false))
Write-Host "已寫入 $Props（共用金鑰 SHA-256 520f63...，驗證通過）"
exit 0
