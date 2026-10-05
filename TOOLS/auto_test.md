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

`DebugTestReceiver` 是一個**只存在於 debug build** 的測試入口，
讓 ADB 可以把「假的 App 切換」餵進守衛真正的程式碼路徑。

也就是說，**跳轉攔截、返回、關閉鈕這些邏輯，可以不用任何廣告就測完**。

```powershell
$A = 'com.rewardadguard.app.action.DEBUG_TEST'

# 看目前狀態
adb shell am broadcast -a $A --es cmd dump_state
adb logcat -d -s RewardAdGuard | Select-Object -Last 20

# 把某個套件設成「獎勵 App」，這樣不用真的裝遊戲
adb shell am broadcast -a $A --es cmd set_reward_app --es package com.example.fake.reward

# 模擬「切到該 App」→ 應該開啟 session
adb shell am broadcast -a $A --es cmd simulate_foreground --es package com.example.fake.reward

# 模擬「跳到別的 App」→ 應該觸發跳轉偵測
adb shell am broadcast -a $A --es cmd simulate_foreground --es package com.android.settings

# 模擬「切回來」→ 應該走返回流程
adb shell am broadcast -a $A --es cmd simulate_foreground --es package com.example.fake.reward

# 清掉測試用的假 App（測完務必執行）
adb shell am broadcast -a $A --es cmd clear_reward_apps
```

**安全設計**（為什麼這不會影響正式版）：
- 接收器只宣告在 `app/src/debug/AndroidManifest.xml` → **release APK 完全不含**。
- 程式碼內另有一道 `if (!BuildConfig.DEBUG) return`。
- 已實測驗證：debug manifest 有 `DebugTestReceiver`，release manifest 為 **0 筆**。
- 它不新增任何權限，只重送守衛本來就會收到的事件。

> ⚠️ **未經實機驗證的一點**：接收器設為 `exported="false"`。`adb shell am broadcast`
> 能否送到「未匯出」的接收器，依 Android 版本而異，**目前無法在無實機下確認**。
>
> 如果你在家跑 `auto_test.ps1` 時看到 **T 區塊顯示 SKIP**（而 APK 確實是 debug 版），
> 就是這個原因。解法二選一：
> 1. 把 `app/src/debug/AndroidManifest.xml` 的 `android:exported` 改成 `true`
>    （仍有 `BuildConfig.DEBUG` 保護、仍只存在 debug build）。
> 2. 改用 debug-only 的 Activity + `adb shell am start -n <pkg>/.service.DebugTestActivity`，
>    `am start` 對明確指定元件是保證可用的。
>
> 其餘區塊（E/I/S/F）完全不受影響，正常運作。

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
