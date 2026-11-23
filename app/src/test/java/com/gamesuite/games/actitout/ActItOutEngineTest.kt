package com.gamesuite.games.actitout

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules of Act It Out: teams, dealing, the clock, skips, scoring with the handicap, save and restore. */
class ActItOutEngineTest {
    private var now = 0L
    private val clock: () -> Long = { now }

    private fun ids(n: Int) = List(n) { "p${it + 1}" }
    private fun names(n: Int) = List(n) { "Player ${it + 1}" }

    private fun setup(
        n: Int = 6, seed: Int = 1, defaults: ActItOutDefaults = ActItOutDefaults(),
        edit: ActItOutSetup.() -> Unit = {}
    ): ActItOutSetup = ActItOutSetup(ids(n), names(n), Random(seed), defaults).also(edit)

    private fun engine(
        n: Int = 6, seed: Int = 1, defaults: ActItOutDefaults = ActItOutDefaults(),
        edit: ActItOutSetup.() -> Unit = {}
    ): ActItOutEngine {
        now = 0L
        return ActItOutEngine(setup(n, seed, defaults, edit).build(), Random(seed), clock, minCardVisibleMs = 0L)
    }

    /** Runs the current turn out on the clock and moves to the next curtain / scoreboard / end. */
    private fun finishTurn(e: ActItOutEngine, lastCardCounts: Boolean = false) {
        now += e.config.roundSeconds * 1000L
        assertTrue(e.tick())
        e.resolveLastCard(lastCardCounts)
        assertTrue(e.continueAfterSummary())
    }

    // ---- teams ----

    @Test fun autoSplitIsFairForEveryTableSizeAndSeed() {
        for (n in ActItOutRules.MIN_PLAYERS..ActItOutRules.MAX_PLAYERS) {
            val biggerFirst = HashSet<Boolean>()
            for (seed in 0 until 60) {
                val teams = ActItOutTeams.autoSplit(n, Random(seed))
                assertEquals(2, teams.size)
                assertTrue("n=$n seed=$seed spread", ActItOutTeams.sizeSpread(teams) <= 1)
                assertEquals((0 until n).toList(), teams.flatten().sorted())
                assertTrue(teams.all { it == it.sorted() })
                if (n % 2 == 1) biggerFirst += teams[0].size > teams[1].size
            }
            if (n % 2 == 1) assertEquals("n=$n: the extra player should fall to either team", setOf(true, false), biggerFirst)
        }
    }

    @Test fun autoSplitShufflesWhoIsWithWho() {
        val seen = (0 until 40).map { ActItOutTeams.autoSplit(8, Random(it))[0] }.toSet()
        assertTrue("only ${seen.size} different teams in 40 deals", seen.size > 10)
    }

    @Test fun freeForAllMakesEveryPlayerASide() {
        assertEquals(List(5) { listOf(it) }, ActItOutTeams.individuals(5))
        val e = engine(5) { setFormat(ActItOutFormat.FREE_FOR_ALL) }
        assertEquals(5, e.sideCount)
        assertEquals(ActItOutFormat.FREE_FOR_ALL, e.config.format)
        assertEquals("Player 3", e.config.sideName(2, "Team"))
    }

    @Test fun aPlayerCannotBeMovedOutOfASingletonTeam() {
        val s = setup(3)
        val sizes = s.teamSizes()
        assertEquals(3, sizes.sum())
        val loneTeam = sizes.indexOf(1)
        val lone = (0 until 3).first { s.teamOf[it] == loneTeam }
        assertFalse(s.togglePlayerTeam(lone))
        assertEquals(sizes, s.teamSizes())
        val other = (0 until 3).first { s.teamOf[it] != loneTeam }
        assertTrue(s.togglePlayerTeam(other))
        assertTrue(s.canStart())
    }

    @Test fun teamNamesDefaultToLettersAndKeepTypedNames() {
        val s = setup(4) { renameTeam(1, "Rockets") }
        val c = s.build()
        assertEquals("Team A", c.sideName(0, "Team"))
        assertEquals("Rockets", c.sideName(1, "Team"))
    }

    @Test fun configValidationRejectsBrokenSetups() {
        val good = setup(6).build()
        assertNull(good.validate())
        assertNotNull(good.copy(roundSeconds = 45).validate())
        assertNotNull(good.copy(rounds = 0).validate())
        assertNotNull(good.copy(rounds = 6).validate())
        assertNotNull(good.copy(sides = good.sides.take(1)).validate())
        assertNotNull(good.copy(sides = listOf(good.sides[0].copy(members = listOf(0, 1, 2, 3, 4, 4)), good.sides[1])).validate())
        assertNotNull(good.copy(playerNames = good.playerNames.take(2), playerIds = good.playerIds.take(2)).validate())
    }

    @Test fun hubExtrasBecomeSafeDefaults() {
        val d = ActItOutDefaults.fromExtras(mapOf("format" to "ffa", "time" to "90", "rounds" to "9", "band" to "little", "handicap" to "true", "buzzer" to "false", "hold" to "false"))
        assertEquals(ActItOutFormat.FREE_FOR_ALL, d.format)
        assertEquals(90, d.roundSeconds)
        assertEquals(ActItOutRules.MAX_ROUNDS, d.rounds)
        assertEquals(ActItOutBand.LITTLE, d.band)
        assertTrue(d.handicap); assertFalse(d.buzzer); assertFalse(d.holdToReveal)
        val stale = ActItOutDefaults.fromExtras(mapOf("format" to "x", "time" to "45", "rounds" to "-3", "band" to "??"))
        assertEquals(ActItOutDefaults(rounds = ActItOutRules.MIN_ROUNDS), stale)
        assertEquals(ActItOutDefaults(), ActItOutDefaults.fromExtras(emptyMap()))
    }

    @Test fun littleKidsBonusSwitchStartsOnOnlyForLittleKidsSidesWhenOffered() {
        val s = setup(6, defaults = ActItOutDefaults(band = ActItOutBand.LITTLE, handicap = true))
        assertTrue(s.bonus(0) && s.bonus(1))
        val t = setup(6, defaults = ActItOutDefaults(band = ActItOutBand.EVERYONE, handicap = true))
        assertFalse(t.bonus(0) || t.bonus(1))
        assertTrue(t.handicapOffered)
        assertFalse(setup(6).handicapOffered)
    }

    // ---- the clock ----

    @Test fun theTurnClockRunsOffTheInjectedClockAndTimesOutExactly() {
        val e = engine(6) { roundSeconds = 30 }
        assertEquals(ActItOutPhase.CURTAIN, e.phase)
        assertTrue(e.startTurn())
        assertEquals(ActItOutPhase.PLAYING, e.phase)
        assertEquals(30_000L, e.remainingMs())
        now += 29_999L
        assertFalse(e.tick())
        assertEquals(1L, e.remainingMs())
        assertEquals(1, ActItOutRules.secondsLeft(e.remainingMs()))
        now += 1L
        assertTrue(e.tick())
        assertEquals(ActItOutPhase.TIMES_UP, e.phase)
        assertEquals(0L, e.remainingMs())
        assertFalse(e.tick())
    }

    @Test fun nothingScoresOrSkipsAfterTheBuzzer() {
        val e = engine(6)
        e.startTurn()
        now += 61_000L
        assertEquals(0, e.markCorrect())
        assertFalse(e.skip())
        assertEquals(ActItOutPhase.TIMES_UP, e.phase)
        assertEquals(0, e.scores.sum())
    }

    @Test fun pauseFreezesTheClockPutsTheCardBehindTheCurtainAndTheTurnContinuesFromWhatWasLeft() {
        val e = engine(6)
        e.startTurn()
        val card = e.card
        now += 10_000L
        assertTrue(e.pause())
        assertFalse(e.isRunning)
        assertEquals(ActItOutPhase.CURTAIN, e.phase)
        assertTrue(e.resumedTurn)
        now += 500_000L
        assertFalse(e.tick())
        assertEquals(50_000L, e.remainingMs())
        assertFalse("nothing to pause on a curtain", e.pause())
        assertTrue(e.startTurn())
        assertEquals("same card after the curtain", card, e.card)
        assertTrue(e.isRunning)
        now += 49_999L
        assertFalse(e.tick())
        now += 1L
        assertTrue(e.tick())
    }

    @Test fun startTurnDoesNothingOutsideTheCurtain() {
        val e = engine(6)
        assertTrue(e.startTurn())
        assertFalse(e.startTurn())
    }

    @Test fun milestonesAreFewAndNeverEverySecond() {
        assertEquals(listOf(10, 5), ActItOutRules.milestones(30))
        assertEquals(listOf(30, 10, 5), ActItOutRules.milestones(60))
        assertEquals(listOf(60, 30, 10, 5), ActItOutRules.milestones(90))
        assertEquals(30, ActItOutRules.milestoneCrossed(60, 30_500L, 29_900L))
        assertEquals(10, ActItOutRules.milestoneCrossed(60, 10_100L, 9_990L))
        assertNull(ActItOutRules.milestoneCrossed(60, 29_900L, 29_000L))
        assertNull(ActItOutRules.milestoneCrossed(60, 20_000L, 30_000L))
        // Counting down a whole 60 s turn in 16 ms frames announces exactly three times.
        var left = 60_000L
        var announced = 0
        while (left > 0) {
            val next = (left - 16L).coerceAtLeast(0L)
            if (ActItOutRules.milestoneCrossed(60, left, next) != null) announced++
            left = next
        }
        assertEquals(3, announced)
        assertEquals(60, ActItOutRules.secondsLeft(60_000L))
        assertEquals(1, ActItOutRules.secondsLeft(1L))
        assertEquals(0, ActItOutRules.secondsLeft(0L))
    }

    // ---- dealing ----

    private fun dealAll(e: ActItOutEngine, count: Int): List<ActItOutCard> {
        e.startTurn()
        val out = mutableListOf(e.card!!)
        repeat(count - 1) {
            assertTrue(e.markCorrect() > 0)
            out += e.card!!
        }
        return out
    }

    @Test fun cardsAreDealtWithoutRepeatsUntilThePoolIsUsedUp() {
        val e = engine(6) { for (s in 0..1) setBand(s, ActItOutBand.LITTLE) }
        val pool = ActItOutDeck.pool(ActItOutBand.LITTLE)
        val dealt = dealAll(e, pool.size)
        assertEquals("a card repeated before the pool was used up", pool.size, dealt.map { it.id }.toSet().size)
        assertEquals(pool.map { it.id }.toSet(), dealt.map { it.id }.toSet())
    }

    @Test fun anExhaustedPoolComesRoundAgainButNeverRepeatsTheLastCardStraightAway() {
        val e = engine(6) { for (s in 0..1) setBand(s, ActItOutBand.LITTLE) }
        val pool = ActItOutDeck.pool(ActItOutBand.LITTLE)
        val dealt = dealAll(e, pool.size * 2 + 5)
        assertEquals(pool.size * 2 + 5, dealt.size)
        for (i in 1 until dealt.size) assertTrue("repeat at $i", dealt[i].id != dealt[i - 1].id)
        assertTrue(dealt.all { it.difficulty == 1 })
        // Second pass is again a full pass without repeats inside it.
        val second = dealt.drop(pool.size).take(pool.size)
        assertEquals(pool.size, second.map { it.id }.toSet().size)
    }

    @Test fun skippedCardsAreUsedToo() {
        val e = engine(6)
        e.startTurn()
        val first = e.card!!.id
        assertTrue(e.skip())
        assertTrue(first in e.used)
        assertTrue(e.card!!.id != first)
    }

    @Test fun everySideIsDealtCardsFromItsOwnBand() {
        val e = engine(6) { setBand(0, ActItOutBand.LITTLE); setBand(1, ActItOutBand.GROWN_UPS) }
        for (turn in 0 until 6) {
            e.startTurn()
            val band = e.config.sides[e.side].band
            repeat(15) {
                assertTrue("${e.card} outside $band", band.allows(e.card!!.difficulty))
                e.markCorrect()
            }
            assertTrue(band.allows(e.card!!.difficulty))
            finishTurn(e)
            if (e.phase == ActItOutPhase.SCOREBOARD) e.continueFromScoreboard()
        }
    }

    @Test fun theSameSeedDealsTheSameCards() {
        fun run(): List<String> { val e = engine(6, seed = 7); return dealAll(e, 20).map { it.id } }
        assertEquals(run(), run())
        val other = dealAll(engine(6, seed = 8), 20).map { it.id }
        assertTrue(run() != other)
    }

    // ---- skips ----

    @Test fun anActorGetsExactlyThreeSkipsPerTurn() {
        val e = engine(6)
        e.startTurn()
        repeat(ActItOutRules.SKIP_LIMIT) { assertTrue(e.skip()) }
        assertEquals(0, e.skipsLeft)
        val card = e.card
        assertFalse(e.skip())
        assertEquals(card, e.card)
        assertEquals(3, e.turnSkipped)
        assertEquals(3, e.skipped[e.side])
        assertEquals(0, e.scores.sum())
        // The skips come back on the next turn.
        finishTurn(e)
        e.startTurn()
        assertEquals(3, e.skipsLeft)
        assertTrue(e.skip())
    }

    // ---- scoring ----

    @Test fun eachCorrectCardIsWorthOnePointOrTwoWithTheLittleKidsBonus() {
        val e = engine(6) { setBonus(0, true) }
        e.startTurn()
        assertEquals(2, e.markCorrect())
        assertEquals(2, e.markCorrect())
        assertEquals(4, e.scores[0])
        assertEquals(2, e.correct[0])
        assertEquals(4, e.turnPoints)
        finishTurn(e)
        e.startTurn()
        assertEquals(1, e.markCorrect())
        assertEquals(1, e.scores[1])
        assertEquals(1, e.correct[1])
    }

    @Test fun theBonusFollowsTheSideNotTheTurn() {
        val e = engine(6) { setBonus(1, true) }
        e.startTurn()
        assertEquals(1, e.markCorrect())
        finishTurn(e)
        e.startTurn()
        assertEquals(2, e.markCorrect())
        assertEquals(listOf(1, 2), e.scores.toList())
    }

    @Test fun aJustInTimeCardScoresOnlyWhenTheGroupSaysSo() {
        val e = engine(6) { setBonus(0, true) }
        e.startTurn()
        now += 60_000L
        e.tick()
        assertNotNull(e.card)
        assertEquals(2, e.resolveLastCard(true))
        assertEquals(ActItOutPhase.TURN_SUMMARY, e.phase)
        assertNull(e.card)
        assertEquals(0, e.resolveLastCard(true))
        assertEquals(2, e.scores[0])

        val f = engine(6)
        f.startTurn()
        now += 60_000L
        f.tick()
        assertEquals(0, f.resolveLastCard(false))
        assertEquals(0, f.scores.sum())
    }

    // ---- flow ----

    @Test fun aWholeMatchWalksCurtainSummaryScoreboardAndEnd() {
        val e = engine(6) { rounds = 2 }
        assertEquals(4, e.totalTurns)
        repeat(4) {
            if (e.phase == ActItOutPhase.SCOREBOARD) assertTrue(e.continueFromScoreboard())
            assertEquals(ActItOutPhase.CURTAIN, e.phase)
            e.startTurn()
            e.markCorrect()
            finishTurn(e)
        }
        assertEquals(ActItOutPhase.END, e.phase)
        assertTrue(e.isOver)
        assertEquals(listOf(2, 2), e.correct.toList())
        assertNull(e.snapshot())
    }

    @Test fun theScoreboardAppearsBetweenRoundsOnly() {
        val e = engine(6) { rounds = 3 }
        e.startTurn(); finishTurn(e)
        assertEquals(ActItOutPhase.CURTAIN, e.phase)
        assertEquals(1, e.side)
        e.startTurn(); finishTurn(e)
        assertEquals(ActItOutPhase.SCOREBOARD, e.phase)
        assertEquals(1, e.round)
        assertTrue(e.continueFromScoreboard())
        assertEquals(ActItOutPhase.CURTAIN, e.phase)
        assertEquals(0, e.side)
        assertFalse(e.continueFromScoreboard())
    }

    @Test fun actorsRotateThroughATeamRoundByRound() {
        val e = engine(6) { rounds = 3 }
        val team0 = e.config.sides[0].members
        val seen = mutableListOf<Int>()
        repeat(6) {
            if (e.side == 0) seen += e.actor
            assertEquals(e.config.sides[e.side].members[e.round % e.config.sides[e.side].members.size], e.actor)
            e.startTurn(); finishTurn(e)
            if (e.phase == ActItOutPhase.SCOREBOARD) e.continueFromScoreboard()
        }
        assertEquals(listOf(team0[0], team0[1], team0[2]), seen)
    }

    @Test fun inFreeForAllEveryPlayerActsOncePerRound() {
        val e = engine(4) { setFormat(ActItOutFormat.FREE_FOR_ALL); rounds = 2 }
        val actors = mutableListOf<Int>()
        repeat(8) {
            actors += e.actor
            e.startTurn(); finishTurn(e)
            if (e.phase == ActItOutPhase.SCOREBOARD) e.continueFromScoreboard()
        }
        assertEquals(listOf(0, 1, 2, 3, 0, 1, 2, 3), actors)
    }

    @Test fun leadersListsTiesAndThePlainWinner() {
        val e = engine(6) { rounds = 1 }
        assertEquals(listOf(0, 1), e.leaders())
        e.startTurn(); e.markCorrect(); finishTurn(e)
        assertEquals(listOf(0), e.leaders())
        e.startTurn(); e.markCorrect(); finishTurn(e)
        assertEquals(listOf(0, 1), e.leaders())
    }

    // ---- save and restore ----

    private fun restore(json: String?) = ActItOutEngine.restore(json!!, Random(3), clock, 0L)

    private fun assertSameState(a: ActItOutEngine, b: ActItOutEngine) {
        assertEquals(a.config, b.config)
        assertEquals(a.deckOrder, b.deckOrder)
        assertEquals(a.used.toList(), b.used.toList())
        assertEquals(a.turnIndex, b.turnIndex)
        assertEquals(a.scores.toList(), b.scores.toList())
        assertEquals(a.correct.toList(), b.correct.toList())
        assertEquals(a.skipped.toList(), b.skipped.toList())
        assertEquals(a.skipsLeft, b.skipsLeft)
    }

    @Test fun aCurtainSnapshotRoundTrips() {
        val e = engine(7) { rounds = 2; setBand(0, ActItOutBand.LITTLE); setBonus(0, true); renameTeam(1, "Rockets") }
        e.startTurn(); e.markCorrect(); e.skip(); finishTurn(e)
        val back = restore(e.snapshot())!!
        assertEquals(ActItOutPhase.CURTAIN, back.phase)
        assertSameState(e, back)
        assertFalse(back.resumedTurn)
        // And the restored match plays on.
        assertTrue(back.startTurn())
        assertNotNull(back.card)
    }

    @Test fun aTurnUnderWayComesBackOnACurtainWithTheSameCardAndTimeLeft() {
        val e = engine(6)
        e.startTurn()
        e.markCorrect()
        e.skip()
        val card = e.card!!
        now += 20_000L
        val back = restore(e.snapshot())!!
        assertEquals(ActItOutPhase.CURTAIN, back.phase)
        assertTrue(back.resumedTurn)
        assertEquals(40_000L, back.startingMs())
        assertEquals(card, back.card)
        // The card is never on show while the curtain is up: the screen shows no card in this phase.
        assertTrue(back.startTurn())
        assertEquals(card, back.card)
        assertEquals(40_000L, back.remainingMs())
        assertEquals(2, back.skipsLeft)
        assertEquals(1, back.correct[0])
        assertSameState(e.also { it.pause() }, restore(e.snapshot())!!.also { })
    }

    @Test fun aTimesUpSnapshotKeepsTheUnresolvedCard() {
        val e = engine(6)
        e.startTurn(); now += 60_000L; e.tick()
        val back = restore(e.snapshot())!!
        assertEquals(ActItOutPhase.TIMES_UP, back.phase)
        assertEquals(e.card, back.card)
        assertEquals(1, back.resolveLastCard(true))
    }

    @Test fun summaryAndScoreboardSnapshotsRoundTrip() {
        val e = engine(6)
        e.startTurn(); e.markCorrect(); now += 60_000L; e.tick(); e.resolveLastCard(false)
        assertEquals(ActItOutPhase.TURN_SUMMARY, e.phase)
        val a = restore(e.snapshot())!!
        assertEquals(ActItOutPhase.TURN_SUMMARY, a.phase)
        assertSameState(e, a)
        e.continueAfterSummary(); e.startTurn(); finishTurn(e)
        assertEquals(ActItOutPhase.SCOREBOARD, e.phase)
        val b = restore(e.snapshot())!!
        assertEquals(ActItOutPhase.SCOREBOARD, b.phase)
        assertSameState(e, b)
    }

    @Test fun thereIsNothingToSnapshotOnceTheMatchIsOver() {
        val e = engine(6) { rounds = 1 }
        repeat(2) { e.startTurn(); finishTurn(e) }
        assertEquals(ActItOutPhase.END, e.phase)
        assertNull(e.snapshot())
    }

    private fun tampered(e: ActItOutEngine, from: String, to: String): ActItOutEngine? {
        val json = e.snapshot()!!
        val changed = Regex(from).replace(json, to)
        assertTrue("pattern '$from' not found", changed != json)
        return restore(changed)
    }

    @Test fun corruptSnapshotsAreRejected() {
        val e = engine(6)
        e.startTurn(); e.markCorrect(); e.markCorrect(); finishTurn(e)
        assertNotNull(restore(e.snapshot()))

        assertNull(ActItOutEngine.restore("", Random(1), clock))
        assertNull(ActItOutEngine.restore("not json", Random(1), clock))
        assertNull(ActItOutEngine.restore("{}", Random(1), clock))
        assertNull(ActItOutEngine.restore("""{"playerIds":[]}""", Random(1), clock))

        // arithmetic: a score that is not correct x points per card
        assertNull(tampered(e, "\"scores\":\\[2,", "\"scores\":[3,"))
        // a turn index outside the match
        assertNull(tampered(e, "\"turnIndex\":1", "\"turnIndex\":99"))
        // an unknown phase and a phase that cannot be saved
        assertNull(tampered(e, "\"phase\":\"CURTAIN\"", "\"phase\":\"SOMEWHERE\""))
        assertNull(tampered(e, "\"phase\":\"CURTAIN\"", "\"phase\":\"END\""))
        assertNull(tampered(e, "\"phase\":\"CURTAIN\"", "\"phase\":\"PLAYING\""))
        // a bad clock or skip count
        assertNull(tampered(e, "\"skipsLeft\":3", "\"skipsLeft\":7"))
        assertNull(tampered(e, "\"roundSeconds\":60", "\"roundSeconds\":45"))
        // a bad format
        assertNull(tampered(e, "\"format\":\"teams\"", "\"format\":\"chaos\""))
        // a card id on a clean curtain
        assertNull(tampered(e, "\"cardId\":null", "\"cardId\":\"cat\""))
    }

    @Test fun aBrokenDeckOrderOrUsedSetIsRejected() {
        val e = engine(6)
        e.startTurn(); e.markCorrect()
        val ok = e.snapshot()!!
        assertNotNull(restore(ok))
        // duplicate card in the order (and one missing)
        val order = e.deckOrder
        val dup = ok.replace("\"${order[1]}\"", "\"${order[0]}\"")
        assertNull(restore(dup))
        // a card that is not in the deck
        assertNull(restore(ok.replace("\"deckOrder\":[\"${order[0]}\"", "\"deckOrder\":[\"zzz_unknown\"")))
        // truncated order
        val shortOrder = ok.replace(Regex("\"deckOrder\":\\[[^\\]]*\\]"), "\"deckOrder\":[\"${order[0]}\"]")
        assertNull(restore(shortOrder))
        // used set naming an unknown card, and a duplicate in it
        assertNull(restore(ok.replace(Regex("\"used\":\\["), "\"used\":[\"zzz_unknown\",")))
        val firstUsed = e.used.first()
        assertNull(restore(ok.replace(Regex("\"used\":\\["), "\"used\":[\"$firstUsed\",")))
    }

    @Test fun anInProgressTurnWhoseCardWasNeverDealtIsRejected() {
        val e = engine(6)
        e.startTurn()
        val ok = e.snapshot()!!
        assertNotNull(restore(ok))
        assertNull(restore(ok.replace(Regex("\"cardId\":\"[a-z0-9_]+\""), "\"cardId\":null")))
        // the card must be one of the used ones
        val card = e.card!!.id
        val other = ActItOutDeck.cards.first { it.id !in e.used }.id
        assertNull(restore(ok.replace("\"cardId\":\"$card\"", "\"cardId\":\"$other\"")))
    }

    // ---- the game module ----

    private fun context(n: Int, mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) = GameContext(
        mode, List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }, 0, LocalPassAndPlayTransport()
    )

    private fun game(n: Int = 6): ActItOutGame {
        now = 0L
        val g = ActItOutGame(Random(5), clock, minCardVisibleMs = 0L)
        g.init(context(n))
        g.startMatch()
        return g
    }

    @Test fun theGameStartsInSetupAndBeginsAMatch() {
        val g = game()
        assertNotNull(g.setup)
        assertNull(g.engine)
        assertNull("nothing to save while still in setup", g.snapshot())
        assertTrue(g.beginMatch())
        assertNull(g.setup)
        assertEquals(ActItOutPhase.CURTAIN, g.engine!!.phase)
        assertFalse(g.beginMatch())
    }

    @Test fun theGameSavesAndRestoresAndRejectsAnotherRoster() {
        val g = game()
        g.beginMatch(); g.startTurn(); g.markCorrect()
        val snap = g.snapshot()!!
        val h = ActItOutGame(Random(9), clock)
        h.init(context(6))
        assertTrue(h.restore(snap))
        assertEquals(1, h.engine!!.correct[0])
        val other = ActItOutGame(Random(9), clock)
        other.init(context(5))
        assertFalse(other.restore(snap))
        assertNull(other.engine)
        assertFalse(other.restore("garbage"))
    }

    @Test fun finishedMatchReportsEachPlayersSideScoreAndEveryPlayerOnTheTopSideWins() {
        val g = game(6)
        g.setup!!.rounds = 1
        g.setup!!.setBonus(0, false)
        g.beginMatch()
        val e = g.engine!!
        g.startTurn(); g.markCorrect(); g.markCorrect()
        now += 60_000L; g.tick(); g.resolveLastCard(false); g.continueAfterSummary()
        g.startTurn()
        now += 60_000L; g.tick(); g.resolveLastCard(false); g.continueAfterSummary()
        assertTrue(e.isOver)
        val result = g.finishedResult!!
        assertEquals(6, result.scores.size)
        val winners = result.scores.filter { it.isWinner }
        assertEquals(e.config.sides[0].members.size, winners.size)
        assertTrue(winners.all { it.score == 2 })
        assertTrue(result.scores.filter { !it.isWinner }.all { it.score == 0 })

        var reported: GameResult? = null
        g.setOnMatchEnd { reported = it }
        g.leaveSession()
        assertEquals(result, reported)
        assertTrue(g.matchOver.value)
        assertNull(g.snapshot())
    }

    @Test fun aScorelessMatchHasNoWinnersAndLeavingMidMatchIsAnAbort() {
        val g = game(6)
        g.setup!!.rounds = 1
        g.beginMatch()
        for (i in 0 until 2) {
            g.startTurn(); now += 60_000L; g.tick(); g.resolveLastCard(false); g.continueAfterSummary()
        }
        assertTrue(g.finishedResult!!.scores.none { it.isWinner })

        val h = game(6)
        h.beginMatch()
        var reported: GameResult? = null
        h.setOnMatchEnd { reported = it }
        h.abortMatch()
        assertTrue(reported!!.wasAborted)
    }

    @Test fun playAgainReturnsToSetupWithTheSameChoices() {
        val g = game(6)
        g.setup!!.rounds = 1
        g.setup!!.roundSeconds = 30
        g.setup!!.renameTeam(0, "Owls")
        val teams = g.setup!!.teamOf.toList()
        g.beginMatch()
        g.playAgain()
        val s = g.setup!!
        assertNull(g.engine)
        assertEquals(1, s.rounds)
        assertEquals(30, s.roundSeconds)
        assertEquals("Owls", s.teamNames[0])
        assertEquals(teams, s.teamOf.toList())
    }

    @Test fun pausingFreezesTheTurnAndResumingContinuesIt() {
        val g = game()
        g.beginMatch(); g.startTurn()
        now += 5_000L
        val v = g.version.intValue
        g.pause()
        assertTrue("the screen must redraw to show the curtain", g.version.intValue > v)
        now += 100_000L
        g.tick()
        assertEquals(ActItOutPhase.CURTAIN, g.engine!!.phase)
        g.resume()
        g.startTurn()
        assertEquals(ActItOutPhase.PLAYING, g.engine!!.phase)
        assertEquals(55_000L, g.engine!!.remainingMs())
    }

    @Test fun theGameDeclaresAFamilyPartyShape() {
        val g = game()
        assertEquals("act-it-out", g.gameId)
        assertEquals(3, g.minPlayers)
        assertEquals(12, g.maxPlayers)
        assertEquals(listOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY), g.supportedModes)
    }

    // ---- audit fixes: expiry frame, last millisecond, per-band dealing, restore checks, save discard ----

    @Test fun aTapOnTheExpiryFrameRedrawsSoTheTimeUpEffectsRunOnce() {
        val g = game()
        g.beginMatch(); g.startTurn()
        now += 60_000L
        var v = g.version.intValue
        assertEquals(0, g.markCorrect())
        assertEquals(ActItOutPhase.TIMES_UP, g.engine!!.phase)
        assertTrue("Correct on the expiry frame must bump the version", g.version.intValue > v)
        // The frame loop's own tick finds nothing left to do, so it must not bump again.
        v = g.version.intValue
        g.tick()
        assertEquals(v, g.version.intValue)

        val h = game()
        h.beginMatch(); h.startTurn()
        now += 60_000L
        val hv = h.version.intValue
        assertFalse(h.skip())
        assertEquals(ActItOutPhase.TIMES_UP, h.engine!!.phase)
        assertTrue("Skip on the expiry frame must bump the version", h.version.intValue > hv)
    }

    @Test fun aCardOnScreenForLessThanTheMinimumCannotBeMarkedCorrectAndNotAfterTimeIsUp() {
        now = 0L
        val e = ActItOutEngine(setup(6).build(), Random(1), clock)
        e.startTurn()
        assertEquals(0, e.markCorrect())
        now += ActItOutRules.MIN_CARD_VISIBLE_MS - 1
        assertEquals(0, e.markCorrect())
        now += 1
        assertEquals(1, e.markCorrect())
        // the replacement card has just been dealt: not yet guessable
        assertEquals(0, e.markCorrect())
        // a card dealt in the last milliseconds is never offered as guessed
        now = 59_900L
        assertEquals(1, e.markCorrect())
        now = 59_999L
        assertEquals(0, e.markCorrect())
        assertEquals(2, e.scores[0])
        now = 60_000L
        assertEquals(0, e.markCorrect())
        assertEquals(ActItOutPhase.TIMES_UP, e.phase)
    }

    @Test fun pausingAfterTheClockRanOutEndsTheTurnInsteadOfHidingIt() {
        val e = engine(6)
        e.startTurn()
        now += 60_000L
        assertTrue(e.pause())
        assertEquals(ActItOutPhase.TIMES_UP, e.phase)
    }

    @Test fun aPausedTurnSurvivesSaveAndRestoreBehindTheCurtain() {
        val e = engine(6)
        e.startTurn(); e.markCorrect()
        val card = e.card
        now += 20_000L
        e.pause()
        val back = restore(e.snapshot())!!
        assertEquals(ActItOutPhase.CURTAIN, back.phase)
        assertTrue(back.resumedTurn)
        assertEquals(card, back.card)
        assertEquals(40_000L, back.startingMs())
    }

    @Test fun poolResetOfOneBandDoesNotFreeCardsAnotherBandAlreadySaw() {
        val e = engine(6) { rounds = 2; setBand(0, ActItOutBand.EVERYONE); setBand(1, ActItOutBand.LITTLE) }
        e.startTurn()
        val seenBySideZero = HashSet<String>()
        repeat(8) { seenBySideZero += e.card!!.id; e.markCorrect() }
        finishTurn(e)
        // side 1 (little kids) runs through its whole pool and wraps
        e.startTurn()
        repeat(ActItOutDeck.pool(ActItOutBand.LITTLE).size + 3) { e.markCorrect() }
        finishTurn(e)
        if (e.phase == ActItOutPhase.SCOREBOARD) e.continueFromScoreboard()
        // side 0 again: nothing it already saw may come back while unseen cards remain
        e.startTurn()
        val dealt = ArrayList<String>()
        repeat(20) { dealt += e.card!!.id; e.markCorrect() }
        assertTrue("repeat: " + dealt.filter { it in seenBySideZero }, dealt.none { it in seenBySideZero })
    }

    @Test fun anOldSaveWithoutPerBandRecordsStillRestores() {
        val e = engine(6)
        e.startTurn(); e.markCorrect()
        val json = e.snapshot()!!
        val old = Regex(",\"usedByBand\":\\{[^}]*\\}").replace(json, "")
        assertTrue(old != json)
        val back = restore(old)!!
        assertEquals(e.used.toSet(), back.used.toSet())
    }

    @Test fun restoreChecksTheTurnCountersAgainstTheScores() {
        val e = engine(6)
        e.startTurn(); e.markCorrect()
        assertNotNull(restore(e.snapshot()))
        // points this turn that do not match the correct cards
        assertNull(tampered(e, "\"turnPoints\":1", "\"turnPoints\":5"))
        val f = engine(6)
        f.startTurn(); f.skip()
        assertNotNull(restore(f.snapshot()))
        // more skips this turn than the side has skipped overall
        assertNull(tampered(f, "\"skipped\":\\[1,", "\"skipped\":[0,"))
    }

    @Test fun aTurnSavedAtZeroSecondsComesBackAsTimesUp() {
        val e = engine(6)
        e.startTurn()
        now += 1_000L
        val back = tampered(e, "\"remainingMs\":\\d+", "\"remainingMs\":0")!!
        assertEquals(ActItOutPhase.TIMES_UP, back.phase)
        assertNotNull(back.card)
        assertFalse(back.resumedTurn)
        back.resolveLastCard(false)
        assertEquals(ActItOutPhase.TURN_SUMMARY, back.phase)
    }

    @Test fun aBlankPlayerNameGetsTheLocalizedDefault() {
        val s = ActItOutSetup(ids(3), listOf("", "Ann", "  "), Random(1), defaultName = { "Jugador ${it + 1}" })
        assertEquals(listOf("Jugador 1", "Ann", "Jugador 3"), s.names)
        s.rename(1, "   ")
        assertEquals("Jugador 2", s.names[1])
        assertEquals("Player 1", ActItOutSetup(ids(3), listOf("", "b", "c"), Random(1)).names[0])
    }

    @Test fun aFinishedMatchAndPlayAgainBothDiscardTheResumeSave() {
        val g = game(6)
        var discards = 0
        g.setOnDiscardSave { discards++ }
        g.setup!!.rounds = 1
        g.beginMatch()
        assertEquals(0, discards)
        repeat(2) {
            g.startTurn(); now += 60_000L; g.tick(); g.resolveLastCard(false); g.continueAfterSummary()
        }
        assertTrue(g.engine!!.isOver)
        assertEquals("finishing clears the save", 1, discards)
        g.playAgain()
        assertEquals("play again clears it too", 2, discards)
    }
}
