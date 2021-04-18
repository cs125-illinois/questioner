package edu.illinois.cs.cs125.questioner.server

import com.github.slugify.Slugify
import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import edu.illinois.cs.cs125.jsp.server.moshi.Adapters
import edu.illinois.cs.cs125.questioner.lib.Question
import edu.illinois.cs.cs125.questioner.lib.TestResults
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
import java.lang.Error
import java.time.Instant
import kotlin.system.exitProcess
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters
import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import org.bson.BsonDocument

val logger = KotlinLogging.logger {}
private const val SEED = 124

private val collection: MongoCollection<BsonDocument> = run {
    require(System.getenv("MONGODB") != null) { "MONGODB environment variable not set" }
    val mongoUri = MongoClientURI(System.getenv("MONGODB")!!)
    val database = mongoUri.database ?: error { "MONGO must specify database to use" }
    MongoClient(mongoUri).getDatabase(database).getCollection("questioner", BsonDocument::class.java)
}

object Questions {
    private val moshi = Moshi.Builder().build()

    val availableQuestions: MutableMap<String, Question> = mutableMapOf()
    val prevalidated: MutableSet<String> = mutableSetOf()

    private val slugify = Slugify()

    init {
        moshi.adapter<Map<String, Question>>(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Question::class.java
            )
        )
            .fromJson(this::class.java.getResource("/questions.json").readText())?.also { questions ->
                check(questions.isNotEmpty()) { "No questions loaded" }
                questions.values.forEach { question ->
                    val slugified = slugify.slugify(question.name)
                    availableQuestions[slugified] =
                        this::class.java.getResource("/${question.metadata.contentHash}-validated.json")?.let {
                            prevalidated.add(slugified)
                            moshi.adapter(Question::class.java).fromJson(it.readText())
                        } ?: question
                }
                assert(availableQuestions.keys.size == questions.keys.size)
            } ?: error("Couldn't load questions")
    }

    suspend fun test(submission: Submission): TestResults {
        val question = availableQuestions[submission.path] ?: error("No question ${submission.path}")
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
    val prevalidated: Boolean,
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
    questions = Questions.availableQuestions.map { (path, question) ->
        QuestionStatus(
            path,
            question.name,
            question.metadata.version,
            Questions.prevalidated.contains(path),
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
            val question = Questions.availableQuestions[path] ?: return@get call.respond(HttpStatusCode.NotFound)
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
            val question = Questions.availableQuestions[path] ?: return@get call.respond(HttpStatusCode.NotFound)
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
                Questions.availableQuestions[submission.path]
                    ?: return@withContext call.respond(HttpStatusCode.NotFound)
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
    embeddedServer(Netty, port = 8888, module = Application::questioner).start(wait = true)
}
