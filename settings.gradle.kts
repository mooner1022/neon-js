pluginManagement {
    repositories {
        gradlePluginPortal()
        // com.vanniktech.maven.publish is released to Maven Central only
        mavenCentral()
    }
}

rootProject.name = "neonjs"

include("neonjs-core", "neonjs-intl", "neonjs-android", "neonjs-android-d8", "neonjs-cli", "neonjs-test262")
