package com.gamesuite.games.trailtogether

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.MultiplayerTransport

/** A transport that does nothing: Trail Together is same-screen only. */
class TrailFakeTransport : MultiplayerTransport {
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {}
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) {}
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) {}
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) {}
    override fun onReconnected(listener: () -> Unit) {}
}

fun trailSeats(humans: Int, bots: Int): List<TrailSeat> =
    List(humans) { TrailSeat("p${it + 1}", "Player ${it + 1}", false) } + List(bots) { TrailSeat("bot${it + 1}", "CPU ${it + 1}", true) }

fun trailContext(humans: Int, bots: Int, mode: PlayMode = if (humans > 1) PlayMode.SINGLE_DEVICE_PASS_AND_PLAY else PlayMode.SINGLE_PLAYER_VS_BOT): GameContext {
    val players = List(humans) { PlayerInfo("p${it + 1}", "Player ${it + 1}") } + List(bots) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }
    return GameContext(activeMode = mode, players = players, localPlayerIndex = 0, transport = TrailFakeTransport())
}

/** A set-up engine, not yet dealt. */
fun trailEngine(
    humans: Int = 2, bots: Int = 0, band: TrailBand = TrailBand.LITTLE, handicap: String = "none", level: CpuDifficulty = CpuDifficulty.HARD
): RealTrailEngine {
    val e = RealTrailEngine()
    e.band = band
    e.handicapSpec = handicap
    e.difficulty = level
    e.setup(trailSeats(humans, bots))
    return e
}

fun trailStarted(seed: Long, humans: Int = 2, bots: Int = 0, band: TrailBand = TrailBand.LITTLE, handicap: String = "none", level: CpuDifficulty = CpuDifficulty.HARD): RealTrailEngine {
    val e = trailEngine(humans, bots, band, handicap, level)
    e.start(seed)
    return e
}

/**
 * Builds a valid, fully controlled [TrailSave]: the whole deck minus [hands] and [pileFront] sits in the discard (so every
 * card is accounted for), the fog cards left over go to the end of the pile. Used to put a table exactly where a rule test
 * needs it, through the engine's own strict [RealTrailEngine.restore].
 */
fun craftSave(
    seats: List<TrailSeat>, band: TrailBand = TrailBand.LITTLE, trail: List<TrailColour>, pals: List<Int>,
    hands: List<List<String>>, pileFront: List<String> = emptyList(), fog: Int = 0, currentSeat: Int = 0,
    handicap: String = "none", tries: Int = 0, wins: Int = 0, difficulty: CpuDifficulty = CpuDifficulty.HARD
): TrailSave {
    val counts = TrailSaveCodec.deckCounts(band, TrailRules.palsFor(seats.size)).toMutableMap()
    fun take(code: String) {
        val left = (counts[code] ?: 0) - 1
        require(left >= 0) { "the deck has no more $code" }
        counts[code] = left
    }
    hands.flatten().forEach { take(it) }
    pileFront.forEach { take(it) }
    val fogLeft = counts["F"] ?: 0
    require(fogLeft >= fog) { "not enough fog cards for fog=$fog" }
    // real cards after the ones the test asked for, so a few turns can be played before the fog is reached
    val cycle = listOf("S:berry", "S:sun", "S:leaf", "S:sky", "T:berry", "T:sun", "T:leaf", "T:sky", "L")
    val filler = ArrayList<String>()
    var guard = 0
    while (filler.size < FILLER && guard++ < 200) {
        val code = cycle[guard % cycle.size]
        if ((counts[code] ?: 0) > 0) { take(code); filler += code }
    }
    val pile = pileFront + filler + List(fogLeft - fog) { "F" }
    val discard = ArrayList<String>()
    repeat(fog) { discard += "F" }
    for ((code, n) in counts) if (code != "F") repeat(n) { discard += code }
    return TrailSave(
        players = seats.map { TrailPlayerSave(it.playerId, it.isBot) }, band = band.id, handicap = handicap,
        difficulty = difficulty.name, seed = 7L, trail = trail.map { it.id }, pals = pals, hands = hands, pile = pile,
        discard = discard, fog = fog, currentSeat = currentSeat, plays = discard.size - fog, tries = tries, wins = wins, eventSerial = 3
    )
}

private const val FILLER = 12

/** An engine put on a crafted table. */
fun trailCrafted(
    humans: Int = 2, bots: Int = 0, band: TrailBand = TrailBand.LITTLE, trail: List<TrailColour>, pals: List<Int>,
    hands: List<List<String>>, pileFront: List<String> = emptyList(), fog: Int = 0, currentSeat: Int = 0, handicap: String = "none",
    level: CpuDifficulty = CpuDifficulty.HARD
): RealTrailEngine {
    val e = trailEngine(humans, bots, band, handicap, level)
    val seats = trailSeats(humans, bots)
    val ok = e.restore(craftSave(seats, band, trail, pals, hands, pileFront, fog, currentSeat, handicap, difficulty = level))
    check(ok) { "the crafted table was rejected" }
    return e
}

/** A lit-through Little trail of 16 stones cycling berry, sun, leaf, sky (index 0 berry). */
val LITTLE_FULL_TRAIL: List<TrailColour> = List(16) { listOf(TrailColour.BERRY, TrailColour.SUN, TrailColour.LEAF, TrailColour.SKY)[it % 4] }

/** The smallest Little trail (4 stones): berry, sun, leaf, berry. */
val LITTLE_OPENING: List<TrailColour> = listOf(TrailColour.BERRY, TrailColour.SUN, TrailColour.LEAF, TrailColour.BERRY)

/** Plays a whole try with helper pals only; returns the number of steps. Fails the test if it does not end. */
fun TrailEngine.playOut(maxSteps: Int = 600): Int {
    var steps = 0
    while (state()?.over == false) {
        check(steps++ < maxSteps) { "the try did not end in $maxSteps steps" }
        check(helperStep()) { "the helper could not play at step $steps" }
    }
    return steps
}

/** The state without the fields a restore deliberately does not bring back (the last action and event). */
fun TrailState.core(): TrailState = copy(action = null, lastEvent = null)
