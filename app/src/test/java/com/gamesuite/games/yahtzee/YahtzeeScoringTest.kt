package com.gamesuite.games.yahtzee

import com.gamesuite.games.yahtzee.YahtzeeCategory.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YahtzeeScoringTest {

    private fun card(vararg filled: Pair<YahtzeeCategory, Int>, bonuses: Int = 0): YahtzeeScorecard {
        var c = YahtzeeScorecard(yahtzeeBonuses = bonuses)
        for ((cat, pts) in filled) c = c.filled(cat, pts)
        return c
    }

    private fun s(cat: YahtzeeCategory, vararg dice: Int) = YahtzeeScoring.score(cat, dice.toList())

    @Test
    fun `upper boxes count only their own face`() {
        val dice = intArrayOf(1, 3, 3, 5, 6)
        assertEquals(1, s(ACES, *dice))
        assertEquals(0, s(TWOS, *dice))
        assertEquals(6, s(THREES, *dice))
        assertEquals(0, s(FOURS, *dice))
        assertEquals(5, s(FIVES, *dice))
        assertEquals(6, s(SIXES, *dice))
    }

    @Test
    fun `three of a kind scores the sum of all dice`() {
        assertEquals(17, s(THREE_OF_A_KIND, 4, 4, 4, 2, 3))
        assertEquals(0, s(THREE_OF_A_KIND, 4, 4, 2, 2, 3))
    }

    @Test
    fun `four of a kind scores the sum and needs four`() {
        assertEquals(23, s(FOUR_OF_A_KIND, 5, 5, 5, 5, 3))
        assertEquals(0, s(FOUR_OF_A_KIND, 5, 5, 5, 3, 3))
    }

    @Test
    fun `a Yahtzee also satisfies three and four of a kind`() {
        assertEquals(15, s(THREE_OF_A_KIND, 3, 3, 3, 3, 3))
        assertEquals(15, s(FOUR_OF_A_KIND, 3, 3, 3, 3, 3))
    }

    @Test
    fun `full house is a pair plus three of a kind`() {
        assertEquals(25, s(FULL_HOUSE, 2, 2, 5, 5, 5))
        assertEquals(25, s(FULL_HOUSE, 6, 1, 6, 1, 1))
    }

    @Test
    fun `five of a kind is not a natural full house`() {
        assertEquals(0, s(FULL_HOUSE, 4, 4, 4, 4, 4))
    }

    @Test
    fun `four of a kind plus one and two pair are not full houses`() {
        assertEquals(0, s(FULL_HOUSE, 4, 4, 4, 4, 1))
        assertEquals(0, s(FULL_HOUSE, 4, 4, 2, 2, 1))
    }

    @Test
    fun `small straight accepts all three runs and duplicates`() {
        assertEquals(30, s(SMALL_STRAIGHT, 1, 2, 3, 4, 6))
        assertEquals(30, s(SMALL_STRAIGHT, 2, 3, 4, 5, 5))
        assertEquals(30, s(SMALL_STRAIGHT, 6, 3, 4, 5, 3))
        assertEquals(30, s(SMALL_STRAIGHT, 1, 2, 2, 3, 4))
    }

    @Test
    fun `small straight needs four in a row`() {
        assertEquals(0, s(SMALL_STRAIGHT, 1, 2, 3, 5, 6))
        assertEquals(0, s(SMALL_STRAIGHT, 1, 1, 3, 4, 6))
    }

    @Test
    fun `large straight is exactly one to five or two to six`() {
        assertEquals(40, s(LARGE_STRAIGHT, 1, 2, 3, 4, 5))
        assertEquals(40, s(LARGE_STRAIGHT, 6, 5, 4, 3, 2))
        assertEquals(0, s(LARGE_STRAIGHT, 1, 2, 3, 4, 6))
        assertEquals(0, s(LARGE_STRAIGHT, 1, 2, 3, 4, 4))
    }

    @Test
    fun `a large straight also counts as a small straight`() {
        assertEquals(30, s(SMALL_STRAIGHT, 2, 3, 4, 5, 6))
    }

    @Test
    fun `yahtzee is fifty for five alike and zero otherwise`() {
        assertEquals(50, s(YAHTZEE, 6, 6, 6, 6, 6))
        assertEquals(0, s(YAHTZEE, 6, 6, 6, 6, 5))
    }

    @Test
    fun `chance is the sum of the dice`() {
        assertEquals(19, s(CHANCE, 1, 2, 5, 5, 6))
    }

    @Test
    fun `joker gives full values to the fixed boxes`() {
        val dice = listOf(2, 2, 2, 2, 2)
        assertEquals(25, YahtzeeScoring.score(FULL_HOUSE, dice, joker = true))
        assertEquals(30, YahtzeeScoring.score(SMALL_STRAIGHT, dice, joker = true))
        assertEquals(40, YahtzeeScoring.score(LARGE_STRAIGHT, dice, joker = true))
        assertEquals(10, YahtzeeScoring.score(THREE_OF_A_KIND, dice, joker = true))
        assertEquals(10, YahtzeeScoring.score(CHANCE, dice, joker = true))
    }

    @Test
    fun `unrolled or malformed dice score nothing`() {
        assertEquals(0, YahtzeeScoring.score(CHANCE, listOf(0, 0, 0, 0, 0)))
        assertEquals(0, YahtzeeScoring.score(CHANCE, listOf(1, 2, 3)))
        assertEquals(0, YahtzeeScoring.score(CHANCE, listOf(1, 2, 3, 4, 7)))
        assertFalse(YahtzeeScoring.isYahtzee(listOf(0, 0, 0, 0, 0)))
    }

    @Test
    fun `upper bonus is thirty five at sixty three and not at sixty two`() {
        val par = card(ACES to 3, TWOS to 6, THREES to 9, FOURS to 12, FIVES to 15, SIXES to 18)
        assertEquals(63, par.upperSum)
        assertEquals(35, par.upperBonus)
        val short = card(ACES to 2, TWOS to 6, THREES to 9, FOURS to 12, FIVES to 15, SIXES to 18)
        assertEquals(62, short.upperSum)
        assertEquals(0, short.upperBonus)
    }

    @Test
    fun `total adds upper bonus lower boxes and hundred per yahtzee bonus`() {
        val c = card(
            ACES to 3, TWOS to 6, THREES to 9, FOURS to 12, FIVES to 15, SIXES to 18,
            YAHTZEE to 50, CHANCE to 20, bonuses = 2
        )
        assertEquals(63 + 35 + 70 + 200, c.total)
    }

    @Test
    fun `without the joker every open box is legal`() {
        val c = card(CHANCE to 10)
        val legal = YahtzeeScoring.legalCategories(listOf(1, 2, 3, 4, 5), c)
        assertEquals(12, legal.size)
        assertFalse(CHANCE in legal)
    }

    @Test
    fun `a first yahtzee does not trigger the joker`() {
        val dice = listOf(4, 4, 4, 4, 4)
        assertFalse(YahtzeeScoring.jokerApplies(dice, YahtzeeScorecard()))
        assertEquals(13, YahtzeeScoring.legalCategories(dice, YahtzeeScorecard()).size)
    }

    @Test
    fun `joker forces the matching upper box when it is open`() {
        val c = card(YAHTZEE to 50)
        assertEquals(listOf(FOURS), YahtzeeScoring.legalCategories(listOf(4, 4, 4, 4, 4), c))
    }

    @Test
    fun `joker moves to the open lower boxes when the matching upper box is taken`() {
        val c = card(YAHTZEE to 50, FOURS to 12, FULL_HOUSE to 25)
        val legal = YahtzeeScoring.legalCategories(listOf(4, 4, 4, 4, 4), c)
        assertTrue(legal.all { !it.isUpper })
        assertFalse(FULL_HOUSE in legal)
        assertTrue(LARGE_STRAIGHT in legal)
        assertTrue(CHANCE in legal)
    }

    @Test
    fun `joker with the lower section full forces an open upper box`() {
        var c = card(YAHTZEE to 50, FOURS to 12)
        for (cat in YahtzeeCategory.LOWER) if (c.isOpen(cat)) c = c.filled(cat, 0)
        val legal = YahtzeeScoring.legalCategories(listOf(4, 4, 4, 4, 4), c)
        assertEquals(listOf(ACES, TWOS, THREES, FIVES, SIXES), legal)
    }

    @Test
    fun `joker still applies when the yahtzee box holds a zero but earns no bonus`() {
        val c = card(YAHTZEE to 0)
        val dice = listOf(5, 5, 5, 5, 5)
        assertTrue(YahtzeeScoring.jokerApplies(dice, c))
        assertEquals(0, YahtzeeScoring.bonusFor(dice, c))
        assertEquals(25, YahtzeeScoring.scoreFor(FULL_HOUSE, dice, c))
    }

    @Test
    fun `the hundred point bonus needs a fifty in the yahtzee box`() {
        val dice = listOf(5, 5, 5, 5, 5)
        assertEquals(100, YahtzeeScoring.bonusFor(dice, card(YAHTZEE to 50)))
        assertEquals(0, YahtzeeScoring.bonusFor(dice, YahtzeeScorecard()))
        assertEquals(0, YahtzeeScoring.bonusFor(listOf(5, 5, 5, 5, 4), card(YAHTZEE to 50)))
    }
}
