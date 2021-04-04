plugins {
    kotlin("jvm")
    kotlin("kapt")
    `maven-publish`
}
dependencies {
    kapt("com.squareup.moshi:moshi-kotlin-codegen:1.12.0")

    implementation(kotlin("stdlib"))
    api("com.github.cs125-illinois.jeed:core:2021.4.0")
    api("com.github.cs125-illinois.jenisol:core:2021.3.6")
    implementation("com.squareup.moshi:moshi-kotlin-codegen:1.12.0")
    api("io.kotest:kotest-runner-junit5:4.4.3")

    api("com.google.truth:truth:1.1.2")
}
publishing {
    publications {
        create<MavenPublication>("lib") {
            from(components["java"])
        }
    }
}

