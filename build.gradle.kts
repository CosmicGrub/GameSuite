plugins {
    id("com.android.application") version "8.6.0" apply false
    id("com.android.library") version "8.6.0" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    // Pinned to the last 1.7.x release, per the compatibility guide (Compose
    // Multiplatform 1.8.0+ requires Kotlin 2.1.0+ -- see docs/ENGINE_DECISION.md
    // Action Item 1). Do NOT bump this without also bumping every Kotlin version
    // above, and do not bump those without a real reason to -- they are shared
    // with the existing, shipping :app module.
    id("org.jetbrains.compose") version "1.7.3" apply false
}
