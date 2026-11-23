# Release signing

No key, keystore or password is ever committed. `app/build.gradle.kts` reads them at build time and the
build still works when none is present.

## How the build finds the key

In this order, per value:

1. `keystore.properties` at the repository root (gitignored, as are `*.jks` and `*.keystore`)
2. Environment variables

| keystore.properties key | Environment variable | Meaning |
| --- | --- | --- |
| `storeFile` | `GAMESUITE_KEYSTORE_FILE` | Path to the `.jks` (absolute, or relative to the repo root) |
| `storePassword` | `GAMESUITE_KEYSTORE_PASSWORD` | Keystore password |
| `keyAlias` | `GAMESUITE_KEY_ALIAS` | Key alias |
| `keyPassword` | `GAMESUITE_KEY_PASSWORD` | Key password |

All four must be present. If any is missing the release build type is **unsigned**:

- `:app:assembleRelease` produces `app-release-unsigned.apk`, `:app:bundleRelease` an unsigned `.aab`.
  Both are fine for checking R8 output and size; neither can be installed or uploaded.
- `-PdebugSignRelease=true` signs the local release build with the debug key so it can be sideloaded for a
  smoke test. Never upload a debug-signed build (Play rejects it).

## Verified facts (2026-11-22, branch session/ws-release)

`./gradlew :app:assembleRelease :app:bundleRelease` ran to BUILD SUCCESSFUL (6m48s cold-ish) with NO keystore and no
secrets present: R8 + resource shrinking ran, `lintVitalRelease` passed, and the outputs are **unsigned**:

| Artifact | Size |
| --- | --- |
| `app/build/outputs/apk/release/app-release-unsigned.apk` (universal, 3 ABIs) | 14.85 MB (14,849,678 B) |
| `app/build/outputs/bundle/release/app-release.aab` | 18.06 MB (18,057,649 B) |
| `app/build/outputs/mapping/release/mapping.txt` | 135.7 MB (upload to Play Console with the AAB) |

The `.aab` is larger than the APK because it holds every ABI and density split input; Play serves per-device
splits that are far smaller than either. Final DEX: one `classes.dex`, 54,446 method refs, 11,228 classes (limit
65,536 refs for a single dex, so ~11k of headroom; R8 reports the build still fits one dex).
`zipalign -c -P 16 -v 4` on the APK: all entries OK. The `-PdebugSignRelease=true` path and a keystore-signed
build were **not** run (no key, and nothing may be installed); the signing branch reads the same four values and
is trivial, but treat the first signed build as untested.

**R8 verification scope:** statically confirmed from `mapping.txt` that `com.sun.jna.{Native,Structure,Library}`,
`com.gamesuite.sim.*` and `com.gamesuite.audio.rust.*` are kept unrenamed and that the build has no missing-class
failure. NOT confirmed at runtime: no device or emulator was used, so launching a game from the minified build
(UniFFI/JNA calls, kotlinx.serialization of saved games, `getIdentifier` string lookups) is unverified. The first
step after this is a smoke run of a release (debug-signed via `-PdebugSignRelease=true`) build by someone allowed
to install it, playing at least one Rust-backed game (Air Hockey, ambient audio) and resuming a saved game.

## Generate an upload key (once)

Keep the keystore outside the repository (for example `C:\Users\<you>\keys\gamesuite-upload.jks`) and back it
up somewhere private. If you use Play App Signing (recommended, and the default for new apps), this is only
the *upload* key: Google holds the real app-signing key, and a lost upload key can be reset through Play
support. Without Play App Signing, losing this key means you can never update the app.

```
keytool -genkeypair -v -keystore C:\Users\<you>\keys\gamesuite-upload.jks -alias gamesuite-upload ^
  -keyalg RSA -keysize 4096 -validity 10000 -storetype PKCS12
```

(One line on macOS/Linux, with `~/keys/gamesuite-upload.jks` and no `^`.) `keytool` ships with the JDK 17
the project already uses. It prompts for the passwords and the certificate owner fields.

Print the fingerprint to register or verify it:

```
keytool -list -v -keystore C:\Users\<you>\keys\gamesuite-upload.jks -alias gamesuite-upload
```

## Use it

`keystore.properties` (never commit; use forward slashes or doubled backslashes):

```
storeFile=C:/Users/<you>/keys/gamesuite-upload.jks
storePassword=...
keyAlias=gamesuite-upload
keyPassword=...
```

Then:

```
./gradlew :app:bundleRelease        # app/build/outputs/bundle/release/app-release.aab  (upload this to Play)
./gradlew :app:assembleRelease      # app/build/outputs/apk/release/app-release.apk
```

In CI, store the four values as repository secrets, write the keystore from a base64 secret to a temp file
in the job, and export the four `GAMESUITE_*` variables for that step only. There is deliberately no CI
release job yet: it needs the owner's key and a decision on where uploads happen.

## Checks before the first upload

- `keystore.properties` and the `.jks` do not appear in `git status`.
- `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` shows the upload
  certificate (APK builds only; the `.aab` is signed with `jarsigner`, check with
  `keytool -printcert -jarfile app-release.aab`).
- Upload `mapping.txt` (`app/build/outputs/mapping/release/mapping.txt`) with each release so Play
  deobfuscates crash stacks. The bundle includes it automatically when built with the Play Gradle flow;
  uploading it by hand is the fallback.
