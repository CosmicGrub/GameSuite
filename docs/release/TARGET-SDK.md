# targetSdk / compileSdk: findings and plan

Status: **targetSdk stays at 34 in this branch; compileSdk of `:app` was raised to 35** (see below; `:shared`
stays at 34, which is fine because a library may be compiled against an older SDK than its consumer). The bump is not "safe and minimal" because the
app has no edge-to-edge convention, and Android 15 enforces edge-to-edge for apps that target 35. Everything
below was checked in the code at commit 3d69c97 plus the local SDK install.

## Play deadline

`docs/audit/05-quality-ops.md` records that Play requires new apps and updates to target API 35 (since August
2025) and API 36 from August 2026. Today is November 2026, so **treat 36 as the real bar** and confirm the
current number in Play Console (Policy status, "Target API level") before the first upload; this document could
not check Play's live policy page. Plan for 35 as the first step and 36 as the destination, since 36 removes
the temporary opt-outs.

## Toolchain compatibility

| Piece | Now | Needs for compileSdk 35 | Needs for 36 |
| --- | --- | --- | --- |
| AGP | 8.6.0 | 8.6.0 supports API 35 | AGP 8.9.1+ (8.9 added API 36 support) |
| Kotlin / KGP | 2.0.21 | fine | fine for 2.0.21, but AGP 8.9 raises the KMP warning level; Compose Multiplatform 1.7.3 is pinned to Kotlin 2.0.x (see `build.gradle.kts`) so a Kotlin bump means a Compose Multiplatform bump |
| Gradle | 8.13 (wrapper) | fine | fine (AGP 8.9 needs 8.11.1+) |
| Compose BOM | 2024.09.00 | fine | a newer BOM is advisable (edge-to-edge/insets fixes) |
| activity-compose | 1.9.3 | fine, has `enableEdgeToEdge()` (1.8+) | fine |
| core-ktx | 1.13.1 | fine | fine |
| SDK platforms installed on the maintainer machine | android-33..36, build-tools 34, 35, 36 | present | present |
| CI runner (`android.yml`) | installs `platforms;android-34 build-tools;34.0.0` | change to `android-35 build-tools;35.0.0` | `android-36 build-tools;36.0.0` |

So raising `compileSdk` to 35 alone needs no other change and has no runtime effect (compileSdk only changes
which APIs and lint checks are visible). It is safe to do together with the CI package update. It was not
done here because the release build could not be completed on the build machine (see the report: the system
drive ran out of space) and a compileSdk change should be verified by `:app:assembleDebug` plus the unit tests.

## Behaviour changes that matter at targetSdk 35

1. **Edge-to-edge is enforced** on Android 15 devices. Windows draw behind the status and navigation bars and
   `Theme.GameSuite` can no longer keep them opaque. Search results for this app:
   - No `enableEdgeToEdge`, `WindowCompat.setDecorFitsSystemWindows`, `safeDrawing`, `systemBarsPadding`,
     `navigationBarsPadding` or `WindowInsets` use anywhere except `arena/ArenaUi.kt`, which applies
     `statusBarsPadding()` in two places.
   - There is no `Scaffold` in the app. `MainActivity` sets Compose content directly.
   - No `windowOptOutEdgeToEdgeEnforcement` in the themes.
   So the app has **no inset convention**. Bumping would put top bars, game boards and bottom buttons under the
   system bars on Android 15 on every one of the ~80 game screens. That is a layout change for the whole app
   and needs a device pass; it is not a build-file change.
2. **Foreground services / exported components**: the manifest declares no services, receivers or providers of
   its own and only `MainActivity` (the launcher) is exported. The library merge adds androidx startup and
   Play Services entries; check the merged manifest after the next build
   (`app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`). Nothing
   in the app uses a foreground service, so the 34/35 foreground-service type rules do not apply.
3. **Bluetooth / Nearby**: the permission block already uses the API 31+ model
   (`BLUETOOTH_SCAN` with `neverForLocation`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`) and
   `NEARBY_WIFI_DEVICES` on 33+. No change is expected for 35, but run the Nearby join/host flow on a real
   device after the bump.
4. **16 KB pages** (see below).
5. Android 15 also adds: predictive back opt-in becomes default behaviour only when the manifest enables
   `enableOnBackInvokedCallback`; this app does not set it, so legacy back handling continues. Other 35
   changes (media projection, `BOOT_COMPLETED` limits, `dataSync` FGS timeout) do not touch this app.
6. At **36**: the edge-to-edge opt-out attribute is ignored, large-screen orientation/resizability
   restrictions are ignored (`MainActivity` already handles configuration changes itself and the app targets
   foldables and tablets, so this is low risk), and the predictive back system animations are on by default.

## 16 KB page size alignment of native libraries

Play requires 16 KB support for apps targeting 35+ (from November 2025). Three sets of native code:

1. **Rust libraries** (`libgamesuite_sim.so`, `libgamesuite_audio.so`): built with cargo-ndk 4.1.2 against
   NDK 28.2.13676358 (`resolveAndroidNdkHome()` and the `cargoNdk*` tasks in `app/build.gradle.kts`). NDK r28
   links with 16 KB max page size by default, so no `-C link-arg=-Wl,-z,max-page-size=16384` is needed. This
   **Measured** on the release APK built in this branch (`llvm-readelf -lW`, all `LOAD` segments): both
   `libgamesuite_sim.so` and `libgamesuite_audio.so` have `Align 0x4000` on arm64-v8a and x86_64, as do
   `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so`. No linker flag is needed. To re-check, for
   each ABI (arm64-v8a and x86_64 matter for 16 KB devices; armeabi-v7a is 32-bit and not subject to it):
   `llvm-readelf -lW app/src/main/jniLibs/arm64-v8a/libgamesuite_sim.so | grep LOAD` and confirm every
   `Align` column reads `0x4000`. The NDK ships `llvm-readelf` under `toolchains/llvm/prebuilt/*/bin`.
2. **JNA 5.14.0 (`@aar`)** bundles `libjnidispatch.so`. **Measured:** arm64-v8a `Align 0x10000` (64 KB, which is
   fine for 16 KB pages) but **x86_64 `Align 0x1000` (4 KB), which fails the 16 KB check** (armeabi-v7a is not
   subject to it). Play's pre-launch/Console check inspects x86_64 too, so this will be flagged for targetSdk 35+
   uploads unless the x86_64 ABI is dropped or JNA is upgraded. JNA **5.17.0** is the first release built with 16 KB alignment (release notes: "Android
   16 KB page size support"). Recommended change when targeting 35: `net.java.dev.jna:jna:5.17.0@aar` (and the
   test-only desktop jar to the same version). Version number is from memory of the JNA changelog; confirm it
   on Maven Central before changing, and re-run `RustPadSynthCorrectnessTest`.
3. **Play Services Nearby 18.7.0** ships no native code of its own (the code lives in Play Services).
   OkHttp and Compose have none.

Zip alignment **measured:** `zipalign -c -P 16 -v 4 app-release-unsigned.apk` reports every `.so` entry OK
(all ABIs, including `libjnidispatch.so`), so the container side is already fine; only the ELF segment alignment
of the x86_64 JNA library above is outstanding.

Packaging: AGP 8.6 stores `.so` files uncompressed and zip-aligns them to 16 KB when
`useLegacyPackaging` is false, which is the default for minSdk 23+ and is not overridden in the build file. The
bundle (`.aab`) is re-aligned by Play. After the first release build, run
`zipalign -c -P 16 -v 4 app-release.apk` to confirm.

## Recommended plan (in order)

1. Bump `compileSdk` to 35 and the CI SDK packages to android-35 / build-tools 35.0.0. Verify
   `:app:assembleDebug`, `:app:testDebugUnitTest`. No runtime change.
2. Bump JNA to 5.17.0 and verify the 16 KB alignment with the commands above on `libjnidispatch.so` too.
3. Adopt edge-to-edge deliberately **before** raising targetSdk, on API 34 where it is optional:
   call `enableEdgeToEdge()` in `MainActivity.onCreate`, then apply `WindowInsets.safeDrawing` (or
   `systemBars`/`displayCutout`) once in the shared game container (`uikit/GameChrome`) and the Hub/profile
   shells, so individual game screens inherit it. Games that draw a full-bleed board can opt into drawing
   behind the bars but must inset their controls. Test on Fold 5 inner/cover/tabletop and the tablet, with
   gesture and 3-button navigation. This is the bulk of the work (estimate 1 to 2 days plus the device pass).
4. Bump `targetSdk` to 35, then to 36 when AGP 8.9.1+ is adopted (this pulls a newer Gradle plugin set; keep
   Kotlin and Compose Multiplatform changes in their own change per the pin comment in `build.gradle.kts`).
   Temporary shortcut if the deadline bites before step 3 is finished: set
   `<item name="android:windowOptOutEdgeToEdgeEnforcement">true</item>` in a `values-v35` theme. It works for
   35 only and is ignored at 36, so it buys one release at most.
5. Re-run the Nearby and online-room flows on a real device. (This team did not touch any device.)
