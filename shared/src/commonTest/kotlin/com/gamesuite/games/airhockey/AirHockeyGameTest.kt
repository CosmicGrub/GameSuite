package com.gamesuite.games.airhockey

import androidx.compose.ui.geometry.Offset
import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Migrated from app/src/test/java/com/gamesuite/games/airhockey/AirHockeyGameTest.kt
 * (docs/ENGINE_DECISION.md Action Item 3) -- the regression-safety half of
 * the "harder pilot" claim, run against both the android and desktop
 * targets from this one shared source file, same as TicTacToeGameTest.kt.
 *
 * The only change from the original: org.junit.Test/Assert.assertTrue
 * (JVM-only) became kotlin.test.Test/assertTrue (a real Kotlin Multiplatform
 * artifact) -- and kotlin.test.assertTrue takes its message LAST
 * (actual, message), the reverse of JUnit's (message, actual), the same
 * parameter-order swap already called out when TicTacToeGameTest.kt made
 * this same JUnit4 -> kotlin.test crossing.
 *
 * Covers the two real-physics correctness fixes made to [AirHockeyGame.tick] / its private
 * `resolvePaddleCollision`:
 *
 *  1) Tunneling: a paddle that crosses clear from one side of the ball to the other within a
 *     single frame (a fast flick, or a frame-rate hitch) used to pass straight through with zero
 *     bounce, because the old check only ever tested the paddle's post-move position against the
 *     ball, never the segment it swept across getting there. [flickAcrossBall_stillBounces]
 *     reproduces exactly that shape of frame — the paddle jumps from well clear of the ball on
 *     one side to well clear on the other, in a single movePlayerPaddle + tick() pair — and
 *     checks the ball picked up nonzero velocity, i.e. a collision was found despite neither the
 *     paddle's start nor end position alone ever overlapping the ball's hit circle.
 *
 *  2) Puck coasting: every wall bounce was fully elastic and every paddle hit re-floored speed,
 *     so nothing in the loop ever removed energy from a rally. [ballSlowsDownInFreeFlight] drives
 *     many frames of pure free flight (ball moving parallel to both goal lines, dead center in y
 *     so it never nears a paddle or a goal mouth — only elastic side-wall bounces happen) and
 *     checks speed has measurably decreased, where before this fix it would have stayed exactly
 *     constant forever.
 *
 * Both tests use [PlayMode.SINGLE_DEVICE_PASS_AND_PLAY] so the top paddle is fully static (no
 * CPU AI movement to account for) and drive the game entirely through its public surface
 * (state/movePlayerPaddle/tick) — the one exception being that AirHockeyGame.state is a public
 * `MutableState`, so tests set up exact ball/paddle positions directly through it (same
 * mutability the UI layer itself doesn't use, but a real public property nonetheless) rather than
 * reaching into AirHockeyGame's actually-private fields (lastPlayerPaddle, resolvePaddleCollision).
 */
class AirHockeyGameTest {

    private fun newGame(): AirHockeyGame {
        val game = AirHockeyGame()
        val players = listOf(
            PlayerInfo(playerId = "p1", displayName = "Player 1", isBot = false),
            PlayerInfo(playerId = "p2", displayName = "Player 2", isBot = false)
        )
        game.init(
            GameContext(
                activeMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY,
                players = players,
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        return game
    }

    @Test
    fun flickAcrossBall_stillBounces() {
        val game = newGame()
        game.startMatch()

        // Park the ball somewhere harmless and bake a known "last frame" paddle position via one
        // tiny, uneventful tick (dt small enough that nothing moves or collides) -- this is what
        // seeds the private lastPlayerPaddle the swept check reads as its segment start.
        game.state.value = game.state.value.copy(
            ballPos = Offset(0.05f, 0.5f),
            ballVel = AirHockeyGame.Vec(0f, 0f),
            playerPaddle = Offset(0.15f, 0.8f),
            cpuPaddle = Offset(0.5f, 0.15f)
        )
        game.tick(0.001f)

        // Now place the ball directly in the path the paddle is about to sweep across, and jump
        // the paddle clear to the other side in a single frame. Distance from the ball to either
        // paddle ENDPOINT (start 0.15,0.8 or end 0.85,0.8) is ~0.35 -- far outside the ~0.105 hit
        // radius -- so only a check of the swept segment between them, not either point alone,
        // can find this collision.
        game.state.value = game.state.value.copy(ballPos = Offset(0.5f, 0.79f), ballVel = AirHockeyGame.Vec(0f, 0f))
        game.movePlayerPaddle(0.85f, 0.8f)
        game.tick(0.016f)

        val ballVel = game.state.value.ballVel
        assertTrue(
            ballVel.length() > 1.0f,
            "expected the swept-path tunneling fix to catch the flick and bounce the ball, " +
                "but ball velocity is still $ballVel"
        )
    }

    @Test
    fun ballSlowsDownInFreeFlight() {
        val game = newGame()
        game.startMatch()

        // Purely horizontal flight at MAX_SPEED, dead center in y -- ~0.35 away from both
        // paddles (well outside collision range) and never near either goal mouth, so only
        // elastic side-wall bounces happen for the whole run; no paddle hit ever re-floors speed.
        game.state.value = game.state.value.copy(
            ballPos = Offset(0.5f, 0.5f),
            ballVel = AirHockeyGame.Vec(AirHockeyGame.MAX_SPEED, 0f),
            playerPaddle = Offset(0.5f, 0.85f),
            cpuPaddle = Offset(0.5f, 0.15f)
        )
        val initialSpeed = game.state.value.ballVel.length()

        repeat(120) { game.tick(0.016f) } // ~2 seconds of free flight

        val finalSpeed = game.state.value.ballVel.length()
        assertTrue(
            finalSpeed < initialSpeed * 0.95f,
            "expected free-flight drag to bleed off speed over ~2s of play (started at " +
                "$initialSpeed), but final speed was $finalSpeed -- rallies should decay, not " +
                "coast forever at MAX_SPEED"
        )
    }
}
