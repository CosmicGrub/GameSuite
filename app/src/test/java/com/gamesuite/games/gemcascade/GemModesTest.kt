package com.gamesuite.games.gemcascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A board with no match and no legal swap (found by pattern search, then random search). */
fun deadBoard(): GemBoard {
    val rules = ModeRules.of(GemMode.ZEN)
    for (a in 1..6) for (b in 1..6) {
        val bd = GemBoard(6, 6)
        for (r in 0 until 6) for (c in 0 until 6) bd[r, c] = Cell.gem((a * r + b * c) % 7)
        if (!GemMatcher.hasAny(bd) && !GemMoves.hasLegal(bd, rules)) return bd
    }
    for (seed in 1L..200_000L) {
        val bd = GemBoard(6, 6)
        GemDeal.fillEmpty(bd, GemRng(seed), 7)
        if (!GemMoves.hasLegal(bd, rules)) return bd
    }
    throw AssertionError("no dead board found")
}

class GemModeRulesTest {
    @Test fun classicLevelsUpAtTheThresholds() {
        val e = GemEngine.start(GemMode.CLASSIC, 8, 8, 3)
        e.score = 990
        val ev = e.apply(e.legalActions().first()).events
        assertTrue(ev.any { it is GemEvent.LevelUp && it.level == 2 })
        assertEquals(2, e.classicLevel)
        assertEquals(1000, ModeRules.classicThreshold(1))
    }

    @Test fun classicEndsWhenNoMovesAreLeft() {
        val e = engineOn(deadBoard(), GemMode.CLASSIC)
        val ev = ArrayList<GemEvent>()
        e.endOfTurn(ev, false)
        assertEquals(GemStatus.OVER, e.status)
        assertEquals(OverReason.NO_MOVES, e.overReason)
        assertTrue(ev.any { it is GemEvent.GameOver })
        assertEquals(RejectReason.GAME_OVER, e.swap(Pos(0, 0), Pos(0, 1)).reason)
    }

    @Test fun zenShufflesInsteadOfEnding() {
        val e = engineOn(deadBoard(), GemMode.ZEN)
        val ev = ArrayList<GemEvent>()
        e.endOfTurn(ev, false)
        assertEquals(GemStatus.PLAYING, e.status)
        assertTrue(ev.any { it is GemEvent.NoMovesShuffle })
        assertTrue(e.legalActions().isNotEmpty())
        assertSettled(e.board)
        assertFalse(e.rules.timed || e.rules.limitedMoves)
    }

    @Test fun lightningRunsOnTheInjectedClock() {
        val e = GemEngine.start(GemMode.LIGHTNING, 8, 8, 5)
        assertEquals(75_000L, e.timeLeftMs)
        e.elapse(74_000)
        assertEquals(GemStatus.PLAYING, e.status)
        val ev = e.elapse(2_000).events
        assertEquals(GemStatus.OVER, e.status)
        assertEquals(OverReason.TIME_UP, e.overReason)
        assertTrue(ev.any { it is GemEvent.GameOver && it.reason == OverReason.TIME_UP })
        assertEquals(RejectReason.GAME_OVER, e.swap(Pos(0, 0), Pos(0, 1)).reason)
    }

    @Test fun lightningSpecialsAndChainsAddTime() {
        val b = baseBoard().put(6, 2 to 0, 2 to 1, 2 to 3, 1 to 2)
        val e = engineOn(b, GemMode.LIGHTNING)
        val before = e.timeLeftMs
        val ev = e.swap(Pos(1, 2), Pos(2, 2)).events
        val bonus = ev.filterIsInstance<GemEvent.TimeBonus>()
        assertTrue(bonus.any { it.reason == TimeReason.SPECIAL && it.ms == 2_000L })
        assertTrue(e.timeLeftMs > before)
    }

    @Test fun lightningSpeedMeterFillsAndDoublesScoring() {
        val e = GemEngine.start(GemMode.LIGHTNING, 8, 8, 9)
        e.speedMeter = 75
        e.lastMoveAtMs = 0
        e.elapse(1_000)
        val ev = e.apply(e.legalActions().first()).events
        assertTrue(ev.any { it is GemEvent.SpeedBonus })
        assertTrue(e.burstUntilMs > e.elapsedMs)
        assertEquals(2, (ev.first { it is GemEvent.ChainStep } as GemEvent.ChainStep).multiplier)
    }

    @Test fun twistOnlyRotates() {
        val e = GemEngine.start(GemMode.TWIST, 8, 8, 4)
        assertEquals(RejectReason.NOT_ALLOWED, e.swap(Pos(0, 0), Pos(0, 1)).reason)
        val legal = e.legalActions()
        assertTrue(legal.isNotEmpty() && legal.all { it is GemAction.RotateAction })
        val a = legal.first() as GemAction.RotateAction
        val r = e.rotate(a.topLeft, a.clockwise)
        assertTrue(r.accepted)
        val rot = r.events.first { it is GemEvent.Rotate } as GemEvent.Rotate
        assertTrue(rot.steps in 1..3)
        assertSettled(e.board)
    }

    @Test fun aTwistWithNoMatchIsRefusedAndCostsNothing() {
        val e = GemEngine.start(GemMode.TWIST, 8, 8, 6)
        val good = e.legalActions().filterIsInstance<GemAction.RotateAction>().map { it.topLeft to it.clockwise }.toSet()
        var checked = false
        for (r in 0 until 7) for (c in 0 until 7) {
            if ((Pos(r, c) to true) in good) continue
            val before = e.board.copy()
            val res = e.rotate(Pos(r, c), true)
            assertFalse(res.accepted)
            assertEquals(RejectReason.NO_MATCH, res.reason)
            assertEquals(listOf<GemEvent>(GemEvent.RotateBack(Pos(r, c), true)), res.events)
            assertTrue(before.sameAs(e.board))
            assertEquals(0, e.movesMade)
            checked = true
            break
        }
        assertTrue(checked)
        assertEquals(RejectReason.OUT_OF_BOARD, e.rotate(Pos(7, 7), true).reason)
    }

    @Test fun rotationIsAQuarterTurn() {
        val b = baseBoard()
        val orig = b.copy()
        repeat(4) { GemRotation.rotate(b, Pos(1, 1), true) }
        assertTrue(orig.sameAs(b))
        GemRotation.rotate(b, Pos(1, 1), true)
        GemRotation.rotate(b, Pos(1, 1), false)
        assertTrue(orig.sameAs(b))
        GemRotation.rotate(b, Pos(1, 1), true)
        assertEquals(orig[2, 1], b[1, 1])
        assertEquals(orig[1, 1], b[1, 2])
    }

    @Test fun mineStartsWithDirtAndTreasure() {
        val e = GemEngine.start(GemMode.MINE, 8, 8, 2)
        assertSettled(e.board)
        assertEquals(2, e.board.positionsOf(PieceType.TREASURE).size)
        assertEquals(16, e.board.positionsOf(PieceType.STONE).size)
        assertEquals(35, e.movesLeft)
    }

    @Test fun aTreasureThatReachesTheBottomIsCollectedForExtraMoves() {
        val b = baseBoard()
        b[4, 0] = Cell(PieceType.TREASURE)
        val e = engineOn(b, GemMode.MINE)
        val ev = e.useBooster(BoosterKind.HAMMER, Pos(5, 0), null).events
        assertTrue(ev.any { it is GemEvent.Escaped && it.piece == PieceType.TREASURE && it.at == Pos(5, 0) })
        assertEquals(1, e.treasuresCollected)
        assertEquals(38, e.movesLeft)
        assertSettled(e.board)
    }

    @Test fun butterfliesRiseAndEscapeThroughTheTop() {
        val b = baseBoard()
        b[1, 2] = Cell(PieceType.BUTTERFLY)
        val e = engineOn(b, GemMode.BUTTERFLIES)
        e.movesMade = 1
        val ev = ArrayList<GemEvent>()
        e.runHooks(ev)
        assertTrue(ev.any { it is GemEvent.PieceMoved })
        assertTrue(ev.any { it is GemEvent.Escaped && it.piece == PieceType.BUTTERFLY })
        assertEquals(1, e.butterfliesEscaped)
        assertSettled(e.board)
    }

    @Test fun aButterflyWaitsBehindAStoneOrACage() {
        val b = baseBoard()
        b[4, 1] = Cell(PieceType.BUTTERFLY); b[3, 1] = Cell(PieceType.STONE, hp = 1)
        b[4, 4] = Cell(PieceType.BUTTERFLY); b[3, 4] = b[3, 4].copy(locked = true)
        val e = engineOn(b, GemMode.BUTTERFLIES)
        e.movesMade = 1
        e.runHooks(ArrayList())
        assertEquals(PieceType.BUTTERFLY, e.board[4, 1].type)
        assertEquals(PieceType.BUTTERFLY, e.board[4, 4].type)
    }

    @Test fun aSpiderReachingTheTopEndsTheGame() {
        val b = baseBoard()
        b[1, 3] = Cell(PieceType.SPIDER, hp = 1)
        val e = engineOn(b, GemMode.BUTTERFLIES)
        e.movesMade = 1
        val ev = ArrayList<GemEvent>()
        e.runHooks(ev)
        assertEquals(GemStatus.OVER, e.status)
        assertEquals(OverReason.SPIDER, e.overReason)
    }

    @Test fun butterfliesModeSpawnsButterfliesAndSpiders() {
        val e = GemEngine.start(GemMode.BUTTERFLIES, 8, 8, 12)
        e.movesMade = 9
        val ev = ArrayList<GemEvent>()
        e.runHooks(ev)
        val spawned = ev.filterIsInstance<GemEvent.Spawn>().map { it.cell.type }
        assertTrue(PieceType.BUTTERFLY in spawned)
        assertTrue(PieceType.SPIDER in spawned)
    }

    @Test fun aBombAtZeroEndsTheGameAndAClearedOneIsDefused() {
        val b = baseBoard()
        b[3, 3] = b[3, 3].copy(timer = 1)
        val e = engineOn(b, GemMode.COUNTDOWN)
        e.movesMade = 1
        val ev = ArrayList<GemEvent>()
        e.runHooks(ev)
        assertEquals(OverReason.BOMB, e.overReason)
        assertTrue(ev.any { it is GemEvent.BombExploded })

        val b2 = baseBoard()
        b2[3, 3] = b2[3, 3].copy(timer = 5)
        val e2 = engineOn(b2, GemMode.COUNTDOWN)
        e2.movesMade = 1
        val ev2 = ArrayList<GemEvent>()
        e2.runHooks(ev2)
        assertTrue(ev2.any { it is GemEvent.BombTick && it.remaining == 4 })
        val ev3 = e2.useBooster(BoosterKind.HAMMER, Pos(3, 3), null).events
        assertTrue(ev3.any { it is GemEvent.BombDefused })
        assertEquals(1, e2.bombsDefused)
    }

    @Test fun countdownSpawnsABombOnSchedule() {
        val e = GemEngine.start(GemMode.COUNTDOWN, 8, 8, 3)
        e.movesMade = 4
        val ev = ArrayList<GemEvent>()
        e.runHooks(ev)
        val bombs = e.board.cells.count { it.timer > 0 }
        assertEquals(1, bombs)
        assertEquals(14, e.board.cells.first { it.timer > 0 }.timer)
    }

    @Test fun everyModeHasRulesAndTheTableIsConsistent() {
        for (m in GemMode.values()) {
            val r = ModeRules.of(m)
            assertEquals(m, r.mode)
            assertTrue(r.kinds in 3..7)
            assertTrue(r.allowSwap || r.allowRotate)
            assertEquals(m, GemMode.fromId(m.id))
        }
        assertTrue(ModeRules.of(GemMode.LIGHTNING).timed)
        assertFalse(ModeRules.of(GemMode.ZEN).timed)
        assertFalse(ModeRules.of(GemMode.ZEN).limitedMoves)
        assertTrue(ModeRules.of(GemMode.TWIST).allowRotate && !ModeRules.of(GemMode.TWIST).allowSwap)
        assertTrue(ModeRules.of(GemMode.CLASSIC).classicLevels && !ModeRules.of(GemMode.CLASSIC).shuffleOnNoMoves)
        assertNull(GemMode.fromId("nope"))
    }
}

class GemObstacleTest {
    private fun swapIn(b: GemBoard, mode: GemMode = GemMode.ZEN): Pair<GemEngine, List<GemEvent>> {
        val e = engineOn(b, mode)
        return e to e.swap(Pos(1, 2), Pos(2, 2)).events
    }

    @Test fun anAdjacentMatchBreaksAStoneInTwoHits() {
        val b = baseBoard().put(6, 2 to 0, 2 to 1, 1 to 2)
        b[2, 3] = Cell(PieceType.STONE, hp = 2)
        val (e, ev) = swapIn(b)
        assertTrue(ev.any { it is GemEvent.ObstacleDamaged && it.obstacle == ObstacleKind.STONE && it.hpLeft == 1 })
        assertEquals(PieceType.STONE, e.board[2, 3].type)
        assertEquals(0, e.stonesBroken)
    }

    @Test fun aStoneWithOneHitLeftIsRemoved() {
        val b = baseBoard().put(6, 2 to 0, 2 to 1, 1 to 2)
        b[2, 3] = Cell(PieceType.STONE, hp = 1)
        val (e, ev) = swapIn(b)
        assertTrue(ev.any { it is GemEvent.ObstacleDamaged && it.at == Pos(2, 3) && it.hpLeft == 0 })
        assertEquals(1, e.stonesBroken)
        assertSettled(e.board)
    }

    @Test fun iceLosesALayerWhenItsGemIsCleared() {
        val b = baseBoard().put(6, 2 to 0, 2 to 1, 1 to 2)
        b.ice[b.idx(2, 1)] = 2
        val (e, ev) = swapIn(b)
        assertTrue(ev.any { it is GemEvent.ObstacleDamaged && it.obstacle == ObstacleKind.ICE && it.hpLeft == 1 })
        assertEquals(1, e.board.ice[e.board.idx(2, 1)])
        assertEquals(1, e.iceCleared)
    }

    @Test fun aCagedGemBreaksItsCageAndStays() {
        val b = baseBoard().put(6, 2 to 0, 2 to 1, 1 to 2)
        b[2, 1] = b[2, 1].copy(locked = true)
        val (e, ev) = swapIn(b)
        assertTrue(ev.any { it is GemEvent.ObstacleDamaged && it.obstacle == ObstacleKind.LOCK && it.at == Pos(2, 1) })
        assertEquals(1, e.locksBroken)
        val cleared = ev.filterIsInstance<GemEvent.Clear>().first().cells
        assertFalse(Pos(2, 1) in cleared)
        assertFalse(e.board[2, 1].locked)
    }

    @Test fun gemsAboveAStoneRestOnItAndGemsBelowRefillFromIt() {
        val b = baseBoard()
        b[3, 0] = Cell(PieceType.STONE, hp = 3)
        val e = engineOn(b)
        val ev = e.useBooster(BoosterKind.HAMMER, Pos(5, 0), null).events
        assertEquals(PieceType.STONE, e.board[3, 0].type)
        val sp = ev.filterIsInstance<GemEvent.Spawn>().first { it.at == Pos(4, 0) }
        assertEquals(3, sp.fromRow)
        assertSettled(e.board)
    }

    @Test fun cagedGemsCannotBeSwappedButFall() {
        val b = baseBoard()
        b[4, 2] = b[4, 2].copy(locked = true)
        val e = engineOn(b)
        assertEquals(RejectReason.BLOCKED, e.swap(Pos(4, 2), Pos(4, 3)).reason)
        e.useBooster(BoosterKind.HAMMER, Pos(5, 2), null)
        assertTrue(e.board[5, 2].locked)
    }
}

class GemBoosterTest {
    @Test fun theHammerIsCappedPerLevel() {
        val e = GemEngine.start(GemMode.ZEN, 8, 8, 5)
        repeat(3) { assertTrue(e.useBooster(BoosterKind.HAMMER, Pos(3, 3), null).accepted) }
        assertEquals(0, e.boosterLeft(BoosterKind.HAMMER))
        assertEquals(RejectReason.BOOSTER_CAP, e.useBooster(BoosterKind.HAMMER, Pos(3, 3), null).reason)
    }

    @Test fun theHammerNeedsAGemOrABlocker() {
        val b = baseBoard()
        b[1, 1] = Cell(PieceType.BUTTERFLY)
        val e = engineOn(b)
        assertEquals(RejectReason.BAD_TARGET, e.useBooster(BoosterKind.HAMMER, Pos(1, 1), null).reason)
        assertEquals(RejectReason.BAD_TARGET, e.useBooster(BoosterKind.HAMMER, null, null).reason)
        assertEquals(3, e.boosterLeft(BoosterKind.HAMMER))
    }

    @Test fun swapAnyKeepsANonMatchingSwapAndCostsNoMove() {
        val e = engineOn(baseBoard(), GemMode.CLASSIC)
        val r = e.useBooster(BoosterKind.SWAP_ANY, Pos(0, 0), Pos(0, 1))
        assertTrue(r.accepted)
        assertTrue(r.events.none { it is GemEvent.SwapBack })
        assertEquals(0, e.movesMade)
        assertEquals(1, e.boosterLeft(BoosterKind.SWAP_ANY).let { 2 - e.boosterLeft(BoosterKind.SWAP_ANY) })
    }

    @Test fun shuffleAndExtraMovesAndColourBomb() {
        val e = GemEngine.start(GemMode.MINE, 8, 8, 5)
        val moves = e.movesLeft
        assertTrue(e.useBooster(BoosterKind.EXTRA_MOVES, null, null).accepted)
        assertEquals(moves + 5, e.movesLeft)
        assertEquals(RejectReason.BOOSTER_CAP, e.useBooster(BoosterKind.EXTRA_MOVES, null, null).reason)
        val before = e.board.encode()
        assertTrue(e.useBooster(BoosterKind.SHUFFLE, null, null).accepted)
        assertNotEquals(before, e.board.encode())

        val zen = GemEngine.start(GemMode.ZEN, 8, 8, 5)
        assertEquals(RejectReason.NOT_ALLOWED, zen.useBooster(BoosterKind.EXTRA_MOVES, null, null).reason)
        val colour = zen.board[2, 2].matchKind
        val n = GemSpecials.colourCells(zen.board, colour).size
        val ev = zen.useBooster(BoosterKind.COLOUR_BOMB, Pos(2, 2), null).events
        assertTrue(ev.filterIsInstance<GemEvent.Clear>().first().cells.size >= n)
    }

    @Test fun lightningExtraTimeBooster() {
        val e = GemEngine.start(GemMode.LIGHTNING, 8, 8, 5)
        assertTrue(e.useBooster(BoosterKind.EXTRA_MOVES, null, null).accepted)
        assertEquals(85_000L, e.timeLeftMs)
    }

    @Test fun thereAreNoPurchasesOnlyFreeCappedBoosters() {
        assertEquals(5, GemBoosters.defs.size)
        assertTrue(GemBoosters.defs.all { it.capPerLevel in 1..3 })
    }
}

class GemQuestTest {
    private fun tinyLevel(goalTarget: Int, moves: Int, seed: Long = 5) = GemLevel(
        id = 99, rows = 6, cols = 6, kinds = 5, seed = seed, moves = moves, stars = listOf(100, 200, 300),
        goals = listOf(LevelGoal("score", goalTarget)), pieces = List(6) { "......" }
    )

    @Test fun winningEmitsLevelCompleteWithStarsAndAMovesBonus() {
        val e = GemEngine.forLevel(tinyLevel(50, 10))
        var last: List<GemEvent> = emptyList()
        while (e.status == GemStatus.PLAYING) last = e.apply(e.legalActions().first()).events
        assertEquals(GemStatus.WON, e.status)
        assertTrue(last.any { it is GemEvent.LevelComplete })
        assertTrue(last.any { it is GemEvent.ScoreDelta && it.reason == ScoreReason.LEFTOVER_MOVES })
        assertTrue(e.stars in 1..3)
        assertEquals(RejectReason.GAME_OVER, e.apply(GemAction.SwapAction(Pos(0, 0), Pos(0, 1))).reason)
    }

    @Test fun runningOutOfMovesEndsTheLevel() {
        val e = GemEngine.forLevel(tinyLevel(5_000_000, 3))
        repeat(3) { e.apply(e.legalActions().first()) }
        assertEquals(GemStatus.OVER, e.status)
        assertEquals(OverReason.OUT_OF_MOVES, e.overReason)
    }

    @Test fun anAllTargetMeansEverythingInTheLayout() {
        val lv = tinyLevel(0, 10).copy(
            goals = listOf(LevelGoal("locks"), LevelGoal("stones"), LevelGoal("ice")),
            pieces = listOf("..L...", "......", "...s..", "......", "..S...", "......"),
            floor = listOf("000000", "001100", "000000", "000000", "000000", "000000")
        )
        assertEquals(emptyList<String>(), GemLevels.problems(lv))
        val e = GemEngine.forLevel(lv)
        assertEquals(listOf(1, 2, 2), e.goals.map { it.target }.let { listOf(it[0], it[1], it[2]) })
        assertTrue(e.board[0, 2].locked)
        assertEquals(2, e.board.positionsOf(PieceType.STONE).size)
        assertSettled(e.board)
        assertTrue(e.legalActions().isNotEmpty())
    }

    @Test fun badLevelDataIsReported() {
        val ok = tinyLevel(100, 10)
        assertEquals(emptyList<String>(), GemLevels.problems(ok))
        assertTrue(GemLevels.problems(ok.copy(rows = 5)).isNotEmpty())
        assertTrue(GemLevels.problems(ok.copy(stars = listOf(3, 2, 1))).isNotEmpty())
        assertTrue(GemLevels.problems(ok.copy(goals = emptyList())).isNotEmpty())
        assertTrue(GemLevels.problems(ok.copy(goals = listOf(LevelGoal("locks", 3)))).isNotEmpty())
        assertTrue(GemLevels.problems(ok.copy(pieces = List(6) { "..?..." })).isNotEmpty())
        assertTrue(GemLevels.problems(ok.copy(pieces = listOf("......", "......", "......", "......", "......", "T....."))).isNotEmpty())
        assertTrue(GemLevels.problems(ok.copy(bombs = listOf(LevelBomb(0, 0, 2)))).isNotEmpty())
    }

    @Test fun theDailyLevelIsTheSameForEveryoneOnADay() {
        assertEquals(GemLevels.daily(20_000), GemLevels.daily(20_000))
        assertNotEquals(GemLevels.daily(20_000), GemLevels.daily(20_001))
        assertEquals(emptyList<String>(), GemLevels.problems(GemLevels.daily(20_000)))
    }
}

class GemBotTest {
    @Test fun everyBotLevelOnlyPlaysLegalMoves() {
        for (level in BotLevel.values()) for (seed in 1L..25L) {
            val e = GemEngine.start(GemMode.ZEN, 8, 8, seed)
            val bot = GemBot(level, seed)
            repeat(10) {
                val a = bot.choose(e)
                assertNotNull(a)
                assertTrue(a in e.legalActions())
                assertTrue(e.apply(a!!).accepted)
            }
        }
    }

    @Test fun hintsAreLegalDeterministicAndDoNotChangeTheGame() {
        for (seed in 1L..30L) {
            val e = GemEngine.start(GemMode.ZEN, 8, 8, seed)
            val board = e.board.encode()
            val rng = e.rng.state
            val h = GemSolver.hint(e)
            assertNotNull(h)
            assertTrue(h in e.legalActions())
            assertEquals(h, GemSolver.hint(e))
            assertEquals(board, e.board.encode())
            assertEquals(rng, e.rng.state)
        }
    }

    @Test fun selfPlayTerminatesInEveryEndingMode() {
        val caps = mapOf(GemMode.LIGHTNING to 800, GemMode.MINE to 400, GemMode.BUTTERFLIES to 3000, GemMode.COUNTDOWN to 3000)
        for ((mode, cap) in caps) for (level in BotLevel.values()) {
            val e = GemEngine.start(mode, 8, 8, 17)
            val bot = GemBot(level, 3)
            var n = 0
            while (e.status == GemStatus.PLAYING && n < cap) {
                val a = bot.choose(e) ?: break
                e.apply(a)
                if (mode == GemMode.LIGHTNING) e.apply(GemAction.Elapse(4_000))
                if (e.status == GemStatus.PLAYING) assertSettled(e.board)
                n++
            }
            assertTrue("$mode $level did not end in $cap moves", e.status != GemStatus.PLAYING)
        }
    }

    @Test fun classicAndZenAndTwistSelfPlayKeepsTheBoardValid() {
        for (mode in listOf(GemMode.CLASSIC, GemMode.ZEN, GemMode.TWIST)) {
            val e = GemEngine.start(mode, 8, 8, 21)
            val bot = GemBot(BotLevel.HARD, 1)
            repeat(80) {
                if (e.status != GemStatus.PLAYING) return@repeat
                val a = bot.choose(e) ?: return@repeat
                e.apply(a)
                assertSettled(e.board)
            }
            assertTrue(e.score > 0)
        }
    }

    @Test fun aSmarterBotScoresMoreThanARandomOne() {
        var easy = 0L
        var hard = 0L
        for (seed in 1L..20L) {
            val a = GemEngine.start(GemMode.ZEN, 8, 8, seed)
            val b = GemEngine.start(GemMode.ZEN, 8, 8, seed)
            val ea = GemBot(BotLevel.EASY, seed)
            val hb = GemBot(BotLevel.HARD, seed)
            repeat(25) { ea.choose(a)?.let { m -> a.apply(m) }; hb.choose(b)?.let { m -> b.apply(m) } }
            easy += a.score; hard += b.score
        }
        assertTrue("hard $hard vs easy $easy", hard > easy)
    }
}
