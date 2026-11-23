package com.gamesuite.games.gemcascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GemMatchShapeTest {
    private fun groupsOf(board: GemBoard) = GemMatcher.groups(board)

    @Test fun threeInARowIsALine3WithNoSpecial() {
        val b = baseBoard().put(6, 2 to 1, 2 to 2, 2 to 3)
        val g = groupsOf(b).single()
        assertEquals(MatchShape.LINE_3, g.shape)
        assertEquals(SpecialKind.NONE, g.special)
        assertEquals(3, g.cells.size)
    }

    @Test fun fourInARowMakesAFlareAcrossTheLine() {
        val h = groupsOf(baseBoard().put(6, 2 to 0, 2 to 1, 2 to 2, 2 to 3)).single()
        assertEquals(MatchShape.LINE_4, h.shape)
        assertEquals(SpecialKind.FLARE_COL, h.special)
        val v = groupsOf(baseBoard().put(6, 0 to 2, 1 to 2, 2 to 2, 3 to 2)).single()
        assertEquals(MatchShape.LINE_4, v.shape)
        assertEquals(SpecialKind.FLARE_ROW, v.special)
    }

    @Test fun fiveInARowMakesAPrism() {
        val g = groupsOf(baseBoard().put(6, 2 to 0, 2 to 1, 2 to 2, 2 to 3, 2 to 4)).single()
        assertEquals(MatchShape.LINE_5, g.shape)
        assertEquals(SpecialKind.PRISM, g.special)
    }

    @Test fun anLShapeMakesABurst() {
        val g = groupsOf(baseBoard().put(6, 1 to 1, 1 to 2, 1 to 3, 2 to 1, 3 to 1)).single()
        assertEquals(MatchShape.L, g.shape)
        assertEquals(SpecialKind.BURST, g.special)
        assertEquals(5, g.cells.size)
    }

    @Test fun aTShapeMakesABurst() {
        val g = groupsOf(baseBoard().put(6, 1 to 1, 1 to 2, 1 to 3, 2 to 2, 3 to 2)).single()
        assertEquals(MatchShape.T, g.shape)
        assertEquals(SpecialKind.BURST, g.special)
    }

    @Test fun aCrossMakesABurst() {
        val g = groupsOf(baseBoard().put(6, 2 to 1, 2 to 2, 2 to 3, 1 to 2, 3 to 2)).single()
        assertEquals(MatchShape.CROSS, g.shape)
        assertEquals(SpecialKind.BURST, g.special)
    }

    @Test fun twoFoursInOneGroupMakeANova() {
        val g = groupsOf(baseBoard().put(6, 1 to 0, 1 to 1, 1 to 2, 1 to 3, 2 to 0, 3 to 0, 4 to 0)).single()
        assertEquals(MatchShape.DOUBLE_4, g.shape)
        assertEquals(SpecialKind.NOVA, g.special)
    }

    @Test fun separateRunsOfOneColourAreSeparateGroups() {
        val b = baseBoard().put(6, 0 to 0, 0 to 1, 0 to 2).put(6, 4 to 2, 4 to 3, 4 to 4)
        assertEquals(2, groupsOf(b).size)
    }

    @Test fun obstaclesAndPrismsNeverMatch() {
        val b = baseBoard()
        b[2, 1] = Cell(PieceType.STONE, hp = 1); b[2, 2] = Cell(PieceType.STONE, hp = 1); b[2, 3] = Cell(PieceType.STONE, hp = 1)
        b[4, 1] = Cell(PieceType.GEM, 6, SpecialKind.PRISM); b[4, 2] = Cell(PieceType.GEM, 6, SpecialKind.PRISM); b[4, 3] = Cell(PieceType.GEM, 6, SpecialKind.PRISM)
        assertTrue(groupsOf(b).isEmpty())
        assertFalse(GemMatcher.hasAny(b))
    }

    @Test fun aSpecialGemStillMatchesByColour() {
        val b = baseBoard().put(6, 2 to 1, 2 to 3)
        b[2, 2] = Cell(PieceType.GEM, 6, SpecialKind.FLARE_ROW)
        assertEquals(1, groupsOf(b).size)
    }
}

class GemSwapTest {
    @Test fun aSwapThatMakesNoMatchSwapsBackAndCostsNothing() {
        val b = baseBoard()
        val e = engineOn(b)
        val before = e.board.copy()
        val r = e.swap(Pos(0, 0), Pos(0, 1))
        assertFalse(r.accepted)
        assertEquals(RejectReason.NO_MATCH, r.reason)
        assertEquals(listOf(GemEvent.Swap(Pos(0, 0), Pos(0, 1)), GemEvent.SwapBack(Pos(0, 0), Pos(0, 1))), r.events)
        assertTrue(before.sameAs(e.board))
        assertEquals(0, e.movesMade)
    }

    @Test fun nonAdjacentAndOutOfBoardSwapsAreRejected() {
        val e = engineOn(baseBoard())
        assertEquals(RejectReason.NOT_ADJACENT, e.swap(Pos(0, 0), Pos(0, 2)).reason)
        assertEquals(RejectReason.NOT_ADJACENT, e.swap(Pos(0, 0), Pos(1, 1)).reason)
        assertEquals(RejectReason.OUT_OF_BOARD, e.swap(Pos(0, 0), Pos(-1, 0)).reason)
        assertEquals(RejectReason.OUT_OF_BOARD, e.swap(Pos(5, 5), Pos(5, 6)).reason)
    }

    @Test fun lockedAndNonGemCellsCannotBeSwapped() {
        val b = baseBoard()
        b[1, 1] = b[1, 1].copy(locked = true)
        b[3, 3] = Cell(PieceType.STONE, hp = 1)
        val e = engineOn(b)
        assertEquals(RejectReason.BLOCKED, e.swap(Pos(1, 1), Pos(1, 2)).reason)
        assertEquals(RejectReason.BLOCKED, e.swap(Pos(3, 3), Pos(3, 4)).reason)
    }

    @Test fun aMatchingSwapMakesTheFlareWhereThePlayerDroppedTheGem() {
        val b = baseBoard().put(6, 2 to 0, 2 to 1, 2 to 3, 1 to 2)
        val e = engineOn(b)
        val r = e.swap(Pos(1, 2), Pos(2, 2))
        assertTrue(r.accepted)
        val made = r.events.filterIsInstance<GemEvent.SpecialCreated>().first()
        assertEquals(Pos(2, 2), made.at)
        assertEquals(SpecialKind.FLARE_COL, made.special)
        assertEquals(1, e.movesMade)
        assertSettled(e.board)
    }

    @Test fun theFirstEventOfAcceptedSwapIsSwapThenChainStepOne() {
        val b = baseBoard().put(6, 2 to 0, 2 to 1, 1 to 2)
        val r = engineOn(b).swap(Pos(1, 2), Pos(2, 2))
        assertTrue(r.accepted)
        assertEquals(GemEvent.Swap(Pos(1, 2), Pos(2, 2)), r.events[0])
        assertEquals(GemEvent.ChainStep(1, 1), r.events[1])
    }

    @Test fun legalActionsAreExactlyTheAcceptedSwaps() {
        for (seed in 1L..60L) {
            val e = GemEngine.start(GemMode.ZEN, 7, 7, seed)
            val legal = e.legalActions().filterIsInstance<GemAction.SwapAction>().toSet()
            assertTrue(legal.isNotEmpty())
            for (r in 0 until 7) for (c in 0 until 7) {
                for ((dr, dc) in listOf(0 to 1, 1 to 0)) {
                    if (r + dr > 6 || c + dc > 6) continue
                    val a = Pos(r, c); val b = Pos(r + dr, c + dc)
                    val accepted = e.copy().swap(a, b).accepted
                    assertEquals("seed $seed swap $a $b", accepted, GemAction.SwapAction(a, b) in legal)
                }
            }
        }
    }
}

class GemSpecialTest {
    private fun centreBoard(special: SpecialKind, kind: Int = 6): GemBoard =
        baseBoard().putCell(2, 2, Cell(PieceType.GEM, kind, special))

    private fun fire(special: SpecialKind): GemEvent.SpecialActivated {
        val e = engineOn(centreBoard(special))
        val r = e.useBooster(BoosterKind.HAMMER, Pos(2, 2), null)
        assertTrue(r.accepted)
        return r.events.filterIsInstance<GemEvent.SpecialActivated>().first { it.at == Pos(2, 2) }
    }

    @Test fun flareRowClearsItsRow() = assertEquals(6, fire(SpecialKind.FLARE_ROW).affected.size)
    @Test fun flareColumnClearsItsColumn() = assertEquals((0..5).map { Pos(it, 2) }, fire(SpecialKind.FLARE_COL).affected)
    @Test fun burstClearsTheThreeByThreeBlock() = assertEquals(9, fire(SpecialKind.BURST).affected.size)
    @Test fun novaClearsRowColumnAndBlock() {
        // row 6 + column 6 - 1 shared + the four block corners = 15
        assertEquals(15, fire(SpecialKind.NOVA).affected.size)
    }

    @Test fun prismActivatedByAChainClearsTheCommonestColour() {
        val b = centreBoard(SpecialKind.PRISM)
        val e = engineOn(b)
        val common = GemSpecials.commonestColour(b)
        val expected = GemSpecials.colourCells(b, common, Pos(2, 2))
        val ev = e.useBooster(BoosterKind.HAMMER, Pos(2, 2), null).events
        assertEquals(expected.sorted(), ev.filterIsInstance<GemEvent.SpecialActivated>().first().affected)
    }

    @Test fun swappingAPrismWithAGemClearsThatGemsColour() {
        val b = baseBoard().putCell(2, 2, Cell(PieceType.GEM, 6, SpecialKind.PRISM))
        val target = b[2, 3].kind
        val total = GemSpecials.colourCells(b, target).size
        val e = engineOn(b)
        val r = e.swap(Pos(2, 2), Pos(2, 3))
        assertTrue(r.accepted)
        val cleared = r.events.filterIsInstance<GemEvent.Clear>().first()
        assertTrue(cleared.cells.size >= total)
        assertEquals(ClearCause.SPECIAL, cleared.cause)
    }

    @Test fun aSpecialInsideAMatchFiresAndChainsIntoOtherSpecials() {
        val b = baseBoard().put(6, 2 to 0, 2 to 1, 1 to 2)
        b[2, 0] = Cell(PieceType.GEM, 6, SpecialKind.FLARE_COL)
        b[5, 0] = Cell(PieceType.GEM, 0, SpecialKind.BURST)
        val e = engineOn(b)
        val r = e.swap(Pos(1, 2), Pos(2, 2))
        assertTrue(r.accepted)
        val fired = r.events.filterIsInstance<GemEvent.SpecialActivated>()
        assertTrue(fired.any { it.special == SpecialKind.FLARE_COL })
        assertTrue("the flare's column covers the burst, which must fire too", fired.any { it.special == SpecialKind.BURST })
    }

    @Test fun everyPairOfSpecialClassesHasAComboAndTheTableIsSymmetric() {
        val kinds = listOf(SpecialKind.FLARE_ROW, SpecialKind.FLARE_COL, SpecialKind.PRISM, SpecialKind.BURST, SpecialKind.NOVA)
        for (a in kinds) for (b in kinds) {
            val ab = GemSpecials.comboFor(a, b)
            assertNotNull("$a + $b", ab)
            assertEquals(ab, GemSpecials.comboFor(b, a))
        }
        assertEquals(10, GemSpecials.comboTable.size)
        assertNull(GemSpecials.comboFor(SpecialKind.NONE, SpecialKind.BURST))
    }

    private fun comboOf(a: SpecialKind, b: SpecialKind): Pair<GemEvent.ComboActivated, GemEngine> {
        val board = baseBoard()
        board[2, 1] = Cell(PieceType.GEM, 6, a)
        board[2, 2] = Cell(PieceType.GEM, 6, b)
        val e = engineOn(board)
        val r = e.swap(Pos(2, 1), Pos(2, 2))
        assertTrue("$a + $b", r.accepted)
        assertSettled(e.board)
        return r.events.filterIsInstance<GemEvent.ComboActivated>().single() to e
    }

    @Test fun flareFlareIsACross() {
        val (c, _) = comboOf(SpecialKind.FLARE_ROW, SpecialKind.FLARE_COL)
        assertEquals(ComboEffect.CROSS, c.effect); assertEquals(11, c.affected.size)
    }

    @Test fun flareBurstIsAThreeWideCross() {
        val (c, _) = comboOf(SpecialKind.FLARE_ROW, SpecialKind.BURST)
        assertEquals(ComboEffect.FAT_CROSS_1, c.effect); assertEquals(27, c.affected.size)
    }

    @Test fun flareNovaIsAFiveWideCross() {
        val (c, _) = comboOf(SpecialKind.FLARE_COL, SpecialKind.NOVA)
        assertEquals(ComboEffect.FAT_CROSS_2, c.effect); assertEquals(35, c.affected.size)
    }

    @Test fun burstBurstIsALargeBlast() {
        val (c, _) = comboOf(SpecialKind.BURST, SpecialKind.BURST)
        assertEquals(ComboEffect.AREA_2, c.effect); assertEquals(25, c.affected.size)
    }

    @Test fun burstNovaIsAnEvenLargerBlast() {
        val (c, _) = comboOf(SpecialKind.BURST, SpecialKind.NOVA)
        assertEquals(ComboEffect.AREA_3, c.effect); assertEquals(36, c.affected.size)
    }

    @Test fun prismPrismAndNovaNovaClearTheBoard() {
        for (pair in listOf(SpecialKind.PRISM to SpecialKind.PRISM, SpecialKind.NOVA to SpecialKind.NOVA)) {
            val (c, e) = comboOf(pair.first, pair.second)
            assertEquals(ComboEffect.BOARD, c.effect); assertEquals(36, c.affected.size)
            assertTrue(e.gemsCleared >= 36)
        }
    }

    @Test fun prismWithAnotherSpecialTurnsItsColourIntoThatSpecial() {
        val expect = mapOf(
            SpecialKind.FLARE_ROW to ComboEffect.COLOUR_TO_FLARES,
            SpecialKind.BURST to ComboEffect.COLOUR_TO_BURSTS,
            SpecialKind.NOVA to ComboEffect.COLOUR_TO_NOVAS
        )
        for ((sp, effect) in expect) {
            val board = baseBoard().put(6, 0 to 0, 5 to 5, 3 to 4, 4 to 1)
            board[2, 1] = Cell(PieceType.GEM, 6, SpecialKind.PRISM)
            board[2, 2] = Cell(PieceType.GEM, 6, sp)
            val e = engineOn(board)
            val r = e.swap(Pos(2, 1), Pos(2, 2))
            assertTrue(r.accepted)
            val c = r.events.filterIsInstance<GemEvent.ComboActivated>().single()
            assertEquals(effect, c.effect)
            val created = r.events.takeWhile { it !is GemEvent.Clear }.filterIsInstance<GemEvent.SpecialCreated>()
            assertEquals("every other colour-6 gem becomes a special", 4, created.count { it.kind == 6 })
            assertEquals(created.size, created.count { it.special.specialClass == sp.specialClass })
            assertTrue(r.events.filterIsInstance<GemEvent.SpecialActivated>().size >= 4)
        }
    }
}

class GemCascadeTest {
    @Test fun gravityDropsPiecesAndSpawnsEnterFromAbove() {
        val b = baseBoard().put(6, 4 to 0, 4 to 1, 4 to 2, 3 to 3)
        val e = engineOn(b)
        val r = e.swap(Pos(3, 3), Pos(4, 3))
        assertTrue(r.accepted)
        val falls = r.events.filterIsInstance<GemEvent.Fall>()
        assertTrue(falls.isNotEmpty())
        assertTrue(falls.all { it.to.r > it.from.r && it.to.c == it.from.c })
        val spawns = r.events.filterIsInstance<GemEvent.Spawn>()
        assertTrue(spawns.isNotEmpty())
        assertTrue(spawns.all { it.fromRow < it.at.r })
        assertSettled(e.board)
    }

    @Test fun chainStepsCountUpAndTheMultiplierFollowsTheStepWithACap() {
        var longest = 0
        for (seed in 1L..300L) {
            val e = GemEngine.start(GemMode.ZEN, 8, 8, seed)
            repeat(30) {
                val a = e.legalActions().firstOrNull() ?: return@repeat
                val ev = e.apply(a).events
                val steps = ev.filterIsInstance<GemEvent.ChainStep>()
                steps.forEachIndexed { i, s ->
                    assertEquals(i + 1, s.n)
                    assertEquals(minOf(s.n, ModeRules.MAX_MULTIPLIER), s.multiplier)
                }
                longest = maxOf(longest, steps.size)
            }
        }
        assertTrue("some game should cascade at least twice", longest >= 2)
    }

    @Test fun theScoreIsTheSumOfTheScoreDeltas() {
        for (seed in 1L..80L) {
            val e = GemEngine.start(GemMode.CLASSIC, 8, 8, seed)
            var sum = 0
            repeat(25) {
                val a = e.legalActions().firstOrNull() ?: return@repeat
                for (d in e.apply(a).events.filterIsInstance<GemEvent.ScoreDelta>()) {
                    sum += d.points
                    assertEquals(sum, d.total)
                }
            }
            assertEquals(sum, e.score)
        }
    }

    @Test fun everyBoardIsSettledAfterEveryMove() {
        for (seed in 1L..120L) {
            val e = GemEngine.start(GemMode.ZEN, 8, 8, seed)
            repeat(20) {
                val a = e.legalActions().firstOrNull() ?: return@repeat
                e.apply(a)
                assertSettled(e.board)
                anyBoardLegal(e)
            }
        }
    }
}

class GemDealTest {
    @Test fun noInitialMatchesAndALegalMoveOver2000Seeds() {
        for (seed in 1L..2000L) {
            val e = GemEngine.start(GemMode.CLASSIC, 8, 8, seed)
            assertSettled(e.board)
            assertTrue("seed $seed has no legal move", e.legalActions().isNotEmpty())
            for (cell in e.board.cells) assertTrue(cell.kind in 0 until 7)
        }
    }

    @Test fun everySizeAndColourCountDealsCleanly() {
        for (size in 6..10) for (kinds in 3..7) for (seed in 1L..12L) {
            val e = GemEngine.start(GemMode.ZEN, size, size, seed * 31 + kinds, kinds)
            assertSettled(e.board)
            assertTrue(e.legalActions().isNotEmpty())
            for (cell in e.board.cells) assertTrue(cell.kind in 0 until kinds)
        }
    }

    @Test fun nonSquareBoardsWork() {
        val e = GemEngine.start(GemMode.ZEN, 6, 10, 3)
        assertEquals(6, e.rows); assertEquals(10, e.cols)
        assertSettled(e.board)
    }

    @Test fun theSameSeedDealsTheSameBoard() {
        assertEquals(GemEngine.start(GemMode.ZEN, 8, 8, 99).board.encode(), GemEngine.start(GemMode.ZEN, 8, 8, 99).board.encode())
        assertFalse(GemEngine.start(GemMode.ZEN, 8, 8, 99).board.encode() == GemEngine.start(GemMode.ZEN, 8, 8, 100).board.encode())
    }

    @Test fun shuffleAlwaysLeavesALegalMoveAndNoMatch() {
        for (seed in 1L..300L) {
            val kinds = 3 + (seed % 5).toInt()
            val e = GemEngine.start(GemMode.ZEN, 6 + (seed % 5).toInt(), 6 + (seed % 5).toInt(), seed, kinds)
            val before = e.board.copy()
            val ev = ArrayList<GemEvent>()
            assertTrue(e.shuffleBoard(ev))
            assertSettled(e.board)
            assertTrue(e.legalActions().isNotEmpty())
            assertLogReproduces(before, ev, e.board, "shuffle seed $seed")
        }
    }

    @Test fun shuffleLeavesLockedGemsAndObstaclesInPlace() {
        val b = baseBoard()
        b[1, 1] = b[1, 1].copy(locked = true)
        b[2, 4] = Cell(PieceType.STONE, hp = 2)
        val e = engineOn(b, kinds = 5)
        val lockedKind = b[1, 1]
        assertTrue(e.shuffleBoard(ArrayList()))
        assertEquals(lockedKind, e.board[1, 1])
        assertEquals(Cell(PieceType.STONE, hp = 2), e.board[2, 4])
    }
}

class GemEventLogTest {
    @Test fun applyingTheEventsReproducesTheBoardInEveryMode() {
        val modes = listOf(GemMode.CLASSIC, GemMode.ZEN, GemMode.LIGHTNING, GemMode.TWIST, GemMode.MINE, GemMode.BUTTERFLIES, GemMode.COUNTDOWN)
        for (mode in modes) for (seed in 1L..40L) {
            val e = GemEngine.start(mode, 8, 8, seed)
            val bot = GemBot(BotLevel.EASY, seed)
            for (i in 0 until 60) {
                if (e.status != GemStatus.PLAYING) break
                val action = bot.choose(e) ?: break
                val before = e.board.copy()
                val r = e.apply(action)
                assertLogReproduces(before, r.events, e.board, "$mode seed $seed move $i $action")
                if (mode == GemMode.LIGHTNING) e.apply(GemAction.Elapse(900))
            }
        }
    }

    @Test fun rejectedAttemptsLeaveTheBoardUntouched() {
        val e = GemEngine.start(GemMode.ZEN, 8, 8, 4)
        val legal = e.legalActions().filterIsInstance<GemAction.SwapAction>().map { it.a to it.b }.toSet()
        var tried = 0
        loop@ for (r in 0 until 7) for (c in 0 until 7) {
            val a = Pos(r, c); val b = Pos(r, c + 1)
            if ((a to b) in legal) continue
            val before = e.board.copy()
            val res = e.swap(a, b)
            assertFalse(res.accepted)
            assertLogReproduces(before, res.events, e.board, "reject")
            assertTrue(before.sameAs(e.board))
            if (++tried > 20) break@loop
        }
        assertTrue(tried > 0)
    }

    @Test fun boosterEventsAlsoReproduceTheBoard() {
        for (seed in 1L..40L) {
            val e = GemEngine.start(GemMode.ZEN, 8, 8, seed)
            val before = e.board.copy()
            val r = e.useBooster(BoosterKind.HAMMER, Pos(3, 3), null)
            assertLogReproduces(before, r.events, e.board, "hammer $seed")
            val b2 = e.board.copy()
            val r2 = e.useBooster(BoosterKind.COLOUR_BOMB, Pos(4, 4), null)
            assertLogReproduces(b2, r2.events, e.board, "colour bomb $seed")
            val b3 = e.board.copy()
            val r3 = e.useBooster(BoosterKind.SHUFFLE, null, null)
            assertLogReproduces(b3, r3.events, e.board, "shuffle booster $seed")
        }
    }
}

class GemDeterminismTest {
    private fun play(seed: Long, mode: GemMode, moves: Int): GemEngine {
        val e = GemEngine.start(mode, 8, 8, seed)
        val bot = GemBot(BotLevel.MEDIUM, 5)
        repeat(moves) {
            if (e.status == GemStatus.PLAYING) bot.choose(e)?.let { a -> e.apply(a); if (mode == GemMode.LIGHTNING) e.apply(GemAction.Elapse(700)) }
        }
        return e
    }

    @Test fun theSameSeedAndMovesGiveTheSameGame() {
        for (mode in GemMode.values().filter { it != GemMode.QUEST }) {
            val a = play(11, mode, 30)
            val b = play(11, mode, 30)
            assertEquals(a.board.encode(), b.board.encode())
            assertEquals(a.score, b.score)
            assertEquals(a.rng.state, b.rng.state)
        }
    }

    @Test fun aReplayOfTheActionLogReproducesTheExactGame() {
        for (mode in GemMode.values().filter { it != GemMode.QUEST }) for (seed in 1L..8L) {
            val live = play(seed, mode, 40)
            val replayed = GemReplay.run(GemSetup(mode, 8, 8, seed), live.actionLog.toList())
            assertNotNull("$mode $seed", replayed)
            assertEquals(live.board.encode(), replayed!!.board.encode())
            assertEquals(live.score, replayed.score)
            assertEquals(live.movesMade, replayed.movesMade)
            assertEquals(live.status, replayed.status)
        }
    }

    @Test fun theReplayTextRoundTrips() {
        val live = play(3, GemMode.LIGHTNING, 30)
        val text = GemReplay.encode(live.actionLog)
        assertEquals(live.actionLog.toList(), GemReplay.decode(text))
        assertNull(GemReplay.decode("X1,2"))
        assertNull(GemReplay.decode("S1,2,3"))
        assertEquals(emptyList<GemAction>(), GemReplay.decode(""))
    }

    @Test fun aCopyPlaysExactlyLikeTheOriginal() {
        val e = GemEngine.start(GemMode.ZEN, 8, 8, 21)
        val c = e.copy()
        val a = e.legalActions().first()
        assertEquals(e.apply(a).events, c.apply(a).events)
        assertEquals(e.board.encode(), c.board.encode())
    }

    @Test fun utcDateHelpersAreStable() {
        assertEquals(0L, UtcDate.epochDay(0))
        assertEquals(-1L, UtcDate.epochDay(-1))
        assertEquals("1970-01-01", UtcDate.iso(0))
        assertEquals("2000-03-01", UtcDate.iso(11017))
        assertEquals("2026-11-22", UtcDate.iso(UtcDate.epochDay(1_795_305_600_000L).let { it }))
        assertEquals(GemLevels.daily(20_000).seed, GemLevels.daily(20_000).seed)
        assertTrue(GemLevels.daily(20_000).seed != GemLevels.daily(20_001).seed)
    }
}
