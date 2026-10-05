# Reward Ad Guard — project rules

Android accessibility service (Kotlin, Compose, Room, DataStore) that guards
reward/ad flows for a personal sideloaded APK. **Not on Google Play.**
Debug builds only; there is no release signing config.

## Build & test

```bash
./gradlew assembleDebug testDebugUnitTest
```

* **Do not pass `--offline`.** `org.robolectric:robolectric:4.14.1` is needed by
  the unit tests and is not always in the local Gradle cache. Offline mode fails
  with `No cached version of org.robolectric:robolectric:4.14.1 available for
  offline mode.`
* Unit tests run on the JVM. Robolectric is used for anything that touches the
  Android framework (Context, SQLite/Room, PackageManager). Pure logic tests
  (detectors, classifiers, formatters) stay plain JUnit.
* Always run the whole suite before claiming a change works: 10 suites / 107 tests.
* A green build is not proof of correctness — see `known-issues.md`.

## Device testing (read this before touching a phone)

Rewarded ads reset once per day, so ad-consuming tests are the scarcest resource
in this project. **Always run the automated pre-flight first — it costs no ad:**

```powershell
.\TOOLS\auto_test.ps1            # install, inspect, exercise; exit 0 = safe
.\TOOLS\auto_test.ps1 -SkipInstall   # re-check a phone that is already set up
```

It answers the three questions a manual trial otherwise gets wrong: whether the
accessibility service is **actually bound** (not merely listed — the MIUI trap),
which apps are configured **in which mode**, and whether **any events reached the
database**. Full guide: `TOOLS/auto_test.md`.

Section T of that script drives `DebugTestReceiver` (debug builds only) to
exercise redirect/return logic with **zero ads**, so the once-per-day ad can be
reserved for D1/D2/D6. See `known-issues.md` for the `exported="false"` caveat.

## Layout

```
app/src/main/java/com/rewardadguard/app/
  service/     RewardAdAccessibilityService (the only entry point for events)
  session/     SessionManager — session lifecycle + state machine
  detector/    CloseButtonDetector, RedirectDetector, PackageClassifier
  guard/       CloseButtonGuard, AllowlistGuard, SmartRedirectEngine
  controller/  ReturnController (go back to the reward app)
  manager/     EventLogger, MonitoringState, RewardAppsRepository
  ui/          Compose screens + MainViewModel + Format
app/src/debug/java/                        DebugTestReceiver (never in release)
app/src/test/java/com/rewardadguard/app/   mirrors the main tree
TOOLS/         auto_test.ps1 + device_test_checklist.ps1 + adb dump scripts
```

## Line endings

`.gitattributes` + `core.autocrlf=true`. The repo stores **LF**; the Windows
worktree is **CRLF**. This is intentional — do not "fix" a CRLF warning, and do
not commit a file with mixed endings.
