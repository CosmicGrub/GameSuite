package com.gamesuite.games.uno

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Game Hub's UNO options: the extras -> [UnoRules] mapping, hand size / target score /
 * draw-until-playable in the engine, big tables, and resume of a local game.
 */
class UnoHubOptionsTest {

    private fun roster(n: Int, bots: Set<Int> = emptySet(), teams: Boolean = false) =
        (0 until n).map {
            PlayerInfo(
                playerId = "p$it",
                displayName = "Player $it",
                isBot = it in bots,
                teamId = if (teams) it % 2 else -1
            )
        }

    private fun newGame(
        n: Int = 3,
        rules: UnoRules = UnoRules(),
        bots: Set<Int> = emptySet(),
        teams: Boolean = false,
        start: Boolean = true,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): UnoGame {
        val game = UnoGame()
        game.rules = rules
        game.init(
            GameContext(
                activeMode = mode,
                players = roster(n, bots, teams),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        if (start) {
            game.startMatch()
            if (game.state.value!!.awaitingColorChoice) game.chooseColor(UnoColor.RED)
        }
        return game
    }

    // ---- extras -> rules ----

    @Test
    fun `extras map onto the rules`() {
        val r = unoRulesFromExtras(
            mapOf(
                "stackDraw" to "true", "stackDrawFourOnDrawTwo" to "true", "sevenZero" to "true",
                "jumpIn" to "true", "forcePlayDrawnCard" to "true", "drawUntilPlayable" to "true",
                "handSize" to "9", "targetScore" to "200"
            ),
            UnoRules()
        )
        assertEquals(
            UnoRules(
                stackDraw = true, stackDrawFourOnDrawTwo = true, sevenZero = true, jumpIn = true,
                forcePlayDrawnCard = true, drawUntilPlayable = true, handSize = 9, targetScore = 200
            ),
            r
        )
    }

    @Test
    fun `empty extras leave the base rules alone and keep team play`() {
        val base = UnoRules(teamPlay = true, jumpIn = true)
        assertEquals(base, unoRulesFromExtras(emptyMap(), base))
        assertFalse(unoExtrasHaveRules(emptyMap()))
        assertFalse(unoExtrasHaveRules(mapOf("cpus" to "2", "difficulty" to "HARD")))
        assertTrue(unoExtrasHaveRules(mapOf("handSize" to "7")))
        // teamPlay is never switched off or on by extras.
        assertTrue(unoRulesFromExtras(mapOf("stackDraw" to "true"), base).teamPlay)
    }

    @Test
    fun `bad values clamp or are ignored`() {
        val base = UnoRules(handSize = 6, targetScore = 300, sevenZero = true)
        val r = unoRulesFromExtras(
            mapOf("handSize" to "abc", "targetScore" to "x", "sevenZero" to "maybe", "jumpIn" to " TRUE "),
            base
        )
        assertEquals(6, r.handSize)
        assertEquals(300, r.targetScore)
        assertTrue("an unparsable boolean keeps the base value", r.sevenZero)
        assertTrue("booleans are trimmed and case-insensitive", r.jumpIn)

        assertEquals(10, unoRulesFromExtras(mapOf("handSize" to "99"), UnoRules()).handSize)
        assertEquals(3, unoRulesFromExtras(mapOf("handSize" to "-4"), UnoRules()).handSize)
        assertEquals(0, unoRulesFromExtras(mapOf("targetScore" to "-5"), UnoRules()).targetScore)
        assertEquals(0, unoRulesFromExtras(mapOf("targetScore" to "0"), UnoRules()).targetScore)
        assertEquals(500, unoRulesFromExtras(mapOf("targetScore" to "9999"), UnoRules()).targetScore)
        assertEquals(300, unoRulesFromExtras(mapOf("targetScore" to "280"), UnoRules()).targetScore)
    }

    @Test
    fun `plus four on plus two needs stacking`() {
        val r = unoRulesFromExtras(mapOf("stackDraw" to "false", "stackDrawFourOnDrawTwo" to "true"), UnoRules())
        assertFalse(r.stackDraw)
        assertFalse(r.stackDrawFourOnDrawTwo)
    }

    // ---- hand size ----

    @Test
    fun `hand size is clamped so a full table cannot exhaust the deck`() {
        assertEquals(7, UnoRules().dealtHandSize(4))
        assertEquals(10, UnoRules(handSize = 10).dealtHandSize(10))
        // Out-of-range requests are clamped even if constructed directly.
        assertEquals(10, UnoRules(handSize = 50).dealtHandSize(2))
        assertEquals(3, UnoRules(handSize = 0).dealtHandSize(2))
        for (players in 2..10) for (hand in 3..10) {
            val dealt = UnoRules(handSize = hand).dealtHandSize(players)
            assertTrue("$players x $hand dealt $dealt", dealt * players <= UNO_MAX_DEALT_CARDS)
        }
        // A table larger than the supported 10 still never over-deals.
        assertTrue(UnoRules(handSize = 10).dealtHandSize(15) * 15 <= UNO_MAX_DEALT_CARDS)
    }

    @Test
    fun `ten seats with ten cards each deal cleanly and conserve the deck`() {
        repeat(30) {
            val game = newGame(n = 10, rules = UnoRules(handSize = 10), bots = (1..9).toSet())
            val s = game.state.value!!
            assertEquals(10, s.players.size)
            // Seat 0 may have taken two more cards from an opening Draw Two.
            assertTrue(s.players[0].hand.size in 10..12)
            for (i in 1..9) assertEquals(10, s.players[i].hand.size)
            assertEquals(1, s.discardPile.size)
            assertEquals(108, s.players.sumOf { it.hand.size } + s.discardPile.size + s.drawPileSize)
            assertTrue("the draw pile can still be drawn from", s.drawPileSize > 0)
        }
    }

    @Test
    fun `hand size is dealt on every table size`() {
        for (n in listOf(2, 5, 10)) {
            val game = newGame(n = n, rules = UnoRules(handSize = 3))
            val s = game.state.value!!
            for (i in 1 until n) assertEquals(3, s.players[i].hand.size)
        }
        val seven = newGame(n = 4)
        for (i in 1 until 4) assertEquals(7, seven.state.value!!.players[i].hand.size)
    }

    @Test
    fun `teams of ten deal with team ids`() {
        val game = newGame(n = 10, rules = UnoRules(teamPlay = true, handSize = 7), teams = true)
        val s = game.state.value!!
        assertEquals((0 until 10).map { it % 2 }, s.players.map { it.teamId })
    }

    // ---- target score ----

    private fun injectWinningMoment(game: UnoGame): UnoCard {
        val s = game.state.value!!
        val winning = UnoCard(UnoColor.RED, UnoRank.THREE, instanceId = 9001)
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p ->
                when (i) {
                    0 -> p.copy(hand = listOf(winning))
                    else -> p.copy(hand = listOf(UnoCard(UnoColor.BLUE, UnoRank.SEVEN, instanceId = 9100 + i)))
                }
            },
            currentPlayerIndex = 0,
            currentColor = UnoColor.RED,
            discardPile = listOf(UnoCard(UnoColor.RED, UnoRank.FIVE, instanceId = 9000)),
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false,
            awaitingDrawDecision = false,
            roundOver = false,
            matchOver = false,
            cumulativeScores = emptyMap(),
            direction = 1
        )
        return winning
    }

    @Test
    fun `target score zero makes the first round the whole match`() {
        val game = newGame(rules = UnoRules(targetScore = 0))
        var result: GameResult? = null
        game.setOnMatchEnd { result = it }
        assertEquals(0, game.targetScore)
        val winning = injectWinningMoment(game)
        game.playCard(0, winning)
        val after = game.state.value!!
        assertTrue(after.roundOver)
        assertTrue("one round decides it", after.matchOver)
        val scores = result!!.scores
        assertTrue(scores.first { it.playerId == "p0" }.isWinner)
        assertFalse(scores.first { it.playerId == "p1" }.isWinner)
    }

    @Test
    fun `a normal target keeps the match going after a small round`() {
        val game = newGame(rules = UnoRules(targetScore = 200))
        assertEquals(200, game.targetScore)
        val winning = injectWinningMoment(game)
        game.playCard(0, winning)
        val after = game.state.value!!
        assertTrue(after.roundOver)
        assertFalse("14 points is nowhere near 200", after.matchOver)
        assertEquals(14, after.cumulativeScores["p0"])
    }

    @Test
    fun `reaching the target ends the match`() {
        val game = newGame(rules = UnoRules(targetScore = 200))
        val winning = injectWinningMoment(game)
        val s = game.state.value!!
        game.state.value = s.copy(cumulativeScores = mapOf("p0" to 190))
        game.playCard(0, winning)
        assertTrue(game.state.value!!.matchOver)
    }

    // ---- draw until playable ----

    private fun isPlayableOnRedFive(c: UnoCard) = c.isWild || c.color == UnoColor.RED || c.rank == UnoRank.FIVE

    private fun injectNoPlayHand(game: UnoGame) {
        val s = game.state.value!!
        game.state.value = s.copy(
            players = s.players.mapIndexed { i, p ->
                if (i == 0) p.copy(hand = listOf(
                    UnoCard(UnoColor.BLUE, UnoRank.ONE, 9201),
                    UnoCard(UnoColor.BLUE, UnoRank.TWO, 9202)
                )) else p
            },
            currentPlayerIndex = 0,
            currentColor = UnoColor.RED,
            discardPile = listOf(UnoCard(UnoColor.RED, UnoRank.FIVE, instanceId = 9000)),
            pendingDraw = 0,
            awaitingColorChoice = false,
            awaitingChallenge = false,
            awaitingDrawDecision = false,
            roundOver = false,
            matchOver = false
        )
    }

    @Test
    fun `draw until playable keeps drawing until a card fits`() {
        var sawMultiDraw = false
        repeat(80) {
            val game = newGame(rules = UnoRules(drawUntilPlayable = true))
            injectNoPlayHand(game)
            game.drawCard(0)
            val after = game.state.value!!
            val drawn = after.players[0].hand.drop(2)
            assertTrue("at least one card drawn", drawn.isNotEmpty())
            assertTrue("every card but the last one was unplayable", drawn.dropLast(1).none(::isPlayableOnRedFive))
            assertTrue("the last card is playable", isPlayableOnRedFive(drawn.last()))
            assertEquals("the turn stays so the card can be played", 0, after.currentPlayerIndex)
            assertTrue(after.awaitingDrawDecision)
            if (drawn.size > 1) sawMultiDraw = true
        }
        assertTrue("at least one of 80 deals needed more than one draw", sawMultiDraw)
    }

    @Test
    fun `without the house rule one draw ends the lookup`() {
        repeat(40) {
            val game = newGame()
            injectNoPlayHand(game)
            game.drawCard(0)
            val after = game.state.value!!
            assertEquals(3, after.players[0].hand.size)
        }
    }

    @Test
    fun `draw until playable never loses or duplicates cards`() {
        repeat(40) {
            val game = newGame(n = 4, rules = UnoRules(drawUntilPlayable = true, handSize = 10))
            injectNoPlayHand(game)
            val before = game.state.value!!
            game.drawCard(0)
            val after = game.state.value!!
            // 2 injected cards were not part of the real deck's accounting, so compare the pile drop.
            val drawnCount = after.players[0].hand.size - 2
            assertEquals(before.drawPileSize - drawnCount, after.drawPileSize)
        }
    }

    // ---- resume ----

    private val resumeRules = UnoRules(sevenZero = true, handSize = 5, targetScore = 300, drawUntilPlayable = true)

    @Test
    fun `there is nothing to snapshot before the deal, after the match, or in a networked mode`() {
        val unstarted = newGame(start = false)
        assertNull(unstarted.snapshot())

        val game = newGame()
        assertNotNull(game.snapshot())
        game.state.value = game.state.value!!.copy(matchOver = true)
        assertNull(game.snapshot())

        val online = newGame(start = false, mode = PlayMode.ONLINE, n = 2)
        online.state.value = newGame(n = 2).state.value
        assertNull(online.snapshot())
    }

    @Test
    fun `a restored game is identical and plays on identically`() {
        val a = newGame(n = 4, rules = resumeRules, bots = setOf(1, 2, 3))
        a.difficulty = CpuDifficulty.HARD
        val snapshot = a.snapshot()!!

        val b = newGame(n = 4, bots = setOf(1, 2, 3), start = false)
        assertTrue(b.restore(snapshot))
        assertEquals(a.state.value, b.state.value)
        assertEquals(resumeRules, b.rules)
        assertEquals(CpuDifficulty.HARD, b.difficulty)
        assertEquals(300, b.targetScore)

        // The draw pile order survived: the same draw gives the same cards.
        val cur = a.state.value!!.currentPlayerIndex
        a.drawCard(cur)
        b.drawCard(cur)
        assertEquals(a.state.value, b.state.value)
    }

    @Test
    fun `a snapshot for a different roster or garbage is rejected and leaves the game untouched`() {
        val a = newGame(n = 3, rules = resumeRules)
        val snapshot = a.snapshot()!!

        val fewer = newGame(n = 2, start = false)
        assertFalse(fewer.restore(snapshot))
        assertNull(fewer.state.value)

        val differentBots = newGame(n = 3, bots = setOf(2), start = false)
        assertFalse(differentBots.restore(snapshot))
        assertNull(differentBots.state.value)
        assertEquals(UnoRules(), differentBots.rules)

        val plain = newGame(n = 3, start = false)
        assertFalse(plain.restore("not json"))
        assertFalse(plain.restore("{}"))
        assertNull(plain.state.value)
    }

    @Test
    fun `a snapshot with a missing card is rejected`() {
        val a = newGame(n = 3)
        val snapshot = a.snapshot()!!
        val s = a.state.value!!
        // Tamper: drop one card from a hand by editing the live state and re-snapshotting.
        a.state.value = s.copy(players = s.players.mapIndexed { i, p -> if (i == 1) p.copy(hand = p.hand.drop(1)) else p })
        val tampered = a.snapshot()!!
        val b = newGame(n = 3, start = false)
        assertFalse(b.restore(tampered))
        assertTrue(b.restore(snapshot))
    }
}
