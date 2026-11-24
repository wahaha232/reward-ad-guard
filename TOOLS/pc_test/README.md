# CASHZINE／獎勵廣告守衛 電腦端測試工具

用電腦讓手機上的守衛跑收取、同時記錄，結束後自動整理成一份精簡的 `REPORT.txt`。
Claude 只要讀 `REPORT.txt`（必要時再看幾張截圖）就能分析、修正，不必全程盯著，省用量。

## 位置與換到別台電腦

主版本在守衛 repo 的 `TOOLS/pc_test/`（wahaha232/reward-ad-guard）。別台電腦 `git pull` 後直接執行
`TOOLS\pc_test\cz_test.cmd`；或只把這個資料夾複製過去也能用（腳本只需要 `cz_test.cmd`、`cz_test.ps1`）。
測試結果 `testruns\` 與個人設定 `cz_test.config.txt` 不進 git。

需要：
- Windows 10／11（內建 PowerShell 5.1 與 tar）
- **adb**：會自動找 PATH、Android SDK、以及本資料夾的 `platform-tools\`。
  都沒有的話，下載 Google「SDK Platform-Tools for Windows」，解壓成 `tools\platform-tools\adb.exe`。
- 手機開**無線偵錯**（同一網路）或接 USB 開 USB 偵錯；第一次連線手機會跳授權，按允許。

不需要 Python、原始碼或 JDK（只有 `install -Build` 才要）。

## 用法（雙擊或在命令列執行）

| 指令 | 做什麼 | 會操作手機？ |
|---|---|---|
| `cz_test.cmd` | 跑一輪 CASHZINE 收取並記錄（預設最長 60 分鐘） | 會（守衛點擊） |
| `cz_test.cmd dry` | 乾跑：只判斷不點擊 | 只開 App |
| `cz_test.cmd snap` | 你在手機上切畫面，回電腦按 Enter 記錄「守衛讀到的文字＋截圖」，輸入 q 結束 | 不會 |
| `cz_test.cmd pull` | 只抓手機上最新一輪收取紀錄並分析（你自己在手機上按開始的也行） | 不會 |
| `cz_test.cmd install` | 從 GitHub 下載最新版守衛並安裝 | 安裝 |
| `cz_test.cmd install -Apk 檔案.apk` | 安裝指定的 APK | 安裝 |
| `cz_test.cmd analyze -Dir 資料夾` | 重新分析某次測試 | 不會 |

其他參數（PowerShell 寫法，接在模式後面）：
- `-App bitwalk`：測別的 App（cashzine、bitwalk、ahealth、walkearn、hahago、linewalk、linelottery）
- `-TimeoutMin 30`：最長幾分鐘，超過就停止收取
- `-FrameSec 20`：電腦每幾秒截一張手機畫面（0＝不截）
- `-Device 192.168.137.219:5555`：指定手機位址
- `-Run run_20261124_132259`：pull 指定某一輪

執行中摸手機螢幕會讓守衛暫停；懸浮面板的「停止」會結束這一輪，腳本會照常抓紀錄。

## 結果

`tools\testruns\<日期_時間>_<模式>\`：

| 檔案 | 內容 |
|---|---|
| `REPORT.txt` | **給 Claude 讀的精簡報告**：摘要、警示（誤點頁面上方、跳到別的 App、找不到、卡住）、步驟、時間軸 |
| `run\` | 守衛在手機上寫的原始紀錄（events.jsonl、summary.json、shots\ 截圖） |
| `frames\` | 電腦定時截的手機畫面（已縮成寬 540 的 JPG） |
| `foreground.txt` | 前景 Activity 變化 |
| `logcat.txt` | 守衛的 log |
| `script.log` | 腳本自己的紀錄 |

測完跟 Claude 說「測試好了」並給資料夾名稱（或把資料夾壓縮傳 TG）即可。

## 結束碼

0 完成；2 找不到手機；3 手機上沒有測試入口（先 `install`）；4 已有收取在跑／沒開始；5 建置、下載或安裝失敗；6 找不到 adb

## 設定檔（可選）

本資料夾的 `cz_test.config.txt`，每行 `key=value`：

```
device=192.168.137.219:5555
adb=C:\platform-tools\adb.exe
output=D:\測試紀錄
repo=D:\Visual Studio Code\PROJECT\Reward Ad Guard
jdk=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot
```

## 注意

- 雲端更新裝的守衛就是 debug 版，有測試入口，不用另外建置。
- 安裝一律覆蓋（`adb install -r`），**不要移除重裝**：會清掉守衛的資料、無障礙授權與省電白名單。簽章不符時腳本會停下來。
- `run`、`dry`、`snap` 會實際接管或讀取手機畫面，測試時手機不要拿來做別的事。
