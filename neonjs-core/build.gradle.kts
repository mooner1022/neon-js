plugins {
    `java-library`
}

description = "JavaScript engine for the JVM and Android: ES2025+, a security sandbox, Java interop and a JVM bytecode JIT"

dependencies {
    // the execution core (docs/IR_NEUTRALITY.md §10): its classes are part of this module's API
    api(project(":neon-vm"))
    implementation("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-tree:9.9.1")
    implementation("org.ow2.asm:asm-util:9.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
    // frames and argument arrays are reused as on ART (off by default on HotSpot): FramePoolTest and the rest test that
    systemProperty("neonjs.framePool", "true")
    // Test262, where tools/check.sh checks it out: TolerantProbeTest reads all of it (and is skipped without it); its
    // revision is an input, so that a run without it is not reused once it is there
    val test262 = rootProject.file(System.getenv("TEST262") ?: "third_party/test262")
    systemProperty("neonjs.test262", test262.absolutePath)
    inputs.property("test262", providers.provider { File(test262, ".git/HEAD").takeIf { it.isFile }?.readText()?.trim() ?: "absent" })
}
