plugins {
    `kotlin-dsl`
}

dependencies {
    // Convention plugins apply Spotless, so its plugin marker has to be on the
    // buildSrc classpath. Kept in sync with gradle/libs.versions.toml by hand:
    // buildSrc cannot read the root version catalog in the plugins block.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:7.0.4")
}
