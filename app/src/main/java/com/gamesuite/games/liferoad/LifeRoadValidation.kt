package com.gamesuite.games.liferoad

/** Structural checks a decoded save must pass before it is trusted (docs section 3.6). Pure; false means "refuse the save". */
internal object LifeRoadValidation {
    fun ok(s: LifeRoadState): Boolean = try { check(s) } catch (_: Exception) { false }

    private fun check(s: LifeRoadState): Boolean {
        val g = LifeRoadBoard.of(s.config.size)
        val junior = s.config.junior
        val cap = s.config.size.turnCapPerPlayer
        val n = s.players.size
        val homeIds = ArrayList<String>()
        val jobsOut = ArrayList<String>()
        for (p in s.players) {
            if (!g.contains(p.nodeId)) return false
            if (p.branch != null && g.node(p.nodeId)?.next?.contains(p.branch) != true) return false
            if (p.loans > (if (junior) 0 else LifeRoadRules.MAX_LOANS)) return false
            if (p.homes.size > LifeRoadRules.HOUSE_CAP || p.familyPegs > LifeRoadRules.FAMILY_CAP) return false
            if (p.raises > LifeRoadRules.MAX_RAISES || p.luckyCoins > 9) return false
            if (p.cardMemories > 60 || p.turnsTaken > cap + 1) return false
            if (junior && (p.covers.isNotEmpty() || p.freshStarts > 0)) return false
            if (p.milestonesClaimed.any { it !in LifeRoadIndex.milestone } || p.milestonesClaimed.toSet().size != p.milestonesClaimed.size) return false
            if (p.jobId != null) {
                val j = LifeRoadIndex.job[p.jobId] ?: return false
                if (j.needsDegree && !p.degree) return false
                jobsOut += p.jobId
            }
            for (h in p.homes) {
                if (h.houseId !in LifeRoadIndex.house || h.paid < 0 || (h.soldFor ?: 0) < 0) return false
                homeIds += h.houseId
            }
            if (p.retired && p.lane == null) return false
            if (!p.retired && p.lane != null) return false
        }
        // retirement bookkeeping
        if (s.retiredCount != s.players.count { it.retired }) return false
        val orders = s.players.map { it.retireOrder }.filter { it > 0 }.sorted()
        if (orders != (1..orders.size).toList()) return false
        if (s.players.any { it.retireOrder > 0 && !it.retired }) return false
        if (s.players.any { it.lane == RetireLane.LATE && it.retireOrder != 0 }) return false
        // decks
        if (s.decks.jobs.size != 5) return false
        for ((i, pile) in s.decks.jobs.withIndex()) for (id in pile) {
            val j = LifeRoadIndex.job[id] ?: return false
            if (j.tier != i + 1) return false
            jobsOut += id
        }
        for (id in s.pending.offerIds.filter { LifeRoadIndex.job.containsKey(it) }) jobsOut += id
        if (jobsOut.size != jobsOut.toSet().size) return false
        if (s.decks.events.any { it !in LifeRoadIndex.event } || s.decks.events.toSet().size != s.decks.events.size) return false
        if (s.decks.family.any { it !in LifeRoadIndex.family } || s.decks.family.toSet().size != s.decks.family.size) return false
        if (s.decks.houseStock.any { it !in LifeRoadIndex.house }) return false
        homeIds += s.decks.houseStock
        if (s.phase == Phase.AWAIT_HOUSE) homeIds += s.pending.offerIds
        if (homeIds.size != homeIds.toSet().size) return false
        // phase and pending agree
        val me = s.players[s.currentSeat]
        val pend = s.pending
        if (me.retired && s.phase != Phase.SETTLING) return false
        when (s.phase) {
            Phase.START_FORK -> if (s.players.any { it.nodeId != LifeRoadBoardSpec.START_ID }) return false
            Phase.AWAIT_FORK -> {
                val f = pend.forkNodeId?.let { g.node(it) } ?: return false
                if (f.next.size < 2 || f.id != me.nodeId || pend.stepsLeft < 0) return false
            }
            Phase.AWAIT_JOB -> if (pend.offerIds.isEmpty() || pend.offerIds.any { it !in LifeRoadIndex.job }) return false
            Phase.AWAIT_HOUSE -> if (pend.offerIds.isEmpty() || pend.offerIds.any { it !in LifeRoadIndex.house }) return false
            Phase.AWAIT_CARD -> {
                val id = pend.cardId ?: return false
                val known = id == LifeRoadIndex.WHEEL_CARD || id in LifeRoadIndex.event || id in LifeRoadIndex.family ||
                    (id.startsWith(LifeRoadIndex.MS_PREFIX) && id.removePrefix(LifeRoadIndex.MS_PREFIX) in LifeRoadIndex.milestone)
                if (!known) return false
            }
            Phase.SETTLING -> if (!me.retired || pend.settleIndex !in 0 until me.homes.size) return false
            Phase.AWAIT_RESPIN -> if (pend.spin !in 1..LifeRoadRules.SPIN_MAX || me.luckyCoins <= 0) return false
            Phase.AWAIT_SPIN, Phase.AWAIT_COVER, Phase.AWAIT_BANK, Phase.AWAIT_MARKET -> {}
            else -> return false
        }
        if (s.phase == Phase.AWAIT_MARKET && me.homes.isEmpty()) return false
        if (s.phase != Phase.AWAIT_JOB && s.phase != Phase.AWAIT_HOUSE && pend.offerIds.isNotEmpty()) return false
        return n in 2..6
    }
}
