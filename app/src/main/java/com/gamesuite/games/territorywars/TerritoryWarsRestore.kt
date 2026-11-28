package com.gamesuite.games.territorywars

import com.gamesuite.core.CpuLevel
import com.gamesuite.core.PlayerInfo

/** A saved war that passed every check of [TerritoryWarsRestore.rebuild]. */
internal class RestoredWar(
    val state: TerritoryWarsState,
    val sessionWins: Map<String, Int>,
    val level: CpuLevel,
    val diceDrawn: Int
)

/**
 * Decodes and VALIDATES a Territory Wars save against the roster that is resuming it (the checklist on
 * [TerritoryWarsSnapshot]). Returns null (fail closed) for anything wrong, and never throws.
 */
internal object TerritoryWarsRestore {
    private const val MAX_DICE_LOG = 50_000_000

    fun rebuild(text: String, roster: List<PlayerInfo>): RestoredWar? = try {
        run(text, roster)
    } catch (_: Exception) {
        null
    }

    private inline fun <reified E : Enum<E>> parse(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

    private fun run(text: String, roster: List<PlayerInfo>): RestoredWar? {
        val s = territoryWarsJson.decodeFromString(TerritoryWarsSnapshot.serializer(), text)
        if (s.schemaVersion != TerritoryWarsGame.SCHEMA_VERSION) return null
        val map = TerritoryWarsMaps.byId(s.mapId) ?: return null
        val n = roster.size
        if (n !in TerritoryWarsRules.MIN_PLAYERS..TerritoryWarsRules.MAX_PLAYERS || s.players.size != n) return null
        for (i in 0 until n) {
            val p = s.players[i]
            if (p.playerId != roster[i].playerId || p.isBot != roster[i].isBot) return null
        }
        val length = parse<WarLength>(s.length) ?: return null
        val reach = parse<FortifyReach>(s.fortifyReach) ?: return null
        val winRule = parse<WinRule>(s.winRule) ?: return null
        val phase = parse<TurnPhase>(s.phase) ?: return null
        val reason = parse<WinReason>(s.winReason) ?: return null
        val level = CpuLevel.values().firstOrNull { it.name == s.difficulty } ?: return null
        if (phase == TurnPhase.DEAL) return null
        if (s.owners.size != map.size || s.armies.size != map.size) return null
        if (s.handicapArmies.size > n || s.handicapArmies.any { it < 0 || it > 100 }) return null
        if (s.roundCap < 0 || s.roundCap > 1000 || s.captureTarget < 0 || s.captureTarget > map.size) return null
        if (s.firstSeat !in 0 until n) return null
        val over = phase == TurnPhase.GAME_OVER
        val pend = if (s.moveInFrom >= 0) PendingMoveIn(s.moveInFrom, s.moveInTo, s.moveInMin, s.moveInMax) else null
        for (i in s.owners.indices) {
            if (s.owners[i] !in 0 until n) return null
            val floor = if (phase == TurnPhase.MOVE_IN && pend?.to == i) 0 else 1
            if (s.armies[i] < floor || s.armies[i] > 100_000) return null
        }
        // cards: every card of the map exactly once between hands, deck and discard
        val byId = TerritoryWarsCards.buildDeck(map).associateBy { it.id }
        val ids = s.hands.flatten() + s.deck + s.discard
        if (s.hands.size != n || ids.size != byId.size || ids.toSet() != byId.keys) return null
        fun cards(list: List<String>) = list.map { byId.getValue(it) }
        // eliminated flags match "owns nothing"
        if (s.eliminated.size != n) return null
        for (seat in 0 until n) if (s.eliminated[seat] != (s.owners.none { it == seat })) return null
        if (s.initialPools.size != n || s.initialPools.any { it < 0 || it > 1000 } || s.armiesToPlace < 0 || s.armiesToPlace > 10_000) return null
        if (s.currentSeat !in 0 until n || (!over && s.eliminated[s.currentSeat])) return null
        if (!over && s.eliminated.count { !it } < 2) return null
        if (s.winnerSeats.any { it !in 0 until n }) return null
        if ((s.winnerSeats.isNotEmpty() || reason != WinReason.NONE) != over) return null
        if (s.round < 1 || s.round > (if (s.roundCap > 0) s.roundCap + 1 else 100_000)) return null
        if (s.setsTurnedIn < 0 || s.roundsWithoutConquest < 0 || s.battles < 0 || s.conquests < 0 || s.conquestsThisRound < 0) return null
        if (s.diceDrawn < 0 || s.diceDrawn > MAX_DICE_LOG || s.lastBattleDice !in 0..3) return null
        if (s.sessionWins.any { (k, v) -> v < 0 || roster.none { r -> r.playerId == k } }) return null
        // phase and pending move-in agree
        if ((phase == TurnPhase.MOVE_IN) != (pend != null)) return null
        if (pend != null) {
            if (pend.from !in s.owners.indices || pend.to !in s.owners.indices) return null
            if (s.owners[pend.from] != s.currentSeat || s.owners[pend.to] != s.currentSeat) return null
            if (pend.to == pend.from || pend.to !in map.neighbourIndices(pend.from)) return null
            if (s.armies[pend.to] != 0 || pend.minMove < 1 || pend.maxMove < pend.minMove || pend.maxMove > s.armies[pend.from] - 1) return null
        }
        if (phase == TurnPhase.PLACE_INITIAL && s.initialPools[s.currentSeat] <= 0) return null
        if (phase == TurnPhase.CARDS_FORCED && s.hands[s.currentSeat].size < 3) return null
        val config = TerritoryWarsConfig(
            length = length, mapId = s.mapId, fortifyReach = reach, autoPlace = s.autoPlace, handicapArmies = s.handicapArmies,
            winRule = winRule, captureTarget = s.captureTarget, roundCap = s.roundCap, seed = s.seed, firstSeat = s.firstSeat
        )
        val state = TerritoryWarsState(
            mapId = s.mapId, config = config,
            players = s.players.mapIndexed { i, p -> TerritoryWarsPlayer(p.playerId, p.displayName, p.isBot, i) },
            owners = s.owners, armies = s.armies, hands = s.hands.map { cards(it) }, deck = cards(s.deck), discard = cards(s.discard),
            setsTurnedIn = s.setsTurnedIn, currentSeat = s.currentSeat, phase = phase, armiesToPlace = s.armiesToPlace,
            initialPools = s.initialPools, conqueredThisTurn = s.conqueredThisTurn, fortifiedThisTurn = s.fortifiedThisTurn,
            pendingMoveIn = pend, lastBattleDice = s.lastBattleDice, round = s.round, eliminated = s.eliminated,
            winnerSeats = s.winnerSeats, winReason = reason, roundsWithoutConquest = s.roundsWithoutConquest,
            battles = s.battles, conquests = s.conquests, event = null, eventSerial = 0, conquestsThisRound = s.conquestsThisRound
        )
        return RestoredWar(state, s.sessionWins, level, s.diceDrawn)
    }
}
