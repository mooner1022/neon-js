plugins {
    application
}

repositories {
    google()
}

// the D8 converter and its dependencies, for running the suite on Android with D8 (tools/android: --d8)
val d8Runtime: Configuration = configurations.create("d8Runtime")

dependencies {
    implementation(project(":neonjs-core"))
    implementation(project(":neonjs-intl"))
    // Node.js's own tests against neonjs-node (NodeTestsKt)
    implementation(project(":neonjs-node"))
    // DexCheckingDefiner (-Dneonjs.codeDefiner=dev.mooner.neonjs.android.DexCheckingDefiner) and the device checks (AndroidCheck)
    implementation(project(":neonjs-android"))
    // class files with missing types for AndroidCheck (also a dependency of neonjs-core)
    implementation("org.ow2.asm:asm:9.9.1")
    d8Runtime(project(":neonjs-android-d8"))
}

application {
    mainClass.set("dev.mooner.neonjs.test262.MainKt")
    applicationDefaultJvmArgs = listOf("-Xss64m", "-Xmx6g")
}

tasks.register<Sync>("d8Libs") {
    description = "Copies neonjs-android-d8 and the r8 library to build/d8-libs."
    from(d8Runtime)
    into(layout.buildDirectory.dir("d8-libs"))
}
