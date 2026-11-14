package com.gamesuite.games.trailtogether

import com.gamesuite.settings.CpuDifficulty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rules of the game, one at a time on crafted tables, plus determinism, termination and balance (tests T07 to T24). */
class TrailTogetherEngineTest {
    private val B = TrailColour.BERRY
    private val S = TrailColour.SUN
    private val L = TrailColour.LEAF
    private val K = TrailColour.SKY

    private fun hop(c: TrailColour) = TrailCard(TrailCardKind.STEP, c).code
    private fun stone(c: TrailColour) = TrailCard(TrailCardKind.STONE, c).code
    private val lantern = "L"

    // ---- the deal ----

    @Test
    fun sameSeedGivesIdenticalDeckAndTrail() {
        for (b in TrailBand.entries) for (seats in 1..4) {
            val a = trailStarted(1234L, humans = seats, band = b).toSave()!!
            val c = trailStarted(1234L, humans = seats, band = b).toSave()!!
            assertEquals(a, c)
            val other = trailStarted(1235L, humans = seats, band = b).toSave()!!
            assertTrue("a different seed gives a different deal", other.hands != a.hands || other.pile != a.pile || other.trail != a.trail)
        }
    }

    @Test
    fun initialTrailHasNoTwoAdjacentSameColour() {
        for (b in TrailBand.entries) for (seed in 0L until 200L) {
            val s = trailStarted(seed, band = b).state()!!
            assertEquals(TrailRules.band(b).builtAtStart, s.trail.size)
            for (i in 1 until s.trail.size) assertNotEquals("seed $seed band $b", s.trail[i - 1], s.trail[i])
            assertTrue(s.trail.all { it in TrailRules.coloursOf(b) })
            assertEquals("every spot beyond the opening is dim", s.length + 1, s.spots.size)
            assertTrue(s.spots.take(s.trail.size).all { it.lit && it.colour != null })
            assertTrue(s.spots.drop(s.trail.size).none { it.lit && it.colour != null })
        }
    }

    @Test
    fun dealGivesHandSizeFromNonFogCardsOnly() {
        for (b in TrailBand.entries) for (seats in 1..4) for (seed in 0L until 30L) {
            val s = trailStarted(seed, humans = seats, band = b).state()!!
            assertEquals(seats, s.hands.size)
            for (h in s.hands) {
                assertEquals(3, h.size)
                assertTrue("no fog card in a hand", h.none { it.kind == TrailCardKind.FOG })
            }
            assertEquals(0, s.fog)
            assertEquals(TrailPhase.PICK_CARD, s.phase)
            assertEquals(0, s.currentSeat)
            assertTrue(s.pals.all { it.position == 0 })
        }
    }

    // ---- cards ----

    @Test
    fun hopCardMovesPalToNextLitSpotOfThatColour() {
        val e = trailCrafted(trail = LITTLE_OPENING + listOf(K), pals = listOf(0, 0), hands = listOf(listOf(hop(B), hop(S), stone(L)), listOf(stone(B), stone(S), stone(K))), pileFront = listOf(stone(K)))
        assertEquals(TrailMoveResult.OK, e.playCard(0, 0, TrailTarget(palIndex = 0)))
        val s = e.state()!!
        assertEquals(3, s.pals[0].position)
        assertEquals("only the chosen pal moves", 0, s.pals[1].position)
        assertEquals(1, s.currentSeat)
        assertEquals(3, s.hands[0].size)
        assertEquals(TrailCard(TrailCardKind.STONE, K), s.hands[0].last())
        val a = s.action!!
        assertEquals(TrailEventCode.HOP, a.kind)
        assertEquals(0, a.seat); assertEquals(0, a.palIndex); assertEquals(0, a.from); assertEquals(3, a.to)
        assertFalse(a.viaLantern)
        // a pal that is already ahead can hop again with another card (the sun stone at 1 is behind pal 0 now, so pal 1 takes it)
        assertEquals(TrailMoveResult.OK, e.swap(1, 0))
        assertEquals(TrailMoveResult.OK, e.playCard(0, 0, TrailTarget(palIndex = 1)))
        assertEquals(1, e.state()!!.pals[1].position)
    }

    @Test
    fun hopCardWithNoMatchingSpotIsRejectedAndHandUnchanged() {
        val e = trailCrafted(trail = LITTLE_OPENING, pals = listOf(0, 3), hands = listOf(listOf(hop(K), hop(B), stone(L)), listOf(stone(B), stone(S), stone(K))), pileFront = listOf(stone(K)))
        val before = e.toSave()!!
        assertEquals("no sky stone is lit", TrailMoveResult.NO_LEGAL_TARGET, e.playCard(0, 0, TrailTarget(palIndex = 0)))
        assertEquals("pal 1 stands on the last lit berry, nothing ahead", TrailMoveResult.NO_LEGAL_TARGET, e.playCard(0, 1, TrailTarget(palIndex = 1)))
        assertEquals("no pal named", TrailMoveResult.INVALID_TARGET, e.playCard(0, 1, TrailTarget()))
        assertEquals("pal out of range", TrailMoveResult.INVALID_TARGET, e.playCard(0, 1, TrailTarget(palIndex = 7)))
        assertEquals(TrailMoveResult.NOT_YOUR_TURN, e.playCard(1, 0, TrailTarget()))
        assertEquals(TrailMoveResult.NO_SUCH_CARD, e.playCard(0, 3, TrailTarget(palIndex = 0)))
        assertEquals(TrailMoveResult.NO_SUCH_CARD, e.playCard(0, -1, TrailTarget(palIndex = 0)))
        assertEquals(before, e.toSave())
    }

    @Test
    fun stoneCardLightsFrontierSpotWithCardColour() {
        val e = trailCrafted(trail = LITTLE_OPENING, pals = listOf(0, 0), hands = listOf(listOf(stone(K), hop(B), hop(S)), listOf(stone(B), stone(S), stone(L))), pileFront = listOf(hop(L)))
        assertEquals(TrailMoveResult.OK, e.playCard(0, 0, TrailTarget()))
        val s = e.state()!!
        assertEquals(5, s.trail.size)
        assertEquals(K, s.trail[4])
        assertEquals(K, s.spots[4].colour)
        val a = s.action!!
        assertEquals(TrailEventCode.STONE, a.kind)
        assertEquals(4, a.spotIndex)
        assertEquals(K, a.colour)
        assertFalse(a.burrowOpened)
        assertEquals(TrailEventCode.STONE, s.lastEvent!!.code)
    }

    @Test
    fun lanternCanLightAnyColourStoneOrHopOneSpot() {
        val e = trailCrafted(trail = LITTLE_OPENING, pals = listOf(0, 3), hands = listOf(listOf(lantern, lantern, hop(B)), listOf(stone(B), stone(S), stone(L))), pileFront = listOf(hop(L), hop(S)))
        // light a stone of a colour the hand does not hold
        assertEquals(TrailMoveResult.INVALID_TARGET, e.playCard(0, 0, TrailTarget(lanternMode = LanternMode.STONE)))
        assertEquals(TrailMoveResult.INVALID_TARGET, e.playCard(0, 0, TrailTarget(colour = TrailColour.PLUM, lanternMode = LanternMode.STONE)))
        assertEquals(TrailMoveResult.INVALID_TARGET, e.playCard(0, 0, TrailTarget(palIndex = 0)))
        assertEquals(TrailMoveResult.OK, e.playCard(0, 0, TrailTarget(colour = K, lanternMode = LanternMode.STONE)))
        var s = e.state()!!
        assertEquals(K, s.trail[4])
        assertTrue(s.action!!.viaLantern)
        // the other seat swaps, back to seat 0: a lantern hop of exactly one spot
        assertEquals(TrailMoveResult.OK, e.swap(1, 0))
        assertEquals(TrailMoveResult.OK, e.playCard(0, 0, TrailTarget(palIndex = 0, lanternMode = LanternMode.HOP)))
        s = e.state()!!
        assertEquals(1, s.pals[0].position)
        assertEquals(1, s.action!!.to - s.action!!.from)
        assertTrue(s.action!!.viaLantern)
    }

    @Test
    fun lanternHopNeedsALitNextSpot() {
        val e = trailCrafted(trail = LITTLE_OPENING, pals = listOf(3, 3), hands = listOf(listOf(lantern, hop(B), hop(S)), listOf(stone(B), stone(S), stone(L))), pileFront = listOf(hop(L)))
        val before = e.toSave()
        assertEquals(TrailMoveResult.NO_LEGAL_TARGET, e.playCard(0, 0, TrailTarget(palIndex = 0, lanternMode = LanternMode.HOP)))
        assertEquals(before, e.toSave())
        // but it can still light a stone
        assertEquals(TrailMoveResult.OK, e.playCard(0, 0, TrailTarget(colour = S, lanternMode = LanternMode.STONE)))
    }

    @Test
    fun burrowOpensOnlyWhenTrailIsLitThroughAndAcceptsAnyHop() {
        // 15 lit: not open, so no hop can reach the Burrow, and a stone lights the last spot
        val e = trailCrafted(trail = LITTLE_FULL_TRAIL.take(15), pals = listOf(13, 2), hands = listOf(listOf(stone(K), hop(B), hop(K)), listOf(stone(B), stone(S), stone(L))), pileFront = listOf(hop(L)))
        assertFalse(e.state()!!.burrowOpen)
        assertEquals("the Burrow is shut: nothing berry-coloured ahead of 13", TrailMoveResult.NO_LEGAL_TARGET, e.playCard(0, 1, TrailTarget(palIndex = 0)))
        assertEquals(TrailMoveResult.OK, e.playCard(0, 0, TrailTarget()))
        var s = e.state()!!
        assertTrue(s.burrowOpen)
        assertTrue(s.action!!.burrowOpened)
        assertEquals(TrailEventCode.BURROW_OPEN, s.lastEvent!!.code)
        assertTrue(s.spots.last().lit)
        // now any colour hops a pal home, even one with no matching stone ahead
        assertEquals(TrailMoveResult.OK, e.swap(1, 0))
        val hand0 = s.hands[0]
        val idx = e.state()!!.hands[0].indexOfFirst { it == TrailCard(TrailCardKind.STEP, B) }
        assertTrue(idx >= 0)
        assertEquals(TrailMoveResult.OK, e.playCard(0, idx, TrailTarget(palIndex = 0)))
        s = e.state()!!
        assertEquals(16, s.pals[0].position)
        assertEquals(1, s.palsHome)
        assertFalse(s.over)
        assertTrue(hand0.isNotEmpty())
    }

    @Test
    fun stoneCardsAreNotLegalOnceTheBurrowIsOpen() {
        val e = trailCrafted(trail = LITTLE_FULL_TRAIL, pals = listOf(3, 9), hands = listOf(listOf(stone(B), lantern, hop(S)), listOf(stone(K), stone(S), stone(L))), pileFront = listOf(hop(L)))
        val before = e.toSave()
        assertEquals(TrailMoveResult.NO_LEGAL_TARGET, e.playCard(0, 0, TrailTarget()))
        assertEquals(TrailMoveResult.NO_LEGAL_TARGET, e.playCard(0, 1, TrailTarget(colour = B, lanternMode = LanternMode.STONE)))
        assertEquals(before, e.toSave())
    }

    @Test
    fun swapDiscardsDrawsAndStillPassesTheTurn() {
        val e = trailCrafted(trail = LITTLE_OPENING, pals = listOf(0, 0), hands = listOf(listOf(hop(K), hop(B), stone(L)), listOf(stone(B), stone(S), stone(K))), pileFront = listOf(hop(S), hop(L)))
        val playsBefore = e.state()!!.plays
        assertEquals(TrailMoveResult.OK, e.swap(0, 1))
        val s = e.state()!!
        assertEquals(listOf(hop(K), stone(L), hop(S)), s.hands[0].map { it.code })
        assertEquals(1, s.currentSeat)
        assertEquals(playsBefore + 1, s.plays)
        assertEquals(TrailEventCode.SWAP, s.action!!.kind)
        assertEquals(TrailEventCode.SWAP, s.lastEvent!!.code)
        assertEquals(TrailMoveResult.NOT_YOUR_TURN, e.swap(0, 0))
        assertEquals(TrailMoveResult.NO_SUCH_CARD, e.swap(1, 5))
        // swap is always legal, even when a card could be played
        assertEquals(TrailMoveResult.OK, e.swap(1, 0))
        assertEquals(0, e.state()!!.currentSeat)
    }

    // ---- the Fog ----

    @Test
    fun fogCardAdvancesFogAndDrawsReplacement() {
        val e = trailCrafted(trail = LITTLE_OPENING, pals = listOf(0, 0), hands = listOf(listOf(hop(K), hop(B), stone(L)), listOf(stone(B), stone(S), stone(K))), pileFront = listOf("F", hop(S)))
        assertEquals(TrailMoveResult.OK, e.swap(0, 0))
        val s = e.state()!!
        assertEquals(1, s.fog)
        assertEquals(3, s.hands[0].size)
        assertEquals(hop(S), s.hands[0].last().code)
        assertEquals(1, s.action!!.fogSteps)
        assertEquals(TrailEventCode.FOG_STEP, s.lastEvent!!.code)
        assertFalse(s.over)
        // two fog cards in a row both count, and the hand still ends full of real cards
        val e2 = trailCrafted(trail = LITTLE_OPENING, pals = listOf(0, 0), hands = listOf(listOf(hop(K), hop(B), stone(L)), listOf(stone(B), stone(S), stone(K))), pileFront = listOf("F", "F", hop(S)))
        assertEquals(TrailMoveResult.OK, e2.swap(0, 0))
        val s2 = e2.state()!!
        assertEquals(2, s2.fog); assertEquals(2, s2.action!!.fogSteps); assertEquals(3, s2.hands[0].size)
        assertTrue(s2.hands[0].none { it.kind == TrailCardKind.FOG })
    }

    @Test
    fun fogReachingTrackEndLosesTheTry() {
        val e = trailCrafted(trail = LITTLE_OPENING, pals = listOf(0, 0), hands = listOf(listOf(hop(K), hop(B), stone(L)), listOf(stone(B), stone(S), stone(K))), pileFront = listOf("F"), fog = 9)
        assertEquals(10, e.state()!!.fogLength)
        assertEquals(TrailMoveResult.OK, e.swap(0, 0))
        val s = e.state()!!
        assertEquals(TrailPhase.LOST, s.phase)
        assertTrue(s.over); assertFalse(s.won)
        assertEquals(10, s.fog)
        assertTrue(s.action!!.lost)
        assertEquals(TrailEventCode.LOSE, s.lastEvent!!.code)
        assertEquals(1, e.sessionTries); assertEquals(0, e.sessionWins)
        assertEquals(TrailMoveResult.NOT_ACTIVE, e.swap(0, 0))
        assertEquals(TrailMoveResult.NOT_ACTIVE, e.playCard(0, 0, TrailTarget()))
        assertNull("a finished try has nothing to resume", e.toSave())
        assertFalse(e.helperStep())
        assertNull(e.hint())
    }

    @Test
    fun allPalsHomeWinsBeforeAnyFurtherDraw() {
        val e = trailCrafted(
            trail = LITTLE_FULL_TRAIL, pals = listOf(16, 15),
            hands = listOf(listOf(lantern, hop(S), hop(B)), listOf(stone(B), stone(S), stone(L))), pileFront = listOf(hop(K), "F")
        )
        val left = e.state()!!.cardsLeft
        assertEquals(TrailMoveResult.OK, e.playCard(0, 0, TrailTarget(palIndex = 1, lanternMode = LanternMode.HOP)))
        val s = e.state()!!
        assertEquals(TrailPhase.WON, s.phase)
        assertTrue(s.won)
        assertEquals("no card is drawn after the winning play", left, s.cardsLeft)
        assertEquals(2, s.hands[0].size)
        assertEquals(0, s.fog)
        assertTrue(s.action!!.won)
        assertEquals(TrailEventCode.WIN, s.lastEvent!!.code)
        assertEquals(1, e.sessionTries); assertEquals(1, e.sessionWins)
        assertEquals(2, s.palsHome)
        assertNull(e.toSave())
    }

    // ---- seats ----

    @Test
    fun handicapSeatGetsFourCardHand() {
        val e = trailStarted(9L, humans = 3, handicap = "p2")
        assertEquals(listOf(3, 4, 3), e.state()!!.hands.map { it.size })
        assertEquals(listOf(3, 4, 3), e.state()!!.handSizes)
        assertEquals(TrailMoveResult.OK, e.swap(0, 0))
        assertEquals(TrailMoveResult.OK, e.swap(1, 0))
        assertEquals("the extra card is refilled all game", listOf(3, 4, 3), e.state()!!.hands.map { it.size })
        // a handicap for a seat that is not at the table is ignored
        assertEquals(listOf(3, 3), trailStarted(9L, humans = 2, handicap = "p4").state()!!.hands.map { it.size })
        // the comma list form
        assertEquals(listOf(4, 3), trailStarted(9L, humans = 2, handicap = "4,3").state()!!.hands.map { it.size })
    }

    @Test
    fun turnOrderRotatesAcrossSeatsIncludingHelpers() {
        val e = trailStarted(21L, humans = 2, bots = 1)
        assertEquals(0, e.state()!!.currentSeat)
        assertFalse("a person's turn is not the helper's", e.helperStep())
        assertEquals(TrailMoveResult.OK, e.swap(0, 0))
        assertEquals(1, e.state()!!.currentSeat)
        assertFalse(e.helperStep())
        assertEquals(TrailMoveResult.OK, e.swap(1, 0))
        assertEquals(2, e.state()!!.currentSeat)
        assertEquals(TrailMoveResult.NOT_YOUR_TURN, e.swap(0, 0))
        assertTrue(e.helperStep())
        assertEquals(0, e.state()!!.currentSeat)
        val a = e.state()!!.action!!
        assertEquals(2, a.seat)
    }

    @Test
    fun hintNeverSuggestsAnIllegalMove() {
        var swaps = 0
        var plays = 0
        for (b in TrailBand.entries) for (seats in 1..4) for (seed in 0L until 40L) {
            val e = trailStarted(seed * 31 + seats, humans = seats, band = b)
            var guard = 0
            while (e.state()!!.over.not()) {
                check(guard++ < 600)
                val s = e.state()!!
                val h = e.hint()
                assertNotNull(h)
                h!!
                val r = if (h.isSwap) { swaps++; e.swap(s.currentSeat, h.handIndex) } else { plays++; e.playCard(s.currentSeat, h.handIndex, h.target) }
                assertEquals("hint $h on $b seed $seed", TrailMoveResult.OK, r)
            }
        }
        assertTrue("both kinds of hint occur", swaps > 0 && plays > 0)
    }

    @Test
    fun hintOnACraftedTablePicksTheBestHop() {
        // seat 0 holds a berry hop that jumps from 0 to 3, a sun hop to 1, and a stone card: the long hop is the best play
        val e = trailCrafted(trail = LITTLE_OPENING, pals = listOf(0, 3), hands = listOf(listOf(hop(S), stone(K), hop(B)), listOf(stone(B), stone(S), stone(L))), pileFront = listOf(hop(L)))
        val h = e.hint()!!
        assertFalse(h.isSwap)
        // pal 1 (at 3) has nothing ahead; pal 0 can reach 1 with sun (jump 1) or 3 with berry (jump 3): the berry hop wins
        assertEquals(2, h.handIndex)
        assertEquals(0, h.target.palIndex)
        // with only a dead hop and a dead stone (burrow shut, all colours behind) the hint is a swap
        val dead = trailCrafted(trail = LITTLE_OPENING, pals = listOf(3, 3), hands = listOf(listOf(hop(K), hop(S), hop(L)), listOf(stone(B), stone(S), stone(L))), pileFront = listOf(hop(B)))
        val d = dead.hint()!!
        assertTrue("nothing can be played, so the hint is a swap", d.isSwap)
        assertTrue(d.handIndex in 0..2)
        assertEquals(TrailMoveResult.OK, dead.swap(0, d.handIndex))
    }

    @Test
    fun soloUsesTwoPalsAndOneHand() {
        val e = trailStarted(4L, humans = 1)
        val s = e.state()!!
        assertEquals(2, s.pals.size)
        assertEquals(1, s.hands.size)
        assertEquals(3, s.hands[0].size)
        assertEquals(TrailRules.deck(TrailBand.LITTLE, 2).fogCards, s.fogLength)
        assertEquals(TrailMoveResult.OK, e.swap(0, 0))
        assertEquals("a solo player plays every turn", 0, e.state()!!.currentSeat)
        assertEquals(listOf(0, 0), e.state()!!.pals.map { it.ownerSeat })
    }

    @Test
    fun palsOwnTheirSeatsBadgeInOrder() {
        val s = trailStarted(4L, humans = 3).state()!!
        assertEquals(listOf(0, 1, 2), s.pals.map { it.ownerSeat })
        val s4 = trailStarted(4L, humans = 4).state()!!
        assertEquals(listOf(0, 1, 2, 3), s4.pals.map { it.ownerSeat })
    }

    // ---- sessions ----

    @Test
    fun playAgainStartsFreshSeedAndKeepsSessionTally() {
        val e = trailEngine(humans = 0, bots = 3)
        e.start(100L)
        e.playOut()
        val tries = e.sessionTries
        val wins = e.sessionWins
        assertEquals(1, tries)
        val firstSeed = e.state()!!.seed
        e.start(101L)
        val s = e.state()!!
        assertNotEquals(firstSeed, s.seed)
        assertEquals(TrailPhase.PICK_CARD, s.phase)
        assertEquals(tries, s.tries); assertEquals(wins, s.wins)
        assertEquals(0, s.plays); assertEquals(0, s.fog)
        assertTrue(s.pals.all { it.position == 0 })
        e.playOut()
        assertEquals(2, e.sessionTries)
        // a fresh table (setup) starts the tally again
        e.setup(trailSeats(0, 3))
        assertEquals(0, e.sessionTries)
        assertNull(e.state())
    }

    // ---- helper pals ----

    @Test
    fun helperPalsPlayEveryLevelWithoutStallingAndKeepTheTableConsistent() {
        for (level in listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) for (b in TrailBand.entries) for (seats in 1..4) {
            for (seed in 0L until 12L) {
                val e = trailEngine(humans = 0, bots = seats, band = b, level = level)
                e.start(seed * 7919 + seats)
                var guard = 0
                while (e.state()!!.over.not()) {
                    check(guard++ < 600) { "no end: $level $b $seats seed $seed" }
                    assertTrue(e.helperStep())
                    // every step leaves a table the strict validator accepts
                    val save = e.toSave()
                    if (save != null) assertTrue("$level $b $seats seed $seed step $guard", TrailSaveCodec.isValid(save, trailSeats(0, seats)))
                }
                val s = e.state()!!
                assertTrue(s.won || s.fog == s.fogLength)
                assertEquals(1, e.sessionTries)
            }
        }
    }

    @Test
    fun helperDecisionsAreDeterministicAndSurviveAResume() {
        for (level in listOf(CpuDifficulty.EASY, CpuDifficulty.MEDIUM, CpuDifficulty.HARD)) {
            val a = trailStarted(555L, humans = 0, bots = 3, band = TrailBand.MEDIUM, level = level)
            val b = trailStarted(555L, humans = 0, bots = 3, band = TrailBand.MEDIUM, level = level)
            repeat(8) { a.helperStep(); b.helperStep() }
            assertEquals(a.toSave(), b.toSave())
            // snapshot b, restore it into a third engine, and let both finish: identical outcomes
            val c = trailEngine(humans = 0, bots = 3, band = TrailBand.MEDIUM, level = level)
            assertTrue(c.restore(b.toSave()!!))
            assertEquals(b.state()!!.core(), c.state()!!.core())
            b.playOut(); c.playOut()
            assertEquals(b.state()!!.core(), c.state()!!.core())
        }
    }

    @Test
    fun pausedEngineLetsNoHelperPlay() {
        val e = trailStarted(2L, humans = 0, bots = 2)
        e.pause()
        val before = e.toSave()
        assertFalse(e.helperStep())
        assertEquals(before, e.toSave())
        e.resume()
        assertTrue(e.helperStep())
        assertNotEquals(before, e.toSave())
    }

    @Test
    fun easyHelpersSometimesSwapEvenWhenAPlayExistsAndHardOnesPlayTheBestHop() {
        var easySwaps = 0
        var easyTurns = 0
        for (seed in 0L until 60L) {
            val e = trailStarted(seed, humans = 0, bots = 2, level = CpuDifficulty.EASY)
            repeat(10) {
                if (!e.state()!!.over) { e.helperStep(); easyTurns++; if (e.state()!!.action?.kind == TrailEventCode.SWAP) easySwaps++ }
            }
        }
        assertTrue("easy helpers swap some of the time ($easySwaps of $easyTurns)", easySwaps in 1 until easyTurns)
        // hard: on a crafted table with a three-spot hop available the helper takes it
        val e = trailCrafted(humans = 0, bots = 2, trail = LITTLE_OPENING, pals = listOf(0, 3), hands = listOf(listOf(hop(S), stone(K), hop(B)), listOf(stone(B), stone(S), stone(L))), pileFront = listOf(hop(L)))
        assertTrue(e.helperStep())
        val a = e.state()!!.action!!
        assertEquals(TrailEventCode.HOP, a.kind)
        assertEquals(3, a.to)
    }

    // ---- balance ----

    /** Win rate of the all-helper `smart` policy over [seeds] tries. */
    private fun winRate(band: TrailBand, pals: Int, seeds: Int): Double {
        var wins = 0
        for (i in 0 until seeds) {
            val e = trailStarted(i * 2654435761L + pals * 97L + band.ordinal, humans = 0, bots = pals, band = band, level = CpuDifficulty.HARD)
            e.playOut()
            if (e.state()!!.won) wins++
        }
        return wins.toDouble() / seeds
    }

    /** The measured win rates of the smart policy (2000 seeds, this Kotlin engine); see the design doc section 3.10. */
    private val target = mapOf(
        TrailBand.LITTLE to listOf(0.90, 0.90, 0.90),
        TrailBand.MEDIUM to listOf(0.80, 0.80, 0.80),
        TrailBand.BIG to listOf(0.68, 0.66, 0.66)
    )

    @Test
    fun smartPolicyWinRateStaysInsideTargetBandPerBandAndPalCount() {
        for (b in TrailBand.entries) for (pals in 2..4) {
            val rate = winRate(b, pals, 2000)
            val want = target.getValue(b)[pals - 2]
            assertEquals("win rate of $b with $pals pals was $rate", want, rate, 0.06)
        }
    }

    @Test
    fun everyBandIsHarderThanTheOneBelowItAndEveryTryEnds() {
        val rates = TrailBand.entries.map { winRate(it, 3, 600) }
        assertTrue("Little ${rates[0]} > Medium ${rates[1]} > Big ${rates[2]}", rates[0] > rates[1] && rates[1] > rates[2])
    }
}
