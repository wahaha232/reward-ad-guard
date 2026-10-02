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
* Always run the whole suite before claiming a change works: 9 suites / 99 tests.
* A green build is not proof of correctness — see `known-issues.md`.

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
app/src/test/java/com/rewardadguard/app/   mirrors the main tree
TOOLS/         adb dump scripts used during development
```

## Line endings

`.gitattributes` + `core.autocrlf=true`. The repo stores **LF**; the Windows
worktree is **CRLF**. This is intentional — do not "fix" a CRLF warning, and do
not commit a file with mixed endings.
