package edu.illinois.cs.cs125.questioner.server

import com.beyondgrader.questioner.agent.Agent
import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Sorts
import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import edu.illinois.cs.cs125.jeed.core.warm
import edu.illinois.cs.cs125.questioner.lib.Question
import edu.illinois.cs.cs125.questioner.lib.TestResults
import edu.illinois.cs.cs125.questioner.lib.moshi.Adapters
import edu.illinois.cs.cs125.questioner.lib.test
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.bson.BsonDocument
import java.time.Duration
import java.time.Instant
import java.util.Properties
import java.util.concurrent.Executors
import kotlin.collections.forEach
import kotlin.system.exitProcess
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters

private val moshi = Moshi.Builder().apply { JeedAdapters.forEach { add(it) } }.build()
private val logger = KotlinLogging.logger {}
private val collection: MongoCollection<BsonDocument> = run {
    require(System.getenv("MONGODB") != null) { "MONGODB environment variable not set" }
    val keystore = System.getenv("KEYSTORE_FILE")
    if (keystore != null) {
        require(System.getenv("KEYSTORE_PASSWORD") != null) { "Must set KEYSTORE_PASSWORD" }
        System.setProperty("javax.net.ssl.trustStore", keystore)
        System.setProperty("javax.net.ssl.trustStorePassword", System.getenv("KEYSTORE_PASSWORD"))
    }
    val collection = System.getenv("MONGODB_COLLECTION") ?: "questioner"
    val mongoUri = MongoClientURI(System.getenv("MONGODB")!!)
    val database = mongoUri.database ?: error("MONGODB must specify database to use")
    MongoClient(mongoUri).getDatabase(database).getCollection(collection, BsonDocument::class.java)
}

data class QuestionPath(val path: String, val version: String, val author: String) {
    companion object {
        fun fromSubmission(submission: Submission) =
            QuestionPath(submission.path, submission.version!!, submission.author!!)
    }
}

object Questions {
    private fun getQuestion(path: String) = collection.find(
        Filters.and(Filters.eq("published.path", path), Filters.eq("latest", true))
    ).sort(Sorts.descending("updated")).let {
        @Suppress("ReplaceSizeZeroCheckWithIsEmpty")
        if (it.count() == 0) {
            return null
        }
        check(it.count() == 1) { "Found multiple full-path matches" }
        try {
            moshi.adapter(Question::class.java).fromJson(it.first()!!.toJson())
        } catch (e: Exception) {
            logger.warn { "Couldn't load question $path, which might use an old schema" }
            null
        }
    }

    private fun getQuestionByPath(path: QuestionPath) = collection.find(
        Filters.and(
            Filters.eq("published.path", path.path),
            Filters.eq("published.version", path.version),
            Filters.eq("published.author", path.author),
            Filters.eq("latest", true)
        )
    ).sort(Sorts.descending("updated")).let {
        @Suppress("ReplaceSizeZeroCheckWithIsEmpty")
        if (it.count() == 0) {
            return null
        }
        check(it.count() == 1) { "Found multiple full-path matches" }
        try {
            moshi.adapter(Question::class.java).fromJson(it.first()!!.toJson())
        } catch (e: Exception) {
            logger.warn { "Couldn't load question $path, which might use an old schema" }
            null
        }
    }

    fun load(path: String): Question? {
        return getQuestion(path)
    }

    fun load(submission: Submission): Question? {
        return if (submission.version != null && submission.author != null) {
            getQuestionByPath(QuestionPath.fromSubmission(submission))
        } else {
            check(submission.version == null && submission.author == null) { "Bad submission with partial information" }
            getQuestion(submission.path)
        }
    }

    suspend fun test(submission: Submission): TestResults {
        val question = load(submission) ?: error("No question ${submission.path}")
        check(question.validated) { "Question ${submission.path} is not validated" }
        val start = Instant.now().toEpochMilli()
        val timeout = question.testingSettings!!.timeout * (System.getenv("TIMEOUT_MULTIPLIER")?.toInt() ?: 1)
        val settings =
            question.testingSettings!!.copy(timeout = timeout, disableLineCountLimit = submission.disableLineCountLimit)
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
    val disableLineCountLimit: Boolean = false,
    val version: String?,
    val author: String?
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
    val version: String = versionString
)

val threadPool = Executors.newFixedThreadPool(System.getenv("QUESTIONER_THREAD_POOL_SIZE")?.toIntOrNull() ?: 8)
    .asCoroutineDispatcher()

@JsonClass(generateAdapter = true)
data class ServerResponse(val results: TestResults)

val runtime: Runtime = Runtime.getRuntime()

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
            call.respond(Status())
        }
        post("/") {
            withContext(threadPool) {
                val submission = call.receive<Submission>()
                Questions.load(submission) ?: return@withContext call.respond(HttpStatusCode.NotFound)
                @Suppress("TooGenericExceptionCaught")
                try {
                    val startMemory = (runtime.freeMemory().toFloat() / 1024.0 / 1024.0).toInt()
                    val results = Questions.test(submission)
                    call.respond(ServerResponse(results))
                    System.gc()
                    System.gc()
                    val endMemory = (runtime.freeMemory().toFloat() / 1024.0 / 1024.0).toInt()
                    logger.debug { "$startMemory -> $endMemory" }
                    System.getenv("RESTART_THRESHOLD_INTERVAL")?.toLong()?.also {
                        if (endMemory < it) {
                            val duration = Duration.between(serverStarted, Instant.now())
                            logger.debug { "Restarting after $duration" }
                            exitProcess(-1)
                        }
                    }
                } catch (e: StackOverflowError) {
                    e.printStackTrace()
                    call.respond(HttpStatusCode.BadRequest)
                } catch (e: Error) {
                    e.printStackTrace()
                    logger.debug { submission }
                    logger.error { e.toString() }
                    exitProcess(-1)
                } catch (e: Throwable) {
                    e.printStackTrace()
                    logger.warn { e.toString() }
                    call.respond(HttpStatusCode.BadRequest)
                }
            }
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
    }
}

fun main() {
    Agent.activate()
    check(Agent.isActivated) { "agent should have activated" }
    logger.debug { Status() }
    CoroutineScope(Dispatchers.IO).launch { warm(2, failLint = false) }
    embeddedServer(Netty, port = 8888, module = Application::questioner).start(wait = true)
}
