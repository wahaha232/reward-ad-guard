# CASHZINE / 獎勵廣告守衛 電腦端測試腳本（由 cz_test.cmd 呼叫；任何一台 Windows 電腦都能用）
#
# 需要：Windows PowerShell 5.1（Windows 內建）、adb（找不到時見 README）、手機開無線偵錯或 USB 偵錯。
# 不需要：Python、原始碼、JDK（只有 install -Build 才需要）。
#
# 模式：
#   quick   CASHZINE 只測短時間能收的：+60、首頁金幣、Lucky Draw（時段內）、看小說 10 分鐘、看影片領 +600
#   run     讓手機上的守衛跑一輪收取，同時記錄前景 Activity、定時截圖、logcat，
#           結束後抓回手機的收取紀錄並產生 REPORT.txt
#   dry     同 run，但守衛只判斷不點擊（乾跑）
#   snap    你在手機上手動切畫面，每按一次 Enter 記錄「守衛讀到的文字＋截圖」
#   pull    只把手機上最新一輪（或 -Run 指定）收取紀錄抓回來分析
#   install 安裝守衛到手機：預設從 GitHub 下載最新版；-Apk 指定檔案；-Build 從原始碼建置
#   analyze 重新分析一個已存在的測試資料夾（-Dir）
#
# 結果放在本工具資料夾旁邊的 testruns\<時間>_<模式>\，看 REPORT.txt。
# 結束碼：0 完成；2 找不到手機；3 手機上沒有守衛的測試入口；4 已有收取在跑/沒開始；
#         5 建置/下載/安裝失敗；6 找不到 adb

param(
    [ValidateSet('run', 'quick', 'dry', 'snap', 'pull', 'install', 'analyze')]
    [string]$Mode = 'run',
    [string]$App = 'cashzine',
    # CASHZINE 只跑這些步驟（逗號分隔）：checkin,watch60,lucky,coins,reading,extra；空白＝全部
    [string]$Steps = '',
    # CASHZINE 看小說最多幾分鐘（0＝守衛預設 45 分）
    [int]$ReadMin = 0,
    [int]$Limit = 0,
    [int]$TimeoutMin = 60,
    [int]$FrameSec = 20,
    [string]$Device = '',
    [string]$Run = '',
    [string]$Apk = '',
    [switch]$Build,
    [string]$Repo = '',
    [string]$Dir = ''
)

# Continue：PowerShell 5.1 會把 adb 寫到 stderr 的正常訊息當成錯誤而中止
$ErrorActionPreference = 'Continue'
try { [Console]::OutputEncoding = [Text.UTF8Encoding]::new($false) } catch { }

$Pkg = 'com.rewardadguard.app'
$DebugAct = "$Pkg/com.rewardadguard.app.debug.DebugTestActivity"
$GitHubRepo = 'wahaha232/reward-ad-guard'
$ToolDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ConfigFile = Join-Path $ToolDir 'cz_test.config.txt'

# ------------------------------------------------------------------ config
# cz_test.config.txt（可選）：每行 key=value，例如 device=192.168.137.219:5555
$Config = @{}
if (Test-Path $ConfigFile) {
    foreach ($line in Get-Content $ConfigFile -Encoding utf8) {
        if ($line -match '^\s*([A-Za-z]+)\s*=\s*(.*?)\s*$' -and $line -notmatch '^\s*#') { $Config[$Matches[1].ToLower()] = $Matches[2] }
    }
}
if (-not $Device -and $Config['device']) { $Device = $Config['device'] }
if (-not $Repo -and $Config['repo']) { $Repo = $Config['repo'] }
if (-not $Repo -and $env:REWARD_AD_GUARD_REPO) { $Repo = $env:REWARD_AD_GUARD_REPO }

$Root = if ($Config['output']) { $Config['output'] } else { Join-Path $ToolDir 'testruns' }
$Stamp = Get-Date -Format 'yyyyMMdd_HHmmss'
$Out = if ($Mode -eq 'analyze') { $Dir } else { Join-Path $Root "${Stamp}_$Mode" }
if (-not $Out) { Write-Host 'analyze 要用 -Dir 指定測試資料夾'; exit 1 }
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$Log = Join-Path $Out 'script.log'
# 結束碼：0 完成 2 找不到手機 3 沒有測試入口（先 install）4 已有收取在跑/沒開始 5 建置/下載/安裝失敗 6 找不到 adb

function Say([string]$msg) {
    $line = (Get-Date -Format 'HH:mm:ss') + ' ' + $msg
    Write-Host $line
    Add-Content -Path $Log -Value $line -Encoding utf8
}

# ------------------------------------------------------------------ adb

function Find-Adb {
    $cands = @()
    if ($Config['adb']) { $cands += $Config['adb'] }
    $cands += (Join-Path $ToolDir 'platform-tools\adb.exe')
    $cmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($cmd) { $cands += $cmd.Source }
    foreach ($base in @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
            'C:\Android\Sdk', 'D:\Android\Sdk', 'C:\platform-tools', 'D:\platform-tools')) {
        if ($base) { $cands += (Join-Path $base 'platform-tools\adb.exe'); $cands += (Join-Path $base 'adb.exe') }
    }
    foreach ($c in $cands) { if ($c -and (Test-Path $c)) { return (Resolve-Path $c).Path } }
    return $null
}

$AdbExe = Find-Adb
if (-not $AdbExe -and $Mode -ne 'analyze') {
    Say '找不到 adb。請下載 Android SDK Platform-Tools 解壓到本工具資料夾的 platform-tools\，或在 cz_test.config.txt 寫 adb=完整路徑'
    exit 6
}

function AdbRaw { & $AdbExe @args 2>$null | Out-String }

function Adb {
    # 文字輸出；stderr 丟掉。shell 一律加 -n：沒有鍵盤輸入時 adb shell 會一直等標準輸入
    $a = @($args)
    if ($a.Count -gt 0 -and $a[0] -eq 'shell') { $a = @('shell', '-n') + $a[1..($a.Count)] | Where-Object { $_ -ne $null } }
    $o = & $AdbExe -s $script:Serial @a 2>$null | Out-String
    if ($null -eq $o) { $o = '' }
    return $o
}

function AdbToFile([string]$file, [string[]]$argv) {
    # 二進位輸出（截圖、tar）直接寫檔；Start-Process 的導向不會改動位元組
    $p = Start-Process -FilePath $AdbExe -ArgumentList (@('-s', $script:Serial) + $argv) -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput $file -RedirectStandardError (Join-Path $Out '_err.txt') -RedirectStandardInput (Join-Path $Out '_nul.txt')
    return (Test-Path $file) -and ((Get-Item $file).Length -gt 0)
}
Set-Content -Path (Join-Path $Out '_nul.txt') -Value '' -NoNewline

function Online-Devices {
    $t = AdbRaw devices
    return @($t -split "`r?`n" | Where-Object { $_ -match "`tdevice$" } | ForEach-Object { ($_ -split "`t")[0] })
}

function Connect-Phone {
    $found = @(Online-Devices)
    if ($Device -and ($found -contains $Device)) { return $Device }
    if ($found.Count -ge 1 -and -not $Device) { return $found[0] }
    if ($Device) {
        Say "連線 $Device"
        AdbRaw connect $Device | Out-Null
        Start-Sleep -Seconds 2
        if (@(Online-Devices) -contains $Device) { return $Device }
    }
    # 無線偵錯：用 mDNS 找手機廣播的位址
    $mdns = AdbRaw mdns services
    foreach ($m in [regex]::Matches($mdns, '(\d+\.\d+\.\d+\.\d+:\d+)')) {
        AdbRaw connect $m.Groups[1].Value | Out-Null
        Start-Sleep -Seconds 2
        $found = @(Online-Devices)
        if ($found.Count -ge 1) { return $found[0] }
    }
    $found = @(Online-Devices)
    if ($found.Count -ge 1) { return $found[0] }
    return $null
}

function Debug-Cmd([string[]]$extra) {
    Adb shell am start -n $DebugAct -f 0x10008000 @extra | Out-Null
}

function Latest-Run {
    $t = Adb exec-out run-as $Pkg ls files/collector_runs
    # ls 會排成多欄，直接抓所有 run_ 名稱
    $names = @([regex]::Matches($t, 'run_\d{8}_\d{6}') | ForEach-Object { $_.Value } | Sort-Object -Unique)
    if ($names.Count -eq 0) { return '' } else { return $names[-1] }
}

function Run-Summary([string]$name) { return (Adb exec-out run-as $Pkg cat "files/collector_runs/$name/summary.json") }

function Foreground {
    $t = Adb shell dumpsys activity activities
    $m = [regex]::Match($t, 'topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)')
    if ($m.Success) { return $m.Groups[1].Value } else { return '?' }
}

function Pull-Run([string]$name) {
    $tar = Join-Path $Out 'run.tar'
    if (-not (AdbToFile $tar @('exec-out', 'run-as', $Pkg, 'tar', '-cf', '-', "files/collector_runs/$name"))) {
        Say "抓紀錄失敗：$name"
        return $false
    }
    Push-Location $Out
    & tar -xf run.tar 2>$null
    Pop-Location
    Remove-Item $tar -ErrorAction SilentlyContinue
    $src = Join-Path $Out "files\collector_runs\$name"
    if (Test-Path $src) {
        Move-Item $src (Join-Path $Out 'run') -Force
        Remove-Item (Join-Path $Out 'files') -Recurse -Force -ErrorAction SilentlyContinue
        return $true
    }
    Say '解開紀錄失敗（需要 Windows 10 1803 以上內建的 tar）'
    return $false
}

# ------------------------------------------------------------------ install

function Install-Apk([string]$path) {
    Say "安裝 $path"
    $r = Adb install -r $path
    Say ('安裝結果：' + (($r.Trim() -split "`r?`n")[-1]))
    if ($r -notmatch 'Success') {
        Say '安裝失敗：若是簽章不符，手機上的版本是用別的金鑰簽的（不要移除重裝，會清掉資料與無障礙授權）'
        exit 5
    }
    # HyperOS 更新後不會自動重啟無障礙服務：打開 App 一次
    Adb shell am start -n "$Pkg/.ui.MainActivity" | Out-Null
    Start-Sleep -Seconds 5
}

function Download-Latest {
    Say "從 GitHub 下載最新版（$GitHubRepo）"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $rel = Invoke-RestMethod "https://api.github.com/repos/$GitHubRepo/releases/latest" -Headers @{ 'User-Agent' = 'cz-test' }
    $asset = $rel.assets | Where-Object { $_.name -like '*.apk' } | Select-Object -First 1
    if (-not $asset) { Say '最新版沒有 APK'; exit 5 }
    $dest = Join-Path $Out $asset.name
    Invoke-WebRequest $asset.browser_download_url -OutFile $dest -UseBasicParsing
    Say "下載完成 $($rel.tag_name) $([math]::Round((Get-Item $dest).Length / 1MB, 1)) MB"
    return $dest
}

function Build-Apk {
    if (-not $Repo -or -not (Test-Path (Join-Path $Repo 'gradlew.bat'))) {
        Say '找不到原始碼：用 -Repo 指定，或在 cz_test.config.txt 寫 repo=路徑'
        exit 5
    }
    $jdk = $Config['jdk']
    if (-not $jdk) { $jdk = $env:JAVA_HOME }
    if (-not $jdk) {
        $jdk = (Get-ChildItem 'C:\Program Files\Microsoft' -Directory -Filter 'jdk-17*' -ErrorAction SilentlyContinue | Select-Object -First 1).FullName
    }
    if (-not $jdk) { $jdk = 'C:\Program Files\Android\Android Studio\jbr' }
    Say "建置 debug 版（JDK $jdk，約 3～6 分鐘）"
    Push-Location $Repo
    & .\gradlew.bat "-Dorg.gradle.java.home=$jdk" assembleDebug --console=plain *> (Join-Path $Out 'build.txt')
    $ok = $LASTEXITCODE -eq 0
    Pop-Location
    if (-not $ok) { Say '建置失敗，見 build.txt'; exit 5 }
    return (Join-Path $Repo 'app\build\outputs\apk\debug\reward-ad-guard-debug.apk')
}

# ------------------------------------------------------------------ analysis

$NoiseActions = @('snapshot', 'capture_begin', 'ad_frame')
$AlertActions = @{
    'ad_redirect_blocked' = '跳到別的 App（守衛已按返回）'; 'foreground_other' = '點擊後前景變成別的 App'
    'blocked_web_page' = '開到網頁'; 'tap_failed' = '點擊失敗'; 'engine_error' = '引擎錯誤'
    'cz_step_error' = '步驟例外'; 'ad_stuck' = '廣告卡住'; 'ad_timeout' = '廣告逾時'; 'ad_trapped' = '廣告退不出'
    'paused' = '收取暫停'; 'cz_reward_unconfirmed' = '看完廣告但沒確認到獎勵'; 'cz_double_no_ad' = '加倍廣告沒出來'
    'wrong_package_persisted' = '一直不在目標 App'; 'user_switched_app' = '偵測到使用者切 App'
}
$AlertSuffix = @('_not_found', '_unknown', '_missing', '_not_open', '_no_award', '_no_result', '_lost', '_stalled')
$TapActions = @('next_ad', 'corner_skip', 'close_ad_x', 'install_card_back', 'ad_open_store_tap')
$AdActivityHints = @('ads.', 'openadsdk', 'pangle', 'applovin', 'mbridge', 'vungle', 'unity3d', 'ironsource',
    'inmobi', 'chartboost', 'facebook.ads', 'AdActivity', 'Reward', 'Interstitial')

function Clock([double]$ms) { return [DateTimeOffset]::FromUnixTimeMilliseconds([long]$ms).ToLocalTime().ToString('HH:mm:ss') }

function Fields($e, [int]$max = 160) {
    $parts = @()
    foreach ($p in $e.PSObject.Properties) {
        if ($p.Name -in @('t', 'action', 'app')) { continue }
        $parts += ('{0}={1}' -f $p.Name, $p.Value)
    }
    $s = $parts -join ' '
    if ($s.Length -gt $max) { $s = $s.Substring(0, $max) + '…' }
    return $s
}

function Is-AdActivity([string]$a) {
    if (-not $a) { return $false }
    foreach ($h in $AdActivityHints) { if ($a.ToLower().Contains($h.ToLower())) { return $true } }
    return $false
}

function Shrink-Images([string]$folder) {
    if (-not (Test-Path $folder)) { return 0 }
    Add-Type -AssemblyName System.Drawing
    $codec = [Drawing.Imaging.ImageCodecInfo]::GetImageEncoders() | Where-Object { $_.MimeType -eq 'image/jpeg' }
    $ep = New-Object Drawing.Imaging.EncoderParameters 1
    $ep.Param[0] = New-Object Drawing.Imaging.EncoderParameter ([Drawing.Imaging.Encoder]::Quality, [long]70)
    $n = 0
    foreach ($f in Get-ChildItem $folder -File | Where-Object { $_.Extension -in '.png', '.jpg' }) {
        try {
            $img = [Drawing.Image]::FromFile($f.FullName)
            if ($img.Width -le 560) { $img.Dispose(); continue }
            $h = [int]($img.Height * 540 / $img.Width)
            $bmp = New-Object Drawing.Bitmap 540, $h
            $g = [Drawing.Graphics]::FromImage($bmp)
            $g.InterpolationMode = 'HighQualityBicubic'
            $g.DrawImage($img, 0, 0, 540, $h)
            $g.Dispose(); $img.Dispose()
            $dest = [IO.Path]::ChangeExtension($f.FullName, '.jpg')
            $tmp = $dest + '.tmp'
            $bmp.Save($tmp, $codec, $ep); $bmp.Dispose()
            Remove-Item $f.FullName -Force
            Move-Item $tmp $dest -Force
            $n++
        } catch { }
    }
    return $n
}

function Analyze-Run([string]$base, [System.Collections.Generic.List[string]]$L) {
    $run = Join-Path $base 'run'
    $sumPath = Join-Path $run 'summary.json'
    if (-not (Test-Path $sumPath)) { $L.Add('！沒有抓到收取紀錄（run\summary.json 不存在）'); return }
    $s = Get-Content $sumPath -Raw -Encoding utf8 | ConvertFrom-Json
    $L.Add('== 摘要 ==')
    $L.Add(('App {0}  版本 {1} ({2})  結束原因 {3}  時長 {4} 分' -f $s.name, $s.version, $s.commit, $s.endReason, [math]::Round($s.durationMillis / 60000, 1)))
    $L.Add(('獎勵 {0}  廣告 {1}  點擊 {2}  未確認 {3}  逾時 {4}  跳轉 {5}  錯誤 {6}  需手動 {7}' -f `
        $s.rewards, $s.ads, $s.taps, $s.unconfirmed, $s.timeouts, $s.redirects, $s.errors, $s.manual))
    $L.Add('')

    $alerts = New-Object System.Collections.Generic.List[string]
    $timeline = New-Object System.Collections.Generic.List[string]
    $steps = New-Object System.Collections.Generic.List[string]
    $lastAct = ''
    $step = $null; $stepStart = ''; $stepRewards = 0; $rewards = 0; $lastT = ''
    foreach ($raw in [IO.File]::ReadAllLines((Join-Path $run 'events.jsonl'), [Text.Encoding]::UTF8)) {
        if (-not $raw.Trim()) { continue }
        $e = $raw | ConvertFrom-Json
        $a = [string]$e.action
        $t = Clock $e.t
        $lastT = $t
        if ($a -eq 'ad_frame') { if ($e.activity) { $lastAct = [string]$e.activity }; continue }
        if ($NoiseActions -contains $a) { continue }
        if ($a -eq 'reward_collected') { $rewards++ }
        if ($a -eq 'cz_step') {
            if ($step) { $steps.Add(('{0}～{1}  {2,-14} 獎勵 {3}' -f $stepStart, $t, $step, ($rewards - $stepRewards))) }
            $step = $e.step; $stepStart = $t; $stepRewards = $rewards
        }
        $body = Fields $e 120
        if ($AlertActions.ContainsKey($a)) { $alerts.Add("$t $($AlertActions[$a])：$a $body") }
        elseif ($AlertSuffix | Where-Object { $a.EndsWith($_) }) { $alerts.Add("$t 找不到/異常：$a $body") }
        if ($TapActions -contains $a) {
            $y = $e.y
            if (-not (Is-AdActivity $lastAct) -and $y -ne $null -and [int]$y -lt 600) {
                $alerts.Add("$t ⚠疑似誤點頁面上方（不是廣告畫面）：$a ($($e.x),$y) 當時 Activity=$lastAct")
            }
            $body += ' act=' + (($lastAct -split '\.')[-1])
        }
        $timeline.Add("$t $a $body")
    }
    if ($step) { $steps.Add(('{0}～{1}  {2,-14} 獎勵 {3}' -f $stepStart, $lastT, $step, ($rewards - $stepRewards))) }

    $L.Add("== 警示（$($alerts.Count)）==")
    if ($alerts.Count -eq 0) { $L.Add('（無）') } else { $L.AddRange($alerts) }
    $L.Add('')
    if ($steps.Count -gt 0) { $L.Add('== 步驟 =='); $L.AddRange($steps); $L.Add('') }
    # 看小說的滑動會有幾百筆：連續的只留前 3 筆
    $compact = New-Object System.Collections.Generic.List[string]
    $streak = 0
    foreach ($row in $timeline) {
        if ($row -match ' cz_read_\d' -or $row -match ' cz_read_\w+ ' -and $row -notmatch 'cz_reading') {
            $streak++
            if ($streak -gt 3) { continue }
        } else {
            if ($streak -gt 3) { $compact.Add("   …（略過 $($streak - 3) 筆閱讀紀錄）") }
            $streak = 0
        }
        $compact.Add($row)
    }
    $L.Add("== 時間軸（$($compact.Count) 筆，已去掉截圖/廣告畫面雜訊）==")
    $L.AddRange($compact)
    $L.Add('')
    $shotDir = Join-Path $run 'shots'
    if (Test-Path $shotDir) {
        $shots = @(Get-ChildItem $shotDir -File | Sort-Object Name | ForEach-Object { $_.Name })
        $L.Add("== 守衛截圖（$($shots.Count)）run\shots ==")
        foreach ($x in $shots) { $L.Add($x) }
        $L.Add('')
    }
}

function Analyze([string]$base, [switch]$Snaps) {
    $L = New-Object System.Collections.Generic.List[string]
    $L.Add("測試資料夾 $base")
    $L.Add("分析時間 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')")
    $meta = Join-Path $base 'meta.txt'
    if (Test-Path $meta) { $L.Add(((Get-Content $meta -Encoding utf8) -join '  ')) }
    $L.Add('')
    if ($Snaps) {
        $files = @(Get-ChildItem $base -Filter 'snap_*.txt' | Sort-Object Name)
        $L.Add("== 畫面擷取（$($files.Count)）==")
        foreach ($f in $files) {
            $L.Add('--- ' + $f.Name)
            foreach ($row in Get-Content $f.FullName -Encoding utf8) {
                $i = $row.IndexOf('BOX ')
                if ($i -ge 0) { $L.Add($row.Substring($i)) } else { $L.Add($row) }
            }
        }
        Shrink-Images $base | Out-Null
    } else {
        Analyze-Run $base $L
        $fg = Join-Path $base 'foreground.txt'
        if (Test-Path $fg) {
            $L.Add('== 前景 Activity 變化（電腦每 3 秒記錄）==')
            $L.AddRange([string[]](Get-Content $fg -Encoding utf8))
            $L.Add('')
        }
        $frames = Join-Path $base 'frames'
        if (Test-Path $frames) {
            $n = Shrink-Images $frames
            $L.Add("== 電腦定時截圖 frames\（$(@(Get-ChildItem $frames -File).Count) 張，已縮小 $n）==")
            $L.Add('')
        }
        Shrink-Images (Join-Path $base 'run\shots') | Out-Null
        $lc = Join-Path $base 'logcat.txt'
        if (Test-Path $lc) {
            $keep = @(Get-Content $lc -Encoding utf8 | Where-Object { $_ -match 'RESULT|Exception|FATAL|RUN |rror' })
            $L.Add("== logcat 重點（$($keep.Count)）==")
            if ($keep.Count -gt 60) { $keep = $keep[-60..-1] }
            foreach ($k in $keep) { $L.Add($k) }
        }
    }
    [IO.File]::WriteAllLines((Join-Path $base 'REPORT.txt'), $L, [Text.UTF8Encoding]::new($true))
    Remove-Item (Join-Path $base '_err.txt'), (Join-Path $base '_lcerr.txt'), (Join-Path $base '_nul.txt') -ErrorAction SilentlyContinue
    Say "報告：$base\REPORT.txt"
}

# ------------------------------------------------------------------ main

if ($Mode -eq 'analyze') {
    Analyze $Out -Snaps:([bool](Get-ChildItem $Out -Filter 'snap_*.txt' -ErrorAction SilentlyContinue))
    exit 0
}

$script:Serial = Connect-Phone
if (-not $script:Serial) {
    Say '找不到手機：請打開無線偵錯（手機與電腦同一網路），或接 USB 開 USB 偵錯；也可在 cz_test.config.txt 寫 device=IP:5555'
    exit 2
}
$ver = [regex]::Match((Adb shell dumpsys package $Pkg), 'versionName=(\S+)').Groups[1].Value
Say "adb $AdbExe"
Say "手機 $script:Serial  守衛版本 $(if ($ver) { $ver } else { '（未安裝）' })  模式 $Mode"
Set-Content -Path (Join-Path $Out 'meta.txt') -Encoding utf8 -Value @(
    "mode=$Mode", "app=$App", "steps=$Steps", "readmin=$ReadMin", "limit=$Limit", "device=$script:Serial", "guard=$ver",
    "pc=$env:COMPUTERNAME", "started=$(Get-Date -Format s)"
)

if ($Mode -eq 'install') {
    $path = if ($Apk) { $Apk } elseif ($Build) { Build-Apk } else { Download-Latest }
    Install-Apk $path
    $ver = [regex]::Match((Adb shell dumpsys package $Pkg), 'versionName=(\S+)').Groups[1].Value
    Say "手機上的守衛現在是 $ver"
    exit 0
}

$hasDebug = (Adb shell pm dump $Pkg) -match 'DebugTestActivity'
if (-not $hasDebug -and $Mode -ne 'pull') {
    Say '手機上的守衛沒有測試入口（DebugTestActivity）：請先執行 cz_test.cmd install'
    exit 3
}

if ($Mode -eq 'pull') {
    $name = if ($Run) { $Run } else { Latest-Run }
    if (-not $name) { Say '手機上沒有收取紀錄'; exit 0 }
    Say "抓紀錄 $name"
    Add-Content -Path (Join-Path $Out 'meta.txt') -Value "run=$name" -Encoding utf8
    if (Pull-Run $name) { Analyze $Out }
    exit 0
}

if ($Mode -eq 'snap') {
    $i = 0
    Say '在手機上切到要記錄的畫面，回到電腦按 Enter 擷取；輸入 q 再按 Enter 結束'
    while ($true) {
        $k = Read-Host "[$i] Enter=擷取 / q=結束"
        if ($k -eq 'q') { break }
        $fg = Foreground
        Adb logcat -c | Out-Null
        Debug-Cmd @('--es', 'cmd', 'collector_snapshot')
        Start-Sleep -Seconds 6
        $lc = Adb logcat -d -v time -s 'RewardAdGuardDebug:I'
        $boxes = ($lc -split "`r?`n" | Where-Object { $_ -match 'SNAPSHOT|BOX |RESULT' }) -join "`r`n"
        Set-Content -Path (Join-Path $Out ('snap_{0:D2}.txt' -f $i)) -Encoding utf8 -Value ("foreground=$fg`r`n" + $boxes)
        # 守衛擷取時會先把測試畫面退到背景，所以這張截圖就是被讀的畫面
        AdbToFile (Join-Path $Out ('snap_{0:D2}.png' -f $i)) @('exec-out', 'screencap', '-p') | Out-Null
        Say ('[{0}] {1}  文字框 {2} 個' -f $i, $fg, ([regex]::Matches($boxes, 'BOX ')).Count)
        $i++
    }
    Analyze $Out -Snaps
    exit 0
}

# ---------------------------------------------------------------- run / dry

$before = Latest-Run
if ($before -and ((Run-Summary $before) -notmatch 'endReason')) {
    Say "上一輪 $before 還在跑（沒有 summary）：先在手機上按停止，或等它結束"
    exit 4
}

$frames = Join-Path $Out 'frames'
New-Item -ItemType Directory -Force -Path $frames | Out-Null
$lcFile = Join-Path $Out 'logcat.txt'
Adb logcat -c | Out-Null
$lcProc = Start-Process -FilePath $AdbExe -ArgumentList @('-s', $script:Serial, 'logcat', '-v', 'time',
    'RewardAdGuardDebug:I', 'RewardAdGuardService:I', '*:S') -NoNewWindow -PassThru -RedirectStandardOutput $lcFile `
    -RedirectStandardError (Join-Path $Out '_lcerr.txt') -RedirectStandardInput (Join-Path $Out '_nul.txt')

$dry = if ($Mode -eq 'dry') { 'true' } else { 'false' }
if ($Mode -eq 'quick') {
    # 短時間能收的：+60（冷卻中會立刻跳過）、金幣、Lucky Draw（時段內）、看小說、看影片領 +600；簽到一天一次跳過
    if (-not $Steps) { $Steps = 'watch60,coins,lucky,reading,extra' }
    if ($ReadMin -le 0) { $ReadMin = 10 }
    if ($TimeoutMin -eq 60) { $TimeoutMin = 30 }
}
$extra = @()
if ($Steps) { $extra += @('--es', 'steps', $Steps) }
if ($ReadMin -gt 0) { $extra += @('--es', 'readmin', "$ReadMin") }
Say "開始收取 app=$App dryrun=$dry limit=$Limit steps=$(if ($Steps) { $Steps } else { '全部' }) 閱讀上限=$(if ($ReadMin -gt 0) { "$ReadMin 分" } else { '預設' })（最長 $TimeoutMin 分鐘；手機上摸螢幕會暫停，懸浮面板可停止）"
Debug-Cmd (@('--es', 'cmd', 'collector_run', '--es', 'apps', $App, '--es', 'limit', "$Limit", '--es', 'dryrun', $dry) + $extra)

$name = ''
for ($w = 0; $w -lt 15 -and -not $name; $w++) {
    Start-Sleep -Seconds 2
    $l = Latest-Run
    if ($l -and $l -ne $before) { $name = $l }
}
if (-not $name) {
    Say '收取沒有開始（30 秒內沒有新紀錄）：確認守衛的無障礙服務有開'
    Stop-Process -Id $lcProc.Id -ErrorAction SilentlyContinue
    exit 4
}
Say "紀錄名稱 $name"
Add-Content -Path (Join-Path $Out 'meta.txt') -Value "run=$name" -Encoding utf8

$fgFile = Join-Path $Out 'foreground.txt'
$deadline = (Get-Date).AddMinutes($TimeoutMin)
$lastFg = ''
$lastFrame = Get-Date '2000-01-01'
$lastNote = Get-Date
$ended = $false
while ((Get-Date) -lt $deadline) {
    $fg = Foreground
    if ($fg -ne $lastFg) {
        Add-Content -Path $fgFile -Value ((Get-Date -Format 'HH:mm:ss') + ' ' + $fg) -Encoding utf8
        $lastFg = $fg
    }
    if ($FrameSec -gt 0 -and ((Get-Date) - $lastFrame).TotalSeconds -ge $FrameSec) {
        AdbToFile (Join-Path $frames ((Get-Date -Format 'HHmmss') + '.png')) @('exec-out', 'screencap', '-p') | Out-Null
        $lastFrame = Get-Date
    }
    if ((Run-Summary $name) -match 'endReason') { $ended = $true; break }
    if (((Get-Date) - $lastNote).TotalMinutes -ge 2) { Say "收取中…前景 $fg"; $lastNote = Get-Date }
    Start-Sleep -Seconds 3
}
if (-not $ended) {
    Say "超過 $TimeoutMin 分鐘，停止收取"
    Debug-Cmd @('--es', 'cmd', 'collector_stop')
    for ($w = 0; $w -lt 10; $w++) { Start-Sleep -Seconds 3; if ((Run-Summary $name) -match 'endReason') { break } }
}
Stop-Process -Id $lcProc.Id -ErrorAction SilentlyContinue
Say '收取結束，抓回紀錄'
Pull-Run $name | Out-Null
Analyze $Out
Remove-Item (Join-Path $Out '_err.txt'), (Join-Path $Out '_lcerr.txt'), (Join-Path $Out '_nul.txt') -ErrorAction SilentlyContinue
Say "結果：$Out"
exit 0
