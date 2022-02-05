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

    api("com.github.cs125-illinois.jeed:core:2022.2.1")
    api("com.github.cs125-illinois:jenisol:2022.1.2")
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
publishing {
    publications {
        create<MavenPublication>("lib") {
            from(components["java"])
        }
    }
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
