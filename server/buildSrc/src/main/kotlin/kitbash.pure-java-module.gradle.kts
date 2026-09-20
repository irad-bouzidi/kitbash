plugins {
    id("kitbash.spring-free-module")
}

// `core` is the strictest case: framework-free, and the JDK plus two data libraries for the
// patch appliers to parse with (docs/adr/0003-jackson-in-core-for-format-aware-patching.md).
// The enforcement itself lives in `kitbash.spring-free-module`, which `cli` uses too.
