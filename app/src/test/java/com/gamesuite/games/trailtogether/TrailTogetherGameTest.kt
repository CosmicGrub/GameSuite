package com.gamesuite.games.trailtogether

import com.gamesuite.core.GameCategory
import com.gamesuite.core.GameResult
import com.gamesuite.core.PlayMode
import com.gamesuite.settings.CpuDifficulty
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import kotlin.random.Random

/** The game wrapper: lifecycle, the resume snapshot and its strict validation, results and wording (tests T25 to T35). */
class TrailTogetherGameTest {
    private fun game(humans: Int = 2, bots: Int = 0, seed: Int = 1, band: TrailBand = TrailBand.LITTLE, level: CpuDifficulty = CpuDifficulty.HARD): TrailTogetherGame {
        val g = TrailTogetherGame(Random(seed))
        g.band = band
        g.difficulty = level
        g.init(trailContext(humans, bots))
        return g
    }

    private fun TrailTogetherGame.playToTheEnd() {
        var guard = 0
        while (state.value!!.over.not()) {
            check(guard++ < 600)
            check(helperStep())
        }
    }

    /** A running game a few turns in, plus its snapshot. */
    private fun midGame(humans: Int = 2, bots: Int = 1, seed: Int = 3, band: TrailBand = TrailBand.MEDIUM): Pair<TrailTogetherGame, String> {
        val g = game(humans, bots, seed, band)
        g.startMatch()
        repeat(humans) { assertEquals(TrailMoveResult.OK, g.swap(it, 0)) }
        repeat(3) { g.helperStep() }
        val snap = g.snapshot()
        assertNotNull(snap)
        return g to snap!!
    }

    // ---- the module ----

    @Test
    fun theGameIsACooperativeBoardGameForOneToFourOnTheSameScreen() {
        val g = TrailTogetherGame()
        assertEquals("trail-together", g.gameId)
        assertEquals(1, g.minPlayers); assertEquals(4, g.maxPlayers)
        assertEquals(GameCategory.BOARD, g.category)
        assertEquals(setOf(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, PlayMode.SINGLE_PLAYER_VS_BOT), g.supportedModes.toSet())
    }

    @Test
    fun aRosterOutsideOneToFourSeatsIsRefused() {
        val g = TrailTogetherGame()
        var threw = 0
        for (n in listOf(0, 5, 6)) {
            try { g.init(trailContext(n, 0)) } catch (_: IllegalArgumentException) { threw++ }
        }
        assertEquals(3, threw)
    }

    @Test
    fun startingPublishesAStateAndEveryCallBeforeItIsRefused() {
        val g = game()
        assertNull(g.state.value)
        assertEquals(TrailMoveResult.NOT_ACTIVE, g.swap(0, 0))
        assertEquals(TrailMoveResult.NOT_ACTIVE, g.playCard(0, 0, TrailTarget()))
        assertFalse(g.helperStep())
        assertNull(g.hint())
        g.startMatch()
        assertNotNull(g.state.value)
        assertEquals(TrailMoveResult.OK, g.swap(0, 0))
        assertEquals(1, g.state.value!!.currentSeat)
        assertNotNull(g.hint())
    }

    @Test
    fun aSecondTapBeforeTheScreenHasSettledIsRefusedAsBusy() {
        val g = game(humans = 2, bots = 0)
        g.holdInputUntilSettled = true
        g.startMatch()
        assertEquals(TrailMoveResult.OK, g.swap(0, 0))
        // the other seat tries to act in the same input batch: refused until the screen reports the move settled
        assertEquals(TrailMoveResult.BUSY, g.swap(1, 0))
        assertEquals(TrailMoveResult.BUSY, g.playCard(1, 0, TrailTarget()))
        assertEquals(1, g.state.value!!.currentSeat)
        g.settled()
        assertEquals(TrailMoveResult.OK, g.swap(1, 0))
        // a rejected move does not hold the input
        g.settled()
        assertEquals(TrailMoveResult.NOT_YOUR_TURN, g.swap(1, 0))
        assertEquals(TrailMoveResult.OK, g.swap(0, 0))
    }

    @Test
    fun aHelperStepIsHeldUntilTheScreenHasSettledToo() {
        val g = game(humans = 0, bots = 2)
        g.holdInputUntilSettled = true
        g.startMatch()
        assertTrue(g.helperStep())
        assertFalse("the screen has not finished animating the first helper's play", g.helperStep())
        g.settled()
        assertTrue(g.helperStep())
    }

    // ---- resume ----

    @Test
    fun snapshotIsNullBeforeStartAndAfterMatchOver() {
        val g = game()
        assertNull(g.snapshot())
        g.startMatch()
        assertNotNull(g.snapshot())
        g.endMatch(GameResult(wasAborted = true))
        assertNull(g.snapshot())
        // a finished try has nothing to resume either
        val over = game(humans = 0, bots = 2)
        over.startMatch()
        over.playToTheEnd()
        assertNull(over.snapshot())
    }

    @Test
    fun snapshotIsOnlyForTheSameScreenModes() {
        val g = TrailTogetherGame(Random(1))
        g.init(trailContext(2, 0, PlayMode.LOCAL_AD_HOC))
        g.startMatch()
        assertNull(g.snapshot())
        val ok = TrailTogetherGame(Random(1))
        ok.init(trailContext(2, 0, PlayMode.SINGLE_DEVICE_PASS_AND_PLAY))
        ok.startMatch()
        assertNotNull(ok.snapshot())
    }

    @Test
    fun snapshotRoundTripRestoresIdenticalState() {
        for (band in TrailBand.entries) {
            val (g, snap) = midGame(band = band)
            val fresh = game(2, 1, seed = 9, band = TrailBand.BIG)
            assertTrue(fresh.restore(snap))
            assertEquals(g.state.value!!.core(), fresh.state.value!!.core())
            assertEquals("the saved band wins over the launch options", band, fresh.band)
            assertEquals(snap, fresh.snapshot())
            // both play on identically
            g.playToTheEndWithPeople()
            fresh.playToTheEndWithPeople()
            assertEquals(g.state.value!!.core(), fresh.state.value!!.core())
        }
    }

    /** Plays on: people swap their first card, helpers play their own turn. */
    private fun TrailTogetherGame.playToTheEndWithPeople() {
        var guard = 0
        while (state.value!!.over.not()) {
            check(guard++ < 800)
            val s = state.value!!
            if (!helperStep()) assertEquals(TrailMoveResult.OK, swap(s.currentSeat, 0))
        }
    }

    @Test
    fun handicapAndDifficultyComeBackWithTheSnapshot() {
        val g = game(humans = 2, bots = 1, band = TrailBand.BIG, level = CpuDifficulty.EASY)
        g.handicap = "p2"
        g.startMatch()
        g.swap(0, 0)
        val fresh = game(2, 1)
        assertTrue(fresh.restore(g.snapshot()!!))
        assertEquals("p2", fresh.handicap)
        assertEquals(CpuDifficulty.EASY, fresh.difficulty)
        assertEquals(listOf(3, 4, 3), fresh.state.value!!.hands.map { it.size })
    }

    @Test
    fun restoreRejectsWrongVersion() {
        val (_, snap) = midGame()
        val save = TrailSaveCodec.decode(snap, trailSeats(2, 1))!!
        assertRejected(save.copy(version = 2))
        assertRejected(save.copy(version = 0))
        assertRejected(save.copy(version = -1))
    }

    @Test
    fun restoreRejectsRosterMismatch() {
        val (_, snap) = midGame()
        val save = TrailSaveCodec.decode(snap, trailSeats(2, 1))!!
        assertRejected(save.copy(players = save.players.map { it.copy(playerId = it.playerId + "x") }))
        assertRejected(save.copy(players = save.players.mapIndexed { i, p -> if (i == 0) p.copy(isBot = true) else p }))
        assertRejected(save.copy(players = save.players.dropLast(1)))
        assertRejected(save.copy(players = save.players.reversed()))
        assertRejected(save.copy(hands = save.hands.dropLast(1)))
        assertRejected(save.copy(hands = save.hands + listOf(save.hands[0])))
        // and a save made for a different table size does not fit this one
        val g4 = game(4, 0)
        assertFalse(g4.restore(snap))
    }

    @Test
    fun restoreRejectsOutOfRangePalPositionAndFogOverflow() {
        val (_, snap) = midGame()
        val save = TrailSaveCodec.decode(snap, trailSeats(2, 1))!!
        val len = TrailRules.band(TrailBand.MEDIUM).length
        assertRejected(save.copy(pals = listOf(-1, save.pals[1], save.pals[2])))
        assertRejected(save.copy(pals = listOf(len + 1, save.pals[1], save.pals[2])))
        assertRejected(save.copy(pals = listOf(len, save.pals[1], save.pals[2])), "the Burrow is shut")
        assertRejected(save.copy(pals = listOf(save.trail.size, save.pals[1], save.pals[2])), "a pal on a dim spot")
        assertRejected(save.copy(pals = save.pals.drop(1)))
        assertRejected(save.copy(pals = save.pals + 0))
        assertRejected(save.copy(fog = TrailRules.deck(TrailBand.MEDIUM, 3).fogCards))
        assertRejected(save.copy(fog = TrailRules.deck(TrailBand.MEDIUM, 3).fogCards + 5))
        assertRejected(save.copy(fog = -1))
        assertRejected(save.copy(fog = save.fog + 1), "the fog marker must equal the fog cards in the discard")
    }

    @Test
    fun restoreRejectsDeckThatDoesNotMatchTable() {
        val (_, snap) = midGame()
        val save = TrailSaveCodec.decode(snap, trailSeats(2, 1))!!
        assertRejected(save.copy(pile = save.pile.drop(1)), "a card is missing")
        assertRejected(save.copy(pile = save.pile + "L"), "a card too many")
        assertRejected(save.copy(discard = save.discard + "T:berry"))
        assertRejected(save.copy(pile = save.pile.map { if (it == "F") "L" else it }), "fog cards turned into lanterns")
        assertRejected(save.copy(hands = listOf(save.hands[0].drop(1)) + save.hands.drop(1)), "a short hand")
        assertRejected(save.copy(hands = listOf(save.hands[0] + "L") + save.hands.drop(1)), "a long hand")
        assertRejected(save.copy(hands = listOf(listOf("F") + save.hands[0].drop(1)) + save.hands.drop(1)), "fog in a hand")
        assertRejected(save.copy(hands = listOf(listOf("X:berry") + save.hands[0].drop(1)) + save.hands.drop(1)), "an unknown code")
        assertRejected(save.copy(hands = listOf(listOf("S:berry:") + save.hands[0].drop(1)) + save.hands.drop(1)), "a non-canonical code")
        assertRejected(save.copy(hands = listOf(listOf("S:peach") + save.hands[0].drop(1)) + save.hands.drop(1)), "a colour outside a Medium trail")
        assertRejected(save.copy(plays = save.plays + 1), "plays must equal the non-fog cards in the discard")
        assertRejected(save.copy(plays = -1))
    }

    @Test
    fun restoreRejectsBadTrailsBandsAndCounters() {
        val (_, snap) = midGame()
        val save = TrailSaveCodec.decode(snap, trailSeats(2, 1))!!
        val p = TrailRules.band(TrailBand.MEDIUM)
        assertRejected(save.copy(trail = save.trail.take(p.builtAtStart - 1)), "an opening trail that is too short")
        assertRejected(save.copy(trail = save.trail + List(p.length) { "berry" }), "a trail longer than the Burrow")
        assertRejected(save.copy(trail = save.trail.mapIndexed { i, c -> if (i == 0) "peach" else c }), "a colour outside the band")
        assertRejected(save.copy(trail = listOf("berry", "berry") + save.trail.drop(2)), "two equal neighbours at the start")
        assertRejected(save.copy(trail = save.trail.mapIndexed { i, c -> if (i == 0) "teal" else c }), "an unknown colour")
        // a freshly dealt table lights nothing: one more lit stone than the discard can explain is rejected
        val fresh = trailStarted(4L, humans = 2, bots = 1, band = TrailBand.MEDIUM).toSave()!!
        assertTrue(TrailSaveCodec.isValid(fresh, trailSeats(2, 1)))
        assertRejected(fresh.copy(trail = fresh.trail + "sun"), "a lit stone with no stone or lantern card played")
        assertRejected(save.copy(band = "gigantic"))
        assertRejected(save.copy(band = "big"), "a Medium deck is not a Big deck")
        assertRejected(save.copy(difficulty = "IMPOSSIBLE"))
        assertRejected(save.copy(handicap = "p1".padEnd(60, ' ')))
        assertRejected(save.copy(handicap = "4,3,3"), "a hand size list changes the hand sizes, so the hands no longer fit")
        assertRejected(save.copy(currentSeat = 3))
        assertRejected(save.copy(currentSeat = -1))
        assertRejected(save.copy(tries = -1))
        assertRejected(save.copy(wins = 5, tries = 2))
        assertRejected(save.copy(tries = TrailRules.MAX_TRIES + 1, wins = 0))
        assertRejected(save.copy(eventSerial = -1))
        assertRejected(save.copy(eventSerial = TrailRules.MAX_EVENT_SERIAL + 1))
    }

    @Test
    fun restoreRejectsGarbageTextAndOversizedText() {
        val g = game(2, 1)
        for (bad in listOf("", "{}", "null", "[]", "not json", "{\"version\":1}", "{\"players\":[]}", "\u0000", "{".repeat(50))) {
            assertFalse("'$bad'", g.restore(bad))
        }
        assertFalse(g.restore("x".repeat(TrailSaveCodec.MAX_TEXT + 1)))
        val (_, snap) = midGame()
        assertFalse(g.restore(snap.dropLast(7)))
        assertFalse(g.restore(snap.replace("\"band\":\"medium\"", "\"band\":\"medium\",\"pals\":\"x\"")))
        assertNull(g.state.value)
    }

    @Test
    fun aSaveWithAWonTryInItsTallyStillRestores() {
        val e = trailEngine(humans = 2, bots = 0)
        val seats = trailSeats(2, 0)
        val save = craftSave(seats, trail = LITTLE_OPENING, pals = listOf(0, 0), hands = listOf(listOf("S:berry", "S:sun", "T:leaf"), listOf("T:berry", "T:sun", "T:sky")), tries = 4, wins = 3)
        assertTrue(e.restore(save))
        assertEquals(4, e.sessionTries); assertEquals(3, e.sessionWins)
        assertEquals(save, e.toSave()!!.copy(eventSerial = save.eventSerial))
    }

    @Test
    fun restoreFailureLeavesGameUntouched() {
        val (g, snap) = midGame()
        val before = g.state.value
        val beforeSnap = g.snapshot()
        val bad = TrailSaveCodec.decode(snap, trailSeats(2, 1))!!.copy(fog = 99)
        assertFalse(g.restore(TrailSaveCodec.encode(bad)))
        assertFalse(g.restore("garbage"))
        assertEquals(before, g.state.value)
        assertEquals(beforeSnap, g.snapshot())
        assertEquals(TrailBand.MEDIUM, g.band)
        assertFalse(g.matchOver.value)
        // an engine rejects a bad save without a trace too
        val e = trailStarted(8L, humans = 2, bots = 1)
        val saved = e.toSave()
        assertFalse(e.restore(bad))
        assertEquals(saved, e.toSave())
    }

    // ---- results ----

    @Test
    fun leavingBeforeAnyTryEndsUsesAbortPath() {
        val g = game(humans = 2, bots = 0)
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.startMatch()
        g.swap(0, 0)
        g.leaveSession()
        assertNotNull(result)
        assertTrue(result!!.wasAborted)
        assertTrue(result!!.scores.isEmpty())
        assertTrue(g.matchOver.value)
        // a second leave does not fire the callback again
        var again = 0
        g.setOnMatchEnd { again++ }
        g.leaveSession()
        assertEquals(0, again)
    }

    @Test
    fun resultMarksEveryoneWinnerOnlyWhenAtLeastOneTryWon() {
        var sawWon = false
        var sawLost = false
        var seed = 0
        while (!(sawWon && sawLost) && seed < 400) {
            // Big trail with easy helpers: both outcomes turn up quickly
            val g = game(humans = 0, bots = 3, seed = seed++, band = TrailBand.BIG, level = CpuDifficulty.EASY)
            var result: GameResult? = null
            g.setOnMatchEnd { result = it }
            g.startMatch()
            g.playToTheEnd()
            val won = g.state.value!!.won
            g.leaveSession()
            val r = result!!
            assertFalse(r.wasAborted)
            assertEquals(3, r.scores.size)
            if (won) {
                sawWon = true
                assertTrue(r.scores.all { it.isWinner && it.score == 1 })
            } else {
                sawLost = true
                assertTrue("a lost try makes nobody a winner", r.scores.none { it.isWinner })
                assertTrue(r.scores.all { it.score == 0 })
            }
        }
        assertTrue("saw a won and a lost session ($sawWon, $sawLost)", sawWon && sawLost)
    }

    @Test
    fun aSessionWithALossAndALaterWinCountsAsWon() {
        // play again until a try is won after at least one loss, or just won; either way the final tally decides
        val g = game(humans = 0, bots = 3, seed = 5, band = TrailBand.BIG, level = CpuDifficulty.EASY)
        var result: GameResult? = null
        g.setOnMatchEnd { result = it }
        g.startMatch()
        var tries = 0
        while (tries < 60) {
            g.playToTheEnd()
            tries++
            if (g.state.value!!.wins > 0) break
            g.playAgain()
        }
        assertTrue(g.state.value!!.tries >= 1)
        val wins = g.state.value!!.wins
        g.leaveSession()
        assertTrue(result!!.scores.all { it.isWinner == (wins > 0) && it.score == wins })
    }

    @Test
    fun playAgainStartsFreshSeedAndKeepsTheTallyThroughTheGame() {
        val g = game(humans = 0, bots = 2, seed = 11)
        g.startMatch()
        val first = g.state.value!!
        g.playToTheEnd()
        val tries = g.state.value!!.tries
        g.playAgain()
        val s = g.state.value!!
        assertNotEquals(first.seed, s.seed)
        assertEquals(tries, s.tries)
        assertEquals(TrailPhase.PICK_CARD, s.phase)
        assertEquals(0, s.plays)
    }

    // ---- wording ----

    private fun resFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun parse(dir: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        resFile(dir).listFiles { f -> f.isFile && f.name.startsWith("trail_together_") && f.name.endsWith(".xml") }!!.forEach { f ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.documentElement.childNodes
            for (i in 0 until nodes.length) {
                val e = nodes.item(i) as? Element ?: continue
                if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
            }
        }
        return out
    }

    @Test
    fun resultAndStatusTextUseCooperativeWordingOnly() {
        val banned = mapOf(
            "values" to listOf("winner", "loser", "you lose", "you lost", "game over", "wrong", "defeat", "beaten", "fail"),
            "values-es" to listOf("ganador", "perdedor", "has perdido", "perdiste", "fin del juego", "incorrecto", "equivocad", "derrota", "fallaste")
        )
        for ((dir, words) in banned) {
            for ((k, v) in parse(dir)) {
                if (k.startsWith("coach_")) continue
                for (w in words) assertFalse("$dir/$k contains '$w': $v", v.lowercase().contains(w))
            }
        }
        val en = parse("res/values")
        assertEquals("We did it!", en["trail_together_status_win"]!!.replace("\\'", "'"))
        assertEquals("Try again together", en["trail_together_status_lose"])
    }

    // ---- pause ----

    @Test
    fun pauseStopsHelperAutoplayAndResumeContinues() {
        val g = game(humans = 0, bots = 2)
        g.startMatch()
        g.pause()
        assertTrue(g.paused.value)
        val before = g.state.value
        assertFalse(g.helperStep())
        assertEquals(before, g.state.value)
        g.resume()
        assertFalse(g.paused.value)
        assertTrue(g.helperStep())
        assertNotEquals(before, g.state.value)
    }

    private fun assertRejected(save: TrailSave, why: String = "") {
        val seats = trailSeats(2, 1)
        assertFalse("must be rejected: $why $save", TrailSaveCodec.isValid(save, seats))
        val g = game(2, 1)
        assertFalse("restore must refuse: $why", g.restore(TrailSaveCodec.encode(save)))
        assertNull("a refused restore leaves the game untouched: $why", g.state.value)
    }
}
