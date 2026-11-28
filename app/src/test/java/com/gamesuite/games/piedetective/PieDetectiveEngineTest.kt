package com.gamesuite.games.piedetective

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Engine behaviour of section 2.3 (tests P08-P26 plus the hidden-thief and edge rules). */
class PieDetectiveEngineTest {
    private fun thiefOf(seed: Long, band: PieBand, caseIndex: Int = 0) = PieDealer.deal(seed, caseIndex, band).thief

    // ---- dealing ----

    @Test
    fun sameSeedGivesIdenticalLineupThiefAndSneakDeck() {
        for (band in PieBand.entries) {
            assertEquals(PieDealer.deal(11L, 0, band), PieDealer.deal(11L, 0, band))
            val a = pieEngine(band = band, seed = 11L).state()!!
            val b = pieEngine(band = band, seed = 11L).state()!!
            assertEquals(a.lineup, b.lineup)
            assertEquals(PieDealer.deck(11L, 0, band), PieDealer.deck(11L, 0, band))
        }
        assertNotEquals(PieDealer.deal(11L, 0, PieBand.BIG), PieDealer.deal(12L, 0, PieBand.BIG))
        assertNotEquals(PieDealer.deal(11L, 0, PieBand.BIG), PieDealer.deal(11L, 1, PieBand.BIG))
        assertEquals(11L * 31 + 2, PieDealer.caseSeed(11L, 2))
    }

    @Test
    fun lineupSizeFollowsBandAndIsASubsetOfTheCast() {
        for (band in PieBand.entries) for (seed in 1L..40L) {
            val d = PieDealer.deal(seed, 0, band)
            assertEquals(PieRules.band(band).lineup, d.lineup.size)
            assertEquals(d.lineup.size, d.lineup.toSet().size)
            assertTrue(d.lineup.all { PieCast.byId(it) != null })
        }
    }

    @Test
    fun thiefIsAlwaysInTheLineup() {
        for (band in PieBand.entries) for (seed in 1L..200L) {
            val d = PieDealer.deal(seed, seed.toInt() % 3, band)
            assertTrue(d.thief in d.lineup)
        }
    }

    @Test
    fun theThiefIsNeverInTheStateWhileACaseRuns() {
        val e = pieEngine(seed = 21L)
        val d = PieDriver(e)
        assertNull(e.state()!!.revealedThiefId)
        d.turnAsk(PieTrait.HAT)
        assertNull(e.state()!!.revealedThiefId)
        assertFalse(e.state().toString().contains("thief=")) // no field of that name leaks the id
    }

    // ---- asking ----

    @Test
    fun askedTraitAnswerComesFromTheThiefVector() {
        for (seed in 1L..30L) for (t in PieTrait.entries) {
            val e = pieEngine(band = PieBand.BIG, seed = seed)
            val clue = PieDriver(e).askAndShow(t)
            assertEquals(PieCast.byId(thiefOf(seed, PieBand.BIG))!!.has(t), clue.yes)
        }
    }

    @Test
    fun sameTraitCannotBeAskedTwiceInACase() {
        val e = pieEngine(seed = 3L)
        val d = PieDriver(e)
        d.turnAsk(PieTrait.HAT)
        d.endSneak()
        d.ack()
        assertEquals(PieResult.TRAIT_ALREADY_ASKED, e.chooseTrait(e.state()!!.currentSeat, PieTrait.HAT, false))
        assertFalse(PieTrait.HAT in e.state()!!.availableTraits)
        // the Little band never offers boots or a bow tie
        val little = pieEngine(band = PieBand.LITTLE, seed = 3L)
        PieDriver(little).ack()
        assertEquals(PieResult.TRAIT_NOT_OFFERED, little.chooseTrait(little.state()!!.currentSeat, PieTrait.BOOTS, false))
    }

    @Test
    fun traitIsOnlyMarkedAskedAfterShowEveryone() {
        val e = pieEngine(seed = 4L)
        val d = PieDriver(e)
        d.ack()
        val seat = e.state()!!.currentSeat
        assertEquals(PieResult.OK, e.chooseTrait(seat, PieTrait.GLASSES, false))
        assertEquals(PiePhase.PEEK, e.state()!!.phase)
        assertNotNull(e.state()!!.pendingAnswer)
        assertEquals(PieResult.WRONG_PHASE, e.showEveryone(seat)) // "Show everyone" needs a peek first
        assertEquals(PieResult.OK, e.cancelToChoose(seat))
        assertTrue(PieTrait.GLASSES in e.state()!!.availableTraits)
        assertTrue(e.state()!!.clues.isEmpty())
        assertNull(e.state()!!.pendingAnswer)
        d.askAndShow(PieTrait.GLASSES)
        assertFalse(PieTrait.GLASSES in e.state()!!.availableTraits)
        assertEquals(1, e.state()!!.clues.size)
    }

    // ---- flipping ----

    private fun matchingAndNot(e: PieEngine): Pair<List<String>, List<String>> {
        val s = e.state()!!
        val clue = s.clues.last()
        val (match, other) = s.lineup.filter { !it.flipped }.partition { PieCast.byId(it.id)!!.has(clue.trait) == clue.yes }
        return match.map { it.id } to other.map { it.id }
    }

    @Test
    fun flipOfAMatchingSuspectIsRejectedAndChangesNothing() {
        val e = pieEngine(seed = 5L)
        val d = PieDriver(e)
        d.askAndShow(PieTrait.HAT)
        val (match, _) = matchingAndNot(e)
        val before = e.state()!!
        assertEquals(PieResult.STILL_MATCHES, e.flip(before.currentSeat, match.first()))
        assertEquals(before.lineup, e.state()!!.lineup)
        assertEquals(PiePhase.FLIP, e.state()!!.phase)
        assertEquals(PieResult.NO_SUCH_SUSPECT, e.flip(before.currentSeat, "fox"))
        assertEquals(PieResult.NOT_YOUR_TURN, e.flip(1 - before.currentSeat, match.first()))
    }

    @Test
    fun flipOfANonMatchingSuspectClearsIt() {
        val e = pieEngine(seed = 6L)
        val d = PieDriver(e)
        d.askAndShow(PieTrait.HAT)
        val (_, other) = matchingAndNot(e)
        val seat = e.state()!!.currentSeat
        assertEquals(PieResult.OK, e.flip(seat, other.first()))
        assertTrue(e.state()!!.lineup.first { it.id == other.first() }.flipped)
        assertEquals(PieResult.ALREADY_CLEARED, e.flip(seat, other.first()))
        // the thief is never among the non-matching animals
        assertFalse(thiefOf(6L, PieBand.MEDIUM) in other)
        // the sneak waits until every flip is done
        if (other.size > 1) assertEquals(PiePhase.FLIP, e.state()!!.phase)
        d.flipAll()
        assertEquals(PiePhase.SNEAK, e.state()!!.phase)
    }

    @Test
    fun autoFlipClearsExactlyTheNonMatchingSuspects() {
        for (seed in 1L..15L) {
            val e = pieEngine(seed = seed, autoFlip = true)
            val d = PieDriver(e)
            val clue = d.askAndShow(PieTrait.SCARF)
            assertEquals(PiePhase.SNEAK, e.state()!!.phase)
            for (v in e.state()!!.lineup) assertEquals(PieCast.byId(v.id)!!.has(clue.trait) != clue.yes, v.flipped)
            assertTrue(thiefOf(seed, PieBand.MEDIUM) in e.state()!!.lineup.filter { !it.flipped }.map { it.id })
        }
    }

    // ---- sneaking, catching ----

    @Test
    fun sneakCardIsDrawnAfterEveryAskButNotAfterACatch() {
        val seed = 8L
        val e = pieEngine(seed = seed, autoFlip = true)
        val d = PieDriver(e)
        d.askAndShow(PieTrait.HAT)
        assertEquals(1, e.state()!!.sneakDrawn)
        assertNotNull(e.state()!!.lastSneakWasDash)
        assertEquals(PieDealer.deck(seed, 0, PieBand.MEDIUM)[0], e.state()!!.lastSneakWasDash)
        d.endSneak()
        d.wrongCatch(thiefOf(seed, PieBand.MEDIUM))
        assertEquals(PiePhase.SNEAK, e.state()!!.phase)
        assertTrue(e.state()!!.wrongCatch)
        assertNull(e.state()!!.lastSneakWasDash)
        assertEquals(1, e.state()!!.sneakDrawn) // a catch draws no card
    }

    @Test
    fun dashMovesTheThiefOneHopAndThiefAtGateLosesTheCase() {
        val seed = pieSeedWithFirstCard(PieBand.MEDIUM, true)
        val e = pieEngine(seed = seed, autoFlip = true)
        val d = PieDriver(e)
        d.askAndShow(PieTrait.HAT)
        assertEquals(1, e.state()!!.thiefHops)
        assertEquals(true, e.state()!!.lastSneakWasDash)
        d.endSneak()
        val thief = thiefOf(seed, PieBand.MEDIUM)
        d.wrongCatch(thief) // +2 hops: 1 + 2 reaches the gate
        assertEquals(3, e.state()!!.thiefHops)
        d.endSneak()
        assertEquals(PiePhase.CASE_LOST, e.state()!!.phase)
        assertEquals(thief, e.state()!!.revealedThiefId)
        assertEquals(PieEventCode.CASE_LOST, e.state()!!.lastEvent!!.code)
        assertEquals(3, e.session.lastCaseHops)
        assertEquals(0, e.session.casesSolved)
        // a pause card does not move the thief
        val pauseSeed = pieSeedWithFirstCard(PieBand.MEDIUM, false)
        val p = pieEngine(seed = pauseSeed, autoFlip = true)
        PieDriver(p).askAndShow(PieTrait.HAT)
        assertEquals(0, p.state()!!.thiefHops)
        assertEquals(false, p.state()!!.lastSneakWasDash)
    }

    @Test
    fun correctCatchWinsTheCaseWithStarsByHops() {
        val seed = pieSeedWithFirstCard(PieBand.MEDIUM, false)
        val thief = thiefOf(seed, PieBand.MEDIUM)
        val e = pieEngine(seed = seed)
        PieDriver(e).rightCatch(thief)
        assertEquals(PiePhase.CASE_WON, e.state()!!.phase)
        assertEquals(3, e.state()!!.caseStars)
        assertEquals(thief, e.state()!!.revealedThiefId)
        assertEquals(1, e.session.casesSolved); assertEquals(3, e.session.stars)
        // caught after one wrong catch (thief at hop 2): two stars
        val e2 = pieEngine(seed = seed)
        val d2 = PieDriver(e2)
        d2.wrongCatch(thief); d2.endSneak()
        d2.rightCatch(thief)
        assertEquals(PiePhase.CASE_WON, e2.state()!!.phase)
        assertEquals(2, e2.state()!!.caseStars)
    }

    @Test
    fun wrongCatchClearsTheSuspectAndMovesTheThiefTwoHops() {
        val seed = 9L
        val thief = thiefOf(seed, PieBand.MEDIUM)
        val e = pieEngine(seed = seed)
        val d = PieDriver(e)
        d.wrongCatch(thief)
        val s = e.state()!!
        assertEquals(2, s.thiefHops)
        assertEquals(1, s.lineup.count { it.flipped })
        assertFalse(s.lineup.first { it.id == thief }.flipped)
        assertEquals(PieEventCode.CATCH_WRONG, s.lastEvent!!.code)
        assertEquals(PieResult.ALREADY_CLEARED, run {
            e.sneakDone(); d.ack()
            e.startCatch(e.state()!!.currentSeat)
            e.catchSuspect(e.state()!!.currentSeat, s.lineup.first { it.flipped }.id)
        })
        assertEquals(PiePhase.CATCH, e.state()!!.phase) // a refused catch changes nothing
    }

    @Test
    fun luckyPeekSkipsTheSneakOncePerCase() {
        val seed = pieSeedWithFirstCard(PieBand.MEDIUM, true)
        val e = pieEngine(seed = seed, handicap = "p1", autoFlip = true)
        val d = PieDriver(e)
        d.ack()
        assertTrue(e.state()!!.luckyAvailable)
        d.askAndShow(PieTrait.HAT, lucky = true)
        assertTrue(e.state()!!.sneakSkipped)
        assertEquals(0, e.state()!!.thiefHops) // the first card is a dash but it was never drawn
        assertEquals(0, e.state()!!.sneakDrawn)
        assertEquals(1, e.session.luckyPeeksUsed)
        d.endSneak()
        d.ack() // seat 1: no Lucky peek
        assertFalse(e.state()!!.luckyAvailable)
        assertEquals(PieResult.NO_LUCKY_PEEK_LEFT, e.chooseTrait(1, PieTrait.BAG, true))
        d.turnAsk(PieTrait.BAG); d.endSneak()
        d.ack() // seat 0 again: its Lucky peek for this case is spent
        assertFalse(e.state()!!.luckyAvailable)
        assertEquals(PieResult.NO_LUCKY_PEEK_LEFT, e.chooseTrait(0, PieTrait.GLASSES, true))
    }

    // ---- policies, balance (P22) ----

    private enum class Policy { SMART, RANDOM }

    private fun playCase(band: PieBand, seed: Long, policy: Policy): Boolean {
        val e = pieEngine(band = band, seed = seed)
        val d = PieDriver(e)
        val rnd = Random(seed xor 0x77L)
        var guard = 0
        while (e.state()!!.phase != PiePhase.CASE_WON && e.state()!!.phase != PiePhase.CASE_LOST) {
            check(guard++ < 200) { "case does not end" }
            d.ack()
            val s = e.state()!!
            when (s.phase) {
                PiePhase.CHOOSE -> {
                    val alive = s.lineup.filter { !it.flipped }.map { it.id }
                    if (alive.size == 1) {
                        e.startCatch(s.currentSeat)
                        e.catchSuspect(s.currentSeat, alive.random(rnd))
                    } else {
                        val trait = when (policy) {
                            Policy.RANDOM -> s.availableTraits.random(rnd)
                            else -> e.hint()!!.trait ?: s.availableTraits.first()
                        }
                        d.turnAsk(trait)
                    }
                }
                PiePhase.SNEAK -> d.endSneak()
                else -> error("unexpected ${s.phase}")
            }
        }
        return e.state()!!.phase == PiePhase.CASE_WON
    }

    private fun rate(band: PieBand, policy: Policy, n: Int = 3000): Double {
        var wins = 0
        for (i in 1..n) if (playCase(band, i.toLong() * 7919L, policy)) wins++
        return wins.toDouble() / n
    }

    @Test
    fun askerPolicyWinRatesStayInsideTargetBandsPerBand() {
        // Design doc 2.6 table: smart / random asker. Smart within 0.04, random within 0.05.
        val targets = mapOf(
            PieBand.LITTLE to (0.98 to 0.96),
            PieBand.MEDIUM to (0.92 to 0.86),
            PieBand.BIG to (0.79 to 0.68)
        )
        for ((band, t) in targets) {
            val smart = rate(band, Policy.SMART)
            val random = rate(band, Policy.RANDOM)
            assertEquals("smart $band", t.first, smart, 0.04)
            assertEquals("random $band", t.second, random, 0.05)
            assertTrue("smart is at least as good as random for $band", smart >= random - 0.01)
        }
    }

    // ---- sidekick, hint ----

    @Test
    fun sidekickCatchesOnlyWhenExactlyOneSuspectIsLeft() {
        for (diff in CpuDifficulty.entries) for (seed in 1L..25L) {
            val e = pieEngine(humans = 0, bots = 2, band = PieBand.MEDIUM, seed = seed, difficulty = diff)
            var guard = 0
            var leftBeforeStep = -1
            while (e.state()!!.phase != PiePhase.CASE_WON && e.state()!!.phase != PiePhase.CASE_LOST) {
                check(guard++ < 300)
                if (e.state()!!.phase == PiePhase.CHOOSE) leftBeforeStep = e.state()!!.uncleared
                assertTrue(e.sidekickStep())
                val code = e.state()!!.lastEvent?.code
                if (e.state()!!.phase == PiePhase.CASE_WON) {
                    assertEquals("a Sidekick catch needs exactly one suspect left", 1, leftBeforeStep)
                }
                assertNotEquals(PieEventCode.CATCH_WRONG, code) // the Sidekick never gambles
            }
        }
    }

    @Test
    fun sidekickDoesNothingOnAPersonsTurnAndWhenPaused() {
        val e = pieEngine(humans = 1, bots = 1, seed = 2L)
        assertEquals(0, e.state()!!.currentSeat)
        assertFalse(e.sidekickStep())
        val bots = pieEngine(humans = 0, bots = 2, seed = 2L)
        bots.pause()
        assertFalse(bots.sidekickStep())
        bots.resume()
        assertTrue(bots.sidekickStep())
    }

    @Test
    fun hintPicksBestSplitAndNeverAnAskedTrait() {
        val e = pieEngine(band = PieBand.BIG, seed = 14L, humans = 1, bots = 1)
        val d = PieDriver(e)
        repeat(3) {
            if (e.state()!!.phase == PiePhase.CASE_WON || e.state()!!.phase == PiePhase.CASE_LOST) return
            val s = e.state()!!
            assertEquals(0, s.currentSeat)
            val alive = s.lineup.filter { !it.flipped }.map { PieCast.byId(it.id)!! }
            fun worst(t: PieTrait): Int { val y = alive.count { it.has(t) }; return maxOf(y, alive.size - y) }
            val splitting = s.availableTraits.filter { worst(it) < alive.size }.ifEmpty { s.availableTraits }
            val expected = splitting.minWithOrNull(compareBy<PieTrait>({ worst(it) }, { it.ordinal }))
            val h = e.hint()!!
            assertFalse(h.catchNow)
            assertEquals(expected, h.trait)
            assertTrue(h.trait in s.availableTraits)
            d.turnAsk(h.trait!!)
            d.endSneak()
            // the Sidekick plays its turn so the person is up again
            while (e.state()!!.currentSeat == 1 && e.state()!!.phase != PiePhase.CASE_LOST && e.state()!!.phase != PiePhase.CASE_WON) check(e.sidekickStep())
        }
    }

    @Test
    fun hintPulsesCatchWhenOneAnimalIsLeftAndIsNullOutsideChoose() {
        var seed = 1L
        while (PieDealer.deck(seed, 0, PieBand.MEDIUM).take(6).any { it }) seed++ // a slow thief: no dash in the first six cards
        val e = pieEngine(band = PieBand.MEDIUM, seed = seed)
        val d = PieDriver(e)
        var guard = 0
        while (e.state()!!.uncleared > 1) {
            check(guard++ < 6)
            d.ack()
            val h = e.hint()!!
            assertFalse(h.catchNow)
            d.turnAsk(h.trait!!)
            assertNull(e.hint()) // the hint only exists in CHOOSE
            d.endSneak()
        }
        d.ack()
        assertEquals(PieHint(null, true), e.hint())
    }

    // ---- book, states ----

    @Test
    fun caseBookAdvancesThroughAllCasesAndEnds() {
        for (band in PieBand.entries) {
            val e = pieEngine(humans = 0, bots = 2, band = band, seed = 99L, difficulty = CpuDifficulty.HARD)
            val cases = PieRules.band(band).cases
            var seen = 0
            var guard = 0
            while (e.state()!!.phase != PiePhase.BOOK_DONE) {
                check(guard++ < 2000)
                when (e.state()!!.phase) {
                    PiePhase.CASE_WON, PiePhase.CASE_LOST -> { seen++; assertEquals(PieResult.OK, e.nextCase()) }
                    else -> assertTrue(e.sidekickStep())
                }
            }
            assertEquals(cases, seen)
            assertEquals(cases, e.session.casesPlayed)
            assertTrue(e.session.bookFinished)
            assertEquals(PieResult.NOT_ACTIVE, e.nextCase())
            assertNull(e.toSave())
        }
    }

    @Test
    fun theFirstSeatOfTheNextCaseIsTheOneAfterTheCatcher() {
        val seed = 40L
        val e = pieEngine(seed = seed)
        val d = PieDriver(e)
        d.rightCatch(thiefOf(seed, PieBand.MEDIUM)) // seat 0 caught the thief
        assertEquals(PieResult.OK, e.nextCase())
        assertEquals(1, e.state()!!.currentSeat)
        assertEquals(1, e.state()!!.caseIndex)
        assertTrue(e.state()!!.clues.isEmpty() && e.state()!!.thiefHops == 0)
    }

    @Test
    fun peekLensStateMachineWalksChoosePeekShowFlipSneak() {
        val e = pieEngine(seed = 12L)
        assertEquals(PiePhase.PASS, e.state()!!.phase) // two people: the pass screen first
        assertEquals(PieResult.WRONG_PHASE, e.chooseTrait(0, PieTrait.HAT, false))
        assertEquals(PieResult.OK, e.passAcknowledged(0))
        assertEquals(PiePhase.CHOOSE, e.state()!!.phase)
        assertEquals(PieResult.NOT_YOUR_TURN, e.chooseTrait(1, PieTrait.HAT, false))
        assertEquals(PieResult.OK, e.chooseTrait(0, PieTrait.HAT, false))
        assertEquals(PiePhase.PEEK, e.state()!!.phase)
        assertFalse(e.state()!!.peeked)
        assertEquals(PieResult.OK, e.peekHeld(0))
        assertTrue(e.state()!!.peeked)
        assertEquals(PieResult.OK, e.showEveryone(0))
        assertTrue(e.state()!!.phase == PiePhase.FLIP || e.state()!!.phase == PiePhase.SNEAK)
        PieDriver(e).flipAll()
        assertEquals(PiePhase.SNEAK, e.state()!!.phase)
        assertEquals(PieResult.OK, e.sneakDone())
        assertEquals(1, e.state()!!.currentSeat)
        assertEquals(PiePhase.PASS, e.state()!!.phase) // a different person: pass screen again
        assertEquals(PieResult.WRONG_PHASE, e.nextCase())
        // CATCH can be backed out of at no cost
        PieDriver(e).ack()
        assertEquals(PieResult.OK, e.startCatch(1))
        assertEquals(PiePhase.CATCH, e.state()!!.phase)
        assertEquals(PieResult.OK, e.cancelToChoose(1))
        assertEquals(PiePhase.CHOOSE, e.state()!!.phase)
    }

    @Test
    fun aSinglePersonWithASidekickNeverSeesAPassScreen() {
        val e = pieEngine(humans = 1, bots = 1, seed = 5L)
        assertEquals(PiePhase.CHOOSE, e.state()!!.phase)
        assertFalse(e.state()!!.hasPass)
    }

    @Test
    fun pausingAPeekHidesTheAnswerAndCoversTheScreenForGroups() {
        val e = pieEngine(seed = 13L)
        val d = PieDriver(e)
        d.ack()
        e.chooseTrait(0, PieTrait.BAG, false)
        e.peekHeld(0)
        e.pause()
        val s = e.state()!!
        assertNull(s.pendingAnswer)
        assertEquals(PiePhase.PASS, s.phase)
        assertTrue(PieTrait.BAG in s.availableTraits) // the peek cost nothing
        e.resume()
    }
}
