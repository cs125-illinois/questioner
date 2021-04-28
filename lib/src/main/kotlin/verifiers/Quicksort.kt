@file:Suppress("unused")

package edu.illinois.cs.cs125.questioner.lib.verifiers

import com.google.common.truth.Truth
import edu.illinois.cs.cs125.jenisol.core.One
import edu.illinois.cs.cs125.jenisol.core.TestResult
import edu.illinois.cs.cs125.jenisol.core.TestResult.Differs
import java.util.*

fun verify(results: TestResult<Int, One<IntArray>>) {
    val pivotLocation = results.solution.returned
    if (pivotLocation != results.submission.returned) {
        results.differs.add(Differs.RETURN)
        return
    }
    if (pivotLocation == -1) {
        return
    }
    val solution = results.solution.parameters.first.copyOf()
    val submission = results.submission.parameters.first.copyOf()

    Truth.assertWithMessage("Partitioned arrays are not the same size")
        .that(submission.size)
        .isEqualTo(solution.size)
    Truth.assertWithMessage("Pivot location $pivotLocation is not a valid array index:")
        .that(0 <= pivotLocation!! && pivotLocation < submission.size)
        .isTrue()
    Truth.assertWithMessage("Pivot value is not in the correct position")
        .that(submission[pivotLocation])
        .isEqualTo(solution[pivotLocation])
    val pivotValue = solution[pivotLocation]
    for (i in 0 until pivotLocation) {
        Truth.assertWithMessage("Bad value to the left of the pivot")
            .that(submission[i] < pivotValue)
            .isTrue()
    }
    for (i in pivotLocation + 1 until submission.size) {
        Truth.assertWithMessage("Bad value to the right of the pivot")
            .that(submission[i] >= pivotValue)
            .isTrue()
    }
    Arrays.sort(solution)
    Arrays.sort(submission)
    Truth.assertWithMessage("Returned array does not contain original values")
        .that(submission)
        .isEqualTo(solution)
}
