@file:Suppress("unused")

package edu.illinois.cs.cs125.questioner.lib.verifiers

import edu.illinois.cs.cs125.jenisol.core.One
import edu.illinois.cs.cs125.jenisol.core.TestResult
import edu.illinois.cs.cs125.jenisol.core.TestResult.Differs

fun verify(results: TestResult<Int?, One<Any?>>) {
    val solutionThrew = results.solution.threw
    val submissionThrew = results.submission.threw
    if (solutionThrew != null) {
        if (submissionThrew == null) {
            results.differs.add(Differs.THREW)
        } else if (solutionThrew is AssertionError) {
            if (!(submissionThrew is AssertionError
                        || submissionThrew is ClassCastException
                        || submissionThrew is NullPointerException)
            ) {
                results.differs.add(Differs.THREW)
            }
        } else if (solutionThrew.javaClass != submissionThrew.javaClass) {
            results.differs.add(Differs.THREW)
        }
        return
    }
    if (submissionThrew != null) {
        results.differs.add(Differs.THREW)
        return
    }
    val solutionReturn = results.solution.returned!!
    val submissionReturn = results.submission.returned!!
    if (solutionReturn > 0) {
        if (submissionReturn <= 0) {
            results.differs.add(Differs.RETURN)
            results.message = "Submission did not return a positive value"
        }
    } else if (solutionReturn < 0) {
        if (submissionReturn >= 0) {
            results.differs.add(Differs.RETURN)
            results.message = "Submission did not return a negative value"
        }
    } else if (submissionReturn != 0) {
        results.differs.add(Differs.RETURN)
        results.message = "Submission did not return zero"
    }
}
