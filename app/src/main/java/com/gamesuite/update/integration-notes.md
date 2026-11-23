# Updater integration notes (branch session/ws-updater)

## Shared-file edits made (all minimal)
* `app/build.gradle.kts`: `buildFeatures.buildConfig = true`; `debug`/`sideload`/`release` `SIDELOAD_UPDATES` fields; new
  `sideload` build type (initWith debug, not debuggable, `SIDELOAD_*` env signing, fallback debug key); `sideload`
  signing config block. `versionCode`/`versionName` lines untouched (cut-release.sh edits them with sed).
* `app/src/main/res/xml/network_security_config.xml` and `src/debug/.../network_security_config.xml`: a `domain-config`
  pinning the four GitHub hosts to HTTPS.
* `ui/profiles/ProfilesSettingsSection.kt`: one call at the end, `UpdateSettingsSection(isAdult = !active.isKid)`.
  MainActivity is NOT edited (the Settings `extraContent` slot already renders this section).
* New: `src/debug/AndroidManifest.xml`, `src/sideload/AndroidManifest.xml`, `res/xml/update_file_paths.xml`,
  `res/values{,-es}/update_strings.xml`, `.github/workflows/sideload.yml`, `scripts/setup-sideload-secrets.ps1`.

## Optional hub banner (not wired: GameHubSpecs/hub files are off limits)
Add to the hub screen where an adult profile is active:
`com.gamesuite.update.UpdateBanner(isAdult = !profile.isKid, onOpen = { navController.navigate("settings") })`.
It starts the controller, runs the throttled auto-check (only if the owner switched auto-check on) and shows when the
newest release in the channel is newer than installed and not dismissed.

## Done
Core (parser, tolerant), pagination + ETag, version selection, channels, URL allow-list + redirect rules, size caps,
SHA-256, APK package/version/signature gate, throttle + rate limit, downloader, controller, Android glue, Settings UI,
banner composable, EN+ES strings, workflow, secrets script, docs, JVM tests (see the testq `.result` files).

## Unverified (compiled/unit-tested only)
Workflow never run on GitHub; installer hand-off, unknown-apps screen, FileProvider and PackageManager signature reads
never exercised on a device; hub banner not placed; TalkBack/visual layout not checked on a device.
