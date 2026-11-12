# 自動收取開發進度（唯一真相）

最後更新：2026-11-12 17:20（MW-EDDIE，Review 修正進行中，暫停點）
分支：`feature/auto-collector`（Review P0-1 已推送）
基準測試：**273 個、0 失敗**（原 270＋P0-1 3 個）
最新報告：`reports/progress_20261112_1519.md`

## 階段狀態

| 階段 | 狀態 | 說明 |
|---|---|---|
| 0 準備 | 完成 | repo 乾淨、分支建立、基準測試 224／0 失敗（11-12 再加 20 個階段 1 測試） |
| 1 基礎建設 | 完成 | 程式、審查補強與文件已提交；APK 憑證已驗（符合共用金鑰）。BitWalk 實機驗證仍待手機連上 |
| 2 收取引擎 | 完成（實機待驗） | `CollectorRules`／`CollectorEngine`／`RunRecorder`／`collector_run`；fixtures 測試通過，六支 App 只做過乾跑 |
| 3 BitWalk | 部分完成 | profile、install card、資訊視窗、多則廣告、獎勵對話框已實作並有 fixtures；實機待測 |
| 4 其他 App | 完成（待實機） | AHEALTH／WALK EARN／HAHAGO／LINE 散步趣／LINE 抽抽樂 全部完成程式與 fixtures 測試；六個 App 都未實機 |
| 5 介面與收尾 | 完成（待實機） | 「自動收取」頁籤、常駐通知、手機端紀錄、最近一次紀錄的 zip 匯出（FileProvider）已完成；UI 未經實機走查 |

## 目前在做什麼

2026-11-12 17:20 依使用者要求暫停。正在執行 `Kilo提示詞_自動收取修正_2026-11-12.md`（Review 修正清單），P0-1 已完成並推送；其餘項目尚未開始。手機在線，但尚未做本輪實機測試。

## 2026-11-12 Review 修正進度

| 項目 | 狀態 | 提交／備註 |
|---|---|---|
| P0-1 停止後不得啟動下一個 App | 完成 | `ee61ecb`；`CollectorRunPolicyTest` 3 個 |
| P0-2 除錯入口加 DUMP 權限 | 未做 | 需改 debug manifest 並用 adb 驗證 |
| P1-3 暫停同步與自動繼續 | 未做 | |
| P1-4 不限數量不受 60 分鐘限制 | 未做 | |
| P1-5 暫停時間不計入逾時 | 未做 | |
| P2-7 商店頁判斷收斂 | 未做 | |
| P2-8 中獎無關閉鈕 | 未做 | |
| P2-9 遲到 bitmap 回收 | 未做 | |
| P2-10 更名與 UI 文字 | 未做 | |
| P2-11 觸碰誤判觀察 | 未做 | 只記錄，不改邏輯 |

## 下一步

1. P0-2：`DebugTestActivity` 加 `android.permission.DUMP`，adb 驗證仍可呼叫。
2. P1-3～P1-5（暫停同步、60 分鐘、暫停不計時）。
3. P2-7～P2-10，每項一個提交與單元測試。
4. 實機測試 1～6（P0-2、BitWalk、停止、鎖屏、其他 App、抽抽樂）並上傳 testlogs。
5. 寫 `reports/review-fix_20261112.md`。

## 2026-11-12 實機進度（手機已連上）

- 無線 adb `192.168.137.219:5555` 連上，安裝 `293c6ee` 的 APK 成功，無障礙服務已連上（`Service[label=獎勵廣告守衛]`）。
- BitWalk 乾跑成功：引擎自己啟動 BitWalk、切到「步行」，選中節點文字「收看廣告影片，獲得5倍的BTC」@ (610,1680)；「賺取」頁籤當下沒有 reward candidate（節點 12、OCR 0）。
- 真實收取（limit 3）在 `ready_0` 後卡住超過 3 分鐘，沒有任何後續事件。已判斷為截圖回呼沒有回來；已加 5 秒截圖 timeout、15 秒 OCR timeout、`capture_begin` 事件與 `collector_stop` 指令。
- 為了中止卡住的執行，使用了 `run-as ... kill -9`；程序被殺後無障礙服務進入 `Crashed services`，需要重新綁定（下次會改用 `collector_stop`）。
- 使用者表示今天獎勵已結束，真實收取改到明天。
- 14:05～14:12 再跑三次「只讀不點」乾跑（BitWalk／LINE 散步趣／LINE 抽抽樂）：
  - BitWalk 啟動落在「賺取」，無 candidate（正確）。
  - LINE 散步趣等待載入後找到節點「獲得金幣\n3」@ (600,1096)，置中、無 AD 標籤。
  - LINE 抽抽樂偵測到入口（entry=true）、sheet 未開；0 taps。
- 乾跑已改為**完全不點擊**（含頁籤與彈窗），並在啟動後最多等 5 次×2 秒讓畫面載入。
- 14:27～14:33 另外三支 App 的只讀乾跑：
  - AHEALTH：載入後找到節點「領取」@ (830,1905)。
  - WALK EARN：找到 OCR「錄咋晚的睡眠 即可領取點數」——這是說明文字不是按鈕，已把「即可領取／睡眠／點數」加入 avoid。
  - HAHAGO：5 次讀取都沒有 reward candidate。
  - WALK EARN 重跑時 HAHAGO 在背景搶回前景，讀到錯的 App；已加「前景套件必須等於 profile」檢查與啟動後穩定取樣，錯的畫面一律不判斷、不點擊。
- 15:22 以 `55c35a8` APK 再跑六支 App 的只讀乾跑：
  - BitWalk 選中「收看廣告影片，獲得5倍的BTC」@ (610,1680)。
  - AHEALTH 選中「領取」@ (350,2028)。
  - WALK EARN（node 5／OCR 4）與 HAHAGO（node 21-25／OCR 26-48）無候選。
  - LINE 抽抽樂找到「獲得金幣 3」與入口。
  - LINE 散步趣第一次被 HAHAGO 搶前景（launch_failed），加入啟動重試後 15:28 attempt 1 即成功。
- 15:44 **LINE 抽抽樂真實抽取（limit 1）失敗**：按下 BIG 抽一次後跳到 LINE 內嵌的 Google Play「momo 購物」頁，套件仍是 LINE；舊版等待邏輯只認「中獎＋關閉」，240 秒逾時、`rewards=0`，沒有再抽。已加商店頁偵測、關閉／返回、`Install`／`More Info` 不可點。
- 16:53 **LINE 抽抽樂真實抽取成功**（APK `3cb8931`，limit 1）：商店頁再次出現，這次由文字關閉鈕關掉、按 SKIP，取得「20枚金幣中獎囉!」；`rewards=1`、`taps=4`、85 秒、`errors=0`，取得獎勵後停止。紀錄已上傳 testlogs。
- UI 補齊：`全部` 勾選（5 個 App，不含抽抽樂）、`不限數量`（-1）／`收滿 N`（>0）、`已執行` 時間；Material 3 動態色與 Apache-2.0 Material Icons；launcher icon 維持原創。
- 抽抽樂的抽獎與開啟入口點擊已計入 `taps`；`collector_stop` 透過共用的 CollectorEnvironment 也能停止除錯入口啟動的執行。

## 待實機測試清單

- 階段 1：BitWalk 首頁 `collector_snapshot`（應含「可兌換 N 個印章」「收看廣告影片」）、`collector_corners`。11-12 只測到桌面（OCR 655 ms、node 69／OCR 37），裝置隨後離線。
- 階段 3：BitWalk dryRun、真實收取 ≥30 個、install card「←」、獎勵對話框、導流攔截。
- 階段 4：LINE 散步趣（choice sheet 紅利／普通、突擊挑戰、AD 橫幅排除）與抽抽樂（BIG→免費→一般、倒數不等待）實機。
- 其他 App 依階段 4 順序。

## 已知問題

- 2026-11-12 手機無線偵錯 `192.168.137.219:5555` 中途變成 `offline`，未修改手機設定；實機測試順延。
- APK 因 ML Kit 內建中文模型＋四 ABI native lib，由 9.96 MiB 增至 54.14 MB（GitHub 對 >50 MB 發警告，未阻擋推送）。
- pc-collector 的 `corner_regions` 只搜左上／右上兩個角帶；移植照原範圍，未加下方角帶（避免誤點）。
- 階段 1 審查發現（11-12 已排入修正）：Actor 的 back/home/launch 未檢查 RUNNING、非同步返回未重查互斥、CollectorEnvironment 狀態切換非原子、截圖複製在主執行緒、fixture 測試斷言恆真。

## 最近提交

- `24f2417` Ship the debug APK built from 55c35a8
- `55c35a8` Never decide on a screen that belongs to another app
- `df77c26` Point the next session at 11-13 and the shipped APK
- `1fbe357` Ship the debug APK built from cc9feec
- `cc9feec` Make a dry run truly read-only and let LINE render first
- `11380ed` Port LINE walk and its lottery, the last two profiles
- `275a197` Give a tab time to render before calling it empty
- `3e405f1` Stop a stuck run instead of killing the process
- `8ca0d6c` Master prompt: port all six pc-collector apps and test on all three days
- `0608892` Correct the final report's export status
- `293c6ee` Ship the debug APK built from 63292ce

## 環境

- 守衛 repo：`D:\Visual Studio Code\PROJECT\Reward Ad Guard`
- pc-collector 乾淨參考副本：`D:\Claude\pc-collector-clean`（v0.2.5，只讀）
- JDK：`C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot`
- 建置：`.\gradlew.bat "-Dorg.gradle.java.home=$env:JAVA_HOME" assembleDebug testDebugUnitTest --console=plain`

