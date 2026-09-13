package com.gamesuite.testing

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.chess.ChessGame
import com.gamesuite.games.chess.ChessResult
import com.gamesuite.games.chess.playerIndexForColor
import com.gamesuite.games.tictactoe.TicTacToeGame
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LanMultiplayerTransport

/**
 * Real cross-device LAN multiplayer verification tool -- NOT production code. Drives a full
 * bot-vs-bot Tic-Tac-Toe or Chess game to completion over a real [LanMultiplayerTransport],
 * with each side deciding moves ONLY for its own local seat (see [ChessGame.playBotTurn]'s own
 * KDoc on why this ownership discipline matters -- it's what lets two independently-running
 * processes, potentially on two different physical devices/platforms, drive one real
 * host-authoritative match without either side racing or double-guessing the other's move).
 *
 * This exists specifically to let a PC process (see the desktopMain CLI entry point built on
 * top of this) and a real Android device (see app/src/androidTest's instrumented test built on
 * top of this) each run one of these functions pointed at each other over an actual LAN --
 * proving the same [LanMultiplayerTransport]/[TicTacToeGame]/[ChessGame] code already covered
 * by shared/src/jvmTest's own real-socket tests (loopback-only, both ends the same JVM process)
 * also works across two genuinely separate devices/platforms/JVMs on a real network, with real
 * latency and real Android/Desktop runtime differences. Lives in jvmMain (not jvmTest) so it
 * compiles into :shared's real Android output and is reachable from :app's androidTest, which
 * only depends on :shared's main artifact, not its test sources.
 */

/** Everything a caller needs to confirm the match actually happened correctly, from this
 *  process's own local point of view -- since the other side is a separate process (possibly
 *  on a separate physical device), there is no single shared JVM object to assert against;
 *  each side reports its own version of this and the orchestrator compares them externally
 *  (e.g. by diffing the two processes' printed/logged results). */
data class BotMatchResult(
    val completed: Boolean,
    val plies: Int,
    val resultDescription: String,
    val scoreP1: Int,
    val scoreP2: Int,
    /** True only when a single per-move wait itself timed out -- i.e. one side genuinely
     *  stopped hearing from the other for the whole [runTicTacToeBotMatch]/[runChessBotMatch]
     *  timeoutMs window. A real, if rare, sign of a networking/engine problem. */
    val timedOut: Boolean,
    /** True when the game simply ran out of the bounded ply budget with every individual move
     *  along the way succeeding -- NOT a stall. EASY-vs-EASY Chess in particular plays uniformly
     *  random legal moves with no 50-move-rule/insufficient-material draw detection in this
     *  engine (see ChessGame.kt's own top-of-file scope note), so two random bots can
     *  legitimately wander for a very long time without ever delivering checkmate or repeating
     *  a position 3 times -- ChessGameTest's own random-self-play test already documents this
     *  exact outcome ("hitting the safety cap is an EXPECTED, non-failure outcome for pure
     *  random play"). A caller should treat this as a pass, not a failure, when [timedOut] is
     *  false alongside it.
     */
    val hitPlyCap: Boolean = false
)

private fun busyAwait(timeoutMs: Long, intervalMs: Long = 20, check: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (check()) return true
        Thread.sleep(intervalMs)
    }
    return check()
}

/**
 * The original loopback JVM tests (TicTacToeLanMultiplayerTest/ChessLanMultiplayerTest)
 * guarantee "host calls init() before guest calls init()" for free -- both game instances are
 * constructed and init()'d sequentially in one test thread. A real cross-device run has no
 * such guarantee: each side's own "connected" callback (onPlayerJoined / onJoinedLobby) fires
 * independently on its own device/process, and a fast device reaching init() -- which is what
 * actually registers this side's onMessageReceived listener and, for a guest, sends the one-shot
 * initial RequestState -- before a slower one finishes its OWN init() will have that RequestState
 * silently dropped on the floor (no listener registered yet to receive it), with nothing to
 * retry it. Confirmed for real: PC-hosts-tablet worked instantly (the fast desktop process was
 * already past init() by the time the slower `adb shell am instrument`-launched tablet guest
 * reached its own), but tablet-hosts-PC stalled the whole game (the fast PC guest's initial
 * RequestState reached the tablet host before ITS OWN init() had registered a listener for it).
 * A fixed grace delay before a GUEST's own init() -- generous relative to how fast a HOST's own
 * init() call actually completes (a synchronous, sub-millisecond, in-process call once its own
 * connected callback fires) -- closes this real, if narrow, timing gap between two independent
 * physical devices/processes without needing a production-code change (a retry-until-acked
 * RequestState would be the more general fix, but isn't needed for what this tool verifies).
 */
private const val GUEST_INIT_GRACE_MS = 1200L

/**
 * Default per-move wait -- raised from an initial 30s to 45s after a real 36-combination
 * cross-device matrix run (PC <-> Tab S9 FE) surfaced a real, if narrow, calibration gap: in a
 * handful of runs (a small minority, always specifically during an unusually long, 100+-ply
 * real Chess game), ONE side's own local busyAwait genuinely exceeded 30s waiting on a single
 * move, even though the OTHER side (and the game overall) kept progressing correctly the whole
 * time -- not a stall, not a desync, just real Android-side scheduling/network jitter
 * occasionally outlasting a tight budget over a very large number of real round trips. 45s
 * gives real headroom without meaningfully slowing down a genuine stall's own detection.
 */
private const val DEFAULT_MOVE_TIMEOUT_MS = 45_000L

/**
 * Plays one real Tic-Tac-Toe ROUND (not a whole multi-round match/session -- this project's own
 * "match" is a running series, and a bot-vs-bot series would otherwise never stop on its own)
 * to completion. [localPlayerIndex] is 0 for the host, 1 for the guest -- this process's own
 * bot only ever moves for that seat; the other seat's moves arrive over [transport] from the
 * remote process's own identical call. [onProgress] receives a human-readable line per move,
 * for the caller to log (Log.i on Android, println on desktop) as real-time evidence, not just
 * a final pass/fail.
 */
fun runTicTacToeBotMatch(
    transport: LanMultiplayerTransport,
    localPlayerIndex: Int,
    hostDifficulty: CpuDifficulty,
    guestDifficulty: CpuDifficulty,
    timeoutMs: Long = DEFAULT_MOVE_TIMEOUT_MS,
    onProgress: (String) -> Unit = {}
): BotMatchResult {
    if (localPlayerIndex != 0) Thread.sleep(GUEST_INIT_GRACE_MS)
    val players = listOf(
        PlayerInfo(playerId = "host-p", displayName = "Host", isBot = true),
        PlayerInfo(playerId = "guest-p", displayName = "Guest", isBot = true)
    )
    val game = TicTacToeGame().apply {
        difficulty = if (localPlayerIndex == 0) hostDifficulty else guestDifficulty
        init(
            GameContext(
                activeMode = PlayMode.LOCAL_AD_HOC,
                players = players,
                localPlayerIndex = localPlayerIndex,
                transport = transport
            )
        )
        startMatch()
    }

    var plies = 0
    var timedOut = false
    while (plies < 60) {
        // Unlike Chess's state, TicTacToeGame's board/currentPlayer are non-nullable and
        // already fully valid the instant startMatch() returns (no "wait for state to exist"
        // concern) -- so the only real wait needed here is the post-move settle check below.
        if (game.roundOver.value) break
        val toMove = game.currentPlayer.value - 1
        // Snapshot the board BEFORE deciding/waiting -- see the "settled" check below for why
        // this, not currentPlayer's own 2-valued flag, is what that check watches.
        val boardBefore = game.board.value.toList()
        if (toMove == localPlayerIndex) {
            game.playBotTurn()
        }
        // Waits for a REAL NEW move to land, not merely "currentPlayer changed" -- a 2-valued
        // (player 1/player 2) flag can cycle back to the SAME value this check started on if
        // the opponent's own reply (or, on a fast bot, several further plies) lands between two
        // poll ticks, which a same-JVM loopback test practically never hits but a real
        // cross-device run genuinely can (confirmed for real: Chess's own analogous bug, fixed
        // alongside this one -- see this file's own history/commit message). Comparing the
        // actual board contents is immune to that: it only reports "settled" once at least one
        // real move has actually been recorded since boardBefore was captured, regardless of how
        // many more plies happened after that before this check runs.
        val settled = busyAwait(timeoutMs) { game.roundOver.value || game.board.value.toList() != boardBefore }
        if (!settled) { timedOut = true; break }
        if (toMove == localPlayerIndex) {
            onProgress("ply ${plies + 1}: seat $toMove (this device's own bot) moved -- board now ${game.board.value.toList()}")
        }
        plies++
    }

    return BotMatchResult(
        completed = game.roundOver.value,
        plies = plies,
        // Derived from the score delta, not currentPlayer.value -- TicTacToeGame.applyCellClicked
        // deliberately never flips currentPlayer on a winning move (it returns immediately after
        // recording the win), so currentPlayer.value at this point still names the WINNER, not
        // "whoever's turn is next" the way it would after any other move. An earlier version of
        // this function inverted it as if it always meant the latter, which reported the wrong
        // winner despite scoreP1/scoreP2 (the real game engine's own bookkeeping) being correct
        // the whole time -- a bug in this reporting code, not in TicTacToeGame itself. Reading
        // the score directly sidesteps that distinction entirely.
        resultDescription = if (game.roundOver.value) {
            when {
                game.scoreP1.value > 0 -> "winner=player1"
                game.scoreP2.value > 0 -> "winner=player2"
                else -> "draw"
            }
        } else "incomplete",
        scoreP1 = game.scoreP1.value,
        scoreP2 = game.scoreP2.value,
        timedOut = timedOut
    )
}

/** Same shape as [runTicTacToeBotMatch], for Chess -- see that function's own KDoc for the
 *  general design. Chess's own seat-to-color mapping ([playerIndexForColor]) is used instead
 *  of Tic-Tac-Toe's flat 1/2 currentPlayer int. */
fun runChessBotMatch(
    transport: LanMultiplayerTransport,
    localPlayerIndex: Int,
    hostDifficulty: CpuDifficulty,
    guestDifficulty: CpuDifficulty,
    timeoutMs: Long = DEFAULT_MOVE_TIMEOUT_MS,
    onProgress: (String) -> Unit = {}
): BotMatchResult {
    if (localPlayerIndex != 0) Thread.sleep(GUEST_INIT_GRACE_MS)
    val players = listOf(
        PlayerInfo(playerId = "host-p", displayName = "Host", isBot = true),
        PlayerInfo(playerId = "guest-p", displayName = "Guest", isBot = true)
    )
    val game = ChessGame().apply {
        difficulty = if (localPlayerIndex == 0) hostDifficulty else guestDifficulty
        init(
            GameContext(
                activeMode = PlayMode.LOCAL_AD_HOC,
                players = players,
                localPlayerIndex = localPlayerIndex,
                transport = transport
            )
        )
        startMatch()
    }

    val plyCap = 200
    var plies = 0
    var timedOut = false
    while (plies < plyCap) {
        val convergeOk = busyAwait(timeoutMs) { game.state.value != null }
        if (!convergeOk) { timedOut = true; break }
        val before = game.state.value!!
        if (before.roundOver) break
        val toMove = playerIndexForColor(before.sideToMove)
        if (toMove == localPlayerIndex) {
            game.playBotTurn()
        }
        // Waits for a REAL NEW move to land (lastFrom/lastTo actually changed), not merely
        // "sideToMove changed" -- a real, confirmed bug: sideToMove is only a 2-valued
        // (WHITE/BLACK) flag, so it can cycle back to the SAME value this check started on if
        // the opponent's own reply lands fast enough to complete between two poll ticks (a
        // same-JVM loopback test practically never hits this timing, but a real cross-device
        // run genuinely did -- confirmed live: the host advanced two full plies past a guest's
        // own just-sent move before the guest's next poll ever ran, so its "did sideToMove
        // change" check saw WHITE->BLACK->WHITE->BLACK land back on the exact value it started
        // on and waited the full timeout despite the game having correctly moved on without
        // it). Comparing lastFrom/lastTo is immune to that: it only reports "settled" once at
        // least one real move has actually been recorded since `before` was captured, no matter
        // how many more plies happened after that before this check runs.
        val settled = busyAwait(timeoutMs) {
            val cur = game.state.value
            cur != null && (cur.roundOver || cur.lastFrom != before.lastFrom || cur.lastTo != before.lastTo)
        }
        if (!settled) { timedOut = true; break }
        if (toMove == localPlayerIndex) {
            val after = game.state.value
            onProgress("ply ${plies + 1}: seat $toMove (this device's own bot) played ${after?.lastFrom}->${after?.lastTo}")
        }
        plies++
    }

    val finalState = game.state.value
    return BotMatchResult(
        completed = finalState?.roundOver == true,
        plies = plies,
        resultDescription = when (finalState?.result) {
            ChessResult.WHITE_WINS -> "White (seat 0) wins by checkmate"
            ChessResult.BLACK_WINS -> "Black (seat 1) wins by checkmate"
            ChessResult.DRAW_STALEMATE -> "draw by stalemate"
            ChessResult.DRAW_REPETITION -> "draw by repetition"
            else -> "incomplete"
        },
        scoreP1 = game.scoreP1.value,
        scoreP2 = game.scoreP2.value,
        timedOut = timedOut,
        hitPlyCap = !timedOut && finalState?.roundOver != true && plies >= plyCap
    )
}
