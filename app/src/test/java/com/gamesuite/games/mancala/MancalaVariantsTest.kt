package com.gamesuite.games.mancala

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Hub's Mancala variants: stones per pit (3-6), pits per side (5 or 6) and the capture rule
 * (opposite pit or none). Board index reminder for n pits per side: seat 0's pits are 0 until n,
 * its store is n, seat 1's pits are n+1..2n and its store is 2n+1; the pit opposite p is 2n - p.
 */
class MancalaVariantsTest {

    private val allRules: List<MancalaRules> = buildList {
        for (pits in listOf(5, 6)) {
            for (stones in 3..6) {
                for (capture in MancalaCaptureRule.values()) add(MancalaRules(pits, stones, capture))
            }
        }
    }

    private fun newGame(
        rules: MancalaRules,
        bots: Pair<Boolean, Boolean> = false to false,
        mode: PlayMode = PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    ): MancalaGame {
        val game = MancalaGame()
        game.rules = rules
        game.init(
            GameContext(
                activeMode = mode,
                players = listOf(
                    PlayerInfo(playerId = "p0", displayName = "P0", isBot = bots.first),
                    PlayerInfo(playerId = "p1", displayName = "P1", isBot = bots.second)
                ),
                localPlayerIndex = 0,
                transport = LocalPassAndPlayTransport()
            )
        )
        game.difficulty = CpuDifficulty.MEDIUM
        return game
    }

    private fun started(rules: MancalaRules) = newGame(rules).also { it.startMatch() }

    /** A board with [counts] (board index -> stones) and every other stone parked in seat 1's last pit. */
    private fun fixture(rules: MancalaRules, counts: Map<Int, Int>): List<Int> {
        val p = IntArray(rules.boardSize)
        counts.forEach { (i, c) -> p[i] = c }
        val rest = rules.totalStones - p.sum()
        assertTrue("fixture overfills the board for $rules", rest >= 0)
        p[rules.store1 - 1] += rest
        return p.toList()
    }

    private fun seed(game: MancalaGame, pits: List<Int>, current: Int = 0) {
        assertEquals(game.rules.totalStones, pits.sum())
        game.state.value = MancalaState(pits = pits, currentPlayerIndex = current, lastAction = "Fixture seeded")
    }

    private fun MancalaGame.plain() = state.value!!.copy(restored = false)

    // --- rules value type ---------------------------------------------------------------------

    @Test
    fun `the stone total is twice pits times stones and the standard rules are 6 by 4 with opposite capture`() {
        assertEquals(MancalaRules(6, 4, MancalaCaptureRule.OPPOSITE), MancalaRules())
        assertEquals(48, MancalaRules().totalStones)
        assertEquals(12 * 5, MancalaRules(6, 5).totalStones)
        assertEquals(10 * 3, MancalaRules(5, 3).totalStones)
        assertEquals(14, MancalaRules().boardSize)
        assertEquals(12, MancalaRules(5, 4).boardSize)
        assertEquals(5, MancalaRules(5, 4).store0)
        assertEquals(11, MancalaRules(5, 4).store1)
    }

    @Test
    fun `hub extras map to rules and anything missing or unsupported falls back to the standard game`() {
        assertEquals(MancalaRules(), MancalaRules.fromExtras(emptyMap()))
        assertEquals(
            MancalaRules(5, 6, MancalaCaptureRule.NONE),
            MancalaRules.fromExtras(mapOf("pits" to "5", "stones" to "6", "capture" to "none"))
        )
        assertEquals(MancalaRules(), MancalaRules.fromExtras(mapOf("pits" to "9", "stones" to "40", "capture" to "wild")))
        assertEquals(MancalaRules(6, 3), MancalaRules.fromExtras(mapOf("stones" to "3")))
    }

    @Test
    fun `an unsupported rules value is replaced by the standard game`() {
        val game = MancalaGame()
        game.rules = MancalaRules(pitsPerSide = 9, stonesPerPit = 4)
        assertEquals(MancalaRules(), game.rules)
        game.rules = MancalaRules(5, 3)
        assertEquals(MancalaRules(5, 3), game.rules)
    }

    // --- dealing and sowing ---------------------------------------------------------------------

    @Test
    fun `every variant deals stones per pit into every pit and nothing into the stores`() {
        for (rules in allRules) {
            val pits = started(rules).state.value!!.pits
            assertEquals(rules.boardSize, pits.size)
            assertEquals(rules.totalStones, pits.sum())
            assertEquals(0, pits[rules.store0])
            assertEquals(0, pits[rules.store1])
            for (i in rules.pitsOf(0) + rules.pitsOf(1)) assertEquals("$rules pit $i", rules.stonesPerPit, pits[i])
        }
    }

    @Test
    fun `the standard game still deals 4 stones in 6 pits per side`() {
        val game = newGame(MancalaRules()).also { it.startMatch() }
        assertEquals(listOf(4, 4, 4, 4, 4, 4, 0, 4, 4, 4, 4, 4, 4, 0), game.state.value!!.pits)
    }

    @Test
    fun `sowing drops one stone per pit, skips the opponent store, and ends where the count says`() {
        for (rules in allRules) {
            val game = started(rules)
            val n = rules.pitsPerSide
            // Pit 0 with s stones lands in pit s (a pit with stones in it, so no capture) unless that is the store.
            val s = rules.stonesPerPit
            if (s >= n) continue
            game.sow(0, 0)
            val pits = game.state.value!!.pits
            assertEquals("$rules", 0, pits[0])
            for (i in 1..s) assertEquals("$rules pit $i", s + 1, pits[i])
            assertEquals(rules.totalStones, pits.sum())
            assertEquals(1, game.state.value!!.currentPlayerIndex)
            assertEquals((0..s).toList(), game.state.value!!.lastSowPath)
        }
    }

    @Test
    fun `the pit n minus s from the store ends in the store for an extra turn in every variant`() {
        for (rules in allRules) {
            val game = started(rules)
            val n = rules.pitsPerSide
            val pit = n - rules.stonesPerPit
            if (pit < 0) continue
            game.sow(0, pit)
            val s = game.state.value!!
            assertEquals("$rules", 1, s.pits[rules.store0])
            assertEquals("$rules: the extra turn keeps seat 0 on move", 0, s.currentPlayerIndex)
            assertEquals(rules.store0, s.lastSowPath.last())
            assertNull(s.lastCapture)
        }
    }

    @Test
    fun `a long sow laps the board, skipping only the opponent store`() {
        for (rules in allRules) {
            val game = newGame(rules)
            val big = rules.totalStones - 2
            game.state.value = MancalaState(
                pits = fixture(rules, mapOf(0 to big, rules.pitsOf(1).first() to 1)),
                currentPlayerIndex = 0,
                lastAction = "Fixture seeded"
            )
            assertEquals(rules.totalStones, game.state.value!!.pits.sum())
            game.sow(0, 0)
            val after = game.state.value!!.pits
            assertEquals("$rules", rules.totalStones, after.sum())
            assertEquals("$rules: opponent store is never sown into", 0, after[rules.store1])
            assertTrue("$rules: the sow passed the own store at least once", after[rules.store0] >= 1)
        }
    }

    // --- capture --------------------------------------------------------------------------------

    @Test
    fun `opposite capture sweeps the landing pit and the pit across into the store`() {
        for (rules in allRules.filter { it.capture == MancalaCaptureRule.OPPOSITE }) {
            val game = newGame(rules)
            val opposite = rules.oppositeOf(1)
            seed(game, fixture(rules, mapOf(0 to 1, 1 to 0, 3 to 2, opposite to 3)))
            assertEquals("$rules", setOf(0), game.captureCandidates(0))
            game.sow(0, 0)
            val s = game.state.value!!
            assertEquals("$rules", 4, s.pits[rules.store0])
            assertEquals(0, s.pits[1])
            assertEquals(0, s.pits[opposite])
            assertEquals(MancalaCapture(landingPit = 1, oppositePit = opposite, totalSwept = 4), s.lastCapture)
            assertEquals(1, s.currentPlayerIndex)
            assertFalse(s.roundOver)
            assertEquals(rules.totalStones, s.pits.sum())
        }
    }

    @Test
    fun `an empty opposite pit means no capture even with captures on`() {
        for (rules in allRules.filter { it.capture == MancalaCaptureRule.OPPOSITE }) {
            val game = newGame(rules)
            seed(game, fixture(rules, mapOf(0 to 1, 3 to 2)))
            assertEquals(emptySet<Int>(), game.captureCandidates(0).filter { it == 0 }.toSet())
            game.sow(0, 0)
            val s = game.state.value!!
            assertEquals(1, s.pits[1])
            assertEquals(0, s.pits[rules.store0])
            assertNull(s.lastCapture)
        }
    }

    @Test
    fun `with captures off an empty-pit landing does nothing and nothing is highlighted`() {
        for (rules in allRules.filter { it.capture == MancalaCaptureRule.NONE }) {
            val game = newGame(rules)
            val opposite = rules.oppositeOf(1)
            seed(game, fixture(rules, mapOf(0 to 1, 1 to 0, 3 to 2, opposite to 3)))
            assertEquals(emptySet<Int>(), game.captureCandidates(0))
            game.sow(0, 0)
            val s = game.state.value!!
            assertEquals("$rules", 1, s.pits[1])
            assertEquals(3, s.pits[opposite])
            assertEquals(0, s.pits[rules.store0])
            assertNull(s.lastCapture)
            assertEquals(1, s.currentPlayerIndex)
        }
    }

    // --- game end -------------------------------------------------------------------------------

    @Test
    fun `emptying one side ends the game and the other side's stones go to its owner, in every variant`() {
        for (rules in allRules) {
            val game = newGame(rules)
            val n = rules.pitsPerSide
            // Seat 0's last stone sits in its last pit: sowing it ends in the store and empties seat 0.
            seed(game, fixture(rules, mapOf(n - 1 to 1)))
            game.sow(0, n - 1)
            val s = game.state.value!!
            assertTrue("$rules", s.roundOver)
            assertEquals(1, s.pits[rules.store0])
            assertEquals(rules.totalStones - 1, s.pits[rules.store1])
            assertTrue(rules.pitsOf(0).all { s.pits[it] == 0 })
            assertTrue(rules.pitsOf(1).all { s.pits[it] == 0 })
            assertEquals("p1", s.winnerPlayerId)
            assertEquals(1, game.scoreP2.value)
            assertEquals(0, game.scoreP1.value)
            // Once over, further taps change nothing.
            val frozen = game.state.value
            game.sow(0, 0)
            assertEquals(frozen, game.state.value)
        }
    }

    @Test
    fun `an equal final count is a tie`() {
        for (rules in allRules) {
            val game = newGame(rules)
            val n = rules.pitsPerSide
            val half = rules.totalStones / 2
            // Seat 0 has half-1 banked and one stone left to sow into the store; seat 1 has the other half.
            seed(game, fixture(rules, mapOf(n - 1 to 1, rules.store0 to half - 1, rules.store1 - 1 to half)))
            game.sow(0, n - 1)
            val s = game.state.value!!
            assertTrue(s.roundOver)
            assertEquals(s.pits[rules.store0], s.pits[rules.store1])
            assertNull(s.winnerPlayerId)
            assertEquals(1, game.draws.value)
        }
    }

    // --- resume / undo --------------------------------------------------------------------------

    @Test
    fun `a snapshot round trips every variant, restores its rules into a default game and plays on identically`() {
        for (rules in allRules) {
            val a = started(rules)
            val extraPit = (rules.pitsPerSide - rules.stonesPerPit).takeIf { it >= 0 } ?: 0
            a.sow(0, extraPit)
            val snapshot = a.snapshot()!!

            val b = newGame(MancalaRules())
            assertTrue("$rules", b.restore(snapshot))
            assertEquals(rules, b.rules)
            assertEquals(a.state.value, b.plain())
            assertTrue(b.state.value!!.restored)

            val next = rules.pitsOf(a.state.value!!.currentPlayerIndex).first { a.state.value!!.pits[it] > 0 }
            a.sow(a.state.value!!.currentPlayerIndex, next)
            b.sow(b.state.value!!.currentPlayerIndex, next)
            assertEquals("$rules: the same move leads to the same position", a.state.value, b.state.value)
        }
    }

    @Test
    fun `a save written before the options existed restores as the standard game`() {
        val old = """{"pits":[4,4,4,4,4,4,0,4,4,4,4,4,4,0],"currentPlayerIndex":0,"lastAction":"x","roundOver":false,""" +
            """"winnerPlayerId":null,"lastSowPath":[],"lastCapture":null,"players":[{"playerId":"p0","isBot":false},""" +
            """{"playerId":"p1","isBot":false}],"scoreP1":0,"scoreP2":0,"draws":0,"difficulty":"MEDIUM"}"""
        val game = newGame(MancalaRules(5, 3, MancalaCaptureRule.NONE))
        assertTrue(game.restore(old))
        assertEquals(MancalaRules(), game.rules)
        assertEquals(48, game.state.value!!.pits.sum())
    }

    @Test
    fun `restore rejects a stone count, pit count, rule name or board that does not match the saved options`() {
        val five = started(MancalaRules(6, 5, MancalaCaptureRule.NONE)).snapshot()!!
        val fiveBoard = started(MancalaRules(5, 4)).snapshot()!!
        assertTrue(five.contains("\"stonesPerPit\":5"))
        assertTrue(five.contains("\"captureRule\":\"none\""))
        assertTrue(fiveBoard.contains("\"pitsPerSide\":5"))

        val target = started(MancalaRules())
        val before = target.state.value
        val tampered = listOf(
            five.replace("\"stonesPerPit\":5", "\"stonesPerPit\":4"), // 60 stones on a 4-stone board
            five.replace("\"stonesPerPit\":5", "\"stonesPerPit\":9"), // unsupported
            five.replace("\"captureRule\":\"none\"", "\"captureRule\":\"bogus\""),
            fiveBoard.replace("\"pitsPerSide\":5", "\"pitsPerSide\":6"), // 12 entries on a 14-entry board
            fiveBoard.replace("\"pitsPerSide\":5", "\"pitsPerSide\":4"), // unsupported
            five.replace("\"pits\":[", "\"pits\":[1,") // one entry too many and one stone too many
        )
        for (bad in tampered) {
            assertFalse(bad, target.restore(bad))
            assertEquals("a rejected restore leaves the engine untouched", before, target.state.value)
            assertEquals(MancalaRules(), target.rules)
        }
        // The untampered saves are fine.
        assertTrue(target.restore(five))
        assertEquals(MancalaRules(6, 5, MancalaCaptureRule.NONE), target.rules)
    }

    @Test
    fun `undo works on a variant board`() {
        val rules = MancalaRules(5, 5, MancalaCaptureRule.NONE)
        val game = started(rules)
        val opening = game.plain()
        game.sow(0, 1)
        assertNotNull(game.state.value!!.lastSowPath)
        assertTrue(game.canUndo)
        game.undo()
        assertEquals(opening, game.plain())
        assertEquals(rules, game.rules)
    }

    // --- bot and hint ---------------------------------------------------------------------------

    @Test
    fun `every difficulty plays only legal pits on every variant`() {
        for (rules in allRules) {
            for (difficulty in CpuDifficulty.values()) {
                val game = newGame(rules, bots = true to true, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
                game.difficulty = difficulty
                game.startMatch()
                val maxMoves = if (difficulty == CpuDifficulty.HARD) 5 else 3000
                var moves = 0
                while (!game.state.value!!.roundOver && moves++ < maxMoves) {
                    val before = game.state.value!!
                    val mover = before.currentPlayerIndex
                    game.playBotTurn()
                    val after = game.state.value!!
                    assertTrue("$rules $difficulty: the bot must move", after !== before)
                    val picked = after.lastSowPath.first()
                    assertTrue("$rules $difficulty: pit $picked is the mover's", picked in rules.pitsOf(mover))
                    assertTrue("$rules $difficulty: pit $picked was non-empty", before.pits[picked] > 0)
                    assertEquals(rules.totalStones, after.pits.sum())
                }
                if (difficulty != CpuDifficulty.HARD) {
                    assertTrue("$rules $difficulty: the game finishes", game.state.value!!.roundOver)
                }
            }
        }
    }

    @Test
    fun `a bot plan computed off-thread is applied once and dropped when the position has moved on`() {
        val rules = MancalaRules(5, 5)
        val game = newGame(rules, bots = false to true, mode = PlayMode.SINGLE_PLAYER_VS_BOT)
        game.difficulty = CpuDifficulty.HARD
        game.startMatch()
        assertNull("it is the human's turn", game.planBotTurn())
        game.sow(0, 1) // pit 1 with 5 stones ends in seat 1's first pit: not an extra turn, the bot is on move
        assertEquals(1, game.state.value!!.currentPlayerIndex)

        val plan = game.planBotTurn()!!
        val move = plan.chooseMove()!!
        assertTrue(move in rules.pitsOf(1))
        assertTrue(game.state.value!!.pits[move] > 0)

        // The position changes under the plan (an undo, a new board): the stale answer is dropped.
        val replaced = game.state.value!!.copy(lastAction = "changed")
        game.state.value = replaced
        game.applyBotMove(plan, move)
        assertTrue(game.state.value === replaced)

        val fresh = game.planBotTurn()!!
        game.applyBotMove(fresh, fresh.chooseMove())
        assertTrue("the fresh plan is applied", game.state.value !== replaced)
        assertEquals(move, game.state.value!!.lastSowPath.first())
    }

    @Test
    fun `a hint plan rings a legal pit and is dropped when the position changed`() {
        for (rules in allRules) {
            val game = started(rules)
            val plan = game.planHint()!!
            val pit = plan.chooseMove()
            assertNotNull(pit)
            assertTrue("$rules", pit!! in rules.pitsOf(0))
            val moved = game.state.value!!.copy(lastAction = "changed")
            game.state.value = moved
            assertNull(game.applyHint(plan, pit))
            assertNull(game.hintPit.value)
            assertEquals(pit, game.hint())
        }
    }

    // --- networking -----------------------------------------------------------------------------

    private fun variantPair(hostRules: MancalaRules, guestRules: MancalaRules): MancalaPair {
        val hub = MancalaHub()
        val host = MancalaTestKit.node(0, PlayMode.LOCAL_AD_HOC, hub.endpoint(MancalaTestKit.HOST_ID))
        val guest = MancalaTestKit.node(1, PlayMode.LOCAL_AD_HOC, hub.endpoint(MancalaTestKit.GUEST_ID))
        host.game.rules = hostRules
        guest.game.rules = guestRules
        host.game.startMatch()
        guest.game.startMatch()
        return MancalaPair(host, guest, hub)
    }

    @Test
    fun `a networked variant board reaches the guest and stays in sync through a move`() {
        val rules = MancalaRules(5, 6, MancalaCaptureRule.NONE)
        val pair = variantPair(rules, rules)
        val opening = pair.guest.game.state.value
        assertNotNull(opening)
        assertEquals(rules.boardSize, opening!!.pits.size)
        assertEquals(rules.totalStones, opening.pits.sum())

        // The wire carries the variant settings.
        val sync = pair.hub.endpoints.getValue(MancalaTestKit.HOST_ID).sent.first { it.message is MancalaNetMessage.StateSync }.stateSync
        assertEquals(5, sync.state.pitsPerSide)
        assertEquals(6, sync.state.stonesPerPit)
        assertEquals("none", sync.state.captureRule)

        pair.host.game.sow(0, 0)
        MancalaTestKit.assertSynced(pair)
        assertEquals(1, pair.guest.game.state.value!!.currentPlayerIndex)
    }

    @Test
    fun `a guest ignores a board with different settings from its own`() {
        val pair = variantPair(MancalaRules(6, 5), MancalaRules())
        assertNull("the mismatched opening board is rejected", pair.guest.game.state.value)

        // The standard host and guest still work as before.
        val standard = variantPair(MancalaRules(), MancalaRules())
        assertNotNull(standard.guest.game.state.value)
    }

    @Test
    fun `a state sync with the wrong stone total for its settings is rejected`() {
        val rules = MancalaRules(6, 5)
        val pair = variantPair(rules, rules)
        val before = pair.guest.game.state.value
        val standardBoard = MancalaTestKit.netState() // 48 stones, default settings
        pair.hub.endpoints.getValue(MancalaTestKit.GUEST_ID)
            .inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(50, standardBoard))
        assertTrue(pair.guest.game.state.value === before)
        val wrongTotal = MancalaTestKit.netState(pits = List(14) { if (it == 6 || it == 13) 0 else 4 })
            .copy(stonesPerPit = 5)
        pair.hub.endpoints.getValue(MancalaTestKit.GUEST_ID)
            .inject(MancalaTestKit.HOST_ID, MancalaNetMessage.StateSync(51, wrongTotal))
        assertTrue(pair.guest.game.state.value === before)
    }

    @Test
    fun `net state messages without the new fields still decode as the standard game`() {
        // A state built without the variant fields takes the standard defaults, and survives the codec.
        val state = MancalaNetState(pits = List(14) { 0 }, currentPlayerIndex = 0, lastAction = "x")
        assertEquals(6, state.pitsPerSide)
        assertEquals(4, state.stonesPerPit)
        assertEquals("opposite", state.captureRule)
        val roundTrip = MancalaNetCodec.decode(MancalaNetCodec.encode(MancalaNetMessage.StateSync(1, state)))
        assertEquals(MancalaNetMessage.StateSync(1, state), roundTrip)
    }
}
