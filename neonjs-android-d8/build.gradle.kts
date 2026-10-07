plugins {
    `java-library`
}

repositories {
    google()
}

// D8 instead of dx for DexCodeDefiner: found through ServiceLoader when this module is on the class path.
dependencies {
    api(project(":neonjs-android"))
    // the r8 library (D8 and R8), published self-contained on Google's Maven repository
    implementation("com.android.tools:r8:9.5.22")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
}
