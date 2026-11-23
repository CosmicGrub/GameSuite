# Release build status (resume note)

Branch `session/ws-release`, last updated 2026-11-22. A fresh agent can resume from this file.

## Done and verified by a real build
- Release signing config reading gitignored `keystore.properties` or `GAMESUITE_*` env vars; no key => unsigned output, build does not fail.
- R8 + resource shrinking enabled (`isMinifyEnabled`, `isShrinkResources`); keep rules for JNA, UniFFI bindings (`com.gamesuite.sim`, `com.gamesuite.audio.rust`), plus the existing serialization/OkHttp/Nearby rules; `act_it_out_keep.xml` keeps by-name string resources (`act_it_out_card_*`, `fsh_fish_*`, `tip_*`, `hub_*`).
- `:app:assembleRelease` and `:app:bundleRelease` succeed on the merged tree (wave-3 + this branch). Sizes and DEX facts in SIGNING.md.
- `compileSdk` 35 for `:app`; targetSdk 34. Lint: no `lint {}` block, so `abortOnError` is the default (true); `lintVitalRelease` (fatal-only checks) ran and passed. Full `lintRelease` was NOT run, so non-fatal lint findings are unknown.
- 16 KB: zip alignment OK; Rust libs `Align 0x4000`; JNA 5.14.0 x86_64 `libjnidispatch.so` is 4 KB aligned (fails 16 KB check on x86_64).

## Open
- Runtime smoke test of the minified build (needs someone allowed to install; see SIGNING.md "R8 verification scope").
- Signed build never produced (needs the owner's key).
- JNA 5.17.0 bump for 16 KB x86_64 (not done: unverifiable version, needs RustPadSynthCorrectnessTest run).
- Edge-to-edge adoption, then targetSdk 35/36 (see TARGET-SDK.md). Play currently requires 35+ for new uploads: this blocks publishing.
- Full `lintRelease`, baseline.
- Owner steps below.

## Owner must do
1. Create the upload keystore (SIGNING.md, outside the repo), back it up, create `keystore.properties`.
2. Play Console: create the app, enroll in Play App Signing, upload `app-release.aab` and `mapping.txt`, complete Data safety (DATA-SAFETY.md), Content rating (CONTENT-RATING.md), target audience/Families (FAMILIES-CHECKLIST.md), store listing and screenshots (STORE-LISTING.md, SCREENSHOTS.md), and host the privacy policy at a public URL.
3. Decide the targetSdk 35/36 timeline (edge-to-edge device pass).

## Build machine notes
- `Z:\Android\Sdk` lacks platform 35; Gradle's auto-install leaves a broken stub. Use `sdk.dir=C:/Users/Obliv/android-sdk` in the worktree's gitignored `local.properties` (see TARGET-SDK.md).
- `rust/target` is copied from another worktree; `app/src/main/jniLibs` holds prebuilt .so files.
