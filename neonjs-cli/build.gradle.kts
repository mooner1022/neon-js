plugins {
    application
}

dependencies {
    implementation(project(":neonjs-core"))
    implementation(project(":neonjs-intl"))
    // on Android (the jar dexed with d8), compiled mode defines JIT code through dex; inactive on standard JVMs
    runtimeOnly(project(":neonjs-android"))
}

application {
    mainClass.set("dev.mooner.neonjs.cli.MainKt")
    applicationName = "neonjs"
    // deep JS recursion needs more than the default 1 MB thread stack to reach the engine's call-depth limit
    applicationDefaultJvmArgs = listOf("-Xss16m")
}

// Self-contained executable jar with every runtime dependency: build/libs/neonjs-cli-<version>-all.jar,
// run with `java -jar` (the CLI starts the REPL when given no files).
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles an executable jar of the CLI with all runtime dependencies."
    archiveClassifier.set("all")
    manifest { attributes["Main-Class"] = "dev.mooner.neonjs.cli.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    // the dependencies' module descriptors would make the merged jar look like one of those modules
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
    // only neonjs-intl registers services (META-INF/services), so no file needs merging
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
