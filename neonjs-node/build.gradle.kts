plugins {
    `java-library`
}

description = "Node.js APIs for NeonJS: node: built-in modules (events, timers, buffer, util, path...) on the web globals"

dependencies {
    api(project(":neonjs-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
}
