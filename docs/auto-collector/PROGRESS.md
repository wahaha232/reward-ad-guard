# 自動收取開發進度（唯一真相）

最後更新：2026-11-12（MW-EDDIE，第二階段進行中）
分支：`feature/auto-collector`（基底 `origin/fix/close-assist-1102`，已推送）
基準測試：**256 個、0 失敗**（階段 0 基準 224＋階段 1 新增 20＋階段 2 新增 12）

## 階段狀態

| 階段 | 狀態 | 說明 |
|---|---|---|
| 0 準備 | 完成 | repo 乾淨、分支建立、基準測試 224／0 失敗（11-12 再加 20 個階段 1 測試） |
| 1 基礎建設 | 完成 | 程式、審查補強與文件已提交；APK 憑證已驗（符合共用金鑰）。BitWalk 實機驗證仍待手機連上 |
| 2 收取引擎 | 部分完成 | `CollectorRules`／`CollectorEngine`／`RunRecorder`／`collector_run` 已提交；fixtures 測試通過；手機離線，乾跑待實機 |
| 3 BitWalk | 部分完成 | profile、install card、資訊視窗、多則廣告、獎勵對話框已實作並有 fixtures；實機待測 |
| 4 其他 App | 部分完成 | AHEALTH／WALK EARN／HAHAGO profile 已完成，未實機；LINE 散步趣與抽抽樂尚未移植（安全理由） |
| 5 介面與收尾 | 完成（待實機） | 「自動收取」頁籤、常駐通知、手機端紀錄、最近一次紀錄的 zip 匯出（FileProvider）已完成；UI 未經實機走查 |

## 目前在做什麼

程式碼已到階段 5；手機離線，所有實機驗證仍待補。

## 下一步

1. 手機恢復連線後安裝目前 APK（憑證已驗），依序跑 BitWalk dryRun → 真實收取 ≥30 個。
2. 依序驗 AHEALTH、WALK EARN、HAHAGO（每個先 dryRun）。
3. LINE 散步趣／抽抽樂：先移植 choice sheet 的置中與避開 AD 橫幅規則，再上線。
4. 把 `collector_runs/<run>` 接上既有 FileProvider 匯出。

## 2026-11-12 實機進度（手機已連上）

- 無線 adb `192.168.137.219:5555` 連上，安裝 `293c6ee` 的 APK 成功，無障礙服務已連上（`Service[label=獎勵廣告守衛]`）。
- BitWalk 乾跑成功：引擎自己啟動 BitWalk、切到「步行」，選中節點文字「收看廣告影片，獲得5倍的BTC」@ (610,1680)；「賺取」頁籤當下沒有 reward candidate（節點 12、OCR 0）。
- 真實收取（limit 3）在 `ready_0` 後卡住超過 3 分鐘，沒有任何後續事件。已判斷為截圖回呼沒有回來；已加 5 秒截圖 timeout、15 秒 OCR timeout、`capture_begin` 事件與 `collector_stop` 指令。
- 為了中止卡住的執行，使用了 `run-as ... kill -9`；程序被殺後無障礙服務進入 `Crashed services`，需要重新綁定（下次會改用 `collector_stop`）。
- 使用者表示今天獎勵已結束，真實收取改到明天。

## 待實機測試清單

- 階段 1：BitWalk 首頁 `collector_snapshot`（應含「可兌換 N 個印章」「收看廣告影片」）、`collector_corners`。11-12 只測到桌面（OCR 655 ms、node 69／OCR 37），裝置隨後離線。
- 階段 3：BitWalk dryRun、真實收取 ≥30 個、install card「←」、獎勵對話框、導流攔截。
- 其他 App 依階段 4 順序。

## 已知問題

- 2026-11-12 手機無線偵錯 `192.168.137.219:5555` 中途變成 `offline`，未修改手機設定；實機測試順延。
- APK 因 ML Kit 內建中文模型＋四 ABI native lib，由 9.96 MiB 增至 54.14 MB（GitHub 對 >50 MB 發警告，未阻擋推送）。
- pc-collector 的 `corner_regions` 只搜左上／右上兩個角帶；移植照原範圍，未加下方角帶（避免誤點）。
- 階段 1 審查發現（11-12 已排入修正）：Actor 的 back/home/launch 未檢查 RUNNING、非同步返回未重查互斥、CollectorEnvironment 狀態切換非原子、截圖複製在主執行緒、fixture 測試斷言恆真。

## 最近提交

- `9d7b966` Note in the master prompt that phase 1 is mostly committed already
- `ad6df78` Add the auto-collector plan and the master prompt for unattended development
- `a9a6dad` Ship the debug APK built from 48998f9
- `48998f9` Let the collector own the screen while it runs
- `7031b37` Keep the screen awake and pause for the lock screen
- `b1ed793` Give the collector one pause-aware actor
- `6991725` Find corner ad buttons without OpenCV
- `05b2644` Read the screen from nodes, with ML Kit OCR as fallback
- `ccaacbb` Read pc-collector's recorded screens as snapshots

## 環境

- 守衛 repo：`D:\Visual Studio Code\PROJECT\Reward Ad Guard`
- pc-collector 乾淨參考副本：`D:\Claude\pc-collector-clean`（v0.2.5，只讀）
- JDK：`C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`
- 建置：`.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" assembleDebug testDebugUnitTest --console=plain`

