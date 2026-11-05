plugins {
    `java-library`
}

description = "The execution core of NeonJS and NeonPy (working name): the JIT's policy, the optimizing tier's " +
    "language-neutral core and the frames, code units and limits it reads; each language plugs in a dialect " +
    "(docs/IR_NEUTRALITY.md). Not a stable API."

dependencies {
    // (the dialects' interface hands its code generators ASM's visitors)
    api("org.ow2.asm:asm:9.9.1")
    implementation("org.ow2.asm:asm-tree:9.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1g"
    jvmArgs("-Xss16m")
}
