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
