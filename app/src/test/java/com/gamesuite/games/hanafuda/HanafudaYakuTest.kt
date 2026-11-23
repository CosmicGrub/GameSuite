package com.gamesuite.games.hanafuda

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The deck's composition and every scoring combination against hand-counted hands. */
class HanafudaYakuTest {
    private fun id(s: HanaSpecial) = HanafudaCards.idOf(s)
    private fun plains(n: Int, skipMonths: Set<Int> = emptySet()): List<Int> =
        HanafudaCards.ofKind(HanaKind.PLAIN).filter { it.month !in skipMonths }.take(n).map { it.id }
    private fun total(cards: Collection<Int>, set: YakuSet = YakuSet.BASIC) = KoiYaku.total(cards, set)
    private fun yaku(cards: Collection<Int>, set: YakuSet = YakuSet.BASIC) = KoiYaku.evaluate(cards, set).associate { it.yaku to it.points }

    // ---- the deck -----------------------------------------------------------------------------

    @Test fun theDeckHasFortyEightCardsInTwelveMonthsOfFour() {
        assertEquals(48, HanafudaCards.all.size)
        assertEquals((0 until 48).toList(), HanafudaCards.all.map { it.id })
        for (m in 0 until 12) assertEquals(4, HanafudaCards.all.count { it.month == m })
    }

    @Test fun theClassCountsAreFiveNineTenTwentyFour() {
        assertEquals(5, HanafudaCards.ofKind(HanaKind.BRIGHT).size)
        assertEquals(9, HanafudaCards.ofKind(HanaKind.ANIMAL).size)
        assertEquals(10, HanafudaCards.ofKind(HanaKind.RIBBON).size)
        assertEquals(24, HanafudaCards.ofKind(HanaKind.PLAIN).size)
    }

    @Test fun ribbonTypesAreThreePoetryThreeBlueFourPlainRed() {
        val ribbons = HanafudaCards.ofKind(HanaKind.RIBBON)
        assertEquals(3, ribbons.count { it.ribbon == HanaRibbon.RED_POETRY })
        assertEquals(3, ribbons.count { it.ribbon == HanaRibbon.BLUE })
        assertEquals(4, ribbons.count { it.ribbon == HanaRibbon.RED })
        assertTrue(HanafudaCards.all.filter { it.kind != HanaKind.RIBBON }.all { it.ribbon == HanaRibbon.NONE })
    }

    @Test fun poetryRibbonsAreInPinePlumCherryAndBlueInPeonyChrysanthemumMaple() {
        val poetry = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.month }.toSet()
        val blue = HanafudaCards.all.filter { it.ribbon == HanaRibbon.BLUE }.map { it.month }.toSet()
        assertEquals(setOf(0, 1, 2), poetry)
        assertEquals(setOf(5, 8, 9), blue)
    }

    @Test fun everyNamedPictureAppearsOnExactlyOneCard() {
        val named = HanafudaCards.all.filter { it.special != HanaSpecial.NONE }
        assertEquals(14, named.size)
        assertEquals(14, named.map { it.special }.toSet().size)
        assertTrue(named.all { it.kind == HanaKind.BRIGHT || it.kind == HanaKind.ANIMAL })
        assertEquals(14, HanaSpecial.entries.size - 1)
    }

    @Test fun theBrightsAreCraneCurtainMoonRainManPhoenix() {
        assertEquals(
            setOf(HanaSpecial.CRANE, HanaSpecial.CURTAIN, HanaSpecial.MOON, HanaSpecial.RAIN_MAN, HanaSpecial.PHOENIX),
            HanafudaCards.ofKind(HanaKind.BRIGHT).map { it.special }.toSet()
        )
        assertEquals(HanafudaCards.rainManId, id(HanaSpecial.RAIN_MAN))
    }

    @Test fun monthOfIsIdOverFour() {
        for (i in 0 until 48) assertEquals(i / 4, HanafudaCards.monthOf(i))
    }

    @Test fun worthRanksBrightsAboveAnimalsAboveRibbonsAbovePlains() {
        val crane = HanafudaCards.worth(HanafudaCards.card(id(HanaSpecial.CRANE)))
        val rain = HanafudaCards.worth(HanafudaCards.card(id(HanaSpecial.RAIN_MAN)))
        val boar = HanafudaCards.worth(HanafudaCards.card(id(HanaSpecial.BOAR)))
        val geese = HanafudaCards.worth(HanafudaCards.card(id(HanaSpecial.GEESE)))
        val ribbon = HanafudaCards.worth(HanafudaCards.ofKind(HanaKind.RIBBON).first())
        val plain = HanafudaCards.worth(HanafudaCards.ofKind(HanaKind.PLAIN).first())
        assertTrue(crane > rain)
        assertTrue(rain > geese)
        assertTrue(boar > geese)
        assertTrue(geese > ribbon && ribbon > plain)
    }

    // ---- brights --------------------------------------------------------------------------------

    @Test fun fiveBrightsScoreTen() {
        assertEquals(mapOf(Yaku.FIVE_BRIGHTS to 10), yaku(HanafudaCards.brightIds))
    }

    @Test fun fourBrightsWithoutTheRainManScoreEight() {
        val four = HanafudaCards.brightIds - HanafudaCards.rainManId
        assertEquals(mapOf(Yaku.FOUR_BRIGHTS to 8), yaku(four))
    }

    @Test fun fourBrightsIncludingTheRainManScoreSeven() {
        val four = HanafudaCards.brightIds - id(HanaSpecial.PHOENIX)
        assertEquals(mapOf(Yaku.RAINY_FOUR_BRIGHTS to 7), yaku(four))
    }

    @Test fun threeBrightsWithoutTheRainManScoreFive() {
        val three = listOf(id(HanaSpecial.CRANE), id(HanaSpecial.CURTAIN), id(HanaSpecial.MOON))
        assertEquals(mapOf(Yaku.THREE_BRIGHTS to 5), yaku(three))
    }

    @Test fun threeBrightsThatIncludeTheRainManScoreNothing() {
        val three = listOf(id(HanaSpecial.CRANE), id(HanaSpecial.CURTAIN), id(HanaSpecial.RAIN_MAN))
        assertEquals(emptyMap<Yaku, Int>(), yaku(three))
    }

    @Test fun twoBrightsScoreNothing() {
        assertEquals(0, total(listOf(id(HanaSpecial.CRANE), id(HanaSpecial.MOON))))
    }

    // ---- animals --------------------------------------------------------------------------------

    @Test fun boarDeerButterfliesScoreFive() {
        val ibc = listOf(HanaSpecial.BOAR, HanaSpecial.DEER, HanaSpecial.BUTTERFLIES).map { id(it) }
        assertEquals(mapOf(Yaku.BOAR_DEER_BUTTERFLIES to 5), yaku(ibc))
    }

    @Test fun eachAnimalBeyondThreeAddsAPointToBoarDeerButterflies() {
        val ibc = listOf(HanaSpecial.BOAR, HanaSpecial.DEER, HanaSpecial.BUTTERFLIES).map { id(it) }
        assertEquals(6, yaku(ibc + id(HanaSpecial.GEESE))[Yaku.BOAR_DEER_BUTTERFLIES])
        // Five animals also score "animals" for 1.
        val five = ibc + id(HanaSpecial.GEESE) + id(HanaSpecial.CUCKOO)
        assertEquals(mapOf(Yaku.BOAR_DEER_BUTTERFLIES to 7, Yaku.ANIMALS to 1), yaku(five))
    }

    @Test fun fiveAnimalsScoreOneAndEachExtraAddsOne() {
        val animals = HanafudaCards.ofKind(HanaKind.ANIMAL).map { it.id }
        val noIbc = animals.filter { HanafudaCards.card(it).special !in setOf(HanaSpecial.BOAR, HanaSpecial.DEER, HanaSpecial.BUTTERFLIES) }
        assertEquals(6, noIbc.size)
        assertEquals(mapOf(Yaku.ANIMALS to 1), yaku(noIbc.take(5)))
        assertEquals(mapOf(Yaku.ANIMALS to 2), yaku(noIbc))
        assertEquals(0, total(noIbc.take(4)))
    }

    @Test fun allNineAnimalsScoreIboChoPlusAnimals() {
        val all = HanafudaCards.ofKind(HanaKind.ANIMAL).map { it.id }
        assertEquals(mapOf(Yaku.BOAR_DEER_BUTTERFLIES to 11, Yaku.ANIMALS to 5), yaku(all))
    }

    // ---- ribbons --------------------------------------------------------------------------------

    private val poetry = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED_POETRY }.map { it.id }
    private val blue = HanafudaCards.all.filter { it.ribbon == HanaRibbon.BLUE }.map { it.id }
    private val plainRed = HanafudaCards.all.filter { it.ribbon == HanaRibbon.RED }.map { it.id }

    @Test fun redPoetryRibbonsScoreFiveAndEachOtherRibbonAddsOne() {
        assertEquals(mapOf(Yaku.RED_POETRY_RIBBONS to 5), yaku(poetry))
        assertEquals(6, yaku(poetry + blue.first())[Yaku.RED_POETRY_RIBBONS])
    }

    @Test fun blueRibbonsScoreFive() {
        assertEquals(mapOf(Yaku.BLUE_RIBBONS to 5), yaku(blue))
    }

    @Test fun fiveRibbonsScoreOneAndTenScoreSix() {
        assertEquals(mapOf(Yaku.RIBBONS to 1), yaku(plainRed + poetry.first()))
        assertEquals(mapOf(Yaku.RIBBONS to 2), yaku(plainRed + poetry.take(2)))
        val ribbonsOnly = HanafudaCards.ofKind(HanaKind.RIBBON).map { it.id }
        val y = yaku(ribbonsOnly)
        assertEquals(6, y[Yaku.RIBBONS]) // 1 + (10 - 5)
        assertEquals(12, y[Yaku.RED_POETRY_RIBBONS]) // 5 + (10 - 3)
        assertEquals(12, y[Yaku.BLUE_RIBBONS])
    }

    @Test fun twoRibbonsOfEachTypeScoreNothing() {
        assertEquals(0, total(poetry.take(2) + blue.take(2)))
    }

    // ---- plains ---------------------------------------------------------------------------------

    @Test fun tenPlainsScoreOneAndEachExtraAddsOne() {
        assertEquals(0, total(plains(9)))
        assertEquals(mapOf(Yaku.PLAINS to 1), yaku(plains(10)))
        assertEquals(mapOf(Yaku.PLAINS to 4), yaku(plains(13)))
        assertEquals(mapOf(Yaku.PLAINS to 15), yaku(plains(24)))
    }

    // ---- full set -------------------------------------------------------------------------------

    @Test fun viewingHandsOnlyScoreInTheFullSet() {
        val flower = listOf(id(HanaSpecial.CURTAIN), id(HanaSpecial.SAKE_CUP))
        val moon = listOf(id(HanaSpecial.MOON), id(HanaSpecial.SAKE_CUP))
        assertEquals(0, total(flower, YakuSet.BASIC))
        assertEquals(mapOf(Yaku.FLOWER_VIEWING to 5), yaku(flower, YakuSet.FULL))
        assertEquals(mapOf(Yaku.MOON_VIEWING to 5), yaku(moon, YakuSet.FULL))
        assertEquals(10, total(flower + id(HanaSpecial.MOON), YakuSet.FULL) - 0) // viewing both ways = 5 + 5
    }

    @Test fun redAndBlueRibbonsAddTenInTheFullSetOnly() {
        val both = poetry + blue
        val basic = yaku(both, YakuSet.BASIC)
        val full = yaku(both, YakuSet.FULL)
        assertFalse(Yaku.RED_AND_BLUE in basic)
        assertEquals(10, full[Yaku.RED_AND_BLUE])
        assertEquals(basic.values.sum() + 10, full.values.sum())
    }

    @Test fun fullSetOnlyYakuAreFlaggedAsSuch() {
        assertEquals(
            setOf(Yaku.FLOWER_VIEWING, Yaku.MOON_VIEWING, Yaku.RED_AND_BLUE),
            Yaku.entries.filter { it.fullSetOnly }.toSet()
        )
    }

    // ---- misc -----------------------------------------------------------------------------------

    @Test fun placeholdersAndDuplicatesAreIgnored() {
        assertEquals(0, total(listOf(-1, -1000, 99, -5)))
        assertEquals(10, total(HanafudaCards.brightIds + HanafudaCards.brightIds + listOf(-3)))
    }

    @Test fun anEmptyCaptureScoresNothing() {
        assertEquals(emptyList<YakuScore>(), KoiYaku.evaluate(emptyList(), YakuSet.FULL))
    }

    @Test fun yakuIdsAreUniqueForStringLookups() {
        assertEquals(Yaku.entries.size, Yaku.entries.map { it.id }.toSet().size)
        assertEquals(13, Yaku.entries.size)
    }

    @Test fun yakuSetParsesItsIdAndDefaultsToBasic() {
        assertEquals(YakuSet.FULL, YakuSet.fromId("full"))
        assertEquals(YakuSet.BASIC, YakuSet.fromId("basic"))
        assertEquals(YakuSet.BASIC, YakuSet.fromId(null))
        assertEquals(YakuSet.BASIC, YakuSet.fromId("nonsense"))
    }
}
