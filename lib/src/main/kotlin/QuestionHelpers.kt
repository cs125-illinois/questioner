package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jeed.core.CheckstyleArguments
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CompilationArguments
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.CompiledSource
import edu.illinois.cs.cs125.jeed.core.KompilationArguments
import edu.illinois.cs.cs125.jeed.core.KtLintArguments
import edu.illinois.cs.cs125.jeed.core.KtLintFailed
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.SnippetArguments
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.allFixedMutations
import edu.illinois.cs.cs125.jeed.core.checkstyle
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.complexity
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.fromTemplates
import edu.illinois.cs.cs125.jeed.core.kompile
import edu.illinois.cs.cs125.jeed.core.ktLint
import edu.illinois.cs.cs125.jeed.core.moshi.CompiledSourceResult
import edu.illinois.cs.cs125.jenisol.core.CapturedResult
import java.lang.reflect.InvocationTargetException
import kotlin.random.Random

fun Question.templateSubmission(contents: String, language: Question.Language = Question.Language.java): Source {
    val template = getTemplate(language)
    val fileName = "$klass.${language.extension()}"
    return if (template == null) {
        Source(mapOf(fileName to contents))
    } else {
        Source.fromTemplates(
            mapOf("$klass.${language.extension()}" to contents.trimEnd()),
            mapOf("$klass.${language.extension()}.hbs" to template)
        )
    }
}

@Suppress("ThrowsCount")
fun Question.compileSubmission(
    contents: String,
    parentClassLoader: ClassLoader,
    testResults: TestResults
): CompiledSource {
    return try {
        val actualParents = Pair(compiledCommon?.classLoader ?: parentClassLoader, compiledCommon?.fileManager)
        val source = templateSubmission(contents).also {
            if (getTemplate(Question.Language.java) != null) {
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
        testResults.addCheckstyleResults(source.checkstyle(CheckstyleArguments(failOnError = false)))
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
fun Question.kompileSubmission(
    contents: String,
    parentClassLoader: ClassLoader,
    testResults: TestResults
): CompiledSource {
    return try {
        val actualParents = Pair(compiledCommon?.classLoader ?: parentClassLoader, compiledCommon?.fileManager)
        val source = templateSubmission(contents, Question.Language.kotlin).also {
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
        testResults.addKtlintResults(source.ktLint(KtLintArguments(failOnError = false)))
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

fun Question.checkCompiledSubmission(
    compiledSubmission: CompiledSource,
    contents: String,
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
    var klass = it.first()
    if (compiledSubmission.source.type == Source.FileType.KOTLIN &&
        (solution.skipReceiver || solution.fauxStatic) &&
        klass == "${compilationDefinedClass}Kt"
    ) {
        klass = "${compilationDefinedClass}Kt"
    } else {
        if (klass != compilationDefinedClass) {
            testResults.failed.checkSubmission =
                "Submission defines incorrect class: ${it.first()} != $compilationDefinedClass"
            testResults.failedSteps.add(TestResults.Step.checkSubmission)
            return null
        }
    }
    if (sourceChecker != null) {
        try {
            sourceChecker?.invoke(null, contents)
        } catch (e: InvocationTargetException) {
            testResults.failed.checkSubmission =
                "Checking source failed" + if (e.cause?.message != null) {
                    ": ${e.cause!!.message}"
                } else {
                    ""
                }
            testResults.failedSteps.add(TestResults.Step.checkSubmission)
            return null
        }
    }
    return klass
}

@Suppress("ReturnCount")
fun Question.checkExecutedSubmission(
    taskResults: Sandbox.TaskResults<*>,
    testResults: TestResults,
    language: Question.Language
): Boolean {
    var message: String? = null
    taskResults.sandboxedClassLoader!!.definedClasses.topLevelClasses().let {
        when {
            it.isEmpty() -> message = "Submission defined no classes"
            it.size > 1 -> message = "Submission defined multiple classes"
        }
        val klass = it.first()
        if (!(
                language == Question.Language.kotlin &&
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
    taskResults.sandboxedClassLoader!!.loadedClasses.find { imported ->
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

fun Question.mutations(seed: Int, count: Int) = templateSubmission(
    if (getTemplate(Question.Language.java) != null) {
        "// TEMPLATE_START\n" + correct.contents + "\n// TEMPLATE_END \n"
    } else {
        correct.contents
    }
).allFixedMutations(random = Random(seed))
    .map {
        // Mutations will sometimes break the entire template
        Pair(
            try {
                it.contents.deTemplate(getTemplate(Question.Language.java))
            } catch (e: Exception) {
                correct.contents
            }, it
        )
    }
    // Templated questions sometimes will mutate the template
    .filter { (contents, _) -> contents != correct.contents }
    .take(count)
    .map { (contents, source) ->
        Question.IncorrectFile(
            klass,
            contents,
            Question.IncorrectFile.Reason.TEST,
            Question.Language.java,
            null,
            false,
            mutation = source
        )
    }

fun Question.computeComplexity(contents: String, language: Question.Language): Question.ComplexityComparison {
    val solutionComplexity = if (language == Question.Language.java) {
        correct.complexity
    } else {
        alternativeSolutions.filter { it.language == language }.mapNotNull { it.complexity }.minOrNull()
    }
    check(solutionComplexity != null) { "Solution complexity not available" }

    val submissionComplexity = when {
        type == Question.Type.SNIPPET && contents.isBlank() -> 0
        language == Question.Language.java -> {
            val source = when (type) {
                Question.Type.KLASS -> Source(mapOf("$klass.java" to contents))
                Question.Type.METHOD -> Source(
                    mapOf(
                        "$klass.java" to """public class $klass {
                    |$contents
                    }""".trimMargin()
                    )
                )
                Question.Type.SNIPPET -> Source.fromSnippet(contents)
            }
            source.complexity().let { results ->
                when (type) {
                    Question.Type.KLASS -> results.lookupFile("$klass.java")
                    Question.Type.METHOD -> results.lookup(klass, "$klass.java").complexity
                    Question.Type.SNIPPET -> results.lookup("").complexity
                }
            }
        }
        language == Question.Language.kotlin -> {
            val source = when (type) {
                Question.Type.SNIPPET -> Source.fromSnippet(
                    contents,
                    SnippetArguments(fileType = Source.FileType.KOTLIN)
                )
                else -> Source(mapOf("$klass.kt" to contents))
            }
            source.complexity().let { results ->
                when (type) {
                    Question.Type.SNIPPET -> results.lookup("").complexity
                    else -> results.lookupFile("$klass.kt")
                }
            }
        }
        else -> error("Shouldn't get here")
    }
    return Question.ComplexityComparison(solutionComplexity, submissionComplexity, control.maxExtraComplexity)
}

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



