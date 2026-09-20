plugins {
    id("kitbash.java-conventions")
}

// Loads and validates the recipe tree at boot, and computes the catalog digest. Jackson and the
// JSON Schema validator live here rather than in `core`, which stays stdlib-only (§5, §6).
dependencies {
    implementation(project(":core"))

    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.json.schema.validator)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The manifest schema has exactly one checked-in copy, at /recipes/_schema/recipe.schema.json, so
// editors and the loader validate against the same bytes. It is staged onto the classpath rather
// than duplicated, because two copies of a schema diverge the first time one of them is edited.
val stageManifestSchema by tasks.registering(Copy::class) {
    from(file("${rootDir}/../recipes/_schema")) {
        into("kitbash/schema")
    }
    into(layout.buildDirectory.dir("generated/schema-resources"))
}

sourceSets.named("main") {
    resources.srcDir(stageManifestSchema)
}
