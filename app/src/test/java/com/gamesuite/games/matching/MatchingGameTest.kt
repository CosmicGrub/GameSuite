package com.gamesuite.games.matching

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.speed.FakeClock
import com.gamesuite.games.speed.SmFakeTransport
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

object MatchingTestKit {
    fun players(humans: Int, bots: Int = 0): List<PlayerInfo> =
        List(humans) { PlayerInfo("p${it + 1}", "P${it + 1}") } + List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }

    fun game(
        humans: Int = 1, bots: Int = 0, count: Int = 12, seed: Int = 1, clock: FakeClock = FakeClock(),
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM, results: MutableList<GameResult>? = null
    ): MatchingGame {
        val g = MatchingGame(Random(seed), clock)
        g.difficulty = difficulty
        g.cardCount = count
        val mode = if (bots > 0) PlayMode.SINGLE_PLAYER_VS_BOT else if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT
        g.init(GameContext(mode, players(humans, bots), 0, SmFakeTransport()))
        if (results != null) g.setOnMatchEnd { results += it }
        g.startMatch()
        return g
    }

    /** Pairs of card indices by picture, from the engine's own truth. */
    fun pairs(g: MatchingGame): List<Pair<Int, Int>> =
        g.truthFaces().withIndex().groupBy({ it.value }, { it.index }).values.map { it[0] to it[1] }

    /** One attempt: flip [a] and [b], then resolve. */
    fun attempt(g: MatchingGame, a: Int, b: Int): Boolean {
        check(g.flipCard(a) == MatchingReject.NONE)
        check(g.flipCard(b) == MatchingReject.NONE)
        return g.resolve()
    }
}

class MatchingGameTest {
    private val K = MatchingTestKit

    // ---- rules ----

    @Test fun gridsHaveExactlyTheRightNumberOfCells() {
        for (n in MatchingRules.CARD_COUNTS) for (portrait in listOf(true, false)) {
            val (c, r) = MatchingRules.grid(n, portrait)
            assertEquals(n, c * r)
            if (portrait) assertTrue(r >= c) else assertTrue(c >= r)
        }
        assertEquals(20, MatchingRules.normalizeCount(null))
        assertEquals(20, MatchingRules.normalizeCount(13))
        assertEquals(40, MatchingRules.countFromOptionId("40"))
        assertEquals(20, MatchingRules.countFromOptionId("banana"))
    }

    @Test fun everyDealHasEachFaceExactlyTwiceAndFacesComeFromThePool() {
        for (n in MatchingRules.CARD_COUNTS) for (seed in 1..20) {
            val d = MatchingRules.deal(n, Random(seed))
            assertEquals(n, d.size)
            assertTrue(d.all { it in 0 until MatchingRules.FACE_POOL })
            assertTrue(d.groupBy { it }.values.all { it.size == 2 })
        }
        assertTrue(MatchingRules.deal(40, Random(1)) != MatchingRules.deal(40, Random(2)))
    }

    @Test fun botProfilesForgetLessAsTheyGetStronger() {
        val e = MatchingRules.botProfile(CpuDifficulty.EASY)
        val m = MatchingRules.botProfile(CpuDifficulty.MEDIUM)
        val h = MatchingRules.botProfile(CpuDifficulty.HARD)
        assertTrue(e.rememberChance < m.rememberChance && m.rememberChance < h.rememberChance)
        assertTrue(e.forgetChance > m.forgetChance && m.forgetChance > h.forgetChance)
        assertEquals(1.0, h.rememberChance, 0.0)
        assertEquals(0.0, h.forgetChance, 0.0)
    }

    // ---- flow ----

    @Test fun aMatchStaysFaceUpScoresAndKeepsTheTurn() {
        val g = K.game(humans = 2, count = 12)
        val (a, b) = K.pairs(g).first()
        assertEquals(0, g.state.value!!.currentPlayerIndex)
        assertEquals(MatchingReject.NONE, g.flipCard(a))
        assertFalse(g.state.value!!.resolving)
        assertEquals(MatchingReject.NONE, g.flipCard(b))
        assertTrue(g.state.value!!.resolving)
        assertEquals(MatchingReject.RESOLVING, g.flipCard((0 until 12).first { it != a && it != b }))
        assertTrue(g.resolve())
        val s = g.state.value!!
        assertEquals(MatchingStatus.MATCHED, s.status[a]); assertEquals(MatchingStatus.MATCHED, s.status[b])
        assertEquals(listOf(0, 0), listOf(s.owner[a], s.owner[b]))
        assertEquals(listOf(1, 0), s.scores)
        assertEquals(0, s.currentPlayerIndex) // keeps the turn
        assertEquals(MatchingEvent.MATCH, s.event)
        assertEquals(MatchingReject.NOT_FACE_DOWN, g.flipCard(a))
    }

    @Test fun aMissTurnsBothBackAndPassesTheTurn() {
        val g = K.game(humans = 3, count = 12)
        val pairs = K.pairs(g)
        val a = pairs[0].first; val b = pairs[1].first
        assertTrue(K.attempt(g, a, b))
        val s = g.state.value!!
        assertEquals(MatchingStatus.DOWN, s.status[a]); assertEquals(MatchingStatus.DOWN, s.status[b])
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(MatchingEvent.MISS, s.event)
        assertEquals(listOf(1, 0, 0), s.moves)
        K.attempt(g, a, b); assertEquals(2, g.state.value!!.currentPlayerIndex)
        K.attempt(g, a, b); assertEquals(0, g.state.value!!.currentPlayerIndex)
    }

    @Test fun outOfRangeAndRepeatedTapsAreRefused() {
        val g = K.game(count = 12)
        assertEquals(MatchingReject.OUT_OF_RANGE, g.flipCard(-1))
        assertEquals(MatchingReject.OUT_OF_RANGE, g.flipCard(12))
        assertEquals(MatchingReject.NONE, g.flipCard(0))
        assertEquals(MatchingReject.NOT_FACE_DOWN, g.flipCard(0))
        assertFalse(g.resolve()) // only one card up: nothing to resolve
    }

    @Test fun aPerfectSoloGameTakesOneMovePerPairAndIsTimed() {
        val clock = FakeClock(); val results = mutableListOf<GameResult>()
        val g = K.game(count = 20, clock = clock, results = results)
        assertEquals(0L, g.elapsedMs())
        var first = true
        for ((a, b) in K.pairs(g)) {
            K.attempt(g, a, b)
            if (first) { first = false }
            clock.advance(2_000)
        }
        val s = g.state.value!!
        assertTrue(s.boardOver)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(10, s.scores[0]); assertEquals(10, s.moves[0])
        assertEquals(18_000L, g.elapsedMs()) // nine 2 s gaps between ten attempts: the clock started at the first card
        clock.advance(5_000); assertEquals(18_000L, g.elapsedMs()) // stopped at the end
        assertTrue(g.isCleanRun)
        assertEquals(1, g.sessionWins.value["p1"])
        g.leaveSession()
        assertEquals(true, results.single().scores.single().isWinner)
    }

    @Test fun theMostPairsWinsAndAnEvenSplitIsATie() {
        val tie = K.game(humans = 2, count = 12)
        val ps = K.pairs(tie)
        // p1 takes 3 pairs, then misses; p2 takes the other 3.
        ps.take(3).forEach { (a, b) -> K.attempt(tie, a, b) }
        K.attempt(tie, ps[3].first, ps[4].first)
        assertEquals(1, tie.state.value!!.currentPlayerIndex)
        ps.drop(3).forEach { (a, b) -> K.attempt(tie, a, b) }
        val t = tie.state.value!!
        assertTrue(t.boardOver); assertTrue(t.tie); assertNull(t.winnerPlayerId)
        assertEquals(1, tie.sessionDraws.value)

        val win = K.game(humans = 2, count = 12)
        val pw = K.pairs(win)
        pw.take(4).forEach { (a, b) -> K.attempt(win, a, b) }
        K.attempt(win, pw[4].first, pw[5].first)
        pw.drop(4).forEach { (a, b) -> K.attempt(win, a, b) }
        val w = win.state.value!!
        assertTrue(w.boardOver); assertFalse(w.tie); assertEquals("p1", w.winnerPlayerId)
        assertEquals(listOf(4, 2), w.scores)
    }

    @Test fun playAgainDealsAFreshBoardKeepingTheTally() {
        val g = K.game(count = 12)
        K.pairs(g).forEach { (a, b) -> K.attempt(g, a, b) }
        assertTrue(g.state.value!!.boardOver)
        val before = g.truthFaces()
        g.playAgain()
        val s = g.state.value!!
        assertFalse(s.boardOver)
        assertTrue(s.status.all { it == MatchingStatus.DOWN })
        assertEquals(0, g.hintsUsed.value)
        assertEquals(1, g.sessionWins.value["p1"])
        assertTrue(before != g.truthFaces())
    }

    @Test fun theFirstOpenerRotatesBetweenBoards() {
        val g = MatchingGame(Random(2), FakeClock())
        g.firstOpenerIndex = 1; g.cardCount = 12
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, K.players(3), 0, SmFakeTransport()))
        g.startMatch()
        assertEquals(1, g.state.value!!.currentPlayerIndex)
        K.pairs(g).forEach { (a, b) -> K.attempt(g, a, b) }
        g.playAgain()
        assertEquals(2, g.state.value!!.currentPlayerIndex)
    }

    // ---- hint and undo ----

    @Test fun hintOnlyUsesWhatHasBeenSeen() {
        val g = K.game(count = 12)
        val ps = K.pairs(g)
        val h0 = g.requestHint()!!
        assertFalse(h0.known) // nothing seen yet: just an unseen card to try
        assertEquals(1, h0.cards.size)
        // See pair 0's first card and pair 1's first card (a miss), then pair 0's second card and another one.
        K.attempt(g, ps[0].first, ps[1].first)
        K.attempt(g, ps[0].second, ps[2].first)
        val h = g.requestHint()!!
        assertTrue(h.known)
        assertEquals(setOf(ps[0].first, ps[0].second), h.cards.toSet())
        assertEquals(2, g.hintsUsed.value)
        assertFalse(g.isCleanRun)
        // With one card of that pair already face up, the hint is its partner alone.
        g.flipCard(ps[0].first)
        assertEquals(listOf(ps[0].second), g.requestHint()!!.cards)
    }

    @Test fun hintIsRefusedForTheComputersTurnAndNetworkedPlay() {
        val g = K.game(humans = 1, bots = 1, count = 12)
        K.pairs(g).first().let { (a, b) -> K.attempt(g, a, K.pairs(g)[1].first); assertNotNull(a); assertNotNull(b) }
        assertTrue(g.state.value!!.players[g.state.value!!.currentPlayerIndex].isBot)
        assertNull(g.requestHint())
        assertEquals(MatchingReject.NOT_YOUR_TURN, g.flipCard(0))
    }

    @Test fun undoTakesBackTheLastAttemptButNotTheHintCount() {
        val g = K.game(count = 12)
        val ps = K.pairs(g)
        assertFalse(g.canUndo)
        K.attempt(g, ps[0].first, ps[1].first)
        K.attempt(g, ps[0].second, ps[2].first)
        assertTrue(g.canUndo)
        g.requestHint()
        val movesBefore = g.state.value!!.moves[0]
        g.undo()
        assertEquals(movesBefore - 1, g.state.value!!.moves[0])
        assertEquals(1, g.undosUsed.value)
        assertEquals(1, g.hintsUsed.value)
        assertFalse(g.isCleanRun)
        g.undo()
        assertEquals(0, g.state.value!!.moves[0])
        assertFalse(g.canUndo)
        assertTrue(g.state.value!!.status.all { it == MatchingStatus.DOWN })
        // the board is the same one (same faces)
        assertEquals(ps, K.pairs(g))
    }

    @Test fun undoIsOnlyForSoloPlay() {
        val g = K.game(humans = 2, count = 12)
        val ps = K.pairs(g)
        K.attempt(g, ps[0].first, ps[1].first)
        assertFalse(g.canUndo)
    }

    // ---- computer ----

    @Test fun aComputerThatKnowsAPairPlaysIt() {
        val input = MatchingBotInput(
            status = IntArray(12), flipped = emptyList(), flippedFace = -1,
            memory = mapOf(3 to 7, 9 to 7, 5 to 2)
        )
        repeat(20) {
            val pick = MatchingBot.choose(input, MatchingRules.botProfile(CpuDifficulty.HARD), Random(it))
            assertTrue(pick == 3 || pick == 9)
        }
        // after turning one of them it flips the other
        val after = MatchingBotInput(IntArray(12).also { s -> s[3] = MatchingStatus.UP }, listOf(3), 7, mapOf(9 to 7, 5 to 2, 3 to 7))
        assertEquals(9, MatchingBot.choose(after, MatchingRules.botProfile(CpuDifficulty.HARD), Random(1)))
    }

    @Test fun aComputerExploresUnknownCardsAndNeverPicksTheCardAlreadyUp() {
        val status = IntArray(8).also { it[2] = MatchingStatus.UP; it[0] = MatchingStatus.MATCHED; it[1] = MatchingStatus.MATCHED }
        val input = MatchingBotInput(status, listOf(2), 4, mapOf(5 to 6, 6 to 1))
        repeat(50) {
            val pick = MatchingBot.choose(input, MatchingRules.botProfile(CpuDifficulty.HARD), Random(it))!!
            assertTrue(pick in listOf(3, 4, 7)) // face down, not the one up, not already remembered
        }
        assertNull(MatchingBot.choose(MatchingBotInput(IntArray(4) { MatchingStatus.MATCHED }, emptyList(), -1, emptyMap()), MatchingRules.botProfile(CpuDifficulty.HARD), Random(1)))
    }

    private fun attemptsToClear(level: CpuDifficulty, seed: Int, count: Int): Int {
        val g = MatchingGame(Random(seed), FakeClock())
        g.difficulty = level; g.cardCount = count
        g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, K.players(0, 2), 0, SmFakeTransport()))
        g.startMatch()
        var attempts = 0
        while (!g.state.value!!.boardOver) {
            assertTrue("stuck", attempts++ < 2_000)
            assertTrue(g.playBotAttempt())
        }
        assertEquals(count / 2, g.state.value!!.scores.sum())
        return g.state.value!!.moves.sum()
    }

    @Test fun hardRemembersEverythingSoItFinishesQuicklyAndEasyDoesNot() {
        val seeds = 1..30
        val hard = seeds.map { attemptsToClear(CpuDifficulty.HARD, it, 40) }
        val easy = seeds.map { attemptsToClear(CpuDifficulty.EASY, it, 40) }
        val medium = seeds.map { attemptsToClear(CpuDifficulty.MEDIUM, it, 40) }
        // Perfect memory: each attempt either wins a pair or exposes at least one new card, so at most about 20 + 20 attempts.
        assertTrue("hard worst case ${hard.max()}", hard.max() <= 45)
        assertTrue(hard.average() < medium.average())
        assertTrue(medium.average() < easy.average())
    }

    @Test fun computersAlwaysFinishAndNeverLoseACard() {
        for (level in CpuDifficulty.entries) for (bots in 2..4) for (count in MatchingRules.CARD_COUNTS) {
            val g = MatchingGame(Random(bots * 100 + count), FakeClock())
            g.difficulty = level; g.cardCount = count
            g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, K.players(0, bots), 0, SmFakeTransport()))
            g.startMatch()
            val faces = g.truthFaces().sorted()
            var n = 0
            while (!g.state.value!!.boardOver) {
                assertTrue(n++ < 3_000)
                assertTrue(g.playBotAttempt())
                assertEquals(faces, g.truthFaces().sorted())
            }
            val s = g.state.value!!
            assertEquals(count / 2, s.scores.sum())
            assertTrue(s.status.all { it == MatchingStatus.MATCHED })
            assertTrue(s.owner.all { it in 0 until bots })
        }
    }

    // ---- resume ----

    @Test fun snapshotRoundTripsMidGameIncludingAFaceUpCard() {
        val clock = FakeClock()
        val g = K.game(count = 20, clock = clock, seed = 8)
        val ps = K.pairs(g)
        K.attempt(g, ps[0].first, ps[0].second)
        K.attempt(g, ps[1].first, ps[2].first)
        clock.advance(4_000)
        g.flipCard(ps[3].first) // a face-up card waiting for its partner
        val snap = g.snapshot()!!
        val c2 = FakeClock(5_000_000)
        val h = MatchingGame(Random(99), c2)
        h.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, K.players(1), 0, SmFakeTransport()))
        assertTrue(h.restore(snap))
        val a = g.state.value!!; val b = h.state.value!!
        assertEquals(a.status, b.status); assertEquals(a.owner, b.owner); assertEquals(a.faces, b.faces)
        assertEquals(a.scores, b.scores); assertEquals(a.moves, b.moves); assertEquals(a.currentPlayerIndex, b.currentPlayerIndex)
        assertEquals(4_000L, h.elapsedMs()) // the resumed clock continues rather than counting the time away
        assertEquals(MatchingReject.NONE, h.flipCard(ps[3].second))
        assertTrue(h.resolve())
        assertEquals(2, h.state.value!!.scores[0])
    }

    @Test fun badSnapshotsAreRefused() {
        val g = K.game(count = 12)
        val snap = g.snapshot()!!
        val h = K.game(count = 12, seed = 5)
        val before = h.state.value
        assertFalse(h.restore("nope"))
        assertFalse(h.restore(snap.replace("\"cardCount\":12", "\"cardCount\":13")))
        assertFalse(h.restore(snap.replace("\"boardOver\":false", "\"boardOver\":true")))
        assertFalse(h.restore(snap.replace("\"p1\"", "\"zz\"")))
        // a picture that appears three times
        val faces = Regex("\"faces\":\\[([^\\]]*)\\]").find(snap)!!.groupValues[1].split(",").map { it.trim().toInt() }.toMutableList()
        val other = faces.first { it != faces[0] }
        faces[faces.indexOf(other)] = faces[0]
        assertFalse(h.restore(snap.replace(Regex("\"faces\":\\[[^\\]]*\\]"), "\"faces\":[" + faces.joinToString(",") + "]")))
        assertTrue(before === h.state.value)
        assertTrue(h.restore(snap))
    }

    @Test fun nothingIsSavedOnceTheBoardIsDoneOrTheSessionEnded() {
        val g = K.game(count = 12)
        assertNotNull(g.snapshot())
        K.pairs(g).forEach { (a, b) -> K.attempt(g, a, b) }
        assertNull(g.snapshot())
    }

    // ---- fuzz ----

    @Test fun fuzzRandomHumansKeepEveryInvariantAndSnapshotsRoundTrip() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            val count = MatchingRules.CARD_COUNTS[seed % 4]
            val humans = 1 + seed % 3
            val g = K.game(humans = humans, count = count, seed = seed)
            val faces = g.truthFaces().sorted()
            var steps = 0
            while (!g.state.value!!.boardOver && steps++ < 5_000) {
                val s = g.state.value!!
                when (rnd.nextInt(10)) {
                    0 -> g.resolve()
                    1 -> { if (g.canUndo) g.undo() }
                    2 -> g.requestHint()
                    else -> g.flipCard(rnd.nextInt(-2, count + 2))
                }
                if (g.state.value!!.resolving && rnd.nextBoolean()) g.resolve()
                val t = g.state.value!!
                assertEquals(faces, g.truthFaces().sorted())
                assertTrue(t.status.count { it == MatchingStatus.UP } <= 2)
                assertEquals(t.scores.sum() * 2, t.status.count { it == MatchingStatus.MATCHED })
                assertTrue(t.currentPlayerIndex in 0 until humans)
                assertTrue(s.cardCount == t.cardCount)
                if (steps % 7 == 0) {
                    g.snapshot()?.let { snap ->
                        val h = MatchingGame(Random(1), FakeClock())
                        h.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, K.players(humans), 0, SmFakeTransport()))
                        assertTrue("snapshot refused at step $steps seed $seed", h.restore(snap))
                        assertEquals(t.status, h.state.value!!.status)
                        assertEquals(t.faces, h.state.value!!.faces)
                    }
                }
            }
            var guard = 0
            while (!g.state.value!!.boardOver && guard++ < 500) { // random tapping rarely clears a big board: finish it by hand
                val t = g.state.value!!
                if (t.resolving) { g.resolve(); continue }
                val up = t.status.indices.filter { t.status[it] == MatchingStatus.UP }
                if (up.size == 1) {
                    val pr = K.pairs(g).first { it.first == up[0] || it.second == up[0] }
                    g.flipCard(if (pr.first == up[0]) pr.second else pr.first)
                    continue
                }
                val (a, b) = K.pairs(g).first { t.status[it.first] == MatchingStatus.DOWN }
                g.flipCard(a); g.flipCard(b)
            }
            assertTrue(g.state.value!!.boardOver)
        }
    }
}
