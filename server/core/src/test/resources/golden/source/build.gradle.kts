plugins {
    id("java")
    alias(libs.plugins.spring.boot)
}

group = "com.acme"

// A comment mentioning dependencies { } to prove the scanner is not a regex.
val note = "dependencies { not a block }"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.test {
    useJUnitPlatform()
}
