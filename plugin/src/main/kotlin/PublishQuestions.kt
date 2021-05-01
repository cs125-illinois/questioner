package edu.illinois.cs.cs125.questioner.plugin

import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import edu.illinois.cs.cs125.questioner.lib.loadFromPath
import edu.illinois.cs.cs125.questioner.lib.toJSON
import org.bson.*
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.impldep.com.google.api.client.http.HttpStatusCodes
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*

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
        val collection = if (uri.scheme == "mongodb") {
            val mongoUri = MongoClientURI(destination)
            val database = mongoUri.database ?: error { "MONGO must specify database to use" }
            MongoClient(mongoUri).getDatabase(database).getCollection("questioner", BsonDocument::class.java)
        } else {
            require(uri.scheme == "http" || uri.scheme == "https") { "Invalid destination scheme: ${uri.scheme}" }
            null
        }

        val questions =
            loadFromPath(File(project.buildDir, "questioner/questions.json"), project.javaSourceDir().path).values
        if (collection != null) {
            questions.forEach { question ->
                collection.updateOne(
                    Filters.and(
                        Filters.eq("name", question.name),
                        Filters.eq("metadata.version", question.metadata.version),
                        Filters.eq("metadata.author", question.metadata.author),
                        Filters.eq("metadata.contentHash", question.metadata.contentHash)
                    ),
                    BsonDocument().apply {
                        put("\$set", BsonDocument.parse(question.toJson()).apply {
                            put("updated", BsonDateTime(Date().time))
                            put("latest", BsonBoolean(true))
                        })
                    },
                    UpdateOptions().upsert(true)
                )
                collection.updateMany(
                    Filters.and(
                        Filters.eq("name", question.name),
                        Filters.eq("metadata.version", question.metadata.version),
                        Filters.eq("metadata.author", question.metadata.author),
                        Filters.ne("metadata.contentHash", question.metadata.contentHash)
                    ),
                    BsonDocument().apply {
                        put("\$set", BsonDocument().apply {
                            put("latest", BsonBoolean(false))
                        })
                    }
                )
            }
        } else {
            val client = HttpClient.newBuilder().build()
            val request = HttpRequest.newBuilder().uri(uri)
                .header("token", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(questions.toJSON())).build()
            client.send(request, HttpResponse.BodyHandlers.ofString()).also {
                check(it.statusCode() == HttpStatusCodes.STATUS_CODE_OK) { "Bad status: ${it.statusCode()}" }
            }
        }
    }
}