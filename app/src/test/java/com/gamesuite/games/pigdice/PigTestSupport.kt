package com.gamesuite.games.pigdice

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.random.Random

/** A die that plays back a fixed list of faces (then repeats it): dice must only ever come from the injected Random. */
class PigScriptedRandom(private vararg val faces: Int) : Random() {
    var calls = 0
    override fun nextBits(bitCount: Int): Int = 0
    override fun nextInt(from: Int, until: Int): Int {
        require(from == 1 && until == 7) { "a die must ask for nextInt(1, 7)" }
        return faces[calls++ % faces.size]
    }
}

fun pigHumans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }

fun pigNewGame(
    players: List<PlayerInfo> = pigHumans(2), random: Random = Random(1),
    mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, start: Boolean = true,
    variant: PigVariant = PigVariant.CLASSIC, target: Int = 100
): PigGame {
    val g = PigGame(random)
    g.variant = variant; g.target = target
    g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
    if (start) g.startMatch()
    return g
}
