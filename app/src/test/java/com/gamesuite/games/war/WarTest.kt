package com.gamesuite.games.war

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.blackjack.BwHub
import com.gamesuite.games.blackjack.cd
import com.gamesuite.games.blackjack.cds
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WarTest {
    private fun pile(vararg c: String) = ArrayDeque(cds(*c))
    private fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }
    private val vsCpu = listOf(PlayerInfo("you", "You"), PlayerInfo("cpu", "CPU", isBot = true))

    private fun game(
        players: List<PlayerInfo> = vsCpu, config: WarConfig = WarConfig(), seed: Int = 1,
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT, level: CpuDifficulty = CpuDifficulty.MEDIUM
    ): WarGame {
        val g = WarGame(Random(seed))
        g.config = config
        g.difficulty = level
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        g.startMatch()
        return g
    }

    private fun WarGame.s() = state.value!!
    private fun WarGame.total() = s().seats.sumOf { it.count }

    // ---- the rules -------------------------------------------------------------------------------

    @Test fun dealSplitsTheDeckAndLosesNothing() {
        val deck = Deck.fresh52().shuffled(Random(3))
        for ((n, sizes) in mapOf(2 to listOf(26, 26), 3 to listOf(18, 17, 17), 4 to listOf(13, 13, 13, 13))) {
            val piles = WarRules.deal(deck, n)
            assertEquals(sizes, piles.map { it.size })
            assertEquals(52, piles.flatten().map { it.id }.toSet().size)
        }
        try { WarRules.deal(deck, 1); throw AssertionError("one seat must be refused") } catch (_: IllegalArgumentException) {}
        try { WarRules.deal(deck, 5); throw AssertionError("five seats must be refused") } catch (_: IllegalArgumentException) {}
    }

    @Test fun higherCardTakesBothAcesAreHigh() {
        val piles = listOf(pile("2C"), pile("3C"))
        val r = WarRules.resolve(piles, mapOf(0 to cd("AS"), 1 to cd("KD")), 3, Random(1))
        assertEquals(0, r.winnerSeat)
        assertEquals(2, r.potSize)
        assertEquals(0, r.wars)
        assertEquals(3, piles[0].size)
        assertEquals(1, piles[1].size)
    }

    @Test fun aTieGoesToWarWithThreeFaceDownThenOneFaceUp() {
        val piles = listOf(pile("2C", "3C", "4C", "KD", "9H"), pile("2D", "3D", "4D", "5D", "9S"))
        val r = WarRules.resolve(piles, mapOf(0 to cd("7S"), 1 to cd("7H")), 3, Random(1))
        assertEquals(1, r.wars)
        assertEquals(2, r.steps.size)
        assertEquals(listOf(3, 3), r.steps[1].plays.map { it.down })
        assertEquals(cd("KD"), r.steps[1].plays.first { it.seat == 0 }.up)
        assertEquals(0, r.winnerSeat)
        assertEquals(10, r.potSize)                     // 2 + 2 * (3 down + 1 up)
        assertEquals(1 + 10, piles[0].size)
        assertEquals(1, piles[1].size)
    }

    @Test fun warCanRepeatAndOneFaceDownVariantWorks() {
        val piles = listOf(pile("2C", "KS", "3C", "AD", "9H"), pile("2D", "KH", "3D", "5D", "9S"))
        val r = WarRules.resolve(piles, mapOf(0 to cd("7S"), 1 to cd("7H")), 1, Random(1))
        assertEquals(2, r.wars)                         // first war KS vs KH ties, second AD vs 5D
        assertEquals(0, r.winnerSeat)
        assertEquals(2 + 4 + 4, r.potSize)
        assertEquals(listOf(1, 1), r.steps[1].plays.map { it.down })
    }

    @Test fun aPlayerShortOfCardsPlaysTheLastOneFaceUp() {
        val piles = listOf(pile("9C", "9D", "9H", "KC", "2S"), pile("QD"))
        val r = WarRules.resolve(piles, mapOf(0 to cd("7S"), 1 to cd("7H")), 3, Random(1))
        // seat 1 has one card: no face-down cards, that card is its deciding card
        assertEquals(0, r.steps[1].plays.first { it.seat == 1 }.down)
        assertEquals(cd("QD"), r.steps[1].plays.first { it.seat == 1 }.up)
        assertEquals(0, r.winnerSeat)                   // KC beats QD
    }

    @Test fun aTiedPlayerWithNoCardsLosesTheWar() {
        val piles = listOf(pile("2C", "3C", "4C", "5C"), ArrayDeque<Card>())
        val r = WarRules.resolve(piles, mapOf(0 to cd("7S"), 1 to cd("7H")), 3, Random(1))
        assertEquals(0, r.winnerSeat)
        assertEquals(2, r.potSize)                      // nothing was committed to the war
        assertEquals(4 + 2, piles[0].size)
        assertEquals(0, piles[1].size)
    }

    @Test fun ifEveryTiedPlayerIsOutOfCardsThePotIsShared() {
        val piles = listOf(ArrayDeque<Card>(), ArrayDeque<Card>())
        val r = WarRules.resolve(piles, mapOf(0 to cd("7S"), 1 to cd("7H")), 3, Random(1))
        assertEquals(-1, r.winnerSeat)
        assertEquals(listOf(1, 1), piles.map { it.size })
    }

    @Test fun threeSeatsOnlyTheTopTiedSeatsGoToWar() {
        val piles = listOf(pile("2C", "3C", "4C", "KD"), pile("2D", "3D", "4D", "5D"), pile("2H", "3H", "4H", "6H"))
        val r = WarRules.resolve(piles, mapOf(0 to cd("9S"), 1 to cd("9H"), 2 to cd("4S")), 3, Random(1))
        assertEquals(listOf(0, 1), r.steps[1].plays.map { it.seat })
        assertEquals(0, r.winnerSeat)
        assertEquals(3 + 8, r.potSize)
        assertEquals(piles[2].size, 4)                  // the third player's pile was untouched
    }

    @Test fun resolveConservesEveryCard() {
        val rnd = Random(5)
        repeat(300) {
            val deck = Deck.fresh52().shuffled(rnd)
            val n = 2 + rnd.nextInt(3)
            val piles = WarRules.deal(deck, n)
            val firsts = HashMap<Int, Card>()
            piles.forEachIndexed { i, p -> if (p.isNotEmpty()) firsts[i] = p.removeFirst() }
            WarRules.resolve(piles, firsts, if (rnd.nextBoolean()) 3 else 1, rnd)
            assertEquals(52, piles.flatten().map { it.id }.toSet().size)
        }
    }

    @Test fun bestChoicePrefersTheCardWithTheBestExpectedSwing() {
        assertEquals(2, WarRules.bestChoice(cds("2C", "9D", "AS")))
        assertEquals(0, WarRules.bestChoice(cds("KC", "9D", "3S")))
        assertEquals(0, WarRules.bestChoice(cds("7C")))
        assertEquals(0, WarRules.bestChoice(emptyList()))
        val idx = WarRules.bestChoice(cds("5C", "5D", "5H"))
        assertTrue(idx in 0..2)
    }

    // ---- the engine: classic ---------------------------------------------------------------------

    @Test fun aClassicGameAlwaysEndsAndNeverLosesACard() {
        for (seed in 0 until 25) {
            val g = game(config = WarConfig(maxRounds = 400), seed = seed)
            var flips = 0
            while (g.s().phase != WarPhase.OVER) {
                assertTrue(g.flip())
                flips++
                assertEquals(52, g.total())
                assertTrue("never more than the cap", flips <= 400)
            }
            assertEquals(flips, g.s().round)
            val s = g.s()
            if (s.draw) assertEquals(-1, s.winnerSeat) else assertTrue(s.winnerSeat in 0..1)
            if (s.round < 400) assertTrue(s.seats.any { it.count == 52 })
        }
    }

    @Test fun theRoundLimitCallsTheGameForTheBiggerPile() {
        val g = game(config = WarConfig(maxRounds = 10), seed = 4)
        repeat(10) { assertTrue(g.flip()) }
        val s = g.s()
        assertEquals(WarPhase.OVER, s.phase)
        val counts = s.seats.map { it.count }
        if (counts[0] == counts[1]) assertTrue(s.draw) else assertEquals(counts.indexOf(counts.max()), s.winnerSeat)
        assertFalse(g.flip())                     // nothing more to do
    }

    @Test fun sameSeedPlaysTheSameGameAndADifferentSeedDoesNot() {
        fun trail(seed: Int): List<Int> {
            val g = game(seed = seed, config = WarConfig(maxRounds = 120))
            val out = ArrayList<Int>()
            while (g.s().phase != WarPhase.OVER) { g.flip(); out += g.s().seats[0].count }
            return out
        }
        assertEquals(trail(7), trail(7))
        assertTrue(trail(7) != trail(8))
    }

    @Test fun playAgainDealsAFreshGameAndKeepsTheSessionTally() {
        val g = game(config = WarConfig(maxRounds = 10), seed = 2)
        repeat(10) { g.flip() }
        val winner = g.s().winnerSeat
        g.playAgain()
        assertEquals(WarPhase.FLIP, g.s().phase)
        assertEquals(0, g.s().round)
        assertEquals(26, g.s().seats[0].count)
        if (winner >= 0) assertEquals(1, g.s().seats[winner].wins)
    }

    @Test fun leavingScoresTheSessionByGamesWon() {
        val g = game(config = WarConfig(maxRounds = 10), seed = 2)
        repeat(10) { g.flip() }
        var result: com.gamesuite.core.GameResult? = null
        g.setOnMatchEnd { result = it }
        g.leaveSession()
        val wins = g.s().seats.map { it.wins }
        assertEquals(wins, result!!.scores.map { it.score })
        assertTrue(g.matchOver.value)
        assertFalse(g.flip())
        assertNull(g.snapshot())
    }

    @Test fun configIsClampedToValidChoices() {
        assertEquals(WarConfig(), WarConfig.fromExtras(emptyMap()))
        val c = WarConfig.fromExtras(mapOf("variant" to "choose3", "warDown" to "1", "maxRounds" to "600"))
        assertEquals(WarVariant.CHOOSE3, c.variant); assertEquals(1, c.warDown); assertEquals(600, c.maxRounds)
        val bad = WarConfig.fromExtras(mapOf("variant" to "x", "warDown" to "2", "maxRounds" to "1"))
        assertEquals(WarConfig(), bad)
        assertTrue(WarConfig().isValid)
        assertFalse(WarConfig(maxRounds = 5).isValid)
    }

    // ---- the engine: choose 3 --------------------------------------------------------------------

    private val choose3 = WarConfig(variant = WarVariant.CHOOSE3, maxRounds = 200)

    @Test fun choose3ShowsTheTopThreeAndPlaysThePickedOne() {
        val g = game(config = choose3, seed = 5)
        assertEquals(WarPhase.PICK, g.s().phase)
        assertEquals(0, g.pickerSeat())
        val mine = g.myChoices()
        assertEquals(3, mine.size)
        assertFalse(g.flip())                     // flipping is the classic action
        assertEquals(WarReject.BAD_CHOICE, g.explainPick(3))
        assertEquals(WarReject.BAD_CHOICE, g.explainPick(-1))
        assertTrue(g.pick(1))
        // the bot answers, the round resolves, and the two unchosen cards stay on top of the pile
        assertTrue(g.botToMove())
        g.playBotStep()
        val s = g.s()
        assertEquals(1, s.round)
        assertEquals(52, g.total())
        assertNotNull(s.lastRound)
        assertEquals(mine[1], s.lastRound!!.steps[0].plays.first { it.seat == 0 }.up)
        assertEquals(3, g.myChoices().size)
        if (s.lastRound!!.wars == 0) assertTrue(mine[0] in g.myChoices() && mine[2] in g.myChoices())
    }

    @Test fun aSecondPickInTheSameRoundIsRefused() {
        val g = game(players = humans(3), config = choose3, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        assertTrue(g.pick(0))
        assertEquals(1, g.pickerSeat())            // the next person, never the same one twice
        assertTrue(g.s().seats[0].picked)
        assertTrue(g.s().choices[0].isEmpty())
    }

    @Test fun hotSeatPlayersPickInTurnAndTheRoundResolvesWhenAllHave() {
        val g = game(players = humans(2), config = choose3, mode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        assertEquals(0, g.pickerSeat())
        val first = g.myChoices()
        assertTrue(g.pick(2))
        assertEquals(1, g.pickerSeat())
        val second = g.myChoices()
        assertTrue(first != second)
        assertEquals(0, g.s().round)
        assertTrue(g.pick(0))
        assertEquals(1, g.s().round)
        assertEquals(0, g.pickerSeat())
    }

    @Test fun hintIsTheBestExpectedSwingAmongTheThree() {
        val g = game(config = choose3, seed = 8)
        assertEquals(WarRules.bestChoice(g.myChoices()), g.hint())
        assertEquals(g.hint(), g.hintIndex.value)
        val c = game()          // classic: nothing to choose
        assertNull(c.hint())
    }

    @Test fun hardPicksTheBestCardEveryTimeAndEasyDoesNot() {
        fun agree(level: CpuDifficulty): Int {
            val g = game(config = choose3.copy(maxRounds = 500), seed = 11, level = level)
            var best = 0
            var total = 0
            while (g.s().phase != WarPhase.OVER && total < 150) {
                val seat = 1
                val cand = g.s().choices[seat]
                if (cand.isNotEmpty() && g.s().seats[0].picked) {
                    val before = g.s().round
                    g.playBotStep()
                    total++
                    val played = g.s().lastRound!!.steps[0].plays.first { it.seat == seat }.up
                    if (played == cand[WarRules.bestChoice(cand)]) best++
                    assertEquals(before + 1, g.s().round)
                } else g.pick(0)
            }
            return best * 100 / total.coerceAtLeast(1)
        }
        assertEquals(100, agree(CpuDifficulty.HARD))
        assertTrue(agree(CpuDifficulty.EASY) < 80)
    }

    @Test fun asyncBotPickRunsOffTheMainThreadAndAppliesOnce() {
        val g = game(config = choose3, seed = 6)
        g.pick(0)
        runBlocking { g.playBotTurnAsync() }
        assertEquals(1, g.s().round)
        runBlocking { g.playBotTurnAsync() }      // nothing to do for the bot now
        assertEquals(1, g.s().round)
    }

    // ---- resume ----------------------------------------------------------------------------------

    private fun fresh(players: List<PlayerInfo> = vsCpu, mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT): WarGame {
        val g = WarGame(Random(1))
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        return g
    }

    @Test fun snapshotRoundTripsMidGameInBothVariantsAndPlayContinuesTheSame() {
        for (variant in listOf(WarVariant.CLASSIC, WarVariant.CHOOSE3)) {
            val a = game(config = WarConfig(variant = variant, maxRounds = 300), seed = 9)
            var steps = 0
            while (a.s().phase != WarPhase.OVER && steps++ < 80) {
                val snap = a.snapshot()!!
                val b = fresh()
                assertTrue("restore $variant step $steps", b.restore(snap))
                assertEquals(a.s().copy(lastRound = null), b.s())
                assertEquals(snap, b.snapshot())
                if (a.botToMove()) a.playBotStep() else if (variant == WarVariant.CHOOSE3) a.pick(steps % 3) else a.flip()
            }
        }
    }

    @Test fun aRestoredGameFlipsTheSameCardsAsTheOriginal() {
        val a = game(seed = 12)
        repeat(5) { a.flip() }
        val b = fresh()
        assertTrue(b.restore(a.snapshot()!!))
        a.flip(); b.flip()
        assertEquals(a.s().seats.map { it.count }, b.s().seats.map { it.count })
    }

    @Test fun restoreRejectsCorruptStaleAndForeignSaves() {
        val a = game(seed = 3)
        repeat(3) { a.flip() }
        val good = a.snapshot()!!
        assertTrue(fresh().restore(good))
        assertFalse(fresh().restore("nope"))
        assertFalse(fresh().restore("{}"))
        assertFalse(fresh(listOf(PlayerInfo("other", "You"), PlayerInfo("cpu", "CPU", true))).restore(good))
        assertFalse(fresh(humans(3), PlayMode.SINGLE_DEVICE_PASS_AND_PLAY).restore(good))
        // a card duplicated and another missing
        val dup = good.replaceFirst(Regex("\\[(\\d+),"), "[").let { if (it == good) good else it }
        if (dup != good) assertFalse(fresh().restore(dup))
        assertFalse(fresh().restore(good.replace("\"phase\":\"flip\"", "\"phase\":\"pick\"")))
        assertFalse(fresh().restore(good.replace("\"variant\":\"classic\"", "\"variant\":\"weird\"")))
        assertFalse(fresh().restore(good.replace("\"phase\":\"flip\"", "\"phase\":\"over\"")))   // over needs a winner or a draw
        val c = fresh()
        c.restore("garbage")
        assertNull(c.state.value)
    }

    // ---- fuzz ------------------------------------------------------------------------------------

    @Test fun fuzzEverySeatCountVariantAndLevelTerminatesWithAllCardsAccounted() {
        var finished = 0
        for (seed in 0 until 90) {
            val rnd = Random(seed)
            val seats = 2 + rnd.nextInt(3)
            val players = List(seats) { i -> if (i > 0 && rnd.nextBoolean()) PlayerInfo("b$i", "CPU $i", true) else PlayerInfo("p$i", "P$i") }
            val cfg = WarConfig(
                variant = if (rnd.nextBoolean()) WarVariant.CHOOSE3 else WarVariant.CLASSIC,
                warDown = if (rnd.nextBoolean()) 3 else 1, maxRounds = 10 + rnd.nextInt(250)
            )
            val mode = if (players.count { !it.isBot } > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT
            val g = game(players, cfg, seed, mode, CpuDifficulty.entries[seed % 3])
            var steps = 0
            while (g.s().phase != WarPhase.OVER) {
                assertTrue("seed $seed hung", steps++ < 5000)
                when {
                    g.botToMove() -> g.playBotStep()
                    g.s().phase == WarPhase.PICK -> assertTrue(g.pick(rnd.nextInt(g.myChoices().size)))
                    else -> assertTrue(g.flip())
                }
                assertEquals(52, g.total())
                if (steps % 11 == 0) assertTrue(fresh(players, mode).restore(g.snapshot()!!))
            }
            assertTrue(g.s().round <= cfg.maxRounds)
            finished++
        }
        assertEquals(90, finished)
    }

    // ---- networking ------------------------------------------------------------------------------

    private class Node(val game: WarGame, val transport: com.gamesuite.games.blackjack.BwFakeTransport)

    private fun encode(m: WarNetMessage) = Json.encodeToString(WarNetMessage.serializer(), m).toByteArray(Charsets.UTF_8)

    private fun net(seats: Int, config: WarConfig, seed: Int = 1): Pair<BwHub, List<Node>> {
        val ids = List(seats) { if (it == 0) "h" else "g$it" }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, "N$i") }
        val hub = BwHub()
        val nodes = ids.mapIndexed { i, id ->
            val t = hub.endpoint(id)
            val g = WarGame(if (i == 0) Random(seed) else Random(777 + i))
            g.config = config
            g.init(GameContext(PlayMode.LOCAL_AD_HOC, players, i, t))
            Node(g, t)
        }
        nodes[0].game.startMatch()
        return hub to nodes
    }

    @Test fun guestsFlipThroughTheHostAndSeeTheSameRound() {
        val (hub, nodes) = net(3, WarConfig(maxRounds = 100))
        assertTrue(nodes[1].game.flip())
        val h = nodes[0].game.s()
        assertEquals(1, h.round)
        nodes.forEach {
            assertEquals(h.round, it.game.s().round)
            assertEquals(h.lastRound, it.game.s().lastRound)
            assertEquals(h.seats.map { s -> s.count }, it.game.s().seats.map { s -> s.count })
        }
        assertTrue(hub.lies.isEmpty())
    }

    @Test fun pilesNeverLeaveTheHostOnlyTheirSizes() {
        val (_, nodes) = net(2, WarConfig(maxRounds = 100))
        repeat(5) { nodes[0].game.flip() }
        val raw = nodes[0].transport.sent.joinToString("") { String(it.payload, Charsets.UTF_8) }
        assertFalse(raw.contains("piles"))
        assertFalse(raw.contains("shoe"))
        val states = nodes[0].transport.sent.map { Json.decodeFromString(WarNetMessage.serializer(), String(it.payload, Charsets.UTF_8)) }
            .filterIsInstance<WarNetMessage.StateSync>()
        assertTrue(states.isNotEmpty())
        assertTrue(states.all { it.state.choices.all { c -> c.isEmpty() } })
    }

    @Test fun eachGuestIsSentOnlyItsOwnThreeCardsAndPicksStaySecretUntilTheReveal() {
        val (hub, nodes) = net(3, choose3)
        for (i in 1..2) {
            val mine = nodes[i].game.s().choices
            assertEquals(3, mine[i].size)
            mine.forEachIndexed { seat, c -> if (seat != i) assertTrue("guest $i must not see seat $seat", c.isEmpty()) }
        }
        val hostChoices = nodes[0].game.s().choices
        assertEquals(3, hostChoices[0].size)
        // what the host sent to g1 never contains g2's candidates
        val toG1 = nodes[0].transport.sent.filter { it.to == "g1" }
            .map { Json.decodeFromString(WarNetMessage.serializer(), String(it.payload, Charsets.UTF_8)) }
            .filterIsInstance<WarNetMessage.StateSync>()
        assertTrue(toG1.all { it.state.choices[2].isEmpty() && it.state.choices[0].isEmpty() })
        // g1 picks: everyone sees only a "picked" flag, never which card
        assertTrue(nodes[1].game.pick(0))
        assertTrue(nodes[2].game.s().seats[1].picked)
        assertEquals(0, nodes[2].game.s().round)
        assertNull(nodes[2].game.s().lastRound)
        assertTrue(nodes[0].game.pick(1))
        assertEquals(0, nodes[0].game.s().round)
        assertTrue(nodes[2].game.pick(2))
        nodes.forEach { assertEquals(1, it.game.s().round) }
        val round = nodes[1].game.s().lastRound!!
        assertEquals(nodes[0].game.s().lastRound, round)
        assertEquals(hostChoices[0][1], round.steps[0].plays.first { it.seat == 0 }.up)
        assertTrue(hub.lies.isEmpty())
    }

    @Test fun aGuestCannotPickForAnotherSeatOrTwiceOrOutOfRange() {
        val (hub, nodes) = net(3, choose3)
        val before = nodes[0].game.s()
        // g2 cannot speak for g1: the host acts on the transport's sender only
        nodes[2].transport.send("g2", "h", encode(WarNetMessage.PickRequest(0)))
        assertEquals(1, nodes[0].game.s().seats.count { it.picked })
        assertTrue(nodes[0].game.s().seats[2].picked && !nodes[0].game.s().seats[1].picked)
        nodes[2].transport.send("g2", "h", encode(WarNetMessage.PickRequest(1)))      // twice
        nodes[1].transport.send("g1", "h", encode(WarNetMessage.PickRequest(7)))      // out of range
        nodes[1].transport.send("g1", "h", encode(WarNetMessage.PickRequest(-2)))
        nodes[1].transport.send("g1", "h", encode(WarNetMessage.FlipRequest))          // wrong action for the variant
        nodes[1].transport.send("g1", "h", "junk".toByteArray())
        assertEquals(1, nodes[0].game.s().seats.count { it.picked })
        assertEquals(0, nodes[0].game.s().round)
        assertTrue(before.round == nodes[0].game.s().round)
        assertTrue(hub.lies.isEmpty())
    }

    @Test fun guestsRejectForgedStaleAndMalformedSyncs() {
        val (_, nodes) = net(2, WarConfig(maxRounds = 100))
        val g = nodes[1]
        val real = g.game.state.value
        val good = nodes[0].game.toNet(nodes[0].game.s(), 1)
        g.transport.inject("g1", encode(WarNetMessage.StateSync(300, good)))                       // not from the host
        g.transport.inject("h", "###".toByteArray())
        g.transport.inject("h", encode(WarNetMessage.StateSync(301, good.copy(phase = "x"))))
        g.transport.inject("h", encode(WarNetMessage.StateSync(302, good.copy(seats = good.seats.take(1)))))
        g.transport.inject("h", encode(WarNetMessage.StateSync(303, good.copy(seats = good.seats.map { it.copy(count = 99) }))))
        g.transport.inject("h", encode(WarNetMessage.StateSync(304, good.copy(choices = listOf(listOf(99), emptyList())))))
        g.transport.inject("h", encode(WarNetMessage.StateSync(305, good.copy(config = WarConfig(maxRounds = 1)))))
        assertTrue(real === g.game.state.value)
        nodes[0].game.flip()
        val cur = g.game.state.value
        g.transport.inject("h", encode(WarNetMessage.StateSync(0, good)))
        assertTrue(cur === g.game.state.value)
    }

    @Test fun aDepartedPlayerIsPlayedByTheComputerAndTheGameCarriesOn() {
        val (_, nodes) = net(3, choose3)
        nodes[0].transport.fireLeft("g1")
        assertTrue(nodes[0].game.s().seats[1].isBot)
        assertTrue(nodes[0].game.botToMove())
        nodes[0].game.playBotStep()
        assertTrue(nodes[0].game.s().seats[1].picked)
        nodes[0].transport.fireJoined("g1")
        assertFalse(nodes[0].game.s().seats[1].isBot)
    }

    @Test fun networkedPlayToTheEndKeepsEveryoneInSync() {
        for (variant in listOf(WarVariant.CLASSIC, WarVariant.CHOOSE3)) {
            val (_, nodes) = net(3, WarConfig(variant = variant, maxRounds = 60), seed = 4)
            var guard = 0
            while (nodes[0].game.s().phase != WarPhase.OVER && guard++ < 500) {
                if (variant == WarVariant.CLASSIC) nodes[1 + guard % 2].game.flip()
                else nodes.forEach { n -> if (n.game.pickerSeat() != null) n.game.pick(0) }
                assertEquals(52, nodes[0].game.total())
            }
            assertEquals(WarPhase.OVER, nodes[0].game.s().phase)
            nodes.forEach { assertEquals(nodes[0].game.s().winnerSeat, it.game.s().winnerSeat) }
            nodes[2].game.playAgain()
            assertEquals(0, nodes[0].game.s().round)
            nodes.forEach { assertEquals(0, it.game.s().round) }
        }
    }
}
