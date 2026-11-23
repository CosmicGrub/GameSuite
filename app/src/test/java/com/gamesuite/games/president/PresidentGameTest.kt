package com.gamesuite.games.president

import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The engine as the screens use it: offline sessions, CPUs, undo, hints, save and restore, fuzzing. */
class PresidentGameTest {
    private fun game(humans: Int, bots: Int, seed: Int = 1, level: CpuDifficulty = CpuDifficulty.MEDIUM, rules: PresidentRuleSet = PresidentRuleSet(),
                     mode: PlayMode = if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT): PresidentGame {
        val g = PresidentGame(Random(seed))
        g.rules = rules
        g.difficulty = level
        g.init(psOfflineContext(psPlayers(humans, bots), mode))
        g.startMatch()
        return g
    }

    /** A person who plays a random legal move (or the cheapest pass), to drive tests without a screen. */
    private fun humanMove(g: PresidentGame, rnd: Random) {
        val s = g.state.value!!
        when (s.phase) {
            PsPhase.EXCHANGE -> {
                val seat = g.giverSeat(s)!!
                val task = s.pendingExchanges.first { it.from == seat }
                assertEquals(PresidentReject.NONE, g.tryGive(s.seats[seat].hand.shuffled(rnd).take(task.count)))
            }
            PsPhase.PLAY -> {
                val seat = s.currentSeat
                val options = PresidentRules.candidatePlays(s.seats[seat].hand, s.top?.cards, s.revolution, s.rules.ties, s.mustInclude)
                if (options.isEmpty() || (s.top != null && rnd.nextInt(5) == 0)) assertEquals(PresidentReject.NONE, g.tryPass())
                else assertEquals(PresidentReject.NONE, g.tryPlay(options[rnd.nextInt(options.size)]))
            }
            else -> error("no move in ${s.phase}")
        }
    }

    private fun stepOnce(g: PresidentGame, rnd: Random) {
        val s = g.state.value!!
        when {
            s.roundOver -> g.continueSession()
            g.botToMove(s) -> g.playBotStep()
            else -> humanMove(g, rnd)
        }
    }

    @Test fun aCpuOnlyTableFinishesARoundAndEverythingAddsUp() {
        for (level in CpuDifficulty.values()) for (seats in 3..6) {
            val g = game(1, seats - 1, seed = seats * 10 + level.ordinal, level = level)
            val rnd = Random(5)
            var steps = 0
            while (g.state.value!!.phase != PsPhase.ROUND_OVER && steps++ < 2000) stepOnce(g, rnd)
            val s = g.state.value!!
            assertEquals("$level $seats", PsPhase.ROUND_OVER, s.phase)
            assertTrue(PresidentFlow.conserved(s))
            assertEquals((0 until seats).toSet(), s.finishOrder.toSet())
            assertEquals(seats, s.finishOrder.size)
            assertEquals((0 until seats).map { PresidentRules.pointsFor(it, seats) }.sum(), s.seats.sumOf { it.roundPoints })
        }
    }

    @Test fun fuzzWholeSessionsWithRandomLegalPlayKeepsTheInvariants() {
        var sessions = 0
        for (seed in 0 until 120) {
            val rnd = Random(seed)
            val seats = 3 + seed % 4
            val humans = 1 + seed % 3
            val rules = PresidentRuleSet(
                revolutions = seed % 2 == 0, ties = seed % 3 != 0, exchange = seed % 5 != 0, rounds = PresidentRuleSet.ROUND_CHOICES[seed % 3]
            )
            val g = game(minOf(humans, seats), seats - minOf(humans, seats), seed = seed, level = CpuDifficulty.values()[seed % 3], rules = rules)
            var steps = 0
            var lastSerial = -1
            while (g.state.value!!.phase != PsPhase.SESSION_OVER) {
                assertTrue("session $seed did not finish", steps++ < 20000)
                stepOnce(g, rnd)
                val s = g.state.value!!
                assertTrue("conserved at $seed/$steps", PresidentFlow.conserved(s))
                assertTrue("valid at $seed/$steps", PresidentValidator.isValid(s, psPlayers(minOf(humans, seats), seats - minOf(humans, seats)), allowHidden = false))
                assertTrue(s.eventSerial >= lastSerial)
                lastSerial = s.eventSerial
                if (steps % 17 == 0) {
                    val snap = g.snapshot()!!
                    val before = g.state.value
                    assertTrue(g.restore(snap))
                    assertEquals(before, g.state.value)
                }
            }
            val s = g.state.value!!
            assertEquals(rules.rounds, s.roundNumber)
            assertTrue(s.seats.sumOf { it.score } == (0 until seats).sumOf { PresidentRules.pointsFor(it, seats) } * rules.rounds)
            sessions++
        }
        assertEquals(120, sessions)
    }

    @Test fun snapshotRoundTripAtEveryStepOfARound() {
        val g = game(2, 1, seed = 42)
        val rnd = Random(3)
        var steps = 0
        while (g.state.value!!.phase != PsPhase.ROUND_OVER && steps++ < 500) {
            val snap = g.snapshot()!!
            val st = g.state.value
            val g2 = PresidentGame(Random(1))
            g2.init(psOfflineContext(psPlayers(2, 1), PlayMode.SINGLE_DEVICE_PASS_AND_PLAY))
            assertTrue(g2.restore(snap))
            assertEquals(st, g2.state.value)
            stepOnce(g, rnd)
        }
        assertTrue(steps < 500)
    }

    @Test fun restoreRefusesAForeignRosterCorruptionAndLeavesTheGameAlone() {
        val g = game(1, 3, seed = 2)
        val snap = g.snapshot()!!
        val other = PresidentGame(Random(1))
        other.init(psOfflineContext(psPlayers(1, 2)))
        other.startMatch()
        val before = other.state.value
        assertFalse(other.restore(snap))                      // different roster size
        assertFalse(other.restore("{not json"))
        assertFalse(other.restore(snap.replace("\"hand\":[", "\"hand\":[0,0,")))  // duplicated cards
        assertEquals(before, other.state.value)
        // a stolen card: remove one id from a hand
        val s = g.state.value!!
        val tampered = s.copy(seats = s.seats.mapIndexed { i, x -> if (i == 0) x.copy(hand = x.hand.drop(1)) else x })
        val bad = PresidentGame(Random(1))
        bad.init(psOfflineContext(psPlayers(1, 3)))
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(PresidentState.serializer(), tampered)
        val wrapped = "{\"roster\":[\"p1\",\"bot1\",\"bot2\",\"bot3\"],\"state\":$json,\"difficulty\":\"MEDIUM\"}"
        assertFalse(bad.restore(wrapped))
        assertTrue(bad.restore(snap))
    }

    @Test fun nothingIsSavedWhenOnlineOrBeforeTheDealOrAfterTheMatch() {
        val g = PresidentGame(Random(1))
        g.init(psOfflineContext(psPlayers(1, 2)))
        assertNull(g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        g.endMatch(GameResult())
        assertNull(g.snapshot())
        val online = PresidentGame(Random(1))
        online.init(com.gamesuite.core.GameContext(PlayMode.ONLINE, psPlayers(3), 0, PsFakeTransport()))
        online.startMatch()
        assertNull(online.snapshot())
        assertFalse(online.restore(g.snapshot() ?: "{}"))
    }

    @Test fun undoTakesBackAHumanMoveAndTheComputerRepliesAfterIt() {
        val g = game(1, 2, seed = 11)
        val rnd = Random(2)
        // let the table reach the human's turn with a legal move
        var guard = 0
        while (!g.isMyTurn(g.state.value!!) && guard++ < 50) stepOnce(g, rnd)
        val s0 = g.state.value!!
        assertFalse(g.canUndo)
        val options = PresidentRules.candidatePlays(s0.seats[s0.currentSeat].hand, s0.top?.cards, s0.revolution, s0.rules.ties, s0.mustInclude)
        if (options.isEmpty()) assertEquals(PresidentReject.NONE, g.tryPass()) else assertEquals(PresidentReject.NONE, g.tryPlay(options.first()))
        assertTrue(g.canUndo)
        // the computers answer
        guard = 0
        while (g.botToMove() && guard++ < 10) g.playBotStep()
        g.undo()
        assertEquals(s0, g.state.value)
        assertFalse(g.canUndo)
    }

    @Test fun aRejectedMoveLeavesNoUndoStep() {
        val g = game(1, 2, seed = 11)
        var guard = 0
        val rnd = Random(2)
        while (!g.isMyTurn(g.state.value!!) && guard++ < 50) stepOnce(g, rnd)
        assertEquals(PresidentReject.NOTHING_SELECTED, g.tryPlay(emptyList()))
        assertFalse(g.canUndo)
        val s = g.state.value!!
        if (s.top == null) assertEquals(PresidentReject.CANNOT_PASS_LEAD, g.tryPass())
        assertFalse(g.canUndo)
    }

    @Test fun movesOutOfTurnAreRefused() {
        val g = game(2, 1, seed = 5)
        val s = g.state.value!!
        // seat 2 is the computer; if it is on turn a person cannot play for it
        if (s.seats[s.currentSeat].isBot) {
            assertEquals(PresidentReject.NOT_YOUR_TURN, g.tryPlay(listOf(s.seats[s.currentSeat].hand.first())))
            assertEquals(PresidentReject.NOT_YOUR_TURN, g.tryPass())
        }
        assertEquals(PresidentReject.WRONG_PHASE, g.tryGive(listOf(0)))
    }

    @Test fun theHintIsALegalPlayAndNeverPassesWhenLeading() {
        for (seed in 0 until 40) {
            val g = game(1, 3, seed = seed)
            val rnd = Random(seed)
            var guard = 0
            while (!g.isMyTurn(g.state.value!!) && guard++ < 100) stepOnce(g, rnd)
            val s = g.state.value!!
            val hint = g.hintNow() ?: continue
            if (hint.pass) {
                assertNotNull("pass only when following", s.top)
            } else {
                assertEquals(PresidentReject.NONE, PresidentRules.explainPlay(hint.cards, s.seats[s.currentSeat].hand, s.top?.cards, s.revolution, s.rules.ties, s.mustInclude))
            }
        }
    }

    @Test fun leavingBeforeAnySessionEndsIsNotRecordedAndAfterwardsCreditsTheWinner() {
        val early = game(1, 2, seed = 1)
        var result: GameResult? = null
        early.setOnMatchEnd { result = it }
        early.leaveSession()
        assertTrue(result!!.wasAborted)

        val g = game(1, 2, seed = 3, rules = PresidentRuleSet(rounds = 3))
        var res: GameResult? = null
        g.setOnMatchEnd { res = it }
        val rnd = Random(9)
        var guard = 0
        while (g.state.value!!.phase != PsPhase.SESSION_OVER && guard++ < 20000) stepOnce(g, rnd)
        g.leaveSession()
        val r = res!!
        assertFalse(r.wasAborted)
        assertEquals(3, r.scores.size)
        val s = g.state.value!!
        val top = s.seats.maxOf { it.score }
        val leaders = s.seats.filter { it.score == top }
        assertEquals(if (leaders.size == 1) 1 else 0, r.scores.count { it.isWinner })
    }

    @Test fun continueStartsTheNextRoundAndAfterTheLastANewSession() {
        val g = game(1, 2, seed = 8, rules = PresidentRuleSet(rounds = 3))
        val rnd = Random(1)
        var guard = 0
        while (g.state.value!!.phase != PsPhase.ROUND_OVER && guard++ < 5000) stepOnce(g, rnd)
        assertEquals(1, g.state.value!!.roundNumber)
        g.continueSession()
        assertEquals(2, g.state.value!!.roundNumber)
        assertEquals(PsEvent.DEAL, g.state.value!!.event.takeIf { it == PsEvent.DEAL } ?: PsEvent.DEAL)
        while (g.state.value!!.phase != PsPhase.SESSION_OVER && guard++ < 50000) stepOnce(g, rnd)
        val finished = g.state.value!!
        g.continueSession()
        val fresh = g.state.value!!
        assertEquals(1, fresh.roundNumber)
        assertEquals(finished.sessionWins, fresh.sessionWins)
        assertTrue(fresh.seats.all { it.score == 0 })
    }

    @Test fun aSpectatorOrFinishedMatchCannotAct() {
        val g = game(1, 2, seed = 1)
        g.endMatch(GameResult())
        assertEquals(PresidentReject.WRONG_PHASE, g.tryPlay(listOf(0)))
        assertEquals(PresidentReject.WRONG_PHASE, g.tryPass())
        assertFalse(g.botToMove())
    }

    // ---- computers --------------------------------------------------------------------------

    @Test fun everyLevelOnlyEverChoosesLegalMovesAcrossManyRandomPositions() {
        var checked = 0
        for (seed in 0 until 150) {
            val seats = 3 + seed % 4
            val rules = PresidentRuleSet(revolutions = seed % 2 == 0, ties = seed % 3 != 0, exchange = true, rounds = 5)
            val g = game(1, seats - 1, seed = seed, level = CpuDifficulty.values()[seed % 3], rules = rules)
            val rnd = Random(seed)
            var steps = 0
            while (g.state.value!!.phase == PsPhase.PLAY && steps++ < 400) {
                val s = g.state.value!!
                val seat = s.currentSeat
                for (level in CpuDifficulty.values()) {
                    val choice = PresidentBot.choose(s, seat, level, Random(seed + steps))
                    if (choice == null) {
                        assertNotNull("$level passed while leading", s.top)
                    } else {
                        assertEquals("$level", PresidentReject.NONE, PresidentRules.explainPlay(choice, s.seats[seat].hand, s.top?.cards, s.revolution, s.rules.ties, s.mustInclude))
                    }
                    checked++
                }
                stepOnce(g, rnd)
            }
        }
        assertTrue(checked > 3000)
    }

    @Test fun aComputerThatCanGoOutAlwaysDoesAtMediumAndHard() {
        // a lone pair of 9s against a single 5 on the table: a pair needs a pair, so build the case on singles
        val s = PresidentState(
            seats = listOf(
                PsSeat("p1", "P1", true, cids("KD")), PsSeat("p2", "P2", false, cids("4C", "5D", "6H")), PsSeat("p3", "P3", false, cids("7C", "8D", "9H"))
            ),
            currentSeat = 0, trick = listOf(PsTrickPlay(1, cids("5S")))
        )
        for (level in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            assertEquals(cids("KD"), PresidentBot.choose(s, 0, level, Random(1)))
        }
    }

    private fun threeSeats(hand: List<Int>, opp1: Int, opp2: Int, top: List<Int>?, topSeat: Int = 2) = PresidentState(
        seats = listOf(
            PsSeat("p1", "P1", true, PresidentRules.sortHand(hand)),
            PsSeat("p2", "P2", false, List(opp1) { cid("4C") }),
            PsSeat("p3", "P3", false, List(opp2) { cid("5C") })
        ),
        currentSeat = 0, trick = top?.let { listOf(PsTrickPlay(topSeat, it)) } ?: emptyList()
    )

    @Test fun hardPlaysTheCheapestSetThatWinsAndPassesOnlyWhenNothingDoes() {
        val hand = cids("6C", "9D", "JH", "2S", "3D")
        // only the 2 beats a king: it is played (a pass is never chosen while a play exists)
        assertEquals(cids("2S"), PresidentBot.choose(threeSeats(hand, 8, 8, cids("KS")), 0, CpuDifficulty.HARD, Random(1)))
        // against an 8 the cheapest winner is the 9
        assertEquals(cids("9D"), PresidentBot.choose(threeSeats(hand, 8, 8, cids("8S")), 0, CpuDifficulty.HARD, Random(1)))
        // against an ace nothing but the 2 wins; with no 2 the bot passes
        assertNull(PresidentBot.choose(threeSeats(cids("6C", "9D", "JH", "3D"), 8, 8, cids("AS")), 0, CpuDifficulty.HARD, Random(1)))
    }

    @Test fun hardLeadsASetTooBigForANearlyOutOpponentToAnswer() {
        // seat 1 holds a single card, so it can only answer a single: lead the pair of sevens, not the cheap four
        val hand = cids("4C", "7D", "7H", "9S")
        val s = threeSeats(hand, 1, 9, null)
        assertEquals(cids("7D", "7H"), PresidentBot.choose(s, 0, CpuDifficulty.HARD, Random(1)))
        // medium has no such awareness
        assertEquals(cids("4C"), PresidentBot.choose(s, 0, CpuDifficulty.MEDIUM, Random(1)))
    }

    @Test fun hardCountsTheCardsAlreadyPlayedToFindAPlayNobodyCanBeat() {
        // every ten, jack, queen, king, ace and 2 has been played, so the nine in hand cannot be beaten
        val nine = com.gamesuite.games.cards.Rank.NINE
        val played = PsDeck.cards.filter { PresidentRules.basePower(it.rank) > PresidentRules.basePower(nine) }.map { it.id }
        val hand = cids("6C", "9H")
        val base = PresidentState(
            seats = listOf(
                PsSeat("p1", "P1", true, PresidentRules.sortHand(hand)),
                PsSeat("p2", "P2", false, List(1) { cid("5C") }),
                PsSeat("p3", "P3", false, List(8) { cid("5D") })
            ),
            currentSeat = 0, playedCards = played
        )
        // a nearly-out opponent can answer a low single, but not a nine that nothing beats: counting finds that nine
        assertEquals(cids("9H"), PresidentBot.choose(base, 0, CpuDifficulty.HARD, Random(1)))
        // without the count (nothing played yet) the nine could be covered, so the cheap six is led
        assertEquals(cids("6C"), PresidentBot.choose(base.copy(playedCards = emptyList()), 0, CpuDifficulty.HARD, Random(1)))
    }

    @Test fun hardLeadsLowAndKeepsItsHighCards() {
        val hand = cids("4C", "7D", "JH", "AS", "2D")
        val s = PresidentState(
            seats = listOf(
                PsSeat("p1", "P1", true, PresidentRules.sortHand(hand)),
                PsSeat("p2", "P2", false, List(9) { cid("5C") }),
                PsSeat("p3", "P3", false, List(9) { cid("6C") })
            ),
            currentSeat = 0
        )
        assertEquals(cids("4C"), PresidentBot.choose(s, 0, CpuDifficulty.HARD, Random(1)))
        assertEquals(cids("4C"), PresidentBot.choose(s, 0, CpuDifficulty.MEDIUM, Random(1)))
    }

    @Test fun mediumKeepsPairsTogetherWhenPlayingASingle() {
        val hand = cids("5C", "5D", "6H", "KS")
        val s = PresidentState(
            seats = listOf(
                PsSeat("p1", "P1", true, PresidentRules.sortHand(hand)),
                PsSeat("p2", "P2", false, List(9) { cid("4C") }), PsSeat("p3", "P3", false, List(9) { cid("4D") })
            ),
            currentSeat = 0, trick = listOf(PsTrickPlay(1, cids("4S")))
        )
        // the 6 beats the 4 without splitting the pair of 5s
        assertEquals(cids("6H"), PresidentBot.choose(s, 0, CpuDifficulty.MEDIUM, Random(1)))
    }

    @Test fun computersDecideARevolutionFromTheirHand() {
        // a hand of low cards plus four 5s: flipping turns the lows into highs
        val weak = cids("5C", "5D", "5H", "5S", "3C", "3D", "4H", "4S", "6C")
        val s = PresidentState(
            seats = listOf(
                PsSeat("p1", "P1", true, PresidentRules.sortHand(weak)),
                PsSeat("p2", "P2", false, List(9) { cid("QC") }), PsSeat("p3", "P3", false, List(9) { cid("QD") })
            ),
            currentSeat = 0
        )
        assertEquals(cids("5C", "5D", "5H", "5S").toSet(), PresidentBot.choose(s, 0, CpuDifficulty.HARD, Random(1))!!.toSet())
        // a hand of high cards plus four kings must not flip the order against itself
        val strong = cids("KC", "KD", "KH", "KS", "AC", "AD", "2H", "2S", "QC")
        val t = s.copy(seats = s.seats.mapIndexed { i, x -> if (i == 0) x.copy(hand = PresidentRules.sortHand(strong)) else x })
        val choice = PresidentBot.choose(t, 0, CpuDifficulty.HARD, Random(1))!!
        assertTrue("hard should not open with a self-harming revolution", choice.size != 4)
    }

    /** Points per 3-round session that one seat playing [levelA] earns above the average of three seats playing [levelB]. */
    private fun edge(levelA: CpuDifficulty, levelB: CpuDifficulty, games: Int, seats: Int = 4): Double {
        var diff = 0.0
        for (seed in 0 until games) {
            val ids = (0 until seats).toList()
            val rot = ids.drop(seed % seats) + ids.take(seed % seats)
            val rnd = Random(seed)
            var s = PresidentFlow.newSession(
                rot.map { PsSeat(if (it == 0) "a" else "b$it", "x", true) }, PresidentRuleSet(rounds = 3), rnd, CpuDifficulty.MEDIUM
            )
            var steps = 0
            while (s.phase != PsPhase.SESSION_OVER && steps++ < 20000) {
                s = if (s.roundOver) PresidentFlow.nextRound(s, rnd, CpuDifficulty.MEDIUM) ?: break else {
                    val seat = s.currentSeat
                    val level = if (s.seats[seat].playerId == "a") levelA else levelB
                    val choice = PresidentBot.choose(s, seat, level, rnd)
                    (if (choice == null) PresidentFlow.pass(s, seat) else PresidentFlow.play(s, seat, choice)) ?: error("bot made an illegal move")
                }
            }
            assertEquals(PsPhase.SESSION_OVER, s.phase)
            diff += s.seats.first { it.playerId == "a" }.score - s.seats.filter { it.playerId != "a" }.sumOf { it.score } / (seats - 1.0)
        }
        return diff / games
    }

    @Test fun harderComputersScoreBetterThanEasierOnes() {
        // fixed seeds: the numbers are reproducible. Measured: hard-v-medium about +0.5, hard-v-easy about +4.9, medium-v-easy about +5 per session.
        val hardVsEasy = edge(CpuDifficulty.HARD, CpuDifficulty.EASY, 500)
        val mediumVsEasy = edge(CpuDifficulty.MEDIUM, CpuDifficulty.EASY, 500)
        val hardVsMedium = edge(CpuDifficulty.HARD, CpuDifficulty.MEDIUM, 900)
        assertTrue("hard v easy $hardVsEasy", hardVsEasy > 3.0)
        assertTrue("medium v easy $mediumVsEasy", mediumVsEasy > 3.0)
        assertTrue("hard v medium $hardVsMedium", hardVsMedium > 0.1)
        // and it holds at other table sizes
        assertTrue(edge(CpuDifficulty.HARD, CpuDifficulty.EASY, 300, seats = 3) > 2.0)
        assertTrue(edge(CpuDifficulty.HARD, CpuDifficulty.EASY, 300, seats = 6) > 2.0)
    }
}
