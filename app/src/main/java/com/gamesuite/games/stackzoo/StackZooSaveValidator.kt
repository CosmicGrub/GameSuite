package com.gamesuite.games.stackzoo

import com.gamesuite.settings.CpuDifficulty

/**
 * Validates a [ZooSave] before anything is restored: ranges, enum ids, finite numbers, unique piece ids and bounded
 * counters. Pure and total: it never throws and returns false for anything it does not recognise.
 */
internal object ZooSaveValidator {
    private fun finite(v: Double, limit: Double) = v == v && Math.abs(v) <= limit

    fun validate(save: ZooSave, seatCount: Int): Boolean = try {
        check(save, seatCount)
    } catch (_: Exception) {
        false
    }

    private fun check(s: ZooSave, seatCount: Int): Boolean {
        if (s.version != 1 || s.physicsVersion != ZooPhysics.PHYSICS_VERSION) return false
        if (seatCount !in 1..4 || s.playerIds.size != seatCount || s.playerIds.toSet().size != seatCount) return false
        if (s.mode !in setOf("coop", "turns", "solo")) return false
        if (s.goal !in setOf("little", "medium", "tall")) return false
        if (s.length !in setOf("quick", "standard", "long")) return false
        if (!(s.assist == "none" || s.assist == "all" || Regex("p[1-4]").matches(s.assist))) return false
        if (CpuDifficulty.values().none { it.name == s.difficulty }) return false
        if (s.seed == 0L) return false
        if (s.rngCalls !in 0L..10_000_000L) return false
        if (s.stepIndex !in 0L..(1L shl 40)) return false
        if (s.turnNumber !in 0..100_000) return false
        if (s.phase !in setOf("CHOOSING", "SETTLING", "TURN_RESULT")) return false
        if (s.activeSeat !in 0 until seatCount) return false
        if (s.lives !in 0..5 || s.holdSteps !in 0..100_000) return false
        if (s.settleSteps !in 0..(ZooPhysics.SETTLE_MAX_STEPS + 1) || s.resultSteps !in 0..100_000) return false
        if (s.fellThisTurn !in 0..10_000 || s.fellLastTurn !in 0..ZooPhysics.MAX_BODIES) return false
        if (!finite(s.baseAngle, ZooPhysics.BASE_MAX_ANGLE + 1e-9) || !finite(s.baseOmega, 100.0) || s.baseRest !in 0..1000) return false
        if (!finite(s.tallestHeight, 100.0) || s.tallestHeight < 0.0) return false
        if (s.tallestSeat !in -1 until seatCount || s.pendingWinner !in -1 until seatCount) return false

        val mode = ZooMode.fromId(s.mode)
        val handSize = ZooLength.fromId(s.length).handSize
        val expectedPieces = if (mode == ZooMode.SOLO_BALANCE) ZooEngine.ANIMALS else 2 * ZooEngine.ANIMALS
        val used = when (mode) {
            ZooMode.SOLO_BALANCE -> ZooEngine.ANIMALS
            ZooMode.COOP_TOWER -> ZooGoal.fromId(s.goal).bag
            ZooMode.TAKE_TURNS -> seatCount * handSize
        }
        if (s.pieceAnimals.size != expectedPieces) return false
        val copies = IntArray(ZooEngine.ANIMALS)
        for (a in s.pieceAnimals) {
            if (a !in 0 until ZooEngine.ANIMALS) return false
            copies[a]++
        }
        val maxCopies = if (mode == ZooMode.SOLO_BALANCE) 1 else ZooRules.BAG_COPIES
        if (copies.any { it > maxCopies }) return false
        if (s.bagTotal != used) return false
        if (s.hands.size != seatCount) return false
        if (mode != ZooMode.TAKE_TURNS && s.hands.any { it.isNotEmpty() }) return false
        if (mode == ZooMode.TAKE_TURNS && s.bag.isNotEmpty()) return false
        if (s.placements.size != seatCount || s.clean.size != seatCount) return false
        for (i in 0 until seatCount) {
            if (s.placements[i] !in 0..100_000 || s.clean[i] !in 0..s.placements[i]) return false
        }
        if (s.bodies.size > ZooPhysics.MAX_BODIES || s.bag.size > ZooPhysics.MAX_BODIES) return false

        val seen = HashSet<Int>()
        fun claim(id: Int): Boolean = id in 0 until used && seen.add(id)
        for (id in s.bag) if (!claim(id)) return false
        for (h in s.hands) {
            if (h.size > ZooPhysics.MAX_BODIES) return false
            for (id in h) if (!claim(id)) return false
        }
        for (b in s.bodies) {
            if (!claim(b.pieceId)) return false
            if (b.animalIndex != s.pieceAnimals[b.pieceId]) return false
            if (b.owner !in 0 until seatCount) return false
            if (!finite(b.x, 100.0) || !finite(b.y, 200.0) || b.y < ZooPhysics.REMOVE_Y - 1.0) return false
            if (!finite(b.angle, 1000.0) || !finite(b.vx, 1000.0) || !finite(b.vy, 1000.0) || !finite(b.omega, 1000.0)) return false
            if (b.restSteps !in 0..1000) return false
        }
        if (s.pendingFallen.size > ZooPhysics.MAX_BODIES || s.pendingFallen.toSet().size != s.pendingFallen.size) return false
        for (id in s.pendingFallen) if (id !in 0 until used) return false
        if (s.releases.size > ZooEngine.MAX_RELEASES) return false
        var last = 0L
        for (r in s.releases) {
            if (r.stepIndex < last || r.stepIndex > s.stepIndex) return false
            last = r.stepIndex
            if (r.pieceId !in 0 until used) return false
            if (r.xQ !in -ZooPhysics.X_LIMIT_Q..ZooPhysics.X_LIMIT_Q) return false
            if (r.angleQ !in -ZooPhysics.ANGLE_LIMIT_Q..ZooPhysics.ANGLE_LIMIT_Q) return false
        }
        return true
    }
}
