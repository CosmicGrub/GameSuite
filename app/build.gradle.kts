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
    inputs.dir(simCrateDir.resolve("src"))
    inputs.file(simCrateDir.resolve("Cargo.toml"))
    inputs.file(simCrateDir.resolve("uniffi.toml"))
    inputs.file(rustDir.resolve("Cargo.toml"))
    outputs.dir(jniLibsDir)

    workingDir = simCrateDir
    environment("ANDROID_NDK_HOME", resolveAndroidNdkHome())
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "-P", "26", // matches app/build.gradle.kts' minSdk
        "-o", jniLibsDir.absolutePath,
        "build", "--release"
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

android.sourceSets.getByName("main") {
    // Kotlin sources are picked up from java.srcDirs too (standard Android
    // Kotlin plugin behavior) — avoids depending on KGP's own `kotlin.srcDir`
    // extension accessor, which has moved across AGP/KGP versions.
    java.srcDir(uniffiBindingsOutDir)
}

// preBuild covers the general case (unit tests, lint, IDE sync); the explicit
// compile/merge task matching below is belt-and-suspenders so this can't
// silently race a variant's own Kotlin compile or native-lib merge step on an
// AGP version where preBuild's ordering guarantee is looser than expected.
tasks.named("preBuild") { dependsOn(generateAirHockeySimUniffiBindings) }
tasks.matching { it.name.startsWith("compile") && it.name.contains("Kotlin") }
    .configureEach { dependsOn(generateAirHockeySimUniffiBindings) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoNdkBuildAirHockeySim) }
