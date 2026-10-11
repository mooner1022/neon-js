plugins {
    `java-library`
}

description = "fetch and WebSocket for NeonJS over OkHttp, within a network policy (allowed hosts, public addresses only, limits)"

dependencies {
    api(project(":neonjs-core"))
    // the policy takes the host's OkHttpClient
    api("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation(project(":neonjs-node"))
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:5.5.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
    jvmArgs("-Xss16m")
}
