plugins {
    `java-library`
}

description = "Python on NeonJS's execution core (experimental): its own front end, interpreter and runtime, compiled by the core's optimizing tier"

dependencies {
    api(project(":neonjs-core"))
    implementation("org.ow2.asm:asm:9.9.1")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
}
