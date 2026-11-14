package com.gamesuite.games.territorywars

/*
 * The invented world of Territory Wars: "Maravel", 36 territories in 6 regions, authored fresh for this app.
 * Every name, border and number below is original (docs/design/boardgames/territory-wars.md sections 1.7 and 2.5).
 * GENERATED once from the design lattice (6 x 6 jittered quad cells plus three sea links) and then frozen:
 * ids and adjacency are content, so they must never change after release (saved games and online rooms store them).
 * Art may replace the polygons later without touching ids or adjacency.
 *
 * Coordinates are map units on a [TerritoryWarsMaps.WIDTH] x [TerritoryWarsMaps.HEIGHT] canvas; the screen scales them.
 */
object TerritoryWarsMaps {
    const val WIDTH = 1200f
    const val HEIGHT = 780f

    /** The 7 x 7 lattice vertices (row-major); territory polygons index into this table. */
    private val VERTS: List<MapPoint> = listOf(
        MapPoint(30f, 30f), MapPoint(219f, 30f), MapPoint(433f, 30f), MapPoint(594f, 30f), MapPoint(768f, 30f), MapPoint(969f, 30f), MapPoint(1170f, 30f),
        MapPoint(30f, 160f), MapPoint(246f, 163f), MapPoint(422f, 157f), MapPoint(611f, 160f), MapPoint(799f, 151f), MapPoint(968f, 165f), MapPoint(1170f, 165f),
        MapPoint(30f, 254f), MapPoint(223f, 254f), MapPoint(422f, 279f), MapPoint(588f, 278f), MapPoint(770f, 282f), MapPoint(1007f, 257f), MapPoint(1170f, 278f),
        MapPoint(30f, 387f), MapPoint(201f, 406f), MapPoint(391f, 379f), MapPoint(597f, 394f), MapPoint(787f, 388f), MapPoint(958f, 374f), MapPoint(1170f, 401f),
        MapPoint(30f, 491f), MapPoint(233f, 525f), MapPoint(423f, 492f), MapPoint(609f, 502f), MapPoint(796f, 497f), MapPoint(977f, 510f), MapPoint(1170f, 507f),
        MapPoint(30f, 635f), MapPoint(195f, 616f), MapPoint(389f, 618f), MapPoint(602f, 628f), MapPoint(794f, 641f), MapPoint(962f, 643f), MapPoint(1170f, 646f),
        MapPoint(30f, 750f), MapPoint(237f, 750f), MapPoint(429f, 750f), MapPoint(592f, 750f), MapPoint(807f, 750f), MapPoint(990f, 750f), MapPoint(1170f, 750f)
    )

    private fun terr(
        id: String, name: String, regionId: String, cx: Int, cy: Int, poly: IntArray, neighbours: List<String>
    ): Territory = Territory(
        id = id, name = name, regionId = regionId, neighbours = neighbours,
        centroid = MapPoint(cx.toFloat(), cy.toFloat()), polygon = poly.map { VERTS[it] }
    )

    val REGIONS: List<Region> = listOf(
        Region("cinderwake", "Cinderwake", 2, listOf("ashgate", "kilnmoor", "cinder-ridge", "furnace-vale", "smolder-bay", "charwick")),
        Region("glasswater", "Glasswater", 3, listOf("frostmere", "mirrorlake", "saltspire", "tidewhistle", "kelp-harbour", "brinehollow")),
        Region("heartmark", "Heartmark", 5, listOf("crownfield", "millstead", "goldhaven", "rivermeet", "thornwick", "barleyford")),
        Region("dunescar", "Dunescar", 4, listOf("sunspire", "duskreach", "sandpass", "mirage-flats", "oasis-gate", "scorchwind")),
        Region("mistwood", "Mistwood", 3, listOf("fernhollow", "mossgate", "hushpine", "lanternfen", "owlmere", "dewcrest")),
        Region("stormcrown", "Stormcrown", 3, listOf("gale-point", "thunderlip", "coral-sound", "reefhaven", "squallrock", "pearl-strand"))
    )

    val SEA_LINKS: List<SeaLink> = listOf(
        SeaLink("ashgate", "saltspire", "Northern Strait"),
        SeaLink("fernhollow", "oasis-gate", "Middle Sound"),
        SeaLink("owlmere", "pearl-strand", "Southern Passage")
    )

    /** The full 36-territory map (Standard and Long games; Blitz with 5 or 6 seats). */
    val STANDARD: WarMap = WarMap(
        id = "maravel-36", name = "Maravel", width = WIDTH, height = HEIGHT,
        territories = listOf(
            terr("ashgate", "Ashgate", "cinderwake", 131, 96, intArrayOf(0, 1, 8, 7),
                listOf("kilnmoor", "saltspire", "furnace-vale")),
            terr("kilnmoor", "Kilnmoor", "cinderwake", 330, 95, intArrayOf(1, 2, 9, 8),
                listOf("ashgate", "cinder-ridge", "smolder-bay")),
            terr("cinder-ridge", "Cinder Ridge", "cinderwake", 515, 94, intArrayOf(2, 3, 10, 9),
                listOf("kilnmoor", "frostmere", "crownfield")),
            terr("frostmere", "Frostmere", "glasswater", 693, 93, intArrayOf(3, 4, 11, 10),
                listOf("cinder-ridge", "mirrorlake", "tidewhistle")),
            terr("mirrorlake", "Mirrorlake", "glasswater", 876, 94, intArrayOf(4, 5, 12, 11),
                listOf("frostmere", "saltspire", "kelp-harbour")),
            terr("saltspire", "Saltspire", "glasswater", 1069, 98, intArrayOf(5, 6, 13, 12),
                listOf("ashgate", "mirrorlake", "brinehollow")),
            terr("furnace-vale", "Furnace Vale", "cinderwake", 132, 208, intArrayOf(7, 8, 15, 14),
                listOf("ashgate", "smolder-bay", "charwick")),
            terr("smolder-bay", "Smolder Bay", "cinderwake", 328, 213, intArrayOf(8, 9, 16, 15),
                listOf("kilnmoor", "furnace-vale", "crownfield", "millstead")),
            terr("crownfield", "Crownfield", "heartmark", 511, 218, intArrayOf(9, 10, 17, 16),
                listOf("cinder-ridge", "smolder-bay", "tidewhistle", "goldhaven")),
            terr("tidewhistle", "Tidewhistle", "glasswater", 692, 218, intArrayOf(10, 11, 18, 17),
                listOf("frostmere", "crownfield", "kelp-harbour", "rivermeet")),
            terr("kelp-harbour", "Kelp Harbour", "glasswater", 886, 214, intArrayOf(11, 12, 19, 18),
                listOf("mirrorlake", "tidewhistle", "brinehollow", "sunspire")),
            terr("brinehollow", "Brinehollow", "glasswater", 1079, 216, intArrayOf(12, 13, 20, 19),
                listOf("saltspire", "kelp-harbour", "duskreach")),
            terr("charwick", "Charwick", "cinderwake", 121, 325, intArrayOf(14, 15, 22, 21),
                listOf("furnace-vale", "millstead", "fernhollow")),
            terr("millstead", "Millstead", "heartmark", 309, 330, intArrayOf(15, 16, 23, 22),
                listOf("smolder-bay", "charwick", "goldhaven", "thornwick")),
            terr("goldhaven", "Goldhaven", "heartmark", 500, 332, intArrayOf(16, 17, 24, 23),
                listOf("crownfield", "millstead", "rivermeet", "barleyford")),
            terr("rivermeet", "Rivermeet", "heartmark", 686, 336, intArrayOf(17, 18, 25, 24),
                listOf("tidewhistle", "goldhaven", "sunspire", "sandpass")),
            terr("sunspire", "Sunspire", "dunescar", 880, 325, intArrayOf(18, 19, 26, 25),
                listOf("kelp-harbour", "rivermeet", "duskreach", "mirage-flats")),
            terr("duskreach", "Duskreach", "dunescar", 1076, 328, intArrayOf(19, 20, 27, 26),
                listOf("brinehollow", "sunspire", "oasis-gate")),
            terr("fernhollow", "Fernhollow", "mistwood", 124, 452, intArrayOf(21, 22, 29, 28),
                listOf("charwick", "thornwick", "oasis-gate", "mossgate")),
            terr("thornwick", "Thornwick", "heartmark", 312, 450, intArrayOf(22, 23, 30, 29),
                listOf("millstead", "fernhollow", "barleyford", "hushpine")),
            terr("barleyford", "Barleyford", "heartmark", 505, 442, intArrayOf(23, 24, 31, 30),
                listOf("goldhaven", "thornwick", "sandpass", "lanternfen")),
            terr("sandpass", "Sandpass", "dunescar", 697, 445, intArrayOf(24, 25, 32, 31),
                listOf("rivermeet", "barleyford", "mirage-flats", "gale-point")),
            terr("mirage-flats", "Mirage Flats", "dunescar", 880, 442, intArrayOf(25, 26, 33, 32),
                listOf("sunspire", "sandpass", "oasis-gate", "scorchwind")),
            terr("oasis-gate", "Oasis Gate", "dunescar", 1069, 448, intArrayOf(26, 27, 34, 33),
                listOf("duskreach", "fernhollow", "mirage-flats", "thunderlip")),
            terr("mossgate", "Mossgate", "mistwood", 122, 567, intArrayOf(28, 29, 36, 35),
                listOf("fernhollow", "hushpine", "owlmere")),
            terr("hushpine", "Hushpine", "mistwood", 310, 563, intArrayOf(29, 30, 37, 36),
                listOf("thornwick", "mossgate", "lanternfen", "dewcrest")),
            terr("lanternfen", "Lanternfen", "mistwood", 506, 560, intArrayOf(30, 31, 38, 37),
                listOf("barleyford", "hushpine", "gale-point", "coral-sound")),
            terr("gale-point", "Gale Point", "stormcrown", 700, 567, intArrayOf(31, 32, 39, 38),
                listOf("sandpass", "lanternfen", "scorchwind", "reefhaven")),
            terr("scorchwind", "Scorchwind", "dunescar", 882, 573, intArrayOf(32, 33, 40, 39),
                listOf("mirage-flats", "gale-point", "thunderlip", "squallrock")),
            terr("thunderlip", "Thunderlip", "stormcrown", 1070, 576, intArrayOf(33, 34, 41, 40),
                listOf("oasis-gate", "scorchwind", "pearl-strand")),
            terr("owlmere", "Owlmere", "mistwood", 123, 688, intArrayOf(35, 36, 43, 42),
                listOf("mossgate", "dewcrest", "pearl-strand")),
            terr("dewcrest", "Dewcrest", "mistwood", 312, 684, intArrayOf(36, 37, 44, 43),
                listOf("hushpine", "owlmere", "coral-sound")),
            terr("coral-sound", "Coral Sound", "stormcrown", 503, 686, intArrayOf(37, 38, 45, 44),
                listOf("lanternfen", "dewcrest", "reefhaven")),
            terr("reefhaven", "Reefhaven", "stormcrown", 699, 692, intArrayOf(38, 39, 46, 45),
                listOf("gale-point", "coral-sound", "squallrock")),
            terr("squallrock", "Squallrock", "stormcrown", 888, 696, intArrayOf(39, 40, 47, 46),
                listOf("scorchwind", "reefhaven", "pearl-strand")),
            terr("pearl-strand", "Pearl Strand", "stormcrown", 1073, 697, intArrayOf(40, 41, 48, 47),
                listOf("thunderlip", "owlmere", "squallrock"))
        ),
        regions = REGIONS,
        seaLinks = SEA_LINKS
    )

    /** Blitz map, west half: Cinderwake, Heartmark, Mistwood (18 territories). */
    val BLITZ_WEST: WarMap = STANDARD.subMap("maravel-west-18", "Maravel West", setOf("cinderwake", "heartmark", "mistwood"))

    /** Blitz map, east half: Glasswater, Dunescar, Stormcrown (18 territories). */
    val BLITZ_EAST: WarMap = STANDARD.subMap("maravel-east-18", "Maravel East", setOf("glasswater", "dunescar", "stormcrown"))

    val ALL: List<WarMap> = listOf(STANDARD, BLITZ_WEST, BLITZ_EAST)

    fun byId(id: String): WarMap? = ALL.firstOrNull { it.id == id }
}
