package com.gamesuite.games.ludo

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.chinesecheckers.ClHub
import com.gamesuite.transport.LocalPassAndPlayTransport
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Review findings 4 and 9: a host state naming an absurd last-move range, or an inconsistent one, is refused whole. */
class LudoRemoteValidationTest {
    private val json = Json

    private class Rig(val host: LudoGame, val guest: LudoGame, val hostSent: () -> List<LudoNetMessage.StateSync>, val inject: (LudoNetMessage) -> Unit)

    private fun rig(): Rig {
        val players = listOf(PlayerInfo("h", "H"), PlayerInfo("g", "G"))
        val hub = ClHub()
        val ht = hub.endpoint("h"); val gt = hub.endpoint("g")
        val host = LudoGame(ScriptedRandom(6, 3, 3, 3)); val guest = LudoGame(Random(2))
        host.init(GameContext(PlayMode.LOCAL_AD_HOC, players, 0, ht))
        guest.init(GameContext(PlayMode.LOCAL_AD_HOC, players, 1, gt))
        host.startMatch()
        host.roll()
        host.moveToken(host.state.value!!.legalTokens().first())
        val sent = {
            ht.sent.map { json.decodeFromString(LudoNetMessage.serializer(), String(it.payload)) }
                .filterIsInstance<LudoNetMessage.StateSync>()
        }
        return Rig(host, guest, sent) { m ->
            gt.inject("h", json.encodeToString(LudoNetMessage.serializer(), m).toByteArray())
        }
    }

    private fun LudoState.legalTokens() = LudoRules.legalTokens(tokens.toIntArray(), currentPlayerIndex, dice, needSix, exactFinish)

    private fun assertRejected(r: Rig, version: Int, bad: LudoNetState) {
        val before = r.guest.state.value
        r.inject(LudoNetMessage.StateSync(version, bad))
        assertEquals(before, r.guest.state.value)
    }

    @Test fun guestRejectsAnAbsurdEventToInsteadOfHangingTheScreen() {
        val r = rig()
        val last = r.hostSent().last().state
        assertTrue(last.eventTo >= 0)
        assertRejected(r, 900, last.copy(eventTo = 2_000_000_000, moveSerial = last.moveSerial + 1))
    }

    @Test fun guestRejectsOutOfRangeAndBackwardsEventRanges() {
        val r = rig()
        val last = r.hostSent().last().state
        assertRejected(r, 900, last.copy(eventFrom = -7))
        assertRejected(r, 901, last.copy(eventFrom = LudoBoard.FINISHED + 1, eventTo = LudoBoard.FINISHED + 1))
        assertRejected(r, 902, last.copy(eventFrom = 10, eventTo = 3))
    }

    @Test fun guestStillAcceptsTheHostsGenuineState() {
        val r = rig()
        val last = r.hostSent().last()
        r.inject(last.copy(version = 900))
        assertEquals(last.state.moveSerial, r.guest.state.value!!.moveSerial)
    }

    @Test fun guestRejectsNegativeOrForeignSessionWinsAndWinnerMismatch() {
        val r = rig()
        val last = r.hostSent().last().state
        assertRejected(r, 900, last.copy(sessionWins = mapOf("h" to -1)))
        assertRejected(r, 901, last.copy(sessionWins = mapOf("nobody" to 1)))
        assertRejected(r, 902, last.copy(boardOver = true, winnerPlayerId = null))
        assertRejected(r, 903, last.copy(boardOver = false, winnerPlayerId = "h"))
    }

    @Test fun guestRejectsAwaitingAMoveWithNoLegalToken() {
        val r = rig()
        val last = r.hostSent().last().state
        // every token in the yard and a 3 showing: nothing can move
        assertRejected(r, 900, last.copy(tokens = List(8) { -1 }, dice = 3, awaitingMove = true, needSix = true))
    }

    @Test fun snapshotWithAnAbsurdEventToIsRefused() {
        val g = LudoGame(ScriptedRandom(6, 3))
        g.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, listOf(PlayerInfo("a", "A"), PlayerInfo("b", "B")), 0, LocalPassAndPlayTransport()))
        g.startMatch(); g.roll(); g.moveToken(g.state.value!!.legalTokens().first())
        val snap = g.snapshot()!!
        val fresh = { LudoGame(Random(1)).also { it.init(GameContext(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY, listOf(PlayerInfo("a", "A"), PlayerInfo("b", "B")), 0, LocalPassAndPlayTransport())) } }
        assertTrue(fresh().restore(snap))
        val bad = if (Regex("\"eventTo\":-?\\d+").containsMatchIn(snap)) snap.replace(Regex("\"eventTo\":-?\\d+"), "\"eventTo\":2000000000")
        else snap.replaceFirst("{", "{\"eventTo\":2000000000,")
        assertFalse(fresh().restore(bad))
    }
}
