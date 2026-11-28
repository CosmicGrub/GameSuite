package com.gamesuite.games.propertytycoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Ignore
import org.junit.Test

/** Auctions, pledging and trades ship after the MVP (docs 1.5). Until [PtRelease] flips, the engine must refuse every such command. */
class PropertyTycoonAuctionTradeTest {
    private val e = PTT.engine

    @Test fun theReleaseGatesAreOffAndEveryV1CommandIsRefusedWithFeatureDisabled() {
        assertFalse(PtRelease.AUCTIONS_ENABLED || PtRelease.PLEDGING_ENABLED || PtRelease.TRADES_ENABLED)
        val s = PTT.table(3)
        val cmds = listOf(
            PtCommand.Pledge(1), PtCommand.Redeem(1), PtCommand.Bid(10), PtCommand.PassAuction,
            PtCommand.ProposeTrade(TradeSide(cash = 10), TradeSide(), 1), PtCommand.RespondTrade(1, true),
            PtCommand.CounterTrade(1, TradeSide(), TradeSide()), PtCommand.CancelTrade(1)
        )
        for (c in cmds) assertEquals("$c", PtReject.FEATURE_DISABLED, e.explain(s, 0, c))
    }

    @Test fun declinedTilesStayUnownedWithoutAuctions() {
        val r = PTT.act(PTT.rigged(PTT.table(), 3, 4), PtCommand.Roll)
        val d = PTT.act(r, PtCommand.DeclineBuy)
        assertEquals(null, d.holding(7))
        assertEquals(Phase.AWAIT_POST_ROLL, d.phase)
    }

    @Ignore("v1: auctions are gated off by PtRelease.AUCTIONS_ENABLED")
    @Test fun declinedPropertyStartsAuctionWithEveryActivePlayerEligible() {}

    @Ignore("v1: trades are gated off by PtRelease.TRADES_ENABLED")
    @Test fun tradeProposalMovesTilesCashAndFreeCardsOnlyAfterAcceptance() {}

    @Ignore("v1: pledging is gated off by PtRelease.PLEDGING_ENABLED")
    @Test fun pledgingPaysHalfPriceAndBlocksRent() {}
}
