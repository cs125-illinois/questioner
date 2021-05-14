package edu.illinois.cs.cs125.questioner.plugin.save

import edu.illinois.cs.cs125.questioner.antlr.KotlinLexer
import edu.illinois.cs.cs125.questioner.antlr.KotlinParser
import edu.illinois.cs.cs125.questioner.lib.AlsoCorrect
import edu.illinois.cs.cs125.questioner.lib.Incorrect
import edu.illinois.cs.cs125.questioner.lib.Question
import edu.illinois.cs.cs125.questioner.lib.Starter
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

data class ParsedKotlinFile(val path: String, val contents: String) {
    constructor(file: File) : this(file.path, file.readText())

    init {
        require(path.endsWith(".kt")) { "Can only parse Kotlin files" }
    }

    private val parsedSource = contents.parseKotlin()
    private val parseTree = parsedSource.tree

    private val topLevelFile =
        parseTree.preamble().fileAnnotations()
            ?.getAnnotation(AlsoCorrect::class.java, Incorrect::class.java, Starter::class.java) != null

    private val topLevelClass = if (!topLevelFile) {
        parseTree.topLevelObject().filter { it.classDeclaration() != null }.also {
            require(it.size == 1) { "Kotlin files must only contain a single top-level class declaration: ${it.size}" }
        }.first().classDeclaration()
    } else {
        null
    }

    val alternateSolution = if (topLevelFile) {
        parseTree.preamble().fileAnnotations().getAnnotation(AlsoCorrect::class.java)
    } else {
        topLevelClass!!.getAnnotation(AlsoCorrect::class.java)
    }

    val starter = if (topLevelFile) {
        parseTree.preamble().fileAnnotations().getAnnotation(Starter::class.java)
    } else {
        topLevelClass!!.getAnnotation(Starter::class.java)
    }

    val incorrect = if (topLevelFile) {
        parseTree.preamble().fileAnnotations().getAnnotation(Incorrect::class.java)
    } else {
        topLevelClass!!.getAnnotation(Incorrect::class.java)
    }?.let { ruleContext ->
        when (ruleContext) {
            is KotlinParser.AnnotationContext -> ruleContext.valueArguments()
            is KotlinParser.UnescapedAnnotationContext -> ruleContext.valueArguments()
            else -> error("Bad annotation chain")
        }?.valueArgument()?.find {
            it.simpleIdentifier().text == "reason"
        }?.expression()?.text?.removeSurrounding("\"") ?: "test"
    } ?: if (starter != null) {
        "test"
    } else {
        null
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
            Question.Language.kotlin,
            path,
            starter != null
        )
    }

    val className: String = if (topLevelFile) {
        "${File(path).nameWithoutExtension}Kt"
    } else {
        topLevelClass!!.simpleIdentifier().text
    }

    fun toAlternateFile(cleanSpec: CleanSpec): Question.FlatFile {
        check(alternateSolution != null) { "Not an alternate solution file" }
        return Question.FlatFile(className, clean(cleanSpec), Question.Language.kotlin, path)
    }

    fun toStarterFile(cleanSpec: CleanSpec): Question.FlatFile {
        check(starter != null) { "Not an starter code file" }
        return Question.FlatFile(className, clean(cleanSpec), Question.Language.kotlin, path)
    }

    @Suppress("unused")
    private fun removeImports(importNames: List<String>): String {
        val toRemove = mutableSetOf<Int>()
        parseTree.preamble().importList().importHeader().forEach { importHeaderContext ->
            val packageName = importHeaderContext.identifier().text
            if (packageName in importNames) {
                toRemove.add(importHeaderContext.start.startIndex.toLine())
            }
        }
        return contents
            .split("\n")
            .filterIndexed { index, _ -> (index + 1) !in toRemove }
            .joinToString("\n")
            .trim()
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

    val comment = if (topLevelFile) {
        parseTree.comment()
    } else {
        topLevelClass!!.comment()
    }

    val description =
        if (comment != null) {
            markdownParser.buildMarkdownTreeFromString(comment).let { astNode ->
                HtmlGenerator(comment, astNode, CommonMarkFlavourDescriptor()).generateHtml()
                    .removeSurrounding("<body>", "</body>")
            }
        } else {
            null
        }

    @Suppress("ComplexMethod")
    fun clean(cleanSpec: CleanSpec): String {
        val (hasTemplate, wrapWith, importNames) = cleanSpec
        val toRemove = mutableSetOf<Int>()
        parseTree.preamble()?.packageHeader()?.also {
            toRemove.add(it.start.startIndex.toLine())
        }
        parseTree.preamble().importList().importHeader().forEach { importHeaderContext ->
            val packageName = importHeaderContext.identifier().text
            if (packageName in importsToRemove ||
                packageName in importNames ||
                packagesToRemove.any { packageName.startsWith(it) }
            ) {
                toRemove.add(importHeaderContext.start.startIndex.toLine())
            }
        }
        if (topLevelFile) {
            parseTree.topLevelObject().forEach { topLevelObject ->
                topLevelObject.DelimitedComment()?.also { node ->
                    (node.symbol.startIndex.toLine()..node.symbol.stopIndex.toLine()).forEach { toRemove.add(it) }
                }
            }
            parseTree.preamble().fileAnnotations().fileAnnotation()?.flatMap { it.unescapedAnnotation() }
                ?.filter { annotation ->
                    annotation.identifier()?.text != null &&
                        annotationsToRemove.contains(annotation.identifier().text.removePrefix("@"))
                }?.forEach { context ->
                    (context.start.startIndex.toLine()..context.stop.stopIndex.toLine()).forEach {
                        toRemove.add(it)
                    }
                }
            parseTree.topLevelObject().mapNotNull { it.functionDeclaration()?.modifierList()?.annotations() }
                .flatten().mapNotNull { it.annotation() }.filter { annotation ->
                    annotation.LabelReference()?.text != null &&
                        annotationsToRemove.contains(annotation.LabelReference().text.removePrefix("@"))
                }.forEach { context ->
                    (context.start.startIndex.toLine()..context.stop.stopIndex.toLine()).forEach {
                        toRemove.add(it)
                    }
                }
        } else {
            topLevelClass!!.DelimitedComment()?.also { node ->
                (node.symbol.startIndex.toLine()..node.symbol.stopIndex.toLine()).forEach { toRemove.add(it) }
            }
            topLevelClass.modifierList().annotations()
                ?.filter {
                    it.annotation().LabelReference()?.text != null && annotationsToRemove.contains(
                        it.annotation().LabelReference().text.removePrefix("@")
                    )
                }?.forEach { context ->
                    (context.start.startIndex.toLine()..context.stop.stopIndex.toLine()).forEach {
                        toRemove.add(it)
                    }
                }
        }

        return contents
            .split("\n")
            .filterIndexed { index, _ -> (index + 1) !in toRemove }
            .joinToString("\n") { line ->
                Regex("""//.*mutate-disable""").find(line)?.let {
                    line.substring(0 until it.range.first).trimEnd()
                } ?: line
            }
            .trim()
            .kotlinDeTemplate(hasTemplate, wrapWith)
            .stripPackage()
    }

    var usedImports: List<String> = listOf()

    fun extractTemplate(): String? {
        val correctSolution = clean(CleanSpec(false, null))
        val templateStart = Regex("""//.*TEMPLATE_START""").find(correctSolution)?.range?.start ?: return null
        val templateEnd = correctSolution.indexOf("TEMPLATE_END")
        val start = correctSolution.substring(0 until templateStart)
        val end = correctSolution.substring((templateEnd + "TEMPLATE_END".length) until correctSolution.length)
        return "$start{{{ contents }}}$end"
    }

    private fun String.kotlinDeTemplate(hasTemplate: Boolean, wrappedClass: String?) = when {
        wrappedClass != null && topLevelFile -> {
            usedImports = parseKotlin().tree.preamble().importList().importHeader().map { it.identifier().text }
            this
        }
        wrappedClass != null && !topLevelFile -> parseKotlin().tree.also { context ->
            usedImports = context.preamble().importList().importHeader().map { it.identifier().text }
        }.topLevelObject()
            .filter { it.classDeclaration() != null }.also {
                require(it.size == 1) { "Kotlin files must only contain a single top-level class declaration: ${it.size}" }
            }.first().classDeclaration().let { context ->
                val start = context.start.line
                val end = context.stop.line
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
        !hasTemplate -> this
        else -> {
            val lines = split("\n")
            val start = lines.indexOfFirst { it.contains("TEMPLATE_START") }
            val end = lines.indexOfFirst { it.contains("TEMPLATE_END") }
            require(start != -1) { "Couldn't locate TEMPLATE_START during extraction" }
            require(end != -1) { "Couldn't locate TEMPLATE_END during extraction" }
            lines.slice((start + 1) until end).joinToString(separator = "\n").trimIndent()
        }
    }
}

data class ParsedKotlinContent(val tree: KotlinParser.KotlinFileContext, val stream: CharStream)

internal fun String.parseKotlin() = CharStreams.fromStream(StringInputStream(this)).let { stream ->
    KotlinLexer(stream).also { it.removeErrorListeners() }.let { lexer ->
        CommonTokenStream(lexer).let { tokens ->
            KotlinParser(tokens).also { parser ->
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
        }.kotlinFile()
    }.let { tree ->
        ParsedKotlinContent(tree, stream)
    }
}

fun KotlinParser.FileAnnotationsContext.getAnnotation(vararg toFind: Class<*>): KotlinParser.UnescapedAnnotationContext? =
    fileAnnotation()?.flatMap { it.unescapedAnnotation() }?.find { annotation ->
        annotation.identifier()?.text != null && toFind.map { it.simpleName }.contains(annotation.identifier().text)
    }

fun KotlinParser.ClassDeclarationContext.getAnnotation(annotation: Class<*>): KotlinParser.AnnotationContext? =
    modifierList().annotations()?.find {
        it.annotation()?.LabelReference()?.text == "@${annotation.simpleName}"
    }?.annotation()

fun KotlinParser.KotlinFileContext.comment() =
    topLevelObject().find { it.DelimitedComment() != null }?.DelimitedComment()?.text
        ?.toString()?.split("\n")?.joinToString(separator = "\n") { line ->
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
        }?.trim()

fun KotlinParser.ClassDeclarationContext.comment() = DelimitedComment()?.text
    ?.toString()?.split("\n")?.joinToString(separator = "\n") { line ->
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
    }?.trim()
