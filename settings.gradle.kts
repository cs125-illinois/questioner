rootProject.name = "questioner"
include("lib", "plugin", "server")
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
