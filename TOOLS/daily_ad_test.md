# Daily ad test — the one run you get per day

Rewarded ads reset at midnight, so **on-device ad testing is a once-per-day
resource**. This file exists to stop that single run from being wasted.

Run `TOOLS/device_test_checklist.ps1` for groups A/B/C/E/F — those never need an
ad and can be repeated as often as you like. This file covers **group D**, the
only part that consumes reward credits.

---

## Before you spend the run

Do all of this first. Every item can be verified without watching an ad.

| # | Check | Why it matters |
|---|---|---|
| 1 | `gradle testDebugUnitTest` is green (99 tests) | catches logic errors before you spend the day's credits |
| 2 | `gradle assembleDebug` and the APK is installed | `adb install -r` or the device-UI fallback |
| 3 | Accessibility service shows in **Enabled services *and* Bound services** | MIUI can list it as enabled while never binding it; nothing is detected in that state |
| 4 | Notification permission granted (Android 13+) | the foreground service is required to keep the a11y service alive |
| 5 | The reward app is ticked in **Reward apps** | an unticked app produces no session at all |
| 6 | Redirect policy is set to the value you intend to test | D4 and D5 need *different* policies and both need an ad |
| 7 | `adb logcat` is already streaming to a file | the ad is gone by the time you think to start it |
| 8 | You know which test you are running | see "Pick one" below |

Start logcat **before** opening the reward app, and keep it running:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb logcat | Tee-Object -FilePath "$env:USERPROFILE\Desktop\rag-$(Get-Date -f yyyyMMdd).log"
```

---

## Pick one, not all

D1–D7 are not a queue to work through; each one needs its own ad. You will usually
get one or two ads a day. **Decide in advance which single question the day's run
answers**, and record it. Writing it down in advance is what stops you from
"just checking" something else and burning the run.

| If this is what changed since the last run | Run this |
|---|---|
| Nothing — you are just confirming the build is healthy | **D1** |
| Detection or blocking logic (`detector/`, `guard/`) | D3 + D4 if one ad provides both |
| Close-button matching (`CloseButtonDetector`) | D6 |
| Session counting, logging, `SessionManager` | D1 then E1–E3 (E costs no ad) |
| Nothing ad-related (settings, strings, UI) | **No ad needed — skip group D entirely** |

### The cheapest useful run

**D1 alone is the highest-value test per credit.** It is the only one that can
prove the guard did not break the normal flow, and a false positive there — the
guard interrupting a legitimate ad — is the worst possible regression. If you only
ever run one test, run D1.

---

## Recording the result

Note the date, which test you picked, and the outcome. `TOOLS/device_test_checklist.ps1`
collects verdicts interactively; if you are working ad-hoc, append to the daily log
instead so the next session can see the history:

```
date        test  verdict  notes
2026-10-03  D1    pass     reward granted, no interruption, SESSION_SUMMARY shows 1 possibleAdSession
```

---

## What can be tested without an ad

These are commonly assumed to need a real ad. They do not — save the credits.

| Step | Why it does not need an ad |
|---|---|
| D7 (full-screen CTA not mistaken for `[X]`) | the rejection is a bounds-ratio check in `CloseButtonDetector`; it is covered by unit tests, and any full-screen clickable in any app exercises the same path |
| D5 (log-only policy never blocks) | observable with any external redirect, including one you trigger by hand from another app — no ad required |
| E1–E7 (log, statistics, export) | only require *some* event to exist, from any earlier run |
| F1–F6 (robustness) | app switching, revoking permission, disabling the service and rotation are all ad-free |
| Redirect detection and blocking (D3/D4) | a hand-triggered external redirect follows the same code path; a real ad is only needed to confirm the *ad* case specifically |

So realistically: **D1, D2 and D6 are the only steps that genuinely require a
rewarded ad.** Everything else has a cheaper substitute.

---

## If today's run fails

Do not try to reproduce immediately — you will have spent the credits and be
guessing. Instead:

1. Read the logcat file, not the on-screen log; the on-screen log may be missing
   events from before the app was opened.
2. Check the log for `SESSION_SUMMARY`, `REDIRECT`, `BLOCK`, `RETURN` and
   `CLOSE_DETECT` entries and note which are absent.
3. Write the finding into `.clinerules/known-issues.md` while it is fresh.
4. Fix it and verify with unit tests that same day, then spend the *next* day's
   run confirming the fix rather than re-diagnosing it.
