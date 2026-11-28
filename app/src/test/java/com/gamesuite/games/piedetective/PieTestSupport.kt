package com.gamesuite.games.piedetective

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.MultiplayerTransport

/** A transport that does nothing: Pie Detective is same-screen only. */
class PieFakeTransport : MultiplayerTransport {
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

fun pieSeats(humans: Int, bots: Int): List<PieSeat> =
    List(humans) { PieSeat("p${it + 1}", "Player ${it + 1}", false) } + List(bots) { PieSeat("bot${it + 1}", "CPU ${it + 1}", true) }

fun pieContext(humans: Int, bots: Int): GameContext {
    val players = List(humans) { PlayerInfo("p${it + 1}", "Player ${it + 1}") } + List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }
    return GameContext(
        activeMode = if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT,
        players = players, localPlayerIndex = 0, transport = PieFakeTransport()
    )
}

/** A started engine at [seed] (the case-0 deal is [PieDealer.deal] with the same seed and band). */
fun pieEngine(
    humans: Int = 2, bots: Int = 0, band: PieBand = PieBand.MEDIUM, seed: Long = 7L,
    handicap: String = "none", autoFlip: Boolean = false, difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
): PieEngineImpl {
    val e = PieEngineImpl()
    e.band = band; e.handicapSpec = handicap; e.autoFlip = autoFlip; e.difficulty = difficulty
    e.setup(pieSeats(humans, bots))
    e.startBook(seed)
    return e
}

/** Plays the human steps of a turn by hand. */
class PieDriver(val e: PieEngine) {
    val s: PieState get() = e.state()!!

    fun ack() { if (s.phase == PiePhase.PASS) assertOk(e.passAcknowledged(s.currentSeat)) }

    private fun assertOk(r: PieResult) { check(r == PieResult.OK) { "expected OK, got $r" } }

    /** Choose, peek, show. Leaves the engine in FLIP (or SNEAK when every flip was automatic). Returns the clue. */
    fun askAndShow(trait: PieTrait, lucky: Boolean = false): PieClue {
        ack()
        val seat = s.currentSeat
        assertOk(e.chooseTrait(seat, trait, lucky))
        assertOk(e.peekHeld(seat))
        assertOk(e.showEveryone(seat))
        return s.clues.last()
    }

    /** Flips every animal that does not match the last clue (by hand). */
    fun flipAll() {
        val seat = s.currentSeat
        val clue = s.clues.last()
        for (v in s.lineup.toList()) {
            val a = PieCast.byId(v.id)!!
            if (!v.flipped && a.has(clue.trait) != clue.yes) assertOk(e.flip(seat, v.id))
        }
    }

    /** A full turn that asks [trait]: ask, flip, and stop in SNEAK. */
    fun turnAsk(trait: PieTrait, lucky: Boolean = false): PieClue {
        val c = askAndShow(trait, lucky)
        if (s.phase == PiePhase.FLIP) flipAll()
        return c
    }

    fun endSneak() { assertOk(e.sneakDone()) }

    fun wrongCatch(thief: String) {
        ack()
        val seat = s.currentSeat
        assertOk(e.startCatch(seat))
        val victim = s.lineup.first { !it.flipped && it.id != thief }.id
        assertOk(e.catchSuspect(seat, victim))
    }

    fun rightCatch(thief: String) {
        ack()
        val seat = s.currentSeat
        assertOk(e.startCatch(seat))
        assertOk(e.catchSuspect(seat, thief))
    }
}

/** Finds a book seed whose case-0 Sneak deck starts with [dash] (true: a dash card). */
fun pieSeedWithFirstCard(band: PieBand, dash: Boolean): Long {
    var seed = 1L
    while (PieDealer.deck(seed, 0, band)[0] != dash) seed++
    return seed
}

/** Reads resource and source files of the app module from disk (tests run with the module or the repo root as cwd). */
object PieTestFiles {
    fun file(relative: String): java.io.File {
        val here = java.io.File("src/main/$relative")
        if (here.exists()) return here
        return java.io.File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    fun read(relative: String): String = file(relative).readText()

    /** name to raw value of every `<string>` in an Android strings file. */
    fun strings(xml: String): Map<String, String> =
        Regex("<string name=\"([^\"]+)\"[^>]*>([^<]*)</string>").findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }

    fun dirStrings(dir: String): Map<String, String> {
        val all = linkedMapOf<String, String>()
        file(dir).listFiles { f -> f.isFile && f.name.endsWith(".xml") }!!.forEach { all.putAll(strings(it.readText())) }
        return all
    }
}
