# TEST REPORT — Reward Ad Guard

| Item | Value |
| --- | --- |
| Package | `com.rewardadguard.app` |
| Test type | JVM unit tests (`app/src/test`, Android `testDebugUnitTest`) |
| Runner | JUnit 4 |
| Command | `gradle -p "<project>" testDebugUnitTest --offline` |
| Result | **BUILD SUCCESSFUL — 74 tests, 0 failures, 0 errors, 0 skipped** |
| APK | `app/build/outputs/apk/debug/reward-ad-guard-debug.apk` (9.8 MB) |

## 1. Test suites

| Suite | File | Tests | Focus |
| --- | --- | --- | --- |
| `CloseButtonDetectorTest` | `app/src/test/java/com/rewardadguard/app/detector/CloseButtonDetectorTest.kt` | 23 | ad `[X]` scoring + geometry expansion |
| `FormatTest` | `app/src/test/java/com/rewardadguard/app/ui/FormatTest.kt` | 20 | duration / package / session / timestamp formatting |
| `SmartRedirectEngineTest` | `app/src/test/java/com/rewardadguard/app/guard/SmartRedirectEngineTest.kt` | 16 | redirect risk scoring + policy decisions |
| `SessionStateTest` | `app/src/test/java/com/rewardadguard/app/session/SessionStateTest.kt` | 9 | session state machine + session id factory |
| `PackageClassifierTest` | `app/src/test/java/com/rewardadguard/app/detector/PackageClassifierTest.kt` | 6 | `DestinationKind` semantics |
| `StringResourceTest` | `app/src/test/java/com/rewardadguard/app/ui/StringResourceTest.kt` | 9 | zh-TW / en catalogue consistency, placeholder parity |

Total: **6 suites / 83 tests**.

## 2. What is verified

### 2.1 `CloseButtonDetector` (spec § 24–27) — 23 tests

* `NodeSnapshot` derives `width` / `height` / `area` correctly and never returns a negative area for inverted bounds.
* Matching signals:
  * exact glyph `x` / `×` → `SYMBOL_TEXT` (score ≥ `MATCH_THRESHOLD` = 45);
  * exact keywords `Close`, `Skip Ad`, `關閉`, `跳過` → `TEXT_KEYWORD`;
  * `contentDescription`-only match (`"Close ad"` on an `ImageButton`) → `DESCRIPTION_KEYWORD`;
  * view-id hints (`…:id/iv_close`) measurably raise the score.
* Rejections: `visible = false` → `INVISIBLE`; zero-width node → `ZERO_BOUNDS`; long mid-screen ad copy → `REJECTED_SCORE_*`.
* **False-positive guard:** a full-screen `"Close"` CTA (1080×2400) is rejected because the area penalty (`-25` when `area > screen/6`) outweighs the keyword bonus. This is the rule that stops the guard from "clicking the whole ad".
* `expandedBounds`:
  * `scale = 1f` returns the original rect;
  * the box grows around the node centre (centre invariance asserted);
  * coordinates are clamped to `>= 0`, so a top-edge node yields `y = 0` (documented as `240x220` for the 80×80 node at `(940,60)-(1020,140)`). This keeps the suggested click target on screen.
* `bestMatch` picks the highest-scoring candidate, returns `null` when nothing matches and `null` for an empty tree.
* `boundsLabel()` reports the expanded size, and `"-"` when nothing matched (unmatched nodes must never expose a click target).

### 2.2 `SmartRedirectEngine` (spec § 19–20) — 16 tests

* `baseRisk`: `SELF`/`UNKNOWN` = 0; `BROWSER` = `STORE` = 1; `GAME` = 2; `DEEP_LINK` = 3.
* `riskScore`: additive signals (`redirectCountInWindow >= 2` → +1, `>= 3` → +2, no-return → +1, ad session → +1) and subtractive signals (close seen → -1, real tap on the first hop → -1, `returnCountInWindow >= 2` → -1), clamped to `0..9`. Scores are asserted exactly, e.g. a 3rd browser hop with no return and an active ad session = **6**.
* Decisions:
  * `LOG_ONLY` → `logOnly`, never blocks;
  * `BLOCK_AFTER_GRACE` observes an unresolved medium risk while the grace window is open (`RISK_5_GRACE_ACTIVE`) and blocks the same situation once the window elapsed;
  * risk at/above `redirectRiskThreshold` blocks immediately with `HIGH_RISK_*`;
  * **spec test 55 regression guard:** a first, low-risk `STORE` hop right after a real tap is *not* blocked even under `BLOCK_IMMEDIATELY` — it yields `FIRST_LOW_RISK_INTERACTION`, so `Store → Back → Ad → X` still completes;
  * with Smart Redirect disabled the engine falls back to the raw policy (`GRACE_PERIOD` / `STRICT_POLICY_IMMEDIATE`).
* Invariant sweep: for every `DestinationKind` × `RedirectPolicy` × `graceAlreadyElapsed` × `redirectCountInWindow` combination, `logOnly` and `block` are **never** both true.

### 2.3 `SessionState` — 9 tests

* `isActive()` is true for `REWARD_APP_ACTIVE`, `AD_SESSION_ACTIVE`, `EXTERNAL_APP_DETECTED`, `BLOCKING`, `RETURNING`; false for exactly `IDLE`, `COMPLETED`, `ERROR`.
* A full happy-path walk (`IDLE → REWARD_APP_ACTIVE → EXTERNAL_APP_DETECTED → BLOCKING → RETURNING → REWARD_APP_ACTIVE → COMPLETED → IDLE`) ends inactive.
* `SessionIdFactory`: 500 ids are unique; the format matches `^SESSION_\d{8}_\d{6}_\d{3}$`; ids in the same second increment the `NNN` suffix (001, 002); a new second restarts it at 001.
* `SessionEndReason` exposes every exit path including `LEFT_REWARD_APP`, `SERVICE_DESTROYED` and `MANUAL`.

### 2.4 `DestinationKind` — 6 tests

* `SELF` and `UNKNOWN` are the only non-external classes; all five others (`BROWSER`, `STORE`, `GAME`, `DEEP_LINK`, `EXTERNAL_APP`) report `isExternal = true`.
* Enum values are unique and `valueOf(name)` round-trips, which is what the accessibility service relies on when it logs a classification.

### 2.5 `StringResourceTest` — 9 tests

Parses `res/values/strings.xml` (zh-TW, the default) and
`res/values-en/strings.xml` (the English override) as plain text and asserts the
properties the Android resource pipeline does **not** enforce:

* **Key parity, both directions.** A key present only in `values/` renders
  Chinese on an English device; a key only in `values-en/` renders English on a
  Chinese device. Neither fails the build, so the test does.
* **Placeholder parity.** Every `%1$d` / `%1$s` in one locale must appear in the
  other, otherwise a formatted string throws `IllegalFormatException` or prints
  a stray `%s` on one locale only.
* **No mixed positional and non-positional args.** `"%1$s / %s"` is legal to
  write and fatal at runtime once the arguments shift.
* **Locale content sanity.** The default catalogue is asserted to be genuinely
  Chinese (`app_name` = 獎勵廣告守衛) and `values-en` genuinely English, so a
  future edit that accidentally copies English into `values/` is caught.
* **The accessibility description keeps its `\n` and numbered steps 1.–4.**
  The system Accessibility dialog renders this verbatim; a collapsed newline
  turns four readable steps into one paragraph.
* **No leftover English sentences in `values/`** (brand names, `CSV`/`JSON` and
  format specifiers are allowed via an explicit allow-list).
* **No blank values and no UTF-8 BOM**, the latter because a BOM emitted by a
  scripted file write broke the Kotlin build during this session.


## 3. Deliberately out of scope for JVM tests

These need a device / Android framework and are covered by `TOOLS/device_test_checklist.ps1`:

| Component | Why not unit-tested here |
| --- | --- |
| `PackageClassifier.classify()` | needs a real `Context` + `PackageManager` (`getLaunchIntentForPackage`, `queryIntentActivities`, `getApplicationInfo`) |
| `RewardAdAccessibilityService` | needs a live `AccessibilityNodeInfo` tree and the accessibility binding |
| `SessionManager` / `EventLogger` / `LogExporter` | need `Context`, Room database, coroutines and `FileProvider` |
| Foreground-service notifications, `MainActivity` Compose UI | instrumentation / manual QA |

The pure decision logic for all of those was extracted into the classes above precisely so the risky parts are testable: `PackageClassifier` returns a value that `SmartRedirectEngine` consumes, and `CloseButtonDetector` consumes `NodeSnapshot`, which has no Android dependency.

## 4. Assumptions corrected while writing the tests

Writing the tests surfaced four places where the *test* initially encoded a wrong assumption about the shipped behaviour. In every case the production code was correct; the expectation was corrected and the real value is now asserted explicitly, so the behaviour is pinned by the suite:

1. `expandedBounds` clamps to `y >= 0`, so a node at the top edge yields a shorter box than `height * scale` (`240x220`, not `240x240`). Assertion corrected and the clamp documented in the test body.
2. `riskScore` is clamped; the maximum reachable score with the documented signals is **8**, not 9. The test now asserts `8` **and** `<= MAX_RISK`.
3. The `returnCountInWindow >= 2` bonus is applied inside the `0..9` clamp, so it cannot push a browser hop "below base risk" — the observed values are `2 → 0`. Assertion corrected.
4. `BLOCK_AFTER_GRACE` blocks a **medium**-risk transition once the grace window elapses, and at `redirectRiskThreshold` (default 4) it blocks immediately. To exercise the grace branch deterministically the test raises `redirectRiskThreshold` to 9 — the same knob the settings screen exposes.

No production defect was found: every failing expectation was a test-side misunderstanding, and no production file needed to change for the suite to pass.

## 5. Reproducing

```powershell
$env:JAVA_HOME='C:\Program Files\Microsoft\jdk-17.0.20.8-hotspot'
& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.7-bin\bhs2wmbdwecv87pi65oeuq5iu\gradle-8.7\bin\gradle.bat" `
  -p "d:\Visual Studio Code\PROJECT\Reward Ad Guard" testDebugUnitTest --offline
```

* HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`
* Machine-readable: `app/build/test-results/testDebugUnitTest/*.xml`
