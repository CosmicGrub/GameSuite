package com.gamesuite.games.speed

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.cards.Rank
import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Rules, scripted boards, the computer's reaction time, turn-based assist, resume and fuzz for Speed. */
object SpeedTestKit {
    /** Card id from a rank index (0 = two .. 12 = ace) and a suit index (0 clubs, 1 diamonds, 2 hearts, 3 spades). */
    fun id(rank: Int, suit: Int = 0) = suit * 13 + rank

    fun players(bot: Boolean = false) = listOf(PlayerInfo("p1", "Ann"), PlayerInfo(if (bot) "bot1" else "p2", "Ben", isBot = bot))

    fun newGame(
        clock: FakeClock = FakeClock(),
        seed: Int = 1,
        bot: Boolean = false,
        mode: PlayMode = if (bot) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
        assist: Boolean = false,
        difficulty: CpuDifficulty = CpuDifficulty.MEDIUM,
        results: MutableList<GameResult>? = null
    ): SpeedGame {
        val g = SpeedGame(Random(seed), clock)
        g.difficulty = difficulty
        g.assist = assist
        g.init(GameContext(mode, players(bot), 0, SmFakeTransport()))
        if (results != null) g.setOnMatchEnd { results += it }
        return g
    }

    /**
     * A hand-built board in the engine's own snapshot format. [h0]/[h1] are the five hand cards, [top0]/[top1] the centre
     * piles; every other card goes into the replacement piles (split in half), or, when [reserves] is given, those are the
     * replacement piles and the leftovers sit under the top of pile 0.
     */
    fun crafted(
        h0: List<Int>, h1: List<Int>, top0: Int, top1: Int,
        assist: Boolean = false, assistTurn: Int = 0, bot: Boolean = false, difficulty: String = "MEDIUM",
        reserves: Pair<List<Int>, List<Int>>? = null
    ): String {
        val used = (h0 + h1 + listOf(top0, top1) + (reserves?.first ?: emptyList()) + (reserves?.second ?: emptyList())).toSet()
        val rest = (0 until 52).filter { it !in used }
        val r0: List<Int>
        val r1: List<Int>
        val under: List<Int>
        if (reserves == null) {
            r0 = rest.take(rest.size / 2); r1 = rest.drop(rest.size / 2); under = emptyList()
        } else {
            r0 = reserves.first; r1 = reserves.second; under = rest
        }
        fun l(x: List<Int>) = x.joinToString(",", "[", "]")
        val p2 = if (bot) "{\"playerId\":\"bot1\",\"displayName\":\"Ben\",\"isBot\":true}" else "{\"playerId\":\"p2\",\"displayName\":\"Ben\",\"isBot\":false}"
        return "{\"players\":[{\"playerId\":\"p1\",\"displayName\":\"Ann\",\"isBot\":false},$p2]," +
            "\"hands\":[${l(h0)},${l(h1)}],\"draws\":[[],[]],\"reserves\":[${l(r0)},${l(r1)}]," +
            "\"piles\":[${l(under + top0)},${l(listOf(top1))}],\"assist\":$assist,\"assistTurn\":$assistTurn,\"deadlock\":0," +
            "\"boardOver\":false,\"winnerPlayerId\":null,\"sessionWins\":{\"p1\":0,\"${if (bot) "bot1" else "p2"}\":0},\"sessionDraws\":0," +
            "\"nextOpener\":0,\"difficulty\":\"$difficulty\"}"
    }

    /** Cards that fit neither the 7 nor the king: twos, threes and fours. */
    val deadA = listOf(id(0, 0), id(0, 1), id(1, 0), id(1, 1), id(2, 0))
    val deadB = listOf(id(0, 2), id(1, 2), id(2, 1), id(2, 2), id(0, 3))

    /** A started, live board from [crafted]: past the countdown. */
    fun live(g: SpeedGame, clock: FakeClock, snapshot: String) {
        assertTrue("snapshot rejected", g.restore(snapshot))
        if (g.state.value!!.phase == SpeedPhase.COUNTDOWN) {
            clock.advance(SpeedRules.COUNTDOWN_MS)
            g.tick()
        }
        assertEquals(SpeedPhase.PLAYING, g.state.value!!.phase)
    }
}

class SpeedGameTest {
    private val K = SpeedTestKit

    // ---- rules ----

    @Test fun ranksFormARing() {
        fun r(i: Int) = Rank.entries[i]
        assertTrue(SpeedRules.adjacent(Rank.FIVE, Rank.SIX))
        assertTrue(SpeedRules.adjacent(Rank.SIX, Rank.FIVE))
        assertFalse(SpeedRules.adjacent(Rank.FIVE, Rank.SEVEN))
        assertFalse(SpeedRules.adjacent(Rank.FIVE, Rank.FIVE))
        assertTrue(SpeedRules.adjacent(Rank.ACE, Rank.KING))
        assertTrue(SpeedRules.adjacent(Rank.ACE, Rank.TWO))
        assertTrue(SpeedRules.adjacent(Rank.TWO, Rank.ACE))
        assertFalse(SpeedRules.adjacent(Rank.KING, Rank.TWO))
        for (a in 0 until 13) assertEquals(2, (0 until 13).count { SpeedRules.adjacent(r(a), r(it)) })
    }

    @Test fun anEmptyPileAcceptsNothing() {
        assertFalse(SpeedRules.canPlay(com.gamesuite.games.cards.Deck.fresh52()[0], null))
    }

    @Test fun dealGivesEachSeat26DistinctCards() {
        val deals = SpeedRules.deal(Random(5))
        assertEquals(2, deals.size)
        val ids = deals.flatMap { it.hand + it.draw + it.reserve }.map { it.id }
        assertEquals(52, ids.size)
        assertEquals((0 until 52).toList(), ids.sorted())
        deals.forEach {
            assertEquals(5, it.hand.size); assertEquals(15, it.draw.size); assertEquals(6, it.reserve.size)
        }
    }

    @Test fun cpuProfilesGetFasterAndSmarter() {
        val e = SpeedRules.profile(CpuDifficulty.EASY)
        val m = SpeedRules.profile(CpuDifficulty.MEDIUM)
        val h = SpeedRules.profile(CpuDifficulty.HARD)
        assertEquals(1800L, e.reactionMs); assertEquals(1000L, m.reactionMs); assertEquals(450L, h.reactionMs)
        assertTrue(e.missChance > m.missChance && m.missChance > h.missChance)
        assertTrue(e.smartness < m.smartness && m.smartness < h.smartness)
    }

    // ---- a fresh deal ----

    @Test fun aFreshBoardCountsDownBeforeAnyCardIsLive() {
        val clock = FakeClock()
        val g = K.newGame(clock)
        g.startMatch()
        var s = g.state.value!!
        assertEquals(SpeedPhase.COUNTDOWN, s.phase)
        assertEquals(listOf(1, 1), s.pileSizes)
        assertEquals(listOf(15, 15), s.drawCounts)
        assertEquals(listOf(5, 5), s.reserveCounts)
        s.hands.forEach { h -> assertEquals(5, h.count { it != null }) }
        val any = s.hands[0].first()!!
        assertEquals(SpeedReject.NOT_STARTED, g.play(0, any.id, 0))
        clock.advance(SpeedRules.COUNTDOWN_MS - 1)
        g.tick()
        assertEquals(SpeedPhase.COUNTDOWN, g.state.value!!.phase)
        clock.advance(1)
        g.tick()
        s = g.state.value!!
        assertEquals(SpeedPhase.PLAYING, s.phase)
        assertEquals((0 until 52).toList(), g.allCardIds())
    }

    // ---- scripted boards ----

    @Test fun aLegalPlayMovesTheCardAndTopsUpFromTheDrawPile() {
        var tested = false
        for (seed in 1..60) {
            val clock = FakeClock()
            val g = K.newGame(clock, seed = seed)
            g.startMatch()
            clock.advance(SpeedRules.COUNTDOWN_MS); g.tick()
            val play = SpeedGame.legalPlaysIn(g.state.value!!, 0).firstOrNull() ?: continue
            val before = g.state.value!!
            assertEquals(SpeedReject.NONE, g.play(0, play.cardId, play.pile))
            val after = g.state.value!!
            assertEquals(before.pileSizes[play.pile] + 1, after.pileSizes[play.pile])
            assertEquals(play.cardId, after.pileTops[play.pile]!!.id)
            assertEquals(5, after.hands[0].count { it != null })
            assertEquals(before.drawCounts[0] - 1, after.drawCounts[0])
            assertEquals(before.playSerial + 1, after.playSerial)
            assertEquals((0 until 52).toList(), g.allCardIds())
            tested = true
            break
        }
        assertTrue("no seed had a playable opening", tested)
    }

    @Test fun onlyAnAdjacentRankFitsAndTheAceWraps() {
        val clock = FakeClock(); val g = K.newGame(clock)
        // pile 0 shows a king, pile 1 a seven. Ann holds an ace, an eight and a queen; Ben holds nothing useful.
        val hand = listOf(K.id(12, 0), K.id(6, 0), K.id(10, 0), K.id(0, 0), K.id(1, 0))
        K.live(g, clock, K.crafted(hand, K.deadB, top0 = K.id(11, 1), top1 = K.id(5, 1)))
        assertEquals(SpeedReject.ILLEGAL, g.play(0, K.id(0, 0), 0))      // a two on a king
        assertEquals(SpeedReject.ILLEGAL, g.play(0, K.id(12, 0), 1))     // an ace on a seven
        assertEquals(SpeedReject.NO_SUCH_CARD, g.play(0, K.id(3, 3), 0)) // not in hand
        assertEquals(SpeedReject.NONE, g.play(0, K.id(12, 0), 0))        // ace on king: wraps
        assertEquals(SpeedReject.NONE, g.play(0, K.id(0, 0), 0))         // two on ace: wraps the other way
        assertEquals(SpeedReject.NONE, g.play(0, K.id(6, 0), 1))         // eight on seven
        assertEquals(SpeedReject.ILLEGAL, g.play(0, K.id(10, 0), 1))     // queen on an eight
        assertEquals(K.id(6, 0), g.state.value!!.pileTops[1]!!.id)
        assertEquals((0 until 52).toList(), g.allCardIds())
    }

    @Test fun playingTheLastCardOfHandAndDrawWins() {
        val clock = FakeClock(); val results = mutableListOf<GameResult>(); val g = K.newGame(clock, results = results)
        val chain = (6..10).map { K.id(it, 0) } // 8 9 10 J Q on a 7
        K.live(g, clock, K.crafted(chain, K.deadB, top0 = K.id(5, 1), top1 = K.id(11, 1)))
        chain.forEachIndexed { i, c ->
            assertEquals(SpeedReject.NONE, g.play(0, c, 0))
            assertEquals(i == chain.lastIndex, g.state.value!!.boardOver)
        }
        val s = g.state.value!!
        assertEquals(SpeedPhase.OVER, s.phase)
        assertEquals("p1", s.winnerPlayerId)
        assertEquals(1, g.sessionWins.value["p1"])
        assertEquals(SpeedReject.NOT_PLAYING, g.play(1, K.deadB[0], 0))
        g.leaveSession()
        assertTrue(g.matchOver.value)
        assertEquals(true, results.single().scores.first { it.playerId == "p1" }.isWinner)
    }

    @Test fun aRaceLostByAPhotoFinishIsNotAMistake() {
        val clock = FakeClock(); val g = K.newGame(clock)
        val ann = listOf(K.id(6, 0)) + K.deadA.take(4) // an eight
        val ben = listOf(K.id(4, 1)) + K.deadB.take(4) // a six
        K.live(g, clock, K.crafted(ann, ben, top0 = K.id(5, 1), top1 = K.id(11, 1))) // a seven on pile 0
        assertEquals(SpeedReject.NONE, g.play(0, K.id(6, 0), 0))
        clock.advance(100)
        assertEquals(SpeedReject.LOST_RACE, g.play(1, K.id(4, 1), 0)) // fit the seven, arrived 100 ms late
        assertEquals(SpeedReject.LOST_RACE, g.lastReject.value!!.reason)
        assertTrue(g.state.value!!.hands[1].any { it?.id == K.id(4, 1) }) // the card stays in hand
        clock.advance(300)
        assertEquals(SpeedReject.ILLEGAL, g.play(1, K.id(4, 1), 0)) // too long ago: just a wrong card
        assertEquals((0 until 52).toList(), g.allCardIds())
    }

    @Test fun bothStuckFlipsTheCentreAfterAWhileOrWhenBothSayStuck() {
        val clock = FakeClock(); val g = K.newGame(clock)
        K.live(g, clock, K.crafted(K.deadA, K.deadB, top0 = K.id(5, 1), top1 = K.id(11, 1)))
        val before = g.state.value!!
        assertEquals(SpeedReject.NONE, g.requestFlip(0))
        assertEquals(listOf(true, false), g.state.value!!.flipReady)
        assertEquals(before.flipSerial, g.state.value!!.flipSerial)
        assertEquals(SpeedReject.NONE, g.requestFlip(1))
        var s = g.state.value!!
        assertEquals(before.flipSerial + 1, s.flipSerial)
        assertEquals(SpeedEvent.FLIP, s.event)
        assertEquals(listOf(2, 2), s.pileSizes)
        assertEquals(listOf(before.reserveCounts[0] - 1, before.reserveCounts[1] - 1), s.reserveCounts)
        assertEquals(listOf(false, false), s.flipReady)
        // And with nobody pressing: the automatic flip.
        val f = s.flipSerial
        if (SpeedGame.isStuck(s, 0) && SpeedGame.isStuck(s, 1)) {
            g.tick(); clock.advance(SpeedRules.AUTO_FLIP_MS); g.tick()
            assertEquals(f + 1, g.state.value!!.flipSerial)
        }
        assertEquals((0 until 52).toList(), g.allCardIds())
    }

    @Test fun youCannotSayStuckWhileYouStillHaveAPlay() {
        val clock = FakeClock(); val g = K.newGame(clock)
        K.live(g, clock, K.crafted(listOf(K.id(6, 0)) + K.deadA.take(4), K.deadB, top0 = K.id(5, 1), top1 = K.id(11, 1)))
        assertEquals(SpeedReject.CAN_STILL_PLAY, g.requestFlip(0))
        assertEquals(listOf(false, false), g.state.value!!.flipReady)
    }

    @Test fun anExhaustedReplacementPileReshufflesTheCentre() {
        val clock = FakeClock(); val g = K.newGame(clock)
        val spare = (0 until 52).filter { it !in (K.deadA + K.deadB + listOf(K.id(5, 1), K.id(11, 1))) }
        K.live(g, clock, K.crafted(K.deadA, K.deadB, top0 = K.id(5, 1), top1 = K.id(11, 1), reserves = emptyList<Int>() to listOf(spare.first())))
        g.requestFlip(0); g.requestFlip(1)
        val s = g.state.value!!
        assertEquals(SpeedEvent.RESHUFFLE, s.event)
        assertEquals(listOf(1, 1), s.pileSizes)
        assertEquals(52 - 10 - 2, s.reserveCounts.sum())
        assertEquals((0 until 52).toList(), g.allCardIds())
    }

    // ---- turn-based assist ----

    @Test fun assistTakesTurnsAndPasses() {
        val clock = FakeClock(); val g = K.newGame(clock, assist = true)
        val ann = listOf(K.id(6, 0), K.id(7, 0)) + K.deadA.take(3)
        val ben = listOf(K.id(4, 1)) + K.deadB.take(4)
        assertTrue(g.restore(K.crafted(ann, ben, top0 = K.id(5, 1), top1 = K.id(11, 1), assist = true, assistTurn = 0)))
        assertEquals(SpeedPhase.PLAYING, g.state.value!!.phase) // no countdown in assist
        assertEquals(SpeedReject.NOT_YOUR_TURN, g.play(1, K.id(4, 1), 0))
        assertEquals(SpeedReject.NONE, g.play(0, K.id(6, 0), 0))
        assertEquals(1, g.state.value!!.assistTurn)
        assertEquals(SpeedReject.NOT_YOUR_TURN, g.play(0, K.id(7, 0), 0))
        assertTrue(SpeedGame.legalPlaysIn(g.state.value!!, 0).isEmpty()) // not her turn: nothing highlighted
        assertEquals(SpeedReject.NOT_YOUR_TURN, g.requestFlip(0))
        // Ben (a six, pile 0 now shows an eight) is stuck and passes; the turn returns to Ann.
        assertEquals(SpeedReject.NONE, g.requestFlip(1))
        assertEquals(0, g.state.value!!.assistTurn)
        assertEquals(SpeedEvent.PASS, g.state.value!!.event)
    }

    @Test fun assistFlipsWhenBothAreStuck() {
        val clock = FakeClock(); val g = K.newGame(clock, assist = true)
        assertTrue(g.restore(K.crafted(K.deadA, K.deadB, top0 = K.id(5, 1), top1 = K.id(11, 1), assist = true, assistTurn = 0)))
        val f = g.state.value!!.flipSerial
        assertEquals(SpeedReject.NONE, g.requestFlip(0)) // Ann passes and Ben has nothing either: the centre flips at once
        assertEquals(f + 1, g.state.value!!.flipSerial)
    }

    @Test fun assistCpuTakesItsTimeButNeverLessThanHalfASecond() {
        val clock = FakeClock(); val g = K.newGame(clock, bot = true, assist = true, difficulty = CpuDifficulty.HARD)
        assertTrue(g.restore(K.crafted(K.deadA, listOf(K.id(6, 0)) + K.deadB.take(4), top0 = K.id(5, 1), top1 = K.id(11, 1), assist = true, assistTurn = 1, bot = true, difficulty = "HARD")))
        g.tick()
        val s0 = g.state.value!!.playSerial
        clock.advance(399); g.tick()
        assertEquals(s0, g.state.value!!.playSerial)
        repeat(20) { clock.advance(50); g.tick() }
        assertEquals(s0 + 1, g.state.value!!.playSerial)
        assertEquals(0, g.state.value!!.assistTurn)
    }

    // ---- the computer's reaction time ----

    private fun reactionOf(level: CpuDifficulty, seed: Int): Long {
        val clock = FakeClock(); val g = K.newGame(clock, seed = seed, bot = true, difficulty = level)
        val ben = listOf(K.id(6, 0)) + K.deadB.take(4)
        assertTrue(g.restore(K.crafted(K.deadA, ben, top0 = K.id(5, 1), top1 = K.id(11, 1), bot = true, difficulty = level.name)))
        clock.advance(SpeedRules.COUNTDOWN_MS); g.tick()
        val start = clock.now
        val serial = g.state.value!!.playSerial
        while (clock.now - start < 6_000L) {
            clock.advance(10); g.tick()
            if (g.state.value!!.playSerial != serial) return clock.now - start
        }
        error("the computer never played")
    }

    @Test fun hardReactsInAboutHalfASecond() {
        for (seed in 1..25) { val t = reactionOf(CpuDifficulty.HARD, seed); assertTrue("hard took $t", t in 360L..560L) }
    }

    @Test fun mediumReactsInAboutASecond() {
        for (seed in 1..25) { val t = reactionOf(CpuDifficulty.MEDIUM, seed); assertTrue("medium took $t", t in 800L..1_850L) }
    }

    @Test fun easyReactsInAboutTwoSeconds() {
        for (seed in 1..25) { val t = reactionOf(CpuDifficulty.EASY, seed); assertTrue("easy took $t", t in 1_440L..3_300L) }
    }

    @Test fun levelsAreOrderedByAverageReaction() {
        fun avg(l: CpuDifficulty) = (1..25).map { reactionOf(l, it) }.average()
        assertTrue(avg(CpuDifficulty.HARD) < avg(CpuDifficulty.MEDIUM))
        assertTrue(avg(CpuDifficulty.MEDIUM) < avg(CpuDifficulty.EASY))
    }

    @Test fun theComputerNeverPlaysBeforeTheGo() {
        val clock = FakeClock(); val g = K.newGame(clock, bot = true, difficulty = CpuDifficulty.HARD)
        assertTrue(g.restore(K.crafted(K.deadA, listOf(K.id(6, 0)) + K.deadB.take(4), top0 = K.id(5, 1), top1 = K.id(11, 1), bot = true, difficulty = "HARD")))
        repeat(145) { clock.advance(20); g.tick() } // 2.9 s of the 3 s countdown
        assertEquals(0, g.state.value!!.playSerial)
        assertEquals(SpeedPhase.COUNTDOWN, g.state.value!!.phase)
    }

    @Test fun aComputerWithNothingToPlayFlagsAFlipAfterItsReactionTime() {
        val clock = FakeClock(); val g = K.newGame(clock, bot = true, difficulty = CpuDifficulty.HARD)
        assertTrue(g.restore(K.crafted(K.deadA, K.deadB, top0 = K.id(5, 1), top1 = K.id(11, 1), bot = true, difficulty = "HARD")))
        clock.advance(SpeedRules.COUNTDOWN_MS); g.tick()
        clock.advance(300); g.tick()
        assertEquals(false, g.state.value!!.flipReady[1])
        repeat(60) { clock.advance(10); g.tick() }
        assertEquals(true, g.state.value!!.flipReady[1])
    }

    // ---- hint ----

    @Test fun hintHighlightsAPlayableCardAndIsEmptyWhenStuck() {
        val clock = FakeClock(); val g = K.newGame(clock)
        K.live(g, clock, K.crafted(listOf(K.id(6, 0)) + K.deadA.take(4), K.deadB, top0 = K.id(5, 1), top1 = K.id(11, 1)))
        val h = g.hint(0)
        assertEquals(SpeedPlay(K.id(6, 0), 0), h)
        assertEquals(h, g.hintPlay.value)
        g.play(0, K.id(6, 0), 0)
        assertNull(g.hintPlay.value) // the next change to the table clears it
        assertNull(g.hint(0)) // nothing to play now
    }

    // ---- resume ----

    @Test fun snapshotRoundTripsAndResumesIntoACountdown() {
        val clock = FakeClock(); val g = K.newGame(clock, seed = 9)
        g.startMatch(); clock.advance(3_000); g.tick()
        repeat(30) {
            val p = SpeedGame.legalPlaysIn(g.state.value!!, 0).firstOrNull()
            if (p != null) g.play(0, p.cardId, p.pile) else { g.requestFlip(0); g.requestFlip(1) }
        }
        val snap = g.snapshot()!!
        val c2 = FakeClock(); val h = K.newGame(c2, seed = 2)
        assertTrue(h.restore(snap))
        assertEquals(g.allCardIds(), h.allCardIds())
        val a = g.state.value!!; val b = h.state.value!!
        assertEquals(a.hands, b.hands); assertEquals(a.pileTops, b.pileTops); assertEquals(a.drawCounts, b.drawCounts)
        assertEquals(a.reserveCounts, b.reserveCounts); assertEquals(a.pileSizes, b.pileSizes)
        assertEquals(SpeedPhase.COUNTDOWN, b.phase) // a resumed board is not live until the 3-2-1
    }

    @Test fun badSnapshotsAreRefusedAndLeaveTheGameUntouched() {
        val clock = FakeClock(); val g = K.newGame(clock)
        g.startMatch()
        val before = g.state.value
        val good = K.crafted(K.deadA, K.deadB, top0 = K.id(5, 1), top1 = K.id(11, 1))
        assertFalse(g.restore("not json"))
        assertFalse(g.restore(good.replace("\"p1\"", "\"zz\"")))
        assertFalse(g.restore(good.replace("\"assistTurn\":0", "\"assistTurn\":5")))
        assertFalse(g.restore(good.replaceFirst(Regex("\"hands\":\\[\\[\\d+"), "\"hands\":[[" + K.deadB[0]))) // a duplicated card
        assertFalse(g.restore(good.replace("\"boardOver\":false", "\"boardOver\":true")))
        assertTrue(before === g.state.value)
        assertTrue(g.restore(good))
    }

    @Test fun noSnapshotWhenNothingIsWorthSaving() {
        val g = K.newGame()
        assertNull(g.snapshot()) // not started
        g.startMatch(); assertNotNull(g.snapshot())
        g.leaveSession(); assertNull(g.snapshot())
    }

    // ---- fuzz ----

    private fun checkConserved(g: SpeedGame) = assertEquals((0 until 52).toList(), g.allCardIds())

    @Test fun fuzzRandomHumanAgainstTheComputerAlwaysConservesCardsAndTerminates() {
        for (level in CpuDifficulty.entries) for (seed in 1..12) {
            val rnd = Random(seed * 31 + level.ordinal)
            val clock = FakeClock(); val g = K.newGame(clock, seed = seed, bot = true, difficulty = level)
            g.startMatch()
            var steps = 0
            while (!g.state.value!!.boardOver && steps++ < 60_000) {
                clock.advance(40); g.tick()
                val s = g.state.value!!
                if (rnd.nextInt(4) == 0 && s.phase == SpeedPhase.PLAYING) {
                    val plays = SpeedGame.legalPlaysIn(s, 0)
                    when {
                        plays.isNotEmpty() -> plays[rnd.nextInt(plays.size)].let { g.play(0, it.cardId, it.pile) }
                        else -> g.requestFlip(0)
                    }
                    if (rnd.nextInt(10) == 0) g.play(0, rnd.nextInt(60) - 3, rnd.nextInt(4) - 1) // junk must be refused, never throw
                }
                if (steps % 17 == 0) {
                    checkConserved(g)
                    val snap = g.snapshot()
                    if (snap != null) {
                        val h = K.newGame(FakeClock(), bot = true); assertTrue(h.restore(snap)); assertEquals(g.allCardIds(), h.allCardIds())
                    }
                }
            }
            assertTrue("level $level seed $seed did not finish", g.state.value!!.boardOver)
            checkConserved(g)
        }
    }

    @Test fun twoComputersAlwaysFinishABoard() {
        for (seed in 1..10) {
            val clock = FakeClock()
            val g = SpeedGame(Random(seed), clock)
            g.difficulty = CpuDifficulty.HARD
            g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("b1", "A", isBot = true), PlayerInfo("b2", "B", isBot = true)), 0, SmFakeTransport()))
            g.startMatch()
            var steps = 0
            while (!g.state.value!!.boardOver && steps++ < 40_000) { clock.advance(20); g.tick(); if (steps % 50 == 0) checkConserved(g) }
            assertTrue("seed $seed did not finish", g.state.value!!.boardOver)
            checkConserved(g)
        }
    }

    @Test fun twoComputersInAssistModeAlsoFinish() {
        for (seed in 1..6) {
            val clock = FakeClock()
            val g = SpeedGame(Random(seed), clock)
            g.assist = true; g.difficulty = CpuDifficulty.MEDIUM
            g.init(GameContext(PlayMode.SINGLE_PLAYER_VS_BOT, listOf(PlayerInfo("b1", "A", isBot = true), PlayerInfo("b2", "B", isBot = true)), 0, SmFakeTransport()))
            g.startMatch()
            var steps = 0
            while (!g.state.value!!.boardOver && steps++ < 60_000) { clock.advance(25); g.tick() }
            assertTrue("seed $seed did not finish", g.state.value!!.boardOver)
            checkConserved(g)
        }
    }

    @Test fun playAgainDealsANewBoardInTheSameSession() {
        val clock = FakeClock(); val g = K.newGame(clock)
        K.live(g, clock, K.crafted((6..10).map { K.id(it, 0) }, K.deadB, top0 = K.id(5, 1), top1 = K.id(11, 1)))
        (6..10).forEach { g.play(0, K.id(it, 0), 0) }
        assertTrue(g.state.value!!.boardOver)
        val deal = g.state.value!!.dealSerial
        g.playAgain()
        val s = g.state.value!!
        assertFalse(s.boardOver)
        assertTrue(s.dealSerial > deal)
        assertEquals(1, g.sessionWins.value["p1"])
        checkConserved(g)
    }
}
