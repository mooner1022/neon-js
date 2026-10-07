plugins {
    `java-library`
}

description = "Runs NeonJS JIT code and Java.extend adapters on Android by translating them to dex with dx"

// A plain JVM library: the Android classes it uses (dalvik.system.*) are reached through reflection, so it builds
// and its conversion step is testable without the Android SDK. Apps add it next to neonjs-core.
dependencies {
    api(project(":neonjs-core"))
    // dx, the class-file-to-dex translator (AOSP, repackaged; also what dexmaker uses on devices)
    implementation("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
}
