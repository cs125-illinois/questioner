@file:Suppress("SpellCheckingInspection")

package edu.illinois.cs.cs125.questioner.lib

import org.apache.commons.text.StringEscapeUtils

private fun wrapDocument(question: Question) = """
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@5.0.1/dist/css/bootstrap.min.css" rel="stylesheet" integrity="sha384-+0n0xVW2eSR5OomGNYDnhzAbDsOXxcvSN1TPprVMTNDbiYZCxYbOOl7+AMvyTG2x" crossorigin="anonymous">
    <link rel="stylesheet" href="//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.2/styles/default.min.css">
    <title>${question.name}</title>
  </head>
  <body>
    <div class="container">
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
    </div>
    <script src="https://cdn.jsdelivr.net/npm/bootstrap@5.0.1/dist/js/bootstrap.bundle.min.js" integrity="sha384-gtEjrD/SeCtmISkJkNUaaKMoLD0//ElJ19smozuHV6z3Iehds+3Ulb9Bn9Plx0x4" crossorigin="anonymous"></script>
    <script src="//cdnjs.cloudflare.com/ajax/libs/highlight.js/10.7.2/highlight.min.js"></script>
    <script>hljs.highlightAll();</script>
  </body>
</html>
"""

fun ValidationReport.report(): String {
    return wrapDocument(question)
}