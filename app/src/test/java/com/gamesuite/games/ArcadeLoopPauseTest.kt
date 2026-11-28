package com.gamesuite.games

import com.gamesuite.core.PausableGame
import com.gamesuite.games.fishing.FishingGame
import com.gamesuite.games.slotcars.SlotCarsGame
import com.gamesuite.games.toysoccer.ToySoccerGame
import com.gamesuite.games.toytennis.ToyTennisGame
import com.gamesuite.core.GameModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Toy Soccer, Toy Tennis, Slot Cars and Fishing run on the shared frame clock; the overlay-pause
 * host asks the module whether it is already paused (PausableGame) before pausing and resuming it.
 */
class ArcadeLoopPauseTest {
    private fun check(name: String, g: GameModule) {
        assertTrue("$name is PausableGame", g is PausableGame)
        val p = g as PausableGame
        assertFalse("$name starts unpaused", p.isPaused)
        g.pause()
        assertTrue("$name pause()", p.isPaused)
        g.pause()
        assertTrue("$name pause() is idempotent", p.isPaused)
        g.resume()
        assertFalse("$name resume()", p.isPaused)
    }

    @Test fun toySoccer() = check("toy-soccer", ToySoccerGame())
    @Test fun toyTennis() = check("toy-tennis", ToyTennisGame())
    @Test fun slotCars() = check("slot-cars", SlotCarsGame())
    @Test fun fishing() = check("fishing", FishingGame())

    @Test fun advanceBeforeAMatchIsAHarmlessNoOp() {
        // The frame loop calls advance() from the first frame; with no match started it must not throw.
        assertEquals(false, ToySoccerGame().advance())
        assertEquals(false, ToyTennisGame().advance())
        assertEquals(false, SlotCarsGame().advance())
        assertEquals(false, FishingGame().advance())
    }
}
