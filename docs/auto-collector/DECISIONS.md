# 自行決定紀錄

依 `KILO_MASTER_PROMPT.md` 第 7 節：需要判斷時選最保守、最不會誤點的做法，寫在這裡後繼續。

## 2026-11-12

### D-01 以遠端分支現況為起點
`origin/feature/auto-collector` 已含階段 1 提交（`ccaacbb`～`a9a6dad`）與文件提交（`ad6df78`、`9d7b966`）。依 master prompt 階段 1 現況說明，逐項核對後只補缺漏，不重做已完成項目。

### D-02 保留生產版 `canTakeScreenshot`
審查指出此能力目前只有除錯入口使用。依 master prompt 階段 1 第 3 點明確要求加在 `accessibility_service_config.xml`，且階段 2 的 `ScreenReader` 會由收取引擎在生產路徑使用，因此保留；非事件熱路徑、僅在引擎要求時呼叫。

### D-03 除錯 Activity 仍為 debug-only 且 exported
專案既有出貨流程本來就以 `assembleDebug` 產生 APK 側載（`TOOLS/release.sh`），`DebugTestActivity` 只在 debug source set。階段 2～4 的驗收完全依賴 `adb am start` 指令，因此不關閉。風險：任何 App 可呼叫 debug 指令（含關閉防護）。這是既有設計，非本次新增的攻擊面；列為「待使用者確認」的產品風險。

### D-04 角標比對只搜左上／右上
pc-collector `vision.py:corner_regions` 實際只有兩個角帶。提示詞寫「四個角落」，但「範圍與門檻比照 pc-collector」優先；加下方角帶會把廣告底部圖案當成 X／>>，11-09、11-11 已發生誤點導流。維持兩角帶，若之後要四角需另訂安全範圍。

### D-05 手機離線時跳過實機測試
2026-11-12 無線 adb 於一次 `collector_snapshot` 後變 `offline`。依第 6 節「若不符就跳過實機測試」與第 7 節，不修改手機網路／偵錯設定，記入 `PROGRESS.md` 待測清單，繼續寫程式。

### D-06 pc-collector 版本
本機 `D:\Claude\pc-collector` 有其他工作階段的未提交修改，依指示不動；另 clone `D:\Claude\pc-collector-clean`（`origin/main`，v0.2.5）作唯讀參考。

### D-07 階段 1 審查發現的修正
獨立審查找到五個高信度問題，於階段 2 開工前修正（屬安全與正確性，不改變產品行為）：
1. `Actor.back()/home()/launch()` 補上 RUNNING 檢查，與 `tap()` 一致。
2. 守衛的非同步返回（ReturnController、landing Back runnable）在執行前重查 `CollectorState`，避免 Collector 接手後仍按返回。
3. `CollectorEnvironment` 的狀態切換改為同步，避免 pause 覆寫 stop。
4. 截圖的 GPU→CPU 複製移到背景 executor，不在主執行緒做全螢幕 copy。
5. `OcrFixtureLoadTest` 改為檢查原始寬高為正，不再用已被 `coerceAtLeast(0)` 保護的欄位。

### D-08 交易紀錄不上傳公開 repo
測試紀錄、截圖、資料庫只放私人 repo `reward-collector-testlogs`；守衛 repo 只放摘要。若無法登入該私人 repo，紀錄留在本機並在報告註明，不提交到公開 repo。

### D-09 引擎把決策與動作為分開
`CollectorRules` 只做判斷（可用 fixtures 測），`CollectorEngine` 只負責流程，`Actor` 是唯一觸控出口。這樣「會不會誤點商店」可以在 JVM 回答，不必用一次廣告去驗。

### D-10 無人值守的暫停上限
真人觸控或鎖屏會讓 Collector 暫停；除錯入口沒有「繼續」按鈕，因此暫停超過 10 分鐘會自動結束該次執行（記 `paused_too_long`）。單次執行另設 60 分鐘上限（`run_timeout`），符合 master prompt 的單次最長 60 分鐘。階段 5 有 UI 後可改由使用者決定。

### D-11 手機仍然離線
2026-11-12 第二次檢查 `adb devices` 為空。APK 憑證已用 apksigner 驗過，SHA-256 為共用金鑰 `520f639b…439dc8`，符合安裝條件；但不安裝、不改手機設定，實機測試全部記入待測清單。

### D-12 LINE 散步趣暫不啟用
`linewalk.py` 的獎勵選項 sheet、突擊挑戰、抽抽樂與「避開 AD 橫幅」都是避免誤點的必要規則，尚未移植。只加 profile 會讓「全部」在 LINE 上點到廣告橫幅，因此暫不加入 `CollectorProfiles`，等規則與 fixtures 測試完成再開。

### D-13 階段 5 的執行擁有者
執行不由 ViewModel 持有，而是 `CollectorController` 單例（process 級 scope）＋服務持有的 `CollectorEnvironment`。這樣離開 App、轉屏或除錯 Activity 被 `noHistory` 銷毀都不會中斷收取，且永遠只有一個 job。

### D-14 通知與紀錄
常駐通知的暫停／繼續／結束用 explicit broadcast 到 `CollectorActionReceiver`（exported=false），沒有對外開放。`summary.json` 版本與 commit 來自 `BuildConfig.VERSION_NAME`／`BuildConfig.GIT_HASH`（Gradle 以 `git rev-parse --short HEAD` 產生）。

### D-15 執行紀錄不進公開 repo
`collector_runs` 只存在 App 私有目錄；本任務沒有把任何紀錄或截圖提交到守衛 repo。私人 testlogs repo 的登入狀態未知，若後續要上傳需由有權限的機器執行。

### D-16 執行紀錄匯出
新增 `CollectorRunExporter`：把最近一次 `collector_runs/run_*` 壓成 zip 放到 `cache/exports`（既有 FileProvider 已允許），用系統分享出去。沒有新增權限，也不會把紀錄複製到公開位置。

### D-17 卡住時用 kill -9 中止
2026-11-12 真實收取在截圖回呼未返回時卡住，因為當時沒有 `collector_stop`，只能用 `run-as kill -9`。副作用是無障礙服務被系統標為 Crashed、不會自動重綁。已新增 `collector_stop` 與截圖／OCR timeout；之後一律先用指令停止，不再直接殺程序。

### D-18 乾跑完全不點擊
2026-11-12 發現乾跑仍會點頁籤與彈窗，與 master prompt「只讀畫面、log 判斷、不點擊」不符。已改為啟動後只讀啟動畫面，最多等 5 次×2 秒讓畫面載入；不切頁籤、不關彈窗、不開抽抽樂。PC 版乾跑會切頁籤，但那是 adb 腳本脈絡，手機版以安全規則優先。

### D-19 前景套件不是目標 App 就重新拉回
2026-11-12 WALK EARN 乾跑時，HAHAGO 在啟動後搶回前景，引擎讀到 HAHAGO 的畫面。已加：每次決策前檢查 `Snapshot.foregroundPackage`，不是目標套件就 `recoverToApp()` 後重讀，仍不對就跳過；啟動後也要求一次穩定取樣。這可防止在錯的 App 上點擊獎勵。

### D-20 啟動失敗時先 Home 再重試
2026-11-12 15:23 LINE 散步趣啟動後被 HAHAGO 搶回前景，穩定取樣正確回報 `launch_failed`。已改為最多 3 次啟動：第 2 次起先按 Home 清掉搶前景的 App，再 launch；每次都要通過穩定取樣才開始讀畫面。15:28 重測 attempt 1 即成功。

### D-21 LINE 六個 App 全部移植（取代 D-12）
Master prompt 在 2026-11-12 13:30 更新為「pc-collector 支援的 6 個 App 全部都要移植」。已補上 `LINE_WALK`（choice sheet 紅利→普通、突擊挑戰、AD 橫幅排除、載入中取消／重開）與 `LINE_LOTTERY`（獨立選項、BIG→免費→一般、倒數不等待、張數 0 停止），並以 12 個 fixtures 測試規則。D-12 的「暫不啟用」已被此決定取代；兩者都尚未實機驗證。

### D-22 抽抽樂遇到內嵌商店頁要按返回
2026-11-12 15:44 真實抽取後，LINE 在自家 WebView 開 Google Play「momo 購物」頁（套件仍是 `com.linecorp.LGWKTW`），所以前景套件檢查與廣告判定都不會觸發。抽獎等待現在會認 `Install`／`More Info`／`Play Store`／`momo購物`／`下載`／`開啟`，先找關閉鈕、找不到就按返回；這些英文字也加入不可點清單。**16:53 實機驗證成功**：商店頁再次出現，文字關閉鈕關掉後取得「20枚金幣中獎囉!」，rewards=1。

### D-23 UI 套用開源 Material
依使用者指示，UI 使用 open-source Material 3：Android 12+ 啟用 Material You 動態色（舊機維持原本海軍藍／綠配色），底部導覽使用 Apache-2.0 `material-icons-core`。Launcher icon 維持專案原創的盾牌／阻擋箭頭，未替換成第三方圖示，避免授權與辨識問題。

### D-24 停止要跨 App 生效
Review P0-1：按下「結束」只停目前 App，Controller 迴圈仍會啟動下一個。已加 `stopRequested` 旗標與 `CollectorRunPolicy`，每個 App 開始前檢查；`paused_too_long`、`run_timeout`、`cancelled`、`error`、鎖屏也都終止整個序列。引擎的 `stopCause` 會讓 Controller 看到真正原因，不再一律回報 `done`。對應提交 `ee61ecb`、測試 `CollectorRunPolicyTest`。

### D-25 除錯入口只讓 adb 呼叫（更新 D-03）
Review P0-2：出貨的就是 debug APK，`DebugTestActivity` 為 `exported="true"`，手機上任何 App 都能下 `collector_run`（自動點擊）或關閉防護。已在 debug manifest 加 `android:permission="android.permission.DUMP"`：adb shell（uid 2000）有此權限，一般 App 拿不到。實機驗證結果記在 `reports/review-fix_20261112.md`。
