package com.gamesuite.games.propertytycoon

/**
 * The 40-tile ring as data (design doc, "Board spec"). GENERATED from the same table as the doc; edit the doc's
 * table and this list together. All names are invented for this game's own archipelago.
 */
object PtBoard {
    val tiles: List<Tile> = listOf(
        Tile(0, TileType.START, "pt_tile_00"),
        Tile(1, TileType.PROPERTY, "pt_tile_01", ColorGroup.CLAY, price = 60, buildCost = 40, rents = listOf(4, 20, 55, 150, 220, 290)),
        Tile(2, TileType.PROPERTY, "pt_tile_02", ColorGroup.CLAY, price = 70, buildCost = 40, rents = listOf(5, 25, 70, 190, 275, 360)),
        Tile(3, TileType.TIDE_CARD, "pt_tile_03"),
        Tile(4, TileType.TAX, "pt_tile_04", amount = 120),
        Tile(5, TileType.PROPERTY, "pt_tile_05", ColorGroup.MIST, price = 90, buildCost = 50, rents = listOf(6, 30, 85, 230, 330, 430)),
        Tile(6, TileType.TRANSIT, "pt_tile_06", price = 180),
        Tile(7, TileType.PROPERTY, "pt_tile_07", ColorGroup.MIST, price = 100, buildCost = 50, rents = listOf(7, 35, 100, 265, 385, 505)),
        Tile(8, TileType.GUILD_CARD, "pt_tile_08"),
        Tile(9, TileType.PROPERTY, "pt_tile_09", ColorGroup.REED, price = 130, buildCost = 80, rents = listOf(9, 45, 125, 340, 495, 650)),
        Tile(10, TileType.TIMEOUT, "pt_tile_10"),
        Tile(11, TileType.PROPERTY, "pt_tile_11", ColorGroup.REED, price = 140, buildCost = 80, rents = listOf(10, 50, 140, 380, 550, 720)),
        Tile(12, TileType.UTILITY, "pt_tile_12", price = 150),
        Tile(13, TileType.PROPERTY, "pt_tile_13", ColorGroup.LAGOON, price = 160, buildCost = 100, rents = listOf(11, 55, 155, 420, 605, 790)),
        Tile(14, TileType.PROPERTY, "pt_tile_14", ColorGroup.LAGOON, price = 170, buildCost = 100, rents = listOf(12, 60, 170, 455, 660, 865)),
        Tile(15, TileType.PROPERTY, "pt_tile_15", ColorGroup.LAGOON, price = 190, buildCost = 100, rents = listOf(14, 70, 195, 530, 770, 1010)),
        Tile(16, TileType.TRANSIT, "pt_tile_16", price = 180),
        Tile(17, TileType.GUILD_CARD, "pt_tile_17"),
        Tile(18, TileType.PROPERTY, "pt_tile_18", ColorGroup.PLUM, price = 210, buildCost = 120, rents = listOf(15, 75, 210, 570, 825, 1080)),
        Tile(19, TileType.TIDE_CARD, "pt_tile_19"),
        Tile(20, TileType.FREE_ANCHORAGE, "pt_tile_20"),
        Tile(21, TileType.PROPERTY, "pt_tile_21", ColorGroup.PLUM, price = 220, buildCost = 120, rents = listOf(16, 80, 225, 610, 880, 1150)),
        Tile(22, TileType.PROPERTY, "pt_tile_22", ColorGroup.PLUM, price = 240, buildCost = 120, rents = listOf(17, 85, 240, 645, 935, 1225)),
        Tile(23, TileType.UTILITY, "pt_tile_23", price = 150),
        Tile(24, TileType.PROPERTY, "pt_tile_24", ColorGroup.CORAL, price = 250, buildCost = 150, rents = listOf(18, 90, 250, 685, 990, 1295)),
        Tile(25, TileType.PROPERTY, "pt_tile_25", ColorGroup.CORAL, price = 270, buildCost = 150, rents = listOf(19, 95, 265, 720, 1045, 1370)),
        Tile(26, TileType.TRANSIT, "pt_tile_26", price = 180),
        Tile(27, TileType.PROPERTY, "pt_tile_27", ColorGroup.CORAL, price = 290, buildCost = 150, rents = listOf(21, 105, 295, 800, 1155, 1510)),
        Tile(28, TileType.TAX, "pt_tile_28", amount = 180),
        Tile(29, TileType.PROPERTY, "pt_tile_29", ColorGroup.GOLD, price = 310, buildCost = 180, rents = listOf(22, 110, 310, 835, 1210, 1585)),
        Tile(30, TileType.GO_TO_TIMEOUT, "pt_tile_30"),
        Tile(31, TileType.PROPERTY, "pt_tile_31", ColorGroup.GOLD, price = 330, buildCost = 180, rents = listOf(24, 120, 335, 910, 1320, 1730)),
        Tile(32, TileType.GUILD_CARD, "pt_tile_32"),
        Tile(33, TileType.PROPERTY, "pt_tile_33", ColorGroup.GOLD, price = 360, buildCost = 180, rents = listOf(26, 130, 365, 990, 1430, 1870)),
        Tile(34, TileType.TIDE_CARD, "pt_tile_34"),
        Tile(35, TileType.PROPERTY, "pt_tile_35", ColorGroup.OPAL, price = 380, buildCost = 220, rents = listOf(27, 135, 380, 1025, 1485, 1945)),
        Tile(36, TileType.TRANSIT, "pt_tile_36", price = 180),
        Tile(37, TileType.PROPERTY, "pt_tile_37", ColorGroup.OPAL, price = 400, buildCost = 220, rents = listOf(29, 145, 405, 1100, 1595, 2090)),
        Tile(38, TileType.PROPERTY, "pt_tile_38", ColorGroup.OPAL, price = 420, buildCost = 220, rents = listOf(30, 150, 420, 1140, 1650, 2160)),
        Tile(39, TileType.PROPERTY, "pt_tile_39", ColorGroup.OPAL, price = 460, buildCost = 220, rents = listOf(33, 165, 460, 1255, 1815, 2375))
    )

    fun tile(index: Int): Tile = tiles[Math.floorMod(index, PtRules.TILE_COUNT)]

    /** The tiles of [group] in ring order. */
    fun tilesOf(group: ColorGroup): List<Tile> = tiles.filter { it.group == group }

    /** Indexes of every tile of [type] in ring order. */
    fun indexesOf(type: TileType): List<Int> = tiles.filter { it.type == type }.map { it.index }

    /** The nearest tile of [type] strictly ahead of [from], wrapping around the ring. */
    fun nearestAhead(from: Int, type: TileType): Int {
        for (step in 1..PtRules.TILE_COUNT) {
            val i = (from + step) % PtRules.TILE_COUNT
            if (tiles[i].type == type) return i
        }
        return from
    }
}
