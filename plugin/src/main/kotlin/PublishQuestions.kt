package edu.illinois.cs.cs125.questioner.plugin

import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.mongodb.client.model.Filters
import com.mongodb.client.model.UpdateOptions
import edu.illinois.cs.cs125.questioner.lib.loadFromFiles
import org.bson.*
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
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
            null
        }

        val questions = loadFromFiles(
            File(project.buildDir, "questioner/questions.json"),
            File(project.buildDir, "resources/main")
        ).values
        questions.forEach { question ->
            if (collection != null) {
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
        }
    }
}