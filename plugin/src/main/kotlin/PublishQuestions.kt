package edu.illinois.cs.cs125.questioner.plugin

import edu.illinois.cs.cs125.questioner.lib.loadFromPath
import edu.illinois.cs.cs125.questioner.lib.toJSON
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.impldep.com.google.api.client.http.HttpStatusCodes
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

open class PublishQuestions : DefaultTask() {
    @Input
    lateinit var token: String

    @Input
    lateinit var destination: String

    init {
        group = "Build"
        description = "Publish questions."
    }

    @TaskAction
    fun publish() {
        val uri = URI(destination)
        require(uri.scheme == "http" || uri.scheme == "https") { "Invalid destination scheme: ${uri.scheme}" }
        val questions =
            loadFromPath(File(project.buildDir, "questioner/questions.json"), project.javaSourceDir().path).values
        require(questions.all { it.validated }) { "Cannot publish until all questions are validated" }
        val client = HttpClient.newBuilder().build()
        val request = HttpRequest.newBuilder().uri(uri)
            .header("token", token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(questions.toJSON())).build()
        client.send(request, HttpResponse.BodyHandlers.ofString()).also {
            check(it.statusCode() == HttpStatusCodes.STATUS_CODE_OK) { "Bad status for $destination: ${it.statusCode()}" }
        }
    }
}
