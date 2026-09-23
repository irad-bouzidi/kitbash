plugins {
    // Spring appearing on this module's classpath is a build failure, not a review comment.
    id("kitbash.spring-free-module")
    application
}

// Offline generation: no server, no database, no Spring context. §12 has every verification
// cell call the generator through here rather than over HTTP, which keeps verification
// independent of the API, its auth and its persistence — a red cell then means the generator
// is broken rather than the deployment.
//
// It is also the cheapest check that the §6 module boundary holds: if this can generate a
// project while depending only on core, catalog and render, the domain really is Spring-free.
dependencies {
    implementation(project(":core"))
    implementation(project(":catalog"))
    implementation(project(":render"))
    implementation(libs.jackson.databind)
    // A library on the classpath logs through SLF4J, and with no binding it prints three
    // warnings to stderr. §14 has CI parsing stderr as the error envelope, so a no-op binding
    // is the difference between a parseable failure and one that needs a filter.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

application {
    mainClass = "dev.kitbash.cli.Kitbash"
    applicationName = "kitbash"
}

// The version and the catalog digest are the binary's identity (§42), and a resource is the only
// place a running jar can read its own build from.
tasks.named<ProcessResources>("processResources") {
    val distributionVersion = project.version.toString()
    inputs.property("version", distributionVersion)
    from(resources.text.fromString("version=$distributionVersion\n")) {
        rename { "kitbash-distribution.properties" }
    }
}

// ---------------------------------------------------------------------------
// The self-contained distribution (§42)
// ---------------------------------------------------------------------------
//
// `jlink` rather than GraalVM native-image. §42 allows either, and the constraint it actually
// checks is that the smoke test runs "in a container with no JDK installed". jlink satisfies that
// with the JDK the build already provisions: no second toolchain, no reflection configuration for
// Jackson and Pebble, no per-platform native build matrix. What native would buy is startup time,
// and nothing here is measuring it.
//
// The trade is size and portability: a jlink image is tens of megabytes and is built for the
// platform it was built on, so the release job runs this per platform. That is written down in
// docs/cli.md rather than discovered at download time.

/** The modules the CLI actually needs. Derived with `jdeps`, not guessed — see docs/cli.md. */
val runtimeModules =
    listOf(
        "java.base",
        // Jackson's databind reaches for these; a missing one is a NoClassDefFoundError at the
        // first selection rather than at build time, which is the expensive way to find out.
        "java.logging",
        "java.xml",
        "java.desktop",
        "java.sql",
        "java.naming",
        // Pebble compiles templates to bytecode through a script engine.
        "jdk.unsupported",
    )

val jlinkImage by tasks.registering(Exec::class) {
    group = "distribution"
    description = "A trimmed JRE for the self-contained CLI distribution (§42)."

    val javaHome = javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath
    val output = layout.buildDirectory.dir("jlink-runtime")

    inputs.property("modules", runtimeModules)
    outputs.dir(output)

    // jlink refuses to write into an existing directory, and Gradle's up-to-date check is what
    // stops this running when nothing changed.
    doFirst { delete(output) }

    commandLine(
        javaHome.file("bin/jlink").asFile.absolutePath,
        "--add-modules",
        runtimeModules.joinToString(","),
        "--strip-debug",
        "--no-man-pages",
        "--no-header-files",
        "--compress=zip-6",
        "--output",
        output.get().asFile.absolutePath,
    )
}

/**
 * The published artifact: the jars, a JRE, the recipes, and a launcher that needs neither a JDK
 * nor a JAVA_HOME.
 */
val selfContainedDistribution by tasks.registering(Sync::class) {
    group = "distribution"
    description = "A distribution that runs on a machine with no JDK (§42)."

    val root = layout.buildDirectory.dir("self-contained/kitbash")
    into(root)

    // jlink writes its legal notices read-only, and Sync cannot overwrite a read-only file — so
    // the second build of an unchanged tree failed where the first succeeded. Cleared rather than
    // chmodded, because making the runtime writable would also make `java` writable.
    doFirst { delete(root) }

    from(tasks.named("jar")) { into("lib") }
    from(configurations.runtimeClasspath) { into("lib") }
    from(jlinkImage) { into("runtime") }
    from(resources.text.fromString(LAUNCHER)) {
        rename { "kitbash" }
        into("bin")
        filePermissions { unix("0755") }
    }

    // The catalog travels with the binary: that is what makes generation offline, and what makes
    // the digest part of the binary's identity rather than of its environment.
    //
    // Copied by hand rather than with `from(...)`, because Gradle's copy tasks apply **Ant's
    // default excludes** — which silently drop `.gitignore`. A catalog missing one file is a
    // catalog with a different digest, and the symptom was a generated project failing with
    // PATCH_TARGET_MISSING for a file no recipe had produced.
    //
    // The mode is copied with the bytes, which `File.copyTo` does not do. `gradlew` and `mvnw` are
    // 0755 in the catalog and the loader reads the filesystem to decide a file is executable, so a
    // 0644 copy produced a catalog whose wrapper scripts were not executable — and a generated
    // project whose first documented command was "Permission denied". Both defects were found by
    // the §42 smoke test, which is the argument for having one.
    val recipes = rootDir.parentFile.resolve("recipes")
    inputs.dir(recipes).withPathSensitivity(PathSensitivity.RELATIVE)
    val destination = layout.buildDirectory.dir("self-contained/kitbash/recipes")
    doLast {
        val target = destination.get().asFile
        recipes.walkTopDown().forEach { source ->
            val relative = source.relativeTo(recipes).path
            val copy = File(target, relative)
            if (source.isDirectory) {
                copy.mkdirs()
            } else {
                copy.parentFile.mkdirs()
                source.copyTo(copy, overwrite = true)
                copy.setExecutable(source.canExecute(), false)
            }
        }
    }
}

val distributionArchive by tasks.registering(Exec::class) {
    group = "distribution"
    description = "The self-contained distribution, as one file to publish (§42)."

    dependsOn(selfContainedDistribution)

    val staging = layout.buildDirectory.dir("self-contained")
    val archive = layout.buildDirectory.file("distributions/kitbash-${project.version}.tgz")

    inputs.dir(staging)
    outputs.file(archive)

    doFirst { archive.get().asFile.parentFile.mkdirs() }

    // GNU tar rather than Gradle's Tar task, for two reasons that both cost a debugging session.
    //
    // Gradle's copy machinery applies **Ant's default excludes**, so the archive lost `.gitignore`
    // exactly as the staging copy had — the same defect twice, in two places, with the same
    // symptom: a generated project failing PATCH_TARGET_MISSING for a file nothing produced.
    //
    // And forcing permissions for reproducibility stripped the executable bit from the launcher,
    // `gradlew` and `mvnw`. tar preserves modes and takes its determinism from flags that do not
    // touch them: a fixed mtime, sorted entries, and numeric root ownership. §4's byte-equality
    // claim covers what this project emits, and a published checksum is worth nothing without it.
    commandLine(
        "tar",
        "--create",
        "--gzip",
        "--file",
        archive.get().asFile.absolutePath,
        "--directory",
        staging.get().asFile.absolutePath,
        "--sort=name",
        "--mtime=@0",
        "--owner=0",
        "--group=0",
        "--numeric-owner",
        "kitbash",
    )
}

val smokeTest by tasks.registering(Exec::class) {
    group = "verification"
    description = "§42's exit criterion: no JDK, no network, generate and build (in containers)."

    dependsOn(distributionArchive)
    commandLine(
        projectDir.resolve("smoke-test.sh").absolutePath,
        layout.buildDirectory
            .file("distributions/kitbash-${project.version}.tgz")
            .get()
            .asFile
            .absolutePath,
    )
}

/**
 * The launcher. Resolves its own home so the runtime and the recipes are found wherever the
 * archive was unpacked, and exports KITBASH_HOME so `Distribution` can find the embedded catalog.
 */
val LAUNCHER =
    """
    #!/bin/sh
    # kitbash — a self-contained launcher (§42). No JDK required: the runtime is beside it.
    set -eu
    here=$(cd -- "$(dirname -- "$0")/.." && pwd)
    KITBASH_HOME="${'$'}here"
    export KITBASH_HOME
    exec "${'$'}here/runtime/bin/java" -cp "${'$'}here/lib/*" dev.kitbash.cli.Kitbash "${'$'}@"
    """.trimIndent() + "\n"
