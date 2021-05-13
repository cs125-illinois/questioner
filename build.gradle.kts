import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.32" apply false
    kotlin("kapt") version "1.4.32" apply false
    id("org.jmailen.kotlinter") version "3.4.4" apply false
    id("com.github.ben-manes.versions") version "0.38.0"
}
subprojects {
    group = "com.github.cs125-illinois.questioner"
    version = "2021.5.6"
    tasks.withType<KotlinCompile> {
        val javaVersion = JavaVersion.VERSION_1_8.toString()
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
        kotlinOptions {
            jvmTarget = javaVersion
        }
    }
    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
allprojects {
    repositories {
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://dl.bintray.com/jetbrains/markdown")
        mavenLocal()
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
