plugins {
    kotlin("jvm") version "1.5.0" apply false
    kotlin("kapt") version "1.5.0" apply false
    id("org.jmailen.kotlinter") version "3.4.4" apply false
    id("com.github.ben-manes.versions") version "0.38.0"
}
subprojects {
    group = "com.github.cs125-illinois.questioner"
    version = "2021.5.2"
    configurations.all {
        resolutionStrategy {
            force(
                "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.5.0",
                "org.jetbrains.kotlin:kotlin-script-runtime:1.5.0"
            )
        }
    }
}
allprojects {
    repositories {
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
