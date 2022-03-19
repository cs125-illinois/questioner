plugins {
    java
}

tasks.withType(JavaCompile::class.java) {
    options.compilerArgs.addAll(listOf("--patch-module", "java.base=" + project.file("src").path))
}
