plugins {
    application
}

repositories {
    google()
}

// the D8 converter and its dependencies, for running the suite on Android with D8 (tools/android: --d8)
val d8Runtime: Configuration by configurations.creating

dependencies {
    implementation(project(":neonjs-core"))
    implementation(project(":neonjs-intl"))
    // DexCheckingDefiner (-Dneonjs.codeDefiner=io.neonjs.android.DexCheckingDefiner) and the device checks (AndroidCheck)
    implementation(project(":neonjs-android"))
    d8Runtime(project(":neonjs-android-d8"))
}

application {
    mainClass.set("io.neonjs.test262.MainKt")
    applicationDefaultJvmArgs = listOf("-Xss64m", "-Xmx6g")
}

tasks.register<Sync>("d8Libs") {
    description = "Copies neonjs-android-d8 and the r8 library to build/d8-libs."
    from(d8Runtime)
    into(layout.buildDirectory.dir("d8-libs"))
}
