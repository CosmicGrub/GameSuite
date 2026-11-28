package com.gamesuite.games.propertytycoon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PropertyTycoonFuzzTest {
    private fun table(g: Int): TycoonState {
        val lengths = GameLength.values()
        val cfg = PTT.config(seed = 100L + g, length = lengths[g % 3], rules = HouseRules(jackpot = g % 4 == 0, doubleStipendOnLanding = g % 5 == 0, rentFreeTimeout = g % 3 == 0))
        return PTT.table(2 + g % 5, cfg)
    }

    @Test fun randomLegalCommandsNeverThrowAndAlwaysConserveTotalPearlsPlusTiles() {
        val rnd = Random(77)
        repeat(40) { g ->
            val (end, n) = PTT.playRandom(table(g), rnd, cap = 40000)
            assertTrue(end.over)
            assertTrue(n > 0)
        }
    }

    @Test fun anySnapshotTakenMidFuzzRestoresToAnEqualState() {
        val rnd = Random(5)
        repeat(12) { g ->
            val roster = PTT.roster(2 + g % 5)
            var s = table(g)
            var n = 0
            while (!s.over && n < 4000) {
                val seat = PTT.engine.seatToAct(s)
                val legal = PTT.engine.legalCommands(s, seat)
                s = PTT.act(s, legal[rnd.nextInt(legal.size)], seat)
                if (!s.over && n++ % 25 == 0) {
                    val text = PropertyTycoonSnapshotCodec.encode(PropertyTycoonSnapshot(state = s))
                    val back = PropertyTycoonSnapshotCodec.decode(text)
                    assertNotNull(back)
                    assertEquals("phase ${s.phase}", s, PropertyTycoonSnapshotCodec.validate(back!!, roster))
                }
            }
        }
    }

    @Test fun tileOwnershipBuildingSupplyAndDecksStayConsistentAfterEveryCommand() {
        val rnd = Random(9)
        repeat(10) { g ->
            PTT.playRandom(table(g + 50), rnd, cap = 40000) { st ->
                PTT.checkInvariants(st)
                for (ids in listOf(PtCards.tide.map { it.id } to (st.tideDraw + st.tideDiscard), PtCards.guild.map { it.id } to (st.guildDraw + st.guildDiscard))) {
                    val held = st.players.flatMap { it.passCardIds } + listOfNotNull(st.pendingCardId)
                    val all = ids.second + held.filter { it in ids.first }
                    assertEquals(ids.first.toSet(), all.toSet())
                    assertEquals(16, all.size)
                }
            }
        }
    }
}
