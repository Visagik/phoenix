package com.phoenix.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsHeadlineTest {
    private fun c(f: Int, p: Int) = Diagnostics.Counts(f, 0, 0, 0, 0, p)

    @Test fun quietDayIsOk() {
        assertEquals(Diagnostics.Headline.OK, Diagnostics.headline(c(0, 0)))
    }

    @Test fun manyFailoversMeanUnstableEvenWithoutProblems() {
        assertEquals(Diagnostics.Headline.UNSTABLE, Diagnostics.headline(c(6, 0)))
        assertEquals(Diagnostics.Headline.UNSTABLE, Diagnostics.headline(c(12, 3)))
    }

    @Test fun fewFailoversButProblemsMeanProblems() {
        assertEquals(Diagnostics.Headline.PROBLEMS, Diagnostics.headline(c(5, 2)))
    }
}
