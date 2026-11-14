package com.gamesuite.games.sweettrail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What each card does (docs/design/boardgames/sweet-trail.md, 2.1). Squares: see the table in the doc. */
class SweetTrailRulesTest {
    private fun SweetTrailState.drawn(id: Int) = stEngine.draw(withTop(id))

    @Test fun singleCardMovesToTheNextSquareOfItsColour() {
        val s = stBoard().at(0, 0).drawn(single(TrailColour.CHERRY))
        assertEquals(listOf(1, 0), s.positions)
        assertEquals(SweetTrailEvent.MOVE, s.event)
        assertEquals(0, s.eventSeat)
        assertEquals(0, s.eventFrom); assertEquals(1, s.eventTo)
        // from square 1 the next cherry square is 8 (a shortcut start), so use lemon: 3
        val t = stBoard().at(1, 0).drawn(single(TrailColour.LEMON))
        assertEquals(3, t.positions[0])
    }

    @Test fun doubleCardMovesToTheSecondNextSquareOfItsColour() {
        val s = stBoard().at(0, 0).drawn(double(TrailColour.LEMON))
        assertEquals(12, s.positions[0]) // lemon is on 3 and 12
        assertEquals(SweetTrailEvent.MOVE, s.event)
    }

    @Test fun doubleCardWithOnlyOneSquareLeftReachesTheFinish() {
        val s = stBoard().at(55, 0).drawn(double(TrailColour.CHERRY)) // only 56 is left
        assertEquals(SweetTrailBoard.FINISH, s.positions[0])
        assertTrue(s.over)
        assertEquals(SweetTrailEvent.WIN, s.event)
    }

    @Test fun singleCardWithNoSquareAheadReachesTheFinish() {
        val s = stBoard().at(57, 0).drawn(single(TrailColour.CHERRY)) // last cherry is 56
        assertEquals(SweetTrailBoard.FINISH, s.positions[0])
        assertTrue(s.over)
    }

    @Test fun landmarkAheadJumpsThere() {
        for (l in TrailLandmark.values()) {
            val s = stBoard().at(0, 0).drawn(landmark(l))
            assertEquals(l.name, l.square, s.positions[0])
            assertTrue(l.name, s.event == SweetTrailEvent.MOVE || s.event == SweetTrailEvent.SHORTCUT)
        }
        assertEquals(SweetTrailEvent.MOVE, stBoard().at(0, 0).drawn(landmark(TrailLandmark.COOKIE_HILL)).event)
    }

    @Test fun landmarkBehindLeavesThePieceAndDances() {
        val s = stBoard().at(30, 0).drawn(landmark(TrailLandmark.COOKIE_HILL))
        assertEquals(30, s.positions[0])
        assertEquals(SweetTrailEvent.PASSED, s.event)
        assertEquals(30, s.eventFrom); assertEquals(30, s.eventTo)
        assertEquals(1, s.currentPlayerIndex) // the turn still passes
        assertFalse(s.over)
    }

    @Test fun landmarkOnTheCurrentSquareLeavesThePiece() {
        val s = stBoard().at(11, 0).drawn(landmark(TrailLandmark.COOKIE_HILL))
        assertEquals(11, s.positions[0])
        assertEquals(SweetTrailEvent.PASSED, s.event)
    }

    @Test fun landingOnAShortcutStartTakesTheShortcutOnce() {
        for ((start, end) in SweetTrailBoard.SHORTCUTS) {
            val colour = SweetTrailBoard.colourAt(start)!!
            // stand on the square just before the start's previous same-coloured square so the start is the next of its colour
            var from = start - 1
            while (SweetTrailBoard.nextSquareOfColour(from, colour) != start) from -= 1
            val s = stBoard().at(from, 0).drawn(single(colour))
            assertEquals("start $start", end, s.positions[0])
            assertEquals(SweetTrailEvent.SHORTCUT, s.event)
            assertEquals(end, s.eventTo)
            assertEquals(1, s.shortcutsFound[0])
            assertEquals(start, SweetTrailMoves.shortcutStartFor(end))
        }
    }

    @Test fun shortcutEndNeverTriggersAnotherJump() {
        // 16 -> lime 20 is the end of the Jelly Bridge: the piece stays on 20
        val s = stBoard().at(16, 0).drawn(single(TrailColour.LIME))
        assertEquals(20, s.positions[0])
        assertEquals(SweetTrailEvent.MOVE, s.event)
        assertEquals(0, s.shortcutsFound[0])
        // and a piece standing on an end moves on from there normally
        val t = stBoard().at(20, 0).drawn(single(TrailColour.CHERRY))
        assertEquals(21, t.positions[0])
    }

    @Test fun shortcutsAreOffInLongLength() {
        val long = SweetTrailRules(TrailLength.LONG)
        val s = stBoard(rules = long).at(1, 0).drawn(single(TrailColour.CHERRY)) // next cherry is 8
        assertEquals(8, s.positions[0])
        assertEquals(SweetTrailEvent.MOVE, s.event)
        assertEquals(0, s.shortcutsFound[0])
    }

    @Test fun landingOnThePuddleSticksForOneTurn() {
        val s = stBoard().at(29, 0).drawn(single(TrailColour.LIME)) // next lime is 33, the Gooey Puddle
        assertEquals(33, s.positions[0])
        assertEquals(SweetTrailEvent.PUDDLE, s.event)
        assertEquals(listOf(1, 0), s.stuck)
        assertEquals(1, s.puddleStops[0])
        // a second puddle in the long trail
        val long = stBoard(rules = SweetTrailRules(TrailLength.LONG)).at(40, 0).drawn(double(TrailColour.GRAPE))
        // grape squares after 40: 41, 46, 50 (the second puddle): the second next is 46, so check 50 with a single from 46
        val p2 = stBoard(rules = SweetTrailRules(TrailLength.LONG)).at(46, 0).drawn(single(TrailColour.GRAPE))
        assertEquals(50, p2.positions[0])
        assertEquals(listOf(1, 0), p2.stuck)
        assertEquals(46, long.positions[0])
    }

    @Test fun aStuckSeatSkipsItsDrawThenIsFree() {
        val stuck = stBoard().at(29, 0).drawn(single(TrailColour.LIME))
        val other = stEngine.draw(stuck.withTop(single(TrailColour.CHERRY)))
        assertEquals(0, other.currentPlayerIndex)
        val pileBefore = other.pile
        val freed = stEngine.draw(other)
        assertEquals(SweetTrailEvent.STUCK, freed.event)
        assertEquals(33, freed.positions[0])
        assertEquals(listOf(0, 0), freed.stuck)
        assertEquals("a stuck turn draws no card", pileBefore, freed.pile)
        assertNull(freed.eventCard)
        assertEquals(1, freed.currentPlayerIndex)
        // the turn after that the piece draws and moves on
        val again = stEngine.draw(stEngine.draw(freed).withTop(single(TrailColour.CHERRY)))
        assertEquals(34, again.positions[0])
        assertTrue(stEngine.validate(freed).isEmpty())
    }

    @Test fun twoSeatsCanBeStuckAtOnce() {
        var s = stBoard().at(29, 29)
        s = stEngine.draw(s.withTop(single(TrailColour.LIME)))
        s = stEngine.draw(s.withTop(single(TrailColour.LIME)))
        assertEquals(listOf(1, 1), s.stuck)
        assertTrue(stEngine.validate(s).isEmpty())
        s = stEngine.draw(s)
        s = stEngine.draw(s)
        assertEquals(listOf(0, 0), s.stuck)
        assertEquals(0, s.currentPlayerIndex)
    }

    @Test fun stickyOffMakesThePuddleAPlainSquare() {
        val s = stBoard(rules = SweetTrailRules(sticky = false)).at(29, 0).drawn(single(TrailColour.LIME))
        assertEquals(33, s.positions[0])
        assertEquals(SweetTrailEvent.MOVE, s.event)
        assertEquals(listOf(0, 0), s.stuck)
        assertEquals(0, s.puddleStops[0])
    }

    @Test fun aLandmarkDanceOnAPuddleDoesNotStickAgain() {
        val s = stBoard().copy(positions = listOf(33, 0)).drawn(landmark(TrailLandmark.COOKIE_HILL))
        assertEquals(SweetTrailEvent.PASSED, s.event)
        assertEquals(listOf(0, 0), s.stuck)
    }

    @Test fun noCardEverMovesAPieceBackwards() {
        for (seed in 0L until 300L) {
            val rules = SweetTrailRules(TrailLength.values()[(seed % 3).toInt()], sticky = seed % 5 != 0L)
            stPlayOut(stBoard(3, rules, seed = seed, wish = setOf(1))) { before, after ->
                for (i in before.positions.indices) assertTrue(after.positions[i] >= before.positions[i])
            }
        }
        // every card from every square
        for (from in 0..SweetTrailBoard.PATH_SQUARES) for (id in 0 until 66) {
            val card = SweetTrailDeck.byId(id, TrailLength.STANDARD)!!
            assertTrue(SweetTrailMoves.step(SweetTrailRules(), from, card).to >= from)
        }
    }

    @Test fun firstPieceOnTheFinishWinsAndTheBoardEnds() {
        val s = stBoard(3).at(10, 57, 20).copy(currentPlayerIndex = 1).drawn(single(TrailColour.CHERRY))
        assertTrue(s.over)
        assertEquals("p2", s.winnerPlayerId)
        assertEquals(1, s.currentPlayerIndex)
        assertEquals(listOf(10, SweetTrailBoard.FINISH, 20), s.positions)
        assertEquals(SweetTrailReject.OVER, stEngine.explainDraw(s))
        assertSame(s, stEngine.draw(s))
        assertSame(s, stEngine.decide(s, false))
        assertTrue(stEngine.validate(s).isEmpty())
    }

    @Test fun aPieceOnSixtyWinsWithAnyColourOrDoubleCard() {
        for (c in TrailColour.values()) {
            assertTrue(stBoard().at(60, 0).drawn(single(c)).over)
            assertTrue(stBoard().at(60, 0).drawn(double(c)).over)
        }
    }

    @Test fun turnPassesToTheNextSeatAfterEveryMove() {
        var s = stBoard(3)
        val seen = ArrayList<Int>()
        repeat(9) {
            seen += s.currentPlayerIndex
            s = stEngine.draw(s)
            if (s.over) return
        }
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0, 1, 2).take(seen.size), seen.take(9))
    }

    @Test fun starterRotatesBetweenBoards() {
        val g = stNewGame(stRoster(3))
        val starters = ArrayList<Int>()
        repeat(4) {
            starters += g.state.value!!.currentPlayerIndex
            g.playAgain()
        }
        assertEquals(listOf(0, 1, 2, 0), starters)
    }

    @Test fun reshuffleHappensWhenThePileIsEmpty() {
        val full = stBoard(seed = 3)
        val dry = full.copy(pile = emptyList(), discard = full.pile)
        val s = stEngine.draw(dry)
        assertTrue(s.eventReshuffled)
        assertEquals(1, s.reshuffles)
        assertEquals(66, s.pile.size + s.discard.size) // 65 in the new pile, the drawn card in the new discard
        assertEquals((0 until 66).toList(), (s.pile + s.discard + listOfNotNull(s.card)).sorted())
        // the reshuffle order is a pure function of the board seed and the reshuffle count
        assertEquals(s, stEngine.draw(dry))
    }

    @Test fun wishCardIsShownBeforeTheMoveWhenTheSeatHoldsOne() {
        val s = stBoard(wish = setOf(0)).at(0, 0).drawn(single(TrailColour.CHERRY))
        assertTrue(s.pending)
        assertEquals(SweetTrailEvent.PENDING, s.event)
        assertEquals(single(TrailColour.CHERRY), s.card)
        assertEquals(listOf(0, 0), s.positions)
        assertEquals(0, s.currentPlayerIndex)
        assertEquals(SweetTrailReject.DECIDE_FIRST, stEngine.explainDraw(s))
        assertSame(s, stEngine.draw(s))
        assertTrue(stEngine.validate(s).isEmpty())
    }
}
