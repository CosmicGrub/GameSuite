package com.gamesuite.games.president

import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Hostile and malformed input: nothing throws, nothing changes, nothing is trusted. */
class PresidentAdversarialTest {
    private fun roster(n: Int) = List(n) { PsSeat("p${it + 1}", "P${it + 1}", false) }
    private fun players(n: Int) = List(n) { PlayerInfo("p${it + 1}", "P${it + 1}") }

    @Test fun randomHostileMovesAreRefusedWithoutThrowingOrChangingTheState() {
        val rnd = Random(77)
        repeat(60) { seed ->
            var s = PresidentFlow.newSession(roster(3 + seed % 4), PresidentRuleSet(), Random(seed), CpuDifficulty.MEDIUM)
            repeat(40) {
                if (s.phase != PsPhase.PLAY) return@repeat
                // garbage: empty, negative, out-of-range and duplicate ids, other seats' cards
                val garbage = listOf(
                    emptyList(), listOf(-1), listOf(52), listOf(Int.MAX_VALUE), listOf(Int.MIN_VALUE), listOf(5, 5),
                    List(rnd.nextInt(1, 6)) { rnd.nextInt(-3, 60) },
                    s.seats[rnd.nextInt(s.seats.size)].hand.shuffled(rnd).take(rnd.nextInt(1, 5))
                )
                for (g in garbage) for (seat in listOf(-1, 0, s.currentSeat, (s.currentSeat + 1) % s.seats.size, 99)) {
                    val r = if (seat in s.seats.indices) PresidentFlow.play(s, seat, g) else null
                    if (r != null) {
                        // an accepted move came from the seat on turn, held those cards, and left every card accounted for
                        assertEquals(seat, s.currentSeat)
                        assertTrue(g.all { it in s.seats[seat].hand })
                        assertTrue(PresidentFlow.conserved(r))
                    }
                    if (seat in s.seats.indices) assertNull(PresidentFlow.give(s, seat, g)) // not the exchange phase
                }
                val seat = s.currentSeat
                val options = PresidentRules.candidatePlays(s.seats[seat].hand, s.top?.cards, s.revolution, s.rules.ties, s.mustInclude)
                s = if (options.isEmpty()) PresidentFlow.pass(s, seat)!! else PresidentFlow.play(s, seat, options[rnd.nextInt(options.size)])!!
            }
        }
    }

    @Test fun statesSurviveAJsonRoundTripAtEveryStepAndRedactedViewsStayValid() {
        val json = Json { encodeDefaults = true }
        var s = PresidentFlow.newSession(roster(4), PresidentRuleSet(), Random(5), CpuDifficulty.MEDIUM)
        val rnd = Random(6)
        var guard = 0
        while (s.phase == PsPhase.PLAY && guard++ < 400) {
            assertEquals(s, json.decodeFromString(PresidentState.serializer(), json.encodeToString(PresidentState.serializer(), s)))
            for (viewer in listOf("p1", "p2", "p3", "p4", null)) {
                assertTrue(PresidentValidator.isValid(presidentRedactFor(s, viewer), players(4), allowHidden = true))
            }
            val seat = s.currentSeat
            val options = PresidentRules.candidatePlays(s.seats[seat].hand, s.top?.cards, s.revolution, s.rules.ties, s.mustInclude)
            s = if (options.isEmpty()) PresidentFlow.pass(s, seat)!! else PresidentFlow.play(s, seat, options[rnd.nextInt(options.size)])!!
        }
        assertTrue(guard > 5)
    }

    @Test fun theValidatorRejectsTamperedStates() {
        val roster = players(4)
        val s = PresidentFlow.newSession(roster(4), PresidentRuleSet(), Random(5), CpuDifficulty.MEDIUM)
        assertTrue(PresidentValidator.isValid(s, roster, allowHidden = false))
        fun bad(x: PresidentState) = assertFalse(PresidentValidator.isValid(x, roster, allowHidden = false))
        bad(s.copy(currentSeat = 9))
        bad(s.copy(roundNumber = 0))
        bad(s.copy(roundNumber = 99))
        bad(s.copy(seats = s.seats.mapIndexed { i, x -> if (i == 0) x.copy(hand = x.hand + 3) else x }))
        bad(s.copy(playedCards = listOf(s.seats[0].hand[0])))                       // a card both held and played
        bad(s.copy(seats = s.seats.mapIndexed { i, x -> if (i == 1) x.copy(playerId = "mallory") else x }))
        bad(s.copy(event = "teleport"))
        bad(s.copy(phase = PsPhase.EXCHANGE))
        bad(s.copy(trick = listOf(PsTrickPlay(0, listOf(s.seats[0].hand[0])))))     // the trick's cards are not in the played pile
        bad(s.copy(seats = s.seats.mapIndexed { i, x -> if (i == 2) x.copy(hand = List(x.hand.size) { PS_HIDDEN_CARD }) else x })) // hidden cards in a full state
        bad(s.copy(sessionWins = mapOf("nobody" to 1)))
        bad(s.copy(rules = PresidentRuleSet(rounds = 7)))
    }

    @Test fun cpuSeatsNeverIllegallyMoveInAFullFuzzOfTenThousandDecisions() {
        var decisions = 0
        for (seed in 0 until 80) {
            val seats = 3 + seed % 4
            val rules = PresidentRuleSet(revolutions = seed % 2 == 0, ties = seed % 3 != 0, exchange = seed % 5 != 0, rounds = 3)
            val rnd = Random(seed)
            var s = PresidentFlow.newSession(roster(seats).map { it.copy(isBot = true) }, rules, rnd, CpuDifficulty.values()[seed % 3])
            var guard = 0
            while (s.phase != PsPhase.SESSION_OVER && guard++ < 30000) {
                s = if (s.roundOver) PresidentFlow.nextRound(s, rnd, CpuDifficulty.values()[seed % 3])!! else {
                    val seat = s.currentSeat
                    val level = CpuDifficulty.values()[(seed + s.eventSerial) % 3]
                    val choice = PresidentBot.choose(s, seat, level, rnd)
                    decisions++
                    (if (choice == null) PresidentFlow.pass(s, seat) else PresidentFlow.play(s, seat, choice))
                        ?: error("seed $seed: $level made an illegal move $choice in $s")
                }
                assertTrue(PresidentFlow.conserved(s))
            }
            assertEquals(PsPhase.SESSION_OVER, s.phase)
        }
        assertTrue("only $decisions decisions", decisions > 10000)
    }
}
