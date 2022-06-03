import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.io.File
import java.io.StringWriter
import java.util.Properties

plugins {
    kotlin("jvm")
    kotlin("kapt")
    application
    id("org.jmailen.kotlinter")
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("com.palantir.docker") version "0.33.0"
    id("com.google.devtools.ksp")
}
dependencies {
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.13.0")

    implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.10")
    implementation(project(":lib"))

    implementation("io.ktor:ktor-server-netty:2.0.2")
    implementation("io.ktor:ktor-server-content-negotiation:2.0.2")
    implementation("com.squareup.moshi:moshi-kotlin:1.13.0")
    implementation("com.github.cs125-illinois:ktor-moshi:2022.4.0")
    implementation("com.github.slugify:slugify:2.5")
    implementation("org.mongodb:mongodb-driver:3.12.11")

    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("ch.qos.logback:logback-classic:1.2.11")
    implementation("io.github.microutils:kotlin-logging:2.1.23")
}
task("createProperties") {
    doLast {
        val properties = Properties().also {
            it["version"] = project.version.toString()
        }
        File(projectDir, "src/main/resources/edu.illinois.cs.cs125.questioner.server.version")
            .printWriter().use { printWriter ->
                printWriter.print(
                    StringWriter().also { properties.store(it, null) }.buffer.toString()
                        .lines().drop(1).joinToString(separator = "\n").trim()
                )
            }
    }
}
tasks.processResources {
    dependsOn("createProperties")
}
tasks.shadowJar {
    manifest {
        attributes["Launcher-Agent-Class"] = "com.beyondgrader.resourceagent.AgentKt"
        attributes["Can-Redefine-Classes"] = "true"
        attributes["Can-Retransform-Classes"] = "true"
    }
}
application {
    mainClass.set("edu.illinois.cs.cs125.questioner.server.MainKt")
}
docker {
    name = "cs125/questioner"
    files(tasks["shadowJar"].outputs)
    @Suppress("DEPRECATION")
    tags("latest")
}
kotlin {
    kotlinDaemonJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
tasks.withType<ShadowJar> {
    isZip64 = true
}
