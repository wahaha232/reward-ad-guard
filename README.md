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
| Cannot inject touches | the close-button helper enlarges the target; it does not tap for you |
| Blind to canvas/SurfaceView ads | games that render ads outside the accessibility tree expose no nodes to match |
| Uses broad package visibility | `QUERY_ALL_PACKAGES` is declared so packages can be classified by type |

## Requirements

Android 8.0 (API 26) or newer. The accessibility service must be enabled manually
in system settings — Android provides no way for an app to do this for itself, by
design.

> **Vendor note:** on HyperOS / MIUI the service can be toggled on in Settings and
> still never be bound by the platform. This was observed on the test handset; it
> is a platform policy, not an app defect. See §8.1 of the development report.

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

## Documentation

- [`CHANGELOG.md`](CHANGELOG.md) — development outline: what changed, when and why.
  Start here to catch up on recent work.
- [`DEVELOPMENT_REPORT.md`](DEVELOPMENT_REPORT.md) — architecture, behaviour detail,
  the complete permissions rationale, device test results, known limitations and a
  full change log including the defects found and fixed along the way.
- [`TEST_REPORT.md`](TEST_REPORT.md) — unit test coverage and results.
- [`.clinerules/`](.clinerules/) — working notes for AI assistants: project rules,
  conventions, and the list of fixed bugs and traps. Read
  [`known-issues.md`](.clinerules/known-issues.md) before debugging.

## Status

Functional on the test device for detection, logging and export. Two things are
still open and are documented rather than glossed over: the accessibility service
is never bound on HyperOS/MIUI (§8.1), and the release is not signed yet.
