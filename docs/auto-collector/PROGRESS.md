# 自動收取開發進度（唯一真相）

最後更新：2026-11-12 16:00（MW-EDDIE，手機已斷開，等待 11-13）
分支：`feature/auto-collector`（已推送）
基準測試：**270 個、0 失敗**（階段 0 基準 224＋階段 1 20＋階段 2 12＋LINE 11＋限制 3）
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

2026-11-12 16:00 手機已由使用者斷開；最後一次真實測試是 LINE 抽抽樂（失敗，開到內嵌商店頁），修正已提交但尚未實機重測。等待 11-13 09:00 的自動續跑。

## 下一步

1. 11-13 09:00：安裝最新 APK（含抽抽樂商店頁修正與 UI 更新），重測 LINE 抽抽樂真實抽取（limit 1）。
2. BitWalk 真實收取（目標 ≥30），再跑一次 snapshot/corners 存證。
3. 依序驗 AHEALTH、WALK EARN、HAHAGO、LINE 散步趣（每個先 dryRun）。
4. 每次結束上傳 testlogs 並寫 `DAILY.md`。

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
- 15:44 **LINE 抽抽樂真實抽取（limit 1）失敗**：按下 BIG 抽一次後跳到 LINE 內嵌的 Google Play「momo 購物」頁，套件仍是 LINE；舊版等待邏輯只認「中獎＋關閉」，240 秒逾時、`rewards=0`，沒有再抽。已加商店頁偵測、關閉／返回、`Install`／`More Info` 不可點，尚未實機重測。
- UI 補齊：`全部` 勾選、`不限數量`（-1）／`收滿 N`（>0）、`已執行` 時間；Material 3 動態色與 Apache-2.0 Material Icons；launcher icon 維持原創。

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

