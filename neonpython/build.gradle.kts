plugins {
    `java-library`
}

description = "NeonPy: Python on the Neon execution core (experimental): its own front end, interpreter and runtime, compiled by the core's optimizing tier"

// NeonPy's own coordinates (dev.mooner.neonpython:neonpython), apart from NeonJS's
group = "dev.mooner.neonpython"

dependencies {
    // the execution core only: no JavaScript (docs/IR_NEUTRALITY.md §10)
    api(project(":neon-vm"))
    implementation("org.ow2.asm:asm:9.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // PyAstTest's check of a whole tree of files and CPython's dumps of them (src/test/resources/ast/record_tree.py)
    System.getProperty("neonpy.astDir")?.let { systemProperty("neonpy.astDir", it) }
    // the core's switches (-Dneonjs.jit.suspendable=false, -Dneonjs.jit.osrStress, ...) for the tests' JVM too
    for ((k, v) in System.getProperties()) if (k.toString().startsWith("neonjs.")) systemProperty(k.toString(), v.toString())
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
}
