import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    // The Compose compiler ships with Kotlin, versioned to match it.
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    // Compose Multiplatform — the Skia (Skiko) desktop renderer.
    id("org.jetbrains.compose") version "1.8.2"
}

group = "org.bittrace"
version = "1.0-SNAPSHOT"

repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Public plugin API — shared with bundled and external plugins.
    implementation(project(":plugin-api"))
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
}

compose.desktop {
    application {
        mainClass = "org.bittrace.MainKt"

        // Skip ProGuard for the release build — kotlinx.serialization + Skiko
        // don't survive default minification without extra rules, and we want a
        // reliable production artifact.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            // Msi + Exe installers; `createDistributable` also yields a runnable
            // app image (BitTrace.exe with a bundled JRE) needing no installer.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "BitTrace"
            packageVersion = "1.0.0"
            description = "HTTP(S) traffic capture and inspection."
            vendor = "BitTrace"
            windows {
                menu = true
                shortcut = true
                // Stable UUID so MSI upgrades replace the prior install.
                upgradeUuid = "6f3d9b2e-1c7a-4e5b-9a3d-2b1c4e5f6a7b"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
