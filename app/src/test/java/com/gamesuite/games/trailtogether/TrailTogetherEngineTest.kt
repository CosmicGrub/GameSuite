package com.gamesuite.games.trailtogether

import org.junit.Ignore
import org.junit.Test

/**
 * Scaffold stubs: Engine behaviour of section 2.3. Needs the real TrailEngine (phase 0); until then every test is ignored.
 * Every test is @Ignore until its feature lands, so the gate stays green. ID = row in the design doc section 3.9.
 */
class TrailTogetherEngineTest {
    @Ignore("scaffold: T07 not implemented yet")
    @Test
    fun sameSeedGivesIdenticalDeckAndTrail() {
        // TODO(T07): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T08 not implemented yet")
    @Test
    fun initialTrailHasNoTwoAdjacentSameColour() {
        // TODO(T08): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T09 not implemented yet")
    @Test
    fun dealGivesHandSizeFromNonFogCardsOnly() {
        // TODO(T09): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T10 not implemented yet")
    @Test
    fun hopCardMovesPalToNextLitSpotOfThatColour() {
        // TODO(T10): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T11 not implemented yet")
    @Test
    fun hopCardWithNoMatchingSpotIsRejectedAndHandUnchanged() {
        // TODO(T11): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T12 not implemented yet")
    @Test
    fun stoneCardLightsFrontierSpotWithCardColour() {
        // TODO(T12): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T13 not implemented yet")
    @Test
    fun lanternCanLightAnyColourStoneOrHopOneSpot() {
        // TODO(T13): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T14 not implemented yet")
    @Test
    fun burrowOpensOnlyWhenTrailIsLitThroughAndAcceptsAnyHop() {
        // TODO(T14): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T15 not implemented yet")
    @Test
    fun swapDiscardsDrawsAndStillPassesTheTurn() {
        // TODO(T15): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T16 not implemented yet")
    @Test
    fun fogCardAdvancesFogAndDrawsReplacement() {
        // TODO(T16): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T17 not implemented yet")
    @Test
    fun fogReachingTrackEndLosesTheTry() {
        // TODO(T17): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T18 not implemented yet")
    @Test
    fun allPalsHomeWinsBeforeAnyFurtherDraw() {
        // TODO(T18): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T19 not implemented yet")
    @Test
    fun handicapSeatGetsFourCardHand() {
        // TODO(T19): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T20 not implemented yet")
    @Test
    fun turnOrderRotatesAcrossSeatsIncludingHelpers() {
        // TODO(T20): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T21 not implemented yet")
    @Test
    fun hintNeverSuggestsAnIllegalMove() {
        // TODO(T21): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T22 not implemented yet")
    @Test
    fun smartPolicyWinRateStaysInsideTargetBandPerBandAndPalCount() {
        // TODO(T22): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T23 not implemented yet")
    @Test
    fun soloUsesTwoPalsAndOneHand() {
        // TODO(T23): implement per docs/design/kids-games/trail-together.md section 3.9
    }

    @Ignore("scaffold: T24 not implemented yet")
    @Test
    fun playAgainStartsFreshSeedAndKeepsSessionTally() {
        // TODO(T24): implement per docs/design/kids-games/trail-together.md section 3.9
    }
}
