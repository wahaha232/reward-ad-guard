# 遠端無人值守測試 - `TOOLS/remote_test.ps1`

## 一句話用法

```powershell
.\TOOLS\remote_test.ps1
```

啟動後就可以離開電腦。回來只看**一個檔案**：

```
TOOLS\remote_test_reports\run_<時間戳>\INDEX.txt
```

## 為什麼需要這支腳本

現有兩支測試腳本都是設計成「有人在旁邊看」的：

| 腳本 | 會寫報告檔嗎 | 沒裝置時 |
| --- | --- | --- |
| `auto_test.ps1` | **不會**，只印在 console 並回傳 exit code | 直接失敗 |
| `field_test.ps1` | 會寫 `reward_ad_guard_field_test_*.txt` | PHASE 0 就停住 |

兩者行為本身都沒錯，只是「人不在電腦前也能跑」這件事做不到。這支腳本只補這四點，不重做任何檢查：

1. **等裝置，不是立刻失敗。** 手機重開機、重新授權、喚醒中都是常態，而會去插拔線的人不在現場。最多等 `-WaitSeconds`（預設 600 秒），並區分 `no device` / `unauthorized`，因為這兩者要做的處置不同。

2. **跑完整套並全程留檔。** 輸出同時寫入 `console.txt`（即時）與每個步驟的 `*.stdout.txt` / `*.stderr.txt`，所以中途掛掉也還讀得到先前的輸出。

3. **單一步驟失敗絕不中止。** 失敗只記錄 exit code，繼續跑下一步。一份寫明「哪一步失敗」的殘缺報告，遠比被截斷的完整報告有用。

4. **只產出一份索引檔**（`INDEX.txt`），細節都收在同一个目錄裡。

**它不重新實作任何檢查**：所有結果都複製自子腳本，三者對「PASS」的定義不可能不同。

另外它會記錄 `git rev-parse HEAD` 與 APK 建置時間，因為field report 最浪費時間的一種，就是測到的建置根本不是討論中的那一個（見 `known-issues.md`，"The installed APK can be stale"）。

## 參數

| 參數 | 說明 |
| --- | --- |
| `-WaitSeconds` | 等裝置的上限秒數，預設 600。 |
| `-Serial` | 多台裝置時指定序號（`adb -s`）。 |
| `-Adb` | adb.exe 路徑；省略時自動尋找。 |
| `-OutDir` | 報告輸出目錄，預設 `TOOLS\remote_test_reports`。 |
| `-SkipInstall` | 傳給 `auto_test.ps1`，並讓 `field_test.ps1` 用 `-NoInstall`。 |
| `-Only` | `auto` / `field` / `both`（預設）。 |
| `-Apk` | 要安裝的 APK，預設 debug APK 路徑。 |

## 產出物

```
run_<時間戳>\
  INDEX.txt                     <- 只要回傳這個
  console.txt                   <- 完整主控台逐字紀錄
  auto_test.stdout.txt          <- 各步驟原始輸出
  auto_test.stderr.txt
  field_test.stdout.txt
  field_test.stderr.txt
  reward_ad_guard_field_test_*.txt   <- field_test 自己的 PASS/FAIL 表
  reward_ad_guard.db            <- 裝置上的資料庫（最主要證據）
```

`INDEX.txt` 裡有：git HEAD、APK 建置時間、每一步的 PASS/FAIL 與 exit code、以及要讀哪個檔案的指示。

**先讀 `reward_ad_guard_field_test_*.txt`**（那是逐項 PASS/FAIL 表）；`INDEX.txt` 只說哪一步跑了、結果如何。

## 離開前須確認

* 手機保持插著（或無線偵錯開著），且**螢幕不要鎖到深眠**。
* 無障礙服務已在「設定 → 無障礙 → Reward Ad Guard」開啟。這一項無法用 ADB 代做，見 `known-issues.md`。
* 手機上若跳出「允許 USB 偵錯」授權視窗，腳本會等，但需要有人按允許；無人值守時請先按過一次。

## 刻意的設計（請不要「順手改掉」）

* **不使用 `am force-stop`、不傳 `-S`。** 這會讓 AccessibilityManagerService 把服務從 `enabled_accessibility_services` 移除，製造出假的「never binds」症狀。詳見 `known-issues.md`。
* **不用 `settings put` 去開無障礙服務。** 開服務是使用者在 UI 的動作。
* **資料庫用 `exec-out` 以位元組抓取**，不是文字。文字模式會靜默損壞它。
* **`Invoke-ChildScript` 用 `System.Diagnostics.Process`，不是 `Start-Process -PassThru`。** 本機已量測：後者的 `ExitCode` 在 `WaitForExit` 回傳後仍讀到空值，會讓每一步都記錄成 "exit=" 而沒有數字，正好是本腳本要防止的無法驗證的報告。
* **`ConvertTo-ArgToken` 與 `field_test.ps1` 內的同名函式刻意一致。** 兩支腳本若引用規則不同，就會對「測的是哪個檔案」產生歧見。Windows PowerShell 5.1 的 `ProcessStartInfo` **沒有** `ArgumentList` 屬性，只能自己接字串，而本 repo 路徑含空白。
