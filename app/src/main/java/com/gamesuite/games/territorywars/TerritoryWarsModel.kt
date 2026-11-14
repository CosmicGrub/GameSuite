package com.gamesuite.games.territorywars

/*
 * Data model of Territory Wars (docs/design/boardgames/territory-wars.md, sections 2 and 4).
 * SCAFFOLD: nothing here has been compiled or run yet. Plain Kotlin only (no Compose, no Android), so the
 * engine, bots and simulations can run in JVM unit tests.
 *
 * Territories are addressed by their INDEX into [WarMap.territories] inside states and snapshots (compact,
 * fast to copy) and by their stable string id in content tables, saves of the map itself and network messages.
 */

/** A point in map units (the map is drawn on a [WarMap.width] x [WarMap.height] canvas and scaled by the screen). */
data class MapPoint(val x: Float, val y: Float)

/**
 * One territory. [neighbours] are territory ids (symmetric, includes sea links). [polygon] is the outline,
 * [centroid] the label / army-chip anchor (always inside the polygon bounds).
 */
data class Territory(
    val id: String,
    val name: String,
    val regionId: String,
    val neighbours: List<String>,
    val centroid: MapPoint,
    val polygon: List<MapPoint>
)

/** A group of territories that pays [bonus] armies each turn to the player who holds all of them. */
data class Region(val id: String, val name: String, val bonus: Int, val territoryIds: List<String>)

/** An adjacency across water, drawn as a dashed line between the two centroids. Also present in the neighbour lists. */
data class SeaLink(val a: String, val b: String, val name: String)

data class WarMap(
    val id: String,
    val name: String,
    val width: Float,
    val height: Float,
    val territories: List<Territory>,
    val regions: List<Region>,
    val seaLinks: List<SeaLink>
) {
    val size: Int get() = territories.size

    private val indexById: Map<String, Int> = territories.withIndex().associate { it.value.id to it.index }

    /** Index of the territory with [id], or -1. */
    fun indexOf(id: String): Int = indexById[id] ?: -1

    fun territory(id: String): Territory? = indexOf(id).let { if (it >= 0) territories[it] else null }

    fun region(id: String): Region? = regions.firstOrNull { it.id == id }

    /** Neighbour indices of the territory at [index] (sorted as listed in the content table). */
    fun neighbourIndices(index: Int): List<Int> =
        territories[index].neighbours.map { indexOf(it) }.filter { it >= 0 }

    /** The map restricted to [regionIds]: neighbours, regions and sea links outside the subset are dropped. */
    fun subMap(newId: String, newName: String, regionIds: Set<String>): WarMap {
        val keep = territories.filter { it.regionId in regionIds }
        val keepIds = keep.map { it.id }.toSet()
        return copy(
            id = newId,
            name = newName,
            territories = keep.map { t -> t.copy(neighbours = t.neighbours.filter { n -> n in keepIds }) },
            regions = regions.filter { it.id in regionIds },
            seaLinks = seaLinks.filter { it.a in keepIds && it.b in keepIds }
        )
    }

    /** Content problems (empty when the map is sound): duplicate ids, asymmetric or self borders, region mismatches, bad polygons. */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        val ids = territories.map { it.id }
        if (ids.toSet().size != ids.size) out += "duplicate territory ids"
        for (t in territories) {
            if (t.polygon.size < 3) out += "${t.id}: polygon needs 3+ points"
            if (t.id in t.neighbours) out += "${t.id}: borders itself"
            if (t.neighbours.toSet().size != t.neighbours.size) out += "${t.id}: duplicate neighbour"
            for (n in t.neighbours) {
                val other = territory(n)
                if (other == null) out += "${t.id}: unknown neighbour $n"
                else if (t.id !in other.neighbours) out += "${t.id} -> $n is not symmetric"
            }
            if (region(t.regionId) == null) out += "${t.id}: unknown region ${t.regionId}"
        }
        for (r in regions) {
            for (tid in r.territoryIds) {
                if (territory(tid)?.regionId != r.id) out += "region ${r.id} lists $tid which is not in it"
            }
            if (r.bonus < 0) out += "region ${r.id}: negative bonus"
        }
        if (regions.sumOf { it.territoryIds.size } != territories.size) out += "regions do not partition the territories"
        return out
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Cards

/** The three card symbols (original art: a torch, a compass rose, an anchor) and the wild card. */
enum class CardSymbol { TORCH, COMPASS, ANCHOR, WILD }

/** One card. Territory cards name a territory (flavour and tie to the map); wild cards have no territory. */
data class WarCard(val id: String, val symbol: CardSymbol, val territoryId: String?)

// ---------------------------------------------------------------------------------------------------------------
// Players and configuration

/** A seat at the table. [seat] is the 0-based turn order index; colour and pattern derive from it ([TerritoryWarsPalette]). */
data class TerritoryWarsPlayer(val playerId: String, val displayName: String, val isBot: Boolean, val seat: Int)

enum class WarLength { BLITZ, STANDARD, LONG }

/** Fortify reach: any chain of your own territories (default) or a single neighbour. */
enum class FortifyReach { CONNECTED, ADJACENT }

enum class WinRule { ELIMINATION, CAPTURE_TARGET }

enum class WinReason { NONE, DOMINATION, CAPTURE_TARGET, ROUND_CAP, STALEMATE }

/** Hub handicap presets; resolved to per-seat starting armies by [TerritoryWarsRules.handicapArmies]. */
enum class HandicapPreset(val id: String) {
    NONE("none"),
    HUMAN_SEAT("you6"),
    COMPUTERS("cpu6");

    companion object {
        fun fromId(id: String?): HandicapPreset = values().firstOrNull { it.id == id } ?: NONE
    }
}

/** Everything that fixes the rules of one war. Saved with the game. */
data class TerritoryWarsConfig(
    val length: WarLength = WarLength.STANDARD,
    val mapId: String = "maravel-36",
    val fortifyReach: FortifyReach = FortifyReach.CONNECTED,
    /** Deal the opening armies for the players instead of a placement round (always on in Blitz). */
    val autoPlace: Boolean = false,
    /** Extra starting armies per seat (empty = none). */
    val handicapArmies: List<Int> = emptyList(),
    val winRule: WinRule = WinRule.CAPTURE_TARGET,
    /** Territories to hold to win under [WinRule.CAPTURE_TARGET]. */
    val captureTarget: Int = 26,
    /** The war ends on points after this many rounds; 0 = no cap. */
    val roundCap: Int = 18,
    /** Seed of the whole war: deal, deck order and dice streams are derived from it. */
    val seed: Long = 0L
)

// ---------------------------------------------------------------------------------------------------------------
// State

/** The turn phases (state machine in the design doc, section 2.2). */
enum class TurnPhase { DEAL, PLACE_INITIAL, CARDS_FORCED, REINFORCE, ATTACK, MOVE_IN, FORTIFY, GAME_OVER }

/** A conquest waiting for the attacker to decide how many armies move in. Indices into the map. */
data class PendingMoveIn(val from: Int, val to: Int, val minMove: Int, val maxMove: Int)

/** What the last accepted action did; the screen words, animates, announces and speaks it. */
object TerritoryWarsEventKind {
    const val DEALT = "dealt"
    const val PLACED = "placed"
    const val SET_TURNED_IN = "set_turned_in"
    const val BATTLE = "battle"
    const val CONQUERED = "conquered"
    const val MOVED_IN = "moved_in"
    const val REGION_COMPLETED = "region_completed"
    const val ELIMINATED = "eliminated"
    const val FORTIFIED = "fortified"
    const val CARD_DRAWN = "card_drawn"
    const val TURN_STARTED = "turn_started"
    const val WAR_WON = "war_won"
    val ALL = setOf(
        DEALT, PLACED, SET_TURNED_IN, BATTLE, CONQUERED, MOVED_IN, REGION_COMPLETED,
        ELIMINATED, FORTIFIED, CARD_DRAWN, TURN_STARTED, WAR_WON
    )
}

/**
 * One thing that happened. [from] / [to] are territory indices (-1 when not applicable), [amount] and [extra] carry the
 * numbers each kind needs (armies placed, losses, bonus) as documented in the design doc, section 2.2.
 */
data class TerritoryWarsEvent(
    val kind: String,
    val seat: Int,
    val from: Int = -1,
    val to: Int = -1,
    val amount: Int = 0,
    val extra: Int = 0
)

/**
 * The whole table. All lists indexed by territory index or seat are immutable copies (a 36-element copy per move is
 * negligible), so a state is safe to hand to a bot on another thread.
 */
data class TerritoryWarsState(
    val mapId: String,
    val config: TerritoryWarsConfig,
    val players: List<TerritoryWarsPlayer>,
    /** Owning seat per territory index; -1 before the deal. */
    val owners: List<Int>,
    val armies: List<Int>,
    /** Hidden information: each seat's hand. A network host redacts all but the receiver's own ([redactedFor]). */
    val hands: List<List<WarCard>>,
    val deck: List<WarCard>,
    val discard: List<WarCard>,
    /** Sets turned in so far this war (drives the escalating set value). */
    val setsTurnedIn: Int,
    val currentSeat: Int,
    val phase: TurnPhase,
    /** Armies the seat on turn still has to place (reinforcement or opening placement). */
    val armiesToPlace: Int,
    /** Opening armies each seat has not yet placed (PLACE_INITIAL only). */
    val initialPools: List<Int>,
    val conqueredThisTurn: Boolean,
    val fortifiedThisTurn: Boolean,
    val pendingMoveIn: PendingMoveIn?,
    /** Dice the attacker rolled in the last battle round (the minimum move-in after a conquest). */
    val lastBattleDice: Int,
    /** 1-based round; a round is one turn of every living seat. */
    val round: Int,
    val eliminated: List<Boolean>,
    val winnerSeats: List<Int>,
    val winReason: WinReason,
    val roundsWithoutConquest: Int,
    val battles: Int,
    val conquests: Int,
    val event: TerritoryWarsEvent?,
    /** Only ever grows; keys the screen's animations. */
    val eventSerial: Int
) {
    val seats: Int get() = players.size

    fun territoriesOf(seat: Int): Int = owners.count { it == seat }

    fun armiesOf(seat: Int): Int = owners.indices.sumOf { if (owners[it] == seat) armies[it] else 0 }

    /**
     * This state as seat [seat] may see it: every other seat's hand is replaced by that many face-down placeholder
     * cards (same count, wild symbol, no territory), and the deck order is hidden the same way. TODO: network host
     * only; the local pass-and-play screen hides hands with the hand-over curtain instead.
     */
    fun redactedFor(seat: Int): TerritoryWarsState {
        val hidden = WarCard("hidden", CardSymbol.WILD, null)
        return copy(
            hands = hands.mapIndexed { i, h -> if (i == seat) h else h.map { hidden } },
            deck = deck.map { hidden },
            discard = discard
        )
    }

    companion object {
        /** A blank, not yet dealt table for [map] (phase DEAL). The stub engine returns this; the real engine fills it in. */
        fun blank(map: WarMap, config: TerritoryWarsConfig, players: List<TerritoryWarsPlayer>): TerritoryWarsState =
            TerritoryWarsState(
                mapId = map.id, config = config, players = players,
                owners = List(map.size) { -1 }, armies = List(map.size) { 0 },
                hands = players.map { emptyList() }, deck = emptyList(), discard = emptyList(),
                setsTurnedIn = 0, currentSeat = 0, phase = TurnPhase.DEAL, armiesToPlace = 0,
                initialPools = players.map { 0 },
                conqueredThisTurn = false, fortifiedThisTurn = false, pendingMoveIn = null, lastBattleDice = 0,
                round = 1, eliminated = players.map { false }, winnerSeats = emptyList(), winReason = WinReason.NONE,
                roundsWithoutConquest = 0, battles = 0, conquests = 0, event = null, eventSerial = 0
            )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Actions

/** Everything a seat can ask the engine to do. Indices are map territory indices; card ids are [WarCard.id]. */
sealed class TerritoryWarsAction {
    /** Opening placement or reinforcement: put [count] armies of the pool on one of your territories. */
    data class PlaceArmies(val territory: Int, val count: Int) : TerritoryWarsAction()

    /** Place the whole remaining pool at random on your territories (Auto-place option, bots, "place for me"). */
    object AutoPlaceRemaining : TerritoryWarsAction()

    /** Turn in three cards that form a set. */
    data class TurnInCards(val cardIds: List<String>) : TerritoryWarsAction()

    /** One battle round: attack [to] from [from] with [dice] attacker dice (1..3). */
    data class Attack(val from: Int, val to: Int, val dice: Int) : TerritoryWarsAction()

    /** Repeat battle rounds with the maximum dice until the territory falls or the attacker is down to 1 army. */
    data class AttackUntilDone(val from: Int, val to: Int) : TerritoryWarsAction()

    /** After a conquest: how many armies move into the conquered territory. */
    data class MoveIn(val count: Int) : TerritoryWarsAction()

    /** Stop attacking and go to the fortify step. */
    object EndAttack : TerritoryWarsAction()

    /** The single fortify move of the turn. */
    data class Fortify(val from: Int, val to: Int, val count: Int) : TerritoryWarsAction()

    /** Skip fortifying (ends the turn). */
    object SkipFortify : TerritoryWarsAction()
}
