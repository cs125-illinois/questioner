package edu.illinois.cs.cs125.questioner.lib

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CompilationArguments
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.ComplexityFailed
import edu.illinois.cs.cs125.jeed.core.Features
import edu.illinois.cs.cs125.jeed.core.KtLintFailed
import edu.illinois.cs.cs125.jeed.core.MutatedSource
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.jacocoWith
import edu.illinois.cs.cs125.jenisol.core.Settings
import edu.illinois.cs.cs125.jenisol.core.SubmissionDesignError
import edu.illinois.cs.cs125.questioner.lib.moshi.Adapters
import org.jacoco.core.analysis.ICounter
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.ReflectPermission
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters
import edu.illinois.cs.cs125.jenisol.core.solution as jenisol

val moshi: Moshi = Moshi.Builder().apply {
    Adapters.forEach { add(it) }
    JeedAdapters.forEach { add(it) }
}.build()

private val sharedClassWhitelist = setOf(
    "java.lang.",
    "java.io.PrintStream",
    "kotlin.Metadata",
    "jdk.internal.reflect.",
    "kotlin.reflect.jvm."
)

@Suppress("MemberVisibilityCanBePrivate", "LargeClass", "TooManyFunctions")
@JsonClass(generateAdapter = true)
data class Question(
    val name: String,
    val type: Type,
    val klass: String,
    val metadata: Metadata,
    val control: TestingControl,
    val question: FlatFile,
    val correct: FlatFile,
    val alternativeSolutions: List<FlatFile>,
    val incorrect: List<IncorrectFile>,
    val common: List<String>?,
    var javaTemplate: String?,
    var kotlinTemplate: String?,
    val importWhitelist: Set<String>,
    val importBlacklist: Set<String>,
    val slug: String,
    val kotlinSolution: FlatFile? = alternativeSolutions.find { it.language == Language.kotlin },
    val detemplatedJavaStarter: String? = incorrect.find { it.language == Language.java && it.starter }?.contents,
    val detemplatedKotlinStarter: String? = incorrect.find { it.language == Language.kotlin && it.starter }?.contents,
    val hasKotlin: Boolean =
        metadata.kotlinDescription != null && kotlinSolution != null &&
            ((detemplatedJavaStarter != null) == (detemplatedKotlinStarter != null)),
    val published: Published = Published(
        name,
        type,
        slug,
        metadata.author,
        metadata.version,
        metadata.citation,
        metadata.packageName,
        mutableSetOf(Language.java).apply {
            if (hasKotlin) {
                add(Language.kotlin)
            }
        }.toSet(),
        mutableMapOf(Language.java to metadata.javaDescription).apply {
            if (hasKotlin) {
                put(Language.kotlin, metadata.kotlinDescription!!)
            }
        }.toMap(),
        detemplatedJavaStarter?.let {
            mutableMapOf(Language.java to detemplatedJavaStarter).apply {
                if (hasKotlin) {
                    put(Language.kotlin, detemplatedKotlinStarter!!)
                }
            }.toMap()
        },
        mutableMapOf(Language.java to correct.complexity!!).apply {
            if (hasKotlin) {
                put(
                    Language.kotlin,
                    alternativeSolutions.filter { it.language == Language.kotlin }.mapNotNull { it.complexity }
                        .minOrNull()!!
                )
            }
        },
        // TODO: Support Kotlin features
        mutableMapOf(Language.java to correct.features!!),
        control.maxExtraComplexity
    )
) {
    @Suppress("EnumNaming", "EnumEntryName")
    enum class Language { java, kotlin }

    enum class Type { KLASS, METHOD, SNIPPET }

    @JsonClass(generateAdapter = true)
    data class Published(
        val name: String,
        val type: Type,
        val path: String,
        val author: String,
        val version: String,
        val citation: Citation?,
        val packageName: String,
        val languages: Set<Language>,
        val descriptions: Map<Language, String>,
        val starters: Map<Language, String>?,
        val complexity: Map<Language, Int>,
        val features: Map<Language, Features>,
        val maxExtraComplexity: Int
    )

    @JsonClass(generateAdapter = true)
    data class Metadata(
        val contentHash: String,
        val packageName: String,
        val version: String,
        val author: String,
        val javaDescription: String,
        val kotlinDescription: String?,
        val citation: Citation?,
        val usedFiles: List<String> = listOf(),
        val focused: Boolean? = null
    )

    @JsonClass(generateAdapter = true)
    data class TestingControl(
        val solutionThrows: Boolean,
        val minTestCount: Int,
        val maxTestCount: Int,
        val minTimeout: Int,
        val maxTimeout: Int,
        val timeoutMultiplier: Int,
        val minMutationCount: Int,
        val maxMutationCount: Int,
        val outputMultiplier: Int,
        val maxExtraComplexity: Int,
        val maxDeadCode: Int
    ) {
        constructor(correct: CorrectData) : this(
            correct.solutionThrows,
            correct.minTestCount,
            correct.maxTestCount,
            correct.minTimeout,
            correct.maxTimeout,
            correct.timeoutMultiplier,
            correct.minMutationCount,
            correct.maxMutationCount,
            correct.outputMultiplier,
            correct.maxExtraComplexity,
            correct.maxDeadCode
        )
    }

    @JsonClass(generateAdapter = true)
    data class TestingSettings(
        val seed: Int,
        val testCount: Int,
        val timeout: Int,
        val outputLimit: Int,
        val javaWhitelist: Set<String>?,
        val kotlinWhitelist: Set<String>?,
        val shrink: Boolean,
        var solutionCoverage: CoverageComparison.LineCoverage? = null,
        val checkBlacklist: Boolean = true
    )

    @JsonClass(generateAdapter = true)
    data class ValidationResults(
        val seed: Int,
        val requiredTestCount: Int,
        val mutationCount: Int,
        val solutionMaxRuntime: Int,
        val bootstrapLength: Long,
        val mutationLength: Long,
        val incorrectLength: Long,
        val calibrationLength: Long,
        val solutionCoverage: CoverageComparison.LineCoverage
    )

    @JsonClass(generateAdapter = true)
    data class Citation(val source: String, val link: String? = null)

    @JsonClass(generateAdapter = true)
    data class FlatFile(
        val klass: String,
        val contents: String,
        val language: Language,
        val path: String?,
        val complexity: Int? = null,
        val features: Features? = null,
        val expectedDeadCount: Int? = null
    )

    @JsonClass(generateAdapter = true)
    data class IncorrectFile(
        val klass: String,
        val contents: String,
        val reason: Reason,
        val language: Language,
        val path: String?,
        val starter: Boolean,
        var needed: Boolean = true,
        @Transient
        val mutation: MutatedSource? = null
    ) {
        enum class Reason { DESIGN, COMPILE, TEST, CHECKSTYLE, TIMEOUT, DEADCODE }
    }

    @JsonClass(generateAdapter = true)
    data class ComplexityComparison(
        val solution: Int,
        val submission: Int,
        val limit: Int,
        val increase: Int = submission - solution,
        val failed: Boolean = increase > limit
    )

    @JsonClass(generateAdapter = true)
    data class CoverageComparison(
        val solution: LineCoverage,
        val submission: LineCoverage,
        val missed: List<Int>,
        val limit: Int,
        val increase: Int = submission.missed - solution.missed,
        val failed: Boolean = increase > limit
    ) {
        @JsonClass(generateAdapter = true)
        data class LineCoverage(val covered: Int, val total: Int, val missed: Int = total - covered) {
            init {
                check(covered <= total) { "Invalid coverage result" }
            }
        }

        val deadLines = (submission.missed - solution.missed).coerceAtLeast(0)
    }

    @delegate:Transient
    val compiledCommon by lazy {
        if (common?.isNotEmpty() == true) {
            val commonSources = common.mapIndexed { index, contents -> "Common$index.java" to contents }.toMap()
            Source(commonSources).compile(CompilationArguments(isolatedClassLoader = true, parameters = true))
        } else {
            null
        }
    }

    @delegate:Transient
    val compiledSolution by lazy {
        Source(mapOf("${question.klass}.java" to question.contents)).let { questionSource ->
            if (compiledCommon == null) {
                questionSource.compile(CompilationArguments(isolatedClassLoader = true, parameters = true))
            } else {
                questionSource.compile(
                    CompilationArguments(
                        parentClassLoader = compiledCommon!!.classLoader,
                        parentFileManager = compiledCommon!!.fileManager,
                        parameters = true
                    )
                )
            }
        }
    }

    @delegate:Transient
    val compilationDefinedClass by lazy {
        compiledSolution.classLoader.definedClasses.topLevelClasses().let {
            require(it.size == 1)
            it.first()
        }.also {
            require(it == klass) {
                "Solution defined a name that is different from the parsed name: $it != $klass"
            }
        }
    }

    @delegate:Transient
    val solution by lazy {
        jenisol(compiledSolution.classLoader.loadClass(klass))
    }

    @delegate:Transient
    val sourceChecker by lazy {
        compiledSolution.classLoader.loadClass(klass).declaredMethods.filter { it.isCheckSource() }.let {
            require(it.size <= 1) { "Can only use @CheckSource once" }
            it.firstOrNull()
        }?.also {
            CheckSource.validate(it)
        }
    }

    fun toJson(): String = moshi.adapter(Question::class.java).toJson(this)

    fun getTemplate(language: Language) = when (language) {
        Language.java -> javaTemplate
        Language.kotlin -> kotlinTemplate
    }

    fun Set<String>.topLevelClasses() = map { it.split("$").first() }.distinct()

    fun Language.extension() = when (this) {
        Language.java -> "java"
        Language.kotlin -> "kt"
    }

    val javaTemplateAddsLines = javaTemplate?.lines()?.indexOfFirst {
        it.trim().startsWith("{{{")
    } ?: 0

    val kotlinTemplateAddsLines = kotlinTemplate?.lines()?.indexOfFirst {
        it.trim().startsWith("{{{")
    } ?: 0

    @Transient
    val defaultJavaClassWhitelist = importWhitelist.toMutableSet().also {
        it.addAll(sharedClassWhitelist)
    }.toSet()

    @Transient
    val defaultKotlinClassWhitelist = importWhitelist.toMutableSet().also {
        it.addAll(sharedClassWhitelist)
        it.addAll(setOf("java.util.", "kotlin."))
    }.toSet()

    var testingSettings: TestingSettings? = null
    var validationResults: ValidationResults? = null

    val validated: Boolean
        get() = testingSettings != null

    var fauxStatic: Boolean = false

    @Suppress("ReturnCount", "LongMethod", "ComplexMethod", "LongParameterList")
    suspend fun test(
        contents: String,
        language: Language,
        settings: TestingSettings? = testingSettings
    ): TestResults {
        check(settings != null) { "No test settings provided" }

        val results = TestResults()

        @Suppress("SwallowedException")
        val compiledSubmission = try {
            when (language) {
                Language.java ->
                    compileSubmission(
                        contents,
                        InvertingClassLoader(setOf(klass)),
                        results
                    )
                Language.kotlin ->
                    kompileSubmission(
                        contents,
                        InvertingClassLoader(setOf(klass, "${klass}Kt")),
                        results
                    )
            }
        } catch (e: TemplatingFailed) {
            return results
        } catch (e: CheckstyleFailed) {
            return results
        } catch (e: CompilationFailed) {
            return results
        } catch (e: KtLintFailed) {
            return results
        }

        val klassName = checkCompiledSubmission(compiledSubmission, contents, results) ?: return results

        try {
            results.complete.complexity = computeComplexity(contents, language)
            results.completedSteps.add(TestResults.Step.complexity)
        } catch (e: ComplexityFailed) {
            results.failed.complexity = e
            results.failedSteps.add(TestResults.Step.complexity)
        }

        val classLoaderConfiguration = when (language) {
            Language.java -> settings.javaWhitelist
            Language.kotlin -> settings.kotlinWhitelist
        }?.let {
            Sandbox.ClassLoaderConfiguration(isWhiteList = true, whitelistedClasses = it)
        } ?: Sandbox.ClassLoaderConfiguration()

        val jenisolSettings = Settings(
            seed = settings.seed,
            shrink = settings.shrink,
            overrideTotalCount = settings.testCount,
            startMultipleCount = (settings.testCount / 2).coerceAtMost(
                MAX_START_MULTIPLE_COUNT
            )
        )

        val executionArguments = SourceExecutionArguments(
            timeout = settings.timeout.toLong(),
            classLoaderConfiguration = classLoaderConfiguration,
            maxOutputLines = settings.outputLimit,
            permissions = SAFE_PERMISSIONS,
            returnTimeout = DEFAULT_RETURN_TIMEOUT
        )

        val (taskResults, coverageBuilder) = compiledSubmission.jacocoWith(executionArguments) { classLoader ->
            try {
                solution.submission(classLoader.loadClass(klassName)).test(jenisolSettings, ::captureJeedOutput)
            } catch (e: InvocationTargetException) {
                throw e.cause ?: e
            }
        }
        results.taskResults = taskResults
        results.timeout = taskResults.timeout

        if (!taskResults.timeout && taskResults.threw != null) {
            results.failedSteps.add(TestResults.Step.checkSubmission)
            when (taskResults.threw) {
                is ClassNotFoundException -> results.failed.checkSubmission =
                    "Class design error: could not find class $klass"
                is SubmissionDesignError -> results.failed.checkSubmission =
                    "Class design error: ${taskResults.threw?.message}"
                is NoClassDefFoundError -> results.failed.checkSubmission =
                    "Class design error: attempted to use unavailable class ${taskResults.threw?.message}"
                else -> {
                    val actualException = if (taskResults.threw is InvocationTargetException) {
                        (taskResults.threw as InvocationTargetException).targetException ?: taskResults.threw
                    } else {
                        taskResults.threw
                    }
                    results.failed.checkSubmission = "Testing generated an unexpected error: $actualException"
                }
            }
            return results
        }

        if (!checkExecutedSubmission(taskResults, results, language)) {
            return results
        }

        if (taskResults.returned == null) {
            results.failedSteps.add(TestResults.Step.test)
            return results
        }

        results.completedSteps.add(TestResults.Step.test)
        results.complete.testing = TestResults.TestingResult(
            taskResults.returned!!.map { it.asTestResult(compiledSubmission.source) },
            settings.testCount,
            taskResults.completed && !taskResults.timeout
        )

        val coverage = coverageBuilder.classes.find { it.name == klassName }!!
        val missed = (coverage.firstLine..coverage.lastLine).toList().filter { line ->
            coverage.getLine(line).status == ICounter.NOT_COVERED || coverage.getLine(line).status == ICounter.PARTLY_COVERED
        }.map { line ->
            line - when (language) {
                Language.java -> javaTemplateAddsLines
                Language.kotlin -> kotlinTemplateAddsLines
            }
        }
        val submissionCoverage = CoverageComparison.LineCoverage(
            coverage.lineCounter.totalCount - missed.size,
            coverage.lineCounter.totalCount
        )
        val solutionCoverage =
            validationResults?.solutionCoverage ?: settings.solutionCoverage ?: submissionCoverage

        results.complete.coverage =
            CoverageComparison(solutionCoverage, submissionCoverage, missed, control.maxDeadCode)
        results.completedSteps.add(TestResults.Step.coverage)

        return results
    }

    companion object {
        const val DEFAULT_RETURN_TIMEOUT = 16
        const val MAX_START_MULTIPLE_COUNT = 128
        const val UNLIMITED_OUTPUT_LINES = 102400
        const val DEFAULT_MAX_OUTPUT_SIZE = 8 * 1024 * 1024

        @Transient
        val SAFE_PERMISSIONS =
            setOf(
                RuntimePermission("accessDeclaredMembers"),
                ReflectPermission("suppressAccessChecks"),
                RuntimePermission("getClassLoader"),
                RuntimePermission("localeServiceProvider")
            )
    }
}

fun String.deTemplate(template: String?): String {
    return when (template) {
        null -> this
        else -> {
            val lines = split("\n")
            val start = lines.indexOfFirst { it.contains("TEMPLATE_START") }
            val end = lines.indexOfFirst { it.contains("TEMPLATE_END") }
            require(start != -1) { "Couldn't locate TEMPLATE_START during extraction" }
            require(end != -1) { "Couldn't locate TEMPLATE_END during extraction" }
            lines.slice((start + 1) until end).joinToString("\n").trimIndent()
        }
    }
}

fun Question.validationFile(sourceDir: String) = File(
    sourceDir,
    "${metadata.packageName.replace(".", File.separator)}/.validation.json"
)

fun Question.reportFile(sourceDir: String) = File(
    sourceDir,
    "${metadata.packageName.replace(".", File.separator)}/report.html"
)

fun loadFromPath(questionsFile: File, sourceDir: String, validated: Boolean = true): Map<String, Question> {
    return moshi.adapter<Map<String, Question>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Question::class.java
        )
    ).fromJson(questionsFile.readText())!!.toMutableMap().mapValues { (_, question) ->
        if (validated) {
            val validationPath = question.validationFile(sourceDir)
            if (!validationPath.exists()) {
                return@mapValues question
            }
            val validatedQuestion = try {
                moshi.adapter(Question::class.java).fromJson(validationPath.readText())!!
            } catch (e: Exception) {
                println("WARN: Validation file ${validationPath.path} does not match schema. Removing to be safe.")
                validationPath.delete()
                return@mapValues question
            }
            if (question.metadata.contentHash != validatedQuestion.metadata.contentHash) {
                return@mapValues question
            }
            validatedQuestion
        } else {
            question
        }
    }.toMap()
}

fun Collection<Question>.toJSON(): String =
    moshi.adapter<List<Question>>(Types.newParameterizedType(List::class.java, Question::class.java))
        .toJson(this.toList())

fun File.loadQuestions() = try {
    moshi.adapter<Map<String, Question>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Question::class.java
        )
    ).fromJson(readText())!!
} catch (e: Exception) {
    mapOf()
}

fun File.saveQuestions(questions: Map<String, Question>) =
    writeText(
        moshi.adapter<Map<String, Question>>(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                Question::class.java
            )
        ).indent("  ").toJson(questions)
    )