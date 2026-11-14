package com.gamesuite.games.sweettrail

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.random.Random

internal val stEngine = StandardSweetTrailEngine()

fun stPlayers(n: Int, bots: Set<Int> = emptySet()) =
    List(n) { SweetTrailPlayer("p${it + 1}", "Player ${it + 1}", it in bots) }

fun stRoster(n: Int, bots: Set<Int> = emptySet()) =
    List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}", isBot = it in bots) }

fun stBoard(
    n: Int = 2,
    rules: SweetTrailRules = SweetTrailRules(),
    seed: Long = 7L,
    wish: Set<Int> = emptySet(),
    starter: Int = 0,
    bots: Set<Int> = emptySet()
): SweetTrailState = stEngine.newBoard(stPlayers(n, bots), rules, seed, wish, starter)

/** Puts card [id] on top of the pile (the next draw), wherever it is now. */
fun SweetTrailState.withTop(id: Int): SweetTrailState =
    if (id in pile) copy(pile = pile - id + id) else copy(pile = pile + id, discard = discard - id)

/** The pieces on these squares (one per seat). */
fun SweetTrailState.at(vararg squares: Int): SweetTrailState = copy(positions = squares.toList())

fun single(c: TrailColour) = c.ordinal * 8
fun double(c: TrailColour) = 48 + c.ordinal * 2
fun landmark(l: TrailLandmark) = 60 + l.ordinal

fun stNewGame(
    players: List<PlayerInfo> = stRoster(2),
    random: Random = Random(1),
    mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
    start: Boolean = true,
    configure: SweetTrailGame.() -> Unit = {}
): SweetTrailGame {
    val g = SweetTrailGame(random)
    g.configure()
    g.init(GameContext(mode, players, localPlayerIndex = 0, transport = LocalPassAndPlayTransport()))
    if (start) g.startMatch()
    return g
}

/** Plays a board to the end with computer seats only; returns the steps it took (fails if the table ever stalls). */
fun SweetTrailGame.playBotsToTheEnd(limit: Int = 50_000): Int {
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

/**
 * Plays [start] to the end headless, straight through the engine and the bot rule (no Compose state, so it is fast
 * enough for tens of thousands of boards). [level] gives each seat's computer level; [onStep] sees every transition.
 */
fun stPlayOut(
    start: SweetTrailState,
    level: (Int) -> CpuDifficulty = { CpuDifficulty.MEDIUM },
    limit: Int = 5_000,
    onStep: (SweetTrailState, SweetTrailState) -> Unit = { _, _ -> }
): SweetTrailState {
    var s = start
    var steps = 0
    while (!s.over) {
        val before = s
        s = if (s.pending) stEngine.decide(s, SweetTrailBot.decide(s, level(s.currentPlayerIndex))) else stEngine.draw(s)
        check(s !== before) { "the engine changed nothing: the table stalled at step $steps" }
        onStep(before, s)
        steps++
        check(steps < limit) { "no winner after $limit steps" }
    }
    return s
}

/** A Random that counts every call: the deck stream must be asked for exactly one board seed per board. */
class CountingRandom(seed: Int) : Random() {
    private val inner = Random(seed)
    var calls = 0
    override fun nextBits(bitCount: Int): Int {
        calls++
        return inner.nextBits(bitCount)
    }
}
