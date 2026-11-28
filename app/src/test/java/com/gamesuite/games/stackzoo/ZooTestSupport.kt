package com.gamesuite.games.stackzoo

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.MultiplayerTransport
import java.io.File

class ZooFakeTransport : MultiplayerTransport {
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

fun zooSeats(humans: Int, bots: Int = 0): List<ZooSeat> =
    List(humans) { ZooSeat("p${it + 1}", "Player ${it + 1}", false) } + List(bots) { ZooSeat("bot${it + 1}", "CPU ${it + 1}", true) }

fun zooContext(humans: Int, bots: Int = 0): GameContext {
    val players = List(humans) { PlayerInfo("p${it + 1}", "Player ${it + 1}") } + List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }
    return GameContext(
        activeMode = if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
        players = players, localPlayerIndex = 0, transport = ZooFakeTransport()
    )
}

/** A started engine that has pressed "Ready?" (phase CHOOSING). */
fun zooEngine(
    mode: ZooMode = ZooMode.COOP_TOWER, humans: Int = 2, bots: Int = 0, seed: Long = 12345L,
    goal: ZooGoal = ZooGoal.LITTLE, length: ZooLength = ZooLength.QUICK, assist: String = "none", relaxed: Boolean = false,
    difficulty: CpuDifficulty = CpuDifficulty.MEDIUM, oneSwitch: Boolean = false
): ZooEngine {
    val e = ZooEngine()
    e.setup(ZooConfig(mode = mode, goal = goal, length = length, assist = assist, relaxed = relaxed, oneSwitch = oneSwitch, difficulty = difficulty, seed = seed), zooSeats(humans, bots))
    e.start()
    e.begin()
    return e
}

/** Advances until the phase is [target] (or OVER), at most [max] steps. Returns the steps used. */
fun ZooEngine.runUntil(target: ZooPhase, max: Int = 4000): Int {
    var used = 0
    while (state().phase != target && state().phase != ZooPhase.OVER && used < max) { advance(10); used += 10 }
    return used
}

/** One full turn: pick [pieceIndex] of the offer, let go at ([xQ], [angleQ]), play on to the next CHOOSING (or OVER). */
fun ZooEngine.playTurn(xQ: Int, angleQ: Int = 0, pieceIndex: Int = 0) {
    val offer = state().offer
    check(choose(offer[pieceIndex])) { "choose failed" }
    check(release(xQ, angleQ) == ZooReleaseResult.PLACED) { "release failed" }
    runUntil(ZooPhase.CHOOSING)
}

const val ZOO_OFF_EDGE_Q = 4096 // x = 4.0 u: beyond the 3 u half-width of the log, so the animal falls

/** Plays turns with the helper until the game is over; returns the number of turns. */
fun ZooEngine.playWithHelpers(difficulty: CpuDifficulty, maxTurns: Int = 80): Int {
    var turns = 0
    while (state().phase != ZooPhase.OVER && turns < maxTurns) {
        if (state().phase == ZooPhase.READY) { begin(); continue }
        val save = toSave() ?: break
        val move = ZooHelper.plan(save, seatList, difficulty) ?: break
        check(choose(move.pieceId)) { "helper chose an animal that is not on offer" }
        check(release(move.xQ, move.angleQ) == ZooReleaseResult.PLACED)
        runUntil(ZooPhase.CHOOSING, 6000)
        // a TURN_RESULT that never ends the turn would be a bug: runUntil stops on CHOOSING or OVER only
        turns++
    }
    return turns
}

object ZooTestFiles {
    fun file(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    fun read(relative: String): String = file(relative).readText()

    fun strings(xml: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>([^<]*)</string>").findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    fun dirStrings(dir: String): Map<String, String> {
        val all = linkedMapOf<String, String>()
        file(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { all.putAll(strings(it.readText())) }
        return all
    }
}
