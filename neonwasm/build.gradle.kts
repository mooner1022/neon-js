plugins {
    `java-library`
}

description = "NeonWasm: a WebAssembly runtime on the Neon execution core (experimental): decoder, validator and " +
    "interpreter, within the core's limits"

// NeonWasm's own coordinates (dev.mooner.neonwasm:neonwasm), apart from NeonJS's
group = "dev.mooner.neonwasm"

dependencies {
    // the execution core only (its limits now, its optimizing tier later): no JavaScript
    api(project(":neon-vm"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
    // the spec tests as tools/wasm-spec.sh converts them (skipped where they are not there)
    systemProperty("neonwasm.spec", rootProject.layout.buildDirectory.dir("wasm-spec").get().asFile.path)
    systemProperty("neonwasm.known", layout.projectDirectory.file("spec-known-failures.txt").asFile.path)
    // the WASI testsuite as tools/wasi-testsuite.sh fetches it
    systemProperty("neonwasm.wasiTestsuite", rootProject.layout.projectDirectory.dir("third_party/wasi-testsuite").asFile.path)
    systemProperty("neonwasm.wasiKnown", layout.projectDirectory.file("wasi-known-failures.txt").asFile.path)
}
