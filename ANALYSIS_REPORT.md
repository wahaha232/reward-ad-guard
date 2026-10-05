# ANALYSIS REPORT — 現況總結與結論

| Item | Value |
| --- | --- |
| 報告日期 | 2026-10-05（**2026-10-05 二修**：見 §10） |
| 對象 | Reward Ad Guard（`com.rewardadguard.app`） |
| 涵蓋範圍 | 程式碼完成度、建置、測試、實機驗證、裝置限制 |
| 驗證裝置 | **POCO X6 Pro** (`2311DRK48G`, codename `duchamp`), Android 16 / API 36, HyperOS 3 / `OS3.0.3.0.WNLTWXM` |
| 分支 | `HEAD` — 已提交（`a7a8e62`） |
| 主要證據來源 | `.git/devicedump/reward_ad_guard.db`（2026-10-05 09:56 自實機拉出，1,609,728 bytes） |

> **裝置型號更正（2026-10-05）**：本報告初版寫「Redmi Note 13 Pro 5G」，**這是錯的**。
> 型號 `2311DRK48G`、代號 `duchamp` 對應的是 **POCO X6 Pro**。已更正。

> **這份報告的目的**：把「App 到底做完了沒」這個問題，拆成**可以分別回答**的問題。
> 先前的討論把「程式寫完了」和「在這台手機上驗得出來」混為一談，導致
> 「這個 App 應該是沒辦法完成」這種結論。**這兩個問題的答案不一樣。**

---

## 1. 一句話結論

> **程式已經完成；驗證也已經完成很大一部分 —— 而且是實機完成的。**
>
> **2026-10-05 深夜的重大更正**：本報告初版寫「核心路徑從未在實機觸發」、
> 「驗證受阻於手機」。**兩者都不成立。** 實機資料庫
> （`.git/devicedump/reward_ad_guard.db`）證明：
>
> * 服務在 **10-02、10-03（兩次）、10-05 早上**都曾被綁定；
> * **10-03 17:13→20:46（3 小時 32 分）** 連續運作，redirect 19 / return 成功 19 / 失敗 0；
> * **10-03 23:18→23:23** redirect 12 / **block 9** / return 成功 12 / **失敗 0**；
> * 10-05 早上 09:17→09:52 另有 **10,032 筆**真實事件。
>
> **跳轉攔截與返回，在真實廣告上成功過，不只一次。**
>
> 而所謂「HyperOS 永不綁定」的阻塞，**很可能是我方測試流程自己造成的** ——
> 測試工具每一條指令都帶 `am start -S`（force-stop），而 force-stop 會讓系統
> 解除無障礙服務綁定。詳見 §3（已重寫）與 §4.2。

換句話說：**這個 App 不是做不完，也不是驗不完。它多半只是一直被自己的測試工具關掉。**

---

## 2. 目前在什麼狀態

### 2.1 已完成並驗證（有實機證據）

| 項目 | 狀態 | 證據 |
| --- | --- | --- |
| 建置（debug + release） | ✅ | `BUILD SUCCESSFUL in 8m 1s` / `EXITCODE=0`（57 tasks，`--rerun-tasks`） |
| 單元測試 | ✅ | **122 tests / 11 suites / 0 failures** |
| Lint | ✅ | **0 errors / 37 warnings**（本次修掉了 2 個 error） |
| 安裝到實機 | ✅ | `adb install -r` → `Success`，`versionCode=1` |
| 冷啟動 | ✅ | `LaunchState: COLD`、`mCurrentFocus=…ui.MainActivity`、無 `FATAL` |
| **無障礙服務曾被綁定** | ✅ **（2026-10-02、10-03 ×2、10-05）** | `dumpsys accessibility` 顯示 `Service[label=Reward Ad Guard]` + 完整 event type 清單、`Crashed services:{}`，首頁狀態自動由 `NOT CONNECTED` 翻成 `MONITORING`。**且實機 DB 有跨日事件紀錄**（見 §2.2） |
| 四個頁籤 UI | ✅ | 首頁 / App 清單 / 記錄 / 設定 全部以繁中渲染 |
| 設定讀寫（ADB 驅動） | ✅ | `dump_settings` 與 `set_assist_action`，且與 DataStore 原始位元組一致 |
| 記錄（Room）| ✅ | 事件有寫入、統計頁正常 |
| 匯出（TXT / CSV / JSON） | ✅ | 9,060 bytes 檔案寫入 `cache/exports/`，`# events=62` |
| 分享（FileProvider） | ✅ | 交接給 `com.android.intentresolver/.ChooserActivity` |
| Release 不含測試入口 | ✅ | `DebugTestActivity` / `DebugTestReceiver` / `DEBUG_TEST` 全部 `False` |
| Release 權限最小化 | ✅ | 只有 `POST_NOTIFICATIONS` + androidx 自動注入的那一個 |

### 2.2 三條核心路徑的實機觸發狀態（**2026-10-05 重寫**）

> **這節原本寫「三條核心路徑從未在實機被觸發過」，那是錯的。**
> 我當時沒有查閱 `.git/devicedump/reward_ad_guard.db`。查了之後發現，
> **跳轉攔截與關閉鈕偵測都有實機命中紀錄**。

| 路徑 | 單元測試覆蓋 | 實機觸發 | 實機證據 |
| --- | --- | --- | --- |
| 跳轉攔截（redirect / block / return） | ✅ `SmartRedirectEngineTest`（16） | ✅ **已觸發** | redirect 19 + 12、block 9、return 成功 19 + 12、**失敗 0** |
| 關閉鈕偵測（`[X]` 評分） | ✅ `CloseButtonDetectorTest`（23） | ✅ **已觸發** | `CLOSE_DETECT` 4,814 筆，`score=50~75`，含 `CLOSE_BOUNDS original=558x96 scale=3.0 assisted=1674x288` |
| 關閉鈕**實際點擊**（`assist_action` 生效） | ✅ `AssistDecisionTest`（15） | ❌ **未觸發** | 設定是 `assist_action=NONE`（見 §2.4），因此只偵測不點，全程記為 `CLOSE_MISS` |
| 合成前景事件（`simulate_foreground`） | —（走的是正式 `handleWindowEvent`） | ❌ **未觸發** | 測試工具用 `am start -S`，自己把服務 force-stop 掉了。見 §3.2 與 §4.2 Step 1 |

**實機 session 原始紀錄**（`.git/devicedump/reward_ad_guard.db` → `sessions` 表）：

| sessionId | 起訖 | 時長 | redirect | block | return 成功 | return 失敗 | closeDetect |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `SESSION_20261003_171346_001` | 10-03 17:13:46 → 20:46:04 | 3 時 32 分 | 19 | 1 | 19 | **0** | 1,585 |
| `SESSION_20261003_231852_001` | 10-03 23:18:52 → 23:23:36 | 4 分 44 秒 | 12 | **9** | 12 | **0** | 0 |

**為什麼這不可能是合成事件**：`events` 表 10,032 筆的 `sourcePackage` **全部是
`jp.paddleinc.walk`**（真實獎勵步數 App），**沒有任何一筆來自測試用的
`com.example.fake.reward`**。`CLOSE_BOUNDS` 的 `original=558x96` 是真實廣告
關閉鈕的實際像素尺寸，合成事件不可能產生。

**為什麼「實機觸發」在初版會被誤判為「從未」**：初版把「服務現在沒綁定」
直接推論成「服務從未綁定、事件從未產生」，卻沒有去查那份已經躺在
`.git/devicedump/` 裡的實機資料庫。**證據一直都在，是我沒看。**

**驗證過的 122 個測試分佈**（11 suites，`@Test` 實際計數）：

| Suite | Tests | 涵蓋 |
| --- | --- | --- |
| `CloseButtonDetectorTest` | 23 | `[X]` 評分與幾何展開 |
| `FormatTest` | 20 | 時間 / 套件 / session 格式化 |
| `SmartRedirectEngineTest` | 16 | 跳轉風險評分與政策決策 |
| `AssistDecisionTest` | 15 | 八種拒絕原因必須互異 |
| `SessionStateTest` | 11 | 狀態機 + session id 工廠 |
| `StringResourceTest` | 9 | zh-TW / en 目錄一致性 |
| `CandidateSearchTest` | 8 | 已安裝 App 搜尋比對規則 |
| `NewAppSafeModeTest` | 8 | 新 App 預設 `LOG_ONLY` 的後果 |
| `PackageClassifierTest` | 6 | `DestinationKind` 語意 |
| `SessionManagerPublishTest` | 3 | `sourcePackage` 不再殘留 |
| `MainViewModelConstructorTest` | 3 | `SavedStateHandle` 注入 |
| **合計** | **122** | |

**為什麼「實機觸發」全都是「從未」是必然的**：這三條路徑全部**必須收到真實的
`AccessibilityEvent`** 才能啟動，而本機的無障礙服務**永遠不會被綁定**（§3）。
服務沒綁定 → 沒有事件 → 沒有路徑可觸發。

> 這代表一件事：**122 個測試證明的是「邏輯對」。**
> 而實機 DB 證明的是**「它真的動過，而且成功過」** —— 這兩者現在都有，
> 不再只是「邏輯對」。

### 2.4 已由實機 DB 證實的**設定陷阱**（新發現）

這兩項是「App 好像沒作用」的直接原因，先前從未被指出：

| 現象 | 實機證據 | 後果 |
| --- | --- | --- |
| `assist_action = NONE` | `prefs.bin`（62 bytes）二進位內容含 `assist_action` → `NONE` | 關閉鈕偵測到 `score=75` **卻永遠不點**；`CLOSE_MISS` 訊息全部是 `assist action disabled or throttled`，共 2,408 筆。**看起來像普通的節流，完全不像設定問題** |
| 新加入的 App 預設 `LOG_ONLY` | `NewAppSafeModeTest`（8 個測試）＋ `EXTRA_INFO` 402 筆 `PASSIVE app not monitored` | 使用者加入獎勵 App 後**以為開好了，其實只記錄不阻擋** |

換句話說：使用者把 App 加進清單、也開了無障礙服務，**但因為這兩個預設值，
關閉鈕輔助完全不會動作，跳轉也只記錄**。這是「程式沒問題但感覺沒用」的
真正來源 —— 不是程式壞了，是預設值讓它保持安靜。

### 2.3 未驗證的小項目（與裝置限制無關，只是還沒做）

- 首頁啟動時 Gboard 清除的 snackbar 是否出現
- `ASSIST_WHEN_IDLE` 開關在 UI 上的實際行為
- `CLOSE_DETECT` 的 10 秒去重是否真的生效
- 已安裝 App 搜尋框的**打字互動**（程式已修、8 個單元測試過，但沒實機打過字）
- 桌面抽屜圖示在 48dp 下的實際外觀（手機 PIN 鎖，ADB 無法驅動）

---

## 3. 核心問題：為什麼現在服務不綁定（**2026-10-05 重寫**）

> **本節初版的結論是錯的。** 初版把「Enabled 有、Bound 沒有」歸因於
> *HyperOS 平台用白名單擋第三方無障礙服務*。後來發現，**這個症狀在專案自己
> 的 10-02 紀錄裡就出現過，而且當時的成因寫得很清楚：`am force-stop`。**
>
> 換句話說：**我們把一個自己造成的症狀，誤判成手機的限制。**

### 3.1 症狀本身（觀察仍然有效）

```
Enabled services:{{com.rewardadguard.app/...RewardAdAccessibilityService}, 其他, 其他}
Bound services  :{其他, 其他, 其他}          <- 唯獨少了我們
Crashed services:{}                          <- 沒有崩潰
```

「列在 Enabled、卻不在 Bound、也沒 crash」是事實。錯的是**歸因**。

### 3.2 更合理的解釋：`am start -S` 每次都在 force-stop

**證據鏈（三項互相獨立，但指向同一結論）：**

1. **專案自己在 10-02 就記錄過同一症狀。** `DEVELOPMENT_REPORT.md` §6.1 寫著：
   > `am force-stop` silently unbinds the accessibility service… the entry
   > disappears from `Bound services` while remaining in `Enabled services`

   這和「HyperOS 白名單」的症狀**一字不差**。我們早就知道原因了。

2. **測試工具每一條指令都帶 `-S`。** `TOOLS/auto_test.ps1` 的
   `Invoke-TestCommand`（第 564 行起）：
   ```powershell
   # -S force-stops the app first. Without it the activity is reused and onCreate
   # does not run again ...
   $arguments = @('shell', 'am', 'start', '-S', '-n', $DebugComponent)
   ```
   `-S` 的意思就是「先 force-stop 目標 App」。而 `simulate_foreground`、
   `dump_state` 等**全部**透過這個函式送出。

3. **時間線吻合。** 10-05 早上 09:17→09:52 服務還在正常收事件（1 萬筆）→
   09:56 拉出 DB → **之後才開始用 `-S` 流程做測試** → 接著就「永遠不綁定」。

**Android 的原始行為**：`AccessibilityManagerService` 在套件被 force-stop 時，
會把該套件的服務從 `enabled_accessibility_services` 移除並寫回設定
（`onHandleForceStop`）。這**不是 HyperOS 專屬設計，是 AOSP 原生行為**。

**最諷刺的後果**：`simulate_foreground` 在任何 Android 裝置上都測不出東西 ——
指令送進去 → App 被 force-stop → 服務解除綁定 → 回報
`service is not bound`。**工具會親手把要測的東西關掉。**

### 3.3 ⚠️ 連帶風險：別急著做 AOSP 對照實驗

§4 原本建議「用 AOSP 模擬器當對照組，區分是 HyperOS 還是 App 的問題」。
**在修好測試入口之前不要跑這個實驗**：因為 `-S` 流程在模擬器上同樣會
force-stop，結果一樣是「不綁定」，判定矩陣會落在 FAIL / FAIL，
進而觸發 STOP-1「重新檢討架構」—— **等於因為測試工具的缺陷而誤判專案失敗。**

### 3.4 信心程度（誠實標註）

| 命題 | 信心 | 依據 |
| --- | --- | --- |
| 「`-S` / force-stop 造成解除綁定」 | **高** | 工具原始碼 + 專案自己的 10-02 實測 + 時間線，三者獨立吻合 |
| 「完全沒有 HyperOS 因素」 | **中** | MIUI/HyperOS 的自啟動與電池限制**仍可能**讓重新綁定較困難。需靠 §4.1 的唯讀診斷確認後才能排除 |
| 「10-05 早上設定被系統清掉」 | **❌ 已排除** | 09:17→09:52 連續 35 分鐘事件不中斷，設定未被清 |

### 3.5 唯一可信的判定方式

```powershell
# S1 只代表「有列在設定裡」—— 會騙人
adb shell settings get secure enabled_accessibility_services

# S2 才代表「真的被綁定」—— 唯一可信
adb shell dumpsys accessibility | Select-String 'Bound services' -Context 0,4
```

`TOOLS/check_a11y_binding.ps1` 就是在區分這兩者。

## 4. 結論與出路

### 4.1 結論（**2026-10-05 重寫**）

| 問題 | 答案 | 依據 |
| --- | --- | --- |
| App 寫完了嗎？ | **是。** 程式、測試、建置、打包、安裝、UI、記錄、匯出都完成且驗證過。 | §2.1 |
| 核心功能（跳轉攔截 / 返回）驗證過了嗎？ | **✅ 是，已在實機成功。** redirect 19+12、block 9、return 成功 31、**失敗 0**。 | §2.2 實機 DB |
| 關閉鈕偵測驗證過了嗎？ | **✅ 是。** `CLOSE_DETECT` 4,814 筆，含真實廣告座標 `558x96`。 | §2.2 實機 DB |
| 關閉鈕**實際點擊**驗證過了嗎？ | **❌ 沒有。** 因為 `assist_action = NONE`，偵測到但不點。 | §2.4 |
| 為什麼現在服務不綁定？ | **很可能是測試工具自己的 `am start -S` 造成的**，不是 HyperOS 白名單。 | §3.2 |
| 這台手機（POCO X6 Pro / HyperOS）能不能跑？ | **能 —— 它跑過，累計 3 小時 37 分，而且成功攔截。** | §2.2 實機 DB |
| 唯一的真限制是什麼？ | **Canvas / SurfaceView 繪製的遊戲廣告偵測不到**（無節點樹可讀）。這是設計限制，非缺陷。 | §5.3 |
| 有出路嗎？ | **有，而且可能不需要換裝置。** 見 §4.2。 | |

### 4.2 出路（**順序很重要**）

**Step 1 — 修測試入口，不再 force-stop（最高優先）**
`DebugTestActivity` 補上 `onNewIntent()`，`auto_test.ps1` 移除 `-S`
（改用 `am start -n … -f 0x10008000`，NEW_TASK | CLEAR_TASK，讓 Activity
重建但不殺行程）。**任何腳本都不得再出現 `am force-stop` / `am start -S` /
`settings put secure enabled_accessibility_services`。**
這是解鎖驗證的關鍵 —— 在修好之前，任何「服務不綁定」的觀察都不算數。

**Step 2 — 一次乾淨的手動恢復**
1. 確認 App 的「自啟動」已開、電池設為「無限制」（設定 → 應用 → Reward Ad Guard）
2. 在系統設定 UI 裡關閉再開啟無障礙服務（**不要用 ADB**）
3. 用 `TOOLS/check_a11y_binding.ps1` 確認 **Bound**，隔 10 分鐘再確認一次

**Step 3 — 用修好的入口跑 `simulate_foreground`**
跑完再檢查一次 Bound 狀態，確認測試本身不會讓服務掉線。

**Step 4 — 修設定陷阱**
`assist_action = NONE` 與新 App 預設 `LOG_ONLY`（§2.4）是「App 好像沒作用」的
直接原因，實機 DB 已經證實。建議首次設定時明確詢問，或在首頁顯示紅色狀態。

**Step 5 — 若 Step 2 後仍不綁定，才換環境**
只有在「用手動 UI 切換也綁不上、且已排除 force-stop」時，才需要架 AVD
（原生 AOSP 一定綁定）。**注意 §3.3 的警告：修好入口前不要跑 AOSP 對照實驗。**

### 4.3 為什麼 `simulate_foreground` 有價值

```text
DebugTestActivity --es cmd simulate_foreground --es package <pkg>
   ↓ 建構一個真實的 AccessibilityEvent
RewardAdAccessibilityService.simulateForeground()
   ↓ 交給正式路徑
handleWindowEvent(event)      ← 這就是出貨的程式碼
```

只要服務被綁定，這條路就會通，而**被測的程式碼與正式版完全相同**。
換句話說：**在模擬器或實機上驗過的，就是真正出貨的邏輯。**


---

## 5. 本次調查修掉的缺陷

本次 session 總共找到並修好 **3 個真實缺陷**。三個都不是靠「看程式碼」發現的，
各自是靠不同手段才浮出來 —— 這一點值得記錄。

### 5.1 測試入口少了它唯一存在的功能（靠實機回報發現）

`DebugTestReceiver`（debug-only）實作了 `simulate_foreground`，
但**它無法從 ADB 驅動**（見 §6.1）。而 ADB 唯一能驅動的入口
`DebugTestActivity`，**根本沒有實作這個指令** —— 它回覆：

```
FAILED: unknown test command 'simulate_foreground'
```

**管線的兩端各有一半**：有實作的驅動不了，驅動得了的沒實作。
所以整個測試機制形同虛設，而它存在的**唯一理由**就是餵合成事件、省下每天一次的廣告。

**修正**：在 `DebugTestActivity` 實作 `simulate_foreground`，委派給既有的
`RewardAdAccessibilityService.simulateForeground`，並回報結果快照而非只回結束碼。
服務未綁定時現在會精準說明，而不是誤導人的「未知指令」。

### 5.2 `simulateForeground` 在 Android 8/9/10 會直接崩潰（靠 lint 發現）

```kotlin
AccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)   // 建構子只在 API 30+
```

Lint：`Call requires API level 30 (current min is 26) [NewApi]`。

但 `minSdk = 26` 是**硬性產品需求**。在 Android 8/9/10 上這會拋
`NoSuchMethodError` —— 合成事件路徑會直接死掉。

**修正**：改用 `AccessibilityEvent.obtain(...)`（**API 26 唯一可用形式**），
並搭配 `recycle()`（`obtain` 發放的是 pooled 實例）。兩者在較新 API 都已 deprecated，
因此加上帶說明的 `@Suppress("DEPRECATION")`。

> **重點**：`assembleDebug` **完全不會抱怨**這個問題。只有把 lint 跑起來才看得到。

### 5.3 狀態通知在 Android 13+ 被靜默丟棄（靠 lint + 文件比對發現）

`showStatusNotification()` 呼叫 `NotificationManager.notify()`，
但 manifest **完全沒有** `POST_NOTIFICATIONS`，也沒有任何地方請求它 ——
所以在 API 33+ 會被框架層直接丟棄。

**更糟的是文件說謊**：`DEVELOPMENT_REPORT.md` 的權限表列了這個權限，
manifest 的註解也宣稱「保持最小權限集，包含 `POST_NOTIFICATIONS`」——
**兩者都與實際 manifest 不符**。這是典型的「文件宣稱安全、程式碼並非如此」。

**修正**：宣告 `POST_NOTIFICATIONS`，並加上 `canPostNotifications()` 檢查，
讓跳過被**明確記錄原因**，而不是混在通用的 "status notification unavailable" 警告裡。

**已驗證**：權限確實進入 release manifest，且 release **仍然 0 個 debug hook**。



---

## 6. 調查過程中我犯的錯（必須揭露）

為了讓後續接手的人（或另一台電腦上的助理）不被誤導，這一節記錄本次調查中
**我自己做出的錯誤結論**。這些錯誤都已經被實測推翻。

| # | 我曾說過 | 事實 | 為什麼會錯 |
| --- | --- | --- | --- |
| 1 | 「測試入口已打通，`simulate_foreground` 可用」 | **最關鍵的那個指令從未被驗證過** | 我看到其他指令通了就推論全部通了。**下結論太早。** |
| 2 | 「設定會保留，HyperOS 只是不綁定」 | **設定是保留的，但服務被 force-stop 解除綁定** | 我把「設定被清掉」和「服務被解除綁定」混為一談（詳見 #5、#6）。 |
| 3 | 「我的 manifest 編輯沒有生效」 | **編輯是成功的** | `read_files` 與 `search_codebase` 回傳了**快取版本**，我被自己的工具騙了。 |
| 4 | 一度認為「服務有崩潰重啟」 | **從未連上過，`Crashed services` 是空的** | 把 `SERVICE_DESTROYED` 當成機制，其實它是症狀。 |
| **5** | **「核心路徑從未在實機觸發」** | **❌ 完全錯誤。實機 DB 顯示 redirect 31 / block 9 / return 成功 31 / 失敗 0** | **我沒有去查 `.git/devicedump/reward_ad_guard.db`。證據一直躺在專案裡，我卻只憑「現在服務沒綁定」就推論「從來沒綁定過」。** |
| **6** | **「HyperOS 平台白名單擋住，手機的限制」** | **❌ 很可能是我方測試工具的 `am start -S` 造成的** | **專案自己在 10-02 就記錄過這個症狀與成因（`am force-stop`），我卻採信了後來的「平台白名單」說法，沒有回頭比對自己的紀錄。** |

### 6.1 從這些錯誤學到的（已寫進專案規則）

1. **先查資料，再下結論。** #5 和 #6 是同一種錯：**手上已經有決定性證據，卻先寫了結論。**
   任何「從未發生過」的斷言，都必須先確認**沒有**留下紀錄可查。
2. **「現在不成立」不等於「從來不成立」。** 時間點的差異是兩件完全不同的事。
3. **看到重複的舊紀錄時，要當成線索而不是雜訊。** 10-02 的成因早就寫在報告裡，
   後來的結論卻和它相反 —— 這種矛盾本身就是最強的訊號。
4. **工具快取會騙人。** 懷疑檔案內容時，用 `Copy-Item` 複製成新檔名再讀，
   不要相信同一個路徑的第二次讀取。
5. **「3 秒後還在」不等於「會保留」。** 任何被系統托管的設定，都要**隔幾分鐘再讀一次**。
6. **不要用「其他都通了」推論「這個也通了」。** 沒被執行過的東西就是沒被驗證。
7. **shell integration 會偶發吞掉輸出。** 大量使用「重導向到 `.txt` 再讀取」的模式。

### 6.2 關於 `DebugTestReceiver` 為什麼驅動不了（三個疊加的原因）

這也花了很久才釐清，結論是**它已不是正確的工具**：

| 原因 | 現象 |
| --- | --- |
| 1. `exported="false"` | `am broadcast` 到達 `ActivityManager`，但**行程從未被啟動** |
| 2. `signature` 權限 | `com.android.shell` **不持有** App 私有的 signature 權限 |
| 3. **`am broadcast` 無法啟動已停止的行程** | 這是真正的阻擋。`result=0` 是 **shell 的假成功**，不是送達數量 |

**判斷關鍵**：日誌有 `Broadcasting:` 與 `Enqueued broadcast ... : 0`，
但**沒有 `FATAL`、沒有權限拒絕、`ps -A` 是空的** —— 廣播被接受後直接丟棄。

**改用 Activity + `am start -n` 就正常**，而且失敗時會**明確報錯**，不會靜默失敗。

---

## 7. 後續建議（依優先順序）

| 優先 | 項目 | 為什麼 |
| --- | --- | --- |
| ~~1~~ | ~~修好 `DEVELOPMENT_REPORT.md` 的內部矛盾~~ | ✅ **已完成（2026-10-05）**，修法見 §7.1 |
| **1** | **架 AVD 並跑 `simulate_foreground`** | 唯一能把核心功能推進到「已驗證」的路（§4.2） |
| **2** | **提交目前未提交的檔案變動** | `6d3aada` 之後的所有修改都還在 working tree |
| 3 | 補完 §2.3 的未驗證小項目 | 可在模擬器上一起做 |
| 4 | 考慮重新產生 `gradle-wrapper.jar` | repo 內可能缺 wrapper jar |
| 5 | 把 `DEVELOPMENT_REPORT.md` §10.2 與 §8.2 一起讀的註記，複製到 README | 目前這個關鍵脈絡只寫在報告裡 |

### 7.1 `DEVELOPMENT_REPORT.md` 的矛盾（**已修，2026-10-05**）

本節原本列出五項矛盾。**它們已經全部修掉了**，修法記錄如下 —— 保留這張表是為了
讓人知道「改過什麼、為什麼」，而不是留下一份已經不成立的問題清單。

| 原始問題 | 修法 |
| --- | --- |
| 表格寫 **122 tests**，§5 寫 **83 tests / 6 suites** | §5 兩個數字都改成 **122 tests / 11 suites**，並加註「權威數字在檔首表格；下方各節的舊數字是量測當天的真實值，讀起來較小是正常的」 |
| §5 內同一節出現 **99 / 83 / 74** 三個數字 | 指令區塊的註解改成 `122 tests, 11 suites`；其餘保留為歷史值並加註 |
| §8.2 說「**只**因為 MIUI 白名單」（單一機制），§9 說「**兩個**機制」 | §8.2 標題改為 `8.2 Corrected: … the cause is **two** mechanisms`，開頭加一個**引用區塊**列出兩個機制的證據表，並說明「§9 的舊結論被修正、但不是被推翻」 |
| §9 被插在 §8.2 與 §8.3 之間 → 章節錯亂（§9.3 → §8.2 → §8.3 → §9） | 那個 §9 其實是本該放在 §8 底下的追記，**重新編號為 §8.4**；三個缺陷子節改為 **§8.5 / §8.6 / §8.7**。現在 §8 內部是 8.1 → 8.7 的連續順序 |
| 第二個 `## 9. Launcher icon` 與第一個**標題完全相同** | 保留為 `## 9`，並在標題下加 `<Numbering note>` 說明；其子節改為 §9.5–§9.8（因為 §9.1–§9.4 這個編號已被那個**錯置的 §9** 用掉） |
| 兩個 `### 10.2 Full Traditional Chinese localisation` **標題完全一樣** | **這兩個不是重複段落，差點被我誤刪。** 其中一個含有 `settingsActivity` 缺陷修正、`check_a11y_binding.ps1` 工具、以及「改寫清單並不會清掉其他服務」的更正 —— 另一個沒有。兩者**都保留**，改編號為 **§10.3** 與 **§10.8**，並在 §10.3 加註說明為何不能合併 |
| §8 的限制表列了 `QUERY_ALL_PACKAGES` | 改成 `*(removed)*` 並註明 **App 從未宣告此權限**；同時該列的「HyperOS 綁定」說明改為指向 §8.2（修正版）與 §8.4 |
| §10.4 寫「`settings put` **沒有**關掉其他三個服務」 | 保留原文，並在 §10.2 補上註記：這與 §8.2 不衝突，因為 §8.2 描述的是**後來**的狀態 |

**另外補了一節新內容：`§10.2 Accessibility binding — first successful bind, then lost`。**

這節很重要，因為它補上了全篇最關鍵的一塊拼圖：**這個服務在這台手機上確實綁定過一次。**
`dumpsys accessibility` 當時顯示 `Service[label=Reward Ad Guard]` 帶著完整 event type 清單，
`Crashed services:{}`，而且首頁狀態自動從 `NOT CONNECTED` 翻成 `MONITORING`。

> 這代表：**App 沒問題，它在平台願意綁定的時候是會動的。**
> 如果只讀 §8.2，會誤以為這個 App 從來沒跑起來過。它跑過。
> 所以 §8.2 和 §10.2 必須**一起讀**。

### 7.2 這一輪修改的自我檢討

修文件時我差點犯下一個實質錯誤，值得記錄：

> **我原本打算把「重複的 §10.2」整個刪掉。**
> 動刪除前先比對內容，才發現那兩個區塊**不一樣** —— 其中一個裝著
> `settingsActivity` 缺陷的完整診斷與修正驗證，另一個沒有。
> 如果只看標題就刪，**會刪掉一份真實的缺陷記錄**。

這件事和 §6 的教訓是同一條：**標題看起來一樣，不代表內容一樣。**
差別在於這次的代價不會是「查錯方向」，而是「永久刪掉證據」。

---

## 8. 附錄：驗證指令速查

```powershell
# 這台手機能不能用？（S1 只代表「有列在設定裡」，S2 才代表「真的被綁定」）
adb shell settings get secure enabled_accessibility_services   # S1 — 會騙人
adb shell dumpsys accessibility | Select-String 'Bound services' -Context 0,4   # S2 — 唯一可信

# App 自己怎麼想？（應與 S2 一致）
# ⚠️ 絕對不要加 -S：-S 會 force-stop App，把無障礙服務一起解除綁定（§3.2）
$A = 'com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity'
adb shell am start -n $A -f 0x10008000 --es cmd dump_state
adb logcat -d -s RewardAdGuardDebug | Select-Object -Last 20

# 合成前景事件（服務有綁定才會成功）
adb shell am start -n $A -f 0x10008000 --es cmd simulate_foreground --es package com.example.fake.reward

# 唯讀診斷：確認 App 是否被 force-stop 過（看 USER_REQUESTED / force-stop 痕跡）
adb shell dumpsys activity exit-info com.rewardadguard.app

# 建置與測試（切勿加 --offline，單元測試需要連網）
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat" `
    :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks
```

> **禁用清單（任何腳本都不得出現）**
> `am force-stop` / `am start -S` / `settings put secure enabled_accessibility_services`
> —— 這三個都會讓無障礙服務解除綁定，製造出「服務永遠綁不上」的假象。

### 8.1 三個 log tag，永遠不要搞混

| Tag | 來源 |
| --- | --- |
| `RewardAdGuardDebug` | `DebugTestActivity`（測試入口） |
| `RewardAdGuardService` | `RewardAdAccessibilityService` |
| `RewardAdGuard` | debug receiver 與其他模組 |

混用會讓 `adb logcat -s` 看起來像空的。

### 8.2 一個會持續咬人的工具陷阱（**2026-10-05 更正**）

| 陷阱 | 後果 | 作法 |
| --- | --- | --- |
| ~~少了 `am start -S`~~ | ~~`onCreate` 不再執行~~ | **❌ 這個「作法」是錯的。** 正確解法是讓 `DebugTestActivity` 實作 `onNewIntent()`，然後**移除 `-S`**，改用 `-f 0x10008000`（NEW_TASK \| CLEAR_TASK）重建 Activity |
| `am start` 後立刻讀 logcat | 讀到**空的**（時序假象） | 等約 4 秒再讀 |

> **本節初版建議「一律加 `-S`」，那是整份報告最有害的一條建議。**
> `-S` 會 force-stop App、解除無障礙服務綁定，正是 §3.2 的根因。
> 它同時「解決」了 `onCreate` 不重跑的問題，**代價是把要測的東西關掉**。
> 正確解法在 §4.2 Step 1。

---

## 9. 相關文件

| 文件 | 內容 |
| --- | --- |
| `DEVELOPMENT_REPORT.md` | 完整設計理由與歷史。**內部矛盾已於 2026-10-05 修正**（見 §7.1）。**§8.2 與 §10.2 必須一起讀** |
| `TEST_REPORT.md` | 單元測試明細 |
| `CHANGELOG.md` | 變更歷史（簡版） |
| `.clinerules/known-issues.md` | 已修 bug、陷阱、裝置限制。**2026-10-05 已解決「listed and bound」與「never binds」的自我矛盾** |
| `.git/devicedump/reward_ad_guard.db` | **本次結論的決定性證據來源**（實機 DB，2026-10-05 09:56 拉出）。含 2 個真實 session 與 10,032 筆事件。**任何關於「有沒有跑過」的問題都應先查這裡** |
| `TOOLS/pull-db.ps1` | 從實機拉出 DB 與 `prefs.bin` 的工具 |
| `TOOLS/auto_test.md` | ADB 自動測試流程（中文） |
| `TOOLS/daily_ad_test.md` | 需要花廣告的人工測試步驟 |
| `TOOLS/check_a11y_binding.ps1` | 區分 `enabled` 與 `bound` 的檢查工具 —— **這台手機上唯一可信的判定** |

---

## 10. 本報告自身的修訂記錄

| 日期 | 修訂 |
| --- | --- |
| 2026-10-05 | 初版 |
| 2026-10-05 | **修正 `DEVELOPMENT_REPORT.md` 全部矛盾**（§7.1 詳列）。過程中發現一件重要事實：**服務曾被綁定過一次**，已補進 §1、§2.1，並在 `DEVELOPMENT_REPORT.md` 新增 §10.2 完整記錄 |
| 2026-10-05 | 補上 §7.2：修文件時差點誤刪一個「標題相同但內容不同」的段落 |
| **2026-10-05（二修）** | **重大更正。查閱 `.git/devicedump/reward_ad_guard.db` 後推翻兩個核心結論：**<br>① 「核心路徑從未在實機觸發」→ **錯**。實機 DB 有 2 個真實 session、redirect 31 / block 9 / return 成功 31 / 失敗 0（§2.2）<br>② 「HyperOS 永遠不綁定，是手機的限制」→ **很可能是 `am start -S` 自己造成的**（§3.2）<br>同時：更正裝置型號為 **POCO X6 Pro**、新增 §2.4 設定陷阱、重寫 §3 / §4、新增 §6 #5/#6 兩項錯誤、修正 §8.2 那條有害的 `-S` 建議 |

> **這份報告也認錯了兩次。**
> §7.2 那次是險些誤刪；**二修這次更嚴重：我把「現在不成立」當成
> 「從來不成立」，又把「自己造成的症狀」當成「手機的限制」。**
> 兩者都是同一種毛病 —— **結論跑在證據前面。**
> 這次的教訓已寫進 §6.1 第 1 條。

---

## 11. 最後一句

> **這個 App 沒有失敗。它跑過，而且成功攔截過真實廣告。**
>
> 實機資料庫記著：10-03 那天，它連續運作 3 小時 32 分、
> 攔下 31 次跳轉、9 次阻擋、31 次成功返回，**失敗 0 次**。
> 它讀到真實廣告的關閉鈕座標 `558x96`，並把它放大到 `1674x288`。
>
> 後來它「不綁定」了 —— **而最可能的原因，是我方測試工具每條指令都帶
> 的 `am start -S`，親手把它 force-stop 掉。**
>
> 所以在說「這個 App 在這台手機上不能用」之前，請先看兩件事：
> **① 它已經在這台手機上成功運作過；② 讓它停下來的原因可能就在我們自己的腳本裡。**
