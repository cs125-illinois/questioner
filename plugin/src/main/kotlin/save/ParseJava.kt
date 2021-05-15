package edu.illinois.cs.cs125.questioner.plugin.save

import com.google.googlejavaformat.java.Formatter
import edu.illinois.cs.cs125.jeed.core.CheckstyleArguments
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.checkstyle
import edu.illinois.cs.cs125.jeed.core.complexity
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.questioner.antlr.JavaLexer
import edu.illinois.cs.cs125.questioner.antlr.JavaParser
import edu.illinois.cs.cs125.questioner.lib.AlsoCorrect
import edu.illinois.cs.cs125.questioner.lib.Blacklist
import edu.illinois.cs.cs125.questioner.lib.Cite
import edu.illinois.cs.cs125.questioner.lib.Correct
import edu.illinois.cs.cs125.questioner.lib.CorrectData
import edu.illinois.cs.cs125.questioner.lib.Incorrect
import edu.illinois.cs.cs125.questioner.lib.Question
import edu.illinois.cs.cs125.questioner.lib.Starter
import edu.illinois.cs.cs125.questioner.lib.Whitelist
import edu.illinois.cs.cs125.questioner.lib.Wrap
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.apache.tools.ant.filters.StringInputStream
import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import java.io.File

data class ParsedJavaFile(val path: String, val contents: String) {
    constructor(file: File) : this(file.path, file.readText())

    init {
        require(path.endsWith(".java")) { "Can only parse Java files" }
    }

    private val parsedSource = contents.parseJava()
    private val parseTree = parsedSource.tree
    private val topLevelClass = parseTree.topLevelClass()

    val packageName = parseTree.packageName()
    val className = parseTree.className()

    val fullName = "$packageName.$className"

    val listedImports = parseTree.importDeclaration().map { it.qualifiedName().asString() }.filter {
        it !in importsToRemove
    }

    val whitelist = topLevelClass.getAnnotations(Whitelist::class.java).let { annotations ->
        check(annotations.size <= 1) { "Found multiple @Whitelist annotations" }
        if (annotations.isEmpty()) {
            listOf()
        } else {
            annotations.first().let { annotation ->
                @Suppress("TooGenericExceptionCaught")
                try {
                    annotation.parameterMap().let { it["paths"] ?: error("path field not set on @Whitelist") }
                } catch (e: Exception) {
                    error("Couldn't parse @Whitelist paths for $path: $e")
                }.let { names ->
                    names.split(",").map { it.trim() }
                }
            }
        }
    }

    val blacklist = topLevelClass.getAnnotations(Blacklist::class.java).let { annotations ->
        check(annotations.size <= 1) { "Found multiple @Blacklist annotations" }
        if (annotations.isEmpty()) {
            listOf()
        } else {
            annotations.first().let { annotation ->
                @Suppress("TooGenericExceptionCaught")
                try {
                    annotation.parameterMap().let { it["paths"] ?: error("path field not set on @Blacklist") }
                } catch (e: Exception) {
                    error("Couldn't parse @Blacklist paths for $path: $e")
                }.let { names ->
                    names.split(",").map { it.trim() }
                }
            }
        }
    }

    val correct = topLevelClass.getAnnotation(Correct::class.java)?.let { annotation ->
        @Suppress("TooGenericExceptionCaught")
        try {
            annotation.parameterMap().let { parameters ->
                val name = parameters["name"] ?: error("name field not set on @Correct")
                val version = parameters["version"] ?: error("version field not set on @Correct")
                val author = parameters["author"] ?: error("author field not set on @Correct")
                assert(author.isEmail()) { "author field is not an email address" }
                val checkstyle = parameters["checkstyle"]?.toBoolean() ?: Correct.DEFAULT_CHECKSTYLE
                val solutionThrows = parameters["solutionThrows"]?.toBoolean() ?: Correct.DEFAULT_THROWS
                val maxTestCount = parameters["maxTestCount"]?.toInt() ?: Correct.DEFAULT_MAX_TEST_COUNT
                val minTestCount = parameters["minTestCount"]?.toInt() ?: Correct.DEFAULT_MIN_TEST_COUNT
                val focused = parameters["focused"]?.toBoolean() ?: Correct.DEFAULT_FOCUSED
                val description = annotation.comment().let { comment ->
                    markdownParser.buildMarkdownTreeFromString(comment).let { astNode ->
                        HtmlGenerator(comment, astNode, CommonMarkFlavourDescriptor()).generateHtml()
                            .removeSurrounding("<body>", "</body>")
                    }
                }
                CorrectData(
                    name,
                    version,
                    author,
                    description,
                    checkstyle,
                    solutionThrows,
                    maxTestCount,
                    minTestCount,
                    focused
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            error("Couldn't parse @Correct metadata for $path: $e")
        }
    }

    val starter = topLevelClass.getAnnotation(Starter::class.java)

    val incorrect = topLevelClass.getAnnotation(Incorrect::class.java)?.let { annotation ->
        @Suppress("TooGenericExceptionCaught")
        try {
            annotation.parameterMap().let { parameters ->
                parameters["reason"] ?: "test"
            }
        } catch (e: Exception) {
            error("Couldn't parse @Incorrect metadata for $path: $e")
        }
    }

    val alternateSolution = topLevelClass.getAnnotation(AlsoCorrect::class.java)

    val type = mutableListOf<String>().also {
        if (correct != null) {
            it.add(Correct::class.java.simpleName)
        }
        if (starter != null) {
            it.add(Starter::class.java.simpleName)
        }
        if (incorrect != null) {
            it.add(Incorrect::class.java.simpleName)
        }
    }.also { types ->
        require(types.size < 2 || (types.size == 2 && types.containsAll(listOf("Starter", "Incorrect")))) {
            "File $path incorrectly contains multiple annotations: ${types.joinToString(separator = ", ") { "@$it" }}"
        }
    }.firstOrNull()

    val wrapWith = topLevelClass.getAnnotation(Wrap::class.java)?.let { className }
    val autoStarter = topLevelClass.getAnnotation(Wrap::class.java)?.let { annotation ->
        @Suppress("TooGenericExceptionCaught")
        try {
            annotation.parameterMap().let { parameters ->
                parameters["autoStarter"].toBoolean()
            }
        } catch (e: Exception) {
            error("Couldn't parse @Wrap metadata for $path: $e")
        }
    } ?: false

    val citation = topLevelClass.getAnnotation(Cite::class.java)?.let { annotation ->
        @Suppress("TooGenericExceptionCaught")
        try {
            annotation.parameterMap().let { parameters ->
                Question.Citation(parameters["source"]!!, parameters["link"])
            }
        } catch (e: Exception) {
            error("Couldn't parse @Wrap metadata for $path: $e")
        }
    }

    fun toCleanSolution(cleanSpec: CleanSpec): Question.FlatFile {
        val solutionContent = clean(cleanSpec).let { content ->
            Source.fromJava(content).checkstyle(CheckstyleArguments(failOnError = false)).let { results ->
                val removeLines = results.errors.filter { error ->
                    error.message.trim().startsWith("Unused import")
                }.map { it.location.line }.toSet()
                content.lines().filterIndexed { index, _ -> !removeLines.contains(index + 1) }.joinToString("\n")
            }
        }
        val complexity = if (cleanSpec.notClass) {
            Source.fromSnippet(solutionContent)
        } else {
            Source(mapOf("$className.java" to solutionContent))
        }.complexity().let {
            if (cleanSpec.notClass) {
                it.lookup("")
            } else {
                it.lookup(className, "$className.java")
            }
        }.complexity
        return Question.FlatFile(className, solutionContent, Question.Language.java, path, complexity)
    }

    fun extractTemplate(): String? {
        val correctSolution = toCleanSolution(CleanSpec(false, null)).contents
        val templateStart = Regex("""//.*TEMPLATE_START""").find(correctSolution)?.range?.start ?: return null
        val templateEnd = correctSolution.indexOf("TEMPLATE_END")
        val start = correctSolution.substring(0 until templateStart)
        val end = correctSolution.substring((templateEnd + "TEMPLATE_END".length) until correctSolution.length)
        return "$start{{{ contents }}}$end"
    }

    fun extractStarter(): Question.FlatFile? {
        val correctSolution = toCleanSolution(CleanSpec(false, null)).contents
        val parsed = correctSolution.parseJava()
        val methodDeclaration = parsed.tree
            .typeDeclaration(0)
            ?.classDeclaration()
            ?.classBody()
            ?.classBodyDeclaration(0)
            ?.memberDeclaration()
            ?.methodDeclaration() ?: return null
        val start = methodDeclaration.methodBody().start.startIndex
        val end = methodDeclaration.methodBody().stop.stopIndex
        val returnType = methodDeclaration.typeTypeOrVoid().text
        val starterReturn = when {
            returnType == "void" -> ""
            returnType == "String" -> " \"\""
            returnType.capitalize() == returnType || returnType.endsWith("[]") -> " null"
            returnType == "byte" -> " 0"
            returnType == "short" -> " 0"
            returnType == "int" -> " 0"
            returnType == "long" -> " 0"
            returnType == "float" -> " 0.0"
            returnType == "double" -> " 0.0"
            returnType == "char" -> " ' '"
            returnType == "boolean" -> " false"
            else -> error("Invalid return type $returnType")
        }
        val prefix = (start + 1 until correctSolution.length).find { i -> !correctSolution[i].isWhitespace() }.let {
            check(it != null) { "Couldn't find method contents" }
            it - 1
        }
        val postfix = (end - 1 downTo start).find { i -> !correctSolution[i].isWhitespace() }.let {
            check(it != null) { "Couldn't find method contents" }
            it + 1
        }
        return (correctSolution.substring(0..prefix)
            + "return$starterReturn;"
            + correctSolution.substring(postfix until correctSolution.length))
            .javaDeTemplate(false, wrapWith).let {
                Question.FlatFile(className, it, Question.Language.java, null)
            }
    }

    fun toIncorrectFile(cleanSpec: CleanSpec): Question.IncorrectFile {
        check(incorrect != null) { "Not an incorrect file" }
        val reason = when (incorrect.toUpperCase()) {
            "DESIGN" -> Question.IncorrectFile.Reason.DESIGN
            "TEST" -> Question.IncorrectFile.Reason.TEST
            "COMPILE" -> Question.IncorrectFile.Reason.COMPILE
            "CHECKSTYLE" -> Question.IncorrectFile.Reason.CHECKSTYLE
            "TIMEOUT" -> Question.IncorrectFile.Reason.TIMEOUT
            else -> error("Invalid incorrect reason: $incorrect: $path")
        }
        return Question.IncorrectFile(
            className,
            clean(cleanSpec),
            reason,
            Question.Language.java,
            path,
            starter != null
        )
    }

    fun toAlternateFile(cleanSpec: CleanSpec): Question.FlatFile {
        check(alternateSolution != null) { "Not an alternate solution file" }
        return Question.FlatFile(className, clean(cleanSpec), Question.Language.java, path)
    }

    fun toStarterFile(cleanSpec: CleanSpec): Question.FlatFile {
        check(starter != null) { "Not an starter code file" }
        return Question.FlatFile(className, clean(cleanSpec), Question.Language.java, path)
    }

    private val chars = contents.toCharArray()
    private fun Int.toLine(): Int {
        var lines = 1
        for (i in 0 until this) {
            if (chars[i] == '\n') {
                lines++
            }
        }
        return lines
    }

    fun removeImports(importNames: List<String>): String {
        val toRemove = mutableSetOf<Int>()
        parseTree.importDeclaration().forEach { packageContext ->
            val packageName = packageContext.qualifiedName().asString()
            if (packageName in importNames) {
                toRemove.add(packageContext.start.startIndex.toLine())
            }
        }
        return contents
            .split("\n")
            .filterIndexed { index, _ -> (index + 1) !in toRemove }
            .joinToString("\n")
            .trim()
    }

    @Suppress("ComplexMethod")
    fun clean(cleanSpec: CleanSpec): String {
        val (template, wrapWith, importNames) = cleanSpec

        val toRemove = mutableSetOf<Int>()
        parseTree.packageDeclaration()?.also {
            toRemove.add(it.start.startIndex.toLine())
        }
        parseTree.importDeclaration().forEach { packageContext ->
            val packageName = packageContext.qualifiedName().asString()
            if (packageName in importsToRemove ||
                packageName in importNames ||
                packagesToRemove.any { packageName.startsWith(it) }
            ) {
                toRemove.add(packageContext.start.startIndex.toLine())
            }
        }
        topLevelClass.COMMENT()?.symbol?.also { token ->
            (token.startIndex.toLine()..token.stopIndex.toLine()).forEach { toRemove.add(it) }
        }
        topLevelClass.classOrInterfaceModifier().forEach { modifier ->
            modifier.annotation()?.also { annotation ->
                annotation.qualifiedName()?.asString()?.also { name ->
                    if (name in annotationsToRemove) {
                        (annotation.start.startIndex.toLine()..annotation.stop.stopIndex.toLine()).forEach {
                            toRemove.add(it)
                        }
                    }
                }
            }
        }
        topLevelClass.classDeclaration().classBody().classBodyDeclaration().forEach { classBodyDeclaration ->
            val annotations = classBodyDeclaration.modifier()
                .mapNotNull { it.classOrInterfaceModifier()?.annotation()?.qualifiedName()?.asString() }
            if (annotations.toSet().intersect(annotationsToDestroy).isNotEmpty()) {
                (classBodyDeclaration.start.startIndex.toLine()..classBodyDeclaration.stop.stopIndex.toLine()).forEach {
                    toRemove.add(it)
                }
            }
            classBodyDeclaration.modifier().mapNotNull { it.classOrInterfaceModifier()?.annotation() }
                .forEach { annotation ->
                    if (annotation.qualifiedName()?.asString()!! in annotationsToRemove) {
                        (annotation.start.startIndex.toLine()..annotation.stop.stopIndex.toLine()).forEach {
                            toRemove.add(it)
                        }
                    }
                }
        }

        return contents
            .split("\n")
            .filterIndexed { index, _ -> (index + 1) !in toRemove }
            .joinToString("\n")
            .trim()
            .let { Formatter().formatSource(it) }
            .let { content ->
                Source.fromJava(content).checkstyle(CheckstyleArguments(failOnError = false)).let { results ->
                    val unusedImports = results.errors.filter { error ->
                        error.message.trim().startsWith("Unused import")
                    }
                    val removeLines = unusedImports.map { it.location.line }.toSet()
                    require(correct != null || removeLines.isEmpty()) {
                        "Found unused imports in $path: ${unusedImports.joinToString(",") { it.message }}"
                    }
                    content.lines().filterIndexed { index, _ -> !removeLines.contains(index + 1) }
                        .joinToString("\n")
                }
            }.javaDeTemplate(template, wrapWith)
            .stripPackage()
    }

    var usedImports: List<String> = listOf()

    private fun String.javaDeTemplate(hasTemplate: Boolean, wrappedClass: String?): String {
        return when {
            wrappedClass != null -> {
                parseJava().tree.also { context ->
                    usedImports = context.importDeclaration().map { it.qualifiedName().asString() }
                }.topLevelClass().let { context ->
                    val start = context.classDeclaration().start.line
                    val end = context.classDeclaration().stop.line
                    split("\n").subList(start, end - 1).also { lines ->
                        require(
                            lines.find {
                                it.contains("TEMPLATE_START") || it.contains("TEMPLATE_END")
                            } == null
                        ) {
                            "@Wrap should not use template delimiters"
                        }
                    }.joinToString("\n").trimIndent().trim()
                }
            }
            !hasTemplate -> this
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
}

data class ParsedJavaContent(
    val tree: JavaParser.CompilationUnitContext,
    val stream: CharStream
)

internal fun String.parseJava() = CharStreams.fromStream(StringInputStream(this)).let { stream ->
    JavaLexer(stream).also { it.removeErrorListeners() }.let { lexer ->
        CommonTokenStream(lexer).let { tokens ->
            JavaParser(tokens).also { parser ->
                parser.removeErrorListeners()
                parser.addErrorListener(
                    object : BaseErrorListener() {
                        override fun syntaxError(
                            recognizer: Recognizer<*, *>?,
                            offendingSymbol: Any?,
                            line: Int,
                            charPositionInLine: Int,
                            msg: String?,
                            e: RecognitionException?
                        ) {
                            // Ignore messages that are not errors...
                            if (e == null) {
                                return
                            }
                            throw e
                        }
                    }
                )
            }
        }.compilationUnit()
    }.let { tree ->
        ParsedJavaContent(tree, stream)
    }
}

fun JavaParser.CompilationUnitContext.topLevelClass(): JavaParser.TypeDeclarationContext =
    children.filterIsInstance<JavaParser.TypeDeclarationContext>().filter {
        it.classDeclaration() != null || it.interfaceDeclaration() != null
    }.let {
        assert(it.size == 1) { "Found multiple top-level classes" }
        it.first()
    }

fun JavaParser.TypeDeclarationContext.getAnnotation(annotation: Class<*>): JavaParser.AnnotationContext? =
    classOrInterfaceModifier().find {
        it.annotation()?.qualifiedName()?.asString() == annotation.simpleName
    }?.annotation()

fun JavaParser.TypeDeclarationContext.getAnnotations(annotation: Class<*>): List<JavaParser.AnnotationContext> =
    classOrInterfaceModifier().filter {
        it.annotation()?.qualifiedName()?.asString() == annotation.simpleName
    }.map { it.annotation() }

fun JavaParser.AnnotationContext.parameterMap(): Map<String, String> =
    elementValuePairs()?.elementValuePair()?.associate {
        it.IDENTIFIER().toString() to it.elementValue().expression().primary().literal().let { literal ->
            literal.STRING_LITERAL()
                ?: literal.integerLiteral()?.DECIMAL_LITERAL()
                ?: literal.BOOL_LITERAL()
                ?: literal.floatLiteral()?.FLOAT_LITERAL()
        }.toString().removeSurrounding("\"")
    } ?: mapOf()

fun JavaParser.AnnotationContext.comment(): String {
    return when {
        parent.parent is JavaParser.TypeDeclarationContext ->
            (parent.parent as JavaParser.TypeDeclarationContext).COMMENT()
        parent.parent.parent is JavaParser.ClassBodyDeclarationContext ->
            (parent.parent.parent as JavaParser.ClassBodyDeclarationContext).COMMENT()
        else -> error("Error retrieving comment")
    }?.toString()?.split("\n")?.joinToString(separator = "\n") { line ->
        line.trim()
            .removePrefix("""/*""")
            .removePrefix("""*/""")
            .removePrefix("""* """)
            .removeSuffix("""*//*""")
            .let {
                if (it == "*") {
                    ""
                } else {
                    it
                }
            }
    }?.trim() ?: error("Error retrieving comment")
}

fun JavaParser.QualifiedNameContext.asString() = IDENTIFIER().joinToString(".")

fun JavaParser.CompilationUnitContext.packageName() = packageDeclaration()?.qualifiedName()?.asString() ?: ""
fun JavaParser.CompilationUnitContext.className() = topLevelClass().let {
    if (it.classDeclaration() != null) {
        it.classDeclaration().IDENTIFIER()
    } else {
        it.interfaceDeclaration().IDENTIFIER()
    }
}.toString()