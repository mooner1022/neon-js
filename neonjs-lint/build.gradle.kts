plugins {
    `java-library`
}

description = "A linter for NeonJS's JavaScript: the engine's own parser, read on after syntax errors, and rules for mistakes that are valid code"

dependencies {
    // the parser's recovery mode, the scope analysis and the tree: part of this module's API (LintRule reads the tree)
    api(project(":neonjs-core"))
    // EngineGlobalsTest: the globals their extensions install
    testImplementation(project(":neonjs-net"))
    testImplementation(project(":neonjs-node"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
    // Test262, where tools/check.sh checks it out: Test262LintTest reads it (and is skipped without it)
    val test262 = rootProject.file(System.getenv("TEST262") ?: "third_party/test262")
    systemProperty("neonjs.test262", test262.absolutePath)
    inputs.property("test262", providers.provider { File(test262, ".git/HEAD").takeIf { it.isFile }?.readText()?.trim() ?: "absent" })
    // LintBenchTest, a timing that asserts nothing: only with -PlintBench (tools/check.sh full)
    val lintBench = providers.gradleProperty("lintBench").isPresent
    systemProperty("neonjs.lintBench", lintBench)
    inputs.property("lintBench", lintBench)
}
