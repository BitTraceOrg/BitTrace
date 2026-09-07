import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// Versions live in settings.gradle.kts, so every module moves together. The
// Compose compiler ships with Kotlin and is versioned to match it; Compose
// Multiplatform — the Skia (Skiko) desktop renderer — is pinned to what Jewel
// is built against (see jewelVersion below).
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

group = "org.bittrace"
version = "0.1.1-SNAPSHOT"

// The version the *shipped* artifacts carry, which is not `version` above:
// jpackage rejects a `-SNAPSHOT` suffix, and the MSI upgrade rules need a plain
// `major.minor.patch`. Declared here so the installer version and the names of
// the files in `build/dist` cannot drift apart.
val appVersion = "0.1.1"

// Jewel's standalone artifacts are versioned `<jewel>-<intellij-build>`; the
// platform icons live in a separate repository on their own build numbers, and
// the nearest published one to Jewel's is what we align everything onto.
val jewelVersion = "0.39.1-262.9437.29"
val intellijIconsVersion = "262.9437.185"
val kodeMirrorVersion = "0.3.6"
val jgitVersion = "7.1.0.202411261347-r"

repositories {
    google()
    mavenCentral()
    // `com.jetbrains.intellij.platform:icons` (AllIconsKeys) is published here
    // only — icons-api/icons-impl are on Central, the icon *keys* are not.
    maven("https://www.jetbrains.com/intellij-repository/releases")
}

dependencies {
    // Jewel is a foundation-only toolkit; excluding material keeps the "no
    // Material dependency" rule enforced by the build rather than by convention.
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }
    // Jewel — JetBrains' Compose Desktop implementation of the IntelliJ Int UI.
    // Standard controls come from here; see ui/JewelBridge.kt for the theming.
    implementation("org.jetbrains.jewel:jewel-int-ui-standalone:$jewelVersion")
    implementation("org.jetbrains.jewel:jewel-int-ui-decorated-window:$jewelVersion")
    implementation("com.jetbrains.intellij.platform:icons:$intellijIconsVersion")
    // KodeMirror — a native Kotlin port of CodeMirror 6 (no WebView, no JS
    // bridge). It brings the editor machinery this app had hand-rolled: line
    // numbers, find, completion, folding, and a viewport that does not lay out
    // the whole document. Built against Compose 1.11.1, the version Jewel pins,
    // so the two agree without forcing anything.
    implementation(platform("com.monkopedia.kodemirror:kodemirror-bom:$kodeMirrorVersion"))
    implementation("com.monkopedia.kodemirror:view")
    implementation("com.monkopedia.kodemirror:state")
    implementation("com.monkopedia.kodemirror:language")
    // Arrives transitively through `language`, but the app names `Tags` itself
    // in `ui/editor/EditorHighlight.kt`, and a transitive dependency is on the
    // runtime classpath only.
    implementation("com.monkopedia.kodemirror:lezer-highlight")
    implementation("com.monkopedia.kodemirror:commands")
    implementation("com.monkopedia.kodemirror:basic-setup")
    implementation("com.monkopedia.kodemirror:autocomplete")
    implementation("com.monkopedia.kodemirror:search")
    // The languages this app actually shows. GraphQL is not among them upstream
    // and is defined in `ui/editor/GraphQlLanguage.kt`.
    implementation("com.monkopedia.kodemirror:lang-json")
    implementation("com.monkopedia.kodemirror:lang-xml")
    implementation("com.monkopedia.kodemirror:lang-html")
    implementation("com.monkopedia.kodemirror:lang-javascript")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Streaming JSON for HAR import/export. jackson-core only (no databind):
    // kotlinx-serialization cannot decode lazily inside an object, and HAR
    // entries are nested at log.entries. NB: Jackson 3.x moved to the group
    // tools.jackson.core — this is the 2.x API.
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.2")
    // YAML for API-client collections, on kotlinx-serialization so request
    // models stay @Serializable like Settings and the HAR types.
    implementation("com.charleskorn.kaml:kaml:0.104.0")
    // Git, for API-client projects. JGit rather than shelling out to `git`, so
    // the feature works on a machine that has no git installed.
    //
    // `.http.apache` is deliberately absent: core's own `TransportHttp` runs on
    // `HttpURLConnection`, and the Apache stack only adds NTLM and proxy auth
    // this app has no use for. `.ssh.apache.agent` is what makes SSH remotes
    // painless on Windows — it reaches the OpenSSH agent and Pageant through
    // JNA, which is most of the size and worth it. `eddsa` is explicit because
    // ed25519 is what `ssh-keygen` produces by default now, and without it key
    // loading fails with "no such algorithm" rather than anything diagnosable.
    //
    // Note also what is *not* here: BouncyCastle. Its absence means an encrypted
    // classic-PEM key cannot be read, which is a deliberate trade — the app
    // never asks for a key passphrase, and the answer is ssh-agent.
    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:$jgitVersion")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache.agent:$jgitVersion")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    // JGit logs through slf4j and nothing else on the classpath binds it, so
    // without a provider every run opens with a "failed to load class" notice
    // on stderr. Nothing here wants JGit's internal logging.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    // Public plugin API — shared with bundled and external plugins.
    implementation(project(":plugin-api"))
    testImplementation(kotlin("test"))

    // Coroutines arrive transitively from Compose (1.9.0) and, forked, from the
    // platform icons (1.10.2-intellij-1). Pin upstream at the higher of the two
    // so the collapse below cannot land on an API the icons were built against.
    // Stay under 1.11.0: Jewel's release notes report packaged apps crashing there.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // The platform icons pull IntelliJ's coroutines fork, which would otherwise
    // sit on the classpath beside upstream's — two copies of the same packages,
    // and a NoSuchMethodError in the packaged app. Collapse them onto upstream.
    modules {
        module("org.jetbrains.intellij.deps.kotlinx:kotlinx-coroutines-core-jvm") {
            replacedBy("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm", "The IJP fork lags upstream")
        }
    }
}

// Jewel pulls icons-api/icons-impl at its own platform build; the icon *keys*
// artifact is published on a different one. Force all three together — mixing
// builds resolves but fails at runtime looking up icon paths.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.jetbrains.intellij.platform" &&
            requested.name.startsWith("icons")
        ) {
            useVersion(intellijIconsVersion)
        }
    }
}

kotlin {
    jvmToolchain(25)
}

/**
 * Jewel's `DecoratedWindow` throws outright on any JVM that is not the JetBrains
 * Runtime, so both `run` and the packaged app need a JBR — not the toolchain JDK.
 *
 * Resolution order: `-PjbrHome=`, `$JBR_HOME`, then the JBR bundled with a local
 * JetBrains IDE. Note that IDE-bundled JBRs are built `-nomod`: they run the app
 * fine but carry no `jmods`, so `createDistributable` needs a `jbrsdk` build.
 */
val jbrHome: String? =
    (findProperty("jbrHome") as String?)
        ?: System.getenv("JBR_HOME")
        ?: file("C:/Program Files/JetBrains")
            .listFiles()
            .orEmpty()
            .map { File(it, "jbr") }
            .filter { File(it, "bin/java.exe").exists() }
            // A JBR carrying jmods can also package, so prefer one; otherwise
            // take the newest. Sort on the runtime's own JAVA_VERSION — IDE
            // folder names order alphabetically, not chronologically.
            .sortedWith(
                compareBy<File> { File(it, "jmods").isDirectory }
                    .thenBy { jbr ->
                        File(jbr, "release")
                            .takeIf { it.exists() }
                            ?.readLines()
                            ?.firstOrNull { it.startsWith("JAVA_VERSION=") }
                            ?.substringAfter('=')?.trim('"', ' ')
                            ?.split('.')?.firstOrNull()?.toIntOrNull()
                            ?: 0
                    }
            )
            .lastOrNull()
            ?.absolutePath

compose.desktop {
    application {
        mainClass = "org.bittrace.MainKt"

        // Both `run` and the runtime image are built from this JDK.
        jbrHome?.let { javaHome = it }

        // Skip ProGuard for the release build — kotlinx.serialization + Skiko
        // don't survive default minification without extra rules, and we want a
        // reliable production artifact.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            // JGit, Apache MINA sshd and JNA reach for JDK modules nothing else
            // in the app references, and jlink builds the runtime image from the
            // modules it is told about. Left out, they fail *only* in the
            // packaged app and *only* on the transport path — `gradlew run` uses
            // the whole JBR and never shows it.
            //
            // The first seven come from asking jdeps directly:
            //
            //   jdeps --multi-release 24 --ignore-missing-deps --list-deps             //       <jgit, sshd and jna jars from the runtime classpath>
            //
            // which is the same question `suggestRuntimeModules` asks, minus its
            // need for a packaging-capable JBR. The last two are there *because*
            // jdeps cannot see them: both are reached through ServiceLoader and
            // reflection, so no bytecode names them and static analysis reports
            // nothing. `jdk.crypto.ec` supplies EC key exchange and the
            // `ecdsa-sha2-nistp256` keys most SSH remotes negotiate;
            // `jdk.unsupported` is JNA's `sun.misc.Unsafe`.
            modules(
                "java.logging",
                "java.management",
                "java.naming",
                "java.rmi",
                "java.security.jgss",
                "java.sql",
                "java.xml",
                "jdk.crypto.ec",
                "jdk.unsupported",
            )
            // Msi + Exe installers; `createDistributable` also yields a runnable
            // app image (BitTrace.exe with a bundled JRE) needing no installer.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "BitTrace"
            packageVersion = appVersion
            description = "HTTP(S) traffic capture and inspection."
            vendor = "BitTrace"
            windows {
                menu = true
                shortcut = true
                // Six sizes in the one file, 16 through 256: Explorer, the task
                // bar, Alt-Tab and the installer each reach for a different one,
                // and a single-size icon gets scaled into mush by whichever of
                // them misses.
                iconFile.set(project.file("packaging/icon.ico"))
                // Stable UUID so MSI upgrades replace the prior install.
                upgradeUuid = "6f3d9b2e-1c7a-4e5b-9a3d-2b1c4e5f6a7b"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Packaging silently produces a broken app if the bundled runtime is not a JBR
 * (the window layer throws on the first frame) or if the JBR has no `jmods`
 * (jlink cannot build a runtime image). Fail loudly instead.
 */
val checkJbr by tasks.registering {
    doLast {
        val home = jbrHome
            ?: error(
                "No JetBrains Runtime found. BitTrace's window chrome requires one.\n" +
                    "Pass -PjbrHome=<path> or set JBR_HOME to a jbrsdk installation."
            )
        val release = File(home, "release").takeIf { it.exists() }?.readText().orEmpty()
        if (!release.contains("JetBrains")) {
            error("$home is not a JetBrains Runtime (IMPLEMENTOR is not JetBrains).")
        }
        if (!File(home, "jmods").isDirectory) {
            error(
                "$home has no jmods, so jlink cannot build a runtime image.\n" +
                    "IDE-bundled JBRs are '-nomod'; download a 'jbrsdk' build from\n" +
                    "https://github.com/JetBrains/JetBrainsRuntime/releases and point -PjbrHome at it."
            )
        }
    }
}

tasks.matching { it.name.startsWith("createDistributable") || it.name.startsWith("package") }
    .configureEach { dependsOn(checkJbr) }


// ---------------------------------------------------------------------------
// Distribution
// ---------------------------------------------------------------------------
//
// Compose's own tasks each produce one artifact and leave it in its own folder
// under `build/compose/binaries/`, which means shipping a release is four
// commands and a hunt through four directories. The tasks below wrap that into
// one, and put everything a user could download side by side under `build/dist`
// with the version in the file name.
//
// The `Release` variants are the ones used deliberately: the debug ones bundle
// a non-optimised build, and the whole point of these artifacts is that they
// are what other people run.

/** Where a release lands, ready to upload. */
val distDir: Provider<Directory> = layout.buildDirectory.dir("dist")

/**
 * The no-installer build, zipped.
 *
 * `createReleaseDistributable` leaves a *directory* — `BitTrace.exe` beside the
 * runtime image it needs — which is not something anyone can download. The exe
 * does not run from anywhere else, so the folder travels as a unit or not at
 * all, and that makes the archive the artifact rather than a convenience.
 */
val packagePortableZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the portable app image (BitTrace.exe plus its bundled runtime)."
    from(tasks.named("createReleaseDistributable"))
    archiveFileName.set("BitTrace-$appVersion-portable.zip")
    // Staged next to the other binaries rather than in `dist`, so `dist` stays a
    // Sync — able to delete a previous version's files — without racing this.
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/portable"))
}

/**
 * Everything a Windows release ships, in one folder.
 *
 * Three artifacts, because they answer different questions:
 *
 *  - **`BitTrace-<version>.exe`** — the setup wizard. What a person downloads:
 *    it asks where to install, and it makes the Start-menu entry and the
 *    desktop shortcut the `windows { }` block above turns on.
 *  - **`BitTrace-<version>.msi`** — the same install for administrators, which
 *    is what group policy and `msiexec /qn` can deploy unattended. It upgrades
 *    a prior install in place rather than sitting beside it, which is what the
 *    fixed `upgradeUuid` buys.
 *  - **`BitTrace-<version>-portable.zip`** — no installer, no registry, no
 *    admin rights. Unzip and run, which is the only option on a locked-down
 *    machine, and the one to reach for when capturing traffic on someone
 *    else's box.
 *
 * A `Sync`, not a `Copy`: the destination is emptied first, so a stale artifact
 * from an earlier version cannot sit in `build/dist` looking like part of this
 * release.
 *
 * Windows only. jpackage builds the installer format of the host it runs on, so
 * `packageReleaseExe` and `packageReleaseMsi` do not exist elsewhere — hence the
 * check below rather than a confusing "task not found".
 */
val dist by tasks.registering(Sync::class) {
    group = "distribution"
    description = "Builds the setup wizard, the MSI and the portable zip into build/dist."

    doFirst {
        if (!org.gradle.internal.os.OperatingSystem.current().isWindows) {
            error(
                "`dist` builds Windows installers, and jpackage only builds for the host OS.\n" +
                    "Run it on Windows, or use `packageReleaseDistributionForCurrentOS` here."
            )
        }
    }

    from(tasks.named("packageReleaseExe")) { include("*.exe") }
    from(tasks.named("packageReleaseMsi")) { include("*.msi") }
    from(packagePortableZip)
    into(distDir)

    doLast {
        val out = distDir.get().asFile
        logger.lifecycle("")
        logger.lifecycle("BitTrace $appVersion — ${out.absolutePath}")
        out.listFiles()
            ?.sortedBy { it.name }
            ?.forEach { logger.lifecycle("  %-40s %,d KB".format(it.name, it.length() / 1024)) }
        logger.lifecycle("")
    }
}
