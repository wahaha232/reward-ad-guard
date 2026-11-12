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
