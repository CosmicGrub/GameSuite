package com.gamesuite.style

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ProjectionTest {
    private fun near(a: Float, b: Float, eps: Float = 0.01f) = assertTrue("$a vs $b", abs(a - b) <= eps)

    @Test fun zeroTiltIsTheIdentity() {
        val cam = BoardCamera(400f, 400f, 0f, 400f, 400f)
        assertTrue(cam.isIdentity)
        val p = cam.project(123f, 321f)
        near(p.x, 123f); near(p.y, 321f); near(p.scale, 1f)
    }

    @Test fun projectionAndPickingRoundTrip() {
        for (tilt in listOf(0f, 14f, 24f, 36f)) {
            val cam = BoardCamera(480f, 480f, tilt, 500f, 460f)
            for (u in 0..480 step 40) for (v in 0..480 step 40) {
                val p = cam.project(u.toFloat(), v.toFloat())
                val (bu, bv) = cam.unproject(p.x, p.y)
                near(bu, u.toFloat(), 0.05f); near(bv, v.toFloat(), 0.05f)
            }
        }
    }

    @Test fun cellCentresPickTheirOwnCell() {
        val s = 60f
        val cam = BoardCamera(8 * s, 8 * s, 24f, 8 * s, 8 * s)
        for (r in 0 until 8) for (c in 0 until 8) {
            val p = cam.project((c + 0.5f) * s, (r + 0.5f) * s)
            val (u, v) = cam.unproject(p.x, p.y)
            assertEquals(c, (u / s).toInt()); assertEquals(r, (v / s).toInt())
        }
    }

    @Test fun farEdgeIsSmallerAndNarrower() {
        val cam = BoardCamera(400f, 400f, 30f, 400f, 400f)
        val top = cam.project(0f, 0f); val bottom = cam.project(0f, 400f)
        assertTrue(top.scale < bottom.scale)
        val topRight = cam.project(400f, 0f); val bottomRight = cam.project(400f, 400f)
        assertTrue(topRight.x - top.x < bottomRight.x - bottom.x)
    }

    @Test fun projectedBoardFitsTheBox() {
        val cam = BoardCamera(480f, 480f, 36f, 500f, 400f)
        for ((u, v) in listOf(0f to 0f, 480f to 0f, 0f to 480f, 480f to 480f)) {
            val p = cam.project(u, v)
            assertTrue(p.x >= -0.5f && p.x <= 500.5f); assertTrue(p.y >= -0.5f && p.y <= 400.5f)
        }
    }

    @Test fun isometricRoundTripAtHeights() {
        val iso = IsoProjection(64f, 32f, 300f, 40f, 32f)
        for (z in listOf(0f, 0.5f, 2f)) for (gx in 0..7) for (gy in 0..7) {
            val p = iso.project(gx.toFloat(), gy.toFloat(), z)
            val (bx, by) = iso.unproject(p.x, p.y, z)
            near(bx, gx.toFloat(), 0.001f); near(by, gy.toFloat(), 0.001f)
        }
        assertTrue(iso.depthKey(3f, 3f) > iso.depthKey(1f, 2f))
    }
}
