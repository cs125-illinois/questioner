import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.21" apply false
    id("org.jmailen.kotlinter") version "3.10.0" apply false
    id("com.github.ben-manes.versions") version "0.42.0"
    id("com.google.devtools.ksp").version("1.6.21-1.0.5") apply false
}
subprojects {
    group = "com.github.cs125-illinois.questioner"
    version = "2022.5.1a1"
    tasks.withType<KotlinCompile> {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_16.toString()
        }
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("-ea", "-Xmx1G", "-Xss256k", "--illegal-access=permit")
    }
}
allprojects {
    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://dl.bintray.com/jetbrains/markdown")
    }
    tasks.withType<Test> {
        enableAssertions = true
    }
}
tasks.dependencyUpdates {
    fun String.isNonStable() = !(
        listOf("RELEASE", "FINAL", "GA", "JRE").any { toUpperCase().contains(it) }
            || "^[0-9,.v-]+(-r)?$".toRegex().matches(this)
        )
    rejectVersionIf { candidate.version.isNonStable() }
    gradleReleaseChannel = "current"
}
task("publishToMavenLocal") {
    dependsOn(":lib:publishToMavenLocal", ":plugin:publishToMavenLocal")
}
