pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
    // Plugin versions for every module, declared once. The build files name
    // these without a version; two of the three used to repeat the numbers,
    // which is exactly how a Kotlin and a Compose-compiler version drift out of
    // step with each other.
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
        id("org.jetbrains.compose") version "1.11.1"
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "BitTrace"

// Public plugin API (interfaces + value types) shared by the app and by plugins.
include(":plugin-api")
