# Kilo 總提示詞：獎勵守衛「自動收取」— 全自動開發（2026-11-13～11-15）

請用繁體中文寫所有報告與回覆。先完整讀完本文件，再動手。**本任務全自動執行，不等待使用者回覆。**

---

## 0. 任務總覽

把電腦端收獎勵腳本 **pc-collector**（Python，ADB＋OCR）的功能移植進手機 App **獎勵廣告守衛**（Kotlin＋Compose＋AccessibilityService），讓手機自己就能全自動收取獎勵 App 的廣告獎勵。

- 守衛 repo（要修改）：`wahaha232/reward-ad-guard`，分支 **`feature/auto-collector`**
- pc-collector repo（**只讀參考，不准修改、不准提交**）：`wahaha232/pc-reward-collector`
- 測試紀錄 repo（**私人**）：`wahaha232/reward-collector-testlogs`
- 整體規劃：`docs/auto-collector/整合規劃.md`（守衛 repo 內）

時程：**11-13（五）、11-14（六）、11-15（日）三天開發＋測試**。11-16（一）使用者會請另一個 AI 分析測試紀錄，所以紀錄必須完整、可讀、已推送。

---

## 1. 全自動規則（最重要）

1. **不要停下來等使用者。** 遇到問題依第 7 節的「自動處理規則」處理，把決定寫進 `docs/auto-collector/DECISIONS.md`，然後繼續。
2. **不要問問題。** 規劃第 6 節的決定已由使用者確定如下，直接照做：
   - 維持 App 名稱「獎勵廣告守衛」與套件名稱，新增「自動收取」頁籤。
   - OCR 用 **ML Kit 中文內建模型版**（`com.google.mlkit:text-recognition-chinese`，取目前穩定版）。
   - pc-collector 繼續並行，本任務只讀不改。
   - **pc-collector 支援的 App 全部都要移植，一個都不能少**：BitWalk、AHEALTH、WALK EARN、HAHAGO、LINE 散步趣、LINE 抽抽樂（共 6 個）。順序：BitWalk 先做，其餘依上列順序。
   - **不做** AAA Earn App、懂球帝（pc-collector 沒有規則，使用者決定不加入）。
   - **11-13（五）、11-14（六）、11-15（日）三天每天都要做實機測試**，見第 6 節「每日測試要求」。
3. **每完成一個階段**：寫階段報告、更新進度檔、提交、推送（第 3 節）。
4. **工作中途也要推送**：至少每 2 小時、以及每次工作階段結束前，提交並推送目前進度（未完成的工作可以用 `WIP:` 開頭的提交訊息），並更新 `PROGRESS.md` 的「下一步」。這樣另一台裝置隨時能接手。

---

## 2. 開工／接手步驟（每次開始工作都要做）

這份工作會在兩台電腦之間輪流進行。**每次開始時一律先做以下步驟，不要假設本機是最新狀態。**

1. 準備兩個 repo（不存在就 clone，存在就 fetch）：
   ```bash
   git clone https://github.com/wahaha232/reward-ad-guard.git        # 若不存在
   git clone https://github.com/wahaha232/pc-reward-collector.git    # 放在守衛 repo 的同層，若不存在
   git -C <守衛repo> fetch origin
   git -C <pc-collector repo> fetch origin && git -C <pc-collector repo> checkout origin/main
   ```
   本機的 pc-collector 若有未提交修改，**不要動它們**，改在另一個資料夾 clone 一份乾淨的來讀。
2. 切到工作分支：
   - 若 `origin/feature/auto-collector` **已存在**：`git checkout feature/auto-collector && git pull --ff-only`。
   - 若**不存在**（第一次開工）：從 `origin/fix/close-assist-1102` 建立：
     `git checkout -b feature/auto-collector origin/fix/close-assist-1102`，並 `git push -u origin feature/auto-collector`。
3. 若守衛 repo 的 `app/` 有未提交修改：
   - 若是本任務自己留下的半成品（`PROGRESS.md` 有記錄）→ 繼續完成。
   - 若不是 → `git stash push -m "foreign changes <日期>"`，在 `DECISIONS.md` 記下 stash 名稱，繼續工作，**不要丟棄**。
4. 讀 `docs/auto-collector/PROGRESS.md`，從「下一步」接著做。
5. 確認本機建置環境（第 5 節），跑一次 `testDebugUnitTest` 記下基準。

---

## 3. 進度與報告格式（另一台裝置靠它接手）

全部放在守衛 repo 的 `docs/auto-collector/`：

| 檔案 | 用途 | 何時更新 |
|---|---|---|
| `PROGRESS.md` | 唯一的進度真相：各階段狀態（未開始／進行中／完成／部分完成）、目前在做什麼、**下一步**、待實機測試清單、已知問題、最近提交雜湊、最後更新時間與機器名稱 | 每次推送前 |
| `DECISIONS.md` | 所有自行決定的事（含原因、日期） | 有決定就寫 |
| `reports/phase-N_YYYYMMDD.md` | 階段報告：做了什麼、改了哪些檔、新增依賴、APK 體積、測試數量與結果、實機測試摘要（含測試紀錄 repo 的路徑）、未完成事項 | 階段完成時 |
| `HANDOFF.md` | 給另一台裝置的接手說明：環境設定、常用指令、目前狀態一句話 | 第一次建立，有變動時更新 |

提交規則：英文標題、內文寫原因；**提交訊息開頭不可有 BOM 等不可見字元**；結尾加你平常的署名行。每個階段完成後：`git push origin feature/auto-collector`。

**禁止**：合併到 `main`、推送 `main`、建立 GitHub Release、修改版本號、`git push --force`、刪除任何分支、修改 pc-collector。

---

## 4. 各階段內容

### 階段 0：準備（11-13 上午）
- 完成第 2 節。建立 `docs/auto-collector/` 下的四個檔案。
- 基準測試數記入 `PROGRESS.md`（應為 224 個以上、0 失敗）。

### 階段 1：基礎建設（11-13）
> **現況（11-12 晚）**：`feature/auto-collector` 已有階段 1 的提交 `ccaacbb`～`a9a6dad`（Snapshot、ScreenReader、CornerGlyphMatcher、Actor、亮屏／鎖屏、互斥），但還沒有 `docs/auto-collector/PROGRESS.md` 等文件，也沒有實機驗證紀錄。開工時先逐項核對下列 8 點，**已完成的不要重做**，補上缺的（特別是第 8 點除錯入口與階段 0 的文件），再寫階段 1 報告。
新套件 `com.rewardadguard.app.collector`，能寫成純 Kotlin 的就寫成純 Kotlin（方便 JVM 測試）。**本階段不在任何獎勵 App 上點擊。**
1. **Snapshot 模型**：畫面寬高、時間、前景套件／Activity、文字框清單（文字、外框、來源＝NODE／OCR）。可從 pc-collector 的 `tests/fixtures/*.ocr.json` 載入（格式見 `reward_collector/ocr.py` 的 `parse_ocr_json`）。把這些 `.ocr.json`（不含圖片）複製到守衛 `app/src/test/resources/collector-fixtures/`，寫測試確認全部能載入。
2. **文字正規化與比對**：移植 `ocr.py` 的 `matches`／`find`／`contains`，含全形半形與「中文字間多餘空格」（Windows OCR 會產生「關 閉」）。
3. **ScreenReader**：先讀無障礙節點樹（`rootInActiveWindow`，有文字／描述、可見的節點），再用 `AccessibilityService.takeScreenshot()`＋ML Kit OCR 補充（`accessibility_service_config.xml` 加 `android:canTakeScreenshot="true"`）。處理截圖頻率限制等錯誤碼，失敗時回傳只有節點的結果，不可崩潰；Android 10 以下只用節點。**只在 Collector 主動要求時執行**，不可放進 `onAccessibilityEvent` 熱路徑。
4. **CornerGlyphMatcher**：移植 `vision.py`、`close_detection.py`、`apps/base.py` 的 `_corner_buttons`／`_install_card_back`；模板 `reward_collector/assets/templates/*.png` 複製到 `app/src/main/assets/templates/`。**不引入 OpenCV**，用 Kotlin 自寫 NCC，只搜角落小區域，門檻比照 pc-collector（0.78）。純函式＋JVM 測試。
5. **Actor**：`tap`（`dispatchGesture`，並呼叫 `SelfClickTracker.markOwnClick()`）、`back`／`home`（`performGlobalAction`）、`launch(package)`（比照 `launchRewardApp()`）、可暫停／可結束的 `waitFor`（暫停時間不計入逾時，參考 `control.py`）。每個動作寫入事件資料庫，新增 `EventType.COLLECTOR`。
6. **執行環境**：執行中加 `TYPE_ACCESSIBILITY_OVERLAY` 1×1 透明浮層並 `keepScreenOn`；`KeyguardManager.isKeyguardLocked()` 為真 → 自動暫停；偵測到真人觸控（既有的 `lastUserInteractionAt`／`TYPE_TOUCH_INTERACTION_START`）→ 自動暫停。
7. **與守衛互斥**：`CollectorState`（IDLE／RUNNING／PAUSED）。RUNNING 時守衛的「自動點擊關閉鈕」與「跳轉自動拉回」不執行（記一筆紀錄）；跳轉**偵測**照常，結果交給 Collector。IDLE 時守衛行為與現在完全相同，既有測試必須全過。
8. **除錯入口（只讀）**：在 `app/src/debug/.../DebugTestActivity.kt` 加 `collector_snapshot`（輸出文字框清單、來源、OCR 耗時，存截圖到 App 私有目錄）與 `collector_corners`（四角最高分與位置）。讀畫面前先讓除錯 Activity `moveTaskToBack` 並延遲 1 秒，讀的是它背後的畫面。

### 階段 2：收取引擎（11-13～11-14）
移植 `apps/base.py` 的核心狀態機為 `CollectorEngine`（協程）：
- 開 App → 處理彈窗（`dismiss_popups`、`_find_info_dialog_close`）→ 切頁籤（`open_tab`）→ 找領獎按鈕（`_pick_reward`，依 `reward_terms` 順序、排除 `avoid_terms`、同按鈕嘗試上限）→ 點擊 → 判斷是否為廣告（`_ad_evidence`、`_looks_like_ad`，含「to be rewarded」倒數、頁籤列消失、廣告 SDK Activity；**可直接用守衛的 `AdActivityClassifier`**）→ 處理廣告（`_handle_ad`：找 X／>>、只搜角落、避開商店連結、逾時按返回、install card「←」、「第 N 則共 M 則」）→ 確認獎勵（`after_reward`、獎勵對話框）→ 下一輪。
- 跳轉：用守衛的 `RedirectChainPolicy` 判斷，Collector 負責拉回（可呼叫 `ReturnController`）後繼續等廣告結束。
- 停止條件：沒得收、使用者結束、選擇的上限 N（**預設不限**，使用者要求收到 App 沒得收為止）。暫停／繼續／結束。
- 每個 App 的差異用 `AppProfile`（套件、頁籤、頁籤座標、reward_terms、avoid_terms、特殊處理）。
- **回歸測試**：用 collector-fixtures 對 `_pick_reward`、廣告判定、關閉鈕／下一步選擇、獎勵對話框判定寫 JVM 測試，結果要與 pc-collector 對應測試（`tests/test_p0_fixes.py`、`test_p1_fixes.py` 等）的預期一致。
- **乾跑模式**：引擎支援 `dryRun`（照常讀畫面與決策，但不點擊、只記錄「本來要點哪裡」）。

### 階段 3：BitWalk（11-14）
- 移植 `apps/bitwalk.py`：頁籤「步行」「賺取」、`reward_terms`／`avoid_terms`、同按鈕上限 50、「您獲得了BTC！／關閉」對話框、「什麼時候可以收到BTC？」說明視窗（info dialog）、廣告可能在 BitWalk 自家 Activity 內播放（倒數字樣＋頁籤列消失判斷）、install card「←」。
- 實機測試流程見第 6 節。驗收：單次收 30 個以上、計數與 App 顯示的印章數變化一致、沒有誤點導流（商店／瀏覽器跳轉若發生必須是廣告自己跳的，而且被拉回）。

### 階段 4：其他 5 個 App（11-14 下午～11-15，全部必做）
依序移植並各自實機測試：`apps/ahealth.py` → `apps/walkearn.py` → `apps/hahago.py` → `apps/linewalk.py`（LINE 散步趣：先紅利再普通、時間頁「▶ 8 獲得」、排除 adjoe 橫幅與 LINE POINTS GAME）→ 抽抽樂（`apps/linewalk.py` 後段的 linelottery：獨立選項、不在「全部」裡；順序 BIG 50 張 → 免費抽 → 一般 5 張，倒數中不等待，一般抽到券低於 5 張為止）。**6 個 App 都是必做，不是「時間允許」。**
- 手機上沒安裝的 App 才可跳過，並記錄。LINE 散步趣在 pc-collector 仍有已知卡關（見其 `docs/測試紀錄_2026-11-11.md`），照紀錄裡的修法實作。

### 階段 5：介面與收尾（11-15 下午～晚）
- Compose 新頁籤「自動收取」：勾選 App（含「全部」）、數量（不限／收滿 N）、乾跑開關、開始／暫停／繼續／結束、目前狀態（App、畫面判斷、已收數量、經過時間）、最近 50 筆動作。
- 常駐通知：執行中顯示進度，附「暫停／繼續／結束」按鈕。
- 執行紀錄：每次執行一個資料夾（第 6 節格式），App 內可匯出（沿用既有 FileProvider 匯出）。
- README 加一節說明；最終報告 `reports/final_20261115.md`：總結、各 App 實測結果表、已知問題、建議下一步。
- **不發 Release、不改版本號、不合併 main。**

---

## 5. 建置環境

- 每台電腦的 JDK 路徑不同：以環境變數 `JAVA_HOME` 為準，建置時加 `-Dorg.gradle.java.home=<JAVA_HOME>`；**不要修改 `gradle.properties`、`local.properties` 並提交**。
- Windows 上用 PowerShell 執行 `gradlew.bat`（路徑可能含空格）。
- 指令：`gradlew assembleDebug testDebugUnitTest`。每個提交前都要通過。
- **簽章**：手機上的版本用共用金鑰（憑證 SHA-256 `520f639b3a03c5d2b457cedd9f34dad6b1ed713c7c9dc564095649b5ec439dc8`），設定方式在 `local.properties` 的 `rewardadguard.signing.*`（見 `app/build.gradle.kts` 開頭註解）。
  - **安裝到手機前一定要用 `apksigner verify --print-certs` 檢查 APK 憑證**。不符（本機沒設共用金鑰）→ **不要安裝、絕不移除手機上的 App**，改成只做程式與 JVM 測試，在 `PROGRESS.md` 的「待實機測試」記下，等到有共用金鑰的電腦再測。

---

## 6. 實機測試與紀錄

### 前提（由使用者事先準備；若不符就跳過實機測試，繼續寫程式）
- 手機 POCO X6 Pro（HyperOS）以 USB 連接（序號 `797XJBXSGICEEIKZ`）或無線偵錯；`adb devices` 看得到。
- 手機解鎖、螢幕保持亮著；守衛的無障礙服務已開、自啟動與省電「無限制」已開。

### 安裝
- `adb install -r <APK>`（先確認憑證，見第 5 節）。安裝失敗（使用者沒按確認、`INSTALL_FAILED_USER_RESTRICTED`）→ 每 10 分鐘重試一次，最多 3 次；仍失敗就跳過實機測試，記錄後繼續寫程式。
- 安裝後用 `adb shell monkey -p com.rewardadguard.app -c android.intent.category.LAUNCHER 1` 打開一次守衛，等 15 秒，確認 `adb shell dumpsys accessibility` 裡有「獎勵廣告守衛」。沒有 → 記錄並跳過實機測試（**不可用 adb 修改無障礙設定**）。
- Git Bash 下先 `export MSYS_NO_PATHCONV=1`。**禁止使用 `uiautomator dump`**（會踢掉守衛的無障礙服務）。

### 執行測試
- 用除錯入口啟動 Collector（請在階段 2 實作 `collector_run` 指令，參數：App、上限、dryRun），例如：
  ```bash
  adb shell am start -n com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity -f 0x10008000 --es cmd collector_run --es apps bitwalk --es limit 0 --es dryrun false
  ```
- 每個 App 新實作的第一次一定先跑 **dryRun**（只看不點），確認判斷正確後再跑真實收取。
- 測試期間**不要用 adb 點擊或操作獎勵 App**；所有操作都必須由 Collector 自己完成（這正是要測的東西）。可以用 `adb exec-out screencap` 觀察。
- 一次真實收取最長 60 分鐘；BitWalk 每天上限 50 個，收完就換下一個 App。

### 測試紀錄格式（Collector 自己在手機上寫）
每次執行在 App 私有目錄建立 `collector_runs/run_YYYYMMDD_HHMMSS/`：
- `events.jsonl`：每行一個事件（時間、App、畫面判斷、證據、動作、座標、結果、錯誤）。
- `summary.json`：開始／結束時間、結束原因、各 App 收到數量、廣告數、逾時數、跳轉數、錯誤數、版本／提交雜湊。
- `console.log`：人看的逐行紀錄。
- `shots/`：截圖。成功的廣告只留首尾兩張；失敗、逾時、跳轉、無法判斷的畫面全留。

### 每日測試要求（11-13、11-14、11-15 三天都要做）
- **11-13（五）**：階段 1 除錯入口實測（BitWalk 首頁 `collector_snapshot`／`collector_corners`）；階段 2 完成後對 BitWalk 跑 dryRun。
- **11-14（六）**：BitWalk 真實收取（目標收滿當天上限或 30 個以上）；已移植的其他 App 至少各跑 1 次 dryRun＋1 次真實收取。
- **11-15（日）全面測試**：6 個 App **每個都要**至少 1 次真實收取（抽抽樂可只抽到券或次數用完）；最後用「全部」跑 1 次完整回合（BitWalk 若當天已收完，就跑剩下的 App）；再跑 1 次守衛模式回歸（Collector 為 IDLE 時，使用者手動看廣告的跳轉攔截仍正常）。
- 每天結束前在測試紀錄 repo 寫 `YYYY-MM-DD/DAILY.md`：當天每次執行的 App、收到數量、成功／失敗、主要問題，供 11-16 分析。
- 某天手機不在而無法測試：當天 `DAILY.md` 照樣寫，註明原因，隔天補測。

### 拉回與上傳
- 每次執行結束後：`adb exec-out run-as com.rewardadguard.app tar -c files/collector_runs/<run>` 拉回本機（或逐檔 cat），同時匯出守衛事件資料庫（`databases/reward_ad_guard.db`、`-wal`、`-shm`）。
- 上傳到**私人** repo `wahaha232/reward-collector-testlogs`：資料夾 `YYYY-MM-DD/<run>/`，附一份 `NOTES.md`（測了什麼、預期、實際、守衛提交雜湊、手機上看到的獎勵數字變化）。
- 截圖每天總量超過 200 MB 時，只上傳失敗類截圖，其餘留在本機並在 `NOTES.md` 註明。
- 守衛 repo 是**公開**的：**測試紀錄、截圖、資料庫一律不得提交到守衛 repo。** 階段報告只放摘要與測試紀錄 repo 的路徑。
- 紀錄中若出現通知內容裡的真實人名、群組名、帳號，寫進 `NOTES.md` 或報告時要遮蔽。

---

## 7. 自動處理規則（取代「停下回報」）

| 狀況 | 處理 |
|---|---|
| 建置或測試失敗且 2 小時內修不好 | 還原到最後一個綠燈提交的狀態，把失敗的嘗試另開分支 `wip/<主題>` 推送，記入 `PROGRESS.md`「已知問題」，繼續下一項 |
| 手機不在、鎖住、安裝被拒、無障礙沒連上 | 跳過實機測試，記入「待實機測試」，繼續寫程式；每完成一個階段再嘗試一次 |
| 某個 App 沒安裝或改版到認不得 | 跳過該 App，記錄原因與截圖，做下一個 |
| 實機測試發現誤點導流（點了導致開商店／瀏覽器，且不是廣告自己跳的） | **立即結束該次執行**，保存紀錄，把該 App 改回只跑 dryRun，修正後重測；不可帶著誤點繼續收 |
| 同一畫面連續 5 次判斷不出來 | Collector 按返回、重開 App；同一 App 一次執行內發生 3 次就略過該 App |
| 發現 pc-collector 的規則有錯 | 在守衛這邊修正並寫進 `DECISIONS.md`；**不要改 pc-collector** |
| 需要使用者決定的產品問題 | 選最保守、最不會誤點的做法，寫進 `DECISIONS.md` 的「待使用者確認」 |
| 三天時間不夠 | 6 個 App 都必須至少移植完並實測過一次。優先順序：6 個 App 都能收 ＞ 紀錄完整 ＞ 介面美化。犧牲介面，不犧牲 App 與測試。未完成的寫進最終報告 |

---

## 8. 安全底線（任何情況都不可違反）

- 不在 Collector 之外的 App（包含 Chrome、Play 商店、設定）上自動點擊任何東西，返回鍵除外。
- 不點任何「安裝」「下載」「購買」「訂閱」「允許」類按鈕；不點廣告本體與商店連結。
- 不修改系統設定、不用 adb 開關無障礙、不移除任何 App、不清除任何 App 資料。
- 不把金鑰、密碼、測試紀錄、截圖提交到公開的守衛 repo。

---

## 9. 完成時的狀態（11-15 結束前）

- `feature/auto-collector` 已推送，`PROGRESS.md` 是最新的，`reports/` 有每個階段的報告與 `final_20261115.md`。
- `reward-collector-testlogs` 有三天所有實機測試的紀錄，每個資料夾都有 `NOTES.md`，每天有 `DAILY.md`。
- 最終報告有 6 個 App 的實測結果表（每個 App：測試次數、收到數量、失敗原因）。
- 最後一個提交附 `TOOLS/apk/reward-ad-guard-debug.apk`（標題 `Ship the debug APK built from <短雜湊>`）。
