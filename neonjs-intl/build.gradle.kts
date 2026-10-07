plugins {
    `java-library`
}

description = "ECMA-402 Intl and the non-ISO Temporal calendars for NeonJS, on ICU4J"

dependencies {
    api(project(":neonjs-core"))
    // ICU 78: Unicode 17 / CLDR 48, matching the engine's own Unicode tables
    implementation("com.ibm.icu:icu4j:78.3")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
}
