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
