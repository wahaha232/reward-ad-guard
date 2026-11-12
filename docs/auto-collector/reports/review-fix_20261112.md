# Review 修正報告（2026-11-12 晚上，Claude 接手）

- 分支：`feature/auto-collector`（未合併 main、未發 Release、未改版本號）
- 依據：`Kilo提示詞_自動收取修正_2026-11-12.md`（Review of `cbddd9c`）
- 單元測試：**298 個、0 失敗**（Kilo 暫停時 273 個）
- 實機：POCO X6 Pro，無線偵錯 `192.168.68.100`，19:54～20:40 共 14 次執行，紀錄在私人 testlogs `2026-11-12/`（提交 `beed1e4`）

## 修正清單狀態

| 項目 | 狀態 | 提交 | 測試 |
|---|---|---|---|
| P0-1 停止後不得啟動下一個 App | 完成（Kilo `ee61ecb`）＋補強 | `31f8773` | `CollectorRunSequenceTest`（模擬 3 個 App 的實際迴圈）；實機通過 |
| P0-2 除錯入口加 DUMP 權限 | 完成 | `b79b031` | 實機：APK manifest 有 `android:permission="android.permission.DUMP"`，adb `collector_snapshot` 仍可用 |
| P1-3 暫停同步與自動繼續 | 完成（鎖屏實機未測） | `31f8773` | `CollectorAutoResumeTest` |
| P1-4 不限數量不受 60 分鐘限制 | 完成 | `31f8773` | `CollectorRunSequenceTest.unlimitedHasNoSixtyMinuteCap` |
| P1-5 暫停時間不計入逾時 | 完成 | `31f8773` | `ActiveClockTest` |
| P2-7 商店頁判斷收斂 | 完成（實機後再修一次） | `8ca04c5`、`c92840e` | `LotteryStorePageTest` |
| P2-8 中獎無關閉鈕 | 完成 | `8ca04c5` | `LotteryStorePageTest.aPrizeCountsWithOrWithoutItsCloseButton` |
| P2-9 遲到 bitmap 回收 | 完成 | `d7e6ba2` | 無 JVM 測試（需要 Android Bitmap）；邏輯為 `invokeOnCancellation` |
| P2-10 更名與 UI 文字 | 完成 | `31f8773`、`429fe3a` | `CollectorLimitsTest`（`hasUserCap`） |
| P2-11 觸碰誤判觀察 | 未觀察到 | — | 今晚 14 次執行沒有任何 `user_interaction` 暫停 |

### 補充說明
- **P0-1 補強**：停止旗標改放在共用的 `CollectorEnvironment`（`requestStop()`／`beginSequence()`），`start()` 在已要求停止時拒絕，補上「檢查完、還沒開始」的空窗。adb `collector_run` 原本有自己的迴圈，`collector_stop` 後也會開下一個 App，已改為共用 `CollectorRunPolicy.runSequence()`。
- **P1-3**：自動暫停（鎖屏、手指）在解鎖且 5 秒沒有觸碰後自動繼續；使用者按的暫停只能按「繼續」，也不會 10 分鐘後自動結束。Actor 每次點擊、返回、開 App 前先檢查一次（只會暫停）。
- **P2-7**：實機發現 `googleplay` 標記會把抽抽樂頁下方的橫幅廣告當成商店頁，已移除，且抽抽樂頁顯示時一律不判斷為商店頁。

## 實機測試中新發現並修正的問題

| # | 現象 | 原因 | 提交 |
|---|---|---|---|
| N1 | 連續兩次讀畫面，第二次 OCR=0；BitWalk「賺取」被判無獎勵 | Android 無障礙截圖每 ~333 ms 限一次 | `091b5bf` 截圖間隔 350 ms、被限速重試一次；snapshot 事件記 `ocrError` |
| N2 | 抽抽樂誤判商店頁按返回，離開 LINE 到 HAHAGO，之後 4 分鐘在 HAHAGO 上找 X／按返回 | 前景套件只靠視窗事件，回到已開的 App 時沒有事件 | `c92840e` 讀畫面時以實際視窗的套件為準 |
| N3 | LINE 散步趣 node 0／OCR 3，三個頁籤都找不到 | LINE 沒有 page markers，啟動畫面被當成已就緒 | `c92840e` 沒看到底部列就等 2 秒，最多 4 次 |
| N4 | 獎勵視窗後一直開 Google Play | 「獲得60枚金幣！」視窗中嵌廣告，廣告內隱藏的「確認」節點被當成按鈕 | `ae08b95` 對話框 AD 區塊內不點；有 OCR 時節點文字必須在截圖上看得到 |
| N5 | 廣告結束後自己開 Google Play 安裝視窗，按返回後又重開 LINE，廣告與獎勵都沒了（一次執行 5 次） | 返回已經回到 LINE，重開 App 把廣告清掉 | `62f3788` 返回後先讀畫面，已回到 App 就不重開 |
| N6 | 乾跑被停止時結束原因是 `done` | 乾跑讀畫面不檢查狀態 | `091b5bf` 外部停止時記 `stopped` |

## 實機結果

| 時間 | App | 模式 | 結果 | APK |
|---|---|---|---|---|
| 19:54 | 全部 | 乾跑 limit 1＋中途停止 | BitWalk 後 `sequence_stopped`，沒開下一個 App | `429fe3a` |
| 19:55 | BitWalk | 真實 limit 3 | 沒卡住（26 秒）；今日廣告已用完 → 資訊視窗 → `ad_unavailable`；發現 N1 | `429fe3a` |
| 19:59 | BitWalk | 真實 limit 3 | 每張都有 OCR；賺取頁確認無獎勵 | `429fe3a`＋N1 |
| 19:59 | AHEALTH | 真實 limit 1 | 點「領取 10 點數」→ `changed`，未計入；**待查** | 同上 |
| 20:00 | WALK EARN | 真實 limit 1 | node 5／OCR 5，找不到頁籤；**待查** | 同上 |
| 20:00 | HAHAGO | 真實 limit 1 | 關彈窗後無候選 | 同上 |
| 20:00 | LINE 散步趣 | 真實 limit 1 | 啟動畫面就判斷（N3） | 同上 |
| 20:01 | LINE 抽抽樂 | 真實 limit 3 | 卡在 HAHAGO 4 分鐘（N2） | 同上 |
| 20:11 | LINE 散步趣 | 真實 limit 1 | **rewards=1**；之後開 Google Play（N4） | `c92840e` |
| 20:13 | LINE 抽抽樂 | 真實 limit 1 | N4 擋住入口 | `c92840e` |
| 20:18 | LINE 散步趣 | 真實 limit 2 | rewards=1；N5 五次 | `ae08b95` |
| 20:24 | LINE 抽抽樂 | 真實 limit 1 | 一般 5 張抽中 40 枚，rewards=1；BIG／免費因 N5 失敗 | `ae08b95` |
| 20:32 | LINE 散步趣 | 真實 limit 2 | **rewards=2**（紅利 60 枚 ×2，金幣 7930→7990）；Google Play 視窗 4 次都只按返回 | `62f3788` |
| 20:36 | LINE 抽抽樂 | 真實 limit 2 | **BIG 100 枚、免費 10 枚**，rewards=2 | `62f3788` |

## 沒做或做不到

1. **鎖屏後自動繼續（實機）**：手機鎖屏有密碼，不能也不應該代為解鎖。需要使用者在手機旁：開始一次真實收取 → 鎖屏 30 秒 → 解鎖，看 UI 與通知顯示「已暫停：手機已鎖定」再自動繼續。
2. **使用者在 UI／通知按暫停、繼續**：需要人操作，未測。
3. **AHEALTH**：點「領取 10 點數」後沒有算成獎勵，該次沒有存截圖，無法判斷是否實際領到。下次請在該流程加截圖再測。
4. **WALK EARN**：畫面只有 5 個節點、5 段文字，可能停在登入或推廣頁，需要截圖確認。
5. **BIG 倒數中仍會被點**：BIG 抽完後又點了 2 次（各算一次失敗）才改抽免費，沒有損失但浪費時間；倒數判斷要再看抽完後的畫面。
6. **APK 未提交到 repo**：這次只安裝到手機，沒有提交 `TOOLS/apk`（公開 repo 已 23 次 APK，見 PROGRESS 已知問題）。
