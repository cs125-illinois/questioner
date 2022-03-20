plugins {
    kotlin("jvm")
    java
    `maven-publish`
}

dependencies {
    implementation("org.ow2.asm:asm:9.2")
    implementation("org.ow2.asm:asm-tree:9.2")
    compileOnly("com.github.cs125-illinois.jeed:core:2022.3.1")
}

tasks.getByName("processResources") {
    dependsOn("copyClassResource")
}

tasks.withType(Jar::class.java) {
    manifest {
        attributes["Premain-Class"] = "com.beyondgrader.questioner.agent.AgentKt"
        attributes["Can-Redefine-Classes"] = "true"
        attributes["Can-Retransform-Classes"] = "true"
    }
}

tasks.create("copyClassResource", Copy::class.java) {
    dependsOn(":agentsink:classes")
    rootProject.childProjects["agentsink"]!!.file("build/classes/java/main/java/lang").also {
        inputs.dir(it)
        from(it)
    }
    project.file("src/main/resources").also {
        outputs.dir(it)
        into(it)
    }
}

publishing {
    publications {
        create<MavenPublication>("agent") {
            from(components["java"])
        }
    }
}
