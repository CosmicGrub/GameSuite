package com.gamesuite.games.sweettrail

/** SINGLE: next square of the colour. DOUBLE: the second next square of the colour. LANDMARK: jump forward to a landmark. */
enum class TrailCardKind { SINGLE, DOUBLE, LANDMARK }

/**
 * One card. [id] is dense (0 until [SweetTrailDeck.SIZE]) and is what state, snapshots and (later) network
 * messages carry. Ids 0..47 are singles (colour = id / 8), 48..59 are doubles (colour = (id - 48) / 2; in the LONG
 * deck they are two extra singles per colour) and 60..65 are the landmark cards in [TrailLandmark] order.
 */
data class TrailCard(
    val id: Int,
    val kind: TrailCardKind,
    val colour: TrailColour? = null,
    val landmark: TrailLandmark? = null
)

/** The deck. 66 cards in every length; the shuffled order is hidden information (host only, if it is ever networked). */
object SweetTrailDeck {
    const val SIZE = 66
    const val SINGLES_PER_COLOUR = 8
    const val DOUBLES_PER_COLOUR = 2
    const val LONG_SINGLES_PER_COLOUR = 10
    const val LANDMARK_CARDS = 6

    /** Every card of the deck for [length], in id order (index == id). */
    fun cards(length: TrailLength): List<TrailCard> {
        val colours = TrailColour.values()
        val landmarks = TrailLandmark.values()
        val out = ArrayList<TrailCard>(SIZE)
        for (i in 0 until 48) out += TrailCard(i, TrailCardKind.SINGLE, colours[i / SINGLES_PER_COLOUR])
        for (i in 48 until 60) {
            val colour = colours[(i - 48) / DOUBLES_PER_COLOUR]
            out += TrailCard(i, if (length.doubles) TrailCardKind.DOUBLE else TrailCardKind.SINGLE, colour)
        }
        for (i in 60 until SIZE) out += TrailCard(i, TrailCardKind.LANDMARK, landmark = landmarks[i - 60])
        return out
    }

    // built once per length: the engine looks a card up on every draw and the self-play tests do so millions of times
    private val cache: Map<TrailLength, List<TrailCard>> = TrailLength.values().associateWith { cards(it) }

    /** The card with [id] for [length], or null when [id] is not a card id. */
    fun byId(id: Int, length: TrailLength): TrailCard? = cache.getValue(length).getOrNull(id)
}
