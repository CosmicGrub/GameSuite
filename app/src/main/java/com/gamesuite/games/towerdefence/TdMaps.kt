package com.gamesuite.games.towerdefence

/**
 * The authored open-field "Castle Siege" maps. Original layouts; all attack from the west edge
 * toward the player's castle on the east edge. Roads are a cost bias, not walls: enemies prefer
 * them but leave them when towers block the way. Every spawn is verified to reach the gate by the
 * tests (TdNavTest), and the route cap is the standard 2.5 on all of them.
 */
object TdMaps {
    /** Open meadow with a winding road, a pond and two rock fields. One entrance. */
    val CASTLE_PASS: TdTerrain = TdTerrainBuilder("castle-pass").apply {
        castle(10, 17)
        spawn(14)
        waterDisc(19f, 20f, 4f)          // pond
        block(26, 2, 31, 6)              // north rocks
        block(30, 21, 35, 25)            // south rocks
        rough(8, 4, 15, 8)               // bramble
        rough(12, 19, 18, 24)            // marsh edge
        road(listOf(1 to 14, 12 to 14, 20 to 9, 32 to 9, 38 to 14, 43 to 14))
    }.build()

    /** A river splits the field; a bridge in the north, a muddy ford in the south. Two entrances. */
    val RIVER_BEND: TdTerrain = TdTerrainBuilder("river-bend").apply {
        castle(10, 17)
        spawn(6)
        spawn(21)
        water(22, 0, 25, 27)             // river
        bridge(22, 5, 25, 9)             // stone bridge
        rough(22, 18, 25, 22)            // ford: passable mud, buildable
        for (y in 18..22) for (x in 22..25) { cost[TdGrid.idx(x, y)] = TdTerrainBuilder.ROUGH; buildable[TdGrid.idx(x, y)] = true }
        blockDisc(34f, 6f, 3f)           // boulder field
        blockDisc(10f, 12f, 2.5f)        // old well ruins
        road(listOf(1 to 6, 22 to 7, 30 to 10, 43 to 13))
        road(listOf(1 to 21, 18 to 21, 30 to 17, 43 to 15), 1)
    }.build()

    /** A long wall with two gaps funnels two lanes toward the keep. Two entrances. */
    val TWIN_GATES: TdTerrain = TdTerrainBuilder("twin-gates").apply {
        castle(10, 17)
        spawn(6)
        spawn(21)
        block(20, 0, 23, 3)              // wall, north part
        block(20, 8, 23, 19)             // wall, middle part
        block(20, 24, 23, 27)            // wall, south part
        block(32, 12, 35, 15)            // pillar behind the wall
        rough(8, 11, 14, 17)             // trampled meadow
        road(listOf(1 to 6, 21 to 5, 30 to 9, 43 to 12))
        road(listOf(1 to 21, 21 to 22, 30 to 18, 43 to 15))
    }.build()

    val ALL: List<TdTerrain> = listOf(CASTLE_PASS, RIVER_BEND, TWIN_GATES)

    fun byId(id: String): TdTerrain? = ALL.firstOrNull { it.id == id }
}
