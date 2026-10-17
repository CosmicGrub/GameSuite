package com.gamesuite.games.mancala

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MancalaNetMessageTest {

    private fun roundTrip(message: MancalaNetMessage) =
        MancalaNetCodec.decode(MancalaNetCodec.encode(message))

    @Test
    fun `every message survives a round trip`() {
        val sync = MancalaNetMessage.StateSync(
            version = 7,
            state = MancalaNetState(
                pits = List(14) { if (it == 6 || it == 13) 0 else 4 },
                currentPlayerIndex = 1,
                lastAction = "Guest sowed from pit 3 and captured 5",
                roundOver = true,
                winnerPlayerId = "host",
                lastSowPath = listOf(9, 10, 11),
                lastCapture = MancalaNetCapture(landingPit = 11, oppositePit = 1, totalSwept = 5),
                scoreP1 = 2,
                scoreP2 = 1,
                draws = 1
            )
        )
        assertEquals(sync, roundTrip(sync))
        assertEquals(MancalaNetMessage.SowRequest(pit = 9, baseVersion = 3), roundTrip(MancalaNetMessage.SowRequest(9, 3)))
        assertEquals(MancalaNetMessage.RequestState, roundTrip(MancalaNetMessage.RequestState))
        assertEquals(MancalaNetMessage.PlayAgain, roundTrip(MancalaNetMessage.PlayAgain))
    }

    @Test
    fun `garbage decodes to null instead of throwing`() {
        assertNull(MancalaNetCodec.decode("nope".encodeToByteArray()))
        assertNull(MancalaNetCodec.decode(ByteArray(0)))
    }
}
