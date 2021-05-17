package edu.illinois.cs.cs125.questioner.lib

import com.github.slugify.Slugify
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CompilationArguments
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.KtLintFailed
import edu.illinois.cs.cs125.jeed.core.MutatedSource
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jenisol.core.Settings
import edu.illinois.cs.cs125.jenisol.core.SubmissionDesignError
import edu.illinois.cs.cs125.questioner.lib.moshi.Adapters
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.ReflectPermission
import kotlin.math.max
import kotlin.random.Random
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
    "kotlin.reflect.jvm.",
    "com.sun."
)

@Suppress("MemberVisibilityCanBePrivate", "LargeClass", "TooManyFunctions")
@JsonClass(generateAdapter = true)
data class Question(
    val name: String,
    val klass: String,
    val metadata: Metadata,
    val question: FlatFile,
    val correct: FlatFile,
    val alternativeSolutions: List<FlatFile>,
    val incorrect: List<IncorrectFile>,
    val common: List<String>?,
    val javaStarter: FlatFile?,
    val kotlinStarter: FlatFile?,
    var javaTemplate: String?,
    var kotlinTemplate: String?,
    val importWhitelist: Set<String>,
    val importBlacklist: Set<String>,
    val slug: String = Slugify().slugify(name)
) {
    @Suppress("EnumNaming", "EnumEntryName")
    enum class Language { java, kotlin }

    @JsonClass(generateAdapter = true)
    data class Metadata(
        val contentHash: String,
        val packageName: String,
        val version: String,
        val author: String,
        val javaDescription: String,
        val checkstyle: Boolean,
        val solutionThrows: Boolean,
        val maxTestCount: Int,
        val minTestCount: Int,
        val kotlinDescription: String?,
        val citation: Citation?,
        val usedFiles: List<String> = listOf(),
        val focused: Boolean,
        val maxTimeout: Int,
        val timeoutMultiplier: Int
    )

    @JsonClass(generateAdapter = true)
    data class Citation(val source: String, val link: String? = null)

    @JsonClass(generateAdapter = true)
    data class FlatFile(
        val klass: String,
        val contents: String,
        val language: Language,
        val path: String?,
        val complexity: Int? = null
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
        enum class Reason { DESIGN, COMPILE, TEST, CHECKSTYLE, TIMEOUT }
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

    val detemplatedJavaStarter = javaStarter?.contents
    val detemplatedKotlinStarter = kotlinStarter?.contents

    val hasKotlin =
        metadata.kotlinDescription != null && alternativeSolutions.find { it.language == Language.kotlin } != null &&
            ((javaStarter != null) == (kotlinStarter != null))

    // Set during validation
    var validated = false
    var validationSeed: Int? = null
    var requiredTestCount = -1
    var submissionTimeout = -1L
    var solutionPrinted = 0
    var executionDefinedClasses: List<String>? = null

    @Transient
    val defaultJavaClassWhitelist = importWhitelist.toMutableSet().also {
        it.addAll(sharedClassWhitelist)
    }.toSet()
    var javaClassWhitelist = defaultJavaClassWhitelist.toMutableSet()

    @Transient
    val defaultKotlinClassWhitelist = importWhitelist.toMutableSet().also {
        it.addAll(sharedClassWhitelist)
        it.addAll(setOf("java.util.", "kotlin."))
    }.toSet()
    var kotlinClassWhitelist = defaultKotlinClassWhitelist.toMutableSet()

    fun clearValidation() {
        validated = false
        validationSeed = null
        requiredTestCount = -1
        submissionTimeout = -1L
        solutionPrinted = 0
        executionDefinedClasses = null
        javaClassWhitelist = defaultJavaClassWhitelist.toMutableSet()
        kotlinClassWhitelist = defaultKotlinClassWhitelist.toMutableSet()
    }

    @Suppress("ReturnCount", "LongMethod", "ComplexMethod", "LongParameterList")
    suspend fun test(
        contents: String,
        passedSettings: Settings = Settings(),
        seed: Int = Random.nextInt(),
        correct: Boolean? = null,
        language: Language = Language.java
    ): TestResults {

        require(validated || correct != null) { "Jenisol not validated for this question" }

        val results = TestResults(TestResults.Type.jenisol)

        val failOnCheckstyle = if (correct == false) {
            false
        } else {
            metadata.checkstyle
        }

        @Suppress("SwallowedException")
        val compiledSubmission = try {
            if (language == Language.java) {
                compileSubmission(
                    contents,
                    InvertingClassLoader(setOf(klass)),
                    results,
                    failOnCheckstyle
                )
            } else {
                kompileSubmission(
                    contents,
                    InvertingClassLoader(setOf(klass, "${klass}Kt")),
                    results,
                    failOnCheckstyle
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

        val klassName = checkCompiledSubmission(compiledSubmission, results) ?: return results

        val classWhiteList = if (language == Language.java) {
            javaClassWhitelist
        } else {
            kotlinClassWhitelist
        }
        val classLoaderConfiguration = if (correct == true) {
            Sandbox.ClassLoaderConfiguration()
        } else {
            Sandbox.ClassLoaderConfiguration(isWhiteList = true, whitelistedClasses = classWhiteList)
        }

        require(correct != null || submissionTimeout > 0) { "submissionTimeout not set" }

        val (settings, testingTimeout) = when (correct) {
            true -> Pair(passedSettings.copy(seed = seed), DEFAULT_SOLUTION_TIMEOUT)
            false -> Pair(
                passedSettings.copy(
                    seed = seed,
                    overrideTotalCount = MAX_TEST_COUNT,
                    startMultipleCount = MAX_START_MULTIPLE_COUNT
                ), DEFAULT_KNOWN_INCORRECT_TIMEOUT
            )
            else -> {
                require(requiredTestCount > 0) { "requiredTestCount not set" }
                val testCount = (requiredTestCount * SUBMISSION_TEST_MULTIPLIER).coerceAtLeast(metadata.minTestCount)
                Pair(
                    passedSettings.copy(
                        seed = seed,
                        overrideTotalCount = testCount,
                        startMultipleCount = (testCount / 2).coerceAtMost(MAX_START_MULTIPLE_COUNT)
                    ), submissionTimeout
                )
            }
        }

        val maxOutputLines =
            if (correct == true) {
                SOLUTION_TEST_OUTPUT_LINES
            } else {
                solutionPrinted * OUTPUT_LINE_MULTIPLIER
            }.coerceAtLeast(MIN_OUTPUT_LINES)

        val testingResults = Sandbox.execute(
            compiledSubmission.classLoader,
            Sandbox.ExecutionArguments(
                timeout = testingTimeout,
                classLoaderConfiguration = classLoaderConfiguration,
                maxOutputLines = maxOutputLines,
                permissions = SAFE_PERMISSIONS,
                returnTimeout = DEFAULT_RETURN_TIMEOUT
            )
        ) { (classLoader, _) ->
            solution.submission(classLoader.loadClass(klassName), contents).test(settings, ::captureJeedOutput)
        }.also {
            results.taskResults = it
        }

        if (!testingResults.timeout && testingResults.threw != null) {
            when (testingResults.threw) {
                is ClassNotFoundException -> {
                    results.failedSteps.add(TestResults.Step.checkSubmission)
                    results.failed.checkSubmission = "Class design error: could not find class $klass"
                }
                is SubmissionDesignError -> {
                    results.failedSteps.add(TestResults.Step.checkSubmission)
                    results.failed.checkSubmission = "Class design error: ${testingResults.threw?.message}"
                }
                is NoClassDefFoundError -> {
                    results.failedSteps.add(TestResults.Step.checkSubmission)
                    results.failed.checkSubmission =
                        "Class design error: attempted to use unavailable class ${testingResults.threw?.message}"
                }
                else -> {
                    val actualException = if (testingResults.threw is InvocationTargetException) {
                        (testingResults.threw as InvocationTargetException).targetException ?: testingResults.threw
                    } else {
                        testingResults.threw
                    }
                    results.failedSteps.add(TestResults.Step.checkSubmission)
                    results.failed.checkSubmission = "Testing generated an unexpected error: $actualException"
                }
            }
            return results
        }

        results.timeout = testingResults.timeout
        if (testingResults.returned == null) {
            results.failedSteps.add(TestResults.Step.test)
        } else {
            results.completedSteps.add(TestResults.Step.test)
        }

        check(correct == null || (testingResults.timeout || testingResults.truncatedLines == 0)) {
            "Truncated output during validation: ${testingResults.truncatedLines}"
        }

        if (correct == true) {
            when (language) {
                Language.java -> {
                    javaClassWhitelist.addAll(
                        testingResults.sandboxedClassLoader!!.loadedClasses.filter { klass ->
                            !klass.startsWith("edu.illinois.cs.cs125.jeed.core")
                        }
                    )
                }
                Language.kotlin -> {
                    kotlinClassWhitelist.addAll(
                        testingResults.sandboxedClassLoader!!.loadedClasses.filter { klass ->
                            !klass.startsWith("edu.illinois.cs.cs125.jeed.core")
                        }
                    )
                }
            }

            require(testingResults.truncatedLines == 0) { "Truncated output while testing solution" }
            solutionPrinted = max(solutionPrinted, testingResults.outputLines.size)
            executionDefinedClasses =
                executionDefinedClasses ?: testingResults.sandboxedClassLoader!!.definedClasses.topLevelClasses()
        }

        if (!checkExecutedSubmission(testingResults, results, language)) {
            return results
        }

        if (correct == false && !testingResults.timeout) {
            submissionTimeout = max(submissionTimeout, testingResults.executionInterval.length)
        }

        testingResults.returned?.let { it ->
            if (correct == false) {
                requiredTestCount = requiredTestCount.coerceAtLeast(it.size)
            }
            results.complete.testing =
                TestResults.TestingResult(it.map { it.asTestResult(compiledSubmission.source) }, it.size)
        }

        return results
    }

    companion object {
        const val DEFAULT_RETURN_TIMEOUT = 1024
        const val DEFAULT_SOLUTION_TIMEOUT = 4096L
        const val DEFAULT_KNOWN_INCORRECT_TIMEOUT = 1096L
        const val MAX_START_MULTIPLE_COUNT = 128
        const val MAX_TEST_COUNT = 1024 * 1024
        const val SUBMISSION_TEST_MULTIPLIER = 2
        const val SOLUTION_TEST_OUTPUT_LINES = 102400
        const val MIN_OUTPUT_LINES = 1024
        const val OUTPUT_LINE_MULTIPLIER = 8
        const val DEFAULT_MAX_OUTPUT_SIZE = 8 * 1024 * 1024
        const val DEFAULT_MIN_TIMEOUT = 128L
        const val DEFAULT_TIMEOUT_MULTIPLIER = 4.0

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