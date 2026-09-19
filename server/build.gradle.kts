// Root build holds no logic: module behaviour comes from the convention plugins in
// buildSrc, so adding a seventh module is one line in settings.gradle.kts plus a
// three-line build script.
plugins {
    base
}

tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}
