# Sideload updates: update the app straight from GitHub

## The 4 steps (owner)

1. **Run the script once.** In PowerShell, from the repo folder:
   `powershell -ExecutionPolicy Bypass -File scripts\setup-sideload-secrets.ps1`
   It uploads your signing key to the GitHub repository secrets (details below). You never paste a password into
   chat or a file. Safe to re-run.
2. **Run the workflow.** Normally you do nothing: cutting a release (`scripts/cut-release.sh`) pushes a tag
   `wave3-<versionCode>-<shortsha>` and that tag starts the build. To build a tag by hand:
   `gh workflow run sideload.yml --repo CosmicGrub/GameSuite --ref session/wave-3 -f tag=wave3-12-abc1234`
3. **Open Settings > App updates** in GameSuite (adult profile).
4. **Tap Update.** The app checks GitHub, downloads the newest build, verifies it and opens Android's installer.
   You confirm in Android's own screen. The first time only, Android asks you to allow "Install unknown apps" for
   GameSuite; the app walks you through it and continues by itself when you come back.

## What exists where

| Piece | Where |
| --- | --- |
| Update code (JVM-tested core + Android glue + UI) | `app/src/main/java/com/gamesuite/update/` |
| Settings entry | one line in `ui/profiles/ProfilesSettingsSection.kt` (`UpdateSettingsSection`) |
| Feature flag | `BuildConfig.SIDELOAD_UPDATES` (true: `debug`, `sideload`; false: Play `release`) |
| Install permission + FileProvider | `app/src/debug/AndroidManifest.xml`, `app/src/sideload/AndroidManifest.xml` only |
| CI build + publish | `.github/workflows/sideload.yml` |
| Secrets script | `scripts/setup-sideload-secrets.ps1` |

## How the app finds updates

* Lists **every** release from `https://api.github.com/repos/CosmicGrub/GameSuite/releases` (all pages, ETag
  revalidation, never `/releases/latest`), both channels. Each release shows notes, size, date and a Stable/Preview badge.
* Version = the `versionCode` in the tag (`wave3-<versionCode>-<shortsha>`), or in the asset name
  `GameSuite-sideload-<versionCode>-<shortsha>.apk`. Plain semver tags (`v2.0.4`) are compared with the installed
  versionName. A release is offered only if its versionCode is **greater** than the installed one. Older builds are
  listed for reference; installing them needs the explicit "Show older builds and reinstall help" switch, and Android
  will still refuse a downgrade (it needs an uninstall, which erases saved data; the app never uninstalls anything).
* Channels: **Stable** = non-prerelease only; **Preview** (default in sideload builds) = everything, including the
  `wave3-*` prereleases. The channel decides the badge count and which build "Update" installs; any listed newer
  build can still be installed by name.
* Automatic checks are **off by default**, at most once per 6 hours when on, and stop while GitHub's rate limit window
  is open. "Check now" works any time.

## Security rules enforced in code (all unit-tested without a device)

* HTTPS only, hosts limited to `api.github.com`, `github.com`, `objects.githubusercontent.com`,
  `release-assets.githubusercontent.com`; a redirect to anything else (or to http) is refused; at most 5 redirects.
  The network security config also pins those four hosts to HTTPS.
* Asset links must point into `CosmicGrub/GameSuite/releases/download/`.
* No tokens, no login, no personal data: a generic `User-Agent`, no cookies, no Authorization header.
* Connect timeout 10 s, read timeout 20 s; JSON responses capped at 4 MB; APK capped at 250 MB (declared size,
  `Content-Length` and bytes actually streamed are all checked).
* SHA-256 of the APK must match the `<apk>.sha256` asset (or a `sha256:` line in the release notes). No checksum =
  not installed.
* Before the installer opens, `PackageManager.getPackageArchiveInfo` must show: **same package name**, **higher
  versionCode**, and the **same signing certificate** as the installed app. Otherwise the file is deleted and the app
  explains why. Stale downloads are also deleted at app start and before each new download.

## Signing key: why step 1 matters

Android only updates an app in place if the new APK is signed with the **same key** as the installed one. The CI
runner has no copy of your key. Without the four secrets the workflow signs with a throwaway CI debug key, the release
notes carry a loud warning, and the app will refuse that build ("signed with a different key") instead of failing in
the installer. With the secrets, GitHub builds are signed with your key and update over your existing install.

`scripts/setup-sideload-secrets.ps1` (you run it; Claude never does and never sees a keystore or password):

* default: uses `%USERPROFILE%\.android\debug.keystore` (alias `androiddebugkey`, passwords `android`), asks nothing;
* other key: `-KeystorePath <file> -Alias <alias>`, then it asks for the passwords (hidden input);
* checks `gh auth status`, base64-encodes the keystore **in memory** and sends it, and the three other values, to
  `gh secret set ... --repo CosmicGrub/GameSuite` through standard input (nothing printed or written to disk);
* prints the certificate SHA-256 fingerprint (public) so you can compare it with the **Signing certificate SHA-256**
  line in the release notes after the first build;
* manual alternative in PowerShell, if you prefer:
  `[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\.android\debug.keystore")) | gh secret set SIDELOAD_KEYSTORE_BASE64 --repo CosmicGrub/GameSuite`
  (then set `SIDELOAD_KEYSTORE_PASSWORD`, `SIDELOAD_KEY_ALIAS`, `SIDELOAD_KEY_PASSWORD` the same way).

If the app installed on your phone was signed with a different key than the one you upload (for example a build from
another PC), the first GitHub build will be refused with the signature message. The only way to switch keys is to
uninstall GameSuite once (this erases saved data) and install the GitHub build.

## Release flow (CI)

1. `scripts/cut-release.sh` (on the primary worktree, after a green gate) bumps `versionCode` (+1) and `versionName`
   (patch +1) in `app/build.gradle.kts`, commits, creates the annotated tag `wave3-<versionCode>-<shortsha>`, pushes
   commit and tag. Those two lines must stay single-line (`versionCode = N`, `versionName = "x.y.z"` in `defaultConfig`).
2. The tag triggers `sideload.yml`: it checks out **that existing tag**, verifies the tag's versionCode equals the one
   in `build.gradle.kts`, builds `:app:assembleSideload` (same JDK 17 / Android SDK / NDK 28.2.13676358 / Rust targets /
   cargo-ndk 4.1.2 setup as `android.yml`), names the file `GameSuite-sideload-<versionCode>-<shortsha>.apk`, writes
   `<name>.sha256`, and publishes a GitHub **prerelease** on the existing tag (never creates or moves a tag). The release
   notes include the signing key used, the signing certificate SHA-256 and a `sha256:` line.
3. It then keeps only the newest 5 `wave3-*` prereleases (tags stay).
4. Re-running the workflow for the same tag replaces the files on that release.

`permissions: contents: write` is used only in `sideload.yml`.

## Play release exclusion

Google Play forbids an app that updates itself outside Play. The Play `release` build type therefore has
`SIDELOAD_UPDATES = false`, **no** `REQUEST_INSTALL_PACKAGES` permission and **no** update FileProvider (both live only
in the `debug` and `sideload` source sets), and the Settings section and banner draw nothing. `SideloadGateTest` checks
the flag values in `build.gradle.kts` and the manifests; do not move the permission into `src/main`. Resources and
code of the updater are present in the release source set but unreachable and removed by R8/resource shrinking. Kid
profiles never see the section or banner, in any build.

## Builds and version codes

* Local debug builds use whatever `versionCode` is in `build.gradle.kts`. A GitHub build with a lower code cannot be
  installed over a newer local one (Android blocks downgrades).
* `sideload` build type: not debuggable, not minified, signed with the `SIDELOAD_*` key when present, else the debug key.
  Local try: `./gradlew :app:assembleSideload`.

## Not verified

The workflow has not been run on GitHub, and the install hand-off, the "Install unknown apps" screen and the signature
gate have not been exercised on a device (no device access by rule). The logic around them is unit-tested with fakes.
