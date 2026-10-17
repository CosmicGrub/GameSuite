package com.gamesuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubSummaryTest {
    private fun mode(gameId: String, modeId: String): HubMode =
        GameHubSpecs.get(gameId).mode(modeId) ?: error("no mode $gameId/$modeId")

    private fun summary(gameId: String, modeId: String, choices: HubChoices = HubChoices()): String =
        summarizeHubChoices(mode(gameId, modeId), choices)

    /** Picks a preset the way the Hub does: the choice id plus every key it applies. */
    private fun pickPreset(m: HubMode, base: HubChoices, presetId: String): HubChoices {
        val opt = m.options.filterIsInstance<HubOption.Choice>().first { it.key == "preset" }
        val choice = opt.choices.first { it.id == presetId }
        return HubChoices(base.asMap() + choice.applies + ("preset" to presetId))
    }

    @Test fun defaultsVsComputer() {
        assertEquals("vs Computer · You first", summary("chess", "cpu"))
    }

    @Test fun changedDifficulty() {
        val c = mode("chess", "cpu").defaults().with(HubKeys.DIFFICULTY, "HARD")
        assertEquals("vs Computer · Hard · You first", summary("chess", "cpu", c))
    }

    @Test fun opponentMovesFirst() {
        val c = mode("chess", "cpu").defaults().with(HubKeys.FIRST, HubKeys.FIRST_OPPONENT)
        assertEquals("vs Computer · The computer first", summary("chess", "cpu", c))
    }

    @Test fun passAndPlayFirstMover() {
        assertEquals("Pass & Play · Player 1 first", summary("checkers", "pass"))
        val c = mode("checkers", "pass").defaults().with(HubKeys.FIRST, HubKeys.FIRST_OPPONENT)
        assertEquals("Pass & Play · Player 2 first", summary("checkers", "pass", c))
    }

    @Test fun randomFirstMover() {
        val c = mode("chess", "cpu").defaults().with(HubKeys.FIRST, HubKeys.FIRST_RANDOM)
        assertEquals("vs Computer · Random first", summary("chess", "cpu", c))
    }

    @Test fun unoDefaultCpus() {
        assertEquals("vs Computers · 2 computers", summary("uno", "cpu"))
    }

    @Test fun unoCpusAndPartyPreset() {
        val m = mode("uno", "cpu")
        val c = pickPreset(m, m.defaults().with(HubKeys.CPUS, 4), "party")
        assertEquals("vs Computers · 4 computers · Party", summarizeHubChoices(m, c))
    }

    @Test fun unoPassAndPlayPeopleAndComputers() {
        val m = mode("uno", "pass")
        val c = m.defaults().with(HubKeys.HUMANS, 3).with(HubKeys.CPUS, 1)
        assertEquals("Pass & Play · 3 players · 1 computer", summarizeHubChoices(m, c))
    }

    @Test fun hiddenOptionIsSkipped() {
        val m = mode("uno", "cpu")
        // stackDrawFourOnDrawTwo only shows while stackDraw is on, so a stale "true" is not mentioned.
        val hidden = m.defaults().with("stackDrawFourOnDrawTwo", true)
        assertFalse(summarizeHubChoices(m, hidden).contains("Allow +4 on +2"))
        val shown = hidden.with("stackDraw", true)
        assertEquals(
            "vs Computers · 2 computers · Draw stacking · Allow +4 on +2",
            summarizeHubChoices(m, shown)
        )
    }

    @Test fun customToggleIsListedOnlyWhenOn() {
        val m = mode("uno", "cpu")
        val c = m.defaults().with("jumpIn", true)
        assertEquals("vs Computers · 2 computers · Jump-in", summarizeHubChoices(m, c))
    }

    @Test fun dailyIsJustItsTitle() {
        val spec = GameHubSpecs.all.first { it.hasDaily }
        val daily = spec.modes.first { it.kind == HubModeKind.DAILY }
        assertEquals(daily.title, summarizeHubChoices(daily, daily.defaults()))
    }

    @Test fun nearbyIsJustItsTitle() {
        assertEquals("Nearby", summary("uno", "nearby"))
        assertEquals("Online", summary("uno", "online"))
    }

    @Test fun genericStepperUsesLabelAndValue() {
        val m = HubMode(
            id = "x", kind = HubModeKind.SOLO, title = "Free play", blurb = "", route = "x",
            start = HubStart.IMMEDIATE,
            options = listOf(HubOption.Stepper("handSize", "Starting hand", 3, 10, 7))
        )
        assertEquals("Free play", summarizeHubChoices(m, HubChoices()))
        assertEquals("Free play · Starting hand 9", summarizeHubChoices(m, HubChoices().with("handSize", 9)))
        assertTrue(summarizeHubChoices(m, HubChoices().with("handSize", 99)).endsWith("Starting hand 10"))
    }
}
