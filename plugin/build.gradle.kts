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

    implementation(kotlin("stdlib"))
    implementation(gradleApi())
    implementation(project(":lib"))
    implementation("com.github.cs125-illinois.jeed:core:2021.4.2")
    implementation("com.github.cs125-illinois.jenisol:core:2021.4.2")
    implementation("com.squareup.moshi:moshi:1.12.0")
    implementation("org.jetbrains:markdown:0.2.2") {
        exclude(module = "kotlin-runtime")
        exclude(module = "kotlin-js")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.3")
    implementation("com.google.googlejavaformat:google-java-format:1.10.0")
    implementation("com.google.guava:guava:30.1.1-jre")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.12.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.12.3")
    implementation("org.mongodb:mongodb-driver:3.12.8")

    testImplementation("io.kotest:kotest-runner-junit5:4.4.3")
}
tasks.compileKotlin {
    dependsOn(tasks.generateGrammarSource)
}
tasks.compileTestKotlin {
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
gradlePlugin {
    plugins {
        create("plugin") {
            id = "com.github.cs125-illinois.questioner"
            implementationClass = "edu.illinois.cs.cs125.questioner.plugin.QuestionerPlugin"
        }
    }
}
