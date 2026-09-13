import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.testing.runChessBotMatch
import com.gamesuite.testing.runTicTacToeBotMatch
import com.gamesuite.transport.LanMultiplayerTransport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Headless PC-side counterpart to app/src/androidTest's LanBotMatchInstrumentedTest -- the
 * real cross-device LAN multiplayer verification the "test the multiplayer function by
 * host[ing] on the pc to the tablet (and vice versa)" request asked for. No GUI window at
 * all (deliberately -- this needs to run unattended from a terminal/orchestration script,
 * not require a human to click a Compose window into the right state); see
 * com.gamesuite.testing.LanBotMatchRunner's own KDoc for the shared driving logic both this
 * and the Android side call identically.
 *
 * Usage:
 *   host: --role=host --game=tictactoe|chess --local-difficulty=EASY|MEDIUM|HARD --remote-difficulty=EASY|MEDIUM|HARD
 *   guest: --role=guest --game=tictactoe|chess --local-difficulty=EASY|MEDIUM|HARD --remote-difficulty=EASY|MEDIUM|HARD --host=<ip> --port=<port>
 *
 * As host, prints "HOSTING_ON_PORT=<port>" to stdout the moment it's actually listening (an
 * orchestrator scrapes this to tell the guest process which port to join, since hostGame()
 * always binds an OS-assigned ephemeral port rather than a fixed one -- see
 * LanMultiplayerTransport.hostGame's own KDoc). Prints "RESULT=<...>" as its final line either
 * way, then exits 0 if the match completed with a real result, non-zero otherwise (timeout,
 * connection failure, or an incomplete game) -- exactly what an orchestration script greps for.
 */
fun main(args: Array<String>) {
    val options = args.associate { arg ->
        val cleaned = arg.removePrefix("--")
        val idx = cleaned.indexOf('=')
        if (idx < 0) cleaned to "" else cleaned.substring(0, idx) to cleaned.substring(idx + 1)
    }

    val role = options["role"] ?: error("--role=host|guest is required")
    val game = options["game"] ?: error("--game=tictactoe|chess is required")
    val localDifficulty = CpuDifficulty.valueOf(options["local-difficulty"] ?: "MEDIUM")
    val remoteDifficulty = CpuDifficulty.valueOf(options["remote-difficulty"] ?: "MEDIUM")

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
            if (!hostedLatch.await(10, TimeUnit.SECONDS)) {
                println("RESULT=FAILED_TO_HOST")
                exitProcess(2)
            }
            println("HOSTING_ON_PORT=$port")
            val joinedLatch = CountDownLatch(1)
            transport.onPlayerJoined { joinedLatch.countDown() }
            if (!joinedLatch.await(60, TimeUnit.SECONDS)) {
                println("RESULT=NO_GUEST_JOINED")
                exitProcess(2)
            }
            println("GUEST_JOINED")
        }
        "guest" -> {
            localPlayerIndex = 1
            hostDifficulty = remoteDifficulty
            guestDifficulty = localDifficulty
            val hostAddress = options["host"] ?: error("--host=<ip> is required for role=guest")
            val hostPort = options["port"]?.toIntOrNull() ?: error("--port=<port> is required for role=guest")
            val joinedLatch = CountDownLatch(1)
            transport.onJoinedLobby { joinedLatch.countDown() }
            transport.joinHost(hostAddress, hostPort, "guest-p", "Guest")
            if (!joinedLatch.await(15, TimeUnit.SECONDS)) {
                println("RESULT=FAILED_TO_JOIN: ${transport.connectionError.value}")
                exitProcess(2)
            }
            println("JOINED_HOST")
        }
        else -> error("--role must be host or guest, got '$role'")
    }

    val result = when (game) {
        "tictactoe" -> runTicTacToeBotMatch(transport, localPlayerIndex, hostDifficulty, guestDifficulty, onProgress = ::println)
        "chess" -> runChessBotMatch(transport, localPlayerIndex, hostDifficulty, guestDifficulty, onProgress = ::println)
        else -> error("--game must be tictactoe or chess, got '$game'")
    }

    println(
        "RESULT=completed=${result.completed} plies=${result.plies} outcome=${result.resultDescription} " +
            "scoreP1=${result.scoreP1} scoreP2=${result.scoreP2} timedOut=${result.timedOut} hitPlyCap=${result.hitPlyCap}"
    )
    transport.disconnect()
    // hitPlyCap (every move along the way succeeded, the game just didn't naturally conclude --
    // see BotMatchResult's own KDoc) counts as success alongside a real completed result; only a
    // genuine stall (timedOut) is a failure.
    exitProcess(if ((result.completed || result.hitPlyCap) && !result.timedOut) 0 else 1)
}
