package com.gamesuite.games.uno

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UnoBot.chooseMove] takes a [CpuDifficulty] and must never hand back a
 * card that isn't actually legal to play on the given [UnoState] — EASY,
 * MEDIUM and HARD all filter through the same private `isLegalPlay` first
 * (UnoBot.kt) before doing anything difficulty-specific, but HARD's extra
 * "reserve the Wild Draw Four" filtering (see [UnoBot]'s KDoc) is exactly
 * the kind of extra logic that could accidentally widen the candidate pool
 * back out to an illegal card, so this file exercises all three tiers
 * against several hand/state fixtures — an open turn, a stacked +2, a
 * stacked +4-on-+2, a forced-draw (no legal card) hand, and a
 * single-legal-Wild-Draw-Four hand that specifically stresses HARD's
 * reserve-filtering edge case.
 *
 * `isLegalPlay` is private in both UnoBot and UnoGame (same body in both —
 * see UnoGame.kt's own `isLegalPlay`), so it's reimplemented here verbatim
 * as [isLegalPlayForTest] rather than reached into via reflection.
 */
class UnoBotTest {

    /** Verbatim reimplementation of UnoBot/UnoGame's private `isLegalPlay` — see this file's class KDoc. */
    private fun isLegalPlayForTest(card: UnoCard, state: UnoState, rules: UnoRules): Boolean {
        if (state.pendingDraw > 0) {
            val top = state.topCard
            return when {
                top.rank == UnoRank.DRAW_TWO -> card.rank == UnoRank.DRAW_TWO ||
                    (rules.stackDrawFourOnDrawTwo && card.rank == UnoRank.WILD_DRAW_FOUR)
                top.rank == UnoRank.WILD_DRAW_FOUR -> card.rank == UnoRank.WILD_DRAW_FOUR
                else -> false
            }
        }
        return card.isWild || card.color == state.currentColor || card.rank == state.topCard.rank
    }

    private fun buildState(
        topCard: UnoCard,
        currentColor: UnoColor = topCard.color,
        pendingDraw: Int = 0,
        otherHandSizes: List<Int> = listOf(5, 5)
    ): UnoState {
        val players = listOf(UnoPlayerState("self", "Self", isBot = true, teamId = -1, hand = emptyList())) +
            otherHandSizes.mapIndexed { i, size ->
                UnoPlayerState("opp$i", "Opp$i", isBot = true, teamId = -1, hand = List(size) { topCard })
            }
        return UnoState(
            players = players,
            drawPileSize = 50,
            discardPile = listOf(topCard),
            currentColor = currentColor,
            currentPlayerIndex = 0,
            direction = 1,
            pendingDraw = pendingDraw,
            awaitingColorChoice = false,
            lastAction = "Fixture"
        )
    }

    /** For every difficulty, asserts chooseMove either returns null or a card that is both in [hand] and legal per [isLegalPlayForTest]. */
    private fun assertEveryDifficultyChoosesOnlyLegalCards(hand: List<UnoCard>, state: UnoState, rules: UnoRules = UnoRules()) {
        for (difficulty in CpuDifficulty.entries) {
            val chosen = UnoBot.chooseMove(hand, state, rules, difficulty)
            if (chosen != null) {
                assertTrue(
                    "$difficulty returned $chosen which isn't in the hand it was given: $hand",
                    hand.any { it.instanceId == chosen.instanceId }
                )
                assertTrue(
                    "$difficulty returned $chosen which fails the legality check (topCard=${state.topCard}, currentColor=${state.currentColor}, pendingDraw=${state.pendingDraw})",
                    isLegalPlayForTest(chosen, state, rules)
                )
            }
        }
    }

    @Test
    fun `open turn - mixed legal and illegal cards in hand`() {
        val top = UnoCard(UnoColor.RED, UnoRank.FIVE, instanceId = 100)
        val hand = listOf(
            UnoCard(UnoColor.RED, UnoRank.THREE, instanceId = 1),       // legal: color match
            UnoCard(UnoColor.BLUE, UnoRank.FIVE, instanceId = 2),       // legal: rank match
            UnoCard(UnoColor.GREEN, UnoRank.SEVEN, instanceId = 3),     // illegal: neither
            UnoCard(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, instanceId = 4), // legal: wild
            UnoCard(UnoColor.BLUE, UnoRank.TWO, instanceId = 5)         // illegal: neither
        )
        val state = buildState(topCard = top, currentColor = UnoColor.RED)

        assertEveryDifficultyChoosesOnlyLegalCards(hand, state)
    }

    @Test
    fun `stacked plus-two - only another Draw Two is legal when stacking a Wild Draw Four is disallowed`() {
        val top = UnoCard(UnoColor.RED, UnoRank.DRAW_TWO, instanceId = 100)
        val hand = listOf(
            UnoCard(UnoColor.BLUE, UnoRank.DRAW_TWO, instanceId = 1),          // legal: matches the stack
            UnoCard(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, instanceId = 2),    // illegal here: stacking a +4 onto a +2 is off
            UnoCard(UnoColor.WILD, UnoRank.WILD, instanceId = 3),              // illegal: plain Wild never answers a pending draw
            UnoCard(UnoColor.RED, UnoRank.NINE, instanceId = 4)                // illegal: unrelated number card
        )
        val rules = UnoRules(stackDraw = true, stackDrawFourOnDrawTwo = false)
        val state = buildState(topCard = top, currentColor = UnoColor.RED, pendingDraw = 2)

        assertEveryDifficultyChoosesOnlyLegalCards(hand, state, rules)
    }

    @Test
    fun `stacked plus-two - a Wild Draw Four is legal once stacking a +4 onto a +2 is allowed`() {
        val top = UnoCard(UnoColor.RED, UnoRank.DRAW_TWO, instanceId = 100)
        val hand = listOf(
            UnoCard(UnoColor.BLUE, UnoRank.DRAW_TWO, instanceId = 1),
            UnoCard(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, instanceId = 2),
            UnoCard(UnoColor.RED, UnoRank.NINE, instanceId = 3)
        )
        val rules = UnoRules(stackDraw = true, stackDrawFourOnDrawTwo = true)
        val state = buildState(topCard = top, currentColor = UnoColor.RED, pendingDraw = 2)

        assertEveryDifficultyChoosesOnlyLegalCards(hand, state, rules)
    }

    @Test
    fun `stacked Wild Draw Four - only another Wild Draw Four is legal`() {
        // A Wild Draw Four's own card color is always WILD in the real deck (see UnoDeck.standardDeck());
        // the chosen color lives in state.currentColor separately.
        val top = UnoCard(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, instanceId = 100)
        val hand = listOf(
            UnoCard(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, instanceId = 1),
            UnoCard(UnoColor.BLUE, UnoRank.DRAW_TWO, instanceId = 2),
            UnoCard(UnoColor.BLUE, UnoRank.THREE, instanceId = 3)
        )
        val rules = UnoRules(stackDraw = true)
        val state = buildState(topCard = top, currentColor = UnoColor.BLUE, pendingDraw = 4)

        assertEveryDifficultyChoosesOnlyLegalCards(hand, state, rules)
    }

    @Test
    fun `no legal card in hand - every difficulty draws instead of playing`() {
        val top = UnoCard(UnoColor.RED, UnoRank.FIVE, instanceId = 100)
        val hand = listOf(
            UnoCard(UnoColor.BLUE, UnoRank.THREE, instanceId = 1),
            UnoCard(UnoColor.GREEN, UnoRank.NINE, instanceId = 2),
            UnoCard(UnoColor.YELLOW, UnoRank.SIX, instanceId = 3)
        )
        val state = buildState(topCard = top, currentColor = UnoColor.RED)

        for (difficulty in CpuDifficulty.entries) {
            assertNull(
                "$difficulty should have nothing legal to play and return null",
                UnoBot.chooseMove(hand, state, UnoRules(), difficulty)
            )
        }
    }

    @Test
    fun `HARD reserve-Wild-Draw-Four filtering never leaves it with no legal candidate`() {
        // Only the Wild Draw Four is legal here (the others match neither
        // color nor rank) — HARD's "reserve it unless it's the only legal
        // card" branch must fall back to it, not filter the pool empty.
        val top = UnoCard(UnoColor.BLUE, UnoRank.SEVEN, instanceId = 100)
        val hand = listOf(
            UnoCard(UnoColor.WILD, UnoRank.WILD_DRAW_FOUR, instanceId = 1),
            UnoCard(UnoColor.GREEN, UnoRank.THREE, instanceId = 2),
            UnoCard(UnoColor.RED, UnoRank.TWO, instanceId = 3)
        )
        // A high other-hand size means HARD's "next opponent about to go out" escape hatch
        // doesn't kick in either — the fallback has to come from the reserve logic itself.
        val state = buildState(topCard = top, currentColor = UnoColor.BLUE, otherHandSizes = listOf(6, 6))

        assertEveryDifficultyChoosesOnlyLegalCards(hand, state)
    }
}
