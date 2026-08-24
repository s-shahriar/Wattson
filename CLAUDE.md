# Wattson — Project Instructions

## GitHub Release Rules

The in-app updater (`data/UpdateService.kt`) reads the releases **atom feed** and derives
the APK URL from the tag. Two things must hold or updates silently break:

1. **The repository must stay public.** `releases.atom` is not fetchable without auth on
   a private repo, and the app sends no credentials.
2. **The asset must be named `Wattson-vX.Y.Z.apk`** — matching `ASSET_PREFIX` in
   `UpdateService`. Hyphen, `v` prefix, no underscores.

### 1. Find the last release tag
```bash
gh release list -R s-shahriar/Wattson --limit 1 --json tagName --jq '.[0].tagName'
```

### 2. Determine the next version
- **Patch** for bug fixes (v1.0.0 → v1.0.1)
- **Minor** for new features (v1.0.0 → v1.1.0)
- **Major** for breaking changes (v1.0.0 → v2.0.0)

Ask which bump is appropriate if unclear.

### 3. Update the version in `app/build.gradle.kts`
```kotlin
versionCode = 2          // increment by 1 every release
versionName = "1.1.0"    // must match the tag without the leading v
```

`UpdateService` compares the feed's tag against `BuildConfig.VERSION_NAME`, so a mismatch
here makes the app either miss updates or offer one it already has.

### 4. Build the release APK
```bash
./gradlew :app:assembleRelease
```
Output lands in `app/build/outputs/apk/release/`.

### 5. Rename to the convention
```bash
cp app/build/outputs/apk/release/app-release.apk ./Wattson-vX.Y.Z.apk
```

### 6. Create the release
```bash
gh release create vX.Y.Z \
  --repo s-shahriar/Wattson \
  --title "Wattson vX.Y.Z - <Short Description>" \
  --notes "<Release notes>" \
  "Wattson-vX.Y.Z.apk#Wattson-vX.Y.Z.apk"
```

### 7. Verify
```bash
gh release view vX.Y.Z -R s-shahriar/Wattson
```

Then confirm the in-app check picks it up: open Wattson → Updates → Check for updates.

## Architecture

```
data/
  Shell.kt                  one-shot su calls, always reaped
  BatteryRepository.kt      load() = full dump; loadLive() = cheap sysfs poll
  UpdateService.kt          GitHub atom feed + APK download/install
  model/                    domain types, typed exceptions
  parser/                   dumpsys text -> domain
ui/
  BatteryScreen.kt          shell + state routing only
  BatteryViewModel.kt       refresh + 5s lifecycle-bound live poll
  model/                    view-ready projection, all percentages precomputed
  section/                  one composable per card
  component/                shared primitives, component/chart/ for plots
  theme/                    Material 3 Expressive + fixed chart palette
```

**Rules that matter:**

- The mapper (`ui/model/BatteryUiMapper.kt`) is a pure function. All arithmetic happens
  there, never in the recomposition path.
- Chart colours come from `theme/chartPalette()`, **not** the Material You scheme —
  dynamic colour resolves primary/secondary/tertiary into one hue family, which made
  charging/discharging/screen-on indistinguishable.
- Never add a service, receiver, boot hook or wakelock. The no-background-work guarantee
  is the point of the app.
- App icons are decoded only for the top `ICON_BUDGET` apps; the tail keeps labels only.
- **Nothing a dump touches may be retained.** A full `dumpsys batterystats --history` is
  over 20 MB and 350 000 lines on a device whose history buffer has filled; it is reduced
  a line at a time as it arrives (`parser/BatteryHistoryReducer`) and never held whole.
  A load costs ~5 MB of peak heap and keeps ~300 KB of samples, and repeated refreshes
  must leave that flat — measure with `dumpsys meminfo <pid>` across several refreshes
  before believing otherwise. Every shell call is reaped in a `finally`, every read has a
  deadline, and stderr is capped: an app that watches the battery must not be a reason to
  charge it.
