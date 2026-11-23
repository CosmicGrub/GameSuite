package com.gamesuite.games.hanafuda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The Koi-Koi round rules on hand-built tables. */
class KoiRulesTest {
    private fun id(s: HanaSpecial) = HanafudaCards.idOf(s)

    /** Card [slot] (0..3) of [month] (0..11). */
    private fun c(month: Int, slot: Int) = month * 4 + slot

    /** A round with chosen hands, field and deck (top = last) and everything else captured by nobody. */
    private fun table(
        hand0: List<Int>,
        hand1: List<Int>,
        field: List<Int>,
        deck: List<Int>,
        captured0: List<Int> = emptyList(),
        captured1: List<Int> = emptyList(),
        turn: Int = 0,
        phase: KoiPhase = KoiPhase.PLAY_HAND,
        set: YakuSet = YakuSet.BASIC
    ): KoiRound = KoiRound(
        yakuSet = set, parent = 0, turn = turn, phase = phase,
        deck = deck, field = field, hands = listOf(hand0, hand1), captured = listOf(captured0, captured1)
    )

    // ---- dealing --------------------------------------------------------------------------------

    @Test fun aDealGivesEightEightEightAndTwentyFour() {
        val r = KoiRules.deal(Random(1), 0, YakuSet.BASIC)
        assertEquals(8, r.hands[0].size)
        assertEquals(8, r.hands[1].size)
        assertEquals(8, r.field.size)
        assertEquals(24, r.deck.size)
        assertTrue(KoiRules.isConsistent(r))
        assertEquals(0, r.turn)
        assertEquals(KoiPhase.PLAY_HAND, r.phase)
    }

    @Test fun theParentMovesFirst() {
        assertEquals(1, KoiRules.deal(Random(2), 1, YakuSet.BASIC).turn)
        assertEquals(0, KoiRules.deal(Random(2), 0, YakuSet.BASIC).turn)
    }

    @Test fun theSameRandomDealsTheSameRound() {
        assertEquals(KoiRules.deal(Random(7), 0, YakuSet.BASIC), KoiRules.deal(Random(7), 0, YakuSet.BASIC))
    }

    @Test fun aFieldWithAllFourCardsOfAMonthIsDealtAgain() {
        for (seed in 0 until 400) {
            val r = KoiRules.deal(Random(seed), 0, YakuSet.BASIC)
            assertTrue(r.field.groupBy { HanafudaCards.monthOf(it) }.none { it.value.size == 4 })
        }
    }

    @Test fun dealFromRejectsABadDeck() {
        try {
            KoiRules.dealFrom((0 until 47).toList(), 0, YakuSet.BASIC)
            assertTrue("should have thrown", false)
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---- dealt specials (full set) ----------------------------------------------------------------

    @Test fun fourCardsOfAMonthInHandIsASpecialWorthSix() {
        val order = (0 until 48).toList()
        // parent hand = month 0 (4 cards) + 4 others; child hand: eight spread cards.
        val parent = listOf(0, 1, 2, 3, 8, 12, 16, 20)
        val child = listOf(24, 28, 32, 36, 40, 44, 9, 13)
        val rest = order - parent.toSet() - child.toSet()
        val r = KoiRules.dealFrom(parent + child + rest, 0, YakuSet.FULL)
        assertEquals(KoiPhase.ROUND_OVER, r.phase)
        assertEquals(KoiEnd.DEALT_FOUR_OF_A_MONTH, r.end)
        assertEquals(0, r.winner)
        assertEquals(6, r.points)
        assertTrue(KoiRules.isConsistent(r))
    }

    @Test fun fourPairsInHandIsASpecialToo() {
        val parent = listOf(0, 1, 4, 5, 8, 9, 12, 13)
        val child = listOf(16, 20, 24, 28, 32, 36, 40, 44)
        val rest = (0 until 48).toList() - parent.toSet() - child.toSet()
        val r2 = KoiRules.dealFrom(parent + child + rest, 0, YakuSet.FULL)
        assertEquals(KoiEnd.DEALT_FOUR_PAIRS, r2.end)
        assertEquals(6, r2.points)
    }

    @Test fun bothPlayersDealtASpecialIsADrawWithNoPoints() {
        val parent = listOf(0, 1, 2, 3, 8, 12, 16, 20)
        val child = listOf(4, 5, 6, 7, 24, 28, 32, 36)
        val rest = (0 until 48).toList() - parent.toSet() - child.toSet()
        val r = KoiRules.dealFrom(parent + child + rest, 0, YakuSet.FULL)
        assertEquals(KoiEnd.DEALT_BOTH, r.end)
        assertEquals(-1, r.winner)
        assertEquals(0, r.points)
    }

    @Test fun dealtSpecialsAreIgnoredInTheBasicSet() {
        val parent = listOf(0, 1, 2, 3, 8, 12, 16, 20)
        val child = listOf(24, 28, 32, 36, 40, 44, 9, 13)
        val rest = (0 until 48).toList() - parent.toSet() - child.toSet()
        val r = KoiRules.dealFrom(parent + child + rest, 0, YakuSet.BASIC)
        assertEquals(KoiPhase.PLAY_HAND, r.phase)
    }

    @Test fun dealtSpecialDetection() {
        assertEquals(KoiEnd.NONE, KoiRules.dealtSpecial(listOf(0, 4, 8, 12, 16, 20, 24, 28)))
        assertEquals(KoiEnd.NONE, KoiRules.dealtSpecial(listOf(0, 1, 2, 4, 5, 8, 12, 16))) // three of a month is not four
        assertEquals(KoiEnd.NONE, KoiRules.dealtSpecial(listOf(0, 1, 4, 5, 8, 9, 12, 16))) // only three pairs
    }

    // ---- playing a hand card ----------------------------------------------------------------------

    @Test fun matchingTheSingleFieldCardCapturesBoth() {
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(0, 3), c(7, 2)), listOf(c(9, 2)))
        val n = KoiRules.playHand(r, KoiPlay(c(0, 2)))!!
        assertEquals(listOf(c(0, 3), c(0, 2)), n.captured[0])
        assertEquals(listOf(c(7, 2)), n.field)
        assertTrue(n.hands[0].isEmpty())
        assertEquals(KoiPhase.DRAW_READY, n.phase)
        assertEquals(0, n.turn)
    }

    @Test fun namingTheSingleMatchExplicitlyIsAccepted() {
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(0, 3)), listOf(c(9, 2)))
        assertNotNull(KoiRules.playHand(r, KoiPlay(c(0, 2), c(0, 3))))
        assertNull(KoiRules.playHand(r, KoiPlay(c(0, 2), c(7, 2)))) // not a match
    }

    @Test fun noMatchLeavesTheCardOnTheField() {
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(7, 2)), listOf(c(9, 2)))
        val n = KoiRules.playHand(r, KoiPlay(c(0, 2)))!!
        assertEquals(listOf(c(7, 2), c(0, 2)), n.field)
        assertTrue(n.captured[0].isEmpty())
        assertEquals(KoiPhase.DRAW_READY, n.phase)
    }

    @Test fun aDiscardCannotNameAFieldCard() {
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(7, 2)), listOf(c(9, 2)))
        assertNull(KoiRules.playHand(r, KoiPlay(c(0, 2), c(7, 2))))
    }

    @Test fun twoMatchesRequireAChoiceOfExactlyOneOfThem() {
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(0, 3), c(0, 1), c(7, 2)), listOf(c(9, 2)))
        assertNull(KoiRules.playHand(r, KoiPlay(c(0, 2))))
        assertNull(KoiRules.playHand(r, KoiPlay(c(0, 2), c(7, 2))))
        val n = KoiRules.playHand(r, KoiPlay(c(0, 2), c(0, 1)))!!
        assertEquals(listOf(c(0, 1), c(0, 2)), n.captured[0])
        assertEquals(listOf(c(0, 3), c(7, 2)), n.field)
    }

    @Test fun threeMatchesAreAllTakenByTheFourthCard() {
        val r = table(listOf(c(0, 0)), listOf(c(5, 2)), listOf(c(0, 1), c(0, 2), c(0, 3), c(7, 2)), listOf(c(9, 2)))
        val n = KoiRules.playHand(r, KoiPlay(c(0, 0)))!!
        assertEquals(4, n.captured[0].size)
        assertEquals(listOf(c(7, 2)), n.field)
    }

    @Test fun legalPlaysListsEveryChoiceForADoubleMatch() {
        val r = table(listOf(c(0, 2), c(7, 0)), listOf(c(5, 2)), listOf(c(0, 3), c(0, 1)), listOf(c(9, 2)))
        val plays = KoiRules.legalPlays(r)
        assertEquals(setOf(KoiPlay(c(0, 2), c(0, 3)), KoiPlay(c(0, 2), c(0, 1)), KoiPlay(c(7, 0))), plays.toSet())
        // Every listed play is accepted by playHand.
        plays.forEach { assertNotNull(KoiRules.playHand(r, it)) }
    }

    @Test fun aCardNotInYourHandCannotBePlayed() {
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(7, 2)), listOf(c(9, 2)))
        assertNull(KoiRules.playHand(r, KoiPlay(c(5, 2)))) // the other player's card
        assertNull(KoiRules.playHand(r, KoiPlay(c(11, 0))))
    }

    @Test fun playingIsOnlyAllowedInThePlayHandPhase() {
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(7, 2)), listOf(c(9, 2)), phase = KoiPhase.DRAW_READY)
        assertNull(KoiRules.playHand(r, KoiPlay(c(0, 2))))
        assertEquals(emptyList<KoiPlay>(), KoiRules.legalPlays(r))
    }

    // ---- turning up the deck -----------------------------------------------------------------------

    @Test fun theTurnedUpCardMatchesOrStays() {
        val base = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(7, 2)), listOf(c(3, 3)), phase = KoiPhase.DRAW_READY)
        val stays = KoiRules.draw(base)!!
        assertEquals(listOf(c(7, 2), c(3, 3)), stays.field)
        assertEquals(KoiPhase.PLAY_HAND, stays.phase)
        assertEquals(1, stays.turn)
        assertTrue(stays.deck.isEmpty())

        val matches = KoiRules.draw(base.copy(deck = listOf(c(7, 3))))!!
        assertEquals(listOf(c(7, 2), c(7, 3)), matches.captured[0])
        assertTrue(matches.field.isEmpty())
    }

    @Test fun aTurnedUpCardWithTwoMatchesWaitsForAChoice() {
        val r = table(emptyList(), listOf(c(5, 2)), listOf(c(7, 1), c(7, 2), c(4, 2)), listOf(c(7, 3)), phase = KoiPhase.DRAW_READY, turn = 0)
        val n = KoiRules.draw(r)!!
        assertEquals(KoiPhase.CHOOSE_DRAW_MATCH, n.phase)
        assertEquals(c(7, 3), n.drawn)
        assertEquals(setOf(c(7, 1), c(7, 2)), KoiRules.drawChoices(n).toSet())
        assertNull(KoiRules.chooseDrawMatch(n, c(4, 2)))
        val done = KoiRules.chooseDrawMatch(n, c(7, 2))!!
        assertEquals(listOf(c(7, 2), c(7, 3)), done.captured[0])
        assertEquals(-1, done.drawn)
    }

    @Test fun aTurnedUpCardWithThreeMatchesTakesAllFour() {
        val r = table(emptyList(), listOf(c(5, 2)), listOf(c(7, 0), c(7, 1), c(7, 2), c(4, 2)), listOf(c(7, 3)), phase = KoiPhase.DRAW_READY)
        val n = KoiRules.draw(r)!!
        assertEquals(4, n.captured[0].size)
        assertEquals(listOf(c(4, 2)), n.field)
    }

    @Test fun drawingNeedsTheDrawPhaseAndACard() {
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(7, 2)), listOf(c(3, 3)))
        assertNull(KoiRules.draw(r))
        assertNull(KoiRules.draw(r.copy(phase = KoiPhase.DRAW_READY, deck = emptyList())))
    }

    // ---- koi-koi / shobu ---------------------------------------------------------------------------

    @Test fun makingANewYakuAsksForADecisionBeforeTheDeckCard() {
        val ribbons = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id } // pine, plum, cherry ribbons
        // Player 0 already holds pine + plum ribbons and plays the cherry ribbon from hand onto a cherry card.
        val r = table(
            hand0 = listOf(ribbons[2], c(11, 1)), hand1 = listOf(c(5, 2)),
            field = listOf(c(2, 0), c(7, 2)), deck = listOf(c(9, 2), c(10, 3)),
            captured0 = listOf(ribbons[0], ribbons[1])
        )
        val n = KoiRules.playHand(r, KoiPlay(ribbons[2]))!!
        assertEquals(KoiPhase.DECIDE_AFTER_HAND, n.phase)
        assertEquals(5, n.yakuTotal(0))
        assertEquals(setOf(Yaku.RED_POETRY_RIBBONS), n.yakuOf(0).map { it.yaku }.toSet())
    }

    @Test fun koiKoiAfterTheHandPlayContinuesToTheDraw() {
        val ribbons = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id }
        val r = table(
            hand0 = listOf(ribbons[2], c(11, 1)), hand1 = listOf(c(5, 2)),
            field = listOf(c(2, 0), c(7, 2)), deck = listOf(c(9, 2), c(10, 3)),
            captured0 = listOf(ribbons[0], ribbons[1])
        )
        val decide = KoiRules.playHand(r, KoiPlay(ribbons[2]))!!
        val koi = KoiRules.decide(decide, koiKoi = true)!!
        assertEquals(KoiPhase.DRAW_READY, koi.phase)
        assertEquals(listOf(1, 0), koi.koiCalls)
        assertEquals(0, koi.lastKoi)
        assertEquals(5, koi.decisionBase[0])
        // The draw does not offer another decision unless the total rises again.
        val drawn = KoiRules.draw(koi)!!
        assertEquals(KoiPhase.PLAY_HAND, drawn.phase)
        assertEquals(1, drawn.turn)
    }

    @Test fun anotherPointOfTheSameYakuAsksAgain() {
        val poetry = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id }
        val blueRibbon = c(5, 1)
        // Player 0 already called koi-koi at 5 points. Turning up the peony plain takes the blue ribbon: 4 ribbons = 6 points.
        val r = table(
            hand0 = listOf(c(11, 1)), hand1 = listOf(c(10, 3)),
            field = listOf(blueRibbon, c(7, 2)), deck = listOf(c(3, 3), c(5, 2)),
            captured0 = poetry, phase = KoiPhase.DRAW_READY
        ).copy(decisionBase = listOf(5, 0), koiCalls = listOf(1, 0), lastKoi = 0)
        val n = KoiRules.draw(r)!!
        assertEquals(KoiPhase.DECIDE_AFTER_DRAW, n.phase)
        assertEquals(6, n.yakuTotal(0))
        // Calling koi-koi again ends the turn, raises the base to 6 and passes play on.
        val again = KoiRules.decide(n, koiKoi = true)!!
        assertEquals(listOf(2, 0), again.koiCalls)
        assertEquals(6, again.decisionBase[0])
        assertEquals(1, again.turn)
        assertEquals(KoiPhase.PLAY_HAND, again.phase)
    }

    @Test fun aDrawThatDoesNotRaiseTheTotalDoesNotAskAgain() {
        val poetry = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id }
        val r = table(
            hand0 = listOf(c(11, 1)), hand1 = listOf(c(10, 3)),
            field = listOf(c(7, 2)), deck = listOf(c(3, 3)),
            captured0 = poetry, phase = KoiPhase.DRAW_READY
        ).copy(decisionBase = listOf(5, 0), koiCalls = listOf(1, 0), lastKoi = 0)
        assertEquals(KoiPhase.PLAY_HAND, KoiRules.draw(r)!!.phase)
    }

    @Test fun shobuEndsTheRoundAndScoresTheTotal() {
        val ribbons = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id }
        val r = table(
            hand0 = listOf(ribbons[2], c(11, 1)), hand1 = listOf(c(5, 2)),
            field = listOf(c(2, 0), c(7, 2)), deck = listOf(c(9, 2)),
            captured0 = listOf(ribbons[0], ribbons[1])
        )
        val d = KoiRules.playHand(r, KoiPlay(ribbons[2]))!!
        val done = KoiRules.decide(d, koiKoi = false)!!
        assertEquals(KoiPhase.ROUND_OVER, done.phase)
        assertEquals(KoiEnd.SHOBU, done.end)
        assertEquals(0, done.winner)
        assertEquals(5, done.points)
    }

    @Test fun sevenPointsOrMoreAreDoubled() {
        val r = table(emptyList(), emptyList(), emptyList(), emptyList(), captured0 = HanafudaCards.brightIds - HanafudaCards.rainManId)
        // Four brights = 8 -> 16.
        assertEquals(16, KoiRules.scoreFor(r, 0))
        // Three brights = 5 stays 5.
        val three = r.copy(captured = listOf(listOf(id(HanaSpecial.CRANE), id(HanaSpecial.CURTAIN), id(HanaSpecial.MOON)), emptyList()))
        assertEquals(5, KoiRules.scoreFor(three, 0))
    }

    @Test fun anOpponentsKoiKoiDoublesYourScore() {
        val three = table(
            emptyList(), emptyList(), emptyList(), emptyList(),
            captured0 = listOf(id(HanaSpecial.CRANE), id(HanaSpecial.CURTAIN), id(HanaSpecial.MOON))
        ).copy(koiCalls = listOf(0, 1))
        assertEquals(10, KoiRules.scoreFor(three, 0))
        // Both bonuses stack: 8 -> 16 -> 32.
        val four = three.copy(captured = listOf(HanafudaCards.brightIds - HanafudaCards.rainManId, emptyList()))
        assertEquals(32, KoiRules.scoreFor(four, 0))
    }

    @Test fun decidingOutsideADecisionPhaseIsRefused() {
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(7, 2)), listOf(c(3, 3)))
        assertNull(KoiRules.decide(r, true))
        assertNull(KoiRules.decide(r, false))
    }

    // ---- the end of a round --------------------------------------------------------------------------

    @Test fun whenBothHandsRunOutWithoutAnyYakuTheRoundIsDrawn() {
        // One card each, no combination possible.
        val r = table(listOf(c(0, 2)), listOf(c(5, 2)), listOf(c(7, 2), c(8, 2)), listOf(c(3, 3), c(4, 3)))
        val a = KoiRules.draw(KoiRules.playHand(r, KoiPlay(c(0, 2)))!!)!!
        assertEquals(1, a.turn)
        val b = KoiRules.draw(KoiRules.playHand(a, KoiPlay(c(5, 2)))!!)!!
        assertEquals(KoiPhase.ROUND_OVER, b.phase)
        assertEquals(KoiEnd.DRAW, b.end)
        assertEquals(-1, b.winner)
        assertEquals(0, b.points)
    }

    @Test fun theLastKoiKoiCallerKeepsTheirPointsWhenTheCardsRunOut() {
        val ribbons = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id }
        // Player 0 has called koi-koi with 5 points; both players have one card left and nothing happens.
        val r = table(
            hand0 = listOf(c(11, 1)), hand1 = listOf(c(10, 3)),
            field = listOf(c(7, 2), c(8, 2)), deck = listOf(c(3, 3), c(4, 3)),
            captured0 = ribbons
        ).copy(koiCalls = listOf(1, 0), lastKoi = 0, decisionBase = listOf(5, 0))
        val a = KoiRules.draw(KoiRules.playHand(r, KoiPlay(c(11, 1)))!!)!!
        val b = KoiRules.draw(KoiRules.playHand(a, KoiPlay(c(10, 3)))!!)!!
        assertEquals(KoiEnd.EXHAUSTED_KOI, b.end)
        assertEquals(0, b.winner)
        assertEquals(5, b.points)
    }

    @Test fun theOpponentCanStillWinAfterAKoiKoi() {
        val ribbons = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id }
        val blue = HanafudaCards.all.filter { it.ribbon == HanaRibbon.BLUE }.map { it.id }
        val r = table(
            hand0 = listOf(c(11, 1)), hand1 = listOf(blue[2]),
            field = listOf(c(9, 0), c(7, 2)), deck = listOf(c(3, 3), c(4, 3)),
            captured0 = ribbons, captured1 = blue.take(2)
        ).copy(koiCalls = listOf(1, 0), lastKoi = 0, decisionBase = listOf(5, 0))
        val a = KoiRules.draw(KoiRules.playHand(r, KoiPlay(c(11, 1)))!!)!!
        val d = KoiRules.playHand(a, KoiPlay(blue[2]))!!
        assertEquals(KoiPhase.DECIDE_AFTER_HAND, d.phase)
        val done = KoiRules.decide(d, koiKoi = false)!!
        assertEquals(1, done.winner)
        assertEquals(10, done.points) // aotan 5, doubled because player 0 called koi-koi
    }

    @Test fun aFinishedRoundAcceptsNoFurtherMoves() {
        val over = table(emptyList(), emptyList(), emptyList(), emptyList()).copy(phase = KoiPhase.ROUND_OVER, end = KoiEnd.DRAW)
        assertNull(KoiRules.playHand(over, KoiPlay(0)))
        assertNull(KoiRules.draw(over))
        assertNull(KoiRules.decide(over, true))
        assertNull(KoiRules.chooseDrawMatch(over, 0))
    }

    // ---- redaction ------------------------------------------------------------------------------------

    @Test fun aRedactedViewHidesTheOtherHandAndTheWholeDeck() {
        val r = KoiRules.deal(Random(11), 0, YakuSet.BASIC)
        val v = r.redactedFor(0)
        assertEquals(r.hands[0], v.hands[0])
        assertEquals(8, v.hands[1].size)
        assertTrue(v.hands[1].all { it < 0 })
        assertEquals(24, v.deck.size)
        assertTrue(v.deck.all { it < 0 })
        assertEquals(r.field, v.field)
        assertTrue(v.isRedacted)
        assertFalse(r.isRedacted)
        // Placeholders are unique so they can key animations.
        assertEquals(v.hands[1].size + v.deck.size, (v.hands[1] + v.deck).toSet().size)
        // None of the hidden real ids is present anywhere in the view.
        val visible = (v.hands[0] + v.field + v.captured.flatten()).toSet()
        (r.hands[1] + r.deck).forEach { assertFalse(it in visible) }
    }

    @Test fun redactionFromTheOtherSeatHidesTheOtherHand() {
        val r = KoiRules.deal(Random(12), 0, YakuSet.BASIC)
        val v = r.redactedFor(1)
        assertEquals(r.hands[1], v.hands[1])
        assertTrue(v.hands[0].all { it < 0 })
    }

    // ---- invariants over random play ---------------------------------------------------------------------

    @Test fun randomLegalPlayConservesCardsAndAlwaysTerminates() {
        val rng = Random(2024)
        repeat(300) { game ->
            var r = KoiRules.deal(Random(game), game % 2, if (game % 3 == 0) YakuSet.FULL else YakuSet.BASIC)
            var steps = 0
            while (!r.over) {
                assertTrue("game $game step $steps inconsistent", KoiRules.isConsistent(r))
                r = when (r.phase) {
                    KoiPhase.PLAY_HAND -> KoiRules.playHand(r, KoiRules.legalPlays(r).random(rng))!!
                    KoiPhase.DRAW_READY -> KoiRules.draw(r)!!
                    KoiPhase.CHOOSE_DRAW_MATCH -> KoiRules.chooseDrawMatch(r, KoiRules.drawChoices(r).random(rng))!!
                    KoiPhase.DECIDE_AFTER_HAND, KoiPhase.DECIDE_AFTER_DRAW -> KoiRules.decide(r, rng.nextBoolean())!!
                    KoiPhase.ROUND_OVER -> r
                }
                steps++
                assertTrue("game $game did not end", steps < 200)
            }
            assertTrue(KoiRules.isConsistent(r))
            if (r.winner >= 0) assertTrue(r.points > 0 || r.end == KoiEnd.NONE)
            // Winner points match the scoring rules for shobu rounds.
            if (r.end == KoiEnd.SHOBU) assertEquals(KoiRules.scoreFor(r, r.winner), r.points)
        }
    }

    @Test fun everyRoundUsesExactlySixteenTurnsUnlessAShobuEndsItEarly() {
        repeat(100) { game ->
            var r = KoiRules.deal(Random(1000 + game), 0, YakuSet.BASIC)
            var plays = 0
            val rng = Random(game)
            while (!r.over) {
                r = when (r.phase) {
                    KoiPhase.PLAY_HAND -> { plays++; KoiRules.playHand(r, KoiRules.legalPlays(r).random(rng))!! }
                    KoiPhase.DRAW_READY -> KoiRules.draw(r)!!
                    KoiPhase.CHOOSE_DRAW_MATCH -> KoiRules.chooseDrawMatch(r, KoiRules.drawChoices(r).first())!!
                    else -> KoiRules.decide(r, true)!! // always keep playing
                }
            }
            assertEquals(16, plays)
        }
    }
}
