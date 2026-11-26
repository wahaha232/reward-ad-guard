# Antigravity 修正報告（2026-11-26）

- 分支：`fix/run-analysis-1126`（從 `main` `546863d` 建立，未合併至 `main`，未發行 Release）
- APK 版本：`1.2.56`（`versionCode = 61`，建置 commit：`5332990`）
- 測試結果：
  - 開工前基準：**492 / 492** 通過（0 失敗、0 略過）
  - 修正後全套：**500 / 500** 通過（新增 8 個單元測試，0 失敗、0 略過，未刪除或停用任何既有測試）
- 建置結果：`:app:testDebugUnitTest` 與 `:app:assembleDebug` 成功，使用共用簽章金鑰覆蓋安裝至手機成功（`Performing Streamed Install / Success`）。

---

## 1. 各任務修正明細

### 任務 A（P0）：抽抽樂一般抽失敗被誤判為「票不足」且停掉守衛
- **根因**：`CollectorEngine.collectLottery()` 在一般抽連續失敗達 `maxLotteryFailures` 時，無論票數／可抽次數是否充足，一律呼叫 `finishLotteryLow()` 並以 `lottery_tickets_low` 結束，進而觸發 `CollectorController.leaveLotteryAndStopGuard()` 停掉守衛。此外，按鈕上方紅字實為「可抽次數」（2026-11-26 09:10 與 09:11 截圖：總票數 546 張對應紅字 109 ≈ 546÷5；既有 fixture 亦為 BIG 47 對一般 479、44 對 441，皆呈 10:1 關係），而右上角藍字才是總票數。
- **修改檔案與函式**：
  - `LineWalkRules.kt`：`LotterySheet` 新增 `badgeCounts`；拆出 `lotteryButtonBadges()` 並在註解記明依據；新增 `normalDrawsAvailable()`、`hasSufficientNormalDraws()`、`isReallyTicketsLow()`、`lotteryEndReason()`。
  - `CollectorEngine.kt`：`lotteryTicketsLow` 改為 `lotteryEndReason`；`collectLottery()` 依 `lotteryEndReason()` 分流，票數充足但一般抽連續失敗時呼叫 `finishLotteryNormalFailed()`，以 `lottery_normal_failed` 結束且不停守衛。
- **新增事件／結束原因**：結束原因與事件 `lottery_normal_failed`（含 `draws`、`availableDraws`、`tickets`、`counts`、`normalFailures` 與失敗畫面文字 `failTexts`）。
- **對應測試**：`LineWalkRulesTest.lotteryNormalFailedWithSufficientDrawsIsNotTicketsLow`、`LineWalkRulesTest.lotteryReallyTicketsLowWithZeroBadgeOrBannerBelowFiveOrNotice`、`CollectorRunPolicyTest.normalCompletionContinuesButLockedPhoneDoesNot`。
- **實機驗證**：部分驗證（`run_20261126_163010` 實測時一般抽連續中獎 9 次、未觸發連續失敗分支，確認未出現 `lottery_tickets_low` 亦未停掉守衛；失敗分流邏輯以單元測試驗證）。
- **Commit**：`525bf11`

### 任務 B（P1）：抽抽樂 BIG／免費在冷卻中仍去按
- **根因**：`LineWalkRules.lotterySheet()` 雖已解析 `cooldowns`，但 `CollectorEngine.collectLottery()` 未在選按鈕前過濾冷卻中的 `big` / `free`，導致冷卻中仍點擊並空等 25 秒。
- **修改檔案與函式**：
  - `LineWalkRules.kt`：新增 `isOnCooldown(sheet, kind)`（僅限制 `big` 與 `free`，一般抽 `normal` 不受限）；`lotteryEndReason()` 新增 `lottery_all_cooldown` 分支。
  - `CollectorEngine.kt`：`collectLottery()` 於抽取前跳過冷卻中的 `big`/`free` 並記錄事件；新增 `finishLotteryAllCooldown()`（不停守衛）。
- **新增事件／結束原因**：事件 `lottery_cooldown_skip`（含 `kind`、`remaining`）；結束原因與事件 `lottery_all_cooldown`。
- **對應測試**：`LineWalkRulesTest.lotterySkipsBigAndFreeOnCooldownButNeverNormal`。
- **實機驗證**：**已實機驗證通過**（`run_20261126_163010`：BIG 中獎後立即觸發 `lottery_cooldown_skip (kind=big)`、免費抽中獎後立即觸發 `lottery_cooldown_skip (kind=free)`，隨後一般抽不受冷卻影響連續中獎 9 次，整輪獲得 11 個獎勵、0 錯誤）。
- **Commit**：`45f3712`

### 任務 C（P1）：LINE 散步趣「看廣告 15 倍」失敗後沒有改選一般
- **根因**：`CollectorEngine.handleChoiceSheet()` 在點完紅利、選擇視窗消失時就將 `bonusFailures` 歸零，且後續出現 `ad_invite_promo` 或 `ad_reward_not_seen` 未遞增失敗次數，導致無法達到 `maxBonusFailures`。
- **修改檔案與函式**：
  - `LineWalkRules.kt`：新增 `BonusTracker` 狀態追蹤器（僅在紅利獎勵確認入帳時歸零，連敗達上限後整輪停用紅利）。
  - `CollectorEngine.kt`：改用 `bonusTracker`、`inBonusChoice`、`bonusSawInvitePromo`；在 `handleChoiceSheet()`、`handleAd()`、`confirmLineIfNeeded()` 與 `recordBonusFailure()` 完整追蹤紅利結果，達 `maxBonusFailures`（預設 2）後本輪其餘票券直接選一般。
- **新增事件／結束原因**：事件 `bonus_disabled_for_run`（每輪僅記一次，含 `failures`、`reason`）；`bonus_unavailable` 補上 `reason` 欄位。
- **對應測試**：`LineWalkRulesTest.bonusTrackerDisablesBonusForRunAfterTwoInvitePromoOrUnseenRewardFailures`、`LineWalkRulesTest.bonusTrackerResetsOnlyWhenBonusRewardIsConfirmed`。
- **實機驗證**：未實機驗證（僅單元測試驗證）。
- **Commit**：`2efdaa1`

### 任務 D（P1）：Mintegral 佔位廣告（影片沒載入）提早辨識
- **根因**：夜間 Mintegral no-fill 佔位頁（灰底、`In-Stream Video` / `Click now for details` / `install`，無倒數）未提早辨識，每支白等 25～30 秒並記為 `ad_unconfirmed`。
- **修改檔案與函式**：
  - `CollectorRules.kt`：新增 `isMintegralPlaceholder(snapshot)`（檢查 Activity 含 `com.mbridge.msdk`、OCR 含 `In-Stream Video` 或 `Click now for details`，且無 `left to be rewarded` 與倒數秒數）。
  - `CollectorEngine.kt`：`handleAd()` 於廣告開始 5 秒後偵測為佔位頁時立即關閉並回傳 `"ad_no_fill"`（不計入 `unconfirmed` 與 `timeouts`）；主迴圈連續 3 次 `ad_no_fill` 以 `endReason = "ad_no_fill"` 結束該 App；`summary.json` 新增 `noFill` 計數（僅加在 `recorder.finish()` stats map，不影響既有欄位）。
- **新增事件／結束原因**：事件 `ad_no_fill`、`ad_no_fill_stop`；結束原因 `ad_no_fill`；`summary.json` 新增 `noFill`。
- **對應測試**：`CollectorRulesTest.mintegralPlaceholderIsDetectedOnlyWithoutRewardCountdown`。
- **實機驗證**：未實機驗證（僅於 `run_20261126_163010` 確認 `summary.json` 正確輸出 `"noFill": 0`；未遇到夜間 Mintegral 佔位頁）。
- **Commit**：`586a385`

### 任務 E（P2）：HAHAGO 灰色按鈕重複點擊
- **根因**：HAHAGO「每月任務」已簽到的灰色「簽到」與首頁灰色 2k/4k 里程碑未設 `!isEnabled`，且 `attempts` 在每次 sweep / tab 重建，導致每輪被重複點擊 15～20 次。
- **取捨理由**：採用「同一輪、同一分頁、同一座標（±40 px）＋同一文字，點過一次結果為 `no_change` 或 0 入帳的 `changed` 即本輪略過」，且僅對 HAHAGO profile（`skipNoRewardRepeat = true`）開啟。相較於截圖色飽和度判斷，此法不需在正常迴圈保留 Bitmap、不受深色模式或抗鋸齒干擾、無誤殺可點按鈕風險，且完全不影響 AHEALTH / BITWALK / LINE 等其他 App。
- **修改檔案與函式**：
  - `AppProfile.kt`：新增 `skipNoRewardRepeat: Boolean = false`，於 `CollectorProfiles.HAHAGO` 設為 `true`。
  - `CollectorRules.kt`：新增 `NoRewardTargetTracker`（±40 px 容差）。
  - `CollectorEngine.kt`：新增跨 sweep 的 `deadTargets`、`pickReward()` 過濾已略過目標，並於 `no_change` 及 0 入帳的 `changed` 時記錄。
- **新增事件／結束原因**：事件 `dead_target_skipped`（含 `tab`、`text`、`x`、`y`、`outcome`）。
- **對應測試**：`CollectorRulesTest.noRewardTargetTrackerSkipsSameTabTextAndSpotWithin40PxOnly`。
- **實機驗證**：未實機驗證（僅單元測試驗證）。
- **Commit**：`e59b3e3`

### 任務 F（P2）：同一 App 剛結束就重跑
- **觸發來源追查**：檢查全專案所有 `CollectorController.start()` 呼叫點（共 4 處）：`MainActivity`（手動開始）、`ScheduleSection`（手動掃描）、`RewardReminders`（使用者點通知按鈕）皆為手動觸發；**唯一自動觸發點為 `ScheduledRunner.start(pending)`**。當上一輪排程（或延後排程）結束時，若有另一筆同 App 排程已到期在 `WAIT_BUSY` 等待，`ScheduledRunner` 在 5 秒輪詢後即刻啟動，造成 14:08 結束 25 秒後 14:11 立刻重跑。
- **修改檔案與函式**：
  - `CollectorSchedule.kt`：`ScheduleMath` 新增 `RECENT_RUN_COOLDOWN_MILLIS`（10 分鐘）與 `filterRecentApps()`。
  - `CollectorController.kt`：記錄各 App 最近一次非掃描／非 dryRun 結束時間 `lastFinishedAt`，新增 `lastFinishedAtSnapshot()` 與 `recordRecentSkip()`。
  - `ScheduledRunner.kt`：`start(pending)` 對非掃描排程過濾 10 分鐘內剛結束的 App，全部被略過時清除 pending 並通知，不影響任何手動啟動。
- **新增事件／結束原因**：事件日誌（`EventType.COLLECTOR`）action `skip_recent_run`，並寫入控制器最近訊息。
- **對應測試**：`CollectorScheduleTest.filterRecentApps_skipsAppsFinishedWithinTenMinutes`。
- **實機驗證**：未實機驗證（僅單元測試驗證）。
- **Commit**：`14f2b6a`、`5332990`

---

## 2. 沒做完或刻意不做的項目

- **未實機驗證項目**：任務 A 失敗分流（當下抽抽樂全部中獎未觸發失敗）、任務 C（`linewalk`）、任務 D（夜間 Mintegral no-fill）、任務 E（`hahago`）、任務 F（排程連跑）尚未在實機重現驗證；皆已補齊對應單元測試。
- **刻意不做**：未修改既有 `LineWalkRules.lotteryTicketsLow()` 與 `CollectorRules.lotteryAvailable()` 既定簽名與行為，以維持全部 492 個既有測試完全通過、不改寫既有測試。

---

## 3. 修改行數統計（`git diff --stat main...HEAD`，含版號升級）

```text
 app/build.gradle.kts                               |   4 +-
 .../com/rewardadguard/app/collector/AppProfile.kt  |  12 +-
 .../app/collector/CollectorController.kt           |  25 ++
 .../rewardadguard/app/collector/CollectorEngine.kt | 284 ++++++++++++++++++---
 .../rewardadguard/app/collector/CollectorRules.kt  |  60 +++++
 .../rewardadguard/app/collector/CollectorSchedule.kt | 27 ++
 .../rewardadguard/app/collector/LineWalkRules.kt   | 150 ++++++++++-
 .../rewardadguard/app/collector/ScheduledRunner.kt |  26 +-
 .../app/collector/CollectorRulesTest.kt            |  63 +++++
 .../app/collector/CollectorRunPolicyTest.kt        |   2 +
 .../app/collector/CollectorScheduleTest.kt         |  20 ++
 .../app/collector/LineWalkRulesTest.kt             | 132 ++++++++++
 12 files changed, 751 insertions(+), 54 deletions(-)
```
