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

## Limit: ad detection is inferred, never confirmed

Android does not expose "this screen is an ad" to a non-root app. An ad session
is always *inferred* from window changes, close-button nodes and external
transitions. Do not add UI or logging that claims an official ad state.
(DEVELOPMENT_REPORT.md, Limit 4)
