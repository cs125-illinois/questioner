plugins {
    kotlin("jvm")
    `maven-publish`
    id("com.google.devtools.ksp")
}
dependencies {
    ksp("com.squareup.moshi:moshi-kotlin-codegen:1.14.0")

    implementation("com.squareup.moshi:moshi-kotlin:1.14.0")
    implementation("org.apache.commons:commons-text:1.10.0")
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    implementation("org.ow2.asm:asm:9.4")

    api("com.beyondgrader.resource-agent:agent:2022.9.3")
    api("com.github.cs125-illinois.jeed:core:2022.11.0")
    api("com.github.cs125-illinois:jenisol:2022.10.5")
    api("io.kotest:kotest-runner-junit5:5.5.4")
    api("com.google.truth:truth:1.1.3")
    api("com.github.cs125-illinois:libcs1:2022.10.0")
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
tasks.withType(Test::class.java) {
    val agentJarPath = configurations["runtimeClasspath"].resolvedConfiguration.resolvedArtifacts.find {
        it.moduleVersion.id.group == "com.beyondgrader.resource-agent"
    }!!.file.absolutePath
    jvmArgs(
        "-ea", "--enable-preview", "-Dfile.encoding=UTF-8",
        "-Xms512m", "-Xmx1G", "-Xss256k", "-XX:+UseZGC", "-XX:ZCollectionInterval=8",
        "-javaagent:$agentJarPath",
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-exports", "java.management/sun.management=ALL-UNNAMED"
    )
}
publishing {
    publications {
        create<MavenPublication>("lib") {
            from(components["java"])
        }
    }
}
kotlin {
    kotlinDaemonJvmArgs = listOf("-Dfile.encoding=UTF-8")
}
