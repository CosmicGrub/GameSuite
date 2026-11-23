package com.gamesuite.games.holdem

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review items 7 and 23: idle-turn action on a networked host, and no handling after the table closed. */
class HoldemIdleTurnTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun HdTable.advanceToGuestTurn() {
        var guard = 0
        while (host.state.value!!.toAct == 0 && guard++ < 10) {
            val s = host.state.value!!
            host.act(0, if (HoldemRules.options(s, 0)!!.canCheck) HoldemAction.CHECK else HoldemAction.CALL)
        }
        assertTrue(host.state.value!!.toAct != 0)
    }

    @Test fun idleHumanGuestIsFoldedOrCheckedByTheHost() {
        val t = HdTable(3)
        t.start()
        t.advanceToGuestTurn()
        val s = t.host.state.value!!
        val seat = s.toAct
        val action = t.host.idleTimeoutAction(s)
        assertNotNull(action)
        val canCheck = HoldemRules.options(s, seat)!!.canCheck
        assertEquals(if (canCheck) HoldemActionKind.CHECK else HoldemActionKind.FOLD, action!!.kind)
        assertTrue(t.host.applyIdleTimeout(s))
        val after = t.host.state.value!!
        assertTrue(after !== s)
        if (!canCheck) assertTrue(after.seats[seat].folded)
    }

    @Test fun staleTimerDoesNothingOnceTheTableMovedOn() {
        val t = HdTable(3)
        t.start()
        t.advanceToGuestTurn()
        val old = t.host.state.value!!
        val o = HoldemRules.options(old, old.toAct)!!
        t.games[old.toAct].act(old.toAct, if (o.canCheck) HoldemAction.CHECK else HoldemAction.CALL)
        assertFalse(t.host.applyIdleTimeout(old))
    }

    @Test fun hostsOwnTurnIsNeverTimedOut() {
        val t = HdTable(3)
        t.start()
        val s = t.host.state.value!!
        if (s.toAct == 0) assertNull(t.host.idleTimeoutAction(s))
    }

    @Test fun closedTableIgnoresActMessagesAndIdleTimeouts() {
        val t = HdTable(3)
        t.start()
        t.advanceToGuestTurn()
        val s = t.host.state.value!!
        t.host.leaveSession()
        assertNull(t.host.idleTimeoutAction(s))
        val msg = json.encodeToString(HoldemNetMessage.serializer(), HoldemNetMessage.Act(HoldemAction.FOLD)).toByteArray(Charsets.UTF_8)
        t.transports[0].inject(t.ids[s.toAct], msg)
        assertSame(s, t.host.state.value)
    }
}
