package com.gamesuite.games.botbastion

import java.io.File

internal fun bbModuleFile(relative: String): File {
    val here = File("src/main/$relative")
    if (here.exists()) return here
    return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
}

/** Every map in the bundled JSON, playable or not. */
internal fun allMaps(): List<MapDef> = BbMaps.parse(bbModuleFile("assets/bot_bastion/maps.json").readText())

internal fun shippedMaps(): List<MapDef> = BbMaps.playable(allMaps())

internal fun shipped(id: String): MapDef = shippedMaps().first { it.id == id }

/**
 * A hand-made test arena: one straight lane along y = 4.5 from x = -0.3 to the Core at x = 15, with six nodes
 * beside it and a seventh (index 6) only 0.5 u from the lane. Not a shipped map (it does not satisfy the node rules), so crafted layouts stay easy to reason about.
 */
internal fun flatMap(lanes: Int = 1): MapDef {
    val ls = List(lanes) { i -> Lane(listOf(Pt(-0.3, 4.5 + i), Pt(15.0, 4.5 + i))) }
    return MapDef(
        "flat", 1, 1.0, 0, Pt(15.0, 4.5), ls,
        listOf(Pt(4.0, 3.2), Pt(8.0, 3.2), Pt(12.0, 3.2), Pt(4.0, 6.5), Pt(8.0, 6.5), Pt(12.0, 6.5), Pt(8.0, 4.0)), emptyList()
    )
}

internal fun engine(map: MapDef = flatMap(), tier: Tier = Tier.CAPTAIN, players: Int = 1, seed: Long = 1L) =
    BbEngine(map, tier, players, seed)

/** Runs [seconds] of game time in one go. */
internal fun BbEngine.run(seconds: Double) {
    var left = seconds
    while (left > 0 && !over) {
        val d = minOf(left, 0.25)
        advance(d)
        left -= d
    }
}

/** Gives every player [n] Scrap so a test is not about money. */
internal fun BbEngine.rich(n: Int = 100_000): BbEngine {
    for (p in players) p.scrap = n
    return this
}

/** An enemy [dist] units along lane 0, with the given hp. */
internal fun BbEngine.put(type: EnemyType, dist: Double, hp: Double = type.hp, lane: Int = 0): BbEnemy =
    addEnemy(type, lane, dist, hp, 1.0, false)
