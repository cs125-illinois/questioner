@file:Suppress("TooManyFunctions")

package edu.illinois.cs.cs125.questioner.plugin.save

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import edu.illinois.cs.cs125.questioner.lib.Question
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginConvention
import org.gradle.api.tasks.TaskAction
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.stream.Collectors

private val moshi = Moshi.Builder().build()

@Suppress("unused")
open class SaveQuestions : DefaultTask() {
    init {
        group = "Build"
        description = "Save questions to JSON."
    }

    var email: String? = null

    @TaskAction
    fun save() {
        val resourcesDirectory = File(project.buildDir, "questioner")
        project.convention.getPlugin(JavaPluginConvention::class.java)
            .sourceSets.getByName("main").resources { it.srcDirs(resourcesDirectory) }
        File(resourcesDirectory, "questions.json").let { file ->
            file.parentFile.mkdirs()
            file.writeText(
                moshi.adapter<Map<String, Question>>(
                    Types.newParameterizedType(
                        Map::class.java,
                        String::class.java,
                        Question::class.java
                    )
                )
                    .indent("  ")
                    .toJson(
                        project.getQuestions().map {
                            it.name to it
                        }.toMap()
                    )
            )
        }
    }
}

fun Project.getQuestions(): List<Question> {
    val allFiles = convention.getPlugin(JavaPluginConvention::class.java)
        .sourceSets.getByName("main").allSource
        .filter { it.name.endsWith(".java") || it.name.endsWith(".kt") }
    val parsedJavaFiles = allFiles.filter { it.name.endsWith(".java") }.map { ParsedJavaFile(it) }
    return parsedJavaFiles.findQuestions(allFiles.map { it.path })
}

@Suppress("LongMethod", "ComplexMethod")
fun List<ParsedJavaFile>.findQuestions(allPaths: List<String>): List<Question> {
    map { it.fullName }.groupingBy { it }.eachCount().filter { it.value > 1 }.also { duplicates ->
        if (duplicates.isNotEmpty()) {
            error("Files with duplicate qualified names found: ${duplicates.keys}")
        }
    }

    val byFullName = map { it.fullName to it }.toMap()

    val solutions = filter { it.correct != null }
    solutions.map { it.correct!!.name }.groupingBy { it }.eachCount().filter { it.value > 1 }.also { duplicates ->
        if (duplicates.isNotEmpty()) {
            error("Duplicate questions found: ${duplicates.keys}")
        }
    }
    solutions.map { it.packageName }.sorted().zipWithNext().forEach { (first, second) ->
        if (second.startsWith("$first.")) {
            error("Question package names cannot be nested: $second is inside $first")
        }
    }

    val otherFiles = filter { it.correct == null }
    val usedFiles = solutions.map { it.path to "Correct" }.toMap().toMutableMap()
    val knownFiles = map { it.path }

    val questions = solutions.map { solution ->
        require(solution.correct != null) { "Solutions should have @Correct metadata" }
        require(solution.packageName != "") { "Solutions should not have an empty package name" }
        require(solution.className != "") { "Solutions should not have an empty class name" }

        try {
            val allContentHash = if (Files.isRegularFile(Paths.get(solution.path))) {
                Files.walk(Paths.get(solution.path).parent).filter { path ->
                    Files.isRegularFile(path)
                }.map { File(it.toString()) }.collect(Collectors.toList()).toMutableList().sortedBy { it.path }
                    .map { it.path to it.readText() }.toMap()
            } else {
                mapOf()
            }.let {
                moshi.adapter<Map<String, String>>(
                    Types.newParameterizedType(
                        Map::class.java,
                        String::class.java,
                        String::class.java
                    )
                )
                    .indent("  ")
                    .toJson(it)
            }.let { json ->
                MessageDigest.getInstance("SHA-256").let { digest ->
                    digest.digest(json.toByteArray())
                }.fold("", { str, it -> str + "%02x".format(it) })
            }

            val kotlinFiles = if (Files.isRegularFile(Paths.get(solution.path))) {
                Files.walk(Paths.get(solution.path).parent).filter { path ->
                    !knownFiles.contains(path.toString()) && Files.isRegularFile(path) && path.toString()
                        .endsWith(".kt")
                }.map { it.toString() }.collect(Collectors.toSet())
            } else {
                setOf<String>()
            }.map {
                ParsedKotlinFile(File(it))
            }

            val javaStarter =
                otherFiles.filter { it.packageName.startsWith("${solution.packageName}.") }
                    .filter { it.starter != null }.let {
                        assert(it.size <= 1) {
                            "Solution ${solution.correct.name} provided multiple files marked as starter code"
                        }
                        it.firstOrNull()
                    }?.also {
                        require(it.path !in usedFiles) { "File $it.path was already used as ${usedFiles[it.path]}" }
                        usedFiles[it.path] = "Starter"
                    }

            val importNames = if (solution.imports.isNotEmpty()) {
                solution.imports.map { toImport ->
                    if (toImport.endsWith(".*")) {
                        val packagePrefix = toImport.removeSuffix("*")
                        byFullName.keys.filter { it.startsWith(packagePrefix) }.also {
                            check(it.isNotEmpty()) { "@Import paths $toImport not found" }
                        }
                    } else {
                        toImport.also {
                            check(toImport in byFullName) { "@Import path $toImport not found" }
                        }.let {
                            listOf(it)
                        }
                    }
                }.flatten()
            } else {
                listOf()
            }

            var javaTemplate = File("${solution.path}.hbs").let {
                if (it.exists()) {
                    it
                } else {
                    null
                }
            }?.also {
                require(it.path !in usedFiles) { "File $it.path was already used as ${usedFiles[it.path]}" }
                usedFiles[it.path] = "Template"
            }?.readText()?.stripPackage()

            var kotlinTemplate = File("${solution.path.replace(".java$".toRegex(), ".kt")}.hbs").let {
                if (it.path != "${solution.path}.hbs" && it.exists()) {
                    it
                } else {
                    null
                }
            }?.also {
                require(it.path !in usedFiles) { "File $it.path was already used as ${usedFiles[it.path]}" }
                usedFiles[it.path] = "Template"
            }?.readText()?.stripPackage()

            val javaCleanSpec = CleanSpec(javaTemplate != null, solution.wrapWith, importNames)
            val kotlinCleanSpec = CleanSpec(kotlinTemplate != null, solution.wrapWith, importNames)

            val incorrectExamples =
                otherFiles.filter { it.packageName.startsWith("${solution.packageName}.") }
                    .filter { it.incorrect != null }
                    .onEach {
                        if (it.path in usedFiles) {
                            require(usedFiles[it.path] == "Starter") {
                                "File $it.path was already used as ${usedFiles[it.path]}"
                            }
                        }
                        usedFiles[it.path] = "Incorrect"
                    }
                    .also {
                        require(it.isNotEmpty()) {
                            "Solution ${solution.correct.name} (${solution.path}) did not provide any counterexamples " +
                                    "annotated with @Incorrect"
                        }
                    }.map { it.toIncorrectFile(javaCleanSpec) }.toMutableList().apply {
                        addAll(
                            kotlinFiles.filter { it.incorrect != null }
                                .onEach {
                                    require(it.path !in usedFiles) {
                                        "File $it.path was already used as ${usedFiles[it.path]}"
                                    }
                                    usedFiles[it.path] = "Incorrect"
                                }
                                .map { it.toIncorrectFile(kotlinCleanSpec) }
                        )
                    }

            val alternateSolutions =
                otherFiles.filter { it.packageName.startsWith("${solution.packageName}.") }
                    .filter { it.alternateSolution != null }
                    .onEach {
                        require(it.path !in usedFiles) {
                            "File $it.path was already used as ${usedFiles[it.path]}"
                        }
                        usedFiles[it.path] = "Alternate"
                    }.map {
                        it.toAlternateFile(javaCleanSpec)
                    }.toMutableList().apply {
                        addAll(
                            kotlinFiles
                                .filter { it.alternateSolution != null }
                                .onEach {
                                    require(it.path !in usedFiles) {
                                        "File $it.path was already used as ${usedFiles[it.path]}"
                                    }
                                    usedFiles[it.path] = "Correct"
                                }
                                .map { it.toAlternateFile(kotlinCleanSpec) }
                        )
                    }.toList()

            val common = importNames.map {
                usedFiles[byFullName[it]!!.path] = "Common"
                byFullName[it]?.contents?.stripPackage() ?: error("Couldn't find import $it")
            }

            val javaStarterFile = javaStarter?.toStarterFile(javaCleanSpec)

            val kotlinStarterFile = kotlinFiles.filter { it.starter != null }.also {
                require(it.size <= 1) { "Provided multiple file with Kotlin starter code" }
            }.firstOrNull()?.let {
                require(it.path !in usedFiles || usedFiles[it.path] == "Incorrect") {
                    "File $it.path was already used as ${usedFiles[it.path]}"
                }
                usedFiles[it.path] = "Starter"
                it.toStarterFile(kotlinCleanSpec)
            }

            val kotlinSolution = kotlinFiles.find { it.alternateSolution != null && it.description != null }

            if (solution.wrapWith != null) {
                require(javaTemplate == null && kotlinTemplate == null) {
                    "Can't use both a template and @WrapWith"
                }

                solution.clean(javaCleanSpec)

                javaTemplate = """public class ${solution.wrapWith} {
                |  {{{ contents }}}
                |}
            """.trimMargin()
                if (solution.usedImports.isNotEmpty()) {
                    javaTemplate = solution.usedImports.joinToString("\n") { "import $it;" } + "\n\n$javaTemplate"
                }

                kotlinTemplate = """class ${solution.wrapWith} {
                |  {{{ contents }}}
                |}
            """.trimMargin()

                if (kotlinSolution?.usedImports?.isNotEmpty() == true) {
                    kotlinTemplate =
                        kotlinSolution.usedImports.joinToString("\n") { "import $it" } + "\n\n$kotlinTemplate"
                }
            }

            Question(
                solution.correct.name,
                solution.className,
                Question.Metadata(
                    allContentHash,
                    solution.packageName,
                    solution.correct.version,
                    solution.correct.author,
                    solution.correct.description,
                    solution.correct.points,
                    solution.correct.timeoutMultiplier,
                    solution.correct.minTimeout,
                    solution.correct.mutate,
                    solution.correct.checkstyle,
                    solution.correct.solutionThrows,
                    solution.correct.maxTestCount,
                    kotlinSolution?.description,
                    solution.citation
                ),
                Question.FlatFile(
                    solution.className,
                    solution.removeImports(importNames).stripPackage(),
                    Question.Language.java
                ),
                solution.toCleanSolution(javaCleanSpec),
                alternateSolutions,
                incorrectExamples,
                common,
                javaStarterFile,
                kotlinStarterFile,
                javaTemplate,
                kotlinTemplate,
                solution.whitelist.toSet(),
                solution.blacklist.toSet()
            )
        } catch (e: Exception) {
            throw Exception("Problem parsing ${solution.path}: $e")
        }
    }
    allPaths.filter { !usedFiles.containsKey(it) }.forEach {
        println("WARNING: $it will not be included in the build")
    }
    return questions
}

val annotationsToRemove =
    setOf(
        "Correct",
        "Import",
        "Incorrect",
        "Starter",
        "SuppressWarnings",
        "Suppress",
        "Override",
        "Whitelist",
        "Blacklist",
        "DesignOnly",
        "WrapWith",
        "AlsoCorrect"
    )
val annotationsToDestroy =
    setOf(
        "FixedParameters",
        "RandomParameters",
        "Verify",
        "Both",
        "FilterParameters",
        "SimpleType",
        "EdgeType",
        "RandomType",
        "InstanceValidator",
        "CheckSource",
        "Ignore"
    )

val importsToRemove = annotationsToRemove.map { "edu.illinois.cs.cs125.questioner.lib.$it" }.toSet() +
        "edu.illinois.cs.cs125.questioner.lib.Ignore"
val packagesToRemove = setOf("edu.illinois.cs.cs125.jenisol")

private val emailRegex = Pattern.compile(
    "[a-zA-Z0-9\\+\\.\\_\\%\\-\\+]{1,256}" +
            "\\@" +
            "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,64}" +
            "(" +
            "\\." +
            "[a-zA-Z0-9][a-zA-Z0-9\\-]{0,25}" +
            ")+"
)

fun String.isEmail(): Boolean = emailRegex.matcher(this).matches()

data class CleanSpec(
    val hasTemplate: Boolean = false,
    val wrappedClass: String? = null,
    val importNames: List<String> = listOf()
)

internal fun String.stripPackage(): String {
    val packageLine = lines().indexOfFirst { it.trim().startsWith("package ") }
    if (packageLine == -1) {
        return this
    }
    val range = if (packageLine != 0 && lines()[packageLine - 1].isBlank()) {
        (packageLine - 1)..packageLine
    } else {
        packageLine..packageLine
    }
    return lines().filterIndexed { index, _ -> !range.contains(index) }.joinToString("\n").trimStart()
}

val markdownParser = MarkdownParser(CommonMarkFlavourDescriptor())
