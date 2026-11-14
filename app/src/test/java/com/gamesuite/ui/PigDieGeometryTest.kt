package com.gamesuite.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drawn die must be a real one: opposite faces add to seven and 1-2-3 run counter-clockwise round their corner. */
class PigDieGeometryTest {
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    @Test fun oppositeFacesAddToSeven() {
        for (v in 1..6) {
            val n = dieFaceNormal(v)
            val opposite = dieFaceNormal(7 - v)
            assertEquals("faces $v and ${7 - v} must be opposite", -1f, dot(n, opposite), 1e-6f)
        }
    }

    @Test fun theDieIsRightHandedLikeAStandardOne() {
        // The model's y axis points DOWN the screen, so its frame is left-handed: a physically right-handed die
        // (1, 2, 3 counter-clockwise round their shared corner, seen from outside) has a NEGATIVE triple product here.
        val triple = dot(cross(dieFaceNormal(1), dieFaceNormal(2)), dieFaceNormal(3))
        assertTrue("1-2-3 must run counter-clockwise (right-handed die), triple=$triple", triple < 0f)
    }
}
