package com.gamesuite.games.uno

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import kotlin.random.Random

/**
 * Coverage for every place [UnoGame] draws cards AFTER an `ensureDrawPile()` reshuffle may have
 * collapsed the discard pile, plus the rule paths around them.
 *
 * Background: the draw pile is a private list; `state.value.drawPileSize` mirrors it on every
 * commit. When a draw needs more cards than the pile holds, `ensureDrawPile()` writes
 * `state.value` directly (discard collapses to its top card, the rest moves into the draw pile).
 * Commit 3c4c7c4 fixed callers that then committed a copy of their stale pre-draw snapshot,
 * reverting that collapse (cards double-counted in discard plus hands). The five fix sites are
 * resolveChallenge, catchUnoFailure, drawCard's pendingDraw branch, drawCard's single-draw branch
 * and playCard's final commit (non-stacked Draw Two). Nothing exercised them before this file.
 *
 * Deterministic staging: deck shuffling is unseeded, so nothing here depends on which cards the
 * engine deals. [stage] first DRAINS the private draw pile through the public API down to an
 * exact size, then overwrites every hand and the discard pile with hand-built cards (instanceIds
 * 9000+; the un-drainable remainder of the real pile keeps ids below 108, so drawn cards are
 * recognisable). The discard pile is padded so discard + drawPileSize + hands is exactly 108.
 * Engine-drawn cards are then only ever asserted through invariants and their id range.
 *
 * COUPLING (read before "fixing" a class-wide failure): all active tests here funnel through
 * [drainDrawPile] + [stage]. [drainDrawPile] FABRICATES `pendingDraw = n` on a freshly dealt game by
 * assigning `game.state.value` and then calls the public `drawCard(0)`, relying on the engine
 * honouring a pendingDraw that no Draw Two / Wild Draw Four backs. [stage] then overwrites
 * `state.value` wholesale and hard-codes the 108-card total and the 9000 / 9500 id ranges. If the
 * engine is hardened (rejects an unbacked pendingDraw, makes `state.value` non-assignable, changes
 * the deck size) EVERY test here fails at once with a staging error, not a behavioural one. The
 * first test (`00 staging smoke test ...`, class runs in NAME_ASCENDING order) exists to fail
 * FIRST with an explicit "STAGING BROKEN" message so that case is recognisable at a glance. If a
 * second reshuffle-staging strategy is ever needed, share this helper rather than adding
 * reflection on the private draw pile (the u2 track's seeding helper uses reflection instead).
 *
 * The engine's own deck shuffle is UNSEEDED. Random-playout failures therefore carry a full
 * UnoState dump and the loop position; a `seed` printed in a message only seeds the test's driver
 * RNG and does NOT reproduce the engine's deal.
 *
 * Regression tests for the engine bugs this suite found, tagged with grep-able ids (all fixed in
 * UnoGame together with the suite): UNO-BUG-A (sevenZero win scored against swapped hands),
 * UNO-BUG-B (no roundOver guard on late chooseColor / resolveChallenge / catchUnoFailure),
 * UNO-BUG-C (calledUno and the catch window survived a 7-0 swap onto a new hand). The
 * draw-decision regressions (UNO-BUG-D) and the draw-decision / draw-guard tests live in
 * UnoDrawDecisionTest (u2 track).
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UnoReshuffleCoverageTest {

    // ------------------------------------------------------------------ fixtures

    private val colors = listOf(UnoColor.RED, UnoColor.YELLOW, UnoColor.GREEN, UnoColor.BLUE)

    private fun newGame(
        playerCount: Int = 4,
        rules: UnoRules = UnoRules(),
        onEnd: ((GameResult) -> Unit)? = null
    ): UnoGame {
        val game = UnoGame()
        game.rules = rules
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = (0 until playerCount).map {
                    PlayerInfo(playerId = "p$it", displayName = "P$it", teamId = if (rules.teamPlay) it % 2 else -1)
                },
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        if (onEnd != null) game.setOnMatchEnd(onEnd)
        game.startMatch()
        return game
    }

    private fun card(color: UnoColor, rank: UnoRank, id: Int) = UnoCard(color, rank, id)

    private fun st(game: UnoGame): UnoState = game.state.value!!

    /** Uniform filler cards (ids 9500+) that sit UNDER the discard top and therefore are what a
     *  reshuffle feeds back into the draw pile. Uniform kind => the drawn card's kind is known. */
    private fun padding(n: Int, color: UnoColor = UnoColor.BLUE, rank: UnoRank = UnoRank.NINE) =
        List(n) { UnoCard(color, rank, 9500 + it) }

    /** Shrinks the private draw pile to exactly [target] via the public API only: hands the
     *  surplus to player 0 through drawCard's pendingDraw branch on a freshly dealt game (discard
     *  pile is a single card, so nothing can reshuffle here). Already at [target] is a no-op, so
     *  [stage] may be called a second time on a game in progress with the current pile size. */
    private fun drainDrawPile(game: UnoGame, target: Int) {
        val s = st(game)
        assertTrue("cannot drain up to $target from ${s.drawPileSize}", s.drawPileSize >= target)
        val n = s.drawPileSize - target
        if (n > 0) {
            // Draining fabricates a pending draw, which is only safe while the discard is a single
            // card (nothing can reshuffle). A re-stage with n == 0 (target == current size) is fine
            // on a game in progress.
            assertEquals("draining assumes a freshly dealt game", 1, s.discardPile.size)
            game.state.value = s.copy(
                pendingDraw = n, currentPlayerIndex = 0, awaitingColorChoice = false,
                awaitingChallenge = false, awaitingDrawDecision = false, roundOver = false, matchOver = false
            )
            game.drawCard(0)
        }
        assertEquals("drain failed to reach the target pile size", target, st(game).drawPileSize)
    }

    /** Drains to [pileSize] then installs hand-built hands + discard (`underTop` + `top`).
     *  Total cards stay exactly 108. */
    private fun stage(
        game: UnoGame,
        pileSize: Int,
        hands: List<List<UnoCard>>,
        top: UnoCard,
        current: Int,
        currentColor: UnoColor = if (top.isWild) UnoColor.RED else top.color,
        direction: Int = 1,
        pendingDraw: Int = 0,
        underTop: List<UnoCard>? = null,
        patch: (UnoState) -> UnoState = { it }
    ) {
        drainDrawPile(game, pileSize)
        val s = st(game)
        assertEquals("one hand per player", s.players.size, hands.size)
        val padCount = 108 - pileSize - hands.sumOf { it.size } - 1
        assertTrue("hands too large for pile size $pileSize", padCount >= 0)
        val pad = underTop ?: padding(padCount)
        assertEquals(padCount, pad.size)
        game.state.value = patch(
            s.copy(
                players = s.players.mapIndexed { i, p ->
                    p.copy(hand = hands[i], calledUno = false, catchWindowClosesAfterPlayerIndex = null)
                },
                discardPile = pad + top,
                drawPileSize = pileSize,
                currentColor = currentColor,
                currentPlayerIndex = current,
                direction = direction,
                pendingDraw = pendingDraw,
                awaitingColorChoice = false,
                awaitingChallenge = false,
                challengeVictimIndex = null,
                challengePlayedByIndex = null,
                colorBeforeWildDrawFour = null,
                awaitingDrawDecision = false,
                roundOver = false,
                matchOver = false,
                winnerPlayerId = null,
                winningTeamId = null,
                cumulativeScores = emptyMap()
            )
        )
        assertInvariants(game, "after staging")
    }

    /** The invariant the whole reshuffle fix exists for: no card is ever both in the discard pile
     *  and in a hand (or twice anywhere visible), and nothing is created or destroyed. */
    private fun assertInvariants(game: UnoGame, ctx: String = "") {
        val s = st(game)
        val visible = s.discardPile + s.players.flatMap { it.hand }
        val dupes = visible.groupingBy { it.instanceId }.eachCount().filter { it.value > 1 }.keys
        assertTrue("$ctx: instanceIds present more than once across discard+hands: $dupes", dupes.isEmpty())
        assertEquals(
            "$ctx: conservation (discard ${s.discardPile.size} + drawPileSize ${s.drawPileSize} + hands ${s.players.sumOf { it.hand.size }})",
            108, visible.size + s.drawPileSize
        )
        assertTrue("$ctx: drawPileSize must not be negative", s.drawPileSize >= 0)
        assertTrue("$ctx: discard pile must never be empty", s.discardPile.isNotEmpty())
    }

    private fun gained(before: List<UnoCard>, after: List<UnoCard>): List<UnoCard> {
        val had = before.map { it.instanceId }.toSet()
        return after.filter { it.instanceId !in had }
    }

    /** The draw took exactly [need] new cards: first the [pileSize] real cards that were left in
     *  the private pile (ids < 9000), then (only after the reshuffle) cards that came out of the
     *  collapsed discard, i.e. hand-built ids >= 9000. The collapsed discard also contains earlier
     *  hand-built cards (the previous top, cards played this test), so ANY id >= 9000 qualifies. */
    private fun assertDrewAcrossReshuffle(g: List<UnoCard>, need: Int, pileSize: Int) {
        assertEquals("number of cards drawn", need, g.size)
        assertTrue("the real pile's own cards must be drawn before any reshuffled ones", g.take(pileSize).all { it.instanceId < 9000 })
        assertEquals("cards that came out of the reshuffled discard", need - pileSize, g.count { it.instanceId >= 9000 })
    }

    private fun assertNoOp(game: UnoGame, what: String, action: () -> Unit) {
        val before = st(game)
        action()
        assertEquals("$what must be a no-op", before, st(game))
    }

    // ------------------------------------------------------------------ staging smoke test (sorts first)

    /** Guards the TEST TECHNIQUE, not the engine: [drainDrawPile] (fabricated pendingDraw served
     *  through the public drawCard) plus [stage] (wholesale `state.value` overwrite, 108-card
     *  padding) must still work. Every other test depends on it; if this one fails, the staging
     *  broke (for example the engine now rejects an unbacked pendingDraw, or the deck size changed)
     *  and the other failures in this class are noise. Named `00 ...` and run in NAME_ASCENDING
     *  order so it is the first failure in the report. */
    @Test
    fun `00 staging smoke test - a failure here means the drain-and-stage technique is broken, not the engine`() {
        try {
            val game = newGame(3)
            val top = card(UnoColor.RED, UnoRank.FIVE, 9001)
            val hands = List(3) { p -> listOf(card(UnoColor.GREEN, UnoRank.ONE, 9010 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9011 + p * 10)) }
            stage(game, pileSize = 0, hands = hands, top = top, current = 0)
            val staged = st(game)
            assertEquals("draw pile must be drained to the requested size", 0, staged.drawPileSize)
            assertEquals("hands must be exactly the hand-built ones", hands, staged.players.map { it.hand })
            assertEquals("hand-built top card must be installed", top, staged.topCard)
            assertEquals("discard = 108 - hands - empty pile", 108 - 6, staged.discardPile.size)

            // The engine must still accept a plain draw on the staged position (empty pile => reshuffle).
            game.drawCard(0)
            val drawn = gained(hands[0], st(game).players[0].hand)
            assertEquals("a draw from the staged empty pile must hand over one card", 1, drawn.size)
            assertTrue("that card must come from the staged padding", drawn.single().instanceId >= 9500)
            assertInvariants(game, "after the smoke draw")

            // A second staging call on a game in progress (used by the second-window test) must also work.
            stage(game, pileSize = st(game).drawPileSize, hands = hands, top = top, current = 0)
            assertEquals(hands, st(game).players.map { it.hand })
        } catch (e: Throwable) {
            throw AssertionError(
                "STAGING BROKEN: the drain-and-stage technique this whole class relies on no longer works. " +
                    "This is NOT an engine rule failure; fix the helper first, then re-read the other failures. Cause: $e", e
            )
        }
    }

    // ------------------------------------------------------------------ (a) resolveChallenge

    private class ChallengeScene(val game: UnoGame, val d4: UnoCard, val pileSize: Int)

    /** p0 plays a Wild Draw Four over RED and names BLUE; p1 is left facing Accept/Challenge. */
    private fun stageChallenge(pileSize: Int, p0Rest: List<UnoCard>): ChallengeScene {
        val game = newGame(4)
        val d4 = card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9010)
        val hands = listOf(
            listOf(d4) + p0Rest,
            listOf(card(UnoColor.BLUE, UnoRank.ONE, 9020), card(UnoColor.BLUE, UnoRank.TWO, 9021), card(UnoColor.GREEN, UnoRank.THREE, 9022)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.YELLOW, UnoRank.TWO, 9031), card(UnoColor.GREEN, UnoRank.FOUR, 9032)),
            listOf(card(UnoColor.GREEN, UnoRank.ONE, 9040), card(UnoColor.GREEN, UnoRank.TWO, 9041), card(UnoColor.YELLOW, UnoRank.FIVE, 9042))
        )
        stage(game, pileSize, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, d4)
        game.chooseColor(UnoColor.BLUE)
        val s = st(game)
        assertTrue(s.awaitingChallenge)
        assertEquals(1, s.challengeVictimIndex)
        assertEquals(0, s.challengePlayedByIndex)
        assertEquals(UnoColor.RED, s.colorBeforeWildDrawFour)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(4, s.pendingDraw)
        return ChallengeScene(game, d4, pileSize)
    }

    private fun assertChallengeResolved(scene: ChallengeScene, before: UnoState, drawer: Int, need: Int, expectedCurrent: Int) {
        val after = st(scene.game)
        assertEquals(
            "reshuffle must collapse the discard to the Wild Draw Four that was on top when the draw began",
            listOf(scene.d4), after.discardPile
        )
        assertDrewAcrossReshuffle(gained(before.players[drawer].hand, after.players[drawer].hand), need, scene.pileSize)
        for (i in after.players.indices) {
            if (i != drawer) assertEquals("player $i must not gain or lose cards", before.players[i].hand, after.players[i].hand)
        }
        assertEquals(expectedCurrent, after.currentPlayerIndex)
        assertEquals(0, after.pendingDraw)
        assertFalse(after.awaitingChallenge)
        assertNull(after.challengeVictimIndex)
        assertNull(after.challengePlayedByIndex)
        assertNull(after.colorBeforeWildDrawFour)
        assertEquals(UnoColor.BLUE, after.currentColor)
        assertEquals(
            "draw pile = old pile + the whole collapsed discard except its top - cards drawn",
            scene.pileSize + before.discardPile.size - 1 - need, after.drawPileSize
        )
        assertInvariants(scene.game, "after resolveChallenge")
    }

    /** Guards the resolveChallenge final-commit fix, ACCEPT branch (victim draws 4): if the commit
     *  reused the pre-draw discard pile, the padding would sit in the discard pile AND in the
     *  victim's hand (duplicates + total > 108). */
    @Test
    fun `accepting a Wild Draw Four across a reshuffle draws 4 and keeps the collapsed discard`() {
        val scene = stageChallenge(pileSize = 1, p0Rest = listOf(card(UnoColor.BLUE, UnoRank.SEVEN, 9011), card(UnoColor.YELLOW, UnoRank.EIGHT, 9012)))
        val before = st(scene.game)
        scene.game.resolveChallenge(accept = true)
        assertChallengeResolved(scene, before, drawer = 1, need = 4, expectedCurrent = 2)
        assertTrue(st(scene.game).lastAction.contains("draws 4"))
    }

    /** Guards the same commit for the SUCCESSFUL-challenge branch: the player who played the
     *  Wild Draw Four (p0) draws 4 because p0 held a RED card while RED was in play; the victim's
     *  turn then proceeds (no skip). Pile of 0 forces the ENTIRE draw out of the reshuffle. The
     *  RED card is accompanied by a Wild in p0's hand to show wilds are irrelevant either way. */
    @Test
    fun `a successful challenge across a reshuffle makes the player who played the Draw Four draw 4`() {
        val scene = stageChallenge(pileSize = 0, p0Rest = listOf(card(UnoColor.RED, UnoRank.SEVEN, 9011), card(UnoColor.WILD, UnoRank.WILD, 9012)))
        val before = st(scene.game)
        scene.game.resolveChallenge(accept = false)
        assertChallengeResolved(scene, before, drawer = 0, need = 4, expectedCurrent = 1)
        assertTrue(st(scene.game).lastAction.contains("Challenge succeeds"))
    }

    /** Guards the FAILED-challenge branch: p0 held no RED card (only BLUE and a Wild, which never
     *  counts), so the victim draws SIX, not four, and loses the turn. Pile of 3 makes the
     *  reshuffle happen partway through the draw. */
    @Test
    fun `a failed challenge across a reshuffle makes the victim draw 6`() {
        val scene = stageChallenge(pileSize = 3, p0Rest = listOf(card(UnoColor.BLUE, UnoRank.SEVEN, 9011), card(UnoColor.WILD, UnoRank.WILD, 9012)))
        val before = st(scene.game)
        scene.game.resolveChallenge(accept = false)
        assertChallengeResolved(scene, before, drawer = 1, need = 6, expectedCurrent = 2)
        assertTrue(st(scene.game).lastAction.contains("Challenge fails"))
    }

    /** The challenge is judged against the color in play BEFORE the Wild Draw Four (kept in
     *  colorBeforeWildDrawFour), not the top card's color: here the top card is a plain Wild
     *  (color WILD) with GREEN chosen, and p0 holds a GREEN card, so the challenge must succeed. */
    @Test
    fun `challenge is judged against the color in play before the Draw Four, not the top card color`() {
        val game = newGame(4)
        val d4 = card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9010)
        val hands = listOf(
            listOf(d4, card(UnoColor.GREEN, UnoRank.SEVEN, 9011), card(UnoColor.RED, UnoRank.ONE, 9013)),
            listOf(card(UnoColor.BLUE, UnoRank.ONE, 9020), card(UnoColor.BLUE, UnoRank.TWO, 9021)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.YELLOW, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.GREEN, UnoRank.ONE, 9040), card(UnoColor.GREEN, UnoRank.TWO, 9041))
        )
        stage(game, 20, hands, top = card(UnoColor.WILD, UnoRank.WILD, 9001), current = 0, currentColor = UnoColor.GREEN)
        game.playCard(0, d4)
        game.chooseColor(UnoColor.BLUE)
        assertEquals(UnoColor.GREEN, st(game).colorBeforeWildDrawFour)
        val p0Before = st(game).players[0].hand.size
        game.resolveChallenge(accept = false)
        assertEquals("challenge should succeed: p0 held GREEN while GREEN was in play", p0Before + 4, st(game).players[0].hand.size)
        assertEquals("victim keeps the turn after a successful challenge", 1, st(game).currentPlayerIndex)
        assertInvariants(game)
    }

    /** Only NON-wild cards of the prior color make a challenge succeed. p0 holds Wilds and
     *  other colors only -> the challenge fails (victim draws 6), even though p0 "had" cards. */
    @Test
    fun `wilds and off-color cards do not make a Draw Four illegal`() {
        val game = newGame(4)
        val d4 = card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9010)
        val hands = listOf(
            listOf(d4, card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9011), card(UnoColor.WILD, UnoRank.WILD, 9012), card(UnoColor.GREEN, UnoRank.FIVE, 9013)),
            listOf(card(UnoColor.BLUE, UnoRank.ONE, 9020), card(UnoColor.BLUE, UnoRank.TWO, 9021)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.YELLOW, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.GREEN, UnoRank.ONE, 9040), card(UnoColor.GREEN, UnoRank.TWO, 9041))
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, d4)
        game.chooseColor(UnoColor.YELLOW)
        game.resolveChallenge(accept = false)
        val s = st(game)
        assertEquals(2 + 6, s.players[1].hand.size)
        assertEquals(3, s.players[0].hand.size)
        assertEquals(2, s.currentPlayerIndex)
        assertInvariants(game)
    }

    /** While a Challenge is pending nobody may play the victim's card or pick a colour, and
     *  resolving with no pending challenge does nothing. (Draw / keep guards during a pending
     *  challenge are owned by UnoDrawDecisionTest.) */
    @Test
    fun `everything except resolveChallenge is blocked while a challenge is pending`() {
        val scene = stageChallenge(pileSize = 20, p0Rest = listOf(card(UnoColor.BLUE, UnoRank.SEVEN, 9011), card(UnoColor.YELLOW, UnoRank.EIGHT, 9012)))
        val game = scene.game
        val victimCard = st(game).players[1].hand.first()
        assertNoOp(game, "playCard by the victim") { game.playCard(1, victimCard) }
        assertNoOp(game, "chooseColor with no color pending") { game.chooseColor(UnoColor.GREEN) }
        game.resolveChallenge(accept = true)
        assertNoOp(game, "resolveChallenge with nothing pending") { game.resolveChallenge(accept = true) }
    }

    // ------------------------------------------------------------------ (b) catchUnoFailure

    /** Guards catchUnoFailure's final commit: penalty draw of 2 crossing a reshuffle. The window
     *  is stamped by the engine itself (p0 plays down to one card without calling UNO), then p2
     *  catches p0. After the collapse the only discard card is the card p0 just played. */
    @Test
    fun `a UNO catch penalty across a reshuffle draws 2 and keeps the collapsed discard`() {
        val game = newGame(4)
        val played = card(UnoColor.RED, UnoRank.THREE, 9011)
        val kept = card(UnoColor.BLUE, UnoRank.SEVEN, 9012)
        val hands = listOf(
            listOf(played, kept),
            listOf(card(UnoColor.RED, UnoRank.NINE, 9020), card(UnoColor.GREEN, UnoRank.ONE, 9021)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.GREEN, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FOUR, 9041))
        )
        stage(game, pileSize = 1, hands = hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, played)
        assertEquals("window opens on the seat about to act", 1, st(game).players[0].catchWindowClosesAfterPlayerIndex)
        val before = st(game)

        game.catchUnoFailure(accuserIndex = 2, targetIndex = 0)

        val after = st(game)
        assertEquals("discard collapses to the last played card", listOf(played), after.discardPile)
        assertEquals(1 + 2, after.players[0].hand.size)
        assertTrue(after.players[0].hand.contains(kept))
        assertDrewAcrossReshuffle(gained(before.players[0].hand, after.players[0].hand), need = 2, pileSize = 1)
        assertEquals("catching must not move the turn", 1, after.currentPlayerIndex)
        assertFalse(after.players[0].calledUno)
        assertNull("penalty took the target off one card, so its window must clear", after.players[0].catchWindowClosesAfterPlayerIndex)
        assertEquals(1 + before.discardPile.size - 1 - 2, after.drawPileSize)
        assertTrue(after.lastAction.contains("caught"))
        assertInvariants(game, "after catch")
    }

    // ------------------------------------------------------------------ (c) drawCard pendingDraw

    /** Guards drawCard's pendingDraw branch in isolation: a hand-edited pending draw of 4 (the
     *  amount is what the branch must draw, not 1) with only 1 card left in the pile. */
    @Test
    fun `serving a pending draw across a reshuffle draws exactly the pending amount`() {
        val game = newGame(4)
        val top = card(UnoColor.RED, UnoRank.DRAW_TWO, 9001)
        val hands = listOf(
            listOf(card(UnoColor.RED, UnoRank.ONE, 9010), card(UnoColor.GREEN, UnoRank.ONE, 9011)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9020), card(UnoColor.GREEN, UnoRank.TWO, 9021)),
            listOf(card(UnoColor.YELLOW, UnoRank.TWO, 9030), card(UnoColor.GREEN, UnoRank.THREE, 9031)),
            listOf(card(UnoColor.YELLOW, UnoRank.THREE, 9040), card(UnoColor.BLUE, UnoRank.FOUR, 9041))
        )
        stage(game, pileSize = 1, hands = hands, top = top, current = 1, pendingDraw = 4)
        val before = st(game)

        game.drawCard(1)

        val after = st(game)
        assertEquals("discard collapses to the untouched top card", listOf(top), after.discardPile)
        assertDrewAcrossReshuffle(gained(before.players[1].hand, after.players[1].hand), need = 4, pileSize = 1)
        assertEquals(2, after.currentPlayerIndex)
        assertEquals(0, after.pendingDraw)
        assertFalse(after.awaitingDrawDecision)
        assertEquals(1 + before.discardPile.size - 1 - 4, after.drawPileSize)
        assertTrue(after.lastAction.contains("drew 4"))
        assertInvariants(game)
    }

    /** End-to-end stacking chain: +2 (p0), +2 (p1), Wild +4 (p2, allowed on +2 because
     *  stackDrawFourOnDrawTwo) -> pending 8, then p3 has no answer and must serve all 8 with only 3
     *  cards left in the pile. Every ply is checked for conservation. Also proves p3 cannot dodge
     *  the obligation by playing an ordinary matching card, and pendingDraw resets to 0. */
    @Test
    fun `stacked Draw Two Draw Two and Draw Four then served across a reshuffle`() {
        val game = newGame(4, UnoRules(stackDraw = true, stackDrawFourOnDrawTwo = true))
        val d2a = card(UnoColor.RED, UnoRank.DRAW_TWO, 9011)
        val d2b = card(UnoColor.BLUE, UnoRank.DRAW_TWO, 9021)
        val d4 = card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9031)
        val hands = listOf(
            listOf(d2a, card(UnoColor.RED, UnoRank.ONE, 9012)),
            listOf(d2b, card(UnoColor.GREEN, UnoRank.ONE, 9022)),
            listOf(d4, card(UnoColor.BLUE, UnoRank.TWO, 9032)),
            listOf(card(UnoColor.GREEN, UnoRank.FIVE, 9041), card(UnoColor.YELLOW, UnoRank.SIX, 9042), card(UnoColor.BLUE, UnoRank.NINE, 9043))
        )
        stage(game, pileSize = 3, hands = hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)

        game.playCard(0, d2a)
        assertEquals(2, st(game).pendingDraw)
        assertEquals("stacking passes the obligation on without anyone drawing yet", 1, st(game).currentPlayerIndex)
        assertEquals(2, st(game).players[1].hand.size)
        game.playCard(1, d2b)
        assertEquals(4, st(game).pendingDraw)
        game.playCard(2, d4)
        assertTrue(st(game).awaitingColorChoice)
        assertEquals(8, st(game).pendingDraw)
        game.chooseColor(UnoColor.GREEN)
        val s = st(game)
        assertFalse("stacking rules skip the Challenge step", s.awaitingChallenge)
        assertEquals(3, s.currentPlayerIndex)
        assertEquals(8, s.pendingDraw)
        assertInvariants(game, "before service")

        val greenFive = hands[3][0]
        assertNoOp(game, "playing an ordinary matching card while a draw is pending") { game.playCard(3, greenFive) }

        val before = st(game)
        game.drawCard(3)
        val after = st(game)
        assertEquals("discard collapses to the Wild Draw Four that was on top", listOf(d4), after.discardPile)
        assertDrewAcrossReshuffle(gained(before.players[3].hand, after.players[3].hand), need = 8, pileSize = 3)
        assertEquals(0, after.pendingDraw)
        assertEquals(0, after.currentPlayerIndex)
        assertEquals(3 + before.discardPile.size - 1 - 8, after.drawPileSize)
        assertInvariants(game, "after service")
    }

    // ------------------------------------------------------------------ (drawCard single draw)

    /** Guards drawCard's single-draw commit when the draw itself triggers the reshuffle (pile 0)
     *  and the drawn card (uniform BLUE NINE against RED FIVE) is NOT playable: exactly one card
     *  arrives, the turn passes, and the discard is just the untouched top. */
    @Test
    fun `a single draw from an empty pile reshuffles and passes the turn when the card is unplayable`() {
        val game = newGame(4)
        val top = card(UnoColor.RED, UnoRank.FIVE, 9001)
        val hands = List(4) { p -> listOf(card(UnoColor.GREEN, UnoRank.ONE, 9010 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9011 + p * 10)) }
        stage(game, pileSize = 0, hands = hands, top = top, current = 0)
        val before = st(game)

        game.drawCard(0)

        val after = st(game)
        assertEquals(listOf(top), after.discardPile)
        assertDrewAcrossReshuffle(gained(before.players[0].hand, after.players[0].hand), need = 1, pileSize = 0)
        assertEquals(1, after.currentPlayerIndex)
        assertFalse(after.awaitingDrawDecision)
        assertEquals(before.discardPile.size - 1 - 1, after.drawPileSize)
        assertInvariants(game)
    }

    /** Same commit, playable branch: the drawn card (uniform RED NINE) matches RED, so the turn
     *  stays on the drawer awaiting a decision; playing it afterwards must land it on top of the
     *  COLLAPSED discard (top + drawn card), not on the stale full pile. */
    @Test
    fun `a single draw across a reshuffle that is playable keeps the turn and plays onto the collapsed discard`() {
        val game = newGame(4)
        val top = card(UnoColor.RED, UnoRank.FIVE, 9001)
        val hands = List(4) { p -> listOf(card(UnoColor.GREEN, UnoRank.ONE, 9010 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9011 + p * 10)) }
        stage(game, pileSize = 0, hands = hands, top = top, current = 0, underTop = padding(108 - 8 - 1, UnoColor.RED, UnoRank.NINE))
        val before = st(game)

        game.drawCard(0)

        val mid = st(game)
        assertEquals(listOf(top), mid.discardPile)
        assertTrue(mid.awaitingDrawDecision)
        assertEquals("playable draw leaves the turn on the drawer", 0, mid.currentPlayerIndex)
        val drawn = gained(before.players[0].hand, mid.players[0].hand).single()
        assertTrue(drawn.instanceId >= 9500)
        assertInvariants(game, "after draw")

        game.playCard(0, drawn)
        val after = st(game)
        assertEquals(listOf(top, drawn), after.discardPile)
        assertEquals(1, after.currentPlayerIndex)
        assertFalse(after.awaitingDrawDecision)
        assertInvariants(game, "after playing the drawn card")
    }

    // ------------------------------------------------------------------ (d) playCard Draw Two

    private fun stageDrawTwo(playerCount: Int, direction: Int, pileSize: Int): Triple<UnoGame, UnoCard, UnoCard> {
        val game = newGame(playerCount)
        val top = card(UnoColor.RED, UnoRank.FIVE, 9001)
        val d2 = card(UnoColor.RED, UnoRank.DRAW_TWO, 9011)
        val hands = List(playerCount) { p ->
            if (p == 0) listOf(d2, card(UnoColor.BLUE, UnoRank.ONE, 9012))
            else listOf(card(UnoColor.GREEN, UnoRank.ONE, 9020 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9021 + p * 10))
        }
        stage(game, pileSize, hands, top, current = 0, direction = direction)
        return Triple(game, top, d2)
    }

    private fun assertDrawTwoResolved(game: UnoGame, before: UnoState, top: UnoCard, d2: UnoCard, victim: Int, next: Int, pileSize: Int) {
        val after = st(game)
        assertEquals(
            "final commit must use the collapsed discard: previous top, then the Draw Two just played",
            listOf(top, d2), after.discardPile
        )
        assertDrewAcrossReshuffle(gained(before.players[victim].hand, after.players[victim].hand), need = 2, pileSize = pileSize)
        assertEquals("victim is skipped", next, after.currentPlayerIndex)
        assertEquals("non-stacking Draw Two is served immediately", 0, after.pendingDraw)
        assertEquals(UnoColor.RED, after.currentColor)
        assertEquals("the player who played it lost exactly the played card", before.players[0].hand.size - 1, after.players[0].hand.size)
        assertEquals(pileSize + before.discardPile.size - 3, after.drawPileSize)
        assertInvariants(game)
    }

    /** Guards playCard's final commit (`currentDiscardPile() + card`): the victim's immediate draw
     *  of 2 reshuffles (pile 1), and reverting to `s.discardPile + card` would resurrect the whole
     *  pre-reshuffle discard on top of the victim's new cards. */
    @Test
    fun `a non-stacked Draw Two across a reshuffle keeps the collapsed discard plus the played card`() {
        val (game, top, d2) = stageDrawTwo(playerCount = 4, direction = 1, pileSize = 1)
        val before = st(game)
        game.playCard(0, d2)
        assertDrawTwoResolved(game, before, top, d2, victim = 1, next = 2, pileSize = 1)
    }

    /** Same commit, counter-clockwise: the victim is the PREVIOUS seat (3), and play continues at 2. */
    @Test
    fun `a counter-clockwise Draw Two across a reshuffle hits the previous seat`() {
        val (game, top, d2) = stageDrawTwo(playerCount = 4, direction = -1, pileSize = 0)
        val before = st(game)
        game.playCard(0, d2)
        assertDrawTwoResolved(game, before, top, d2, victim = 3, next = 2, pileSize = 0)
    }

    /** With two players a Draw Two hands the turn straight back to the player who played it. */
    @Test
    fun `a two-player Draw Two across a reshuffle skips the victim back to the player`() {
        val (game, top, d2) = stageDrawTwo(playerCount = 2, direction = 1, pileSize = 1)
        val before = st(game)
        game.playCard(0, d2)
        assertDrawTwoResolved(game, before, top, d2, victim = 1, next = 0, pileSize = 1)
    }

    /** A jump-in Draw Two goes through playCard too: the jumper is the anchor for victim/next
     *  seat, and the reshuffle inside it must not resurrect the old discard either. */
    @Test
    fun `a jump-in Draw Two across a reshuffle anchors on the jumper and keeps the collapsed discard`() {
        val game = newGame(4, UnoRules(jumpIn = true))
        val top = card(UnoColor.RED, UnoRank.DRAW_TWO, 9001)
        val jump = card(UnoColor.RED, UnoRank.DRAW_TWO, 9031)
        val hands = listOf(
            listOf(card(UnoColor.GREEN, UnoRank.ONE, 9010), card(UnoColor.YELLOW, UnoRank.TWO, 9011)),
            listOf(card(UnoColor.GREEN, UnoRank.TWO, 9020), card(UnoColor.YELLOW, UnoRank.THREE, 9021)),
            listOf(jump, card(UnoColor.YELLOW, UnoRank.FOUR, 9032)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FIVE, 9041))
        )
        stage(game, pileSize = 1, hands = hands, top = top, current = 0)
        val before = st(game)

        game.jumpIn(2, jump)

        val after = st(game)
        assertEquals(listOf(top, jump), after.discardPile)
        assertDrewAcrossReshuffle(gained(before.players[3].hand, after.players[3].hand), need = 2, pileSize = 1)
        assertEquals("victim 3 is skipped, play resumes at seat 0", 0, after.currentPlayerIndex)
        assertEquals(0, after.pendingDraw)
        assertInvariants(game)
    }

    // ------------------------------------------------------------------ reshuffle boundaries

    /** A draw that the pile can cover exactly must NOT touch the discard pile (`size >= need`
     *  boundary): an off-by-one that reshuffles when size == need would collapse a big discard
     *  early. The NEXT draw, on the now-empty pile, must then reshuffle. */
    @Test
    fun `an exactly-sufficient pile is drawn without a reshuffle and the next draw reshuffles`() {
        val game = newGame(4)
        val top = card(UnoColor.RED, UnoRank.DRAW_TWO, 9001)
        val hands = List(4) { p -> listOf(card(UnoColor.GREEN, UnoRank.ONE, 9010 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9011 + p * 10)) }
        stage(game, pileSize = 2, hands = hands, top = top, current = 0, pendingDraw = 2)
        val discardBefore = st(game).discardPile

        game.drawCard(0)
        val mid = st(game)
        assertEquals("pile covered the draw exactly, so the discard must be untouched", discardBefore, mid.discardPile)
        assertEquals(0, mid.drawPileSize)
        assertEquals(1, mid.currentPlayerIndex)
        assertInvariants(game, "after exact draw")

        game.drawCard(1) // pile is empty now: single draw must reshuffle
        val after = st(game)
        assertEquals(listOf(top), after.discardPile)
        assertEquals(discardBefore.size - 1 - 1, after.drawPileSize)
        assertInvariants(game, "after the reshuffling draw")
    }

    /** True exhaustion in a multi-card draw: 1 card in the pile, nothing else anywhere but the
     *  top card. A pending draw of 3 must hand over just that 1 card (no crash), and the next
     *  service with nothing at all must hand over nothing and still pass the turn. */
    @Test
    fun `serving a pending draw with almost nothing left gives what exists and never crashes`() {
        val game = newGame(4)
        val top = card(UnoColor.RED, UnoRank.DRAW_TWO, 9001)
        val hands = List(4) { p -> listOf(card(UnoColor.GREEN, UnoRank.ONE, 9010 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9011 + p * 10)) }
        stage(game, pileSize = 1, hands = hands, top = top, current = 1, pendingDraw = 3)
        // Move every card under the top into p3's hand: discard is now just `top`, nothing to reshuffle.
        val s = st(game)
        val under = s.discardPile.dropLast(1)
        game.state.value = s.copy(
            discardPile = listOf(top),
            players = s.players.mapIndexed { i, p -> if (i == 3) p.copy(hand = p.hand + under) else p }
        )
        assertInvariants(game, "exhaustion staging")

        val handBefore = st(game).players[1].hand.size
        game.drawCard(1)
        var after = st(game)
        assertEquals("only the single remaining pile card exists", handBefore + 1, after.players[1].hand.size)
        assertEquals(0, after.drawPileSize)
        assertEquals(0, after.pendingDraw)
        assertEquals(2, after.currentPlayerIndex)
        assertEquals(listOf(top), after.discardPile)
        assertInvariants(game, "after partial service")

        game.state.value = after.copy(pendingDraw = 2)
        val h2 = st(game).players[2].hand.size
        game.drawCard(2)
        after = st(game)
        assertEquals("nothing left anywhere: hand unchanged", h2, after.players[2].hand.size)
        assertEquals(0, after.pendingDraw)
        assertEquals(3, after.currentPlayerIndex)
        assertInvariants(game, "after empty service")
    }

    // ------------------------------------------------------------------ catch-window lifecycle

    private fun stageCatch(pileSize: Int = 30, underTop: List<UnoCard>? = null, current: Int = 0): Triple<UnoGame, UnoCard, UnoCard> {
        val game = newGame(4)
        val played = card(UnoColor.RED, UnoRank.THREE, 9011)
        val kept = card(UnoColor.BLUE, UnoRank.SEVEN, 9012)
        val hands = listOf(
            listOf(played, kept),
            listOf(card(UnoColor.RED, UnoRank.NINE, 9020), card(UnoColor.GREEN, UnoRank.ONE, 9021)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.GREEN, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FOUR, 9041))
        )
        stage(game, pileSize, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = current, underTop = underTop)
        return Triple(game, played, kept)
    }

    /** A catch is only enforceable while play has not moved past the seat that was up next. Here
     *  the next player takes an UNPLAYABLE draw (turn passes deterministically) -- a different way
     *  of closing the window than the existing "next player plays" test. */
    @Test
    fun `the catch window closes when the next player draws and passes`() {
        val (game, played, _) = stageCatch(pileSize = 0)
        game.playCard(0, played)
        assertEquals(1, st(game).players[0].catchWindowClosesAfterPlayerIndex)
        // The old top (RED FIVE, id 9001) is about to be reshuffled into the draw pile along with the
        // padding. Relabel it as an unplayable filler card (same id, so counts are unchanged) so that
        // whichever card the reshuffle deals to seat 1 is deterministically unplayable on RED THREE.
        val relabelled = st(game).let { cur ->
            cur.copy(discardPile = cur.discardPile.map { if (it.instanceId == 9001) card(UnoColor.GREEN, UnoRank.ONE, 9001) else it })
        }
        game.state.value = relabelled
        game.drawCard(1) // every reshuffled card is BLUE NINE or GREEN ONE against RED THREE: unplayable, turn passes to 2
        assertEquals(2, st(game).currentPlayerIndex)

        assertNoOp(game, "a catch after the next player's turn is over") { game.catchUnoFailure(3, 0) }
    }

    /** Inside the window: penalty 2, calledUno stays false, the target leaves the one-card state
     *  (window cleared), and a repeat attempt is a no-op because the target no longer has one card. */
    @Test
    fun `a catch inside the window applies a 2-card penalty once`() {
        val (game, played, kept) = stageCatch()
        game.playCard(0, played)
        game.catchUnoFailure(accuserIndex = 3, targetIndex = 0)
        val s = st(game)
        assertEquals(3, s.players[0].hand.size)
        assertTrue(s.players[0].hand.contains(kept))
        assertFalse(s.players[0].calledUno)
        assertNull(s.players[0].catchWindowClosesAfterPlayerIndex)
        assertInvariants(game)
        assertNoOp(game, "a second catch on the same target") { game.catchUnoFailure(2, 0) }
    }

    /** Catch guards: cannot catch yourself, and cannot catch someone holding 2 cards. */
    @Test
    fun `self-catches and catches on a player with two cards do nothing`() {
        val (game, played, _) = stageCatch()
        assertNoOp(game, "catching a player who still has two cards") { game.catchUnoFailure(2, 0) }
        game.playCard(0, played)
        assertNoOp(game, "a self-catch") { game.catchUnoFailure(0, 0) }
        assertNoOp(game, "catching a player with two cards") { game.catchUnoFailure(0, 1) }
    }

    /** A Wild played as the second-last card must not stamp a catch window while the color choice
     *  is pending (the "next seat" is not known yet), and must stamp the CORRECT seat once the
     *  color is chosen. Before the choice, a catch attempt is a no-op. */
    @Test
    fun `a Wild leaving one card opens the catch window only after the color is chosen`() {
        val game = newGame(4)
        val wild = card(UnoColor.WILD, UnoRank.WILD, 9011)
        val hands = listOf(
            listOf(wild, card(UnoColor.BLUE, UnoRank.SEVEN, 9012)),
            listOf(card(UnoColor.RED, UnoRank.NINE, 9020), card(UnoColor.GREEN, UnoRank.ONE, 9021)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.GREEN, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FOUR, 9041))
        )
        stage(game, 30, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, wild)
        assertTrue(st(game).awaitingColorChoice)
        assertNull(st(game).players[0].catchWindowClosesAfterPlayerIndex)
        assertNoOp(game, "a catch while the color choice is pending") { game.catchUnoFailure(2, 0) }

        game.chooseColor(UnoColor.GREEN)
        assertEquals(1, st(game).currentPlayerIndex)
        assertEquals(1, st(game).players[0].catchWindowClosesAfterPlayerIndex)
        game.catchUnoFailure(2, 0)
        assertEquals(3, st(game).players[0].hand.size)
    }

    /** After a catch clears the window, reaching one card AGAIN must open a FRESH window keyed to
     *  the new next seat (direction reversed here so 3 != the old 1), never reuse a stale value. */
    @Test
    fun `reaching one card a second time opens a fresh window on the new next seat`() {
        val (game, played, _) = stageCatch()
        game.playCard(0, played)
        game.catchUnoFailure(2, 0)
        assertNull(st(game).players[0].catchWindowClosesAfterPlayerIndex)

        // Second staging call on the game in progress (keeps 108-card conservation): p0 is back to two
        // cards, counter-clockwise, on a fresh RED FIVE.
        val again = card(UnoColor.RED, UnoRank.NINE, 9111)
        val hands = listOf(
            listOf(again, card(UnoColor.YELLOW, UnoRank.TWO, 9112)),
            listOf(card(UnoColor.RED, UnoRank.ONE, 9120), card(UnoColor.GREEN, UnoRank.ONE, 9121)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9130), card(UnoColor.GREEN, UnoRank.TWO, 9131)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9140), card(UnoColor.YELLOW, UnoRank.FOUR, 9141))
        )
        stage(game, pileSize = st(game).drawPileSize, hands = hands, top = card(UnoColor.RED, UnoRank.FIVE, 9113), current = 0, direction = -1)
        game.playCard(0, again)
        assertEquals("counter-clockwise: seat 3 is now up next", 3, st(game).currentPlayerIndex)
        assertEquals(3, st(game).players[0].catchWindowClosesAfterPlayerIndex)
        assertInvariants(game, "after the second window opened")
    }

    /** A UNO called BEFORE playing down to one card is honoured (no window, uncatchable), but
     *  playing to more than one card clears any stale call so the flag can't leak forward. */
    @Test
    fun `an early UNO call protects the player and is cleared if their hand stays above one`() {
        val (game, played, _) = stageCatch()
        game.callUno(0)
        game.playCard(0, played)
        assertTrue(st(game).players[0].calledUno)
        assertNull(st(game).players[0].catchWindowClosesAfterPlayerIndex)
        assertNoOp(game, "catching a player who called UNO first") { game.catchUnoFailure(2, 0) }

        val g2 = newGame(4)
        val hands = List(4) { p -> listOf(card(UnoColor.RED, UnoRank.ONE, 9200 + p * 10), card(UnoColor.RED, UnoRank.TWO, 9201 + p * 10), card(UnoColor.RED, UnoRank.THREE, 9202 + p * 10)) }
        stage(g2, 30, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        g2.callUno(0)
        g2.playCard(0, hands[0][0])
        assertEquals(2, st(g2).players[0].hand.size)
        assertFalse("a premature UNO must not survive when the hand does not reach one card", st(g2).players[0].calledUno)
    }

    // ------------------------------------------------------------------ legality / turn structure

    private fun stageLegal(current: Int = 0, top: UnoCard = card(UnoColor.RED, UnoRank.FIVE, 9001), currentColor: UnoColor? = null): UnoGame {
        val game = newGame(4)
        val hands = listOf(
            listOf(
                card(UnoColor.RED, UnoRank.NINE, 9010),   // color match
                card(UnoColor.BLUE, UnoRank.FIVE, 9011),  // rank match
                card(UnoColor.GREEN, UnoRank.EIGHT, 9012),// no match
                card(UnoColor.WILD, UnoRank.WILD, 9013)   // always legal
            ),
            listOf(card(UnoColor.RED, UnoRank.ONE, 9020), card(UnoColor.RED, UnoRank.TWO, 9021)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.RED, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FOUR, 9041))
        )
        stage(game, 30, hands, top = top, current = current, currentColor = currentColor ?: if (top.isWild) UnoColor.RED else top.color)
        return game
    }

    @Test
    fun `only color matches rank matches and wilds are playable and playing changes the color`() {
        var game = stageLegal()
        assertNoOp(game, "an unmatched card") { game.playCard(0, st(game).players[0].hand[2]) }
        assertNoOp(game, "a card that is not in the hand") { game.playCard(0, card(UnoColor.RED, UnoRank.NINE, 9999)) }
        assertNoOp(game, "an out-of-turn play") { game.playCard(1, st(game).players[1].hand[0]) }

        game = stageLegal()
        game.playCard(0, st(game).players[0].hand[1]) // BLUE FIVE onto RED FIVE: rank match
        assertEquals(UnoColor.BLUE, st(game).currentColor)
        assertEquals(1, st(game).currentPlayerIndex)
        assertEquals(3, st(game).players[0].hand.size)

        game = stageLegal()
        game.playCard(0, st(game).players[0].hand[0]) // color match
        assertEquals(UnoColor.RED, st(game).currentColor)
        assertInvariants(game)
    }

    /** After a Wild the discard's own color is WILD; legality must follow currentColor. */
    @Test
    fun `after a wild legality follows the chosen color not the wild card's own color`() {
        val wildTop = card(UnoColor.WILD, UnoRank.WILD, 9001)
        var game = stageLegal(top = wildTop, currentColor = UnoColor.GREEN)
        assertNoOp(game, "a RED card while GREEN is in play") { game.playCard(0, st(game).players[0].hand[0]) }
        game.playCard(0, st(game).players[0].hand[2]) // GREEN EIGHT
        assertEquals(UnoColor.GREEN, st(game).currentColor)
        assertEquals(1, st(game).currentPlayerIndex)

        game = stageLegal(top = wildTop, currentColor = UnoColor.GREEN)
        game.playCard(0, st(game).players[0].hand[3]) // a Wild is always playable
        assertTrue(st(game).awaitingColorChoice)
        assertNoOp(game, "drawing while a color choice is pending") { game.drawCard(0) }
        game.chooseColor(UnoColor.YELLOW)
        assertEquals(UnoColor.YELLOW, st(game).currentColor)
        assertEquals(1, st(game).currentPlayerIndex)
        assertFalse(st(game).awaitingColorChoice)
    }

    @Test
    fun `skip and reverse move the turn correctly for 2 3 and 4 players`() {
        fun play(players: Int, direction: Int, rank: UnoRank): UnoState {
            val game = newGame(players)
            val c = card(UnoColor.RED, rank, 9011)
            val hands = List(players) { p -> if (p == 0) listOf(c, card(UnoColor.BLUE, UnoRank.ONE, 9012)) else listOf(card(UnoColor.GREEN, UnoRank.ONE, 9020 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9021 + p * 10)) }
            stage(game, 30, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0, direction = direction)
            game.playCard(0, c)
            assertInvariants(game)
            return st(game)
        }
        // 2 players: Reverse acts as Skip -> the same player goes again; Skip likewise.
        var s = play(2, 1, UnoRank.REVERSE)
        assertEquals(0, s.currentPlayerIndex); assertEquals(-1, s.direction)
        s = play(2, 1, UnoRank.SKIP)
        assertEquals(0, s.currentPlayerIndex); assertEquals(1, s.direction)
        // 3 players: Reverse hands the turn to the PREVIOUS seat.
        s = play(3, 1, UnoRank.REVERSE)
        assertEquals(2, s.currentPlayerIndex); assertEquals(-1, s.direction)
        s = play(3, -1, UnoRank.REVERSE)
        assertEquals(1, s.currentPlayerIndex); assertEquals(1, s.direction)
        // 4 players: Skip jumps a seat in either direction.
        s = play(4, 1, UnoRank.SKIP)
        assertEquals(2, s.currentPlayerIndex)
        s = play(4, -1, UnoRank.SKIP)
        assertEquals(2, s.currentPlayerIndex)
        s = play(4, 1, UnoRank.REVERSE)
        assertEquals(3, s.currentPlayerIndex)
    }

    // ------------------------------------------------------------------ stacking rules

    /** With stackDraw a Draw Two does not draw immediately; the victim, lacking a Draw Two, is
     *  locked out of normal plays and must draw the whole stack, which then resets to 0. */
    @Test
    fun `stacked Draw Two defers the draw and locks the victim to drawing`() {
        val game = newGame(4, UnoRules(stackDraw = true))
        val d2 = card(UnoColor.RED, UnoRank.DRAW_TWO, 9011)
        val hands = listOf(
            listOf(d2, card(UnoColor.BLUE, UnoRank.ONE, 9012)),
            listOf(card(UnoColor.RED, UnoRank.ONE, 9020), card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9021)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.RED, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FOUR, 9041))
        )
        stage(game, 30, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, d2)
        val s = st(game)
        assertEquals(2, s.pendingDraw)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals("nobody has drawn yet", 2, s.players[1].hand.size)
        assertNoOp(game, "an ordinary RED card on a pending Draw Two") { game.playCard(1, hands[1][0]) }
        assertNoOp(game, "a Wild Draw Four on a Draw Two without stackDrawFourOnDrawTwo") { game.playCard(1, hands[1][1]) }

        game.drawCard(1)
        assertEquals(4, st(game).players[1].hand.size)
        assertEquals(0, st(game).pendingDraw)
        assertEquals(2, st(game).currentPlayerIndex)
        assertInvariants(game)
    }

    /** Draw Four stacking: +4 on +4 accumulates, +2 can never be put on a +4, and no Challenge
     *  step exists when stacking is on. */
    @Test
    fun `Draw Four stacks on Draw Four but a Draw Two cannot answer it`() {
        val game = newGame(4, UnoRules(stackDraw = true))
        val d4a = card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9011)
        val d4b = card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9021)
        val d2 = card(UnoColor.BLUE, UnoRank.DRAW_TWO, 9022)
        val hands = listOf(
            listOf(d4a, card(UnoColor.BLUE, UnoRank.ONE, 9012)),
            listOf(d4b, d2),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.RED, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FOUR, 9041))
        )
        stage(game, 30, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, d4a)
        assertEquals(4, st(game).pendingDraw)
        game.chooseColor(UnoColor.BLUE)
        assertFalse(st(game).awaitingChallenge)
        assertEquals(1, st(game).currentPlayerIndex)
        assertNoOp(game, "a Draw Two on a pending Draw Four") { game.playCard(1, d2) }
        game.playCard(1, d4b)
        assertEquals(8, st(game).pendingDraw)
        game.chooseColor(UnoColor.RED)
        assertEquals(2, st(game).currentPlayerIndex)
        assertInvariants(game)
    }

    /** With stackDrawFourOnDrawTwo a +4 may answer a +2 (accumulating 2 + 4). */
    @Test
    fun `Draw Four answers Draw Two when stackDrawFourOnDrawTwo is on`() {
        val game = newGame(4, UnoRules(stackDraw = true, stackDrawFourOnDrawTwo = true))
        val d2 = card(UnoColor.RED, UnoRank.DRAW_TWO, 9011)
        val d4 = card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9021)
        val hands = listOf(
            listOf(d2, card(UnoColor.BLUE, UnoRank.ONE, 9012)),
            listOf(d4, card(UnoColor.GREEN, UnoRank.ONE, 9022)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.RED, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FOUR, 9041))
        )
        stage(game, 30, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, d2)
        game.playCard(1, d4)
        assertEquals(6, st(game).pendingDraw)
        assertTrue(st(game).awaitingColorChoice)
    }

    /** Non-stacked Wild Draw Four: pendingDraw is 4 during the color choice, the Challenge step
     *  then begins on the NEXT seat (clockwise and counter-clockwise), remembering who played it. */
    @Test
    fun `a non-stacked Draw Four opens the challenge on the next seat in either direction`() {
        for ((direction, victim) in listOf(1 to 1, -1 to 3)) {
            val game = newGame(4)
            val d4 = card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9011)
            val hands = List(4) { p -> if (p == 0) listOf(d4, card(UnoColor.BLUE, UnoRank.ONE, 9012)) else listOf(card(UnoColor.GREEN, UnoRank.ONE, 9020 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9021 + p * 10)) }
            stage(game, 30, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0, direction = direction)
            game.playCard(0, d4)
            assertTrue(st(game).awaitingColorChoice)
            assertEquals(4, st(game).pendingDraw)
            game.chooseColor(UnoColor.GREEN)
            val s = st(game)
            assertTrue(s.awaitingChallenge)
            assertEquals(victim, s.challengeVictimIndex)
            assertEquals(0, s.challengePlayedByIndex)
            assertEquals(victim, s.currentPlayerIndex)
        }
    }

    // ------------------------------------------------------------------ jump-in

    @Test
    fun `a jump-in Skip re-anchors the turn on the jumper`() {
        val game = newGame(4, UnoRules(jumpIn = true))
        val top = card(UnoColor.RED, UnoRank.SKIP, 9001)
        val jump = card(UnoColor.RED, UnoRank.SKIP, 9131)
        val hands = List(4) { p -> if (p == 2) listOf(jump, card(UnoColor.YELLOW, UnoRank.FOUR, 9132)) else listOf(card(UnoColor.GREEN, UnoRank.ONE, 9020 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9021 + p * 10)) }
        stage(game, 30, hands, top = top, current = 0)
        game.jumpIn(2, jump)
        val s = st(game)
        assertEquals("seat 3 is skipped, so play resumes at 0", 0, s.currentPlayerIndex)
        assertEquals(jump, s.discardPile.last())
        assertEquals(1, s.players[2].hand.size)
        assertInvariants(game)
    }

    @Test
    fun `jump-in rejects the current player, cards not in hand, and pending color choices`() {
        val game = newGame(4, UnoRules(jumpIn = true))
        val top = card(UnoColor.RED, UnoRank.FIVE, 9001)
        val twin = card(UnoColor.RED, UnoRank.FIVE, 9099) // exact match for the top, but held by nobody
        val hands = listOf(
            listOf(card(UnoColor.RED, UnoRank.FIVE, 9011), card(UnoColor.YELLOW, UnoRank.FOUR, 9012)), // current player holds an exact match too
            listOf(card(UnoColor.GREEN, UnoRank.ONE, 9020), card(UnoColor.YELLOW, UnoRank.TWO, 9021)),
            listOf(card(UnoColor.RED, UnoRank.FIVE, 9031), card(UnoColor.YELLOW, UnoRank.FOUR, 9032)), // a legitimate jumper
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FIVE, 9041))
        )
        stage(game, 30, hands, top = top, current = 0)
        assertNoOp(game, "the current player jumping in on their own turn") { game.jumpIn(0, hands[0][0]) }
        assertNoOp(game, "jumping in with a card id the player does not hold") { game.jumpIn(2, twin) }
        assertNoOp(game, "jumping in with a same-rank different-color card") { game.jumpIn(3, hands[3][1]) }
        val s = st(game)
        game.state.value = s.copy(awaitingColorChoice = true)
        assertNoOp(game, "jumping in during a color choice") { game.jumpIn(2, hands[2][0]) }
    }

    /** A plain Wild can be jumped onto a Wild (exact color WILD + rank WILD): the jumper becomes
     *  the actor and owns the color choice. */
    @Test
    fun `jumping a Wild onto a Wild hands the color choice to the jumper`() {
        val game = newGame(4, UnoRules(jumpIn = true))
        val jump = card(UnoColor.WILD, UnoRank.WILD, 9131)
        val hands = List(4) { p -> if (p == 2) listOf(jump, card(UnoColor.YELLOW, UnoRank.FOUR, 9132)) else listOf(card(UnoColor.GREEN, UnoRank.ONE, 9020 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9021 + p * 10)) }
        stage(game, 30, hands, top = card(UnoColor.WILD, UnoRank.WILD, 9001), current = 0, currentColor = UnoColor.GREEN)
        game.jumpIn(2, jump)
        assertTrue(st(game).awaitingColorChoice)
        assertEquals(2, st(game).currentPlayerIndex)
        game.chooseColor(UnoColor.BLUE)
        assertEquals(3, st(game).currentPlayerIndex)
        assertEquals(UnoColor.BLUE, st(game).currentColor)
    }

    // ------------------------------------------------------------------ 7-0

    private fun sevenZeroScene(direction: Int = 1, playCard: UnoCard, sizes: List<Int>): Pair<UnoGame, List<List<UnoCard>>> {
        val game = newGame(4, UnoRules(sevenZero = true))
        var id = 9100
        val hands = sizes.mapIndexed { p, n ->
            val base = List(n) { card(colors[(p + it) % 4], UnoRank.values()[1 + (it % 8)], id++) }
            if (p == 0) listOf(playCard) + base else base
        }
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0, direction = direction)
        return game to hands
    }

    /** 7 swaps the player's remaining hand with the opponent holding the FEWEST cards. */
    @Test
    fun `playing a Seven swaps hands with the smallest opponent hand`() {
        val seven = card(UnoColor.RED, UnoRank.SEVEN, 9011)
        val (game, hands) = sevenZeroScene(playCard = seven, sizes = listOf(3, 4, 2, 3))
        game.playCard(0, seven)
        val s = st(game)
        val ids = { i: Int -> s.players[i].hand.map { it.instanceId } }
        assertEquals("p0 took p2's hand", hands[2].map { it.instanceId }, ids(0))
        assertEquals("p2 took p0's post-play hand", hands[0].drop(1).map { it.instanceId }, ids(2))
        assertEquals(hands[1].map { it.instanceId }, ids(1))
        assertEquals(hands[3].map { it.instanceId }, ids(3))
        assertEquals(1, s.currentPlayerIndex)
        assertInvariants(game)
    }

    /** Ties go to the lowest seat number (minByOrNull keeps the first minimum). */
    @Test
    fun `a Seven swap tie breaks toward the lowest seat`() {
        val seven = card(UnoColor.RED, UnoRank.SEVEN, 9011)
        val (game, hands) = sevenZeroScene(playCard = seven, sizes = listOf(3, 3, 3, 3))
        game.playCard(0, seven)
        assertEquals(hands[1].map { it.instanceId }, st(game).players[0].hand.map { it.instanceId })
    }

    /** 0 rotates every hand one seat in the direction of play (both directions). Expectations are
     *  written out literally (NOT derived from the engine's index formula): clockwise, each seat
     *  passes its hand to the seat after it, so seat 1 holds original seat 0's hand, seat 2 holds
     *  seat 1's, seat 3 holds seat 2's and seat 0 holds seat 3's; counter-clockwise is the mirror
     *  image. "Original" = the hands as they stand after p0 played the Zero (p0 keeps its rest). */
    @Test
    fun `playing a Zero rotates all hands in the direction of play`() {
        for (direction in listOf(1, -1)) {
            val zero = card(UnoColor.RED, UnoRank.ZERO, 9011)
            val (game, hands) = sevenZeroScene(direction = direction, playCard = zero, sizes = listOf(3, 4, 5, 6))
            val orig = hands.mapIndexed { i, h -> if (i == 0) h.drop(1) else h }.map { h -> h.map { it.instanceId } }
            game.playCard(0, zero)
            val s = st(game)
            val actual = s.players.map { p -> p.hand.map { it.instanceId } }
            val expected = if (direction == 1) listOf(orig[3], orig[0], orig[1], orig[2]) else listOf(orig[1], orig[2], orig[3], orig[0])
            assertEquals("dir=$direction: hands after the rotation, by seat", expected, actual)
            assertEquals("dir=$direction: hand sizes prove the cards really moved", if (direction == 1) listOf(6, 3, 4, 5) else listOf(4, 5, 6, 3), s.players.map { it.hand.size })
            assertInvariants(game, "after rotate dir=$direction")
        }
    }

    /** The same cards without the house rule do nothing special. */
    @Test
    fun `Seven and Zero are ordinary cards when sevenZero is off`() {
        val game = newGame(4)
        val seven = card(UnoColor.RED, UnoRank.SEVEN, 9011)
        val hands = List(4) { p -> if (p == 0) listOf(seven, card(UnoColor.BLUE, UnoRank.ONE, 9012)) else listOf(card(UnoColor.GREEN, UnoRank.ONE, 9020 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9021 + p * 10), card(UnoColor.BLUE, UnoRank.TWO, 9022 + p * 10)) }
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, seven)
        val s = st(game)
        assertEquals(listOf(9012), s.players[0].hand.map { it.instanceId })
        assertEquals(hands[1], s.players[1].hand)
        assertEquals(hands[2], s.players[2].hand)
        assertEquals(hands[3], s.players[3].hand)
    }

    // ------------------------------------------------------------------ round / match scoring

    private class ScoringRun(val game: UnoGame, val results: MutableList<GameResult>)

    /** p0 plays its last card on a 3-player table. Opposing hands: p1 = 50 + 20 + 7 = 77, p2 = 50 + 9 + 20 = 79; round score 77 + 79 = 156. */
    private fun winNonTeam(prior: Map<String, Int>): ScoringRun {
        val results = mutableListOf<GameResult>()
        val game = newGame(3, onEnd = { results.add(it) })
        val hands = listOf(
            listOf(card(UnoColor.RED, UnoRank.THREE, 9011)),
            listOf(card(UnoColor.WILD, UnoRank.WILD, 9020), card(UnoColor.RED, UnoRank.SKIP, 9021), card(UnoColor.RED, UnoRank.SEVEN, 9022)),           // 50+20+7 = 77
            listOf(card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9030), card(UnoColor.GREEN, UnoRank.NINE, 9031), card(UnoColor.BLUE, UnoRank.DRAW_TWO, 9032)) // 50+9+20 = 79
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0, patch = { it.copy(cumulativeScores = prior) })
        game.playCard(0, hands[0][0])
        return ScoringRun(game, results)
    }

    /** Card values: numbers face value, Skip/Reverse/Draw Two 20, Wild/Wild Draw Four 50; the
     *  winner collects every opponent's hand and prior cumulative totals carry over. */
    @Test
    fun `winning a round scores every opponent hand and adds to the prior totals`() {
        val run = winNonTeam(mapOf("p0" to 100, "p1" to 50))
        val s = st(run.game)
        assertTrue(s.roundOver)
        assertFalse(s.matchOver)
        assertEquals("p0", s.winnerPlayerId)
        assertNull(s.winningTeamId)
        assertEquals(100 + 77 + 79, s.cumulativeScores["p0"])
        assertEquals("opponents' totals must not change", 50, s.cumulativeScores["p1"])
        assertNull(s.cumulativeScores["p2"])
        assertTrue(s.lastAction.contains("+156"))
        assertTrue("match is not over, so no result must have been reported", run.results.isEmpty())
    }

    /** 500 is inclusive: 344 + 156 = 500 ends the match; 343 + 156 = 499 does not. */
    @Test
    fun `the match ends at exactly 500 points and not at 499`() {
        val at499 = winNonTeam(mapOf("p0" to 343))
        assertEquals(499, st(at499.game).cumulativeScores["p0"])
        assertFalse(st(at499.game).matchOver)
        assertTrue(at499.results.isEmpty())

        val at500 = winNonTeam(mapOf("p0" to 344, "p1" to 50))
        val s = st(at500.game)
        assertEquals(500, s.cumulativeScores["p0"])
        assertTrue(s.matchOver)
        assertEquals("exactly one result reported", 1, at500.results.size)
        val byId = at500.results.single().scores.associateBy { it.playerId }
        assertEquals(500, byId["p0"]!!.score)
        assertTrue(byId["p0"]!!.isWinner)
        assertEquals(50, byId["p1"]!!.score)
        assertFalse(byId["p1"]!!.isWinner)
        assertEquals(0, byId["p2"]!!.score)
        assertFalse(byId["p2"]!!.isWinner)
    }

    private fun winTeam(prior: Map<String, Int>): ScoringRun {
        val results = mutableListOf<GameResult>()
        val game = newGame(4, UnoRules(teamPlay = true), onEnd = { results.add(it) })
        val hands = listOf(
            listOf(card(UnoColor.RED, UnoRank.THREE, 9011)),                                                   // p0 wins (team 0)
            listOf(card(UnoColor.BLUE, UnoRank.SEVEN, 9020)),                                                  // 7
            listOf(card(UnoColor.WILD, UnoRank.WILD, 9030)),                                                   // teammate: 50, must NOT count
            listOf(card(UnoColor.YELLOW, UnoRank.TWO, 9040), card(UnoColor.GREEN, UnoRank.DRAW_TWO, 9041))     // 2 + 20
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0, patch = { it.copy(cumulativeScores = prior) })
        assertEquals(listOf(0, 1, 0, 1), st(game).players.map { it.teamId })
        game.playCard(0, hands[0][0])
        return ScoringRun(game, results)
    }

    @Test
    fun `team scoring counts only the opposing team and credits both teammates`() {
        val run = winTeam(mapOf("p0" to 100, "p2" to 100, "p1" to 480))
        val s = st(run.game)
        assertTrue(s.roundOver)
        assertNull(s.winnerPlayerId)
        assertEquals(0, s.winningTeamId)
        assertEquals("7 + 22, teammate's 50 excluded", 100 + 29, s.cumulativeScores["p0"])
        assertEquals(100 + 29, s.cumulativeScores["p2"])
        assertEquals(480, s.cumulativeScores["p1"])
        assertNull(s.cumulativeScores["p3"])
        assertFalse(s.matchOver)
    }

    @Test
    fun `a team match end flags both teammates as winners and nobody else`() {
        val run = winTeam(mapOf("p0" to 480, "p2" to 480))
        assertTrue(st(run.game).matchOver)
        val byId = run.results.single().scores.associateBy { it.playerId }
        assertEquals(509, byId["p0"]!!.score)
        assertEquals(509, byId["p2"]!!.score)
        assertTrue(byId["p0"]!!.isWinner && byId["p2"]!!.isWinner)
        assertFalse(byId["p1"]!!.isWinner || byId["p3"]!!.isWinner)
    }

    /** startNextRound carries cumulative totals, bumps the round, resets round state, and deals a
     *  full fresh 108-card deck. It refuses to run mid-round or after the match ended. */
    @Test
    fun `startNextRound carries scores forward and deals a fresh round`() {
        val run = winNonTeam(mapOf("p0" to 100, "p1" to 50))
        val game = run.game
        val carried = st(game).cumulativeScores
        game.startNextRound()
        val s = st(game)
        assertEquals(2, s.roundNumber)
        assertEquals("cumulative scores must be carried into the next round", carried, s.cumulativeScores)
        assertFalse(s.roundOver)
        assertFalse(s.matchOver)
        assertNull(s.winnerPlayerId)
        assertEquals(0, s.pendingDraw)
        assertTrue(s.players.all { it.hand.size >= 7 && !it.calledUno && it.catchWindowClosesAfterPlayerIndex == null })
        assertEquals("only the opening card's effect can give seat 0 extra cards", 7, s.players[1].hand.size)
        assertEquals(7, s.players[2].hand.size)
        assertInvariants(game, "fresh round")
    }

    @Test
    fun `startNextRound is refused mid-round and after the match is over`() {
        val game = newGame(3)
        assertNoOp(game, "startNextRound mid-round") { game.startNextRound() }
        val over = winNonTeam(mapOf("p0" to 344))
        assertTrue(st(over.game).matchOver)
        assertNoOp(over.game, "startNextRound after the match ended") { over.game.startNextRound() }
    }

    // ------------------------------------------------------------------ roundOver / matchOver guards

    @Test
    fun `playCard and jumpIn change nothing once the round or the match is over`() {
        val game = newGame(4, UnoRules(jumpIn = true))
        val jump = card(UnoColor.RED, UnoRank.FIVE, 9031)
        val hands = listOf(
            listOf(card(UnoColor.GREEN, UnoRank.ONE, 9010)),
            listOf(card(UnoColor.RED, UnoRank.NINE, 9020), card(UnoColor.GREEN, UnoRank.ONE, 9021)),
            listOf(jump, card(UnoColor.YELLOW, UnoRank.FOUR, 9032)),
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FOUR, 9041))
        )
        stage(game, 30, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 1, patch = { it.copy(roundOver = true) })
        assertNoOp(game, "playCard after roundOver") { game.playCard(1, hands[1][0]) }
        assertNoOp(game, "jumpIn after roundOver") { game.jumpIn(2, jump) }
        game.state.value = st(game).copy(roundOver = false, matchOver = true)
        assertNoOp(game, "playCard after matchOver") { game.playCard(1, hands[1][0]) }
        assertNoOp(game, "jumpIn after matchOver") { game.jumpIn(2, jump) }
    }

    // ------------------------------------------------------------------ deal invariants

    @Test
    fun `a fresh deal is the full 108-card deck dealt without duplicates`() {
        repeat(20) {
            val game = newGame(4)
            val s = st(game)
            assertInvariants(game, "fresh deal")
            assertTrue(s.players.all { it.hand.size >= 7 })
            assertEquals("everyone but seat 0 holds exactly 7", listOf(7, 7, 7), s.players.drop(1).map { it.hand.size })
            assertEquals(1, s.discardPile.size)
        }
        // Deck composition sanity (also documents 108 = 25 * 4 + 8).
        val deck = UnoDeck.standardDeck()
        assertEquals(108, deck.size)
        assertEquals(108, deck.map { it.instanceId }.toSet().size)
    }

    /** dealNewRound + applyOpeningEffect: the opening flip is dealt at random, so this re-deals one
     *  4-player game in a BOUNDED loop (at least 300 deals, at most 5000, stopping once every
     *  opening kind has been seen twice) and asserts the deterministic opening state for whatever
     *  kind came up: Skip -> seat 1 is up, direction 1; Reverse -> direction -1, seat 0 is up;
     *  Draw Two -> seat 0 holds 9 cards, seat 1 is up, nothing pending; Wild -> colour choice
     *  pending, colour RED; number -> plain start. Also the official rule that a Wild Draw Four is
     *  never left as the opening card (it is buried and redrawn), and 108-card conservation each
     *  deal. Failing to see a kind in 5000 deals (P < 1e-50 for the rarest, Wild at about 3.8%
     *  per deal) is reported as a failure, so no kind can silently go untested. */
    @Test
    fun `every opening card kind gets its documented opening state and is never a Wild Draw Four`() {
        val game = newGame(4)
        val kinds = listOf("SKIP", "REVERSE", "DRAW_TWO", "WILD", "NUMBER")
        val seen = kinds.associateWith { 0 }.toMutableMap()
        var deals = 0
        while (deals < 5000 && (deals < 300 || kinds.any { seen.getValue(it) < 2 })) {
            game.startMatch()
            deals++
            val s = st(game)
            val top = s.topCard
            fun msg(what: String) = "deal #$deals, opening ${top.color} ${top.rank}: $what | state=$s"
            assertNotEquals(msg("a Wild Draw Four must never open the round"), UnoRank.WILD_DRAW_FOUR, top.rank)
            assertInvariants(game, "deal #$deals")
            assertEquals(msg("opening card is the whole discard pile"), 1, s.discardPile.size)
            assertEquals(msg("pending draw is served immediately, never left pending"), 0, s.pendingDraw)
            assertFalse(msg("no challenge at the start"), s.awaitingChallenge)
            assertFalse(msg("round not over"), s.roundOver || s.matchOver)
            assertEquals(msg("first round"), 1, s.roundNumber)
            assertTrue(msg("nobody starts with a UNO call or a catch window"), s.players.all { !it.calledUno && it.catchWindowClosesAfterPlayerIndex == null })
            val sizes = s.players.map { it.hand.size }
            val kind = when (top.rank) {
                UnoRank.SKIP -> {
                    assertEquals(msg("Skip: seat 0 is skipped, seat 1 is up"), 1, s.currentPlayerIndex)
                    assertEquals(msg("Skip keeps clockwise play"), 1, s.direction)
                    assertEquals(msg("Skip deals nothing extra"), listOf(7, 7, 7, 7), sizes)
                    assertEquals(msg("Skip: colour follows the card"), top.color, s.currentColor)
                    assertFalse(msg("Skip: no colour choice"), s.awaitingColorChoice)
                    "SKIP"
                }
                UnoRank.REVERSE -> {
                    assertEquals(msg("Reverse: direction flips"), -1, s.direction)
                    assertEquals(msg("Reverse: seat 0 stays up (4 players)"), 0, s.currentPlayerIndex)
                    assertEquals(msg("Reverse deals nothing extra"), listOf(7, 7, 7, 7), sizes)
                    assertEquals(msg("Reverse: colour follows the card"), top.color, s.currentColor)
                    assertFalse(msg("Reverse: no colour choice"), s.awaitingColorChoice)
                    "REVERSE"
                }
                UnoRank.DRAW_TWO -> {
                    assertEquals(msg("Draw Two: seat 0 draws two and is skipped"), listOf(9, 7, 7, 7), sizes)
                    assertEquals(msg("Draw Two: seat 1 is up"), 1, s.currentPlayerIndex)
                    assertEquals(msg("Draw Two keeps clockwise play"), 1, s.direction)
                    assertEquals(msg("Draw Two: draw pile mirrors the two extra cards"), 108 - 1 - 30, s.drawPileSize)
                    assertEquals(msg("Draw Two: colour follows the card"), top.color, s.currentColor)
                    assertFalse(msg("Draw Two: no colour choice"), s.awaitingColorChoice)
                    "DRAW_TWO"
                }
                UnoRank.WILD -> {
                    assertTrue(msg("Wild: a colour choice is pending"), s.awaitingColorChoice)
                    assertEquals(msg("Wild: provisional colour is RED"), UnoColor.RED, s.currentColor)
                    assertEquals(msg("Wild: seat 0 is up"), 0, s.currentPlayerIndex)
                    assertEquals(msg("Wild keeps clockwise play"), 1, s.direction)
                    assertEquals(msg("Wild deals nothing extra"), listOf(7, 7, 7, 7), sizes)
                    "WILD"
                }
                else -> {
                    assertEquals(msg("number card: seat 0 starts"), 0, s.currentPlayerIndex)
                    assertEquals(msg("number card: clockwise"), 1, s.direction)
                    assertEquals(msg("number card deals nothing extra"), listOf(7, 7, 7, 7), sizes)
                    assertEquals(msg("number card: colour follows the card"), top.color, s.currentColor)
                    assertFalse(msg("number card: no colour choice"), s.awaitingColorChoice)
                    "NUMBER"
                }
            }
            seen[kind] = seen.getValue(kind) + 1
        }
        for (k in kinds) assertTrue("opening kind $k was never dealt in $deals deals (counts: $seen)", seen.getValue(k) >= 2)
    }

    /** teamId is only honoured in team play: roster teamIds are forced to -1 otherwise. */
    @Test
    fun `team ids are discarded outside team play`() {
        val game = UnoGame()
        game.rules = UnoRules(teamPlay = false)
        game.init(
            GameContext(
                PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                listOf(PlayerInfo("a", "A", teamId = 0), PlayerInfo("b", "B", teamId = 1)),
                0, LocalPassAndPlayTransport()
            )
        )
        game.startMatch()
        assertEquals(listOf(-1, -1), st(game).players.map { it.teamId })
    }

    // ------------------------------------------------------------------ long conservation runs

    private fun randomCard(rnd: Random, id: Int): UnoCard {
        val x = rnd.nextInt(100)
        return when {
            x < 4 -> UnoCard(UnoColor.WILD, UnoRank.WILD, id)
            x < 8 -> UnoCard(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, id)
            else -> UnoCard(colors[rnd.nextInt(4)], UnoRank.values()[rnd.nextInt(13)], id)
        }
    }

    private class FuzzResult(val plies: Int, val reshuffles: Int, val roundEnded: Boolean, val crossChecked: Boolean, val sevenZeroWinSkipped: Boolean)

    /** Re-throws an [AssertionError] with the loop position and the full [UnoState] appended, so an
     *  UNSEEDED failure (the engine's deck shuffle is not seeded) can still be replayed by hand. */
    private inline fun <T> withDump(game: UnoGame, where: () -> String, block: () -> T): T =
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("${where()}\n  ${e.message}\n  UnoState at failure: ${st(game)}", e)
        }

    /** One random playout from a small-pile staged position. Every ply goes through the public API
     *  only, and after EVERY ply (and after each optional UNO call/catch) conservation and
     *  duplicate checks run. The driver's decisions come from `Random(seed)`, but the engine's own
     *  shuffles are unseeded, so the seed alone does NOT reproduce a game: failure messages carry
     *  the loop position and the complete UnoState instead.
     *
     *  Scoring cross-check: the state just before the engine call that ended the round is
     *  snapshotted (every hand). When the round ends, the recorded score is compared with those
     *  snapshotted opponent hands (plus any penalty cards the winning card itself dealt, e.g. a
     *  Draw Two victim), and no opponent may have lost a card since. The one exception is a win by a
     *  Seven or Zero under sevenZero: that is UNO-BUG-A (swap/rotate before scoring, now fixed),
     *  covered by its dedicated regression tests and counted here as skipped rather than asserted. */
    private fun fuzz(rules: UnoRules, playerCount: Int, seed: Long, pileSize: Int, label: String, handSize: Int = 5, maxPlies: Int = 1500): FuzzResult {
        val rnd = Random(seed)
        val game = newGame(playerCount, rules)
        val total = 108 - pileSize
        val cards = List(total) { randomCard(rnd, 9000 + it) }
        val handTotal = handSize * playerCount
        val hands = List(playerCount) { p -> cards.subList(p * handSize, (p + 1) * handSize) }
        val top = card(UnoColor.RED, UnoRank.FIVE, 9000 + total - 1)
        stage(game, pileSize, hands, top, current = 0, underTop = cards.subList(handTotal, total - 1))

        var plies = 0
        var reshuffles = 0
        var lastDiscard = st(game).discardPile.size
        var preEnd: UnoState? = null
        fun where() = "playout $label: driverSeed=$seed (seeds the test driver only; the engine's deck shuffle is unseeded, so this does NOT reproduce the game) ply=$plies rules=$rules"
        // Every state-changing engine call in the driver goes through here so the state before the
        // round-ending call is known.
        val track: (() -> Unit) -> Unit = { block ->
            val before = st(game)
            block()
            if (preEnd == null && st(game).roundOver && !before.roundOver) preEnd = before
        }
        while (plies < maxPlies) {
            val s = st(game)
            if (s.roundOver || s.matchOver) break
            plies++

            if (rules.jumpIn && !s.awaitingColorChoice && !s.awaitingChallenge && rnd.nextInt(6) == 0) {
                val j = (s.currentPlayerIndex + 1 + rnd.nextInt(playerCount - 1)) % playerCount
                for (c in s.players[j].hand) {
                    val b = st(game)
                    track { game.jumpIn(j, c) }
                    if (st(game) != b) break
                }
            }
            val s2 = st(game)
            if (s2.roundOver || s2.matchOver) { withDump(game, ::where) { assertInvariants(game, "after the ending jump-in") }; break }
            stepOnce(game, rnd, track)
            withDump(game, ::where) {
                if (st(game) == s2) fail("engine stalled: a driver step changed nothing in state $s2")

                val a = st(game)
                if (a.discardPile.size < lastDiscard) reshuffles++
                lastDiscard = a.discardPile.size
                assertInvariants(game, "after the ply")
                assertTrue("seat in range", a.currentPlayerIndex in 0 until playerCount)
                assertTrue("direction", a.direction == 1 || a.direction == -1)
                assertTrue("pendingDraw only rides a draw card", a.pendingDraw == 0 || a.topCard.rank == UnoRank.DRAW_TWO || a.topCard.rank == UnoRank.WILD_DRAW_FOUR)
                assertFalse("color choice and challenge cannot be pending together", a.awaitingColorChoice && a.awaitingChallenge)

                if (!a.roundOver) {
                    for (i in 0 until playerCount) {
                        if (st(game).players[i].hand.size != 1) continue
                        when (rnd.nextInt(3)) {
                            0 -> game.callUno(i)
                            1 -> game.catchUnoFailure((i + 1 + rnd.nextInt(playerCount - 1)) % playerCount, i)
                        }
                    }
                    assertInvariants(game, "after UNO call/catch")
                }
            }
        }

        val end = st(game)
        var crossChecked = false
        var skipped = false
        if (end.roundOver) {
            withDump(game, ::where) {
                val pre = checkNotNull(preEnd) { "round is over but no driver call was recorded as ending it" }
                val winCard = end.topCard
                val winnerIdx = pre.players.indexOfFirst { p -> p.hand.any { it.instanceId == winCard.instanceId } }
                assertTrue("the card on top at round end (${winCard.color} ${winCard.rank}) was in nobody's hand before the ending call", winnerIdx >= 0)
                if (rules.sevenZero && (winCard.rank == UnoRank.SEVEN || winCard.rank == UnoRank.ZERO)) {
                    skipped = true // UNO-BUG-A territory; see the dedicated Seven/Zero win tests
                } else {
                    val winner = pre.players[winnerIdx]
                    assertEquals("the winner held exactly one card before the ending play", 1, winner.hand.size)
                    assertTrue("the winner's hand must be empty at round end", end.players[winnerIdx].hand.isEmpty())
                    assertEquals("exactly one player emptied their hand", 1, end.players.count { it.hand.isEmpty() })
                    var expected = 0
                    for (i in pre.players.indices) {
                        val isOpponent = if (rules.teamPlay) pre.players[i].teamId != winner.teamId else i != winnerIdx
                        if (!isOpponent) continue
                        val endHand = end.players[i].hand
                        val endIds = endHand.map { it.instanceId }.toSet()
                        assertTrue("opponent seat $i lost cards between the ending play and scoring: before=${pre.players[i].hand} end=$endHand", pre.players[i].hand.all { it.instanceId in endIds })
                        expected += endHand.sumOf { it.scoreValue } // pre hand + penalty cards the winning card dealt
                    }
                    if (rules.teamPlay) {
                        for (p in end.players.filter { it.teamId == winner.teamId }) assertEquals("score credited to ${p.playerId}", expected, end.cumulativeScores[p.playerId])
                        for (p in end.players.filter { it.teamId != winner.teamId }) assertNull("${p.playerId} must not be credited", end.cumulativeScores[p.playerId])
                    } else {
                        assertEquals("score credited to ${winner.playerId}", expected, end.cumulativeScores[winner.playerId])
                        assertEquals("only the winner is credited", 1, end.cumulativeScores.size)
                    }
                    crossChecked = true
                }
            }
        }
        return FuzzResult(plies, reshuffles, end.roundOver, crossChecked, skipped)
    }

    /** Drives one legal-or-not action from a seeded RNG. Illegal plays are simply ignored by the
     *  engine, so trying cards in random order finds a legal one without duplicating rule logic. */
    private fun stepOnce(game: UnoGame, rnd: Random, track: (() -> Unit) -> Unit) {
        val s = st(game)
        if (s.awaitingColorChoice) { track { game.chooseColor(colors[rnd.nextInt(4)]) }; return }
        if (s.awaitingChallenge) { track { game.resolveChallenge(rnd.nextBoolean()) }; return }
        val cur = s.currentPlayerIndex
        val wantDraw = !s.awaitingDrawDecision && s.pendingDraw == 0 && rnd.nextInt(10) == 0
        if (!wantDraw) {
            for (c in s.players[cur].hand.shuffled(rnd)) {
                val before = st(game)
                track { game.playCard(cur, c) }
                if (st(game) != before) return
            }
        }
        if (s.awaitingDrawDecision) game.keepDrawnCard(cur) else game.drawCard(cur)
    }

    private fun runFuzzConfig(rules: UnoRules, players: Int, seeds: List<Long>, pileSize: Int = 6) {
        var reshuffles = 0
        var ended = 0
        var crossChecked = 0
        var skipped = 0
        for ((index, seed) in seeds.withIndex()) {
            val r = fuzz(rules, players, seed, pileSize + (seed % 4).toInt(), label = "${index + 1}/${seeds.size}") // vary the starting pile so different draw sizes straddle the reshuffle
            reshuffles += r.reshuffles
            if (r.roundEnded) ended++
            if (r.crossChecked) crossChecked++
            if (r.sevenZeroWinSkipped) skipped++
        }
        assertTrue("no reshuffle was ever exercised across $seeds -- staging is broken", reshuffles > 0)
        assertTrue("no round ever finished across $seeds within the ply budget", ended > 0)
        assertEquals("every finished round is either scoring-cross-checked or an UNO-BUG-A Seven/Zero win (ended=$ended)", ended, crossChecked + skipped)
        if (!rules.sevenZero) assertEquals("without sevenZero no win may be skipped", 0, skipped)
        assertTrue("the scoring cross-check never ran across $seeds (ended=$ended, skipped Seven/Zero wins=$skipped)", crossChecked > 0)
    }

    @Test
    fun `classic 4-player random playouts conserve the deck through many reshuffles`() =
        runFuzzConfig(UnoRules(), 4, (11L..18L).toList())

    @Test
    fun `team play 4-player random playouts conserve the deck and score correctly`() =
        runFuzzConfig(UnoRules(teamPlay = true), 4, (21L..28L).toList())

    @Test
    fun `stacking random playouts conserve the deck when stacks are served across reshuffles`() =
        runFuzzConfig(UnoRules(stackDraw = true, stackDrawFourOnDrawTwo = true), 4, (31L..38L).toList(), pileSize = 3)

    @Test
    fun `seven-zero random playouts conserve the deck and never duplicate a hand`() =
        runFuzzConfig(UnoRules(sevenZero = true), 3, (41L..48L).toList())

    @Test
    fun `jump-in random playouts conserve the deck`() =
        runFuzzConfig(UnoRules(jumpIn = true), 4, (51L..58L).toList())

    @Test
    fun `two-player random playouts conserve the deck with Reverse acting as Skip`() =
        runFuzzConfig(UnoRules(), 2, (61L..68L).toList())

    @Test
    fun `every house rule at once with a 5-player table conserves the deck`() =
        runFuzzConfig(
            UnoRules(stackDraw = true, stackDrawFourOnDrawTwo = true, sevenZero = true, jumpIn = true, forcePlayDrawnCard = true, teamPlay = true),
            5, (71L..78L).toList(), pileSize = 4
        )

    // ------------------------------------------------------------------ regressions for engine bugs found by this suite (now fixed)

    /** UNO-BUG-A (fixed): under sevenZero, playing your LAST card as a Seven used to run the swap
     *  anyway, handing the winner the opponent's whole hand and leaving that opponent with the
     *  empty one. checkWinAfterPlay then summed the "other" hands AFTER the swap, so the
     *  swapped-away hand was excluded from the round score and the winner was left holding cards.
     *  Expected: winner scores every opponent's hand as it stood: 59 + 8 = 67 (was 0 + 8). */
    @Test
    fun `winning with a Seven under sevenZero scores the opponents as they stood`() {
        val game = newGame(3, UnoRules(sevenZero = true))
        val seven = card(UnoColor.RED, UnoRank.SEVEN, 9011)
        val hands = listOf(
            listOf(seven),
            listOf(card(UnoColor.BLUE, UnoRank.NINE, 9020), card(UnoColor.WILD, UnoRank.WILD, 9021)),                                             // 59, fewest cards -> swap target
            listOf(card(UnoColor.GREEN, UnoRank.ONE, 9030), card(UnoColor.GREEN, UnoRank.TWO, 9031), card(UnoColor.YELLOW, UnoRank.FIVE, 9032)) // 8
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, seven)
        val s = st(game)
        assertTrue(s.roundOver)
        assertEquals(67, s.cumulativeScores["p0"])
        assertTrue("the winner must not be left holding cards", s.players[0].hand.isEmpty())
    }

    /** UNO-BUG-A (fixed, Zero side): a winning Zero's rotation used to hand the winner the
     *  previous seat's cards and pass the winner's empty hand on. Expected 5 + 7 = 12 (was 5: the
     *  hand the winner ended up holding, 7, was excluded). */
    @Test
    fun `winning with a Zero under sevenZero scores the opponents as they stood`() {
        val game = newGame(3, UnoRules(sevenZero = true))
        val zero = card(UnoColor.RED, UnoRank.ZERO, 9011)
        val hands = listOf(
            listOf(zero),
            listOf(card(UnoColor.BLUE, UnoRank.FIVE, 9020)),                                          // 5
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9030), card(UnoColor.GREEN, UnoRank.FOUR, 9031)) // 7
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, zero)
        assertEquals(12, st(game).cumulativeScores["p0"])
    }

    /** UNO-BUG-B (fixed): a Wild/Wild Draw Four played as the LAST card leaves awaitingColorChoice
     *  true on a finished round, and chooseColor()/resolveChallenge() had no roundOver guard, so
     *  a (networked) ChooseColor intent still mutated a finished round: the turn moved and, for a
     *  Draw Four, a Challenge opened and the victim could be made to draw after scoring. */
    @Test
    fun `a finished round cannot be mutated by a late chooseColor`() {
        val game = newGame(4)
        val wild = card(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, 9011)
        val hands = List(4) { p -> if (p == 0) listOf(wild) else listOf(card(UnoColor.GREEN, UnoRank.ONE, 9020 + p * 10), card(UnoColor.YELLOW, UnoRank.TWO, 9021 + p * 10)) }
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, wild)
        assertTrue(st(game).roundOver)
        assertNoOp(game, "chooseColor after the round ended") { game.chooseColor(UnoColor.BLUE) }
    }

    /** UNO-BUG-B (fixed, catch side): a UNO catch used to be accepted after the round ended, so a
     *  (networked) CatchUnoFailure intent handed a finished round's player two extra cards. */
    @Test
    fun `a finished round cannot be mutated by a late UNO catch`() {
        val game = newGame(4)
        val last = card(UnoColor.RED, UnoRank.THREE, 9011)
        val hands = listOf(
            listOf(last),
            listOf(card(UnoColor.GREEN, UnoRank.ONE, 9020), card(UnoColor.YELLOW, UnoRank.TWO, 9021)),
            listOf(card(UnoColor.BLUE, UnoRank.NINE, 9030)), // one card, never called UNO
            listOf(card(UnoColor.GREEN, UnoRank.THREE, 9040), card(UnoColor.YELLOW, UnoRank.FOUR, 9041))
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.playCard(0, last)
        assertTrue(st(game).roundOver)
        assertNoOp(game, "a UNO catch after the round ended") { game.catchUnoFailure(3, 2) }
    }

    /** UNO-BUG-C (fixed): calledUno used to survive a 7-0 hand swap/rotation. p1 called UNO holding
     *  one card; p0's Seven swaps hands with them, so p1 now holds a bigger hand but kept
     *  calledUno=true and, on reaching one card later, could not be caught. UnoPlayerState says the
     *  flag is "reset on draw/new card". */
    @Test
    fun `a Seven swap clears the stale UNO call of a player who received a new hand`() {
        val game = newGame(3, UnoRules(sevenZero = true))
        val seven = card(UnoColor.RED, UnoRank.SEVEN, 9011)
        val hands = listOf(
            listOf(seven, card(UnoColor.GREEN, UnoRank.ONE, 9012), card(UnoColor.GREEN, UnoRank.TWO, 9013), card(UnoColor.GREEN, UnoRank.THREE, 9014)),
            listOf(card(UnoColor.BLUE, UnoRank.NINE, 9020)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.YELLOW, UnoRank.TWO, 9031))
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.callUno(1)
        game.playCard(0, seven)
        assertEquals("p1 must now hold p0's three cards", 3, st(game).players[1].hand.size)
        assertFalse(st(game).players[1].calledUno)
    }

    // ------------------------------------------------------------------ fix coverage added with the UNO-BUG-A..E fixes

    /** UNO-BUG-C (Zero side): the rotation hands every seat its neighbour's cards, so a stale UNO
     *  call must not survive on a seat that received a new hand. */
    @Test
    fun `a Zero rotation clears the stale UNO call of a player who received a new hand`() {
        val game = newGame(3, UnoRules(sevenZero = true))
        val zero = card(UnoColor.RED, UnoRank.ZERO, 9011)
        val hands = listOf(
            listOf(zero, card(UnoColor.GREEN, UnoRank.ONE, 9012), card(UnoColor.GREEN, UnoRank.TWO, 9013)),
            listOf(card(UnoColor.BLUE, UnoRank.NINE, 9020)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.YELLOW, UnoRank.TWO, 9031))
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        game.callUno(1)
        game.playCard(0, zero)
        assertEquals("p1 must now hold p0's two remaining cards", 2, st(game).players[1].hand.size)
        assertFalse(st(game).players[1].calledUno)
    }

    /** UNO-BUG-B (challenge side): resolveChallenge must not mutate a round that already ended.
     *  Staged directly (the engine no longer lets a finished round reach awaitingChallenge). */
    @Test
    fun `a finished round cannot be mutated by a late resolveChallenge`() {
        for (accept in listOf(true, false)) {
            val scene = stageChallenge(20, listOf(card(UnoColor.RED, UnoRank.ONE, 9013)))
            scene.game.state.value = st(scene.game).copy(roundOver = true)
            assertNoOp(scene.game, "resolveChallenge(accept=$accept) after the round ended") { scene.game.resolveChallenge(accept) }
        }
    }

    /** UNO-BUG-C (catch-window side, Seven): a seat whose catch window was stamped for its OLD
     *  hand (and has since expired: the window closes after p0's turn and p0 is the one playing)
     *  receives a new one-card hand in the swap. Clearing only calledUno left the stale window in
     *  place, so the seat now holding a genuine uncalled one-card hand could never be caught. */
    @Test
    fun `a Seven swap re-opens the catch window for a seat that received a new one-card hand`() {
        val game = newGame(3, UnoRules(sevenZero = true))
        val seven = card(UnoColor.RED, UnoRank.SEVEN, 9011)
        val hands = listOf(
            listOf(seven, card(UnoColor.RED, UnoRank.ONE, 9012)),
            listOf(card(UnoColor.BLUE, UnoRank.NINE, 9020)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.YELLOW, UnoRank.TWO, 9031))
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0) { s ->
            s.copy(players = s.players.mapIndexed { i, p -> if (i == 1) p.copy(catchWindowClosesAfterPlayerIndex = 0) else p })
        }
        game.playCard(0, seven)
        assertEquals("p1 must now hold p0's single remaining card", listOf(9012), st(game).players[1].hand.map { it.instanceId })
        assertEquals("the turn moved on to p1", 1, st(game).currentPlayerIndex)
        game.catchUnoFailure(2, 1)
        assertEquals("p1 holds a fresh uncalled one-card hand and must be catchable (draws 2)", 3, st(game).players[1].hand.size)
        assertInvariants(game, "after the catch")
    }

    /** UNO-BUG-C (catch-window side, Zero): same stale-window hole via the rotation. With p0 down to
     *  one card after playing the Zero, direction +1 hands p1 that single card. */
    @Test
    fun `a Zero rotation re-opens the catch window for a seat that received a new one-card hand`() {
        val game = newGame(3, UnoRules(sevenZero = true))
        val zero = card(UnoColor.RED, UnoRank.ZERO, 9011)
        val hands = listOf(
            listOf(zero, card(UnoColor.RED, UnoRank.ONE, 9012)),
            listOf(card(UnoColor.BLUE, UnoRank.NINE, 9020)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.YELLOW, UnoRank.TWO, 9031))
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0) { s ->
            s.copy(players = s.players.mapIndexed { i, p -> if (i == 1) p.copy(catchWindowClosesAfterPlayerIndex = 0) else p })
        }
        game.playCard(0, zero)
        assertEquals("p1 must now hold p0's single remaining card", listOf(9012), st(game).players[1].hand.map { it.instanceId })
        assertEquals("the turn moved on to p1", 1, st(game).currentPlayerIndex)
        game.catchUnoFailure(0, 1)
        assertEquals("p1 holds a fresh uncalled one-card hand and must be catchable (draws 2)", 3, st(game).players[1].hand.size)
        assertInvariants(game, "after the catch")
    }

    /** A CatchUnoFailure whose seat indexes are outside the table (a forged guest intent carries
     *  arbitrary ints) must be dropped, not throw on the host. Every direction of out-of-range. */
    @Test
    fun `catchUnoFailure with an out of range seat is a no-op instead of a crash`() {
        val game = newGame(4)
        val hands = listOf(
            listOf(card(UnoColor.GREEN, UnoRank.ONE, 9012), card(UnoColor.GREEN, UnoRank.TWO, 9013)),
            listOf(card(UnoColor.BLUE, UnoRank.NINE, 9020)),
            listOf(card(UnoColor.YELLOW, UnoRank.ONE, 9030), card(UnoColor.YELLOW, UnoRank.TWO, 9031)),
            listOf(card(UnoColor.RED, UnoRank.THREE, 9040), card(UnoColor.RED, UnoRank.FOUR, 9041))
        )
        stage(game, 20, hands, top = card(UnoColor.RED, UnoRank.FIVE, 9001), current = 0)
        for ((accuser, target) in listOf(0 to 99, 99 to 1, -1 to 1, 0 to -1, 4 to 1, 0 to 4)) {
            try {
                assertNoOp(game, "catchUnoFailure($accuser, $target)") { game.catchUnoFailure(accuser, target) }
            } catch (e: IndexOutOfBoundsException) {
                fail("catchUnoFailure($accuser, $target) must be ignored, but threw $e")
            }
        }
    }
}
