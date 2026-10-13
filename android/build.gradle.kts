plugins {
    id("com.android.application") version "9.0.1" apply false
    // Must match the Kotlin that AGP 9 bundles (2.2.10).
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
