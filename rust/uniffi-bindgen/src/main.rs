//! Host-only UniFFI Kotlin bindings generator for `gamesuite-sim`.
//!
//! Deliberately its OWN workspace package, separate from `gamesuite-sim`
//! itself: `uniffi`'s "cli" feature (needed for `uniffi_bindgen_main` below)
//! pulls in a fairly heavy dependency tree (askama, clap, goblin, ...) that
//! `gamesuite-sim`'s actual cdylib never needs at runtime. Keeping it here
//! means that tree is never part of `gamesuite-sim`'s own dependency graph at
//! all — in particular, it never gets cross-compiled for Android, which is
//! both wasted build time and (combined with this workspace's fat-LTO release
//! profile) was observed to OOM `rustc`/LLVM when it briefly *was* part of
//! that crate mid-development. See the project's Rust-core migration report
//! for the full story.
//!
//! Run (from the `rust/` workspace root, on the HOST toolchain — never
//! cross-compiled for Android):
//!
//!   cargo run --bin uniffi-bindgen -- generate --library <path-to-compiled-cdylib> \
//!       --language kotlin --out-dir <out-dir>
//!
//! "Library mode" (`--library`, not a `.udl` path) reads the `#[uniffi::export]`
//! metadata `gamesuite-sim` embeds directly in its compiled cdylib, so any
//! already-built `.so` for that crate works here — including a cross-compiled
//! Android one, since bindgen only parses the object file's metadata section,
//! it never executes it. See app/build.gradle.kts's
//! `generateAirHockeySimUniffiBindings` task for how the Gradle build invokes
//! this.
fn main() {
    uniffi::uniffi_bindgen_main()
}
