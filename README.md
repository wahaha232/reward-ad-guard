# Reward Ad Guard

An Android accessibility service that watches the apps you choose and steps in
when a rewarded-ad flow tries to bounce you somewhere else.

Rewarded ads are built to send you away: you tap close and the ad opens the Play
Store, or a browser, or a game you never installed. Reward Ad Guard notices that
redirect as it happens, returns you to the app you were actually using, and tells
you which app tried it.

## What it does

- **Watches only the apps you pick.** Detection is scoped to an explicit list, so
  the service is not inspecting everything on the device.
- **Calls out redirect attempts** when a monitored app tries to hand off to
  another package, and returns you to the origin app.
- **Enlarges close-button targets** so the `X` on a full-screen ad is reachable.
- **Records a local event log** you can browse in-app and export, so the behaviour
  is auditable rather than a black box.

## What it deliberately does not do

This is the honest part, and it is why the app needs no root:

| | |
| --- | --- |
| Cannot kill or suspend another app | "block" means an immediate return, not termination |
| Does **not** inject gestures | the close-button helper calls `performAction(ACTION_CLICK)` on the matched node (`RewardAdAccessibilityService.kt:584`); it never synthesises a touch/gesture stream |
| Blind to canvas/SurfaceView ads | games that render ads outside the accessibility tree expose no nodes to match |
| No broad package visibility | the app does **not** declare `QUERY_ALL_PACKAGES`; launcher-app `<queries>` are used to enumerate what the user picks from |
| *(corrected 2026-10-05)* | an earlier version of this table claimed `QUERY_ALL_PACKAGES` was declared. That was never true of the manifest. |

## Requirements

Android 8.0 (API 26) or newer. The accessibility service must be enabled manually
in system settings — Android provides no way for an app to do this for itself, by
design.

> **Vendor note (corrected 2026-10-05):** an earlier version of this note claimed
> the HyperOS handset *never binds* third-party accessibility services, and that
> this was a platform policy. **The on-device database disproves that.** The
> service was bound on 2026-10-02, twice on 10-03 and again on the morning of
> 10-05, and it intercepted real ads. Read
> [`ANALYSIS_REPORT.md`](ANALYSIS_REPORT.md) §2.2 for the raw numbers.
>
> The likely cause of the *current* "enabled but not bound" state is our own test
> tooling: `TOOLS/auto_test.ps1` passes `-S` to every `am start`, which
> force-stops the app. Force-stopping strips the service from
> `enabled_accessibility_services` — standard AOSP behaviour
> (`AccessibilityManagerService.onHandleForceStop`), not a HyperOS invention, and
> something this project had already documented on 10-02
> (`DEVELOPMENT_REPORT.md` §6.1). See [`ANALYSIS_REPORT.md`](ANALYSIS_REPORT.md) §3.
>
> There may still be a smaller HyperOS background-start restriction on top of
> that, but it is no longer the primary suspect and it has not been demonstrated.
>
> **Never use `am force-stop`, `am start -S`, or
> `settings put secure enabled_accessibility_services` when testing** — all three
> manufacture the "the service never binds" symptom.
>
> **And the service *has* been bound successfully at least once**, on 2026-10-02:
> `dumpsys` showed `Service[label=Reward Ad Guard]` with the full event-type list
> and the dashboard flipped to `MONITORING` on its own. The platform stopped
> binding it afterwards. So this is "worked once, then blocked" — not "never worked".
>
> See [`DEVELOPMENT_REPORT.md`](DEVELOPMENT_REPORT.md) §8.2 **and** §10.2 — they
> describe the two halves and must be read together.

## Building

```bash
# Requires JDK 17 and the Android SDK (set ANDROID_HOME or local.properties)
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

The debug APK lands in `app/build/outputs/apk/debug/`.

## Layout

```
app/src/main/java/com/rewardadguard/app/
  detector/   what to look at  - foreground app, package classification, close buttons, redirects
  guard/      what to do       - allowlist, redirect interception, close-button help, the decision engine
  session/    when it applies  - session lifecycle and state
  service/    the Android entry points (accessibility service, export receiver)
  manager/    persistence, settings, logging, monitoring state
  ui/         Compose screens and the ViewModel
  data/       Room entities, DAOs and the database
```

## Tools

The `TOOLS/` scripts are how the behaviour was verified on real hardware rather
than assumed:

| Script | Purpose |
| --- | --- |
| `device_test_checklist.ps1` | Drives the end-to-end device checklist and reports pass/fail per step |
| `check_a11y_binding.ps1` | Confirms whether the platform actually bound the accessibility service |
| `make_launcher_icons.ps1` | Rasterises the legacy launcher bitmaps from one shared design space |
| `preview_launcher_icon.ps1` | Renders a PNG as ASCII art plus a safe-margin check |
| `find_icon_in_screenshot.ps1` | Locates the icon in a device screenshot by brand colour |

Rewarded ads reset at midnight and each test in the core-flow group consumes one,
so that group is a once-per-day resource. [`TOOLS/daily_ad_test.md`](TOOLS/daily_ad_test.md)
says which single step to pick for a given change, and which steps have an ad-free
substitute.

## Documentation

- [`ANALYSIS_REPORT.md`](ANALYSIS_REPORT.md) — **start here.** The current state and
  conclusion: what is verified (with the raw on-device session numbers), why the
  service is presently unbound, the two settings traps, and the recommended order
  of repairs. Also lists the mistakes made while investigating — including two in
  the report itself — so they are not repeated.
- [`CHANGELOG.md`](CHANGELOG.md) — development outline: what changed, when and why.
- [`DEVELOPMENT_REPORT.md`](DEVELOPMENT_REPORT.md) — architecture, behaviour detail,
  the complete permissions rationale, device test results, known limitations and a
  full change log including the defects found and fixed along the way.
- [`TEST_REPORT.md`](TEST_REPORT.md) — unit test coverage and results.
- [`.clinerules/`](.clinerules/) — working notes for AI assistants: project rules,
  conventions, and the list of fixed bugs and traps. Read
  [`known-issues.md`](.clinerules/known-issues.md) before debugging.

## Status

The app is complete, and its core flow has now been **verified on real hardware
against real ads**. The on-device database pulled on 2026-10-05 records two real
sessions on 10-03:

| Session | Window | redirect | block | return OK | return fail |
| --- | --- | --- | --- | --- | --- |
| `SESSION_20261003_171346_001` | 17:13 → 20:46 (3 h 32 m) | 19 | 1 | 19 | **0** |
| `SESSION_20261003_231852_001` | 23:18 → 23:23 | 12 | **9** | 12 | **0** |

Plus 10,032 real events between 09:17 and 09:52 on 10-05. Every one of them came
from `jp.paddleinc.walk`, a real reward app — none from the synthetic test app.
Close-button detection fired 4,814 times and captured a real ad close button at
`558x96`, enlarged to `1674x288`.

Two things do remain open, and both are now precisely understood:

1. **Close-button *clicking* has never fired**, because `assist_action` was set to
   `NONE` on the device. The guard finds the button and deliberately declines to
   press it. That is a **setting**, not a defect — see
   [`ANALYSIS_REPORT.md`](ANALYSIS_REPORT.md) §2.4.
2. **The service is currently not bound**, and the most likely reason is our own
   test script force-stopping the app with `am start -S`. See
   [`ANALYSIS_REPORT.md`](ANALYSIS_REPORT.md) §3.

Also outstanding: the release is not signed yet.
