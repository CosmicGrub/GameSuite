package com.gamesuite.uikit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPauseControllerTest {
    private class Rig(var gamePaused: Boolean = false) {
        var pauses = 0
        var resumes = 0
        val c = OverlayPauseController(
            pause = { pauses++; gamePaused = true },
            resume = { resumes++; gamePaused = false },
            alreadyPaused = { gamePaused }
        )
    }

    @Test fun firstOpenPausesAndLastCloseResumes() {
        val r = Rig()
        r.c.setOpen("menu", true)
        assertEquals(1, r.pauses)
        r.c.setOpen("help", true)
        assertEquals("a second overlay must not pause again", 1, r.pauses)
        r.c.setOpen("menu", false)
        assertEquals("help is still open", 0, r.resumes)
        assertTrue(r.gamePaused)
        r.c.setOpen("help", false)
        assertEquals(1, r.resumes)
        assertFalse(r.gamePaused)
    }

    @Test fun openingTheSameKeyTwiceAndClosingAStrangerAreNoOps() {
        val r = Rig()
        r.c.setOpen("menu", true)
        r.c.setOpen("menu", true)
        r.c.setOpen("ghost", false)
        assertEquals(1, r.pauses)
        assertEquals(1, r.c.openCount)
        r.c.setOpen("menu", false)
        r.c.setOpen("menu", false)
        assertEquals(1, r.resumes)
    }

    @Test fun aGameAlreadyPausedIsNeitherPausedNorResumed() {
        val r = Rig(gamePaused = true)
        r.c.setOpen("menu", true)
        r.c.setOpen("menu", false)
        assertEquals(0, r.pauses)
        assertEquals(0, r.resumes)
        assertTrue("the player's own pause survives", r.gamePaused)
        assertFalse(r.c.holdsPause)
    }

    @Test fun abandonDropsEverythingWithoutResuming() {
        val r = Rig()
        r.c.setOpen("leave", true)
        r.c.abandon()
        assertFalse(r.c.isAnyOpen)
        r.c.setOpen("leave", false)
        assertEquals(0, r.resumes)
    }

    @Test fun reopenAfterFullCycleWorksAgain() {
        val r = Rig()
        repeat(3) {
            r.c.setOpen("menu", true)
            r.c.setOpen("menu", false)
        }
        assertEquals(3, r.pauses)
        assertEquals(3, r.resumes)
    }

    @Test fun overlayThatOpensAfterOtherClosedStillPausesOnce() {
        val r = Rig()
        r.c.setOpen("menu", true)
        r.c.setOpen("help", true)   // menu -> help hand-off
        r.c.setOpen("menu", false)
        r.c.setOpen("help", false)
        assertEquals(1, r.pauses)
        assertEquals(1, r.resumes)
    }
}
