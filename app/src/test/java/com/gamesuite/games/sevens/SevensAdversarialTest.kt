package com.gamesuite.games.sevens

import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Hostile and malformed input for Sevens: nothing throws, nothing changes, nothing is trusted. */
class SevensAdversarialTest {
    @Test fun hostileMovesAreRefusedWithoutThrowing() {
        val rnd = Random(3)
        repeat(60) { seed ->
            var s = SevensFlow.newTable(List(3 + seed % 4) { SvSeat("p$it", "P$it", false) }, SevensRuleSet(), Random(seed))
            repeat(60) {
                if (s.phase != SvPhase.PLAY) return@repeat
                for (card in listOf(-1, 52, Int.MAX_VALUE, Int.MIN_VALUE, rnd.nextInt(0, 52), rnd.nextInt(0, 52))) {
                    for (seat in listOf(-1, 0, s.currentSeat, (s.currentSeat + 1) % s.seats.size, 99)) {
                        val r = if (seat in s.seats.indices) SevensFlow.play(s, seat, card) else null
                        if (r != null) {
                            assertEquals(seat, s.currentSeat)
                            assertTrue(card in s.seats[seat].hand && SevensRules.isPlayable(card, s.low, s.high))
                            assertTrue(SevensFlow.conserved(r))
                        }
                    }
                }
                val seat = s.currentSeat
                val playable = SevensRules.playableCards(s.seats[seat].hand, s.low, s.high)
                s = if (playable.isEmpty()) SevensFlow.pass(s, seat)!! else SevensFlow.play(s, seat, playable[rnd.nextInt(playable.size)])!!
            }
        }
    }

    @Test fun theValidatorRejectsTamperedStates() {
        val roster = (1..3).map { PlayerInfo("p$it", "P$it") }
        val s = SevensFlow.newTable(List(3) { SvSeat("p${it + 1}", "P${it + 1}", false) }, SevensRuleSet(), Random(2))
        assertTrue(SevensValidator.isValid(s, roster, allowHidden = false))
        fun bad(x: SevensState) = assertFalse(SevensValidator.isValid(x, roster, allowHidden = false))
        bad(s.copy(currentSeat = 7))
        bad(s.copy(low = listOf(0, 0, 0)))
        bad(s.copy(low = listOf(5, 0, 0, 0), high = listOf(9, 0, 0, 0)))       // a suit started before the seven of diamonds
        bad(s.copy(low = listOf(0, 9, 0, 0), high = listOf(0, 10, 0, 0)))      // a row that does not contain the seven
        bad(s.copy(seats = s.seats.mapIndexed { i, x -> if (i == 0) x.copy(hand = x.hand.drop(1)) else x }))
        bad(s.copy(seats = s.seats.mapIndexed { i, x -> if (i == 0) x.copy(hand = x.hand + x.hand[0]) else x }))
        bad(s.copy(phase = SvPhase.ROUND_OVER, winnerSeat = 0))                 // the winner still holds cards
        bad(s.copy(event = "nope"))
        bad(s.copy(wins = mapOf("ghost" to 1)))
        // a placeholder is only acceptable in a state a guest holds
        val redacted = sevensRedactFor(s, "p1")
        assertTrue(SevensValidator.isValid(redacted, roster, allowHidden = true))
        assertFalse(SevensValidator.isValid(redacted, roster, allowHidden = false))
    }

    @Test fun statesSurviveAJsonRoundTrip() {
        val json = Json { encodeDefaults = true }
        var s = SevensFlow.newTable(List(4) { SvSeat("p$it", "P$it", false) }, SevensRuleSet(passPenalty = true), Random(8))
        val rnd = Random(2)
        var guard = 0
        while (s.phase == SvPhase.PLAY && guard++ < 500) {
            assertEquals(s, json.decodeFromString(SevensState.serializer(), json.encodeToString(SevensState.serializer(), s)))
            val seat = s.currentSeat
            val playable = SevensRules.playableCards(s.seats[seat].hand, s.low, s.high)
            s = if (playable.isEmpty()) SevensFlow.pass(s, seat)!! else SevensFlow.play(s, seat, playable[rnd.nextInt(playable.size)])!!
        }
        assertEquals(SvPhase.ROUND_OVER, s.phase)
    }

    @Test fun cpuSeatsNeverMoveIllegallyAcrossThousandsOfRoundsAndAlwaysFinish() {
        var decisions = 0
        for (seed in 0 until 300) {
            val n = 3 + seed % 4
            var s = SevensFlow.newTable(List(n) { SvSeat("p$it", "P$it", true) }, SevensRuleSet(passPenalty = seed % 2 == 0), Random(seed))
            val rnd = Random(seed)
            var guard = 0
            while (s.phase == SvPhase.PLAY && guard++ < 2000) {
                val seat = s.currentSeat
                val c = SevensBot.choose(s, seat, CpuDifficulty.values()[(seed + guard) % 3], rnd)
                decisions++
                s = (if (c == null) SevensFlow.pass(s, seat) else SevensFlow.play(s, seat, c)) ?: error("seed $seed: illegal bot move $c")
            }
            assertEquals(SvPhase.ROUND_OVER, s.phase)
            assertTrue(SevensFlow.conserved(s))
            // the winner holds nothing; nobody else is empty
            assertTrue(s.seats[s.winnerSeat!!].hand.isEmpty())
        }
        assertTrue(decisions > 10000)
    }
}
