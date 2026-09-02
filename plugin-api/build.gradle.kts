plugins {
    kotlin("jvm")
    // `api` rather than `implementation` below needs this: Color appears in the
    // public signatures of Palette and Ramp, so it has to reach consumers.
    `java-library`
    // The compose plugin requires the compose-compiler plugin even though this
    // module currently only uses the Color type (no @Composable code yet).
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

repositories {
    google()
    mavenCentral()
}

dependencies {
    // Only the graphics types (Color) — keeps the public API surface tiny.
    //
    // `api`, not `implementation`: Color is *in* this module's public API
    // (Palette's ramps, Ramp's shades), so a plugin depending on :plugin-api
    // alone must get it transitively. With `implementation` it did not, and the
    // two consumers we have only compiled because each brought compose.ui
    // itself — the app through compose.desktop, the sample through its own
    // compileOnly. A third plugin would have hit `unresolved reference: Color`.
    api(compose.ui)
}

kotlin {
    jvmToolchain(25)
}
