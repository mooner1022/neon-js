import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar

plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("org.jetbrains.dokka") version "2.2.0" apply false
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
}

// group and version: gradle.properties (a release passes -Pversion=X.Y.Z)

// The libraries published as dev.mooner.neonjs:<module> to the GitHub Pages repository and, for some releases, Maven
// Central (docs/RELEASING.md); the CLI and the Test262 runner are not published.
val publishedModules = setOf("neonjs-core", "neonjs-intl", "neonjs-net", "neonjs-node", "neonjs-android", "neonjs-android-d8")

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

    if (name in publishedModules) {
        apply(plugin = "org.jetbrains.dokka")
        apply(plugin = "com.vanniktech.maven.publish")

        extensions.configure<MavenPublishBaseExtension> {
            // an empty javadoc jar satisfies Central and keeps releases within its monthly size quota; the Dokka HTML
            // goes to GitHub Pages instead
            configure(KotlinJvm(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
            // uploads and validates a deployment; it goes public when published on central.sonatype.com
            publishToMavenCentral()
            signAllPublications()

            pom {
                // neonjs-android-d8 -> "NeonJS Android D8"
                name.set(project.name.split('-').joinToString(" ") { if (it == "neonjs") "NeonJS" else it.replaceFirstChar(Char::uppercaseChar) })
                description.set(provider { project.description })
                url.set("https://github.com/mooner1022/neon-js")
                inceptionYear.set("2026")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("mooner1022")
                        name.set("mooner1022")
                        url.set("https://github.com/mooner1022")
                    }
                }
                scm {
                    url.set("https://github.com/mooner1022/neon-js")
                    connection.set("scm:git:git://github.com/mooner1022/neon-js.git")
                    developerConnection.set("scm:git:ssh://git@github.com/mooner1022/neon-js.git")
                }
            }
        }

        // publishAllPublicationsToPagesRepository writes a Maven repository into -PpagesRepo=<dir> (the gh-pages
        // checkout's maven/ directory in the Release workflow), merging the versions already there
        extensions.configure<PublishingExtension> {
            repositories {
                maven {
                    name = "pages"
                    url = rootProject.file(findProperty("pagesRepo") ?: "build/pages-maven").toURI()
                }
            }
        }

        // the license and the third-party notices travel with the classes (Android builds leave these paths out)
        tasks.named<Jar>("jar") {
            from(rootProject.files("LICENSE", "NOTICE")) { into("META-INF") }
        }
    }
}
