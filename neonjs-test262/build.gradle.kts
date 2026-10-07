plugins {
    application
}

dependencies {
    implementation(project(":neonjs-core"))
    implementation(project(":neonjs-intl"))
    // verification tool: -Dneonjs.codeDefiner=io.neonjs.android.DexCheckingDefiner
    runtimeOnly(project(":neonjs-android"))
}

application {
    mainClass.set("io.neonjs.test262.MainKt")
    applicationDefaultJvmArgs = listOf("-Xss64m", "-Xmx6g")
}
