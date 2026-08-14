plugins {
    kotlin("jvm")
    // The compose plugin requires the compose-compiler plugin even though this
    // module currently only uses the Color type (no @Composable code yet).
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.8.2"
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // Only the graphics types (Color) — keeps the public API surface tiny.
    implementation(compose.ui)
}

kotlin {
    jvmToolchain(25)
}
