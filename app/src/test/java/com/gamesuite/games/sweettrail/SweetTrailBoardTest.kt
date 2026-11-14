package com.gamesuite.games.sweettrail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The trail itself: colours, shortcuts, landmarks, puddles and the lengths (docs/design/boardgames/sweet-trail.md, 2.3). */
class SweetTrailBoardTest {
    @Test fun colourSequenceHasSixtyCharacters() {
        assertEquals(60, SweetTrailBoard.COLOUR_SEQUENCE.length)
        assertEquals(SweetTrailBoard.PATH_SQUARES, SweetTrailBoard.COLOUR_SEQUENCE.length)
        assertTrue(SweetTrailBoard.COLOUR_SEQUENCE.all { TrailColour.ofLetter(it) != null })
    }

    @Test fun eachColourAppearsExactlyTenTimes() {
        for (c in TrailColour.values()) assertEquals(c.name, 10, SweetTrailBoard.COLOUR_SEQUENCE.count { it == c.letter })
    }

    @Test fun noColourRunIsLongerThanTwo() {
        val seq = SweetTrailBoard.COLOUR_SEQUENCE
        for (i in 0 until seq.length - 2) assertFalse("run of three at ${i + 1}", seq[i] == seq[i + 1] && seq[i] == seq[i + 2])
    }

    @Test fun everyBlockOfSixIsAPermutationOfTheSixColours() {
        for (b in 0 until 10) {
            val block = SweetTrailBoard.COLOUR_SEQUENCE.substring(b * 6, b * 6 + 6)
            assertEquals(TrailColour.values().map { it.letter }.toSet(), block.toSet())
        }
    }

    @Test fun shortcutsAreForwardAndNeverChain() {
        assertEquals(setOf(8, 24, 42), SweetTrailBoard.SHORTCUTS.keys)
        for ((start, end) in SweetTrailBoard.SHORTCUTS) {
            assertTrue("$start -> $end is not forward", end > start)
            assertTrue("span of $start", end - start in 11..13)
            assertFalse("$end is also a start: a chain", end in SweetTrailBoard.SHORTCUTS.keys)
            assertTrue(SweetTrailBoard.SHORTCUT_NAMES.containsKey(start))
        }
        assertEquals(SweetTrailBoard.SHORTCUTS.size, SweetTrailBoard.SHORTCUTS.values.toSet().size)
    }

    @Test fun landmarksAvoidShortcutPuddleAndStartSquares() {
        val bad = SweetTrailBoard.SHORTCUTS.keys + SweetTrailBoard.SHORTCUTS.values +
            TrailLength.values().flatMap { it.puddles }
        for (l in TrailLandmark.values()) {
            assertFalse("${l.name} sits on a special square", l.square in bad)
            assertTrue(l.square in 1..SweetTrailBoard.PATH_SQUARES)
        }
        assertEquals(listOf(11, 17, 29, 35, 46, 57), TrailLandmark.values().map { it.square })
    }

    @Test fun nextSquareOfColourIsStrictlyAhead() {
        for (from in 0..SweetTrailBoard.PATH_SQUARES) for (c in TrailColour.values()) {
            val to = SweetTrailBoard.nextSquareOfColour(from, c)
            assertTrue("$from $c -> $to", to > from)
            if (to != SweetTrailBoard.FINISH) {
                assertEquals(c, SweetTrailBoard.colourAt(to))
                for (sq in from + 1 until to) assertTrue("$sq is closer", SweetTrailBoard.colourAt(sq) != c)
            } else {
                for (sq in from + 1..SweetTrailBoard.PATH_SQUARES) assertTrue(SweetTrailBoard.colourAt(sq) != c)
            }
        }
    }

    @Test fun nextSquareOfColourBeyondTheLastIsTheFinish() {
        assertEquals(SweetTrailBoard.FINISH, SweetTrailBoard.nextSquareOfColour(57, TrailColour.CHERRY))
        assertEquals(SweetTrailBoard.FINISH, SweetTrailBoard.nextSquareOfColour(60, TrailColour.GRAPE))
        assertEquals(SweetTrailBoard.FINISH, SweetTrailBoard.nextSquareOfColour(0, TrailColour.CHERRY, count = 11))
    }

    @Test fun secondNextSquareOfColourSkipsTheFirst() {
        // cherry red is on 1, 8, 17, 21, 30, 34, 37, 48, 51, 56
        assertEquals(1, SweetTrailBoard.nextSquareOfColour(0, TrailColour.CHERRY, 1))
        assertEquals(8, SweetTrailBoard.nextSquareOfColour(0, TrailColour.CHERRY, 2))
        assertEquals(17, SweetTrailBoard.nextSquareOfColour(1, TrailColour.CHERRY, 2))
        assertEquals(56, SweetTrailBoard.nextSquareOfColour(51, TrailColour.CHERRY, 1))
        assertEquals(SweetTrailBoard.FINISH, SweetTrailBoard.nextSquareOfColour(51, TrailColour.CHERRY, 2))
    }

    @Test fun colourAtStartAndFinishIsNull() {
        for (sq in listOf(-1, 0, SweetTrailBoard.FINISH, 62, 1000)) assertNull(SweetTrailBoard.colourAt(sq))
        assertEquals(TrailColour.CHERRY, SweetTrailBoard.colourAt(1))
        assertEquals(TrailColour.GRAPE, SweetTrailBoard.colourAt(60))
    }

    @Test fun shortLengthStartsOnSquareTwentyEight() {
        assertEquals(28, TrailLength.SHORT.startSquare)
        assertEquals(0, TrailLength.STANDARD.startSquare)
        assertEquals(0, TrailLength.LONG.startSquare)
        val b = stBoard(rules = SweetTrailRules(TrailLength.SHORT))
        assertEquals(listOf(28, 28), b.positions)
        assertTrue(TrailLength.SHORT.shortcuts && TrailLength.SHORT.doubles)
    }

    @Test fun longLengthHasNoShortcutsAndTwoPuddles() {
        assertFalse(TrailLength.LONG.shortcuts)
        assertEquals(setOf(33, 50), SweetTrailRules(TrailLength.LONG).puddleSquares)
        assertEquals(setOf(33), SweetTrailRules(TrailLength.STANDARD).puddleSquares)
        assertNull(SweetTrailBoard.square(8, TrailLength.LONG).shortcutTo)
        assertEquals(20, SweetTrailBoard.square(8, TrailLength.STANDARD).shortcutTo)
        assertTrue(SweetTrailBoard.square(50, TrailLength.LONG).puddle)
        assertFalse(SweetTrailBoard.square(50, TrailLength.STANDARD).puddle)
        assertEquals(TrailLength.STANDARD, TrailLength.fromId("nonsense"))
        assertEquals(TrailLength.STANDARD, TrailLength.fromId(null))
        assertEquals(TrailLength.LONG, TrailLength.fromId("long"))
    }

    @Test fun stickyOffRemovesEveryPuddle() {
        for (length in TrailLength.values()) {
            assertEquals(emptySet<Int>(), SweetTrailRules(length, sticky = false).puddleSquares)
            for (sq in 0..SweetTrailBoard.FINISH) assertFalse(SweetTrailBoard.square(sq, length, sticky = false).puddle)
        }
    }

    @Test fun theScreenLayoutWindsAndEverySquareHasItsOwnCell() {
        val cells = (1..60).map { Pair(SweetTrailLayout.column(it), SweetTrailLayout.row(it)) }
        assertEquals(60, cells.toSet().size)
        for ((c, r) in cells) {
            assertTrue(c in 0 until SweetTrailLayout.COLUMNS)
            assertTrue(r in 1..SweetTrailLayout.TRAIL_ROWS)
        }
        // consecutive squares are neighbours (the trail is one unbroken winding road)
        for (sq in 1 until 60) {
            val d = Math.abs(cells[sq].first - cells[sq - 1].first) + Math.abs(cells[sq].second - cells[sq - 1].second)
            assertEquals("square $sq", 1, d)
        }
        // row 1 runs left to right, row 2 right to left
        assertEquals(0, SweetTrailLayout.column(1)); assertEquals(9, SweetTrailLayout.column(10))
        assertEquals(9, SweetTrailLayout.column(11)); assertEquals(0, SweetTrailLayout.column(20))
        // the start strip is below the road and the Party Table above it
        assertTrue(SweetTrailLayout.centerY(0) > SweetTrailLayout.centerY(1))
        assertTrue(SweetTrailLayout.centerY(61) < SweetTrailLayout.centerY(60))
    }

    @Test fun sharedSquaresSplitIntoQuartersAndTheStripLinesUp() {
        val a = SweetTrailLayout.piecePosition(0, 12, 2)
        val b = SweetTrailLayout.piecePosition(1, 12, 2)
        assertTrue(a != b)
        val s0 = SweetTrailLayout.piecePosition(0, 0, 4)
        val s3 = SweetTrailLayout.piecePosition(3, 0, 4)
        assertTrue(s3.first > s0.first)
        assertEquals(s0.second, s3.second, 0.0001f)
    }
}
