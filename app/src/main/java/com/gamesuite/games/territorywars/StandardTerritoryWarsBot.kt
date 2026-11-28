package com.gamesuite.games.territorywars

import com.gamesuite.core.CpuLevel
import kotlin.random.Random

/**
 * The three computer players of Territory Wars (design doc section 2.6). Every choice is made from the engine's own legal
 * actions, so a bot cannot break a rule, and it always returns an action (ending the attack or skipping the fortify when
 * it has nothing better) so a computer seat can never stall the table. Easy plays plain odds with some randomness; Medium
 * takes the best expected fights and reinforces its weakest border; Hard also weighs regions (finishing a region it nearly holds,
 * breaking a region a rival nearly holds) and keeps its armies where a neighbour threatens them.
 */
class StandardTerritoryWarsBot(private val engine: TerritoryWarsEngine = StandardTerritoryWarsEngine()) : TerritoryWarsBot {

    override fun chooseAction(state: TerritoryWarsState, level: CpuLevel, budget: ThinkBudget, random: Random): TerritoryWarsAction? {
        if (state.phase == TurnPhase.GAME_OVER) return null
        val legal = engine.legalActions(state)
        if (legal.isEmpty()) return null
        val pick = try { decide(state, level, random, legal) } catch (_: Exception) { null }
        if (pick != null && isLegal(pick, legal)) return pick
        return fallback(legal)
    }

    private fun isLegal(a: TerritoryWarsAction, legal: List<TerritoryWarsAction>): Boolean = when (a) {
        is TerritoryWarsAction.PlaceArmies -> legal.any { it is TerritoryWarsAction.PlaceArmies && it.territory == a.territory }
        is TerritoryWarsAction.Fortify -> legal.any { it is TerritoryWarsAction.Fortify && it.from == a.from && it.to == a.to }
        is TerritoryWarsAction.Attack -> legal.any { it is TerritoryWarsAction.Attack && it.from == a.from && it.to == a.to }
        is TerritoryWarsAction.MoveIn -> legal.any { it is TerritoryWarsAction.MoveIn }
        else -> a in legal
    }

    private fun fallback(legal: List<TerritoryWarsAction>): TerritoryWarsAction {
        for (a in legal) if (a is TerritoryWarsAction.TurnInCards) return a
        for (a in legal) if (a is TerritoryWarsAction.MoveIn) return a
        if (TerritoryWarsAction.EndAttack in legal) return TerritoryWarsAction.EndAttack
        if (TerritoryWarsAction.SkipFortify in legal) return TerritoryWarsAction.SkipFortify
        if (TerritoryWarsAction.AutoPlaceRemaining in legal) return TerritoryWarsAction.AutoPlaceRemaining
        return legal.first()
    }

    private fun enemyNeighbours(map: WarMap, s: TerritoryWarsState, t: Int, seat: Int) = map.neighbourIndices(t).filter { s.owners[it] != seat }

    /** How much we want [t] to be reinforced (more enemy armies next door = more need). */
    private fun need(map: WarMap, s: TerritoryWarsState, t: Int, seat: Int, hard: Boolean): Int {
        val enemies = enemyNeighbours(map, s, t, seat)
        if (enemies.isEmpty()) return 0
        var v = enemies.sumOf { s.armies[it] } - s.armies[t] + enemies.size * 2
        if (hard) {
            val region = map.regions.firstOrNull { map.territories[t].id in it.territoryIds }
            if (region != null) {
                val held = region.territoryIds.count { map.indexOf(it).let { i -> s.owners[i] == seat } }
                v += held * region.bonus
            }
        }
        return v
    }

    private fun decide(s: TerritoryWarsState, level: CpuLevel, rnd: Random, legal: List<TerritoryWarsAction>): TerritoryWarsAction? {
        val map = TerritoryWarsMaps.byId(s.mapId) ?: TerritoryWarsMaps.STANDARD
        val seat = s.currentSeat
        val hard = level == CpuLevel.HARD || level == CpuLevel.EXPERT
        val easy = level == CpuLevel.EASY
        val own = s.owners.indices.filter { s.owners[it] == seat }
        return when (s.phase) {
            TurnPhase.CARDS_FORCED -> legal.filterIsInstance<TerritoryWarsAction.TurnInCards>().firstOrNull()
            TurnPhase.PLACE_INITIAL, TurnPhase.REINFORCE -> {
                val turnIn = legal.filterIsInstance<TerritoryWarsAction.TurnInCards>().firstOrNull()
                if (s.phase == TurnPhase.REINFORCE && turnIn != null && !easy) return turnIn
                if (s.armiesToPlace > 0) {
                    if (easy) return TerritoryWarsAction.AutoPlaceRemaining
                    val best = own.maxByOrNull { need(map, s, it, seat, hard) } ?: return TerritoryWarsAction.AutoPlaceRemaining
                    return TerritoryWarsAction.PlaceArmies(best, s.armiesToPlace)
                }
                attackChoice(s, map, level, rnd, legal)
            }
            TurnPhase.ATTACK -> attackChoice(s, map, level, rnd, legal)
            TurnPhase.MOVE_IN -> {
                val m = s.pendingMoveIn ?: return null
                val srcThreat = enemyNeighbours(map, s, m.from, seat).isNotEmpty()
                val dstThreat = enemyNeighbours(map, s, m.to, seat).isNotEmpty()
                val count = when {
                    easy -> m.minMove
                    !srcThreat && dstThreat -> m.maxMove
                    !dstThreat -> m.minMove
                    else -> (m.minMove + m.maxMove) / 2
                }
                TerritoryWarsAction.MoveIn(count.coerceIn(m.minMove, m.maxMove))
            }
            TurnPhase.FORTIFY -> {
                if (easy) return TerritoryWarsAction.SkipFortify
                val moves = legal.filterIsInstance<TerritoryWarsAction.Fortify>()
                val interior = moves.filter { enemyNeighbours(map, s, it.from, seat).isEmpty() && enemyNeighbours(map, s, it.to, seat).isNotEmpty() }
                val best = interior.maxByOrNull { s.armies[it.from] + need(map, s, it.to, seat, hard) }
                best ?: TerritoryWarsAction.SkipFortify
            }
            else -> null
        }
    }

    private fun attackChoice(s: TerritoryWarsState, map: WarMap, level: CpuLevel, rnd: Random, legal: List<TerritoryWarsAction>): TerritoryWarsAction {
        val seat = s.currentSeat
        val easy = level == CpuLevel.EASY
        val hard = level == CpuLevel.HARD || level == CpuLevel.EXPERT
        val attacks = legal.filterIsInstance<TerritoryWarsAction.Attack>()
        val scored = attacks.map { a ->
            val mine = s.armies[a.from]
            val theirs = s.armies[a.to]
            var score = (mine - 1).toDouble() - theirs * 1.2
            if (hard) {
                val region = map.regions.firstOrNull { map.territories[a.to].id in it.territoryIds }
                if (region != null) {
                    val ownedInRegion = region.territoryIds.count { map.indexOf(it).let { i -> s.owners[i] == seat } }
                    if (ownedInRegion == region.territoryIds.size - 1) score += region.bonus * 2.0
                    val rivals = region.territoryIds.mapNotNull { map.indexOf(it).let { i -> s.owners[i] } }.toSet()
                    if (rivals.size == 1 && seat !in rivals) score += region.bonus.toDouble()
                }
            }
            a to score
        }
        val threshold = if (easy) 1.5 else 0.0
        val candidates = scored.filter { it.second > threshold }
        if (candidates.isEmpty()) return TerritoryWarsAction.EndAttack
        // keep the war moving without letting a bot run forever: Easy stops sometimes, every level stops once it has conquered plenty
        if (easy && rnd.nextInt(100) < 25) return TerritoryWarsAction.EndAttack
        val best = if (easy) candidates[rnd.nextInt(candidates.size)] else candidates.maxByOrNull { it.second }!!
        return TerritoryWarsAction.AttackUntilDone(best.first.from, best.first.to)
    }
}
