package com.gamesuite.games.territorywars

/**
 * Constants and pure formulas of Territory Wars (design doc section 2.1, the numbered rules script).
 * SCAFFOLD: written from the doc, not yet compiled or tested. The balance simulation (doc section 3.4) may retune
 * the numbers; they live here and nowhere else.
 */
object TerritoryWarsRules {
    const val MIN_PLAYERS = 2
    const val MAX_PLAYERS = 6
    const val MAX_ATTACK_DICE = 3
    const val MAX_DEFEND_DICE = 2
    const val DIE_FACES = 6

    /** Reinforcement = max(MIN_REINFORCEMENT, territories / TERRITORIES_PER_ARMY) + region bonuses + set value. */
    const val MIN_REINFORCEMENT = 3
    const val TERRITORIES_PER_ARMY = 3

    /** Opening placement: each seat places up to this many armies per round, in seat order. */
    const val INITIAL_BATCH = 3

    /** A seat holding this many cards or more must turn in a set before it may place or attack. */
    const val FORCED_TURN_IN_HAND = 5

    /** Extra starting armies per handicap preset. */
    const val HANDICAP_ARMIES = 6

    /** Rounds with no territory changing hands before a stalemate ends the war on points. */
    const val STALEMATE_ROUNDS = 5

    /** Set values for the 1st..7th set turned in this war; each later set adds [SET_VALUE_STEP]. */
    val SET_VALUES: List<Int> = listOf(3, 5, 7, 9, 12, 15, 18)
    const val SET_VALUE_STEP = 4

    /** Armies a set is worth when [setsAlreadyTurnedIn] sets have been turned in before it. */
    fun setValue(setsAlreadyTurnedIn: Int): Int {
        val n = if (setsAlreadyTurnedIn < 0) 0 else setsAlreadyTurnedIn
        return if (n < SET_VALUES.size) SET_VALUES[n] else SET_VALUES.last() + (n - (SET_VALUES.size - 1)) * SET_VALUE_STEP
    }

    /** The territory part of a reinforcement. */
    fun baseReinforcement(territories: Int): Int =
        maxOf(MIN_REINFORCEMENT, territories / TERRITORIES_PER_ARMY)

    /**
     * Every seat's total starting armies (territories included, one per territory at the deal).
     * 36-territory map: 2 seats 30, 3 seats 26, 4 seats 22, 5 seats 18, 6 seats 16.
     * 18-territory Blitz map: 2 seats 16, 3 seats 14, 4 seats 12.
     */
    fun startingArmies(mapTerritories: Int, seats: Int): Int =
        if (mapTerritories <= 18) {
            when (seats) { 2 -> 16; 3 -> 14; else -> 12 }
        } else {
            when (seats) { 2 -> 30; 3 -> 26; 4 -> 22; 5 -> 18; else -> 16 }
        }

    /** The map a war of [length] uses: Blitz with up to 4 seats plays a random half, everything else the full map. */
    fun mapIdFor(length: WarLength, seats: Int, seed: Long): String =
        if (length == WarLength.BLITZ && seats <= 4) {
            if ((seed and 1L) == 0L) "maravel-west-18" else "maravel-east-18"
        } else {
            "maravel-36"
        }

    /** The rules of one war from the Hub's choices. */
    fun configFor(
        length: WarLength,
        seats: Int,
        seed: Long,
        fortifyReach: FortifyReach = FortifyReach.CONNECTED,
        autoPlace: Boolean = false,
        handicapArmies: List<Int> = emptyList()
    ): TerritoryWarsConfig {
        val mapId = mapIdFor(length, seats, seed)
        val territories = if (mapId == "maravel-36") 36 else 18
        return when (length) {
            WarLength.BLITZ -> TerritoryWarsConfig(
                length = length, mapId = mapId, fortifyReach = fortifyReach, autoPlace = true,
                handicapArmies = handicapArmies, winRule = WinRule.CAPTURE_TARGET,
                captureTarget = (territories * 2 + 2) / 3, roundCap = 10, seed = seed
            )
            WarLength.STANDARD -> TerritoryWarsConfig(
                length = length, mapId = mapId, fortifyReach = fortifyReach, autoPlace = autoPlace,
                handicapArmies = handicapArmies, winRule = WinRule.CAPTURE_TARGET,
                captureTarget = 26, roundCap = 18, seed = seed
            )
            WarLength.LONG -> TerritoryWarsConfig(
                length = length, mapId = mapId, fortifyReach = fortifyReach, autoPlace = autoPlace,
                handicapArmies = handicapArmies, winRule = WinRule.ELIMINATION,
                captureTarget = territories, roundCap = 40, seed = seed
            )
        }
    }

    /** Per-seat extra starting armies for a Hub [preset]; seat 0 is the local person. */
    fun handicapArmies(preset: HandicapPreset, isBot: List<Boolean>): List<Int> = when (preset) {
        HandicapPreset.NONE -> emptyList()
        HandicapPreset.HUMAN_SEAT -> isBot.mapIndexed { i, _ -> if (i == 0) HANDICAP_ARMIES else 0 }
        HandicapPreset.COMPUTERS -> isBot.map { if (it) HANDICAP_ARMIES else 0 }
    }
}

/** The card deck and set rules (design doc section 2.1 rules 13 to 17). */
object TerritoryWarsCards {
    /**
     * The unshuffled deck for [map]: one card per territory, its symbol cycling Torch, Compass, Anchor in table order
     * within each region (so every region has two of each), plus 2 wild cards on the full map and 1 on a Blitz half.
     * Card ids are "card-<territoryId>" and "card-wild-<n>".
     */
    fun buildDeck(map: WarMap): List<WarCard> {
        val symbols = listOf(CardSymbol.TORCH, CardSymbol.COMPASS, CardSymbol.ANCHOR)
        val cards = ArrayList<WarCard>()
        for (region in map.regions) {
            region.territoryIds.forEachIndexed { i, tid ->
                cards += WarCard("card-$tid", symbols[i % symbols.size], tid)
            }
        }
        val wilds = if (map.size > 18) 2 else 1
        for (n in 1..wilds) cards += WarCard("card-wild-$n", CardSymbol.WILD, null)
        return cards
    }

    /** True when [cards] are three distinct cards forming a set: all one symbol, all three symbols, or containing a wild. */
    fun isSet(cards: List<WarCard>): Boolean {
        if (cards.size != 3) return false
        if (cards.map { it.id }.toSet().size != 3) return false
        if (cards.any { it.symbol == CardSymbol.WILD }) return true
        val distinct = cards.map { it.symbol }.toSet().size
        return distinct == 1 || distinct == 3
    }

    /** True when some three cards of [hand] form a set (any hand of 5 or more cards always does). */
    fun hasSet(hand: List<WarCard>): Boolean {
        for (a in hand.indices) for (b in a + 1 until hand.size) for (c in b + 1 until hand.size) {
            if (isSet(listOf(hand[a], hand[b], hand[c]))) return true
        }
        return false
    }
}
