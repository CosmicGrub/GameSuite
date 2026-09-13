package com.gamesuite

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.testing.runChessBotMatch
import com.gamesuite.testing.runTicTacToeBotMatch
import com.gamesuite.transport.LanMultiplayerTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real cross-device LAN multiplayer verification, Android side -- the counterpart to
 * shared/src/desktopMain/kotlin/LanBotMatchCli.kt. Runs entirely headless (no Compose UI,
 * no MainActivity involved at all) via `adb shell am instrument`, fully USB-driven -- see
 * com.gamesuite.testing.LanBotMatchRunner's own KDoc for the shared driving logic both this
 * and the PC side call identically, and LanBotMatchCli.kt's own KDoc for the general design
 * (each side's own bot moves only its own local seat; the host is the sole source of truth).
 *
 * Configured entirely via instrumentation runner arguments (`-e key value`), read here through
 * `InstrumentationRegistry.getArguments()`, since a real cross-device test run needs to launch
 * this same test class against many different (role, game, difficulty, host-address) matrix
 * cells without recompiling anything between them:
 *   - role: "host" or "guest" (required)
 *   - game: "tictactoe" or "chess" (required)
 *   - localDifficulty: EASY|MEDIUM|HARD (default MEDIUM)
 *   - remoteDifficulty: EASY|MEDIUM|HARD (default MEDIUM)
 *   - hostAddress / hostPort: required when role=guest -- the PC (or other device)'s real LAN
 *     IP and the port its own LanBotMatchCli printed as HOSTING_ON_PORT=...
 *
 * All progress and the final result print through both Log.i (real-time `adb logcat`
 * visibility while the match is in progress) and System.out (captured directly in the
 * instrumentation run's own stdout/INSTRUMENTATION_STATUS stream) so an orchestrating script
 * can verify a real completed game without needing a separate logcat scrape.
 */
class LanBotMatchInstrumentedTest {

    private fun logAndPrint(tag: String, message: String) {
        Log.i(tag, message)
        println("[$tag] $message")
    }

    @Test
    fun playOneNetworkedBotMatch() {
        val args = InstrumentationRegistry.getArguments()
        val role = args.getString("role") ?: error("-e role host|guest is required")
        val game = args.getString("game") ?: error("-e game tictactoe|chess is required")
        val localDifficulty = CpuDifficulty.valueOf(args.getString("localDifficulty") ?: "MEDIUM")
        val remoteDifficulty = CpuDifficulty.valueOf(args.getString("remoteDifficulty") ?: "MEDIUM")

        val tag = "LanBotMatch"
        val transport = LanMultiplayerTransport()
        val localPlayerIndex: Int
        val hostDifficulty: CpuDifficulty
        val guestDifficulty: CpuDifficulty

        when (role) {
            "host" -> {
                localPlayerIndex = 0
                hostDifficulty = localDifficulty
                guestDifficulty = remoteDifficulty
                val hostedLatch = CountDownLatch(1)
                var port = -1
                transport.onHosted { p -> port = p; hostedLatch.countDown() }
                transport.hostGame("host-p", "Host")
                assertTrue("failed to open a hosting port", hostedLatch.await(10, TimeUnit.SECONDS))
                logAndPrint(tag, "HOSTING_ON_PORT=$port")
                val joinedLatch = CountDownLatch(1)
                transport.onPlayerJoined { joinedLatch.countDown() }
                assertTrue("no guest ever joined", joinedLatch.await(60, TimeUnit.SECONDS))
                logAndPrint(tag, "GUEST_JOINED")
            }
            "guest" -> {
                localPlayerIndex = 1
                hostDifficulty = remoteDifficulty
                guestDifficulty = localDifficulty
                val hostAddress = args.getString("hostAddress") ?: error("-e hostAddress <ip> is required for role=guest")
                val hostPort = args.getString("hostPort")?.toIntOrNull() ?: error("-e hostPort <port> is required for role=guest")
                val joinedLatch = CountDownLatch(1)
                transport.onJoinedLobby { joinedLatch.countDown() }
                transport.joinHost(hostAddress, hostPort, "guest-p", "Guest")
                assertTrue(
                    "failed to join host at $hostAddress:$hostPort -- ${transport.connectionError.value}",
                    joinedLatch.await(15, TimeUnit.SECONDS)
                )
                logAndPrint(tag, "JOINED_HOST")
            }
            else -> error("role must be host or guest, got '$role'")
        }

        val result = when (game) {
            "tictactoe" -> runTicTacToeBotMatch(transport, localPlayerIndex, hostDifficulty, guestDifficulty) { logAndPrint(tag, it) }
            "chess" -> runChessBotMatch(transport, localPlayerIndex, hostDifficulty, guestDifficulty) { logAndPrint(tag, it) }
            else -> error("game must be tictactoe or chess, got '$game'")
        }

        logAndPrint(
            tag,
            "RESULT=completed=${result.completed} plies=${result.plies} outcome=${result.resultDescription} " +
                "scoreP1=${result.scoreP1} scoreP2=${result.scoreP2} timedOut=${result.timedOut} hitPlyCap=${result.hitPlyCap}"
        )
        transport.disconnect()

        // hitPlyCap (every move along the way succeeded, the game just didn't naturally conclude
        // -- see BotMatchResult's own KDoc, e.g. EASY-vs-EASY Chess's own well-documented
        // tendency to wander without a result) counts as success alongside a real completed
        // result; only a genuine stall (timedOut) is a failure.
        assertTrue(
            "match did not complete (timedOut=${result.timedOut})",
            (result.completed || result.hitPlyCap) && !result.timedOut
        )
    }
}
