plugins {
    id("kitbash.java-conventions")
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

// The only module that knows Spring exists.
dependencies {
    implementation(project(":core"))
    implementation(project(":catalog"))
    implementation(project(":render"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// --- phase 0 scaffolding -----------------------------------------------------
// The reference project is copied onto the classpath so the hardcoded generator has
// something to substitute into. kitbash-13 replaces this with recipes loaded from
// /recipes and deletes the task.
//
// A manifest travels with it because a jar has no file modes, and `gradlew` has to
// arrive executable in the generated zip.
val referenceProjectDir: File = file("${rootDir}/../reference/spring-boot-java-gradle-layered")
val referenceStaging: Provider<Directory> = layout.buildDirectory.dir("generated/reference-resources")

val stageReferenceProject by tasks.registering {
    group = "build"
    description = "Copies the reference project onto the api classpath, with a mode manifest."

    inputs.dir(referenceProjectDir).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(referenceStaging)

    val source = referenceProjectDir
    val destination = referenceStaging

    doLast {
        val excluded = listOf("build/", ".gradle/", ".git/", ".idea/")
        val excludedFiles = setOf("reference-variables.json")

        val staged = destination.get().asFile
        staged.deleteRecursively()
        val blobs = staged.resolve("reference/project")
        blobs.mkdirs()

        // Files are staged as numbered blobs, with their real paths held only in the
        // manifest. Keeping the directory structure would be more readable and does not
        // work: processResources inherits Ant's default excludes, which silently drop
        // `.gitignore` and everything under `.git/`. A reference project missing its
        // .gitignore generates projects missing theirs, and nothing fails loudly.
        val manifest = StringBuilder()
        source.walkTopDown()
            .filter { it.isFile }
            .map { it to it.relativeTo(source).invariantSeparatorsPath }
            .filter { (_, path) -> excluded.none { path.startsWith(it) } && path !in excludedFiles }
            .sortedBy { (_, path) -> path }
            .forEachIndexed { index, (file, path) ->
                val blob = "%04d".format(index)
                file.copyTo(blobs.resolve(blob), overwrite = true)
                manifest.append(blob)
                    .append('\t')
                    .append(if (file.canExecute()) "755" else "644")
                    .append('\t')
                    .append(path)
                    .append('\n')
            }

        staged.resolve("reference/project.manifest").writeText(manifest.toString())
        logger.lifecycle("Staged ${manifest.lines().size - 1} reference files for the phase-0 generator.")
    }
}

sourceSets.named("main") {
    resources.srcDir(stageReferenceProject)
}

// The determinism test forks two JVMs and compares their output, so it needs the
// classpath to hand them. Same-JVM equality would not catch a hash-order or a
// time-of-day dependence, which are exactly the bugs it is guarding against.
tasks.named<Test>("test") {
    systemProperty("kitbash.test.classpath", sourceSets.test.get().runtimeClasspath.asPath)
}
