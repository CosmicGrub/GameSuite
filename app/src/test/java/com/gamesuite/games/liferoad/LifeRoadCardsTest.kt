package com.gamesuite.games.liferoad

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LifeRoadCardsTest {
    private fun base() = LRT.start(3, LRT.config(seed = 3))
    private fun play(s: LifeRoadState, id: String, family: Boolean = false, spin: Boolean = false) =
        LRT.act(LRT.card(s, id, family, spin), if (spin) LifeRoadAction.Spin else LifeRoadAction.Acknowledge)

    @Test fun eventDeckHasSixtyFourUniqueCards() {
        assertEquals(64, LifeRoadContent.events.size)
        assertEquals(64, LifeRoadContent.events.map { it.id }.toSet().size)
    }

    @Test fun familyDeckHasSixteenUniqueCards() {
        assertEquals(16, LifeRoadContent.familyCards.map { it.id }.toSet().size)
    }

    @Test fun milestoneListHasTwelveEntries() {
        assertEquals(12, LifeRoadContent.milestones.map { it.id }.toSet().size)
    }

    @Test fun everyEffectTokenParses() {
        for (c in LifeRoadContent.events) assertTrue(c.id, c.effects.isNotEmpty())
        for (c in LifeRoadContent.familyCards) assertTrue(c.id, c.effects.isNotEmpty())
    }

    @Test fun coverCancelsOnlyItsOwnCategory() {
        val withHome = LRT.edit(base(), 0) { it.copy(covers = setOf(CoverKind.HOME)) }
        val saved = play(withHome, "leaky_roof")
        assertEquals(100, saved.players[0].cash)
        val notSaved = play(withHome, "sprained_ankle")
        assertEquals(80, notSaved.players[0].cash)
        val withIncome = LRT.edit(base(), 0) { it.copy(covers = setOf(CoverKind.INCOME)) }
        assertEquals(NextPay.NORMAL, play(withIncome, "quiet_season").players[0].nextPay)
        assertEquals(NextPay.HALVE, play(base(), "quiet_season").players[0].nextPay)
    }

    @Test fun coverIsPermanentAndBoughtOnce() {
        var s = base().copy(phase = Phase.AWAIT_COVER, pending = PendingState())
        s = LRT.act(s, LifeRoadAction.BuyCover(CoverKind.HOME))
        assertEquals(80, s.players[0].cash)
        assertEquals(LifeRoadReject.BAD_CHOICE, LRT.engine.explain(s, 0, LifeRoadAction.BuyCover(CoverKind.HOME)))
        assertTrue(CoverKind.HOME in s.players[0].covers)
    }

    @Test fun familyIsCappedAtFourIncludingPets() {
        val full = LRT.edit(base(), 0) { it.copy(familyPegs = 4) }
        assertEquals(4, play(full, "baby", true).players[0].familyPegs)
        assertEquals(1, play(base(), "baby", true).players[0].familyPegs)
        // landing on a family square with the cap reached never shows a peg card
        val onFam = LRT.at(full, 0, "FAM.0")
        repeat(16) {
            val r = LRT.engine.apply(LRT.at(onFam, 0, "FAM.1").copy(phase = Phase.AWAIT_SPIN, decks = onFam.decks.copy(family = onFam.decks.family.drop(it) + onFam.decks.family.take(it))), 0, LifeRoadAction.Spin)
            if (r.state.phase == Phase.AWAIT_CARD && r.state.pending.cardIsFamily) {
                assertTrue(LifeRoadIndex.family.getValue(r.state.pending.cardId!!).effects.none { e -> e.kind == EffectKind.FAMILY_PEG })
            }
        }
    }

    @Test fun memoriesAreCappedAtSix() {
        val p = LRT.edit(base(), 0) { it.copy(cardMemories = 9) }.players[0]
        assertEquals(6, p.memories)
    }

    @Test fun milestoneNeverRepeatsForAPlayer() {
        val seed = LRT.seedFor(1)
        fun landOnM(s: LifeRoadState): LifeRoadState {
            val onM = LRT.at(s.copy(phase = Phase.AWAIT_SPIN, currentSeat = 0, pending = PendingState(), cursors = s.cursors.copy(spin = 0)), 0, "COZY.1")
            return LRT.engine.apply(onM, 0, LifeRoadAction.Spin).state // spin 1 lands on COZY.2 (M)
        }
        var s = LRT.start(2, LRT.config(seed = seed))
        val claimed = ArrayList<String>()
        repeat(12) {
            val r = landOnM(s)
            assertEquals(Phase.AWAIT_CARD, r.phase)
            val id = r.pending.cardId!!.removePrefix("ms_")
            assertTrue(id !in claimed)
            claimed += id
            s = r.copy(phase = Phase.AWAIT_SPIN, pending = PendingState())
        }
        assertEquals(12, s.players[0].milestonesClaimed.size)
        val cash = s.players[0].cash
        val r = landOnM(s)
        assertEquals(cash + 10, r.players[0].cash)
    }

    @Test fun eachOtherPaysSkipsRetiredPlayers() {
        val s = LRT.edit(LRT.edit(base(), 2) { it.copy(retired = true, lane = RetireLane.LATE) }, 1) { it.copy(cash = 100) }
        val r = play(s, "parade")
        assertEquals(105, r.players[0].cash)
        assertEquals(95, r.players[1].cash)
        assertEquals(100, r.players[2].cash)
    }

    @Test fun juniorSoftensBadCards() {
        val j = LRT.start(2, LRT.config(junior = true))
        assertEquals(150 - 20, play(j, "broken_pipe").players[0].cash) // 35 halves to 18, rounds up to 20
        assertEquals(150 - 5, play(j, "library_fine").players[0].cash) // at least 5
        val skip = play(j, "workshop_closed")
        assertEquals(NextPay.NORMAL, skip.players[0].nextPay)
        assertEquals(140, skip.players[0].cash)
    }

    @Test fun luckyWheelPaysByTheSpinBands() {
        for ((spin, coins) in listOf(1 to 10, 3 to 10, 4 to 20, 7 to 20, 8 to 40, 9 to 40, 10 to 80)) assertEquals(coins, LifeRoadRules.wheelCoins(spin))
        val seed = LRT.seedFor(10)
        val s = LRT.card(LRT.start(2, LRT.config(seed = seed)), "wheel", needsSpin = true)
        // the first draw of the seed is a 10: the card spin pays the jackpot
        assertEquals(100 + 80, LRT.act(s, LifeRoadAction.Spin).players[0].cash)
    }

    @Test fun spinCoinsCardsUseTheSpin() {
        val seed = LRT.seedFor(7)
        val s = LRT.card(LRT.start(2, LRT.config(seed = seed)), "raffle", needsSpin = true)
        assertEquals(100 + 35, LRT.act(s, LifeRoadAction.Spin).players[0].cash)
    }

    @Test fun jobOfferCardOpensAJobFair() {
        val r = play(base(), "job_offer")
        assertEquals(Phase.AWAIT_JOB, r.phase)
    }
}
