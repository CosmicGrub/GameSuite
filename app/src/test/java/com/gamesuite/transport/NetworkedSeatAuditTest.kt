package com.gamesuite.transport

import com.gamesuite.core.GameContext
import com.gamesuite.core.GameHubSpecs
import com.gamesuite.core.GameModule
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.blackjack.BlackjackGame
import com.gamesuite.games.bowling.BowlingGame
import com.gamesuite.games.carrom.CarromGame
import com.gamesuite.games.chinesecheckers.ChineseCheckersGame
import com.gamesuite.games.curling.CurlingGame
import com.gamesuite.games.darts.DartsGame
import com.gamesuite.games.dominoes.DominoGame
import com.gamesuite.games.fishing.FishingGame
import com.gamesuite.games.holdem.HoldemGame
import com.gamesuite.games.ludo.LudoGame
import com.gamesuite.games.matching.MatchingGame
import com.gamesuite.games.minigolf.GolfGame
import com.gamesuite.games.pigdice.PigGame
import com.gamesuite.games.president.PresidentGame
import com.gamesuite.games.sevens.SevensGame
import com.gamesuite.games.shootinggallery.GalleryGame
import com.gamesuite.games.slotcars.SlotCarsGame
import com.gamesuite.games.tankbattle.TankGame
import com.gamesuite.games.toysoccer.ToySoccerGame
import com.gamesuite.games.toytennis.ToyTennisGame
import com.gamesuite.games.uno.UnoGame
import com.gamesuite.games.war.WarConfig
import com.gamesuite.games.war.WarGame
import com.gamesuite.games.war.WarVariant
import com.gamesuite.games.sixball.SixBallGame
import com.gamesuite.games.holdem.HIDDEN_CARD
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Wave 5 S4 audit: which networked engines really run a 3-4 seat table over a (fake) relay, with all humans and
 * with CPU seats filled in by the host. Each game is built once per device exactly like its screen does
 * (`init(ctx)` then `startMatch()` on every device, ONLINE mode, host = seat 0), joined through [FakeRelay].
 *
 * Scenarios per game and seat count N (3 and 4, within the game's online seat range):
 *  - HUMANS: N human devices. Host starts; every guest must end up with a state (host-authoritative sync).
 *  - CPU_TAIL: 2 human devices + (N-2) CPU seats at the end (what the lobby's "fill with computers" produces).
 *    The host drives CPU turns to quiescence; guests must NOT drive CPUs themselves and must still be in sync.
 *  - CPU_FIRST: a CPU in seat 0's place is not a real lobby shape, so instead the first mover is made a CPU by seating
 *    [cpu, human, human, ...] with the HOST DEVICE playing seat 0 as a CPU: this is the only generic way to make the
 *    engine reach a CPU turn before any human acts, proving the host drives CPU seats in networked mode and the guests
 *    receive the result. Its result is reported as "drives" only when CPU steps actually happened.
 *  - LEAVE: a guest vanishes after the start; no device may throw and the host keeps its state.
 *
 * The result table is printed (and recorded in docs/roadmap/WAVE-5-MULTIPLAYER-DESIGN.md, "S4 audit results").
 */
class NetworkedSeatAuditTest {

    private class Adapter<G : GameModule>(
        val id: String,
        val create: () -> G,
        val state: (G) -> Any?,
        /** True while a CPU seat is to move on this device (null = the engine has no such query). */
        val cpuToMove: ((G) -> Boolean)?,
        /** Performs one CPU move; only called while [cpuToMove] is true. */
        val cpuStep: ((G) -> Unit)?,
        /** Real-time engines: advance the simulation by n fixed ticks (host and guests). */
        val ticks: ((G, Int) -> Unit)? = null,
        /** Hidden-information games: true if this device can see any card another seat (not [mySeat]) should hide. */
        val leak: ((G, Int) -> Boolean)? = null,
    ) {
        @Suppress("UNCHECKED_CAST")
        fun g(m: GameModule) = m as G
    }

    private val adapters: List<Adapter<*>> = listOf(
        Adapter("uno", { UnoGame() }, { it.state.value },
            { g -> g.state.value?.let { s -> !s.matchOver && s.players[s.currentPlayerIndex].isBot } ?: false },
            { it.playBotTurn() },
            leak = { g, me -> g.state.value?.players?.withIndex()?.any { (i, p) -> i != me && p.hand.any { c -> c.instanceId != -1 } } ?: false }),
        Adapter("texas-holdem", { HoldemGame() }, { it.state.value }, { it.botToAct() }, { it.playBotStep() },
            leak = { g, me -> g.state.value?.seats?.withIndex()?.any { (i, st) -> i != me && !st.revealed && st.hole.any { c -> c != HIDDEN_CARD } } ?: false }),
        Adapter("president", { PresidentGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() },
            leak = { g, me -> g.state.value?.seats?.withIndex()?.any { (i, st) -> i != me && st.hand.any { c -> c >= 0 } } ?: false }),
        Adapter("sevens", { SevensGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() },
            leak = { g, me -> g.state.value?.seats?.withIndex()?.any { (i, st) -> i != me && st.hand.any { c -> c >= 0 } } ?: false }),
        Adapter("chinese-checkers", { ChineseCheckersGame() }, { it.state.value }, { it.botToMove() }, { it.playBotTurn() }),
        Adapter("pig-dice", { PigGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("ludo", { LudoGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("blackjack", { BlackjackGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("war", { WarGame().also { it.config = WarConfig(variant = WarVariant.CHOOSE3) } }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("carrom", { CarromGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("curling", { CurlingGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("darts", { DartsGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("bowling", { BowlingGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("mini-golf", { GolfGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("tank-battle", { TankGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("shooting-gallery", { GalleryGame() }, { it.state.value }, { it.botToMove() }, { it.playBotStep() }),
        Adapter("matching", { MatchingGame() }, { it.state.value }, { it.botToMove() }, { it.playBotAttempt() }),
        Adapter("dominoes", { DominoGame() }, { it.state.value },
            { g -> g.state.value?.let { s -> !s.handOver && s.players[s.currentPlayerIndex].isBot } ?: false },
            { it.playBotTurn() }),
        Adapter("six-ball-puzzle", { SixBallGame() }, { g -> g.race.value ?: g.board.value }, null, null),
        Adapter("toy-soccer", { ToySoccerGame() }, { it.state.value }, null, null, ticks = { g, n -> g.stepTicks(n) }),
        Adapter("slot-cars", { SlotCarsGame() }, { it.state.value }, null, null, ticks = { g, n -> g.stepTicks(n) }),
        Adapter("fishing", { FishingGame() }, { it.state.value }, null, null, ticks = { g, n -> g.stepTicks(n) }),
        Adapter("toy-tennis", { ToyTennisGame() }, { it.state.value }, null, null, ticks = { g, n -> g.stepTicks(n) }),
    )

    private enum class Scenario { HUMANS, CPU_TAIL, CPU_FIRST }

    private class Row(val game: String, val seats: Int, val scenario: Scenario, val verdict: String, val ok: Boolean)

    @Suppress("UNCHECKED_CAST")
    private fun run(a: Adapter<*>, seats: Int, scenario: Scenario): Row {
        // Deals and first movers are random: a CPU-first table gets several fresh tries before it counts as "never drove a CPU".
        var last = runOnce(a, seats, scenario)
        var tries = 1
        while (scenario == Scenario.CPU_FIRST && last.ok.not() && last.verdict.startsWith("NO CPU STEP") && tries < 25) {
            last = runOnce(a, seats, scenario)
            tries++
        }
        return last
    }

    @Suppress("UNCHECKED_CAST")
    private fun runOnce(a: Adapter<*>, seats: Int, scenario: Scenario): Row {
        val adapter = a as Adapter<GameModule>
        fun row(ok: Boolean, text: String) = Row(a.id, seats, scenario, text, ok)
        val relay = FakeRelay()
        val ids = (0 until seats).map { if (it == 0) "host" else "guest$it" }
        val isBot = BooleanArray(seats) { i ->
            when (scenario) {
                Scenario.HUMANS -> false
                Scenario.CPU_TAIL -> i >= 2
                Scenario.CPU_FIRST -> i != seats - 1
            }
        }
        val players = ids.mapIndexed { i, id -> PlayerInfo(id, if (isBot[i]) "Computer $i" else "Player $i", isBot = isBot[i]) }
        // CPU_FIRST: the host DEVICE sits in seat 0 as a CPU and the only human is the last seat.
        val humanDevices = ids.indices.filter { (scenario == Scenario.CPU_FIRST && it == 0) || !isBot[it] }
        val games = HashMap<Int, GameModule>()
        try {
            for (i in humanDevices) {
                val g = adapter.create()
                games[i] = g
                g.init(GameContext(PlayMode.ONLINE, players, i, relay.device(ids[i])))
            }
            for (i in humanDevices) games.getValue(i).startMatch()
            relay.pump()
            val host = games.getValue(0)
            if (adapter.state(host) == null) return row(false, "host has no state after start")

            var cpuSteps = 0
            if (adapter.cpuToMove != null && adapter.cpuStep != null) {
                var guard = 0
                while (adapter.cpuToMove.invoke(host) && guard++ < 400) {
                    // While a computer is to move the guests (synced by the last pump) see the same turn: a guest's
                    // own UI may ask its engine to drive it, and that must put nothing on the wire (only the host drives).
                    for (i in humanDevices.filter { it != 0 }) {
                        val gdev = games.getValue(i)
                        if (!adapter.cpuToMove.invoke(gdev)) continue
                        val device = relay.device(ids[i])
                        val before = device.sentCount
                        adapter.cpuStep.invoke(gdev)
                        if (device.sentCount != before) return row(false, "guest $i sent traffic to drive a computer seat")
                    }
                    adapter.cpuStep.invoke(host)
                    cpuSteps++
                    relay.pump()
                }
            }
            if (adapter.leak != null) {
                for (i in humanDevices.filter { it != 0 }) {
                    if (adapter.leak.invoke(games.getValue(i), i)) return row(false, "LEAK: device $i can see another seat's hidden cards")
                }
            }
            var advanced = false
            if (adapter.ticks != null) {
                val before = adapter.state(host).toString()
                repeat(8) {
                    for (i in humanDevices) adapter.ticks.invoke(games.getValue(i), 60)
                    relay.pump()
                }
                advanced = adapter.state(host).toString() != before
                if (!advanced) return row(false, "real-time simulation did not advance on the host")
                // Guests must have been fed the host's simulation, not left at their first frame.
                val hostNow = adapter.state(host).toString()
                if (humanDevices.any { it != 0 && adapter.state(games.getValue(it)).toString() == before }) {
                    return row(false, "a guest is still on its first frame after the host simulated (host now: ${hostNow.take(40)})")
                }
            }
            for (i in humanDevices.filter { it != 0 }) {
                if (adapter.state(games.getValue(i)) == null) return row(false, "guest $i never received a state")
            }
            // A guest vanishes: no device may throw, and the host keeps its state.
            val leaver = ids[humanDevices.last().takeIf { it != 0 } ?: 1]
            relay.drop(leaver)
            relay.pump()
            if (adapter.state(host) == null) return row(false, "host lost its state when $leaver left")

            val note = when {
                adapter.ticks != null -> "real-time: host advanced, guests followed"
                scenario == Scenario.CPU_FIRST && cpuSteps == 0 -> "NO CPU STEP (engine did not drive the CPU seat)"
                else -> "cpu steps=$cpuSteps"
            }
            val ok = !(scenario == Scenario.CPU_FIRST && adapter.cpuToMove != null && cpuSteps == 0)
            return row(ok, note)
        } catch (t: Throwable) {
            return row(false, "THREW ${t::class.simpleName}: ${t.message?.take(120)}")
        }
    }

    @Test
    fun `every networked game with three or more seats is audited`() {
        val unaudited = GameHubSpecs.networkedGames.filter { id ->
            val limits = LobbySeatLimits.forGame(id, HubModeKind.ONLINE)
            limits.maxPlayers >= 3 && adapters.none { it.id == id }
        }
        assertTrue("networked 3+ seat games with no audit adapter: $unaudited", unaudited.isEmpty())
    }

    @Test
    fun `audit 3 and 4 seat tables with humans and with CPU seats`() {
        val rows = ArrayList<Row>()
        for (a in adapters) {
            val limits = LobbySeatLimits.forGame(a.id, HubModeKind.ONLINE)
            for (seats in 3..4) {
                if (seats !in limits.minPlayers..limits.maxPlayers) continue
                for (sc in Scenario.values()) {
                    // CPU_FIRST needs a host, one human and one CPU at least; CPU_TAIL needs seats > 2.
                    rows += run(a, seats, sc)
                }
            }
        }
        println("S4 AUDIT")
        rows.forEach { println("  ${it.game} | ${it.seats} seats | ${it.scenario} | ${if (it.ok) "OK" else "FAIL"} | ${it.verdict}") }
        val failed = rows.filter { !it.ok }
        if (failed.isNotEmpty()) fail("audit failures:\n" + failed.joinToString("\n") { "  ${it.game} ${it.seats} ${it.scenario}: ${it.verdict}" })
    }
}
