# DEVELOPMENT REPORT — Reward Ad Guard

| Item | Value |
| --- | --- |
| App ID | `com.rewardadguard.app` |
| Language | Kotlin 1.9 (`jvmTarget` 17) + Jetpack Compose (Material 3) |
| Build | Gradle 8.7, Android Gradle Plugin 8.x, `compileSdk 36`, `minSdk 26` |
| Root required | **No** — non-root, relies on `AccessibilityService` only |
| APK | `app/build/outputs/apk/debug/reward-ad-guard-debug.apk` (9.8 MB) |
| UI language | Traditional Chinese by default, English via `values-en` |
| Unit tests | 83 passing, 6 suites (`testDebugUnitTest`) |

## 1. What the app does

Reward Ad Guard watches a user-selected list of *reward apps* (apps that pay out
for watching ads) and, while one of them is in the foreground:

1. **Detects** that an external app / browser / store jumped to the foreground.
2. **Classifies** the destination (`BROWSER`, `STORE`, `GAME`, `DEEP_LINK`,
   `EXTERNAL_APP`) — it is explicitly *not* a blacklist, it never bans a named
   app such as Shopee or Google Play.
3. **Scores** the jump with a deterministic risk engine and either observes it,
   logs it, or blocks it by bringing the user back to the reward app.
4. **Assists** the ad `[X]` close control by finding the best-matching node in the
   accessibility tree and exposing an expanded click target.
5. **Logs** every decision (events + session summaries) with export to a file.

## 2. Architecture

```
app/src/main/java/com/rewardadguard/app/
├── RewardAdGuardApp.kt            Application: builds the DI container once
├── data/                          Room entities, DAO, EventType, AppSettings
│   ├── AppSettings.kt             DataStore-backed user settings
│   ├── EventModels.kt             EventRecord / SessionRecord / EventType
│   ├── RewardAdGuardDao.kt
│   └── RewardAdGuardDatabase.kt
├── detector/
│   ├── PackageClassifier.kt       Destination classification (needs Context)
│   └── CloseButtonDetector.kt     PURE heuristics for the ad [X]
├── guard/
│   └── SmartRedirectEngine.kt     PURE risk scoring + policy decisions
├── session/
│   ├── SessionState.kt            State machine + end reasons
│   ├── SessionIdFactory.kt        SESSION_yyyyMMdd_HHmmss_NNN
│   └── SessionManager.kt          Session lifecycle, counters, logging
├── service/
│   ├── RewardAdAccessibilityService.kt   The monitoring engine
│   ├── LogExportReceiver.kt              Export from a notification/QS tile
│   └── ...                        Foreground service + notifications
├── manager/
│   ├── SettingsRepository.kt      Typed accessors over DataStore
│   ├── EventLogger.kt             Event capture + Room persistence
│   ├── LogExporter.kt             CSV / JSON / TXT export to cacheDir/exports
│   └── RewardAppsRepository.kt    Selected reward-app list (Room)
└── ui/
    ├── MainActivity.kt            Single-activity Compose host
    ├── MainViewModel.kt           State + settings + stats + export
    ├── SettingsScreen.kt / LogScreen.kt / HomeScreen.kt
    └── Format.kt                  Shared formatters
```

### 2.1 Design rule: pure logic is separated from Android

The two components that carry real decision risk are deliberately free of
Android APIs so they can be unit tested on the JVM:

* `SmartRedirectEngine` — takes a `RedirectContext` + `RedirectPolicyConfig`,
  returns a `RedirectDecision`. No `Context`, no `Log`, no coroutines.
* `CloseButtonDetector` — takes a list of `NodeSnapshot` (a plain data class
  capturing text, content description, view id, class, clickable flag and
  bounds) and returns a `CloseMatch`.

The accessibility service is the only place that touches
`AccessibilityNodeInfo`, and its job is to *convert* the live tree into
`NodeSnapshot` values, then delegate. This is what makes the heuristics
reviewable and testable instead of being buried in a 600-line service.

## 3. Behaviour detail

### 3.1 Session lifecycle

`SessionManager` owns a single current session. A session id is minted with
`SessionIdFactory` in the documented format `SESSION_20261002_093015_001`
(a monotonic 3-digit suffix keeps ids unique inside the same second).

State machine (spec § 13):

```
IDLE → REWARD_APP_ACTIVE → AD_SESSION_ACTIVE → EXTERNAL_APP_DETECTED
     → BLOCKING → RETURNING → REWARD_APP_ACTIVE → COMPLETED → IDLE
                                                        ↘ ERROR
```

`isActive()` is true for every state except `IDLE`, `COMPLETED` and `ERROR`.
Ad sessions are never claimed as fact: a plain window-change burst raises
`POSSIBLE_AD_SESSION` with `result = ATTEMPTED` and the message explicitly says
`heuristic only, not a confirmed ad`. A stronger signal (a detected `[X]`)
raises `AD_SESSION_ACTIVE`. On close, `endSession()` emits `SESSION_END` +
`SESSION_SUMMARY` (redirects, blocked, returns, closeDetect, risk) and persists
a `SessionRecord`.

### 3.2 Redirect risk engine

Base risk per destination class:

| Destination | Base risk |
| --- | --- |
| `SELF`, `UNKNOWN` | 0 |
| `STORE`, `BROWSER` | 1 |
| `GAME`, `EXTERNAL_APP` | 2 |
| `DEEP_LINK` | 3 |

Raise: `redirectCountInWindow >= 2` (+1), `>= 3` (+2), never returned (+1),
ad session active (+1).
Lower: a close control was seen (-1), the user really tapped and this is the
first hop (-1), the user already returned twice (-1).
The result is clamped to `0..9`; `LOW_RISK = 2`, `MEDIUM_RISK = 4`,
`MAX_RISK = 9`.

Policies (`RedirectPolicy`):

* `LOG_ONLY` — record only, never block.
* `BLOCK_AFTER_GRACE` — wait out `blockGraceMillis`; a transient bounce is only
  observed, a persisted one is blocked.
* `BLOCK_IMMEDIATELY` — block, **except** a first low-risk hop right after a real
  tap, which stays observational so the legitimate
  `Store → Back → Ad → X` flow (spec test 55) still completes.

When *Smart Redirect* is switched off, the engine degrades to the raw policy
(`STRICT_POLICY_IMMEDIATE` / `STRICT_POLICY_GRACE_ELAPSED` / `GRACE_PERIOD`).

### 3.3 Ad `[X]` assistance

`CloseButtonDetector.score()` accumulates evidence:

| Signal | Score |
| --- | --- |
| exact glyph `x` / `×` / `✕` in text or description | +60 |
| exact keyword (`Close`, `Skip`, `Dismiss`, `關閉`, `跳過`, …) | +55 |
| weak token inside a longer string (`"Tap to close the ad"`) | +25 |
| view-id hint (`ad_close`, `iv_close`, `btn_close`, `ad_skip`, …) | +40 |
| corner proximity (horizontal edge +12, vertical edge +8, small +10) | +0..30 |
| class sanity (`ImageButton` / `ImageView` / `Button` / …) | +5 |
| huge clickable area (`> screen/6`) — that is a CTA, not the `[X]` | -25 |

A node is a match at `MATCH_THRESHOLD = 45`. Invisible nodes short-circuit to
`INVISIBLE`, zero-size nodes to `ZERO_BOUNDS`, and a rejected node reports
`REJECTED_SCORE_<n>` so the log shows *why* nothing was clicked.

The area penalty is the important false-positive guard: a full-screen
`"Close"` call-to-action (1080×2400) scores 55 + 30 + 5 - 25 = 65 for the
keyword path but is demoted below threshold by the penalty, so the guard can
never "click the whole ad".

`expandedBounds()` computes a larger click target around the node centre
(default `scale = 3`, clamped to `1..5`, coordinates clamped to `>= 0`). The app
does **not** resize another app's view — it only uses the expanded rect for an
assisted accessibility action, which is the only thing possible without root.

### 3.4 Logging and export

* `EventType` covers the session, redirect, block, return, close-detection and
  error paths. `EventRecord.eventType` is stored as a `String`; the UI resolves
  it back through `EventType.entries.firstOrNull { it.name == event.eventType }`.
* `EventLogger` is constructed exactly once in `RewardAdGuardApp` (after
  `AppManager`) as `EventLogger(this, { MonitoringState.settings.value }, appManager::appLabel)`,
  so it always reads live settings and live app labels.
* `LogExporter` writes CSV / JSON / TXT into `cacheDir/exports` and shares the
  file through `FileProvider` with authority
  `${applicationId}.fileprovider` (`res/xml/file_paths.xml`).
* `LogExportReceiver` allows triggering an export without opening the UI.

## 4. Permissions and safety

| Permission | Why |
| --- | --- |
| `BIND_ACCESSIBILITY_SERVICE` | the only way to see the window tree and act on it without root |
| `POST_NOTIFICATIONS` | foreground-service / status notifications on Android 13+ |
| `FOREGROUND_SERVICE` (+ `_SPECIAL_USE`) | keep the guard alive while a reward app runs |
| `QUERY_ALL_PACKAGES` | resolve launcher / browser / store handlers for classification |

Safety properties that are enforced in code:

* **No blacklist.** Nothing bans a specific vendor app. Only *classes* of
  destination are described, and the block decision comes from the session
  context (which app did the jumping, how often, did the user return).
* **No root, no input injection, no view resizing.** "Blocking" means bringing
  the user back to the reward app, and `[X]` assistance means expanding the
  suggested tap rect. Neither modifies another app's layout.
* **Opt-in per app.** Only packages the user selected in *Reward apps* are
  monitored; anything else is ignored before the risk engine is reached.
* **Honest logging.** A window-change burst is logged as `POSSIBLE_AD_SESSION`
  with `result = ATTEMPTED` and an explicit "heuristic only, not a confirmed ad"
  message. Ad detection is never asserted as certain.
* **Defensive parsing.** `EventType` is persisted as a string and resolved with
  `firstOrNull`, so an unknown value from a future version cannot crash the UI.

## 5. Build and verification

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
$gradle = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat"
$proj = "d:\Visual Studio Code\PROJECT\Reward Ad Guard"

& $gradle -p $proj compileDebugKotlin                    # BUILD SUCCESSFUL
& $gradle -p $proj testDebugUnitTest                     # 99 tests, 0 failures
& $gradle -p $proj assembleDebug                         # produces the APK
```

**Do not add `--offline` to `testDebugUnitTest`.** The unit tests use
`org.robolectric:robolectric:4.14.1` and Robolectric downloads its own Android
runtime jars on first use from outside the Gradle cache, so offline mode fails with
`No cached version of org.robolectric:robolectric:4.14.1 available for offline mode`
even after the Gradle dependency itself has been resolved once. `compileDebugKotlin`
and `assembleDebug` are still fine offline.

Artifact: `app/build/outputs/apk/debug/reward-ad-guard-debug.apk` — **9.71 MB**.

Unit-test details are in `TEST_REPORT.md`; on-device verification steps are in
`TOOLS/device_test_checklist.ps1`.

Verified result: 6 suites, **83 tests, 0 failures, 0 errors, 0 skipped**
(`clean assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL`).

> Build-size note: run `clean` before quoting an APK size. An `assembleDebug`
> on top of an existing (already-`lint`ed) tree produced a 10.12 MB file; the
> clean build of the identical sources is 9.71 MB.

```powershell
.\TOOLS\device_test_checklist.ps1                 # interactive, adb-driven
.\TOOLS\device_test_checklist.ps1 -Preflight      # environment check only, exits 1 if not ready
.\TOOLS\device_test_checklist.ps1 -NonInteractive # print only
```

### 5.1 Installing on the verification handset (Xiaomi / HyperOS)

The reference handset is a **2311DRK48G (Redmi `duchamp`), Android 16 / API 36,
HyperOS 3 (`OS3.0.3.0.WNLTWXM`)**. On that ROM `adb install` fails with:

```
Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]
```

even though USB debugging is on and the RSA prompt was accepted. Every adb-side
route was ruled out:

| Probe | Result |
| --- | --- |
| `settings put global verifier_verify_adb_installs 0` | no effect (already `0`) |
| `settings put global package_verifier_enable 0` | no effect (already `0`) |
| `pm install -r <pushed apk>` | `INSTALL_FAILED_USER_RESTRICTED` |
| `pm install -r -i com.miui.packageinstaller --install-location 0` | same |
| `pm install -r -i com.android.vending` / `--skip-verification` / `--user 0` / `--user 999` | same |
| `cmd package install -r -i com.miui.packageinstaller` | same |
| `adb install --no-streaming` (push install) | same |
| `pm install-create` + staged session | session creates, install still refused |
| `dumpsys user` restrictions, `device_policy` | none set - not a device-owner or restriction issue |
| "Install via USB" toggle in Developer options | verified **ON**, still refused |

Two running users were present (`0` primary, `999` XSpace); both were rejected.

The root cause is a MIUI signature-level permission, not a normal user toggle.
It was found by launching the MIUI installer directly, which throws:

```
java.lang.SecurityException: Permission Denial: starting Intent {
  act=android.intent.action.VIEW dat=file:///... typ=application/vnd.android.package-archive
  cmp=com.miui.global.packageinstaller/.activity.ScanActivity }
  from null (pid=24883, uid=2000)
  requires com.miui.securitycenter.permission.GLOBAL_PACKAGEINSTALLER
```

and the permission itself is declared as:

```
Permission [com.miui.securitycenter.permission.GLOBAL_PACKAGEINSTALLER]:
  com.miui.securitycenter.permission.GLOBAL_PACKAGEINSTALLER: prot=signature
```

`prot=signature` means only `com.miui.securitycenter` (same signing key) can ever
hold it, so **no adb path can install on this ROM**.

The only remaining path is the **device-side UI**: *Files > Download >
reward-ad-guard-debug.apk* and confirm the install prompt. The MIUI installer
runs as `com.miui.securitycenter` in that flow, so the signature check passes.
Reaching it over adb is not possible: `am start` with a `file://` URI only opens
a chooser dialog (RS 檔案管理器 / Termux), and Android 16 no longer resolves
`INSTALL_PACKAGE` with a `file://` URI.

**Resolved (2026-10-02):** after the handset owner accepted the MIUI prompt once,
`adb install -r` succeeded and has kept working ever since:

```
Performing Streamed Install
Success
package:com.rewardadguard.app  versionName=1.0.0 minSdk=26 targetSdk=36
```

So the signature permission is enforced only while the MIUI installer has not
yet been "primed" for USB installs by the user; once the user allows it once,
adb regains the ability to install. The practical rule for this ROM: run the
first install from the device UI, then iterate over adb.

> **Defect found by this first launch.** The very first install crashed on
> `ClassNotFoundException: com.rewardadguard.app.MainActivity`. `AndroidManifest.xml`
> declared `android:name=".MainActivity"`, which resolves against the application
> id `com.rewardadguard.app`, but `MainActivity.kt` declares
> `package com.rewardadguard.app.ui`. The launcher entry therefore pointed at a
> class that does not exist. Fixed to `.ui.MainActivity`; the other three
> components (`RewardAdGuardApp`, `.service.RewardAdAccessibilityService`,
> `.service.LogExportReceiver`) were cross-checked against their `package`
> lines and were already correct. This is a reminder that a manifest class name
> is never validated at build time — only at component instantiation.

The script contains **28 steps** across 6 groups:

| Group | Steps | Covers |
| --- | --- | --- |
| A Installation | 2 | install / upgrade, first launch |
| B Permissions | 3 | notification permission, accessibility binding, service survival |
| C Configuration | 3 | reward-app selection, setting persistence, unselected apps ignored |
| D Core flow | 7 | normal ad (spec test 55), Store→Back→Ad→X, redirect detect/block, log-only, `[X]` assist, CTA false positive |
| E Logging | 7 | log screen, session summary, per-app stats, CSV/JSON/TXT export, out-of-UI export, Room persistence |
| F Robustness | 6 | heavy switching, mid-session disable, permission revoke, service off, rotation/split screen, ANR watch |

## 6. Device test results (2026-10-02, 2311DRK48G / HyperOS 3)

First physical-device run. Groups A–E were exercised; every result below is
backed by a screenshot, a `dumpsys` dump or an exported log file.

| Group | Step | Result | Evidence |
| --- | --- | --- | --- |
| A | Install via `adb install -r` | **PASS** | `Performing Streamed Install / Success`, `versionName=1.0.0` |
| A | First launch | **FAIL -> fixed** | `ClassNotFoundException: com.rewardadguard.app.MainActivity` (§5.1) |
| A | Relaunch after manifest fix | **PASS** | `LaunchState: COLD`, `mCurrentFocus=…ui.MainActivity`, no FATAL |
| B | Dashboard renders | **PASS** | all 4 tabs, status chip, counters drawn |
| B | Accessibility service binds | **PASS** | `Service[label=Reward Ad Guard … eventTypes=[…], notificationTimeout=120]`, `Crashed services:{}` |
| B | Dashboard reflects binding | **PASS** | chip `NOT CONNECTED`/disabled -> `MONITORING`/enabled |
| C | Launcher app enumeration | **PASS** | `com.m104`, `holdingtop.app1111`, `com.scores365` … listed with `Add` |
| C | Add reward app | **PASS** | `104工作快找 / com.m104` appears in *Monitored reward apps* |
| C | New-app default mode | **PASS** | new entry starts in `Log only` (New App Safe Mode = ON) |
| C | Remove reward app | **PASS** | list returns to `No reward app added yet.` |
| C | Mode override `Log only -> Block` | **PASS** | `Block` chip becomes the highlighted one |
| C | Settings persistence | **PASS** | after `am force-stop` + relaunch the entry, toggle and `Block` mode survive |
| E | Log screen renders | **PASS** | 9 statistic cards, Filter chips `All/Redirects/Blocks/Close` |
| E | Export dialog | **PASS** | offers `Txt` / `Csv` / `Json` |
| E | TXT export | **PASS** | `cache/exports/reward_ad_guard_txt_20261002_131731.txt`, 9 060 bytes |
| E | Export content | **PASS** | header `# events=62 sessions=0`, 62 timestamped event rows |
| E | Share sheet | **PASS** | `mCurrentFocus=com.android.intentresolver/…ChooserActivity` |
| E | Event pipeline live | **PASS** | 31 `SERVICE_CONNECTED` / `SERVICE_DISCONNECTED` pairs written by the service |

Not yet exercised on hardware (still needs a reward app that actually serves
ads): the redirect block itself, the `[X]` close assist, the `Store -> Back ->
Ad -> X` flow, and group F robustness. The log already proves the service
receives window/click/content-change events and writes them to Room, which is
the precondition for those checks.

### 6.1 Two tooling traps on this ROM (both cost real time)

1. **`adb shell input text` is transliterated by the active Chinese IME.**
   With Gboard in Zhuyin mode, `input text 'com.m104'` typed
   `com歐元扮黑與歐元扮黑與歐元半`. The service-level fix is to disable the IME
   for the duration of the injection:
   ```powershell
   adb shell "ime disable com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
   adb shell input text 'com.m104'   # now lands verbatim
   adb shell "ime enable  com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"
   ```
   A single `input text` call also typed only `c` before the first fix, so short
   bursts are more reliable than long strings.

2. **`am force-stop` silently unbinds the accessibility service.** After a
   force-stop the entry disappears from `Bound services` while remaining in
   `Enabled services`; re-writing `enabled_accessibility_services` (or toggling
   the switch in Settings) is required to bind it again. Any "service survives a
   restart" test must therefore use a plain app restart, not `force-stop`.

Both are documented here because `TOOLS/device_test_checklist.ps1` drives the
handset through adb and would otherwise report false negatives.

## 7. Localisation (Traditional Chinese default)

The app is Chinese-first: `res/values/strings.xml` is the **Traditional Chinese
(zh-TW)** catalogue and `res/values-en/strings.xml` overrides it for English
devices. Android falls back per key, so `values-en` must list *every* key -
a missing one does not fail the build, it just renders Chinese on an English
phone. Both files now define the same **150** keys.

### 7.1 What had to change in code

Only 14 strings existed before this pass; the other ~136 user-visible strings
were **hardcoded Kotlin literals** (Dashboard 21, Settings 39, RewardApps 14,
Log 13, MainActivity 5, MainViewModel 3). A literal cannot be translated, so
every screen was converted to `stringResource(R.string.…)` and the catalogue was
expanded to match.

### 7.2 Enum names are a storage format, not a label

`ProtectionMode`, `RedirectPolicy`, `AssistAction` and `EventType` are written
into Room rows and DataStore entries and read back by `fromName()` /
`EventType.entries.firstOrNull { it.name == … }`. Translating or renaming them
would corrupt existing rows and break the log filter, so the new
`ui/Strings.kt` maps each constant to a resource id instead:

| Enum | Keys | Example |
| --- | --- | --- |
| `ProtectionMode` | `protection_mode_*` | `BLOCK` -> 封鎖 |
| `RedirectPolicy` | `redirect_policy_*` | `BLOCK_AFTER_GRACE` -> 寬限後封鎖 |
| `AssistAction` | `assist_action_*` | `ASSIST_TIMED_RETRY` -> 定時重試 |
| `LogFilter` | `log_filter_*` | `RETURNS` -> 返回 |
| `LogExporter.Format` | `export_format_*` | `TXT` -> 純文字 |

Each `when` in that file is exhaustive with **no `else` branch**, so adding an
enum constant is a compile error until it is translated - it cannot be silently
forgotten.

### 7.3 New regression guard: `StringResourceTest` (9 tests)

Nothing in the Android build fails when *that* string is later added, so the
JVM test suite now parses both XML catalogues as text and asserts:

* identical key sets in `values/` and `values-en/` (in both directions);
* identical `%1$d` / `%1$s` placeholders per key;
* no string mixes positional (`%1$s`) and non-positional (`%s`) args - the
  classic `IllegalFormatException` source;
* the default locale is genuinely Chinese and `values-en` genuinely English;
* the accessibility description keeps its `\n` and numbered steps;
* no leftover English sentences in `values/`, no blank values, no BOM.

### 7.4 Device verification (zh-TW handset)

Installed over the previous build and driven with `uiautomator dump`, reading
the live accessibility tree rather than screenshots. All four tabs render in
Chinese:

* **首頁** - 未連線 / 模式：封鎖 / 無障礙服務 已啟用 / 目前狀態 / 今日,
  counters 外部跳轉 / 已封鎖 / 錯誤 / 偵測到 X / 已協助點 X
* **App 清單** - 監看的獎勵 App / 以套件名稱新增 / 已安裝的 App, and the
  mode chips 跟隨全域 / 只記錄 / 封鎖 plus 新增 / 移除
* **記錄** - 統計 / 各 App 統計 / 操作 / 篩選 with 全部 · 跳轉 · 封鎖 ·
  關閉鈕 and 匯出 / 清除記錄
* **設定** - 防護 / 跳轉偵測 / 廣告 [X] 協助 / 返回獎勵 App / 記錄與診斷,
  including the previously raw enum chips 關閉|只記錄|封鎖 and
  只記錄|立即封鎖|寬限後封鎖 and 只偵測|協助點擊|閒置時協助|定時重試

The accessibility-service row correctly flipped 已停用 -> 已啟用 after the
service was re-enabled, which also proves the new label mapping is wired to the
live state and not to a constant.

### 7.5 Tooling note (cost real time)

* **Kotlin block comments nest.** A KDoc line containing `` `data/*.kt` `` opened
  a nested `/*` that the single closing `*/` never terminated, so the compiler
  reported `Unclosed comment` at EOF. Kotlin resolves nested block comments, so
  the same trap returns for any comment mentioning a glob path - write
  `` `data` package `` instead.
* **`Set-Content -Encoding UTF8` writes a BOM** on this PowerShell, and a BOM in
  a `.kt` file is a compile error. Prefer the editor tool, or strip bytes
  `EF BB BF` afterwards.
* **`settings put secure enabled_accessibility_services <pkg>` OVERWRITES the
  whole list.** Doing this by hand silently disabled every other accessibility
  service on the test handset. Always read, append, then write back - which is
  what `Enable-RewardAdGuardService` in `TOOLS/device_test_checklist.ps1` does;
  a warning was added to that function.

## 8. Known limitations

| Limitation | Effect | Notes |
| --- | --- | --- |
| No root | cannot kill/suspend another app or inject touches | "block" = quick return; `[X]` assist = expanded click target |
| Accessibility tree only | games rendering ads in a canvas/SurfaceView expose no nodes | nothing to match; the redirect guard still works |
| Browser detection | `PackageClassifier.isBrowser()` asks the `PackageManager` which `https` handlers exist plus a known list | a brand-new browser may be classified `EXTERNAL_APP` until added |
| Ad-session heuristics | window-change counting can over-report during heavy app switching | mitigated by logging it as `POSSIBLE_AD_SESSION`/`ATTEMPTED` |
| OEM battery managers | some vendors kill background services aggressively | the foreground notification is required; see the device checklist |
| `QUERY_ALL_PACKAGES` | broad package visibility | needed for classification; would need justification for Play Store review |
| **MIUI refuses to bind third-party a11y services** | on the test handset the service is enabled but never bound, so nothing is detected | environment issue, not an app defect — see §8.1, §8.2 |

### 8.1 Defect found and fixed: broken `settingsActivity`

`res/xml/accessibility_service_config.xml` declared

```xml
android:settingsActivity="com.rewardadguard.app.MainActivity"   <!-- WRONG -->
```

but the launcher activity lives in the `.ui` sub-package. Verified against the
device:

```
$ adb shell cmd package resolve-activity --brief -n com.rewardadguard.app/com.rewardadguard.app.MainActivity
No activity found
$ adb shell cmd package resolve-activity --brief -n com.rewardadguard.app/com.rewardadguard.app.ui.MainActivity
com.rewardadguard.app/.ui.MainActivity
```

Consequence: the system Settings screen silently dropped the **設定 / Settings**
entry point from the service detail page (no crash, no logcat warning). Fixed to
`com.rewardadguard.app.ui.MainActivity`; after reinstalling, the 設定 row appears
in 設定 > 輔助功能 > 下載的應用程式 > 獎勵廣告守衛 and tapping it launches
`com.rewardadguard.app/.ui.MainActivity`. This is the only code defect found
during the device pass; it does **not** affect binding.

### 8.2 The service still does not bind, and it is MIUI's security policy

After the fix above, a full uninstall/reinstall, and enabling the service through
the Settings UI (danger dialog accepted), the state is:

```
Enabled services : {com.rewardadguard.app/...RewardAdAccessibilityService, ...}
Bound services   : {點擊助手, AnyDesk, AirDroid, 裝置互聯}   <- ours is absent
Binding services : {}
Crashed services : {}
client list callbacks: 19   (our process is a registered client)
```

So the service is enabled, the process is alive (`ps` shows it), the component is
registered with the right `BIND_ACCESSIBILITY_SERVICE` permission, and
`onServiceConnected`/`onUnbind` never ran — the system simply never binds it.
The other three bound services are all MIUI-blessed (點擊助手 / AirDroid /
AnyDesk), which is the tell: **this build of MIUI (HyperOS) only binds
accessibility services it approves.**

The app reports this **correctly**: the Dashboard shows 未連線 and the warning
"無障礙服務已關閉…", while MIUI's own list shows 已啟用. The mismatch is between
MIUI's toggle state (`secure enabled_accessibility_services`) and the actual
binding — the app reads the live connection, not the flag.

Things that did **not** fix it (all tried):

* toggling the Settings switch off/on, accepting the MIUI 危險 dialog;
* `settings put secure accessibility_enabled 0` then `1`;
* `settings put secure enabled_accessibility_services <full merged list>`;
* re-installing the APK (`install -r`) and a full uninstall + fresh install.

Reflection shows the `AM` early in the stack (`AM,0,null,100`) confirming this is
an **MIUI/HyperOS platform behaviour**. It cannot be fixed from the app side and
does not indicate an app defect.

> **Important for anyone re-running the checklist:** `check_a11y_binding.ps1`
> distinguishes "enabled" from "bound" specifically so this failure mode is not
> mistaken for a bug in the guard.

#### Work-around for testing the guard logic

The guard itself is covered by 83 JVM tests, but to exercise it end-to-end on a
handset you need a ROM that binds third-party services. Options, cheapest first:

1. Test on an AOSP/stock-Android device or emulator (`emulator -avd <x>`); plain
   AOSP binds any service with the right permission.
2. On this MIUI build, look for a per-app "無障礙/自啟動" allowlist under
   Settings > 應用程式 > 獎勵廣告守衛, or 開發者選項; MIUI moves this between
   releases, so check 隱私保護 > 特殊權限 when the usual path is missing.
3. As a last resort, a rooted device can push the package into MIUI's
   accessibility allowlist, but this app deliberately does not require root.

### 8.3 Defect found and fixed: the installed-app search box was unusable

**Symptom:** on the Apps tab, typing into 搜尋已安裝的 App did nothing. The field
stayed empty and the list never filtered, so it read as a disabled control.

**Cause:** the text field was bound to a literal rather than to state.

```kotlin
OutlinedTextField(
    value = "",                    // <-- every keystroke is overwritten
    onValueChange = onSearch,
)
```

`onSearch` did reach the ViewModel and did filter the list, but the value handed
back to the field was always `""`. Compose therefore re-rendered the field empty
after each keystroke, and the caret never advanced. It is a one-character bug with
an offline-looking symptom, which is why it read as "the control is broken" rather
than "the filter is wrong" — the filter itself was fine.

**Fix:** the query is now hoisted state owned by the ViewModel
(`MainViewModel.appQuery`) and flows back down to the field. Three related
problems were fixed at the same time:

1. **No debounce.** `AppManager.launcherApps()` walks the whole `PackageManager`,
   so filtering per keystroke would stutter on a device with hundreds of packages.
   `setAppQuery` now cancels the in-flight job and waits 200 ms.
2. **Leading/trailing whitespace broke the match.** The raw string went straight
   into `contains()`, so a stray trailing space from a soft keyboard or a paste
   silently emptied the list. The rule is extracted as `matchesCandidateQuery` and
   now trims first.
3. **The clear affordance was missing.** There was no way to get back to the full
   list other than deleting character by character; a trailing clear button was
   added.

**Verification:** 8 new unit tests (`CandidateSearchTest`) pin the matching
contract — blank matches everything, label and package both match, case is
ignored, whitespace is trimmed, and an app with a blank label stays reachable by
package name. Suite is now 91 tests, 0 failures. Installs and launches with no
`FATAL EXCEPTION`.

**Not verified:** the typing interaction itself on hardware. The test handset is
PIN-locked and could not be driven over `adb`, so this rests on the code fix and
the unit tests rather than a screen recording.

## 9. Launcher icon

The app ships a complete icon set, not just a vector placeholder. Before this
pass the only artwork was a white shield with a `!` drawn straight onto the
adaptive canvas with an **unlayered** background fill, which misbehaves: see the
layer rules below.

### 9.1 The three-layer requirement

An Android icon is really three separate deliverables, and a missing one is
invisible in the IDE but obvious on a device:

| Layer | File | Needed for |
| --- | --- | --- |
| Adaptive (background / foreground) | `mipmap-anydpi-v26/ic_launcher.xml` | API 26+ |
| Themed / monochrome | `drawable/ic_launcher_monochrome.xml` | Android 13+ "themed icons" |
| Legacy bitmaps | `mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png` | API < 26, and third-party launchers that read the mipmaps directly |

The legacy bitmaps are the easy one to forget, because the adaptive icon *appears*
to cover everything on a modern test device. A launch that trusts only
`res/mipmap/ic_launcher.xml` leaves the app with a blank or default icon on older
devices.

### 9.2 Design

A white shield (the guard) containing a navy redirect arrow (an ad sending the
user to another app) that runs into a slanted barrier (the block). The barrier is
13 units wide on the 108-unit canvas on purpose: a hairline bar looks correct in a
vector preview and then aliases away entirely when the launcher rasterises it at
48px.

Artwork spans `x 34..74`, `y 21..87` on the 108x108 adaptive canvas. That
deliberately exceeds the 72x72 safe zone (`18..90`) a little — a shield that fits
strictly inside it reads as timid at launcher size, and the mask trims the excess
on circular launchers.

### 9.3 Why the monochrome layer is not a copy

The themed-icon layer is re-tinted by the launcher, so it must be a **silhouette**.
Reusing the two-tone foreground would collapse into a single flat blob, because the
navy arrow and the white shield would both become the same wallpaper colour. The
monochrome file therefore re-cuts the same marks as *holes* — an `evenOdd` ring for
the shield rim, plus arrow-shaft / arrow-head / barrier cut-outs — so the shape
survives being flattened to one colour.

### 9.4 Reproducing and verifying

| Tool | Purpose |
| --- | --- |
| `TOOLS/make_launcher_icons.ps1` | Rasterises all ten legacy PNGs from one 108x108 design space. Run with `-Force` after editing the vector. |
| `TOOLS/preview_launcher_icon.ps1` | Prints a PNG as ASCII art plus a safe-margin check, so the silhouette can be verified in a terminal. |
| `TOOLS/find_icon_in_screenshot.ps1` | Locates the icon in a device screenshot by matching the brand navy, proving the launcher actually draws it. |

The generator is the single source of truth for the fallback art — the same
coordinates as `ic_launcher_foreground.xml` are declared as named constants, so the
vector and the bitmaps cannot silently drift apart.

**Verification performed:** the ten PNGs regenerate deterministically; the APK
packages all twelve icon resources (`aapt2 dump resources`); the PNG extracted back
out of the built APK renders as the intended shield/arrow/barrier in ASCII; the app
installs and launches with no icon-related `Resources$NotFoundException`.

**Not verified:** the icon appearing on the launcher grid itself. The PIN lock on
the test handset could not be passed via `adb`, so the final "does it look right at
48dp in the drawer" check is still open.

## 10. Change log (this session)

* Added the missing `SettingsRepository` setters and the matching `MainViewModel`
  setters; removed a duplicated block introduced earlier.
* `Container` is now consistently reached via `RewardAdGuardApp.Container`.
* Per-app statistics labels now come from `MonitoringState.rewardApps.value`
  instead of a stale snapshot.
* `exportLog` rewritten on top of `logger.recentEvents()` / `logger.recentSessions()`
  with `LOG_LIMIT` / `SESSION_LIMIT`.
* `SettingsScreen` field names aligned with `AppSettings`
  (`externalRedirectProtection`, `closeButtonAssistance`, `verifyReturn`,
  `autoLaunchRewardApp` — no `…Enabled` suffixes).
* `EventLogger` constructed once with a live-settings lambda and `appManager::appLabel`.
* `LogExportReceiver` / `RewardAdAccessibilityService` point at
  `com.rewardadguard.app.ui.MainActivity`; stray container block removed.
* `MainActivity` share intent uses `result.shareIntent.type ?: LogExporter.Format.TXT.mimeType`.
* `LogScreen` resolves event types through `EventType.entries`, uses a
  nullable-receiver `severityColor()` and shows `destinationPackage ?: sourcePackage`.
* `res/values/strings.xml` gained `channel_name` (it previously existed only in
  `values-en`, which raised a lint warning).
* **New:** `FormatTest` — 20 tests covering `ui/Format.kt`. It found a real
  defect: the three `SimpleDateFormat` instances were cached in top-level `val`s,
  so they froze the default time zone captured at class-load time. A device whose
  zone changes while the process is alive (manual change, NITZ update, travel)
  would keep stamping log rows and exports in the stale zone. `Format.time` /
  `dateTime` / `date` now build a formatter per call with the *current* default
  zone; `LogExporter.LOG_STAMP` / `ISO_STAMP` were converted from `val` to `fun`
  for the same reason.
* **New:** `TEST_REPORT.md` / this section refreshed: **5 suites / 74 tests /
  0 failures**, debug APK **9.71 MB** (the earlier 10.12 MB artefact was built
  before a `clean`; the difference is `lint`-style stale resources, not code).
* **Removed:** stale `build_errors.txt` (a leftover javac error dump). Its one
  line referenced `ui/LogScreen.kt:166` — the `EventType.entries` /
  `severityColor()` fixes listed above already resolved it.
* **Verified:** `clean assembleDebug testDebugUnitTest` →
  `BUILD SUCCESSFUL`.

### 10.1 Physical-device session (2026-10-02)

* **Fixed:** `AndroidManifest.xml` launcher activity renamed `.MainActivity` →
  `.ui.MainActivity`. The old value resolved to a class that does not exist, so
  the very first device launch died with
  `ClassNotFoundException: com.rewardadguard.app.MainActivity`. The remaining
  components were verified against their `package` declarations and were
  already correct.
* **Installed and launched on hardware** for the first time (`2311DRK48G`,
  Android 16 / HyperOS 3): `adb install -r` → `Success`, cold launch clean, no
  `FATAL EXCEPTION` in logcat.
* **Accessibility service verified bound** through `dumpsys accessibility`:
  `Service[label=Reward Ad Guard]` with
  `eventTypes=[TYPE_VIEW_CLICKED, TYPE_VIEW_FOCUSED, TYPE_VIEW_TEXT_CHANGED,
  TYPE_WINDOW_STATE_CHANGED, TYPE_WINDOW_CONTENT_CHANGED, TYPE_WINDOWS_CHANGED]`,
  and `Crashed services:{}`. The dashboard chip flipped from
  `NOT CONNECTED`/`disabled` to `MONITORING`/`enabled` on its own.
* **Configuration flow verified end to end:** launcher enumeration, add by
  package name, per-app mode override (`Log only` → `Block`), remove, and
  persistence across a restart all work on device (§6).
* **Export verified:** TXT export wrote a 9 060-byte file to
  `cache/exports/` with a correct `# events=62 sessions=0` header and 62
  timestamped rows, then handed a FileProvider `content://` URI to
  `com.android.intentresolver/.ChooserActivity`.
* **Documented two adb traps** (§6.1): the Chinese IME transliterating
  `input text`, and `am force-stop` unbinding the accessibility service.
* New screenshots kept as evidence under `TOOLS/ui_01…ui_20*.png`.

* **New:** `TOOLS/device_test_checklist.ps1` — 28-step adb-driven on-device
  checklist (`-NonInteractive` prints without waiting). Verified to parse and run
  with exit code 0.

### 10.2 Full Traditional Chinese localisation (2026-10-02)

* **Fixed `settingsActivity`.** It pointed at `com.rewardadguard.app.MainActivity`,
  a class that does not exist (`cmd package resolve-activity` → *No activity
  found*), so the system Settings screen silently hid the service's 設定 entry
  point. Changed to `com.rewardadguard.app.ui.MainActivity`; verified on device
  that the 設定 row now appears and launches the app. See §8.1.
* **Diagnosed the 未連線 state as a MIUI platform policy, not an app bug.**
  `dumpsys accessibility` shows the service in `Enabled services` and absent from
  `Bound services` / `Binding services` / `Crashed services`, with our process
  alive and registered as an a11y client. The other three bound services are all
  MIUI-preblessed. Full evidence and failed work-arounds are in §8.2 so this is
  not re-litigated. **The app's own status display is correct.**
* **Corrected an earlier finding in this report.** `settings put secure
  enabled_accessibility_services <pkg>` did *not* actually disable the other three
  accessibility services — they are still listed and still bound. Only our own
  entry is refused by MIUI. The `Enable-RewardAdGuardService` warning about
  clobbering the list stays as defensive advice, but the observed damage was
  overstated.
* **New `TOOLS/check_a11y_binding.ps1`.** Parses `dumpsys accessibility` and
  reports `enabled` vs `bound` separately, exiting 0 only when the target is
  actually bound. This is the check that distinguishes a dead service from a
  broken app.
* **Verified after the fix:** `assembleDebug` → `BUILD SUCCESSFUL`; APK installed
  on `2311DRK48G`; the service detail page renders our localized description with
  intact `\n` and the four numbered steps; 設定 launches `MainActivity`; four tabs
  still render in Chinese; no crash in `logcat`.

### 10.2 Full Traditional Chinese localisation (2026-10-02)

* **Language policy:** `values/strings.xml` is now the **zh-TW** catalogue and
  `values-en/strings.xml` the English override, so a Chinese handset needs no
  language setting. Both hold the same **150** keys.
* **De-hardcoded every screen.** Only 14 strings existed before; the remaining
  ~136 were Kotlin literals (Settings 39, Dashboard 21, RewardApps 14, Log 13,
  MainActivity 5, MainViewModel 3). All are now `stringResource(...)`, so the
  app is translatable at all.
* **New `ui/Strings.kt`.** Maps `ProtectionMode`, `RedirectPolicy`,
  `AssistAction`, `LogFilter` and `LogExporter.Format` to resource ids with
  exhaustive `when`s (no `else`), because the enum *names* are the Room/DataStore
  storage format and must not be translated. This is what turned the raw chips
  `OFF|LOG_ONLY|BLOCK` into 關閉|只記錄|封鎖.
* **`Format.humanize` retired from the UI.** Kept and still tested as a
  diagnostics helper, but documented as off-limits for anything on screen.
* **New `StringResourceTest` — 9 tests.** Parses both XML catalogues on the JVM
  and fails on key drift between locales, placeholder mismatch, mixed
  positional/non-positional format args, leftover English in `values/`, blank
  values or a BOM. Nothing in the Android build catches any of these.
* **Verified on the zh-TW handset** by reading the live `uiautomator` tree: all
  four tabs (首頁 / App 清單 / 記錄 / 設定) render in Chinese, and the
  accessibility row flipped 已停用 -> 已啟用 with the real service state.
* **Two build traps fixed and documented (§7.5):** a KDoc mentioning
  `` `data/*.kt` `` opened a *nested* Kotlin block comment and failed the build
  with `Unclosed comment`; and `Set-Content -Encoding UTF8` emitted a BOM that
  Kotlin rejects.
* **Handset-safety note:** `Enable-RewardAdGuardService` in
  `TOOLS/device_test_checklist.ps1` carries a warning that
  `settings put secure enabled_accessibility_services <pkg>` **replaces** the
  whole list and would drop every other accessibility service. (Observed on the
  MIUI test handset: MIUI silently refused the resulting write without actually
  unbinding the other services, but the replacement semantics are real and the
  merged read-append-write approach is still required.)
* **Verified:** `clean assembleDebug testDebugUnitTest` —
  `BUILD SUCCESSFUL`, **6 suites / 83 tests / 0 failures** (74 prior + 9 new),
  APK `reward-ad-guard-debug.apk` ~9.8 MB, installed and launched clean on
  `2311DRK48G` with no `FATAL EXCEPTION`.

### 10.3 Device test pass (2026-10-02) — defect found and fixed

* **Fixed `settingsActivity`.** It pointed at `com.rewardadguard.app.MainActivity`,
  a class that does not exist (`cmd package resolve-activity` → *No activity
  found*), so the system Settings screen silently hid the service's 設定 entry
  point. Changed to `com.rewardadguard.app.ui.MainActivity`; verified on device
  that the 設定 row now appears and launches the app. See §8.1.
* **Diagnosed the 未連線 state as a MIUI platform policy, not an app bug.**
  `dumpsys accessibility` shows the service in `Enabled services` and absent from
  `Bound services` / `Binding services` / `Crashed services`, with our process
  alive and registered as an a11y client. The other three bound services are all
  MIUI-preblessed. Full evidence and failed work-arounds are in §8.2 so this is
  not re-litigated. **The app's own status display is correct.**
* **Corrected an earlier finding in this report.** `settings put secure
  enabled_accessibility_services <pkg>` did *not* actually disable the other three
  accessibility services — they are still listed and still bound. Only our own
  entry is refused by MIUI. The `Enable-RewardAdGuardService` warning about
  clobbering the list stays as defensive advice, but the observed damage was
  overstated.
* **New `TOOLS/check_a11y_binding.ps1`.** Parses `dumpsys accessibility` and
  reports `enabled` vs `bound` separately, exiting 0 only when the target is
  actually bound. This is the check that distinguishes a dead service from a
  broken app.
* **Verified after the fix:** `assembleDebug` → `BUILD SUCCESSFUL`; APK installed
  on `2311DRK48G`; the service detail page renders our localized description with
  intact `\n` and the four numbered steps; 設定 launches `MainActivity`; the four
  tabs still render in Chinese; no crash in `logcat`.



### 10.4 Launcher icon set (2026-10-02)

* **Replaced the placeholder art.** The old vector drew an unlayered white shield
  with an `!` and baked an opaque background rectangle into the foreground layer.
  The new mark is a shield holding a redirect arrow that runs into a slanted
  barrier, split across the three layers Android actually expects.
* **Added the themed-icon layer** (`drawable/ic_launcher_monochrome.xml`) and
  wired it in as `<monochrome>` for Android 13+. It is a true silhouette with the
  arrow and barrier cut out as holes, not a copy of the foreground — copying would
  flatten to one colour when the launcher re-tints it.
* **Added the twelve legacy bitmaps**, which did not exist at all before: five
  densities for `ic_launcher` and `ic_launcher_round`, declared via the new
  `android:roundIcon` in the manifest.
* **New `TOOLS/make_launcher_icons.ps1`** generates those bitmaps from one 108x108
  design space shared with the vector, supersampled 4x and downsampled so the
  48px art stays legible.
* **Two verification tools** so the art can be checked without a device:
  `TOOLS/preview_launcher_icon.ps1` (ASCII render + safe-margin check) and
  `TOOLS/find_icon_in_screenshot.ps1` (finds the icon in a device screenshot by
  brand colour).
* **Tooling trap fixed:** `adb exec-out screencap -p > file.png` through PowerShell
  writes UTF-16LE and produces an unreadable 35 KB file instead of a PNG; the
  capture must go through `cmd /c`. Also `Bitmap.GetPixel()` over a full screenshot
  exhausts memory, so the scanner reads the locked pixel buffer directly.
* **Verified:** `assembleDebug testDebugUnitTest` → `BUILD SUCCESSFUL`, 83 tests,
  0 failures; all twelve icon resources present in the APK; the PNG extracted from
  the APK renders as intended; installs and launches with no icon error.


### 10.5 Fixed the installed-app search box (2026-10-02)

* **The search field on the Apps tab could not be typed into.** It was bound to
  `value = ""` instead of real state, so Compose blanked it after every keystroke
  and the caret never moved. The filter underneath was never the problem.
* **Query hoisted into `MainViewModel.appQuery`** and flowed back down to the
  field, so typed text persists and the list actually filters.
* **Added a 200 ms debounce** — `AppManager.launcherApps()` walks the whole
  `PackageManager`, so filtering on every keystroke would have stuttered on a
  device with several hundred packages.
* **Trimmed the query before matching.** A trailing space from a soft keyboard or
  a paste previously emptied the list with no visible reason.
* **Added a clear button** to the field; getting back to the full list used to
  require deleting character by character.
* **New `CandidateSearchTest`** extracts the match rule as `matchesCandidateQuery`
  and pins it with 8 tests. Suite is now 91 tests, 0 failures.

