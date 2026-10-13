pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "lichen-android"
// core: the protocol in plain Kotlin/JVM (unit-testable on the PC, against the Python node).
// app: the Android app on top of it.
include(":core", ":app")
