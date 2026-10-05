# Known issues, fixed bugs and traps

Read this before debugging anything. Several of these were subtle and the fix is
not obvious from the code alone.

## Fixed: a working guard looked broken because every refusal shared one log line

This is the single most expensive bug in the project. It cost a full day of
device debugging and wasted a Trial because the symptom ("the app has no effect
at all") was real but the cause was invisible.

`CloseButtonGuard.shouldAssist()` returned a bare `Boolean`. The caller collapsed
**every** `false` into one message:

```
CLOSE_MISS / SKIPPED / "assist action disabled or throttled"
```

So a deliberate user setting, a throttle window, a live finger on the screen and
the per-session safety cap were byte-identical in the log. On a real device this
produced 2,408 consecutive identical rows in 35 minutes, and the actual cause -
`assistAction = NONE` in DataStore - had to be extracted by decoding
`prefs.bin` by hand. `CLOSE_ACTION` and `CLOSE_RESULT` never appeared once,
because no click had ever been recorded.

Two distinct defects, both fixed:

1. **The reason was thrown away.** `shouldAssist()` now returns an
   `AssistDecision` (`ALLOW`, `DISABLED`, `ACTION_NONE`, `NOT_AD_WINDOW`,
   `USER_INTERACTING`, `THROTTLED`, `SESSION_CAP`, `NO_MATCH`) and
   `decide()` is the single source of truth for both the action and the message.
2. **`CLOSE_MISS` meant two different things.** It is now reserved for
   "no usable match"; the new `CLOSE_DECISION` event type carries
   "found but deliberately not clicked", with the reason in the message.

Regression test: `AssistDecisionTest` (15 tests, includes an explicit
"all eight outcomes are distinct" assertion and a "two decisions never share a
message" assertion).

**If you add a new refusal reason, add a new `AssistDecision` value.** Do not
reuse `THROTTLED`, and never reintroduce a shared message.

## Trap: `assistAction = NONE` silently disables the whole feature

The code default is `ASSIST_WHEN_IDLE`; `AssistAction.NONE` means *detect and log
but never click*. The guard then does everything right - finds the X, computes
the tap target, logs `score=75 bounds=72x72` - and refuses to tap. Nothing in the
UI says so.

The settings screen now shows an amber `InlineWarning` whenever
`closeButtonAssistance` is on and the action is `NONE`.

## Trap: `LOG_ONLY` is not `OFF`

Already documented in `NewAppSafeModeTest`, repeated here because it is the same
class of bug as the one above: sessions, redirects and detections are all
recorded, so the dashboard looks healthy while nothing is ever blocked. A
per-app `LOG_ONLY` override also outranks a global `BLOCK` in
`effectiveProtectionEnabled()`. Both the settings screen and the reward-apps
screen now show an amber warning naming the affected apps.

## Fixed: 96% of the event table was `CLOSE_DETECT` spam

`TYPE_WINDOW_CONTENT_CHANGED` fires continuously while a rewarded ad animates.
Every callback wrote a `CLOSE_DETECT` row (and a `CLOSE_BOUNDS` row), producing
9,630 of 10,032 rows on a real device - burying the handful of rows that
described what the guard actually did.

`CloseButtonGuard.onDetectedOnly()` now de-duplicates on a `DetectionKey`
(score + method + bounds). An identical observation is written at most once per
`DETECTION_DEDUPE_MILLIS` (10s). Counters and `MonitoringState` still update on
every call, so the dashboard numbers are unaffected.

## Fixed: an input method (Gboard) could be added as a reward app

`AppManager.isSystemInfrastructure()` was only applied to the *candidate list*
built by `launcherApps()`. `RewardAppsRepository.add()` had no such check, so the
runtime "new app detected" prompt and the debug entry points could both add any
package. Gboard (`com.google.android.inputmethod.latin`) was found configured as
a reward app with mode `LOG_ONLY`.

An IME appears over *every* app, so treating it as a reward app corrupts the
session model. (`ApplicationInfo` has no IME flag, so detection asks
`PackageManager` whether any component in the package declares
`android.permission.BIND_INPUT_METHOD`, with a known-package-name fallback.)

Three layers now agree:

* `add()` returns an `AddResult` and refuses infrastructure packages,
* `MainViewModel` shows the refusal as a snackbar instead of a silent no-op,
* `purgeInvalidApps()` runs on `refreshAll()`, so a list polluted by an older
  build repairs itself and reports what it removed.

## Device identity (do not re-litigate these)

Used for all diagnostics in this project:

* Redmi Note 13 Pro 5G (`2311DRK48G`), codename `duchamp_global`
* Android 16 / HyperOS V816

Facts already established, so they do not need re-testing:

* The accessibility service is **listed and bound**. The MIUI "listed but not
  bound" trap does **not** apply here.
* Redirect blocking works when configured: a BitWalk session recorded
  `redirectCount=12`, `blockCount=9`, `returnSuccessCount=12`.
* `SERVICE_DESTROYED` does appear as a session end reason. HyperOS restarts the
  service; this is handled (see "empty sessions polluted the session list").
  Still open: whether a foreground service / battery-optimisation exemption
  would reduce the restarts.
* The installed APK can be stale. `TOOLS/auto_test.ps1` section T needs a build
  that contains `DebugTestActivity` (the shell-facing test entry point; the
  broadcast receiver cannot be driven from ADB — see the note further down).

## Device verification: what can and cannot be done over ADB

Verified on the device:

* `adb install -r app/build/outputs/apk/debug/reward-ad-guard-debug.apk` works.
  **The APK is not called `app-debug.apk`** — it is `reward-ad-guard-debug.apk`
  (`archivesBaseName` / `applicationVariants` renaming in `build.gradle.kts`).
* `adb shell run-as com.rewardadguard.app cat ...` works, so the DataStore and
  DB can be read for forensics. **`prefs.bin` does not exist** — the settings
  live in `files/datastore/reward_ad_guard_settings.preferences_pb`
  (a protobuf, so read it as bytes and look for the ASCII strings).
* `adb shell dumpsys window | grep mCurrentFocus` is the reliable way to know
  what is actually on screen.

Three things that block scripted end-to-end testing:

1. **The accessibility service cannot be enabled over ADB.**
   `adb shell settings put secure enabled_accessibility_services ...` appears to
   succeed and `settings get` even reads the value back, but the Accessibility
   Manager **reverts it** (HyperOS re-validates the list against its own
   store). `dumpsys accessibility` then shows the service absent from both
   `Enabled services:` and `Bound services:`. There is no way around this
   without touching the Settings UI by hand.
   `am startservice` for the service also fails with
   `Requires permission android.permission.BIND_ACCESSIBILITY_SERVICE`.

2. **`uiautomator dump` returns the lock screen, not the app.**
   When the screen is off the dump silently contains only
   `com.android.systemui` nodes and looks like "the notification shade is stuck
   on top". Check `dumpsys power | grep mWakefulness` first — if it says
   `Dozing` the device is asleep and every UI interaction is being eaten by the
   keyguard. `wm dismiss-keyguard` does **not** work on HyperOS; the swipe-up
   gesture does not either when a fingerprint/PIN is configured.

3. **A temp-file pattern that works.** PowerShell mangles `adb` output badly
   (the shell integration truncates and the console encoding garbles it).
   Write command output to a file inside the repo and read the file, and use
   `[System.IO.File]::WriteAllText(path, text, [Text.Encoding]::UTF8)` plus
   `[string]::Join([char]10, ...)` instead of a literal `` "`n" `` — a literal
   backtick-n inside a `-Command` string gets mangled into a parse error.

**The parts that need a human:** the device must be awake, and the accessibility
service must be enabled through Settings -> Accessibility -> Reward Ad Guard.

**The parts that no longer need a human** (verified on device): reading and *writing*
the app's settings. `am start -S -n .../DebugTestActivity --es cmd dump_settings`
returns the live values, and `--es cmd set_assist_action --es value ASSIST_WHEN_IDLE`
fixes the `assist_action = NONE` trap. Both were confirmed against the raw DataStore
bytes. So the "silently configured as a logger" state can now be diagnosed and
repaired from a script instead of by tapping through the UI.

Also confirmed on the device, for the record:

* `assist_action` really was `NONE` in DataStore
  (`...\preferences_pb` contains `assist_action` followed by `NONE`), which is
  the root cause described above, on a real device and not a hypothesis.
* Gboard (`com.google.android.inputmethod.latin`) appeared in the accessibility
  client list, consistent with it having been reachable as a reward app.


The PowerShell script corrupted the pulled file (UTF-16 mangling). The bytes on
the device are correct - `adb exec-out` returns a valid `SQLite format 3\0`
header. Any reimplementation must copy **bytes**, not text: use
`ProcessStartInfo` with `RedirectStandardOutput` and a raw `Stream`, not
`Get-Content`/`Out-File`.

## Fixed: the search field could not be typed into

`RewardAppsScreen` bound the text field to a hard-coded `""`, so every keystroke
was reported and immediately discarded. The box stayed empty. Fixed by moving the
term into `MainViewModel.appQuery` and mirroring it through `SavedStateHandle`.
(commit `2f1d9a3`)

## Fixed: the "unmonitored foreground app" warning named the wrong app

The warning rendered `foregroundPackage` — a raw package id — as if it were an
app name, and the snapshot it read can be stale while the accessibility service
is off. The result was a warning that named the wrong package, typically Reward
Ad Guard itself, because the user had just opened it.

Two things were wrong and both are fixed:

1. It displayed the package id instead of a resolved label.
2. It asserted a foreground app even when the service was not running.

The warning now shows a resolved label (looked up from the pre-resolved
`candidates` / `rewardApps` lists), the package id in monospace next to it, and
an explicit caveat line when the service is disabled — because with the service
off there is no foreground information at all.

## Fixed: `sourcePackage` was sticky after a session ended

`SessionManager.publish()` used:

```kotlin
sourcePackage = session?.sourcePackage ?: snapshot.sourcePackage
```

so once a session ended, the *last* reward app stayed on the dashboard's "source
app" row even though nothing was being monitored. It now follows `session`, so it
becomes null. Regression test: `SessionManagerPublishTest`.

This affected **every** end path, not just `SERVICE_DESTROYED`.

## Fixed: empty sessions polluted the session list on vendor ROMs

MIUI and similar ROMs restart the accessibility service aggressively. Each
restart called `endSession(SERVICE_DESTROYED)`, which persisted a session record
containing nothing at all. `ActiveSession.hasNoObservations()` now lets that case
be closed silently. Regression tests: `SessionStateTest`.

## Fixed: `SavedStateHandle` was never actually injected

`MainViewModel` declared `handle: SavedStateHandle = SavedStateHandle()` as a
Kotlin default argument. The reflection-based `ViewModelProvider` factory matches
on the full JVM parameter list and does not understand Kotlin defaults, so the
constructor it looked for was
`(Application, SavedStateHandle, int, DefaultConstructorMarker)` — which it never
matches. The saved-state path could not have worked. Now there are two explicit
constructors. Regression test: `MainViewModelConstructorTest`.

## Trap: a passing build does not mean the feature works

`MainViewModelConstructorTest` and `SessionManagerPublishTest` both passed while
the code was still wrong, in the sense that the *feature* (process-death restore,
stale source app) was untestable from a plain JVM test. Two techniques found the
real bugs and should be reused:

* **`javap` the compiled class** to see the actual JVM constructor signature
  rather than assuming Kotlin's default-argument sugar is transparent.
* **Robolectric** to run manager/session code against a real Room database and a
  real `MonitoringState` instead of mocks that would have hidden the behaviour.

## Trap: `git diff` was dominated by line-ending noise

`core.autocrlf` was `false` while the committed blobs were LF and the worktree
was CRLF, so a 44-line change appeared as a 348-line diff. Fixed by
`.gitattributes` + `core.autocrlf=true`. If a huge unexplained diff appears
again, check this first:

```bash
git diff --numstat -w --ignore-cr-at-eol -- <file>   # real size of the change
```

## Constraint: on-device ad testing is once per day

Rewarded ads reset at midnight, so `TOOLS/device_test_checklist.ps1` group D
(D1–D7) can only be exercised once in 24 hours and each step in it needs its own
ad. Groups A/B/C/E/F never need one and can be repeated freely.

This changes how you should propose work:

* **Never suggest "test it on the device"** as a way to check a change in
  `detector/`, `guard/` or `session/`. That is what the unit tests are for.
* Check `TOOLS/daily_ad_test.md` before proposing any ad-consuming step; it maps
  a change to the single most informative step and lists the ad-free
  substitutes (D5 and D7 in particular do not need an ad).
* Remember `--offline` breaks `testDebugUnitTest` — see `project.md`.

## CRITICAL: "the app does nothing" — the likely root cause

Reported after two days of real use: **the guard appears to have no effect at all.**

Before writing new features, check these two settings, because *together* they make
the app functionally a logger rather than a guard:

### 1. Every newly added app starts in `LOG_ONLY`

`RewardAppsRepository.add()` defaults to `mode = ProtectionMode.LOG_ONLY`:

```kotlin
fun add(packageName, label, enabled = true, mode: ProtectionMode? = ProtectionMode.LOG_ONLY)
```

`LOG_ONLY` by design **never blocks anything**. This is the "New App Safe Mode"
behaviour (spec 37/38) and it is intentional, but the consequence is that a user who
adds their reward app and changes nothing else will see **zero** visible protection —
only log entries. The global `protectionMode` default is `BLOCK`, but the per-app
override wins (`effectiveProtectionEnabled()` -> `rewardAppStore.modeOverride(...)`),
so the global setting is silently defeated for that app.

**If a user reports "nothing happens", check the per-app mode in the app list first.**

### 2. The default redirect policy waits before acting

`redirectPolicy = BLOCK_AFTER_GRACE` with `blockGraceMillis = 700`. A redirect that
is left before 700 ms elapses is never blocked. Combined with (1), a LOG_ONLY app
never blocks at all regardless of timing.

### Most likely explanation for "no effect"

The reward app was added with the default `LOG_ONLY`, so the guard only ever logged.
**Fix for the user**: in the app list, set the reward app's mode to `BLOCK`
(and confirm the global mode is `BLOCK`).

This is a **UX/setup trap rather than a code bug** — but it is indistinguishable
from "the app is broken", so it must be surfaced in the UI, not just documented.

### Still unverified — needs a real device

The above is inferred from the code, not measured. The following were **never**
confirmed on a device after the two-day trial:

* whether the accessibility service was actually **bound** (not merely enabled —
  see the MIUI trap below),
* which packages were configured and in which mode,
* whether any events were written at all.

`TOOLS/auto_test.ps1` now answers exactly these three questions and should be run
first; it needs no ad and no manual checklist.

## Trap: MIUI / HyperOS says "enabled" but never binds the service

A service can be listed in `enabled_accessibility_services` (green tick in Settings)
while the system never actually binds it. UI state is therefore **not** evidence.
Only `dumpsys activity services <pkg>` proving
`RewardAdAccessibilityService` is running, or a `SERVICE_CONNECTED` event, counts.
`auto_test.ps1` checks S1 (listed) and S2 (bound) separately for this reason.

## RESOLVED on device: `DebugTestReceiver` was unreachable from the shell

Two separate causes stacked here; the second only became visible after fixing the
first. Both were measured on the device.

### Cause 1 — `exported="false"` (found 2026-10-05)

`adb shell am broadcast -n com.rewardadguard.app/.service.DebugTestReceiver` reaches
`ActivityManager` (logcat shows `Broadcasting:` then `Enqueued broadcast ... : 0`) and
`am` prints `Broadcast completed: result=0`, but **no process is started**:
`ps -A | grep rewardadguard` stays empty, with no `FATAL` and no `AndroidRuntime`
entry. `result=0` is a **false success**.

The tell is the trailing `: 0` on the `Enqueued broadcast` line — it is the
delivered-receiver count, not part of the intent.

Logcat was ruled out as the cause: `adb shell log -t INSPECT_TEST 'probe'` appears in
`adb logcat -s INSPECT_TEST`, so the buffer works. The missing app logs are a
*consequence* of the process never starting.

Also note `dumpsys package com.rewardadguard.app` lists the receiver and its `filter`
even when it is unreachable — being declared is not evidence of being reachable.

### Cause 2 — a `signature`-level permission is not held by `com.android.shell`

The first fix was to declare the receiver `exported="true"` and guard it with an
app-defined `android:permission` at `protectionLevel="signature"`, on the assumption
that the shell holds it via its platform signature. **It does not.** The shell holds
`com.android.shell.*` signature permissions; it does **not** hold an arbitrary app's
private signature permission. The broadcast is still dropped and `Enqueued ... : 0`
persists.

### Cause 3 (the real blocker) — `am broadcast` cannot start a stopped app's process

Making the receiver `exported="true"` and removing the permission **still** failed:
`Enqueued broadcast ... : 0` persisted and `ps -A` stayed empty.

What is actually happening: `adb shell am broadcast` **cannot force-start the target
process** when the app is not already running. Both lines come from `system_server`
(PID 1802), and the trailing `.0` is the *shell's own result code*, not a delivery
count. The giveaway is that there is **no `FATAL`, no permission denial, and no
process** — the broadcast is accepted and then dropped.

**This is why the receiver is now the wrong instrument entirely.** See below for the
entry point that works.

## HyperOS never binds the accessibility service (two independent mechanisms)

Measured on Redmi Note 13 Pro 5G / Android 16 / HyperOS V816. This blocks every
end-to-end test of the guard that needs real accessibility events, so it is worth
understanding precisely before blaming the app.

### Mechanism 1 — HyperOS periodically strips the service from the setting

```
$ adb shell settings put secure enabled_accessibility_services \
      "<existing>:com.rewardadguard.app/com.rewardadguard.app.service.RewardAdAccessibilityService"
$ adb shell settings get secure enabled_accessibility_services   # 3 s later: present
$ adb shell settings get secure enabled_accessibility_services   # minutes later: GONE
```

The value does **not** persist. HyperOS rewrites it back to its own list of
approved services. So "the setting is saved" is never safe to assume.

### Mechanism 2 — even while listed, the service is never bound

Immediately after a `settings put`, the authoritative `dumpsys` output is:

```
Enabled services:{{com.rewardadguard.app/...RewardAdAccessibilityService}, 點擊助手, 裝置互聯, AnyDesk}
Bound services  :{點擊助手, AnyDesk, AirDroid, 裝置互聯}   <- ours is ABSENT
Crashed services:{}                                        <- it did not crash
```

**Enabled > bound with no crash is the signature of a platform allowlist**, not an
app defect. `onServiceConnected` never runs, so `RewardAdAccessibilityService.instance`
stays null.

The app reports this **correctly**: the Dashboard shows 未連線 while MIUI's own list
shows 已啟用. The app reads the live connection, not the flag — that is the right
behaviour and must not be "fixed".

### What does not work (all measured, do not retry)

* `settings put secure enabled_accessibility_services <merged list>` — works for
  seconds, then reverted (mechanism 1), and never affects binding (mechanism 2).
* `settings put secure accessibility_enabled 0/1`.
* Toggling the Settings switch, accepting the MIUI 危險 dialog.
* `install -r`, and a full uninstall + fresh install.
* Making the receiver `exported="true"` (unrelated, but it was tried).

### How to tell the two apart

```powershell
# Are we even listed?  (can flip back to "no" at any time)
adb shell settings get secure enabled_accessibility_services
# Does the system actually bind us?  (the only value that matters)
adb shell dumpsys accessibility | Select-String 'Bound services' -Context 0,4
# What does the app itself think?  (should agree with Bound services)
adb shell am start -S -n com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity --es cmd dump_state
#   -> ... serviceConnected=false ...
```

### Consequences for testing

The guard's logic is covered by the JVM tests (see `TOOLS/auto_test.md`), and the
settings/data layer can be driven end-to-end from ADB through `DebugTestActivity`.
But **anything that needs a real `AccessibilityEvent` cannot be verified on this
handset** — `simulate_foreground` will correctly answer:

```
RESULT FAILED: accessibility service is not bound, so no synthetic transition
can be delivered. Check dump_state (serviceConnected) and whether this ROM
actually binds the service.
```

To exercise redirect / return / close-button end-to-end, use a plain AOSP build or
an emulator (`emulator -avd <x>`), which binds any service holding
`BIND_ACCESSIBILITY_SERVICE`.

## WORKING: drive the debug build with `am start -n` (DebugTestActivity)


An explicit **Activity** started by `am start -n` is the mechanism the shell can
reliably reach, and it was verified end-to-end on the device:

```text
adb shell am start -S -n com.rewardadguard.app/com.rewardadguard.app.debug.DebugTestActivity \
    --es cmd dump_settings
```

```
I RewardAdGuardDebug: test command=dump_settings package=null
I RewardAdGuardDebug: RESULT OK: SETTINGS protectionMode=BLOCK assistAction=ASSIST_WHEN_IDLE ...
```

The raw DataStore bytes confirmed it independently:
`assist_action` next to `ASSIST_WHEN_IDLE`.

Practical notes, all learned the hard way:

* **`-S` is mandatory.** Without it the second and later commands are delivered to the
  already-running instance (`Warning: Activity not started, intent has been delivered
  to currently running top-most instance.`), `onCreate` does not run again, and logcat
  just **replays the first command's output**. A whole round of "the settings didn't
  change" was this, not a real failure.
* **`exported="true"` is required on the Activity.** With `false` the shell is refused
  with `SecurityException: Permission Denial: starting Intent {...} from null (pid=...,
  uid=2000) not exported from uid <app>`. Unlike the broadcast case this fails loudly,
  which is what makes it debuggable.
* **The log tag is `RewardAdGuardDebug`.** Three different tags are now in play and
  mixing them up makes `adb logcat -s` look empty:
  `RewardAdGuardDebug` (test activity), `RewardAdGuardService` (accessibility
  service), `RewardAdGuard` (debug receiver / most modules).
* `simulate_foreground` still needs the accessibility service bound; without it the
  hook replies `accessibility service is not connected; cannot simulate`.


Android does not expose "this screen is an ad" to a non-root app. An ad session
is always *inferred* from window changes, close-button nodes and external
transitions. Do not add UI or logging that claims an official ad state.
(DEVELOPMENT_REPORT.md, Limit 4)
