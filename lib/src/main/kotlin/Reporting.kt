@file:Suppress("SpellCheckingInspection")

package edu.illinois.cs.cs125.questioner.lib

import edu.illinois.cs.cs125.jeed.core.suppressionComment
import edu.illinois.cs.cs125.jenisol.core.TestResult
import org.apache.commons.text.StringEscapeUtils

private fun wrapDocument(question: Question, body: String) = """
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.0.1/dist/css/bootstrap.min.css" rel="stylesheet" integrity="sha384-+0n0xVW2eSR5OomGNYDnhzAbDsOXxcvSN1TPprVMTNDbiYZCxYbOOl7+AMvyTG2x" crossorigin="anonymous">
    <link rel="stylesheet" href="//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.2/styles/default.min.css">
    <title>${question.name}</title>
    <style>
html {
  font-size: 13px;
}
</style>
  </head>
  <body>
    <div class="container">
    ${
    if (!question.validated) {
        """
            |<div class="alert alert-danger mt-4 pb-0">
            |<h2>Validation Failed</h2>
            |<p>Please see below for more details.</p>
            |</div>
            |$body
        """.trimMargin()
    } else {
        ""
    }
}
    <h1>${question.name}</h1>
    <span class="badge rounded-pill bg-primary">${question.metadata.author}</span>
    <span class="badge rounded-pill bg-secondary">${question.metadata.version}</span>
    ${
    if (question.hasKotlin) {
        """<span class="badge rounded-pill bg-success">Kotlin</span>"""
    } else {
        ""
    }
}
    <h2>Descriptions and Solutions</h2>
    <h3>Java</h3>
    <h4>Description</h4>
    <figure>
    <blockquote class="blockquote">
    ${question.metadata.javaDescription}
    </blockquote>
    <figcaption class="blockquote-footer">Java description</figcaption>
    </figure>
    <h4>Solution</h4>
    <pre><code class="java">${StringEscapeUtils.escapeHtml4(question.correct.contents)}</code></pre>
    ${
    question.detemplatedJavaStarter?.let {
        """<h4>Starter Code</h4><pre><code class="java">${
            StringEscapeUtils.escapeHtml4(
                question.detemplatedJavaStarter
            )
        }</code></pre>"""
    } ?: ""
}
    ${
    if (question.hasKotlin) {
        """<h3>Kotlin</h3>
        <h4>Description</h4>
        <figure>
        <blockquote class="blockquote">
        ${question.metadata.kotlinDescription}
        </blockquote>
        <figcaption class="blockquote-footer">Kotlin description</figcaption>
        </figure>
        <h4>Solution</h4>
        <pre><code class="kotlin">${StringEscapeUtils.escapeHtml4(question.alternativeSolutions.find { it.language == Question.Language.kotlin }!!.contents)}</code></pre>""" +
            (question.detemplatedKotlinStarter?.let {
                """<h4>Starter Code</h4><pre><code class="java">${
                    StringEscapeUtils.escapeHtml4(
                        question.detemplatedKotlinStarter
                    )
                }</code></pre>"""
            } ?: "")
    } else {
        ""
    }
}
    ${
    if (question.validated) {
        body
    } else {
        ""
    }
}
    </div>
    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.0.1/dist/js/bootstrap.bundle.min.js" integrity="sha384-gtEjrD/SeCtmISkJkNUaaKMoLD0//ElJ19smozuHV6z3Iehds+3Ulb9Bn9Plx0x4" crossorigin="anonymous"></script>
    <script src="//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.2/highlight.min.js"></script>
    <script>hljs.highlightAll();</script>
  </body>
</html>
"""

fun IncorrectResults.html(index: Int, question: Question): String {
    val contents = if (incorrect.mutation != null) {
        incorrect.mutation.marked().contents.deTemplate(question.getTemplate(Question.Language.java))
    } else {
        incorrect.contents
    }
    return """
    <h3>Incorrect $index</h3>
    <p>${
        if (incorrect.mutation != null) {
            "Mutation (${incorrect.mutation.mutations.first().mutation.type.name})"
        } else if (incorrect.starter) {
            if (incorrect.path == null) {
                "Starter (autogenerated)"
            } else {
                "@Starter annotated"
            }
        } else {
            "@Incorrect annotated"
        }
    }</p>${
        if (results.tests() != null) {
            val actualCount = if (question.fauxStatic) {
                results.tests()!!.filter { it.jenisol!!.type != TestResult.Type.CONSTRUCTOR }.size
            } else {
                results.tests()!!.size
            }
            val alert = if (results.tests()!!.size > question.control.minTestCount) {
                """<div class="alert alert-warning" role="alert">Slowly found a failing test. Consider adding this input to @FixedParameters.</div>"""
            } else {
                """<div class="alert alert-success" role="alert">Quickly found a failing test.</div>"""
            }
            """
            $alert
            <table class="table">
      <thead>
        <tr>
          <th scope="col">Tests</th>
          <th scope="col">Time</th>
        </tr>
      </thead>
      <tbody>
        <tr>
          <td>$actualCount</td>
          <td>${results.taskResults!!.interval.length}</td>
        </tr>
      </tbody>
    </table>
    <pre>${results.tests()!!.find { !it.passed }!!.explanation}</pre>"""
        } else {
            """<div class="alert alert-warning" role="alert">${results.summary}</div>"""
        }
    }
        <pre><code class="${
        if (incorrect.language == Question.Language.java) {
            "java"
        } else {
            "kotlin"
        }
    }"> ${StringEscapeUtils.escapeHtml4(contents)}</code></pre>
        """
}

fun ValidationReport.report(): String {
    val incorrectBody = incorrect
        .sortedBy { it.results.tests()?.size }
        .reversed()
        .mapIndexed { i, it -> it.html(i, question) }
        .joinToString("\n")
    val body = """
        |<h2>Incorrect Examples</h2>
        |<p>Used ${incorrect.size} incorrect examples to generate test cases.</p>
        |$incorrectBody
        |""".trimMargin()
    return wrapDocument(question, body)
}

fun ValidationFailed.report(question: Question): String {
    val body = when (this) {
        is SolutionFailed -> {
            """
    |<h2>Solution Failed Testing</h2>
    |<p>The following solution failed testing:</p>
    |<pre><code class="${
                if (solution.language == Question.Language.java) {
                    "java"
                } else {
                    "kotlin"
                }
            }"> ${StringEscapeUtils.escapeHtml4(solution.contents)}</code></pre>
    |<pre>${explanation}</pre>
    |<p><strong>Please verify that this solution matches the reference solution.</strong></p>
""".trimMargin()
        }
        is SolutionThrew -> {
            """
    |<h2>Solution Not Expected to Throw</h2>
    |<p>The olution was not expected to throw, but threw <code>$threw</code> on parameters <code>$parameters</code>.</p>
    |<pre><code class="${
                if (solution.language == Question.Language.java) {
                    "java"
                } else {
                    "kotlin"
                }
            }"> ${StringEscapeUtils.escapeHtml4(solution.contents)}</code></pre>
    |<ul>
    |<li>If it should throw, allow it using <code>@Correct(solutionThrows = true)</code></li>
    |<li>Otherwise filter the inputs using <code>@FixedParameters</code>, <code>@RandomParameters</code>, or <code>@FilterParameters</code>
    |</ul>
""".trimMargin()
        }
        is SolutionLacksEntropy -> {
            """
    |<h2>Solution Results Lack Entropy</h2>
    |<p>Random inputs to the solution only generated $amount distinct return values.</p>
    |<pre><code class="${
                if (solution.language == Question.Language.java) {
                    "java"
                } else {
                    "kotlin"
                }
            }"> ${StringEscapeUtils.escapeHtml4(solution.contents)}</code></pre>
    |<p>You may need to add or adjust your @RandomParameters method.</p>
""".trimMargin()
        }
        is NoIncorrect -> {
            """
                |<h2>No Incorrect Examples Found</h2>
                |<p>No incorrect examples found or generated through mutation.
                |<strong>Please add some using @Incorrect or by enabling suppressed mutations.</strong>
                |</p>
            """.trimMargin()
        }
        is TooFewMutations -> {
            """
                |<h2>Too Few Mutations Found</h2>
                |<p>Generated $found mutations but needed $needed.
                |<strong>Please reduce the required number or remove mutation suppressions.</strong>
                |</p>
            """.trimMargin()
        }
        is TooMuchOutput -> {
            """
                |<h2>Too Much Output</h2>
                |<p>The following submission generated too much output ($size > $maxSize)</p>
                |<pre><code class="${
                if (language == Question.Language.java) {
                    "java"
                } else {
                    "kotlin"
                }
            }"> ${StringEscapeUtils.escapeHtml4(contents)}</code></pre>
                |<p>Consider reducing the number of tests using <code>@Correct(minTestCount = NUM)</code>.</p>
            """.trimMargin()
        }
        is IncorrectPassed -> {
            val contents = incorrect.mutation?.marked()?.contents?.deTemplate(question.getTemplate(incorrect.language))
                ?: incorrect.contents
            """
                |<h2>Incorrect Code Passed the Test Suite</h2>
                |<p>The following incorrect code passed the test suites:</p>
                |<pre><code class=${
                if (incorrect.language == Question.Language.java) {
                    "java"
                } else {
                    "kotlin"
                }
            }"> ${StringEscapeUtils.escapeHtml4(contents)}</code></pre>
                |<ul>
                |<li>If the code is in fact incorrect, you may need to add a failing input using <code>@FixedParameters</code>
                |${
                if (incorrect.mutation != null) {
                    "<li>If the code is a mutation that should pass, you may need to disable the mutation using <code>${incorrect.mutation.mutations.first().mutation.type.suppressionComment()}</code>"
                } else {
                    ""
                }
            }
            |<li>You may also need to increase the test count using <code>@Correct(maxTestCount = NUM)</code>, or remove an existing limitation</li>
            |</ul>
            """.trimMargin()
        }
        is IncorrectTooManyTests -> {
            val contents = incorrect.mutation?.marked()?.contents?.deTemplate(question.getTemplate(incorrect.language))
                ?: incorrect.contents
            """
                |<h2>Incorrect Code Required Too Many Tests</h2>
                |<p>Incorrect code eventually failed but required too many tests ($testsRequired > $testsLimit).
                |${failingInput?.let { "We found failing inputs $failingInput" } ?: "We were unable to find a failing input"}
                |</p>
                |<pre><code class=${
                if (incorrect.language == Question.Language.java) {
                    "java"
                } else {
                    "kotlin"
                }
            }"> ${StringEscapeUtils.escapeHtml4(contents)}</code></pre>
                |<ul>
                |<li>If the code is incorrect, add an input using <code>@FixedParameters</code> to handle this case
                |${
                if (incorrect.mutation != null) {
                    "<li>If the code is correct, you may need to disable this mutation using " +
                        "// ${incorrect.mutation.mutations.first().mutation.type.suppressionComment()}</li>"
                } else {
                    ""
                }
            }<li>You may also need to increase the test count using <code>@Correct(maxTestCount = NUM)</code></li>
                |</ul>""".trimMargin()
        }
        is IncorrectWrongReason -> {
            """
                |<h2>Incorrect Code Failed for the Wrong Reason</h2>
                |<p>Incorrect code failed but not for the reason we expected: Expected: $expected, but found: $explanation.</p>
                |</p>
                |<pre><code class=${
                if (incorrect.language == Question.Language.java) {
                    "java"
                } else {
                    "kotlin"
                }
            }"> ${StringEscapeUtils.escapeHtml4(incorrect.contents)}</code></pre>
                |<p>Check the arguments to <code>@Incorrect(reason = REASON)</code></p>""".trimMargin()
        }
        else -> error("Unexpected error: $this")
    }
    return wrapDocument(question, body)
}