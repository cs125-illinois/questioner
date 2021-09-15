package edu.illinois.cs.cs125.questioner.server

import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import edu.illinois.cs.cs125.questioner.lib.Question
import edu.illinois.cs.cs125.questioner.lib.TestResults
import edu.illinois.cs.cs125.questioner.lib.moshi.Adapters
import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.BsonDocument
import java.time.Instant
import java.util.Properties
import kotlin.collections.List
import kotlin.collections.associateBy
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.filter
import kotlin.collections.filterNotNull
import kotlin.collections.forEach
import kotlin.collections.map
import kotlin.collections.set
import kotlin.collections.toMutableMap
import kotlin.system.exitProcess
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters

private val moshi = Moshi.Builder().apply {
    JeedAdapters.forEach { add(it) }
}.build()
private val logger = KotlinLogging.logger {}

private val collection: MongoCollection<BsonDocument> = run {
    require(System.getenv("MONGODB") != null) { "MONGODB environment variable not set" }
    val mongoUri = MongoClientURI(System.getenv("MONGODB")!!)
    val database = mongoUri.database ?: error { "MONGO must specify database to use" }
    MongoClient(mongoUri).getDatabase(database).getCollection("questioner", BsonDocument::class.java)
}

private fun getQuestion(slug: String) = collection.find(
    Filters.and(Filters.eq("slug", slug), Filters.eq("latest", true))
).sort(Sorts.descending("updated")).first()?.let {
    try {
        moshi.adapter(Question::class.java).fromJson(it.toJson())
    } catch (e: Exception) {
        logger.warn { "Couldn't load question $slug, which might use an old schema" }
        null
    }
}

@Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
private fun getQuestions() = collection.distinct("slug", String::class.java).map { getQuestion(it) }.filterNotNull()

object Questions {
    val questions = getQuestions().associateBy { it.slug }.toMutableMap()

    fun load(path: String): Question? {
        questions[path] = getQuestion(path) ?: return null
        return questions[path]
    }

    suspend fun test(submission: Submission): TestResults {
        val question = load(submission.path) ?: error("No question ${submission.path}")
        check(question.validated) { "Question ${submission.path} is not validated" }
        val start = Instant.now().toEpochMilli()
        val timeout = question.testingSettings!!.timeout * (System.getenv("TIMEOUT_MULTIPLIER")?.toInt() ?: 1)
        val settings = question.testingSettings!!.copy(failOnLint = submission.failOnLint, timeout = timeout)
        logger.trace { "Testing ${question.name} with settings $settings" }
        return question.test(
            submission.contents,
            language = submission.language,
            settings = settings
        ).also {
            logger.trace { "Tested ${question.name} in ${Instant.now().toEpochMilli() - start}" }
        }
    }
}

@JsonClass(generateAdapter = true)
data class Submission(
    val path: String,
    val contents: String,
    val language: Question.Language,
    val failOnLint: Boolean
)

@JsonClass(generateAdapter = true)
data class QuestionStatus(
    val path: String,
    val name: String,
    val version: String,
    val validated: Boolean,
    val kotlin: Boolean
)

@JsonClass(generateAdapter = true)
data class QuestionDescription(
    val path: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val packageName: String,
    val starter: String?
)

private val serverStarted = Instant.now()

val versionString = run {
    @Suppress("TooGenericExceptionCaught")
    try {
        val versionFile = object {}::class.java.getResource("/edu.illinois.cs.cs125.questioner.server.version")
        Properties().also { it.load(versionFile!!.openStream()) }["version"] as String
    } catch (e: Exception) {
        println(e)
        "unspecified"
    }
}

@JsonClass(generateAdapter = true)
data class Status(
    val started: Instant = serverStarted,
    var questions: List<QuestionStatus>,
    val version: String = versionString
)

fun getStatus(kotlinOnly: Boolean = false) = Status(
    questions = Questions.questions.map { (path, question) ->
        QuestionStatus(
            path,
            question.name,
            question.metadata.version,
            question.validated,
            question.hasKotlin
        )
    }.filter {
        !kotlinOnly || it.kotlin
    }
)

@Suppress("LongMethod")
fun Application.questioner() {
    install(ContentNegotiation) {
        moshi {
            Adapters.forEach { this.add(it) }
            JeedAdapters.forEach { this.add(it) }
        }
    }
    routing {
        get("/") {
            call.respond(getStatus())
        }
        get("/kotlin") {
            call.respond(getStatus(true))
        }
        get("/question/java/{path}") {
            val path = call.parameters["path"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val question = Questions.load(path) ?: return@get call.respond(HttpStatusCode.NotFound)
            call.respond(
                QuestionDescription(
                    path,
                    question.name,
                    question.metadata.version,
                    question.metadata.javaDescription,
                    question.metadata.author,
                    question.metadata.packageName,
                    question.detemplatedJavaStarter,
                )
            )
        }
        get("/question/kotlin/{path}") {
            val path = call.parameters["path"] ?: return@get call.respond(HttpStatusCode.BadRequest)
            val question = Questions.load(path) ?: return@get call.respond(HttpStatusCode.NotFound)
            if (!question.hasKotlin) {
                return@get call.respond(HttpStatusCode.NotFound)
            }
            call.respond(
                QuestionDescription(
                    path,
                    question.name,
                    question.metadata.version,
                    question.metadata.kotlinDescription!!,
                    question.metadata.author,
                    question.metadata.packageName,
                    starter = question.detemplatedKotlinStarter
                )
            )
        }
        post("/") {
            withContext(Dispatchers.IO) {
                val submission = call.receive<Submission>()
                Questions.load(submission.path) ?: return@withContext call.respond(HttpStatusCode.NotFound)
                @Suppress("TooGenericExceptionCaught")
                try {
                    val results = Questions.test(submission)
                    call.respond(results)
                } catch (e: Error) {
                    logger.error(e.toString())
                    exitProcess(-1)
                } catch (e: Throwable) {
                    logger.warn(e.toString())
                    call.respond(HttpStatusCode.BadRequest)
                }
            }
        }
    }
}

fun main() {
    logger.debug { getStatus().copy(questions = listOf()).toString() }
    logger.trace {
        Questions.questions.entries.sortedBy { it.key }.joinToString("\n") { (key, value) ->
            "$key -> ${value.name}"
        }
    }
    embeddedServer(Netty, port = 8888, module = Application::questioner).start(wait = true)
}
