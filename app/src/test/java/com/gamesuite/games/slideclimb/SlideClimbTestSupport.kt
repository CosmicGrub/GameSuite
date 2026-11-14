package com.gamesuite.games.slideclimb

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.random.Random

/** A die that plays back a fixed list of faces (then repeats it): dice must only ever come from the injected Random. */
class SlideClimbScriptedRandom(private vararg val faces: Int) : Random() {
    var calls = 0
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextInt(from: Int, until: Int): Int {
        require(from == 1 && until == 7) { "a die must ask for nextInt(1, 7)" }
        return faces[calls++ % faces.size]
    }
}

fun scHumans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }

fun scBots(n: Int) = List(n) { PlayerInfo("bot${it + 1}", "CPU ${it + 1}", isBot = true) }

fun scNewGame(
    players: List<PlayerInfo> = scHumans(2),
    random: Random = Random(1),
    mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
    start: Boolean = true,
    configure: SlideClimbGame.() -> Unit = {}
): SlideClimbGame {
    val g = SlideClimbGame(random)
    g.configure()
    g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
    if (start) g.startMatch()
    return g
}

/** Plays a board to the end with computer seats only; returns the number of steps it took (fails if it never ends). */
fun SlideClimbGame.playBotsToTheEnd(limit: Int = 50_000): Int {
    var steps = 0
    while (state.value?.over != true) {
        val before = state.value
        check(botToMove()) { "a computer must be on turn" }
        playBotStep()
        check(state.value !== before) { "a computer step changed nothing: the table stalled" }
        steps++
        check(steps < limit) { "no winner after $limit steps" }
    }
    return steps
}
