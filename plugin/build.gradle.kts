plugins {
    kotlin("jvm")
    kotlin("kapt")
    antlr
    java
    `java-gradle-plugin`
    `maven-publish`
    id("org.jmailen.kotlinter")
}
dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.12.0")

    antlr("org.antlr:antlr4:4.9.2")

    implementation("org.jetbrains.kotlin:kotlin-reflect:1.5.31")
    implementation(gradleApi())
    implementation(project(":lib"))
    implementation("com.squareup.moshi:moshi:1.12.0")
    implementation("org.jetbrains:markdown:0.2.4") {
        exclude(module = "kotlin-runtime")
        exclude(module = "kotlin-js")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")
    implementation("com.google.googlejavaformat:google-java-format:1.12.0")
    implementation("com.google.guava:guava:31.0.1-jre")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.13.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.0")

    testImplementation("io.kotest:kotest-runner-junit5:4.6.3")
}
tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}
tasks.compileTestKotlin {
    dependsOn(tasks.generateGrammarSource)
}
tasks.lintKotlinMain {
    dependsOn(tasks.generateGrammarSource)
}
tasks.generateGrammarSource {
    outputDirectory = File(projectDir, "src/main/java/edu/illinois/cs/cs125/questioner/antlr")
    arguments.addAll(
        listOf(
            "-visitor",
            "-package", "edu.illinois.cs.cs125.questioner.antlr",
            "-Xexact-output-dir",
            "-lib", "src/main/antlr/edu/illinois/cs/cs125/questioner/antlr/lib/"
        )
    )
}
configurations {
    all {
        exclude("ch.qos.logback")
    }
}
tasks {
    val sourcesJar by creating(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }
    artifacts {
        add("archives", sourcesJar)
    }
}
gradlePlugin {
    plugins {
        create("plugin") {
            id = "com.github.cs125-illinois.questioner"
            implementationClass = "edu.illinois.cs.cs125.questioner.plugin.QuestionerPlugin"
        }
    }
}
