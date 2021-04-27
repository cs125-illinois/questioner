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
import io.ktor.application.*
import io.ktor.features.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.BsonDocument
import java.time.Instant
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

private val moshi = Moshi.Builder().build()
private val logger = KotlinLogging.logger {}
private const val SEED = 124

private val collection: MongoCollection<BsonDocument> = run {
    require(System.getenv("MONGODB") != null) { "MONGODB environment variable not set" }
    val mongoUri = MongoClientURI(System.getenv("MONGODB")!!)
    val database = mongoUri.database ?: error { "MONGO must specify database to use" }
    MongoClient(mongoUri).getDatabase(database).getCollection("questioner", BsonDocument::class.java)
}

private fun getQuestion(slug: String) = collection.find(
    Filters.and(Filters.eq("slug", slug), Filters.eq("latest", true))
).sort(Sorts.descending("updated")).first()?.let {
    moshi.adapter(Question::class.java).fromJson(it.toJson())
}

private fun getQuestions() = collection.distinct("slug", String::class.java).map { getQuestion(it) }.filterNotNull()

object Questions {
    val questions = getQuestions().associateBy { it.slug }.toMutableMap()

    fun load(path: String): Question? {
        questions[path] = getQuestion(path) ?: return null
        return questions[path]
    }

    suspend fun test(submission: Submission): TestResults {
        val question = load(submission.path) ?: error("No question ${submission.path}")

        if (!question.validated) {
            val start = Instant.now().toEpochMilli()
            logger.info("Validating ${question.name}")
            question.initialize(seed = SEED)
            logger.info("Validated ${question.name} in ${Instant.now().toEpochMilli() - start}")
        }
        val start = Instant.now().toEpochMilli()
        logger.trace("Testing ${question.name}")
        return question.test(
            submission.contents,
            seed = SEED,
            language = submission.language,
            timeoutAdjustment = System.getenv("TIMEOUT_ADJUSTMENT")?.toDouble() ?: 1.0
        ).also {
            logger.trace("Tested ${question.name} in ${Instant.now().toEpochMilli() - start}")
        }
    }
}

@JsonClass(generateAdapter = true)
data class Submission(val path: String, val contents: String, val language: Question.Language = Question.Language.java)

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
    val starter: String?,
    val checkstyle: Boolean? = null
)

private val serverStarted = Instant.now()

@JsonClass(generateAdapter = true)
data class Status(val started: Instant = serverStarted, var questions: List<QuestionStatus>)

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
                    question.metadata.checkstyle,
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
    logger.debug(
        Questions.questions.entries.sortedBy { it.key }.joinToString("\n") { (key, value) ->
            "$key -> ${value.name}"
        }
    )
    embeddedServer(Netty, port = 8888, module = Application::questioner).start(wait = true)
}
