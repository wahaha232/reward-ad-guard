# 實地測試工具 - `TOOLS/field_test.ps1`

給「人不在開發機旁邊、要帶著筆電去接手機跑測試」用的**單一檔案**腳本。
沒有相依任何其他 repo 腳本，可以直接複製到 USB 隨身碟、在別的電腦上執行。

---

## 一句話用法

```powershell
powershell -ExecutionPolicy Bypass -File TOOLS\field_test.ps1
```

執行完會在腳本旁邊產生一份報告：

```
TOOLS\reward_ad_guard_field_test_20261002_153000.txt
```

**把那個 `.txt` 整份回傳即可**，它已經包含裝置資訊、每一項結果、原始指令輸出與 logcat。

---

## 為什麼需要這支腳本

真正需要 rewarded ad 的行為一天只能測一次（廣告一天重置一次）。
這支腳本負責的是**不需要花掉那一次廣告**的所有檢查：

* 手機連線與 USB 偵錯是否真的可用
* app 是否安裝、裝的是哪一版
* 無障礙服務是否被系統**綁定**（不只是「清單裡有」）
* 是否能對真正的 guard 送出合成前景事件（`simulate_foreground`）
* 設定寫入是否能正確回讀
* 合理化情境是否**沒有**誤擋（over-blocking）

全部通過之後，才值得去花那一天一次的廣告做 D1/D2/D5/D6。

---

## 參數

| 參數 | 說明 |
| --- | --- |
| `-Adb <path>` | 指定 `adb.exe`。不給會自動搜尋（PATH、`$env:ADB`、`local.properties`、`ANDROID_HOME`、`ANDROID_SDK_ROOT`、`%LOCALAPPDATA%`）。 |
| `-Serial <serial>` | 同時接多支手機時指定目標。**多支裝置又沒指定時腳本會直接 FAIL**，不會亂猜。 |
| `-Install` | 沒安裝才安裝 APK。 |
| `-Reinstall` | 強制 `adb install -r`（**保留 app 資料**，不會刪掉事件紀錄）。 |
| `-ApkPath <path>` | 指定 APK。預設找 `app\build\outputs\apk\debug\*.apk`。 |
| `-NoInstall` | 完全不碰 APK。 |
| `-ReportDir <dir>` | 報告輸出位置，預設是腳本所在目錄。**路徑不合用時不會中斷**：會警告並自動改用腳本目錄／TEMP，並印出實際寫入位置。 |
| `-SkipLogcat` | 不抓 logcat（報告較小、細節較少）。 |

---

## 報告寫不出來時

報告寫入是最後一步，前面所有測試結果都已經在記憶體裡，**這一步失敗等於整趟白跑**，所以它做過防禦：

- 路徑不合法（含 Windows 不允許的字元）、不存在、或指向檔案時，會印出警告並自動退回腳本目錄／TEMP，然後**印出真正寫入的完整路徑**。
- 連退回位置都寫不進去時，會直接印 `NO REPORT FILE WAS PRODUCED`，不會安靜結束。

看到報告沒出現，請**不要直接關視窗**，把畫面上的最後幾行（或整份 console 內容）一起回傳。

> 這個防禦是因為真的踩過：舊版 `執行.bat` 用 `-ReportDir "%KIT%"`，而 `%KIT%` 來自 `%~dp0`、結尾自帶反斜線，反斜線把結尾引號跳脫掉，PowerShell 收到尾端多一個 `"` 的路徑 → `Test-Path` 報錯 → `WriteAllText` 拋例外 → 報告全毀。
>
> 注意 `Test-Path` 在預設 `ErrorActionPreference = 'Continue'` 下是**非終止性錯誤**：它印紅字、回傳 `$false`、繼續往下跑，所以單純用 `try/catch` 包住**完全沒用**。現在改用 `Test-UsableDir`（`-ErrorAction Stop` + `-PathType Container`）明確驗證。

---

## 測試項目

### 階段 0 - 環境與 USB 偵錯（**沒過就不會往下跑**）

| ID | 項目 | 說明 |
| --- | --- | --- |
| P1 | 找得到 adb | 找不到會直接告訴你去裝什麼。 |
| P2 | USB 偵錯已開啟且已授權 | 會分辨三種完全不同的失敗：`unauthorized`（沒按手機上的允許）、`offline`（線材／hub／別的 adb 佔用）、完全沒有裝置（偵錯沒開或線只充電）。**每種都給不同的處理步驟**。 |

> 這一項就是需求裡的「必須確認手機 USB 偵錯模式已開啟才能進行測試」。
> 未通過時**不會**執行任何測試 - 否則失敗會被誤記成 app 的問題。

### 階段 1 - 應用程式與無障礙服務

| ID | 項目 |
| --- | --- |
| I1 | app 已安裝（含版本號） |
| I2 | APK 安裝（依參數決定，或 SKIP） |
| A1 | 服務已列在 `enabled_accessibility_services` |

> A1 是**唯讀**的。啟用服務一定要使用者在手機上自己開 - 用
> `settings put secure enabled_accessibility_services` 假造出來的狀態「看起來有開、
> 其實沒有」，本專案就是這樣白花了三天。

### 階段 2 - 唯讀證據（先記錄，再動任何東西）

| ID | 項目 | 為什麼要記 |
| --- | --- | --- |
| E1 | `dumpsys accessibility` 裡有這個 app | 「清單裡有」不等於「真的被綁定」 |
| E2 | app 行程活著 | 行程死了就不可能有綁定 |
| E3 | `dumpsys activity exit-info` 沒有 force-stop 紀錄 | **推翻舊的錯誤理論的關鍵證據** |
| E4 | 是否在電池最佳化白名單 | 廠商省電凍結是真的會解綁，先記錄下來免得事後各說各話 |

### 階段 3 - Guard 行為（不消耗廣告）

| ID | 項目 |
| --- | --- |
| T0 | `dump_settings` 有回應（測試入口可用） |
| T1 | `assist_action` **不是** `NONE` |
| T2 | **無障礙服務已被綁定且可驅動** ← 最重要的一項 |
| T3 | 合成前景有產生事件 |
| T4 | 設定寫入能持久化並正確回讀 |
| T5 | 不會誤擋無關的 app |

### 階段 4 - 還原

| ID | 項目 |
| --- | --- |
| R1 | 移除測試用的假 reward app |
| R2 | `assist_action` 還原成測試前的值 |
| R3 | 前後設定一致 |

### 階段 5 - logcat

| ID | 項目 |
| --- | --- |
| L1 | logcat 沒有 crash / ANR |

---

## 報告長怎樣

```
==============================================================================
  REWARD AD GUARD - FIELD TEST REPORT
  Send this whole file back. It is plain text and self-contained.
==============================================================================

  Generated      : 2026-10-02 15:30:00
  Computer       : DESKTOP-XXXX
  PowerShell     : 5.1.22621.4391
  Verdict        : PASSED WITH WARNINGS
  Totals         : PASS 12   FAIL 0   WARN 2   SKIP 1

  Device
    Model    Redmi Note 13 Pro 5G
    Brand    Redmi
    Android  16
    ...
------------------------------------------------------------------------------
  RESULTS
------------------------------------------------------------------------------
  [P1] PASS adb.exe found
  ...
------------------------------------------------------------------------------
  WHAT TO DO NEXT
------------------------------------------------------------------------------
  ...
==============================================================================
  RAW EVIDENCE
==============================================================================
---------------- activity exit-info ----------------
...
==============================================================================
  FULL CONSOLE LOG
==============================================================================
```

Verdict 有三種：`PASSED`、`PASSED WITH WARNINGS`、`FAILED`。
有 FAIL 時結束碼為 `1`，其餘為 `0`（CI 可用）。

---

## 刻意的設計限制（請不要「順手改掉」）

1. **絕不使用 `am force-stop`，`am start` 也絕不加 `-S`。**
   兩者都會讓 AOSP `AccessibilityManagerService.onHandleForceStop` 把服務從
   `enabled_accessibility_services` 移除，產生「已啟用但未綁定」的假象 -
   這正是那個錯誤的「HyperOS 從不綁定」結論的來源。詳見 `ANALYSIS_REPORT.md` §3.2。

2. **絕不用 `settings put` 寫 `enabled_accessibility_services`。**
   啟用服務是使用者行為，腳本不得代勞。

3. **不安裝時會保留 app 資料**（`adb install -r`，不加 `-d`）。
   手機上的事件資料庫是證據，破壞性安裝會把它刪掉。

4. **讀 RESULT 行不靠固定 sleep。**
   `am start` 前先抓一次基準行數，之後輪詢到 logcat 長出新行（上限 15 秒），
   再對基準取差集。固定 sleep 會跟指令賽跑：行程若需要冷啟動，
   `RESULT` 可能在 sleep 結束之後才落盤，指令就會被誤報成「沒有 RESULT 行」。
   差值法同時免疫「讀到上一道指令輸出」的時序陷阱。

5. **讀 logcat 用的是 `RewardAdGuard` tag。**
   專案裡有三個 tag，用錯會讓 `logcat -s` 看起來像空的：
   `RewardAdGuardDebug`（測試 Activity）、`RewardAdGuardService`（無障礙服務）、
   `RewardAdGuard`（receiver 與其他模組）。

---

## 除錯

**報告沒產生？**
腳本即使前置檢查失敗也會寫報告。若連報告都沒有，通常是 PowerShell 執行原則擋掉，
改用 `powershell -ExecutionPolicy Bypass -File ...`。

**T2 FAIL（服務沒被綁定）但 A1 PASS（清單裡有）**
依序檢查：(a) 在 app 關閉狀態下把服務關掉再打開；(b) 廠商的
autostart／允許清單設定；(c) 手機上裝的是不是 release APK（release 會拒絕測試指令）。
**不要**直接跳到「這台 ROM 不支援」- 那條路已經走過而且是錯的。

> **實機結果（2026-10-06，POCO X6 Pro / `2311DRK48G` / Android 16 / HyperOS）：
> T2 PASS。** 這台手機**確實會綁定**服務，`simulate_foreground` 也真的送得進去。
> 所以「這台 ROM 不綁定」是**錯的結論**，別再重新推導一次。

**T4 FAIL（`unknown assist action 'CLICK'`）**
這是腳本自己的 bug，不是 app 的。可用的值只有
`NONE` / `ASSIST_CLICK` / `ASSIST_WHEN_IDLE` / `ASSIST_TIMED_RETRY`。

**T0 FAIL（沒有 RESULT 行）**
先確認「手機上裝的是 debug 版」：`DebugTestActivity` 會檢查
`BuildConfig.DEBUG` 並直接 `finish()`。用 `gradlew.bat :app:assembleDebug`
重新編譯，再以 `-Reinstall` 執行。
但**先別急著重編** — 這個症狀也完全可能來自讀 logcat 的參數：這台手機上
`adb logcat -d -t 400 -s RewardAdGuardDebug` 會**靜默地什麼都不輸出**（exit code 0），
而 `adb logcat -d -s RewardAdGuardDebug` 同一秒讀得到。
判定方法是在手機上直接跑這一行，看得到 `RESULT` 就代表 app 沒問題：
```powershell
adb logcat -d -s RewardAdGuardDebug | Select-Object -Last 5
```
