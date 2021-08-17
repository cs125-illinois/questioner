plugins {
    kotlin("jvm")
    kotlin("kapt")
    `maven-publish`
}
dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.12.0")

    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
    implementation("com.squareup.moshi:moshi-kotlin-codegen:1.12.0")
    implementation("com.github.slugify:slugify:2.5")
    implementation("org.apache.commons:commons-text:1.9")

    api("com.github.cs125-illinois.jeed:core:2021.8.2")
    api("com.github.cs125-illinois:jenisol:2021.8.0")
    api("io.kotest:kotest-runner-junit5:4.6.1")
    api("com.google.truth:truth:1.1.3")
    api("com.github.cs125-illinois:libcs1:2021.8.0")
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
}

