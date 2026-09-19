rootProject.name = "kitbash-server"

// Every module declared now, empty where it has nothing to hold yet: reshaping a
// six-module build while also writing logic is the thing this avoids (kitbash-1).
include("core", "catalog", "render", "api", "verify", "cli")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
