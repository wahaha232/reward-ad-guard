# 抽抽樂「載入中誤按取消」修正報告（2026-11-27）

- **分支**：`fix/lottery-loading-cancel`（隨後快轉合併至 `main` 並發行正式版）
- **版本**：`1.2.57`（`versionCode 62`）
- **測試版實測 Commit**：`d30b9a6`

## 1. 修改的檔案

### 正式程式碼
1. `app/src/main/java/com/rewardadguard/app/collector/AppProfile.kt`
   - 新增 `lineLoadingWaitMillis: Long = 45_000L` 設定，並於 `LINE_WALK` 與 `LINE_LOTTERY` 皆明確設定為 `45_000L`。
2. `app/src/main/java/com/rewardadguard/app/collector/LineWalkRules.kt`
   - 新增 `isLoadingDialog(snapshot)`：辨識 LINE「載入中…／載入中...」搭配「取消」或「廣告載入時間過長」的廣告載入對話框。
   - 修正 `lotterySheet(snapshot)` 冷卻剩餘時間解析：當「回復還需」框內不含時間時，在同半邊（BIG 左／免費右）且垂直距離 ≤ 按鈕高度範圍內尋找最近的 `HH:MM:SS` OCR 框。
3. `app/src/main/java/com/rewardadguard/app/collector/CollectorRules.kt`
   - 在 `findCloseTargets()` 對 `profile.lineRules` 的 App 過濾文字正規化後等於「取消」的 `close_text` 目標，同時保留 `CloseButtonDetector.STRONG_TEXT` 不影響其他 App。
4. `app/src/main/java/com/rewardadguard/app/collector/CollectorEngine.kt`
   - `handleAd()`：遇到 `profile.lineRules && LineWalkRules.isLoadingDialog(snapshot)` 時只等待（每輪 1.2 秒）、不找關閉鈕、不按返回、不計入 `adMinWatchMillis`／`AdStuck`；記一次 `line_loading_wait`（`start` / `end` / `timeout`，含 `waitedMs`），離開載入對話框時重設 `segmentStart` 與 `elapsed`；超過 `lineLoadingWaitMillis`（45 秒）才呼叫 `cancelLoadingDialog(snapshot, "line_loading_timeout")` 並回傳 `"ad_loading_timeout"`。
   - `waitLotteryResult()`：改回傳 `LotteryWaitResult`（`Prize` / `LoadingTimeout` / `NoResult`），收到 `"ad_loading_timeout"` 立刻回傳 `LoadingTimeout`，不再白等 25 秒結果。
   - `collectLottery()`：以獨立計數 `loadingTimeouts` 追蹤載入逾時（不計入 `failures[kind]`，中獎一次即歸零），記事件 `lottery_ad_loading_timeout`；第 1 次逾時呼叫 `handleLineLoadFailure(forceRestart = true)` 執行 Home＋重開 LINE 後重進抽抽樂再試；連續 2 次逾時呼叫 `finishLotteryAdUnavailable()`，以結束原因 `lottery_ad_unavailable` 結束（**不停守衛**），並發送「抽抽樂：LINE 廣告載入不到，稍後再試（尚可抽 N 次）」。
5. `app/src/main/java/com/rewardadguard/app/collector/DailyRewardSummary.kt`
   - 新增 `endNote` 欄位，讓首頁每日摘要能清楚顯示 `lottery_ad_unavailable`（`LINE 廣告載入不到，稍後再試`）、`lottery_normal_failed` 與 `lottery_all_cooldown`。
6. `app/build.gradle.kts`
   - 提升版號至 `versionName = "1.2.57"`、`versionCode = 62`。

### 單元測試
- `app/src/test/java/com/rewardadguard/app/collector/LineWalkRulesTest.kt`：新增 `loadingDialogDetectedFromOcrTextVariants`、`findCloseTargetsExcludesCancelForLineProfilesOnly`、`lineProfilesConfigureLoadingWaitMillis`、`lotteryCooldownParsesTimeFromNearbyOcrBoxWhenSplit`。
- `app/src/test/java/com/rewardadguard/app/collector/CollectorRunPolicyTest.kt`：驗證 `lottery_ad_unavailable` 不會中止多 App 序列。
- `app/src/test/java/com/rewardadguard/app/collector/DailyRewardSummaryTest.kt`：驗證 `lottery_ad_unavailable` 與 `lottery_normal_failed` 摘要文字顯示。

## 2. 單元測試結果

- 指令：`.\gradlew.bat "-Dorg.gradle.java.home=C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot" testDebugUnitTest --console=plain`
- 結果：**505 / 505 全部通過**（70 個測試檔，0 失敗、0 錯誤、0 略過）。

## 3. 手機實測結果

- 裝置：無線 ADB `192.168.137.219:5555`，安裝測試版 `1.2.57`（commit `d30b9a6`，共用金鑰覆蓋安裝成功）。

### 3.1 抽抽樂實測（`run_20261127_130407`，`linelottery`，limit 25）
- **是否遇到「載入中」**：是（4 次抽獎全部遇到載入中對話框）。
- **有無誤點「取消」**：無（全程 `close_ad_x` 次數 = 0，完全未再出現 `(609, 2466)` 誤點）。
- **BIG 抽與免費抽**：
  - BIG：`line_loading_wait` `start` → `end`（`waitedMs: 8016`）→ `ad_invite_promo` → `lottery_prize`（`400枚金幣中獎囉!`），冷卻時間正確解析為 `00:59:54`。
  - 免費抽：`line_loading_wait` `start` → `end`（`waitedMs: 7965`）→ `ad_invite_promo` → `lottery_prize`（`10校金幣中獎囉!`），冷卻時間正確解析為 `00:59:54`。
- **一般抽載入逾時處理**：
  - 第 1 次：`line_loading_wait` 等滿 45 秒（`45203 ms`）→ `line_loading_timeout` → 立即 `lottery_ad_loading_timeout`（`count=1`，無 `lottery_no_result`）→ `app_restart`（Home＋重開 LINE）→ 重開抽抽樂面板再試。
  - 第 2 次：`line_loading_wait` 等滿 45 秒（`45189 ms`）→ `line_loading_timeout` → `lottery_ad_loading_timeout`（`count=2`）→ 結束原因 `lottery_ad_unavailable`（`draws=4, availableDraws=7, loadingTimeouts=2`），**未停掉守衛**。

### 3.2 LINE 散步趣回歸實測（`run_20261127_131628`，`linewalk`）
- 結束原因：`done`（`errors=0`，`timeouts=0`）。
- 同步驗證：點擊「獲得票券」→ `choice_bonus` 觸發 `line_loading_wait`（`start` → `end`，`waitedMs: 8808`）→ `ad_invite_promo` → 既有 `bonus_unavailable` 保護機制正常運作。

## 4. 未驗證項目

- 本次實測時段 LINE 一般抽廣告處於長時間無填充狀態（2 次皆達 45 秒載入逾時），因此「載入超過 10 秒但在 45 秒內成功載出一般影片廣告、離開載入後重設 `segmentStart` 再完整看滿 10 秒並中獎」的情境尚未於實機遇到，目前由單元測試與 `line_loading_wait`（8 秒載出宣傳頁）邏輯間接確認。
