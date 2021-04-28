package edu.illinois.cs.cs125.questioner.lib

import com.github.slugify.Slugify
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import edu.illinois.cs.cs125.jeed.core.CheckstyleArguments
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CheckstyleResults
import edu.illinois.cs.cs125.jeed.core.CompilationArguments
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.CompiledSource
import edu.illinois.cs.cs125.jeed.core.KompilationArguments
import edu.illinois.cs.cs125.jeed.core.KtLintArguments
import edu.illinois.cs.cs125.jeed.core.KtLintFailed
import edu.illinois.cs.cs125.jeed.core.KtLintResults
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.allMutations
import edu.illinois.cs.cs125.jeed.core.checkstyle
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.fromTemplates
import edu.illinois.cs.cs125.jeed.core.getStackTraceForSource
import edu.illinois.cs.cs125.jeed.core.kompile
import edu.illinois.cs.cs125.jeed.core.ktLint
import edu.illinois.cs.cs125.jeed.core.moshi.CompiledSourceResult
import edu.illinois.cs.cs125.jenisol.core.CapturedResult
import edu.illinois.cs.cs125.jenisol.core.Settings
import edu.illinois.cs.cs125.jenisol.core.SubmissionDesignError
import edu.illinois.cs.cs125.jenisol.core.TestResult
import edu.illinois.cs.cs125.jenisol.core.safePrint
import edu.illinois.cs.cs125.questioner.lib.moshi.Adapters
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.ReflectPermission
import kotlin.math.max
import kotlin.random.Random
import edu.illinois.cs.cs125.jenisol.core.solution as jenisol
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters

private val slugify = Slugify()
private val moshi = Moshi.Builder().apply {
    Adapters.forEach { add(it) }
    JeedAdapters.forEach { add(it) }
}.build()

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
    val slug: String = slugify.slugify(name)
) {
    fun toJson(): String = moshi.adapter(Question::class.java).toJson(this)

    @JsonClass(generateAdapter = true)
    data class Metadata(
        val contentHash: String,
        val packageName: String,
        val version: String,
        val author: String,
        val javaDescription: String,
        val points: Int,
        val timeoutMultiplier: Double,
        val minTimeout: Long,
        val mutate: Boolean,
        val checkstyle: Boolean,
        val solutionThrows: Boolean,
        val maxTestCount: Int,
        val minTestCount: Int,
        val kotlinDescription: String?,
        val citation: Citation?
    )

    fun getTemplate(language: Language) = when (language) {
        Language.java -> javaTemplate
        Language.kotlin -> kotlinTemplate
    }

    @JsonClass(generateAdapter = true)
    data class Citation(val source: String, val link: String? = null)

    @Suppress("EnumNaming", "EnumEntryName")
    enum class Language { java, kotlin }

    @JsonClass(generateAdapter = true)
    data class FlatFile(val klass: String, val contents: String, val language: Language)

    @JsonClass(generateAdapter = true)
    data class IncorrectFile(val klass: String, val contents: String, val reason: Reason, val language: Language) {
        enum class Reason { DESIGN, COMPILE, TEST, CHECKSTYLE, TIMEOUT }
    }

    @delegate:Transient
    val compiledCommon by lazy {
        if (common?.isNotEmpty() == true) {
            val commonSources = common.mapIndexed { index, contents -> "Common$index.java" to contents }.toMap()
            Source(commonSources).compile(
                CompilationArguments(isolatedClassLoader = true, parameters = true)
            )
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

    var executionDefinedClasses: List<String>? = null

    fun Set<String>.topLevelClasses() = map { it.split("$").first() }.distinct()

    @delegate:Transient
    val solution by lazy {
        jenisol(compiledSolution.classLoader.loadClass(klass))
    }

    fun Language.extension() = when (this) {
        Language.java -> "java"
        Language.kotlin -> "kt"
    }

    private fun templateSubmission(contents: String, language: Language = Language.java): Source {
        val template = getTemplate(language)
        val fileName = "$klass.${language.extension()}"
        return if (template == null) {
            Source(mapOf(fileName to contents))
        } else {
            Source.fromTemplates(
                mapOf("$klass.${language.extension()}" to contents),
                mapOf("$klass.${language.extension()}.hbs" to template)
            )
        }
    }

    @Suppress("ThrowsCount")
    private fun compileSubmission(
        contents: String,
        parentClassLoader: ClassLoader,
        testResults: TestResults,
        failOnCheckstyle: Boolean = true
    ): CompiledSource {
        return try {
            val actualParents = Pair(compiledCommon?.classLoader ?: parentClassLoader, compiledCommon?.fileManager)
            val source = templateSubmission(contents).also {
                if (getTemplate(Language.java) != null) {
                    testResults.completedSteps.add(TestResults.Step.templateSubmission)
                }
            }
            val compiledSource = source.compile(
                CompilationArguments(
                    parentClassLoader = actualParents.first,
                    parentFileManager = actualParents.second,
                    parameters = true
                )
            ).also {
                testResults.complete.compileSubmission = CompiledSourceResult(it)
                testResults.completedSteps.add(TestResults.Step.compileSubmission)
            }
            testResults.complete.checkstyle = source.checkstyle(CheckstyleArguments(failOnError = failOnCheckstyle))
            testResults.completedSteps.add(TestResults.Step.checkstyle)
            compiledSource
        } catch (e: TemplatingFailed) {
            testResults.failed.templateSubmission = e
            testResults.failedSteps.add(TestResults.Step.templateSubmission)
            throw e
        } catch (e: CheckstyleFailed) {
            testResults.failed.checkstyle = e
            testResults.failedSteps.add(TestResults.Step.checkstyle)
            throw e
        } catch (e: CompilationFailed) {
            testResults.failed.compileSubmission = e
            testResults.failedSteps.add(TestResults.Step.compileSubmission)
            throw e
        }
    }

    @Suppress("ThrowsCount")
    private fun kompileSubmission(
        contents: String,
        parentClassLoader: ClassLoader,
        testResults: TestResults,
        failOnKtlint: Boolean = true
    ): CompiledSource {
        return try {
            val actualParents = Pair(compiledCommon?.classLoader ?: parentClassLoader, compiledCommon?.fileManager)
            val source = templateSubmission(contents, Language.kotlin).also {
                if (kotlinTemplate != null) {
                    testResults.completedSteps.add(TestResults.Step.templateSubmission)
                }
            }
            val compiledSource = source.kompile(
                KompilationArguments(
                    parentClassLoader = actualParents.first,
                    parentFileManager = actualParents.second,
                    parameters = true
                )
            ).also {
                testResults.complete.compileSubmission = CompiledSourceResult(it)
                testResults.completedSteps.add(TestResults.Step.compileSubmission)
            }
            testResults.complete.ktlint = source.ktLint(KtLintArguments(failOnError = failOnKtlint))
            testResults.completedSteps.add(TestResults.Step.ktlint)
            compiledSource
        } catch (e: TemplatingFailed) {
            testResults.failed.templateSubmission = e
            testResults.failedSteps.add(TestResults.Step.templateSubmission)
            throw e
        } catch (e: KtLintFailed) {
            testResults.failed.ktlint = e
            testResults.failedSteps.add(TestResults.Step.ktlint)
            throw e
        } catch (e: CompilationFailed) {
            testResults.failed.compileSubmission = e
            testResults.failedSteps.add(TestResults.Step.compileSubmission)
            throw e
        }
    }

    private fun checkCompiledSubmission(
        compiledSubmission: CompiledSource,
        testResults: TestResults
    ): String? = compiledSubmission.classLoader.definedClasses.topLevelClasses().let {
        when {
            it.isEmpty() -> {
                testResults.failed.checkSubmission = "Submission defined no classes"
                testResults.failedSteps.add(TestResults.Step.checkSubmission)
                return null
            }
            it.size > 1 -> {
                testResults.failed.checkSubmission = "Submission defined multiple classes"
                testResults.failedSteps.add(TestResults.Step.checkSubmission)
                return null
            }
        }
        val klass = it.first()
        if (compiledSubmission.source.type == Source.FileType.KOTLIN &&
            solution.skipReceiver &&
            klass == "${compilationDefinedClass}Kt"
        ) {
            return "${compilationDefinedClass}Kt"
        }
        if (klass != compilationDefinedClass) {
            testResults.failed.checkSubmission =
                "Submission defines incorrect class: ${it.first()} != $compilationDefinedClass"
            testResults.failedSteps.add(TestResults.Step.checkSubmission)
            return null
        }
        return klass
    }

    @Suppress("ReturnCount")
    private fun checkExecutedSubmission(
        taskResults: Sandbox.TaskResults<*>,
        testResults: TestResults,
        language: Language
    ): Boolean {
        var message: String? = null
        taskResults.sandboxedClassLoader!!.definedClasses.topLevelClasses().let {
            when {
                it.isEmpty() -> message = "Submission defined no classes"
                it.size > 1 -> message = "Submission defined multiple classes"
            }
            val klass = it.first()
            if (!(
                        language == Language.kotlin &&
                                solution.skipReceiver &&
                                klass == "${compilationDefinedClass}Kt"
                        )
            ) {
                if (klass != compilationDefinedClass) {
                    message =
                        "Submission defines incorrect class: ${it.first()} != $compilationDefinedClass"
                }
            }
        }
        taskResults.sandboxedClassLoader?.loadedClasses?.find { imported ->
            importBlacklist.any { imported.startsWith(it) }
        }?.let {
            message = "Cannot use $it for this problem"
        }
        taskResults.permissionRequests.filter { !it.granted }.let { denied ->
            if (denied.isNotEmpty()) {
                val deniedPermission = denied.find { it.permission.name.startsWith("loadClass") }
                message = if (deniedPermission != null) {
                    "Cannot use ${deniedPermission.permission.name.removePrefix("loadClass ")} for this problem"
                } else {
                    "Submission permission requests were denied: ${denied.first().permission}"
                }
            }
        }
        return if (message != null) {
            testResults.failed.checkSubmission = message
            testResults.failedSteps.add(TestResults.Step.checkSubmission)
            false
        } else {
            true
        }
    }

    @Transient
    val sharedClassWhitelist = setOf(
        "java.lang.",
        "java.io.PrintStream",
        "kotlin.Metadata",
        "jdk.internal.reflect.",
        "kotlin.reflect.jvm.",
        "com.sun."
    )

    var javaClassWhitelist: MutableSet<String> = importWhitelist.toMutableSet().also {
        it.addAll(sharedClassWhitelist)
    }

    var kotlinClassWhitelist: MutableSet<String> = importWhitelist.toMutableSet().also {
        it.addAll(sharedClassWhitelist)
        it.addAll(setOf("java.util.", "kotlin."))
    }

    var requiredTestCount = -1
    var submissionTimeout = -1L

    @Transient
    var slowestFailingContent = ""

    var solutionPrinted = 0

    @Suppress("ReturnCount", "LongMethod", "ComplexMethod", "LongParameterList")
    suspend fun test(
        contents: String,
        passedSettings: Settings = Settings(),
        seed: Int = Random.nextInt(),
        correct: Boolean? = null,
        language: Language = Language.java,
        timeoutAdjustment: Double = 1.0
    ): TestResults {

        require(validated || correct != null) { "Jenisol not validated for this question" }

        val testResults = TestResults(TestResults.Type.jenisol)

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
                    testResults,
                    failOnCheckstyle
                )
            } else {
                kompileSubmission(
                    contents,
                    InvertingClassLoader(setOf(klass, "${klass}Kt")),
                    testResults,
                    failOnCheckstyle
                )
            }
        } catch (e: TemplatingFailed) {
            return testResults
        } catch (e: CheckstyleFailed) {
            return testResults
        } catch (e: CompilationFailed) {
            return testResults
        } catch (e: KtLintFailed) {
            return testResults
        }

        val klassName = checkCompiledSubmission(compiledSubmission, testResults) ?: return testResults

        testResults.skippedSteps.add(TestResults.Step.compileTest)

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
                    ), (submissionTimeout.toDouble() * timeoutAdjustment).toLong()
                )
            }
        }

        val maxOutputLines =
            if (correct == true) {
                SOLUTION_TEST_OUTPUT_LINES
            } else {
                solutionPrinted * OUTPUT_LINE_MULTIPLIER
            }.coerceAtLeast(MIN_OUTPUT_LINES)

        Sandbox.execute(
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
        }.also { taskResults ->
            testResults.taskResults = taskResults
            if (!taskResults.timeout && taskResults.threw != null) {
                return when (taskResults.threw) {
                    is ClassNotFoundException -> {
                        testResults.failedSteps.add(TestResults.Step.checkSubmission)
                        testResults.failed.checkSubmission = "Class design error: could not find class $klass"
                        testResults
                    }
                    is SubmissionDesignError -> {
                        testResults.failedSteps.add(TestResults.Step.checkSubmission)
                        testResults.failed.checkSubmission = "Class design error: ${taskResults.threw?.message}"
                        testResults
                    }
                    is NoClassDefFoundError -> {
                        testResults.failedSteps.add(TestResults.Step.checkSubmission)
                        testResults.failed.checkSubmission =
                            "Class design error: attempted to use unavailable class ${taskResults.threw?.message}"
                        testResults
                    }
                    else -> {
                        val actualException = if (taskResults.threw is InvocationTargetException) {
                            (taskResults.threw as InvocationTargetException).targetException ?: taskResults.threw
                        } else {
                            taskResults.threw
                        }
                        testResults.failedSteps.add(TestResults.Step.checkSubmission)
                        actualException?.printStackTrace()
                        testResults.failed.checkSubmission =
                            "Testing generated an unexpected error: $actualException"
                        testResults
                    }
                }
            }

            if (taskResults.timeout && !(correct == true && taskResults.returned != null)) {
                testResults.timeout = true
                testResults.failedSteps.add(TestResults.Step.test)
            } else {
                testResults.completedSteps.add(TestResults.Step.test)
            }

            check(correct == null || (taskResults.timeout || taskResults.truncatedLines == 0)) {
                "Truncated output during validation: ${taskResults.truncatedLines}"
            }

            if (correct == true) {
                if (language == Language.java) {
                    javaClassWhitelist.addAll(
                        taskResults.sandboxedClassLoader!!.loadedClasses.filter { klass ->
                            !klass.startsWith("edu.illinois.cs.cs125.jeed.core")
                        }
                    )
                } else if (language == Language.kotlin) {
                    kotlinClassWhitelist.addAll(
                        taskResults.sandboxedClassLoader!!.loadedClasses.filter { klass ->
                            !klass.startsWith("edu.illinois.cs.cs125.jeed.core")
                        }
                    )
                }
                require(taskResults.truncatedLines == 0) { "Truncated output while testing solution" }
                solutionPrinted = max(solutionPrinted, taskResults.outputLines.size)
                if (executionDefinedClasses == null) {
                    executionDefinedClasses = taskResults.sandboxedClassLoader!!.definedClasses.topLevelClasses()
                }
            }

            if (!checkExecutedSubmission(taskResults, testResults, language)) {
                return testResults
            }

            if (correct == false && !taskResults.timeout) {
                submissionTimeout = max(submissionTimeout, taskResults.executionInterval.length)
            }
            taskResults.returned?.let { results ->
                if (correct == false && results.size > requiredTestCount) {
                    requiredTestCount = results.size
                    slowestFailingContent = compiledSubmission.source.contents
                }
                testResults.complete.testing =
                    TestResults.TestingResult(results.map { it.asTestResult(compiledSubmission.source) }, results.size)
            }
        }
        return testResults
    }

    var validated = false

    data class ValidationReport(
        val incorrect: Int,
        val mutations: Int,
        val requiredTestCount: Int,
        val kotlin: Boolean,
        val kotlinIncorrect: Int,
        val slowestFailing: String?,
        val timeout: Long
    ) {
        fun summary() = copy(slowestFailing = null).toString()
    }

    @Suppress("LongMethod", "ComplexMethod")
    suspend fun initialize(addMutations: Boolean = true, seed: Int = Random.nextInt()): ValidationReport {
        val jenisolSettings = Settings(shrink = false)

        test(
            correct.contents,
            jenisolSettings,
            correct = true,
            seed = seed
        ).also { results ->
            require(results.succeeded) {
                "Solution did not pass the test suite: ${results.summary} ${correct.contents}"
            }
            if (!metadata.solutionThrows) {
                val solutionThrew =
                    results.complete.testing?.tests?.find {
                        it.jenisol?.solution?.threw != null
                    }
                check(solutionThrew == null) { "Solution not expected to throw: $solutionThrew" }
            }
            results.checkSize()
        }

        alternativeSolutions.forEach { alsoCorrect ->
            test(
                alsoCorrect.contents,
                jenisolSettings,
                language = alsoCorrect.language,
                correct = true,
                seed = seed
            ).also { results ->
                require(results.succeeded) {
                    "Solution did not pass the test suite: ${results.summary}\n${alsoCorrect.contents}"
                }
                if (!metadata.solutionThrows) {
                    val solutionThrew =
                        results.complete.testing?.tests?.find {
                            it.jenisol?.solution?.threw != null
                        }?.explanation
                    check(solutionThrew == null) { "Solution not expected to throw: $solutionThrew" }
                }
                results.checkSize()
            }
        }

        val incorrectToTest = (
                incorrect + if (metadata.mutate && addMutations) {
                    templateSubmission(
                        if (getTemplate(Language.java) != null) {
                            "// TEMPLATE_START\n" + correct.contents + "\n// TEMPLATE_END \n"
                        } else {
                            correct.contents
                        }
                    ).allMutations(random = Random(seed))
                        .map { it.contents.kotlinDeTemplate(getTemplate(Language.java)) }
                        // Templated questions sometimes will mutate the template
                        .filter { it != correct.contents }
                        .map { IncorrectFile(klass, it, IncorrectFile.Reason.TEST, Language.java) }
                } else {
                    listOf()
                }.also { incorrect ->
                    check(incorrect.all { it.contents != correct.contents }) {
                        "Incorrect solution identical to correct solution"
                    }
                }
                ).also {
                check(incorrect.isNotEmpty()) { "No incorrect examples found" }
            }

        incorrectToTest.forEachIndexed { index, wrong ->
            val isMutated = index >= incorrect.size
            test(
                wrong.contents,
                jenisolSettings,
                correct = false,
                language = wrong.language,
                seed = seed
            ).also {
                require(!it.succeeded) { "Incorrect submission was not rejected:\n${wrong.contents}" }
                it.validate(wrong.reason, wrong.contents, isMutated)
                it.checkSize()
            }
        }

        submissionTimeout = if (submissionTimeout == 0L) {
            1
        } else {
            submissionTimeout
        }
        val report = ValidationReport(
            incorrectToTest.size,
            (incorrectToTest.size - incorrect.size),
            requiredTestCount,
            hasKotlin,
            incorrect.count { it.language == Language.kotlin },
            slowestFailingContent,
            submissionTimeout
        )
        check(requiredTestCount > 0)
        require(requiredTestCount <= metadata.maxTestCount) {
            "Requires too many tests: $report"
        }

        validated = true

        test(
            correct.contents,
            jenisolSettings,
            seed = seed,
            timeoutAdjustment = 1024.0
        ).also { results ->
            require(results.succeeded) {
                "Untimed solution did not pass the test suite after validation: ${results.summary} ${correct.contents}"
            }
            submissionTimeout = (results.taskResults!!.interval.length.toDouble() * metadata.timeoutMultiplier).toLong()
                .coerceAtLeast(metadata.minTimeout)
            solutionPrinted = results.taskResults!!.outputLines.size
            results.checkSize()
        }

        check(submissionTimeout > 0)

        (listOf(Pair(correct.contents, Language.java)) + alternativeSolutions.map {
            Pair(
                it.contents,
                it.language
            )
        }).forEach { (contents, language) ->
            test(
                contents,
                language = language,
                seed = seed
            ).also { results ->
                require(results.succeeded) {
                    "Solution did not pass the test suite after validation: ${results.summary}\n$contents"
                }
                results.checkSize()
            }
        }

        incorrectToTest.forEachIndexed { index, wrong ->
            val isMutated = index >= incorrect.size
            test(
                wrong.contents,
                jenisolSettings,
                language = wrong.language,
                seed = seed
            ).also {
                require(!it.succeeded) { "Incorrect submission was not rejected:\n${wrong.contents}" }
                it.validate(wrong.reason, wrong.contents, isMutated)
                it.checkSize()
            }
        }

        return report
    }

    var detemplatedJavaStarter = javaStarter?.contents
    var detemplatedKotlinStarter = kotlinStarter?.contents

    val hasKotlin =
        metadata.kotlinDescription != null && alternativeSolutions.find { it.language == Language.kotlin } != null &&
                ((javaStarter != null) == (kotlinStarter != null))

    companion object {
        const val DEFAULT_RETURN_TIMEOUT = 1024
        const val DEFAULT_SOLUTION_TIMEOUT = 4096L
        const val DEFAULT_KNOWN_INCORRECT_TIMEOUT = 4096L
        const val MAX_START_MULTIPLE_COUNT = 128
        const val MAX_TEST_COUNT = 1024 * 1024
        const val SUBMISSION_TEST_MULTIPLIER = 2
        const val SOLUTION_TEST_OUTPUT_LINES = 102400
        const val MIN_OUTPUT_LINES = 1024
        const val OUTPUT_LINE_MULTIPLIER = 8
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

@JsonClass(generateAdapter = true)
data class TestResults(
    val type: Type,
    val completedSteps: MutableSet<Step> = mutableSetOf(),
    val complete: CompletedTasks = CompletedTasks(),
    val failedSteps: MutableSet<Step> = mutableSetOf(),
    val failed: FailedTasks = FailedTasks(),
    val skippedSteps: MutableSet<Step> = mutableSetOf(),
    var timeout: Boolean = false,
    @Transient
    var taskResults: Sandbox.TaskResults<*>? = null
) {
    @Suppress("EnumNaming", "EnumEntryName")
    enum class Type { jenisol }

    @Suppress("EnumNaming", "EnumEntryName")
    enum class Step {
        templateSubmission,
        checkstyle,
        ktlint,
        compileSubmission,
        checkSubmission,
        compileTest,
        test,
    }

    @JsonClass(generateAdapter = true)
    data class CompletedTasks(
        var compileSubmission: CompiledSourceResult? = null,
        var checkstyle: CheckstyleResults? = null,
        var ktlint: KtLintResults? = null,
        var compileTest: CompiledSourceResult? = null,
        var testing: TestingResult? = null
    )

    @JsonClass(generateAdapter = true)
    data class TestingResult(
        val tests: List<TestResult>,
        val testCount: Int,
        val passed: Boolean = tests.size == testCount && tests.none { !it.passed }
    ) {
        @JsonClass(generateAdapter = true)
        data class TestResult(
            val name: String,
            val passed: Boolean,
            val message: String? = null,
            val arguments: String? = null,
            val expected: String? = null,
            val found: String? = null,
            val explanation: String? = null,
            val output: String? = null,
            val complexity: Int? = null,
            @Transient val jenisol: edu.illinois.cs.cs125.jenisol.core.TestResult<*, *>? = null,
            val submissionStackTrace: String? = null
        )
    }

    @JsonClass(generateAdapter = true)
    data class FailedTasks(
        var templateSubmission: TemplatingFailed? = null,
        var compileSubmission: CompilationFailed? = null,
        var checkstyle: CheckstyleFailed? = null,
        var checkSubmission: String? = null,
        var compileTest: CompilationFailed? = null,
        var ktlint: KtLintFailed? = null
    )

    @Suppress("MemberVisibilityCanBePrivate", "unused")
    val completed: Boolean
        get() = completedSteps.contains(Step.test)
    val succeeded: Boolean
        get() = !timeout && complete.testing?.passed ?: false

    val summary: String
        get() = if (failed.templateSubmission != null) {
            "Templating failed${failed.templateSubmission?.message?.let { ": $it" } ?: ""}"
        } else if (failed.compileSubmission != null) {
            "Compiling submission failed${failed.compileSubmission?.let { ": $it" } ?: ""}"
        } else if (failed.checkstyle != null) {
            "Checkstyle failed:${failed.checkstyle?.let { ": $it" } ?: ""}"
        } else if (failed.ktlint != null) {
            "Ktlint failed:${failed.ktlint?.let { ": $it" } ?: ""}"
        } else if (failed.checkSubmission != null) {
            "Checking submission failed: ${failed.checkSubmission}"
        } else if (failed.compileTest != null) {
            "Compiling test failed: ${failed.compileTest?.message?.let { ": $it" } ?: ""}"
        } else if (timeout) {
            "Testing timed out"
        } else if (!succeeded) {
            "Testing failed: ${complete.testing!!.tests.find { !it.passed }}"
        } else {
            "Passed"
        }

    fun validate(reason: Question.IncorrectFile.Reason, contents: String, isMutated: Boolean) {
        when (reason) {
            Question.IncorrectFile.Reason.COMPILE -> require(failed.compileSubmission != null) {
                "Expected submission not to compile: ${summary}\n$contents"
            }
            Question.IncorrectFile.Reason.CHECKSTYLE -> require(failed.checkstyle != null) {
                "Expected submission to fail checkstyle: ${summary}\n$contents"
            }
            Question.IncorrectFile.Reason.DESIGN -> require(failed.checkSubmission != null) {
                "Expected submission to fail design: ${summary}\n$contents"
            }
            Question.IncorrectFile.Reason.TIMEOUT -> require(timeout || !succeeded) {
                "Expected submission to timeout: ${summary}\n$contents"
            }
            else -> require(isMutated || (!timeout && complete.testing?.passed == false)) {
                "Expected submission to fail tests: ${summary}\n$contents"
            }
        }
    }

    fun toJson(): String = moshi.adapter(TestResults::class.java).toJson(this)
    fun checkSize(maxSize: Int = Question.DEFAULT_MAX_OUTPUT_SIZE) = toJson().length.also {
        check(it < maxSize) { "Output is too large: $it > $maxSize" }
    }
}

fun TestResult<*, *>.asTestResult(source: Source) = TestResults.TestingResult.TestResult(
    solutionExecutable.name,
    succeeded,
    verifierThrew?.message,
    parameters.toString(),
    @Suppress("TooGenericExceptionCaught")
    solution.returned?.safePrint(),
    submission.returned?.safePrint(),
    if (!succeeded) {
        explain()
    } else {
        null
    },
    submission.stdout,
    complexity,
    this,
    submission.threw?.getStackTraceForSource(
        source,
        boundaries = listOf(
            "at edu.illinois.cs.cs125.jenisol.core.TestRunner",
            "at jdk.internal.reflect.",
            "at java.base"
        )
    )
)

class InvertingClassLoader(private val inversions: Set<String>) : ClassLoader() {
    // Invert the usual delegation strategy for classes in this package to avoid using the system ClassLoader
    override fun loadClass(name: String): Class<*> {
        return if (name in inversions) {
            throw ClassNotFoundException()
        } else {
            super.loadClass(name)
        }
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        return if (name in inversions) {
            throw ClassNotFoundException()
        } else {
            super.loadClass(name, resolve)
        }
    }
}

fun captureJeedOutput(run: () -> Any?): CapturedResult = Sandbox.redirectOutput(run).let {
    CapturedResult(it.returned, it.threw, it.stdout, it.stderr)
}

private fun String.kotlinDeTemplate(template: String?): String {
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

fun loadFromResources(): Map<String, Question> {
    return moshi.adapter<Map<String, Question>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Question::class.java
        )
    ).fromJson(object {}::class.java.getResource("/questions.json")!!.readText())!!.toMutableMap().also { questions ->
        questions.entries.forEach { (slug, question) ->
            object {}::class.java.getResource("/${question.metadata.contentHash}-validated.json")?.readText()
                ?.also { it -> questions[slug] = moshi.adapter(Question::class.java).fromJson(it)!! }
        }
    }.toMap()
}

fun loadFromFiles(questionsFile: File, validationDirectory: File): Map<String, Question> {
    require(questionsFile.exists())
    return moshi.adapter<Map<String, Question>>(
        Types.newParameterizedType(
            Map::class.java,
            String::class.java,
            Question::class.java
        )
    ).fromJson(questionsFile.readText())!!.toMutableMap().also { questions ->
        questions.entries.forEach { (slug, question) ->
            File(validationDirectory, "${question.metadata.contentHash}-validated.json").also {
                if (it.exists()) {
                    questions[slug] = moshi.adapter(Question::class.java).fromJson(it.readText())!!
                }
            }
        }
    }.toMap()
}

fun Collection<Question>.toJSON(): String =
    moshi.adapter<List<Question>>(Types.newParameterizedType(List::class.java, Question::class.java))
        .toJson(this.toList())