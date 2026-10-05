# Auto test：手機 ↔ 電腦連線自動測試

給「在家用第二台電腦 + 手機」驗證 App 用的自動化流程。
不需要看廣告、不需要點清單，跑一條指令就知道環境有沒有問題。

> **為什麼需要這個？**
> 獎勵廣告**一天只有一次**。如果花掉當天唯一一次廣告，才發現是「服務根本沒綁定」
> 或「裝到舊 APK」，那次廣告就白費了。所以**先跑自動測試，確認環境全綠，再花廣告**。

---

## 前置：手機與電腦連線

1. 手機開啟 **開發人員選項 → USB 偵錯**（連點「版本號碼」7 次可開啟開發人員選項）。
2. USB 線接上電腦，手機跳出「允許 USB 偵錯嗎？」→ 勾選「一律允許」→ 確定。
3. 電腦端確認看得到手機：

```powershell
adb devices
# 應該看到：  <序號>    device
# 若顯示 unauthorized → 手機上重新確認授權對話框
# 若顯示 offline      → 換 USB 埠 / 換線，或 adb kill-server 後重試
```

> `adb` 找不到時：設定 `$env:ADB` 指向 `adb.exe` 完整路徑，
> 或在本專案 `local.properties` 設定 `sdk.dir`（`auto_test.ps1` 會自動去找）。

---

## 一、跑自動測試（主要指令）

```powershell
# 在 repo 根目錄
.\TOOLS\auto_test.ps1

# 已裝好、只想重新檢查環境（最快）
.\TOOLS\auto_test.ps1 -SkipInstall

# 清除事件記錄後再測，讓「事件有沒有寫進 DB」的判讀更乾淨
.\TOOLS\auto_test.ps1 -ClearLog

# 同時接多台裝置時指定序號
.\TOOLS\auto_test.ps1 -Serial 2311DRK48G
```

**結束碼**：`0` = 全部通過；`1` = 有 blocker（不要花廣告）；`2` = 找不到 adb。

### 它檢查什麼

| 區塊 | 檢查內容 | 需要廣告 |
|---|---|---|
| **E** 環境 | 裝置連線/授權、Android 版本 ≥ 8.0、開機完成 | 否 |
| **I** 安裝 | APK 安裝、套件存在、**實際安裝的版本號** | 否 |
| **S** 服務 | 是否列在啟用清單、**是否真的被系統綁定**、有無崩潰 | 否 |
| **F** 功能 | MainActivity 可啟動、DB 存在、事件有寫入 | 否 |
| **T** 合成測試 | 餵假的「前景切換」給守衛，驗證跳轉/返回邏輯 | 否 |

### 最重要的一個檢查：S2

`S1` 通過不代表服務真的在跑。**MIUI / HyperOS 有個陷阱**：
服務明明列在 `enabled_accessibility_services`（設定裡也打勾），
但系統就是沒有把它 bind 起來。

`S2` 用 `dumpsys activity services` 直接看服務有沒有真的在 process 裡跑，
所以**只有 S2 綠色才算是真的可用**。若 S2 紅燈但 S1 綠燈 → 把服務關掉再打開一次。

---

## 二、合成測試（T 區塊）：不用廣告就能測守衛

`DebugTestReceiver` 原本是**只存在於 debug build** 的測試入口。但**實機驗證後確認
它無法從 ADB 驅動**，因此現在改由 `DebugTestActivity` 擔任測試入口。

> ✅ **已於實機驗證（Redmi Note 13 Pro 5G / Android 16 / HyperOS V816）**
>
> `adb shell am broadcast` **無法**啟動一個沒在跑的 App 行程。日誌會出現
> `Broadcasting:` 與 `Enqueued broadcast ...`，`am` 也印出
> `Broadcast completed: result=0`，但**行程從未被啟動**（`ps -A` 是空的、沒有
> `FATAL`）。那個 `result=0` 是**假成功**。
>
> 把 receiver 改成 `exported="true"` **也沒用**；再加一個 `signature` 權限只是
> 多一個失敗原因（`com.android.shell` **不持有** App 私有的 signature 權限）。
>
> **改用 Activity + `am start -n` 就正常**：明確指定元件的 Activity 啟動保證可用，
> 而且失敗時會**明確報錯**（`SecurityException: ... not exported from uid`），
> 不會像廣播那樣靜默失敗。

```powershell
$A = 'com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity'
$L = 'RewardAdGuardDebug'   # 注意：不是 RewardAdGuard，也不是 RewardAdGuardService
$F = '0x10008000'           # NEW_TASK | CLEAR_TASK，見下方說明

# 看目前狀態
adb shell am start -n $A -f $F --es cmd dump_state
adb logcat -d -s $L | Select-Object -Last 20

# 看所有設定（assistAction=NONE 是靜默停用關閉鈕守衛的致命值）
adb shell am start -n $A -f $F --es cmd dump_settings
adb logcat -d -s $L | Select-Object -Last 20

# 修掉 assistAction=NONE
adb shell am start -n $A -f $F --es cmd set_assist_action --es value ASSIST_WHEN_IDLE

# 把某個套件設成「獎勵 App」，這樣不用真的裝遊戲
adb shell am start -n $A -f $F --es cmd set_reward_app --es package com.example.fake.reward

# 模擬「切到該 App」→ 應該開啟 session
adb shell am start -n $A -f $F --es cmd simulate_foreground --es package com.example.fake.reward

# 模擬「跳到別的 App」→ 應該觸發跳轉偵測
adb shell am start -n $A -f $F --es cmd simulate_foreground --es package com.android.settings

# 清掉測試用的假 App（測完務必執行）
adb shell am start -n $A -f $F --es cmd clear_reward_apps
```

> ⛔ **絕對不要加 `-S`。** 這一節的舊版本寫「一定要加 `-S`」，那是**本專案最糟的一條建議**。
>
> `-S` 的意思是「先 force-stop 目標 App」，而 force-stop 會讓
> `AccessibilityManagerService.onHandleForceStop` 把我們的無障礙服務從
> `enabled_accessibility_services` 裡**移除**。於是 `dumpsys` 看起來像
> 「有列在設定裡、卻永遠沒綁定」，**跟廠商白名單造成的症狀一模一樣**。
>
> 專案因此花了**三天**去追一個不存在的「HyperOS 平台限制」，並在報告裡寫下
> 「核心路徑從未在實機觸發過」。實際上服務在 10-02、10-03（兩次）、10-05 都綁過，
> 也攔到真實廣告（實機 DB 為證，見 `ANALYSIS_REPORT.md` §2.2）。
>
> 詳見 `ANALYSIS_REPORT.md` §3.2 與 `.clinerules/known-issues.md`。

> ℹ️ **那當初為什麼要加 `-S`？** 原因是真的：少了它，第二次之後的 `am start` 會落在
> 同一個已存在的 Activity 實例上，`onCreate` 不再執行，logcat 只會重播舊輸出。
> **但正確的解法不是在 App 外面 force-stop，而是在 App 裡面處理。**
> `DebugTestActivity` 現在實作了 `onNewIntent()`，所以重複的 `am start` 會正常重新執行指令；
> 再搭配 `-f 0x10008000`（`NEW_TASK | CLEAR_TASK`）讓 Activity 被重建、畫面回到前景，
> 同時**行程本身（連同已綁定的服務）完全不受影響**。

> ℹ️ `simulate_foreground` 需要無障礙服務**已連線**。服務沒開時它會回一段明確訊息：
> `FAILED: accessibility service is not bound, so no synthetic transition can be
> delivered.` 這不是 bug，請看下一節。

> ⛔ **已推翻（2026-10-05）：「本機的無障礙服務永遠不會被綁定」是錯的。**
> 這一節原本寫著「HyperOS 永遠不綁定、`Bound services` 永遠不含我們、要用 AOSP 或模擬器
> 才跑得動合成事件」—— **那是本專案自己的 `-S` 造成的，不是平台限制。**
> 實機 DB 證明服務在 10-02、10-03（兩次）、10-05 都綁定過，也攔到真實廣告。
> `Bound services` 之所以查不到，是因為**下指令前**服務剛被 `-S` force-stop 掉。
> 移除 `-S` 之後，合成事件應該就能在同一台手機上跑。
> 完整的證據鏈見 `ANALYSIS_REPORT.md` §3.2；該節也警告**不要**在修好工具前跑
> AOSP 對照實驗，否則會得到 FAIL/FAIL 而誤判專案失敗。

**安全設計**（為什麼這不會影響正式版）：
- 只宣告在 `app/src/debug/AndroidManifest.xml` → **release APK 完全不含**。
- 程式碼內另有一道 `if (!BuildConfig.DEBUG)` 檢查。
- 已實測驗證：debug manifest 有 `DebugTestReceiver`，release manifest 為 **0 筆**。
- 它不新增任何權限，只重送守衛本來就會收到的事件。

> `DebugTestReceiver` 仍然保留（同一組指令，可用於行程內呼叫），但 `auto_test.ps1`
> 已全部改用 Activity 入口。

---

## 三、跑完之後：才花當天那一次廣告

自動測試全綠後，**只挑一個**需要廣告的步驟（詳見 `TOOLS/daily_ad_test.md`）：

| 步驟 | 用途 | 需要廣告 |
|---|---|---|
| **D1** | 正常看廣告領獎勵，確認守衛**沒有誤擋真廣告** | ✅ 最該先做 |
| **D2** | 廣告中斷線/切背景的復原 | ✅ |
| **D5** | 返回流程 | ❌ 有無廣告替代（見 daily_ad_test.md） |
| **D6** | 返回流程（真廣告時序） | ✅ |
| **D7** | 關閉鈕輔助 | ❌ 有無廣告替代（見 daily_ad_test.md） |

**若沒有特定要驗證的修改 → 只跑 D1**（每單位廣告價值最高）。

---

## 四、常見問題排除

| 症狀 | 原因與解法 |
|---|---|
| `adb: no devices/emulators found` | USB 沒插好、偵錯沒開、或沒按手機上的授權 |
| 裝置顯示 `unauthorized` | 手機解鎖 → 重新接受「允許 USB 偵錯」 |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 簽章不同：`adb uninstall com.rewardadguard.app` 後重裝 |
| I3 版本號跟剛 build 的不同 | 裝到舊 APK 了，重新 `assembleDebug` 再跑腳本 |
| S1 綠、S2 紅 | MIUI/HyperOS 陷阱：設定裡把服務關掉再打開；並關閉電池優化 |
| T 區塊全部 SKIP | 這是 release build（正常）。測合成流程請裝 debug APK |
| `simulate_foreground` 回 `service is not bound` | HyperOS 不綁定第三方無障礙服務（見上）。改用 AOSP / 模擬器 |
| `enabled_accessibility_services` 讀不到我們 | 同上：系統會把設定改回去，且寫入與綁定無關 |
| `testDebugUnitTest` 失敗 | **不要加 `--offline`**，此任務需要連網抓相依 |

---

## 五、給（家裡那台）Cline 的快速上手

本檔案、`CHANGELOG.md`、`.clinerules/` 是專案知識的載體，Git 同步過來就有。
在新機器上依序做：

```powershell
git clone https://github.com/wahaha232/reward-ad-guard.git
cd reward-ad-guard

# 1) 單元測試（純 JVM，不需要手機；切勿加 --offline）
.\gradlew.bat testDebugUnitTest

# 2) 產生要裝到手機的 APK
.\gradlew.bat assembleDebug

# 3) 手機接好後，跑自動測試
.\TOOLS\auto_test.ps1
```

> 若 repo 內沒有 `gradlew.bat` 的 wrapper jar，改用本機 Gradle：
> `gradle assembleDebug`（本機已用 Gradle 8.7 + JDK 17 驗證通過）。
