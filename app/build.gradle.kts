plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.gamesuite"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gamesuite"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":shared"))
    // Required by UniFFI's generated Kotlin bindings (the gamesuite-sim Rust core,
    // see rust/gamesuite-sim and the cargo-ndk task wiring below) to load and call
    // into the native .so via a C ABI.
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.window:window:1.3.0")
    implementation("androidx.compose.material3.adaptive:adaptive:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // Nearby Connections — ad-hoc local multiplayer (Wi-Fi Direct/Bluetooth, no internet).
    // NOT the deprecated Nearby Messages API (com.google.android.gms.nearby.messages).
    implementation("com.google.android.gms:play-services-nearby:18.7.0")
    // OnlineTransport (roadmap item 12) — WebSocket client to server/'s relay. minSdk 26
    // predates android.net.http.HttpEngine/java.net.http (API 34+), so a small, well-
    // established library is the honest choice here rather than hand-rolling raw sockets.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    // Plain desktop JNA jar (not the `@aar` variant above, which only bundles Android-target
    // natives) -- RustPadSynthCorrectnessTest.kt (docs/RUST_AUDIO_CORE_PLAN.md Step 4) needs
    // JNA's own native jnidispatch bootstrap for THIS machine's host platform to load the
    // gamesuite_audio host build (see cargoBuildAmbientAudioHost below) from a plain JVM unit
    // test. Test-only: main/androidTest keep using the @aar artifact exclusively.
    testImplementation("net.java.dev.jna:jna:5.14.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    // Real device rotation (setOrientationLeft/Right/Natural) + hardware-level
    // navigation (pressBack, findObject) for the orientation/navigation E2E
    // sweep -- Compose's own test rule can drive clicks via the semantics
    // tree, but actual sensor-level rotation needs UiAutomator underneath it.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// ---------------------------------------------------------------------------
// Rust simulation core (rust/gamesuite-sim) build plumbing.
//
// Chose a plain `Exec`-task pipeline over the `mozilla/rust-android-gradle`
// plugin: cargo-ndk already does 100% of the NDK-toolchain wiring a plugin
// would otherwise provide (per-ABI clang paths, sysroot, linker flags), so
// the plugin's only remaining value here would be gradle-native task
// wrapping/caching — and a raw Exec task gets that too (see the `inputs`/
// `outputs` below) without adding a third-party plugin whose own version
// needs to line up with AGP 8.6.0 / Kotlin 2.0.21. Two tasks:
//   1) cargoNdkBuildAirHockeySim  — cross-compiles the cdylib for all three
//      required ABIs straight into app/src/main/jniLibs via cargo-ndk's own
//      `-o` jniLibs-layout support.
//   2) generateAirHockeySimUniffiBindings — runs the sibling rust/uniffi-bindgen
//      workspace crate's bin (UniFFI "library mode": reads the
//      #[uniffi::export] metadata out of one already-built .so, no .udl
//      file anywhere) to emit the generated Kotlin bindings, then wires that
//      output directory in as a Java/Kotlin source dir. uniffi-bindgen is a
//      separate crate from gamesuite-sim itself so its "cli"-feature
//      dependency tree never enters gamesuite-sim's own (Android-compiled)
//      build — see rust/uniffi-bindgen/src/main.rs.
// Both are wired into preBuild, every compileXxxKotlin task, and every
// mergeXxxJniLibFolders task so `:app:compileDebugKotlin` and
// `:app:assembleDebug` (any variant) always pick up a fresh native lib
// without a separate manual step.
// ---------------------------------------------------------------------------

val rustDir = rootProject.file("rust")
val simCrateDir = rustDir.resolve("gamesuite-sim")
val jniLibsDir = file("src/main/jniLibs")
val uniffiBindingsOutDir = layout.buildDirectory.dir("generated/source/uniffi/kotlin").get().asFile
// Canonical .so this module's other build outputs (bindings generation) read
// metadata from — arm64-v8a since that's what every physical test device in
// this project uses; bindgen only ever parses the object file, never runs it,
// so which built ABI this points at doesn't affect the generated Kotlin.
val referenceSharedLib = jniLibsDir.resolve("arm64-v8a/libgamesuite_sim.so")

/**
 * Resolves the Android NDK for cargo-ndk, independent of Gradle's own
 * `sdk.dir`/`local.properties` (cargo-ndk needs the NDK's clang toolchain,
 * not the Android SDK build-tools). Resolution order: an `androidNdkHome`
 * Gradle project property, the `ANDROID_NDK_HOME` env var, then this
 * machine's confirmed install — override either of the first two on a
 * different machine/CI runner instead of editing this fallback.
 */
fun resolveAndroidNdkHome(): String {
    val fromProperty = providers.gradleProperty("androidNdkHome").orNull
    val fromEnv = providers.environmentVariable("ANDROID_NDK_HOME").orNull
    val resolved = fromProperty ?: fromEnv ?: """C:\Users\Obliv\android-sdk\ndk\28.2.13676358"""
    val marker = File(resolved, "toolchains/llvm/prebuilt")
    check(marker.isDirectory) {
        "Android NDK not found/incomplete at '$resolved' (expected toolchains/llvm/prebuilt under it). " +
            "Set -PandroidNdkHome=<path> or the ANDROID_NDK_HOME env var to a valid NDK install " +
            "(cargo-ndk needs the NDK's own clang toolchain; this is independent of local.properties' sdk.dir)."
    }
    return resolved
}

val cargoNdkBuildAirHockeySim = tasks.register<Exec>("cargoNdkBuildAirHockeySim") {
    group = "rust"
    description = "Cross-compiles rust/gamesuite-sim to arm64-v8a/armeabi-v7a/x86_64 .so files via cargo-ndk."

    // Rebuild only when the crate's own sources/manifests change — cargo's own
    // incremental cache (rust/target) makes a no-op invocation fast regardless,
    // but this lets Gradle skip shelling out to cargo at all when nothing moved.
    //
    // Cargo.lock is a real Gradle input, not just Cargo.toml/uniffi.toml -- without it, a
    // `cargo update` that bumps a transitive dependency (touching only the lockfile, not any
    // declared input above) would leave this task marked UP-TO-DATE on the next build,
    // silently shipping a native .so built against stale dependency versions. Found by an
    // audit of this freshly-committed build script.
    inputs.dir(simCrateDir.resolve("src"))
    inputs.file(simCrateDir.resolve("Cargo.toml"))
    inputs.file(simCrateDir.resolve("uniffi.toml"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))

    // Exec's own commandLine()/environment() values aren't part of Gradle's up-to-date
    // snapshot by default -- registered explicitly as inputs.property(...) here so changing
    // the ABI list, the platform level, or which NDK gets resolved genuinely invalidates the
    // cache too, not just source/manifest changes. Same audit finding as Cargo.lock above.
    val ndkHome = resolveAndroidNdkHome()
    val cargoNdkTargets = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    val cargoNdkPlatform = "26" // matches app/build.gradle.kts' minSdk
    inputs.property("androidNdkHome", ndkHome)
    inputs.property("cargoNdkTargets", cargoNdkTargets)
    inputs.property("cargoNdkPlatform", cargoNdkPlatform)

    // Declares only the specific .so files THIS task writes, not the whole shared jniLibsDir --
    // cargoNdkBuildAmbientAudio (below) writes its own differently-named .so's into the same
    // per-ABI directories (both native libs must live under one jniLibs tree for Android's
    // packaging to find them), and declaring the shared parent dir as this task's own output
    // makes Gradle treat every other task reading anywhere under it as having an undeclared
    // dependency on this one -- exactly the "implicit dependency" validation error this caused
    // in practice once a second cargoNdkBuild* task existed alongside this one.
    outputs.files(cargoNdkTargets.map { jniLibsDir.resolve("$it/libgamesuite_sim.so") })

    workingDir = simCrateDir
    environment("ANDROID_NDK_HOME", ndkHome)
    commandLine(
        listOf("cargo", "ndk") +
            cargoNdkTargets.flatMap { listOf("-t", it) } +
            listOf("-P", cargoNdkPlatform, "-o", jniLibsDir.absolutePath, "build", "--release")
    )
}

val generateAirHockeySimUniffiBindings = tasks.register<Exec>("generateAirHockeySimUniffiBindings") {
    group = "rust"
    description = "Generates the UniFFI Kotlin bindings for gamesuite-sim from the compiled cdylib (library mode, no .udl)."
    dependsOn(cargoNdkBuildAirHockeySim)

    inputs.file(referenceSharedLib)
    inputs.file(simCrateDir.resolve("uniffi.toml"))
    inputs.dir(rustDir.resolve("uniffi-bindgen/src"))
    inputs.file(rustDir.resolve("uniffi-bindgen/Cargo.toml"))
    outputs.dir(uniffiBindingsOutDir)

    // Runs from the workspace root, not simCrateDir: `uniffi-bindgen` is its own sibling
    // workspace crate (rust/uniffi-bindgen), kept separate so its "cli"-feature dependency
    // tree never enters gamesuite-sim's own build — see that crate's src/main.rs.
    workingDir = rustDir
    doFirst { uniffiBindingsOutDir.mkdirs() }
    commandLine(
        "cargo", "run", "--bin", "uniffi-bindgen", "--",
        "generate",
        "--library", referenceSharedLib.absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiBindingsOutDir.absolutePath
    )
}

// ---------------------------------------------------------------------------
// Rust audio DSP core (rust/gamesuite-audio) build plumbing — Step 1 of
// docs/RUST_AUDIO_CORE_PLAN.md. Structurally identical Exec-task pair to the
// gamesuite-sim wiring above (cargoNdkBuildAirHockeySim /
// generateAirHockeySimUniffiBindings): cargo-ndk cross-compiles the cdylib
// into the SAME app/src/main/jniLibs tree (both native libs must land under
// the same per-ABI jniLibs/<abi>/ directories for Android's packaging to pick
// both up), and the shared rust/uniffi-bindgen binary (see that crate's own
// src/main.rs — deliberately generic, reads whatever --library <path> it's
// pointed at) generates this crate's Kotlin bindings into their own output
// dir, kept separate from the sim crate's so the two generated-source trees
// never collide.
// ---------------------------------------------------------------------------

val audioCrateDir = rustDir.resolve("gamesuite-audio")
val uniffiAudioBindingsOutDir = layout.buildDirectory.dir("generated/source/uniffi-audio/kotlin").get().asFile
// Same rationale as referenceSharedLib above: arm64-v8a is what every
// physical test device in this project uses; bindgen only parses the .so's
// metadata section, never executes it, so the specific ABI doesn't affect
// the generated Kotlin.
val referenceAudioSharedLib = jniLibsDir.resolve("arm64-v8a/libgamesuite_audio.so")

val cargoNdkBuildAmbientAudio = tasks.register<Exec>("cargoNdkBuildAmbientAudio") {
    group = "rust"
    description = "Cross-compiles rust/gamesuite-audio to arm64-v8a/armeabi-v7a/x86_64 .so files via cargo-ndk."

    // Same Cargo.lock-as-input / inputs.property(...) coverage as
    // cargoNdkBuildAirHockeySim above (see that task's own comments for the
    // full rationale) — included here from day one, not a fix-later gap.
    inputs.dir(audioCrateDir.resolve("src"))
    inputs.file(audioCrateDir.resolve("Cargo.toml"))
    inputs.file(audioCrateDir.resolve("uniffi.toml"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))

    val ndkHome = resolveAndroidNdkHome()
    val cargoNdkTargets = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
    val cargoNdkPlatform = "26" // matches app/build.gradle.kts' minSdk
    inputs.property("androidNdkHome", ndkHome)
    inputs.property("cargoNdkTargets", cargoNdkTargets)
    inputs.property("cargoNdkPlatform", cargoNdkPlatform)

    // Same non-overlapping-outputs reasoning as cargoNdkBuildAirHockeySim's own outputs.files(...)
    // above -- declares only this task's own .so's, not the whole shared jniLibsDir.
    outputs.files(cargoNdkTargets.map { jniLibsDir.resolve("$it/libgamesuite_audio.so") })

    workingDir = audioCrateDir
    environment("ANDROID_NDK_HOME", ndkHome)
    commandLine(
        listOf("cargo", "ndk") +
            cargoNdkTargets.flatMap { listOf("-t", it) } +
            listOf("-P", cargoNdkPlatform, "-o", jniLibsDir.absolutePath, "build", "--release")
    )
}

val generateAmbientAudioUniffiBindings = tasks.register<Exec>("generateAmbientAudioUniffiBindings") {
    group = "rust"
    description = "Generates the UniFFI Kotlin bindings for gamesuite-audio from the compiled cdylib (library mode, no .udl)."
    dependsOn(cargoNdkBuildAmbientAudio)

    inputs.file(referenceAudioSharedLib)
    inputs.file(audioCrateDir.resolve("uniffi.toml"))
    inputs.dir(rustDir.resolve("uniffi-bindgen/src"))
    inputs.file(rustDir.resolve("uniffi-bindgen/Cargo.toml"))
    outputs.dir(uniffiAudioBindingsOutDir)

    // Runs from the workspace root, same as generateAirHockeySimUniffiBindings —
    // uniffi-bindgen is the one shared sibling crate both cdylibs point at.
    workingDir = rustDir
    doFirst { uniffiAudioBindingsOutDir.mkdirs() }
    commandLine(
        "cargo", "run", "--bin", "uniffi-bindgen", "--",
        "generate",
        "--library", referenceAudioSharedLib.absolutePath,
        "--language", "kotlin",
        "--out-dir", uniffiAudioBindingsOutDir.absolutePath
    )
}

// ---------------------------------------------------------------------------
// Host-native build of rust/gamesuite-audio for JVM unit tests
// (docs/RUST_AUDIO_CORE_PLAN.md Step 4). cargoNdkBuildAmbientAudio above
// cross-compiles ONLY Android-target .so's (arm64-v8a/armeabi-v7a/
// x86_64-linux-android, all built against Android's own Bionic libc) --
// none of which a plain JVM unit test running on this machine's host JVM
// can load. RustPadSynthCorrectnessTest.kt needs to call the REAL Rust DSP
// from a JVM unit test to do its actual job (diffing real output against
// Kotlin's PadSynthState, not a stand-in), so this task instead does a
// plain `cargo build --release` -- no cargo-ndk, no `-t`/`-P` -- producing a
// native library for whatever platform Gradle itself is running on (a
// `.dll` here, since local dev/test on this project runs on Windows)
// straight into the `rust/target/release` directory cargo already uses for
// ordinary host builds. Wired only into Test tasks -- the app's own
// debug/release builds never need this host artifact, only local unit
// tests do.
// ---------------------------------------------------------------------------

/** `<name>.dll` / `lib<name>.so` / `lib<name>.dylib` depending on what platform Gradle itself is
 *  running on -- `crate-type = ["cdylib"]` produces the platform-native shared-library naming
 *  convention automatically, this just has to know which one to look for. */
fun hostCdylibFileName(baseName: String): String {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("win") -> "$baseName.dll"
        osName.contains("mac") -> "lib$baseName.dylib"
        else -> "lib$baseName.so"
    }
}

val hostAudioSharedLib = rustDir.resolve("target/release/${hostCdylibFileName("gamesuite_audio")}")

val cargoBuildAmbientAudioHost = tasks.register<Exec>("cargoBuildAmbientAudioHost") {
    group = "rust"
    description = "Builds rust/gamesuite-audio for this machine's own host platform (not Android) " +
        "so RustPadSynthCorrectnessTest.kt's JVM unit test can load the real native DSP via UniFFI/JNA."

    inputs.dir(audioCrateDir.resolve("src"))
    inputs.file(audioCrateDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    inputs.file(rustDir.resolve("Cargo.lock"))
    outputs.file(hostAudioSharedLib)

    workingDir = audioCrateDir
    commandLine("cargo", "build", "--release")
}

/** `findLibraryName(componentName)` in the generated Kotlin bindings
 *  (`app/build/generated/source/uniffi-audio/kotlin/.../gamesuite_audio.kt`) checks exactly this
 *  system property before falling back to a bare `"gamesuite_audio"` library-name search -- see
 *  that generated file's own `findLibraryName` function. Pointing it straight at the host .dll's
 *  absolute path sidesteps relying on JNA's own `jna.library.path` search order working out. */
val uniffiAudioLibraryOverrideProperty = "uniffi.component.gamesuite_audio.libraryOverride"

tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildAmbientAudioHost)
    systemProperty(uniffiAudioLibraryOverrideProperty, hostAudioSharedLib.absolutePath)
}

android.sourceSets.getByName("main") {
    // Kotlin sources are picked up from java.srcDirs too (standard Android
    // Kotlin plugin behavior) — avoids depending on KGP's own `kotlin.srcDir`
    // extension accessor, which has moved across AGP/KGP versions.
    java.srcDir(uniffiBindingsOutDir)
    java.srcDir(uniffiAudioBindingsOutDir)
}

// preBuild covers the general case (unit tests, lint, IDE sync); the explicit
// compile/merge task matching below is belt-and-suspenders so this can't
// silently race a variant's own Kotlin compile or native-lib merge step on an
// AGP version where preBuild's ordering guarantee is looser than expected.
//
// Both crates' generate*/cargoNdkBuild* tasks are listed explicitly in each
// dependsOn(...) below — the tasks.matching{...} predicates only pattern-match
// the CONSUMING task's name (compileDebugKotlin, mergeDebugJniLibFolders,
// etc.), not a second producer task; adding a new crate's tasks doesn't wire
// them in automatically; each call site needs the new task added by hand.
tasks.named("preBuild") {
    dependsOn(generateAirHockeySimUniffiBindings, generateAmbientAudioUniffiBindings)
}
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
    .configureEach { dependsOn(generateAirHockeySimUniffiBindings, generateAmbientAudioUniffiBindings) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoNdkBuildAirHockeySim, cargoNdkBuildAmbientAudio) }
