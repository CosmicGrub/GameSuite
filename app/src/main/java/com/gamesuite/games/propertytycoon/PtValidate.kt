package com.gamesuite.games.propertytycoon

import com.gamesuite.core.PlayerInfo

/** Structural validation of a decoded Property Tycoon save (the list on [PropertyTycoonSnapshotCodec]). Pure; null = refuse. */
internal object PtValidate {
    fun check(snap: PropertyTycoonSnapshot, roster: List<PlayerInfo>): TycoonState? = try {
        run(snap, roster)
    } catch (_: Exception) {
        null
    }

    private fun run(snap: PropertyTycoonSnapshot, roster: List<PlayerInfo>): TycoonState? {
        if (snap.version != PropertyTycoonSnapshot.VERSION) return null
        val s = snap.state
        val n = s.players.size
        if (n !in PtRules.MIN_SEATS..PtRules.MAX_SEATS || n != roster.size) return null
        if (s.over != (s.phase == Phase.GAME_OVER)) return null
        val cfg = s.config
        for ((i, p) in s.players.withIndex()) {
            if (p.seat != i || p.playerId != roster[i].playerId || p.isBot != roster[i].isBot) return null
            if (p.cash < 0 || (p.bankrupt && p.cash != 0)) return null
            if (p.position !in 0 until PtRules.TILE_COUNT) return null
            if (p.timeoutTurns !in 0 until PtRules.MAX_TIMEOUT_TURNS || (p.timeoutTurns > 0 && !p.inTimeout)) return null
            if (p.inTimeout && p.position != PtRules.TIMEOUT_INDEX) return null
            if (p.doublesStreak !in 0 until PtRules.MAX_DOUBLES) return null
            if (p.freePassCards != p.passCardIds.size) return null
            if (p.bankrupt && (p.passCardIds.isNotEmpty() || p.inTimeout)) return null
        }
        if (s.currentSeat !in 0 until n || (s.players[s.currentSeat].bankrupt && !s.over)) return null
        if (!s.over && s.players.count { !it.bankrupt } < 2) return null
        if (s.round < 1 || s.round > cfg.length.roundCap) return null
        if (s.jackpot < 0 || (s.jackpot > 0 && !cfg.houseRules.jackpot)) return null
        if (s.dice1 !in 0..6 || s.dice2 !in 0..6 || s.pendingMove < 0) return null
        if (s.over && s.winnerSeat !in 0 until n) return null
        // holdings
        val seen = HashSet<Int>()
        var cabins = 0
        var resorts = 0
        for (h in s.holdings) {
            if (!seen.add(h.tile)) return null
            val t = PtBoard.tiles.getOrNull(h.tile) ?: return null
            if (!t.ownable || h.owner !in 0 until n || s.players[h.owner].bankrupt) return null
            if (h.buildings !in 0..PtRules.RESORT_LEVEL) return null
            if (h.buildings > 0 && (t.type != TileType.PROPERTY || h.pledged)) return null
            if (h.pledged) return null
            if (h.buildings == PtRules.RESORT_LEVEL) resorts++ else cabins += h.buildings
        }
        for (g in ColorGroup.values()) {
            val tiles = PtBoard.tilesOf(g)
            val levels = tiles.map { s.holding(it.index)?.buildings ?: 0 }
            if (levels.any { it > 0 }) {
                val owner = s.holding(tiles[0].index)?.owner ?: return null
                if (tiles.any { s.holding(it.index)?.owner != owner }) return null
                if (levels.max() - levels.min() > 1) return null
            }
        }
        if (s.cabinsLeft != PtRules.CABIN_SUPPLY - cabins || s.resortsLeft != PtRules.RESORT_SUPPLY - resorts) return null
        // decks: every card exactly once between the piles, the held free-pass cards and the card being read
        fun deck(draw: List<String>, discard: List<String>, ids: List<String>): Boolean {
            val held = s.players.flatMap { it.passCardIds }.filter { it in ids }
            val all = draw + discard + held + listOfNotNull(s.pendingCardId?.takeIf { it in ids })
            return all.size == ids.size && all.toSet() == ids.toSet()
        }
        if (!deck(s.tideDraw, s.tideDiscard, PtCards.tide.map { it.id })) return null
        if (!deck(s.guildDraw, s.guildDiscard, PtCards.guild.map { it.id })) return null
        // phases
        if (s.phaseStack.size > 3) return null
        val me = s.players[s.currentSeat]
        when (s.phase) {
            Phase.AWAIT_DEBT -> {
                val d = s.debt ?: return null
                if (d.debtor !in 0 until n || s.players[d.debtor].bankrupt || d.amount <= 0) return null
                if (d.creditor != -1 && (d.creditor !in 0 until n || s.players[d.creditor].bankrupt)) return null
            }
            Phase.AWAIT_BUY -> {
                val t = PtBoard.tiles.getOrNull(s.landedTile) ?: return null
                if (!t.ownable || s.holding(s.landedTile) != null) return null
            }
            Phase.AWAIT_CARD_ACK -> if (s.pendingCardId == null || s.pendingCardId !in PtCards.byId) return null
            Phase.AWAIT_TIMEOUT_CHOICE -> if (!me.inTimeout) return null
            Phase.AWAIT_ROLL, Phase.AWAIT_POST_ROLL -> {}
            Phase.GAME_OVER -> {}
            Phase.AWAIT_AUCTION, Phase.AWAIT_TRADE_REPLY -> return null
        }
        if (s.phase != Phase.AWAIT_DEBT && s.debt != null) return null
        if (s.auction != null || s.trades.isNotEmpty()) return null
        if (snap.sessionWins.any { (k, v) -> v < 0 || roster.none { it.playerId == k } }) return null
        if (snap.nextStartingSeat !in 0 until n) return null
        return s
    }
}
