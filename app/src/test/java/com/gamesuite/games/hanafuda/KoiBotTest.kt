package com.gamesuite.games.hanafuda

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The computer opponent: legal, blind to hidden cards, and stronger at higher levels. */
class KoiBotTest {
    /** Plays one whole round with a bot per seat, each seeing only its own redacted view. */
    private fun playRound(r0: KoiRound, bots: List<KoiBot>): KoiRound {
        var r = r0
        var guard = 0
        while (!r.over) {
            val seat = r.turn
            val view = r.redactedFor(seat)
            r = when (r.phase) {
                KoiPhase.PLAY_HAND -> KoiRules.playHand(r, bots[seat].choosePlay(view, seat)) ?: error("illegal bot play")
                KoiPhase.DRAW_READY -> KoiRules.draw(r)!!
                KoiPhase.CHOOSE_DRAW_MATCH -> KoiRules.chooseDrawMatch(r, bots[seat].chooseDrawMatch(view, seat)) ?: error("illegal choice")
                KoiPhase.DECIDE_AFTER_HAND, KoiPhase.DECIDE_AFTER_DRAW -> KoiRules.decide(r, bots[seat].decide(view, seat))!!
                KoiPhase.ROUND_OVER -> r
            }
            assertTrue("round did not end", ++guard < 300)
        }
        return r
    }

    private fun bots(a: CpuDifficulty, b: CpuDifficulty, seed: Int) = listOf(KoiBot(a, Random(seed)), KoiBot(b, Random(seed + 1)))

    @Test fun everyLevelOnlyPlaysLegalMovesThroughWholeRounds() {
        for (level in CpuDifficulty.entries) {
            repeat(60) { g ->
                val r = playRound(KoiRules.deal(Random(g), g % 2, if (g % 2 == 0) YakuSet.BASIC else YakuSet.FULL), bots(level, level, g))
                assertTrue(KoiRules.isConsistent(r))
            }
        }
    }

    @Test fun theBotChoosesTheSameMoveFromARedactedViewAsFromTheFullTable() {
        // Hidden cards are not used: a full table and its redaction give the bot the same view of what it may use.
        repeat(40) { g ->
            val r = KoiRules.deal(Random(500 + g), 0, YakuSet.BASIC)
            val view = r.redactedFor(0)
            val a = KoiBot(CpuDifficulty.HARD, Random(1)).choosePlay(view, 0)
            val b = KoiBot(CpuDifficulty.HARD, Random(1)).choosePlay(r.redactedFor(0).redactedFor(0), 0)
            assertEquals(a, b)
        }
    }

    @Test fun changingTheHiddenCardsNeverChangesTheBotsChoice() {
        // Two tables that differ only in the opponent's hand and the deck order look identical to the bot.
        repeat(40) { g ->
            val base = KoiRules.deal(Random(700 + g), 0, YakuSet.BASIC)
            val hiddenPool = (base.hands[1] + base.deck)
            val shuffled = hiddenPool.shuffled(Random(g))
            val other = base.copy(hands = listOf(base.hands[0], shuffled.take(8).sorted()), deck = shuffled.drop(8))
            assertTrue(KoiRules.isConsistent(other))
            for (level in CpuDifficulty.entries) {
                val a = KoiBot(level, Random(3)).choosePlay(base.redactedFor(0), 0)
                val b = KoiBot(level, Random(3)).choosePlay(other.redactedFor(0), 0)
                assertEquals("level $level game $g", a, b)
            }
        }
    }

    @Test fun theBotTakesABrightWhenOneIsOnOffer() {
        val crane = HanafudaCards.idOf(HanaSpecial.CRANE)
        val pinePlain = 2 // pine plain
        val r = KoiRound(
            YakuSet.BASIC, 0, 0, KoiPhase.PLAY_HAND, deck = listOf(44, 40),
            field = listOf(crane, 20), hands = listOf(listOf(pinePlain, 21), listOf(30, 31)),
            captured = listOf(emptyList(), emptyList())
        )
        for (level in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            val play = KoiBot(level, Random(1)).choosePlay(r.redactedFor(0), 0)
            assertEquals(pinePlain, play.handCard)
        }
    }

    @Test fun theBotPicksTheMoreValuableOfTwoFieldMatches() {
        val boar = HanafudaCards.idOf(HanaSpecial.BOAR)
        val r = KoiRound(
            YakuSet.BASIC, 0, 0, KoiPhase.CHOOSE_DRAW_MATCH, deck = emptyList(),
            field = listOf(boar, boar + 2, 3), hands = listOf(listOf(0), listOf(8)),
            captured = listOf(emptyList(), emptyList()), drawn = boar + 3
        )
        for (level in listOf(CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            assertEquals(boar, KoiBot(level, Random(2)).chooseDrawMatch(r, 0))
        }
    }

    @Test fun theBotAlwaysCallsShobuWithNoCardsLeft() {
        val r = KoiRound(
            YakuSet.BASIC, 0, 0, KoiPhase.DECIDE_AFTER_DRAW, deck = emptyList(), field = emptyList(),
            hands = listOf(emptyList(), emptyList()), captured = listOf(HanafudaCards.brightIds, emptyList())
        )
        for (level in CpuDifficulty.entries) assertEquals(false, KoiBot(level, Random(1)).decide(r, 0))
    }

    @Test fun aBigHandIsBankedByEveryLevel() {
        val strong = KoiRound(
            YakuSet.BASIC, 0, 0, KoiPhase.DECIDE_AFTER_HAND, deck = listOf(1, 2), field = listOf(10),
            hands = listOf(listOf(20, 24, 28, 32), listOf(36, 40, 44, 12)),
            captured = listOf(HanafudaCards.brightIds - HanafudaCards.rainManId, emptyList())
        )
        for (level in CpuDifficulty.entries) assertEquals("level $level", false, KoiBot(level, Random(5)).decide(strong, 0))
    }

    @Test fun aSmallHandEarlyInTheRoundIsPlayedOnByMediumAndHard() {
        val redRibbons = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED }.map { it.id }
        val fiveRibbons = redRibbons + HanafudaCards.all.first { it.ribbon == HanaRibbon.BLUE }.id // Tanzaku: 1 point
        val weak = KoiRound(
            YakuSet.BASIC, 0, 0, KoiPhase.DECIDE_AFTER_HAND, deck = listOf(1, 2), field = listOf(10),
            hands = listOf(listOf(20, 24, 28, 32, 36, 40), listOf(44, 12, 13, 14, 15, 16)),
            captured = listOf(fiveRibbons, emptyList())
        )
        assertEquals(1, weak.yakuTotal(0))
        assertEquals(true, KoiBot(CpuDifficulty.MEDIUM, Random(1)).decide(weak, 0))
        assertEquals(true, KoiBot(CpuDifficulty.HARD, Random(1)).decide(weak, 0))
    }

    @Test fun hintsAreLegalPlaysForTheViewer() {
        repeat(30) { g ->
            val r = KoiRules.deal(Random(900 + g), 0, YakuSet.BASIC)
            val hint = KoiBot.hint(r.redactedFor(0), 0)
            assertTrue(hint in KoiRules.legalPlays(r))
        }
    }

    @Test fun higherLevelsBeatLowerLevelsOverManyRounds() {
        fun points(a: CpuDifficulty, b: CpuDifficulty): Pair<Int, Int> {
            var pa = 0
            var pb = 0
            for (g in 0 until 400) {
                // Alternate who plays first and which seat has which level.
                val aSeat = g % 2
                val parent = (g / 2) % 2
                val bots = if (aSeat == 0) bots(a, b, g) else bots(b, a, g)
                val r = playRound(KoiRules.deal(Random(10_000 + g), parent, YakuSet.BASIC), bots)
                if (r.winner >= 0) {
                    if (r.winner == aSeat) pa += r.points else pb += r.points
                }
            }
            return pa to pb
        }
        val hardVsEasy = points(CpuDifficulty.HARD, CpuDifficulty.EASY)
        assertTrue("hard ${hardVsEasy.first} vs easy ${hardVsEasy.second}", hardVsEasy.first > hardVsEasy.second)
        val medVsEasy = points(CpuDifficulty.MEDIUM, CpuDifficulty.EASY)
        assertTrue("medium ${medVsEasy.first} vs easy ${medVsEasy.second}", medVsEasy.first > medVsEasy.second)
    }

    @Test fun theLevelsPlayDifferentlyFromEachOther() {
        // Over many tables the three levels disagree on at least some opening plays.
        var easyVsHard = 0
        repeat(80) { g ->
            val r = KoiRules.deal(Random(300 + g), 0, YakuSet.BASIC)
            val view = r.redactedFor(0)
            if (KoiBot(CpuDifficulty.EASY, Random(g)).choosePlay(view, 0) != KoiBot(CpuDifficulty.HARD, Random(g)).choosePlay(view, 0)) easyVsHard++
        }
        assertNotEquals(0, easyVsHard)
    }
}
