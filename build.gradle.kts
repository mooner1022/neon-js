plugins {
    kotlin("jvm") version "2.4.20" apply false
}

allprojects {
    group = "dev.mooner.neonjs"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            freeCompilerArgs.addAll("-Xjdk-release=21", "-Xno-call-assertions", "-Xno-param-assertions", "-Xno-receiver-assertions")
        }
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }
}
