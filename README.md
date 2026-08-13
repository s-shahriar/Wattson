# Wattson

An Android battery analyser that reads the same data Android keeps internally, rather
than the summarised view Settings shows.

Built because the stock battery screen rounds away the detail that matters — it will not
tell you your idle drain in milliamps, which app fed the "flashlight" bucket, or what
your cell's real capacity is against its factory rating.

## What it shows

- **Live status** — charge level, current flow in mA, time to full, voltage, cycle count,
  and true capacity health (`charge_full` against `charge_full_design`). Refreshes every
  5 seconds while the screen is open.
- **Battery history** — level over time as a column chart, coloured by charge state, with
  a screen-on activity strip on the same axis. Switchable between the ongoing battery
  cycle and a rolling 24 hours. Tap or drag to inspect any slice.
- **Session totals** — screen on/off split, time on battery, discharge in mAh.
- **Drain split** — screen-on versus screen-off consumption, each with its average draw,
  parsed from the per-state blocks batterystats keeps separately.
- **By category** — screen, video, audio, wakelocks and the rest, each with the apps that
  fed it.
- **Top apps** — ranked by attributed mAh.

## Requirements

Android 12+ (minSdk 31). Wattson adapts to whatever the device allows:

| Tier | Needs | You get |
|---|---|---|
| **Root** | Magisk / KernelSU | Everything, automatically |
| **Privileged** | three adb grants, no root | Everything — same data |
| **Basic** | nothing | Live charge state only |

For the privileged tier, connect the phone to a computer once and run:

```bash
adb shell pm grant com.syed.wattson android.permission.DUMP
adb shell pm grant com.syed.wattson android.permission.BATTERY_STATS
adb shell pm grant com.syed.wattson android.permission.PACKAGE_USAGE_STATS
```

All three are `development`-level permissions, so none is ever granted automatically.
`dumpsys` runs as the calling app, and the batterystats service checks all three on the
caller — DUMP alone yields *"does not have android.permission.BATTERY_STATS"*, and adding
that yields *"missing android.permission.PACKAGE_USAGE_STATS"*.

Without root and without those grants the app still shows charge level, current draw,
voltage, temperature and cycle count, all from public APIs. Capacity health is not
available there: `BATTERY_PROPERTY_STATE_OF_HEALTH` throws `SecurityException` without
`BATTERY_STATS`.

## Design notes

**Nothing runs in the background.** There is no service, no receiver, no boot hook, no
wakelock, no alarm. The live poll is bound to the resumed lifecycle state — minimising
the app cancels it, verified by probing for the child processes each poll forks:

| App state | Poll activity over ~14s |
|---|---|
| Foreground | 8 samples |
| Minimised | 0 samples (process still alive) |
| Reopened | 6 samples |

**The expensive dump runs only on refresh.** `dumpsys batterystats --charged` is ~490 KB
of text and the history dump is over 300,000 lines, so history is reduced on-device with
an awk filter to one record per actual change (~2,200 lines). The 5-second live poll
touches neither — it reads ten sysfs nodes in a single shell round-trip.

**Network access is one button.** `INTERNET` exists solely for the manual update check
against the GitHub releases feed. Battery data never leaves the device.

## Building

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and an Android SDK with platform 36.

## Releases

In-app updates read the GitHub releases atom feed, so **this repository must stay public**
and release assets must be named `Wattson-vX.Y.Z.apk`. See `CLAUDE.md` for the full
release procedure.
