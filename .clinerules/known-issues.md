# Known issues, fixed bugs and traps

Read this before debugging anything. Several of these were subtle and the fix is
not obvious from the code alone.

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

## Note: `DebugTestReceiver` uses `exported="false"`

`app/src/debug/java/.../DebugTestReceiver.kt` is declared `exported="false"` in the
debug manifest. Whether `adb shell am broadcast` can reach a non-exported receiver
varies by Android version and could not be verified without a device. If section T
of `auto_test.ps1` reports SKIP on a working debug build, this is why — switch the
receiver to `exported="true"` (still `BuildConfig.DEBUG`-gated, still debug-only) or
move the entry point to a debug-only Activity.


Android does not expose "this screen is an ad" to a non-root app. An ad session
is always *inferred* from window changes, close-button nodes and external
transitions. Do not add UI or logging that claims an official ad state.
(DEVELOPMENT_REPORT.md, Limit 4)
