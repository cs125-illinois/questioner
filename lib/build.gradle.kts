plugins {
    kotlin("jvm")
    kotlin("kapt")
    `maven-publish`
}
dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.13.0")

    implementation("org.jetbrains.kotlin:kotlin-reflect:1.6.10")
    implementation("com.squareup.moshi:moshi-kotlin:1.13.0")
    implementation("org.apache.commons:commons-text:1.9")
    implementation("com.github.cs125-illinois.questioner:agent:$version")

    api("com.github.cs125-illinois.jeed:core:2022.3.1")
    api("com.github.cs125-illinois:jenisol:2022.3.1a1")
    api("io.kotest:kotest-runner-junit5:4.6.3")
    api("com.google.truth:truth:1.1.3")
    api("com.github.cs125-illinois:libcs1:2022.1.0")
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
configurations.runtimeClasspath {
    val agentArtifact = resolvedConfiguration.resolvedArtifacts.find { it.name == "agent" }!!
    val agentJar = agentArtifact.file.absolutePath
    tasks.withType(Test::class.java) {
        jvmArgs("-javaagent:$agentJar")
    }
}
publishing {
    publications {
        create<MavenPublication>("lib") {
            from(components["java"])
        }
    }
}
kapt {
    includeCompileClasspath = false
}
