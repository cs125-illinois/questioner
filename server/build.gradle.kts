import java.io.File
import java.io.StringWriter
import java.util.Properties

plugins {
    kotlin("jvm")
    kotlin("kapt")
    application
    id("org.jmailen.kotlinter")
    id("com.github.johnrengelman.shadow") version "7.0.0"
    id("com.palantir.docker") version "0.29.0"
}
dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.12.0")

    implementation("org.jetbrains.kotlin:kotlin-reflect:1.5.31")
    implementation(project(":lib"))

    implementation("io.ktor:ktor-server-netty:1.6.3")
    implementation("com.squareup.moshi:moshi-kotlin-codegen:1.12.0")
    implementation("com.github.cs125-illinois:ktor-moshi:1.0.3")
    implementation("com.github.slugify:slugify:2.5")
    implementation("org.mongodb:mongodb-driver:3.12.10")

    implementation("org.slf4j:slf4j-api:1.7.32")
    implementation("ch.qos.logback:logback-classic:1.2.6")
    implementation("io.github.microutils:kotlin-logging:2.0.11")
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
application {
    @Suppress("DEPRECATION")
    mainClassName = "edu.illinois.cs.cs125.questioner.server.MainKt"
}
docker {
    name = "cs125/questioner"
    tag("latest", "cs125/questioner:latest")
    tag(version.toString(), "cs125/questioner:$version")
    files(tasks["shadowJar"].outputs)
}
kapt {
    includeCompileClasspath = false
    javacOptions {
        option("--illegal-access", "permit")
    }
}
kotlin {
    kotlinDaemonJvmArgs = listOf("-Dfile.encoding=UTF-8", "--illegal-access=permit")
}

