# Fork notes

Personal fork of [akaMrNagar/Mindful](https://github.com/akaMrNagar/Mindful), adding a
**cooldown gate**: configured apps interrupt their own opening with a breathing pause,
then show usage context and ask for a deliberate decision.

Everything else is upstream. The fork is deliberately **additive** — 608 insertions and
1 deletion across 11 files at the time of writing — because deletions are what make
upstream merges painful, and staying current matters more than trimming features.

## Branch layout

| Branch | Purpose |
|---|---|
| `main` | Pristine mirror of upstream. **Never commit here.** |
| `cooldown` | The fork's work, kept merged up with `main`. |

Keeping `main` untouched is the whole trick. It means GitHub's **Sync fork** button always
works, and every upstream update is one predictable merge instead of a negotiation.

## Staying up to date

```bash
./scripts/sync-upstream.sh
```

That fast-forwards `main` to upstream, pushes it, and merges `main` into `cooldown`.
It refuses to run on a dirty tree, and tells you what to do if it hits conflicts.

Then rebuild:

```bash
./scripts/build-apk.sh
```

## Toolchain

Not discoverable from the repo, so worth writing down:

- **Flutter** stable at `~/dev/flutter`
- **JDK 17** via the Homebrew `openjdk@17` *formula* (not the cask — that needs sudo).
  Keg-only, so `JAVA_HOME` must be set explicitly.
- **Android SDK** at `/opt/homebrew/share/android-commandlinetools`
- **NDK `27.0.12077973`** must be installed. This is AGP 8.10.1's own default, which is
  why upstream pins exactly it. AGP's auto-download leaves a stub directory with no
  `source.properties` and fails with `[CXX1101]`; install it properly with
  `sdkmanager --install "ndk;27.0.12077973"` (~2.4 GB).

## Signing

Release signing reads **environment variables**, not a properties file:
`KEYSTORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. `build-apk.sh` sources
them from `~/keystores/mindful-fork.env`.

> **The keystore is unrecoverable.** Android only lets an APK be replaced by one signed
> with the same key. Lose `~/keystores/` after installing and you cannot update — the only
> way out is uninstalling and losing the app's data. Back up both files.

The key differs from the Play Store build's, so this APK cannot upgrade over a Play Store
install of Mindful.

## Play Protect

A self-signed APK requesting `SYSTEM_ALERT_WINDOW` + `PACKAGE_USAGE_STATS` +
`QUERY_ALL_PACKAGES` + notification listener + accessibility looks, to a heuristic
scanner, exactly like stalkerware. Expect it to be flagged on first install — *More
details → Install anyway*, or `adb install`. Updates to an already-installed package
signed with the same key are much quieter.

On Android 13+, sideloaded apps also can't enable accessibility services until you allow
it: **Settings → Apps → Mindful → ⋮ → Allow restricted settings**. Optional —
`LaunchTrackingManager` falls back to polling `UsageStatsManager` every 750 ms — but
accessibility makes launch detection instant.

## Cooldown implementation

| File | Role |
|---|---|
| `helpers/storage/CooldownStore.kt` | Rolling 24h attempt timestamps, granted windows |
| `helpers/usages/CooldownUsageHelper.kt` | Time since the previous session |
| `res/layout/overlay_cooldown_layout.xml` | Both phases in one view |
| `services/tracking/RestrictionManager.kt` | `evaluateCooldown`, `grantCooldownWindow` |
| `services/tracking/OverlayBuilder.kt` | `buildCooldownOverlay`, breathing animation |
| `services/tracking/OverlayManager.kt` | `showCooldownOverlay` |
| `services/tracking/MindfulTrackerService.kt` | Routes cooldown states |

Non-obvious choices:

- **Attempts count only when the gate fires**, so app-switching inside a granted window
  doesn't inflate the number.
- **Granted windows are persisted**, so a tracker-service restart mid-window doesn't
  re-gate an app the user just chose to open.
- **Last-used comes from the most recent `ACTIVITY_PAUSED`**, not
  `UsageStats.lastTimeUsed` — the gate fires just *after* the app opened, so
  `lastTimeUsed` would always report "seconds ago".
- **Continuing re-invokes the launch event**, so evaluation re-runs, skips the gate, and
  app-timer reminders the gate pre-empted still get scheduled.
