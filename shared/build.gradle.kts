import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * The Kotlin Multiplatform pilot module described in docs/ENGINE_DECISION.md
 * (Action Item 1) -- proves that GameSuite's pure game-logic layer really is
 * portable, by moving one game (Tic-Tac-Toe, chosen for its small size and
 * this project's own prior familiarity with it) into a commonMain source set
 * shared between the existing Android app and a brand-new desktop target.
 *
 * Deliberately NOT an iOS target yet: per the ADR's own sequencing, iOS work
 * only starts once a macOS build host exists (Action Item 6), and nothing
 * about proving the Android/Desktop split needs one. Adding iOS here would
 * just be dead, unbuildable configuration until that prerequisite lands.
 *
 * Deliberately NOT yet consumed by the :app module -- this is a parallel,
 * additive pilot module with its OWN copy of TicTacToeGame.kt (and the small
 * set of core/transport/settings types it depends on), not a replacement for
 * the shipping Android game. Whether and when :app switches to depending on
 * this module instead of its own copy is Action Item 5 in the ADR -- an
 * explicit, later, game-by-game decision, not something this pilot presumes.
 */
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        // Matches :app's own compileOptions/jvmTarget (JavaVersion.VERSION_17 /
        // "17" -- see app/build.gradle.kts) rather than an arbitrary choice:
        // the two modules will eventually need to interoperate (ADR Action
        // Item 5), and a Kotlin/Java target mismatch fails the build outright
        // ("Inconsistent JVM-target compatibility... 1.8 and 11", hit and
        // fixed while standing this module up) rather than just warning.
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
            // Real JSON encode/decode (not just the @Serializable annotation, which
            // -core alone provides) for TicTacToeGame's own LAN/online network protocol
            // (TicTacToeNetMessage) -- moved here from being jvmMain-only once game logic
            // itself (not just LanMultiplayerTransport) needed it, matching :app's own
            // kotlinx-serialization-json:1.7.3 (app/build.gradle.kts) for version
            // consistency, and UnoGame.kt's own already-proven Json.encodeToString/
            // decodeFromString usage for its own network protocol.
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // Real JVM sockets (java.net.Socket/ServerSocket/DatagramSocket) for LAN local
        // multiplayer (docs/ENGINE_DECISION.md Action Item 8's "Desktop parity" scope)
        // aren't part of Kotlin's common stdlib -- java.net.* is JVM-only, same category
        // as java.lang.Math earlier in this module's history (see AirHockeyGame.kt's own
        // Math.random -> kotlin.random.Random fix). But androidTarget() and
        // jvm("desktop") are both genuine JVMs, so this one intermediate source set lets
        // that code be written exactly once instead of duplicated between androidMain and
        // desktopMain -- not created automatically by the default hierarchy template for
        // a custom-named jvm("desktop") target, so wired up explicitly here.
        val jvmMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // LanMultiplayerTransport's own real need beyond what commonMain now
                // already provides (kotlinx-serialization-json moved up to commonMain
                // above): coroutines for the accept/read/beacon loops. Wasn't previously
                // an explicit dependency anywhere in this project (transitively pulled in
                // via Compose/AndroidX lifecycle elsewhere), so pinned here to a version
                // confirmed compatible with Kotlin 2.0.21.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            }
        }
        androidMain.get().dependsOn(jvmMain)
        val desktopMain by getting {
            dependsOn(jvmMain)
            dependencies {
                implementation(compose.desktop.currentOs)
            }
        }

        // Mirrors jvmMain above, for LanMultiplayerTransport's own real socket tests --
        // written once here, run on both the androidTarget and jvm("desktop") test tasks,
        // same "one shared source, both targets" shape every ported game's commonTest
        // already has (this one just can't live in commonTest itself, for the same
        // java.net-is-JVM-only reason its production code lives in jvmMain, not commonMain).
        val jvmTest by creating {
            dependsOn(commonTest.get())
        }
        androidUnitTest.get().dependsOn(jvmTest)
        val desktopTest by getting {
            dependsOn(jvmTest)
        }
    }
}

android {
    namespace = "com.gamesuite.shared"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    // Must match the androidTarget jvmTarget above, or compileDebugKotlinAndroid
    // fails with "Inconsistent JVM-target compatibility" against javac's default
    // (1.8) -- see the comment on androidTarget for why 17 specifically.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

compose.desktop {
    application {
        mainClass = "MainKt"
    }
}
