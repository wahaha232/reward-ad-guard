# Change log

Development outline for Reward Ad Guard. Newest first. This file is the short
version: it records *what changed and why* so a fresh checkout (or a fresh
assistant session) can pick the work up without re-deriving it.
`DEVELOPMENT_REPORT.md` holds the full design rationale; this is the history.

---

## 2026-10-05 — Investigation: "the app does nothing at all"

**Field report after two days of real use: the guard has no visible effect.**

Root cause found in the code (not yet measured on the device — see the caveat).

### The trap: a new app is added in `LOG_ONLY`, which never blocks

`RewardAppsRepository.add()` defaults to `ProtectionMode.LOG_ONLY`, and
`MainViewModel.safeDefaultMode()` keeps that while New App Safe Mode is on
(the default). The per-app mode then *overrides* the global mode in
`effectiveProtectionEnabled()`, so a user who adds their reward app and changes
nothing else gets:

* sessions started, events logged, dashboard counters moving — everything looks
  healthy,
* and **zero blocking**, forever.

This is intentional (spec 37/38: never auto-escalate a newly added app) but it is
indistinguishable from a broken app, which is exactly how it was reported.

**Not a code bug — a setup trap that must be surfaced in the UI.**

### Secondary: the default policy waits before acting

`redirectPolicy = BLOCK_AFTER_GRACE` with a 700 ms grace. Even in `BLOCK` mode a
redirect the user leaves quickly is never blocked.

### Added

* `NewAppSafeModeTest` (8 tests) — pins the consequences above so a future change
  to the defaults forces a deliberate decision instead of silently restoring the
  trap.
* `TOOLS/auto_test.ps1` — unattended ADB smoke test. Answers the three questions a
  two-day trial should have answered: is the service **actually bound** (vs merely
  listed — the MIUI trap), which apps are configured in which mode, and did any
  events reach the database at all. Consumes no ad.
* `TOOLS/auto_test.md` — how to run it, and the ADB connection prerequisites.
* `app/src/debug/.../DebugTestReceiver.kt` + debug manifest — a debug-only hook so
  redirect/return behaviour can be driven synthetically, without spending the
  once-per-day rewarded ad. Verified absent from the release manifest (0 matches).

### Caveat

Everything above is derived from reading the code. Nothing here has been confirmed
on a device yet. Run `TOOLS/auto_test.ps1` first; it costs no ad and will tell us
whether the diagnosis is right.



Seven commits. Verified with `./gradlew assembleDebug testDebugUnitTest`
(9 suites, 99 tests, 0 failures).

### 1. Repository hygiene: line endings normalised

`core.autocrlf` was `false` while the committed blobs were LF and the Windows
worktree was CRLF. A 44-line change to `SessionManager.kt` therefore showed up as
a 348-line diff and hid the real edits twice during this session.

* Added `.gitattributes` (`* text=auto`, `gradlew text eol=lf`, binaries marked
  `binary`) and set `core.autocrlf=true`.
* Re-checked-out the worktree; `git diff` for `SessionManager.kt` is now 44 lines
  and every one of them is a real change.

### 2. Accessibility-service-off warning told the truth

`RewardAppsScreen`'s unmonitored-foreground warning showed `foregroundPackage`
(a raw package id) where an app name was expected, and it trusted a snapshot that
goes stale while the service is off. It could name the wrong app — usually Reward
Ad Guard itself, because the user had just opened it.

* The warning now shows the **resolved label** (looked up from the pre-resolved
  `candidates` / `rewardApps` lists — never from a composable) with the package id
  in monospace beneath it.
* Added `apps_not_monitored_service_off`, shown when the service is not bound,
  because in that state there is no foreground information to report at all.
* `MainActivity` hoists `ServiceAccess.isServiceEnabled(context)` once and passes
  it down instead of calling it per recomposition.

### 3. `MainViewModel`: `SavedStateHandle` was never injected

The constructor used a Kotlin default argument
(`handle: SavedStateHandle = SavedStateHandle()`), which compiles to
`(Application, SavedStateHandle, int, DefaultConstructorMarker)` — a signature the
reflection-based `ViewModelProvider` factory never matches. The saved-state path
could not have worked.

* Replaced with two explicit constructors: `(Application, SavedStateHandle)` and
  `(Application)`. `javap` confirms only those two exist.
* `MainActivity` no longer threads a separate `SavedStateHandle`; it uses
  `viewModel.savedState`, so there is a single owner of the key.
* New `MainViewModelConstructorTest` (3 tests) pins the signature and fails if the
  default argument comes back.

### 4. `SessionManager.publish()` kept a stale `sourcePackage`

`sourcePackage = session?.sourcePackage ?: snapshot.sourcePackage` left the last
reward app on the dashboard after the session ended, so the UI claimed a source
app while nothing was monitored.

* `sourcePackage` now follows `session` and becomes null.
* Added Robolectric (`org.robolectric:robolectric:4.14.1`,
  `isIncludeAndroidResources = true`) so `SessionManager` can be tested against a
  real `EventLogger`/Room database. **Do not run unit tests with `--offline`.**
* New `SessionManagerPublishTest` (3 tests) seeds a stale package in `@Before`, so
  it cannot pass merely because the field defaulted to null.

### 5. Empty sessions no longer pollute the session list

Vendor ROMs (MIUI) restart the accessibility service often. Each restart called
`endSession(SERVICE_DESTROYED)` and persisted an empty record.

* Added `ActiveSession.hasNoObservations()`; an empty service-destroyed session is
  now closed silently.
* Extended `SessionStateTest` (+5 tests).

### 6. Project knowledge moved into the repository

Cline keeps its history in local `globalStorage` and does not sync between
machines, so the reasoning above was written down where it travels with the code:

* `.clinerules/project.md` — layout, build/test commands, Robolectric caveat.
* `.clinerules/conventions.md` — strings, Compose, ViewModel and testing rules.
* `.clinerules/known-issues.md` — every bug above, plus the traps that made them
  hard to find.

### Method notes

Two techniques found bugs that a green build did not:

* `javap` on the compiled class to see the real JVM signature instead of assuming
  Kotlin's sugar is invisible to reflection.
* Every regression test was run against the *old* code first and had to fail
  before being accepted, and Robolectric used instead of mocks that would have
  hidden the stale-snapshot behaviour.

---

## Earlier

* `2f1d9a3` — Fix untypable search field on the installed-app picker. The field
  was bound to a hard-coded `""`; the term now lives in `MainViewModel.appQuery`.
* `40f11a6` — Initial commit: Reward Ad Guard accessibility service.
