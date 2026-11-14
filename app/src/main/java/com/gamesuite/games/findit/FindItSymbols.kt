package com.gamesuite.games.findit

/**
 * The 57 original pictures. Order matters: the first 13 are the TINY deck, the first 31 the LITTLE deck, all 57
 * the STANDARD deck (see [prefix]). Every picture is drawn in code from primitives (see each recipe) and has its own
 * EN and ES name string `find_it_sym_<id>` for TalkBack. No picture copies any published game's artwork.
 */
object FindItSymbols {
    val all: List<FindItSymbol> = listOf(
        FindItSymbol(0, "sunflower", "find_it_sym_sunflower", FindItColour.SUN, "brown disc, ten petal ellipses, short stem"),
        FindItSymbol(1, "butterfly", "find_it_sym_butterfly", FindItColour.GRAPE, "two rounded wing pairs, thin body, two antennae"),
        FindItSymbol(2, "goldfish", "find_it_sym_goldfish", FindItColour.CORAL, "teardrop body, fan tail, round eye"),
        FindItSymbol(3, "mushroom", "find_it_sym_mushroom", FindItColour.BERRY, "half-disc cap with dots, rounded stalk"),
        FindItSymbol(4, "umbrella", "find_it_sym_umbrella", FindItColour.SKY, "scalloped half-disc, hooked handle"),
        FindItSymbol(5, "kite", "find_it_sym_kite", FindItColour.CORAL, "diamond, cross spars, wavy tail with bows"),
        FindItSymbol(6, "teapot", "find_it_sym_teapot", FindItColour.TEAL, "squat body, spout, handle loop, lid knob"),
        FindItSymbol(7, "cupcake", "find_it_sym_cupcake", FindItColour.BERRY, "pleated trapezoid cup, swirl top, cherry"),
        FindItSymbol(8, "sailboat", "find_it_sym_sailboat", FindItColour.SKY, "hull trapezoid, two triangle sails, mast"),
        FindItSymbol(9, "rocket", "find_it_sym_rocket", FindItColour.CORAL, "capsule body, nose cone, two fins, window"),
        FindItSymbol(10, "acorn", "find_it_sym_acorn", FindItColour.CLAY, "oval nut, cross-hatched cap, tiny stem"),
        FindItSymbol(11, "cherries", "find_it_sym_cherries", FindItColour.BERRY, "two discs on curved stems, one leaf"),
        FindItSymbol(12, "bee", "find_it_sym_bee", FindItColour.SUN, "striped ellipse body, two wing ovals, stinger"),
        FindItSymbol(13, "balloon", "find_it_sym_balloon", FindItColour.GRAPE, "teardrop envelope with stripes, basket, ropes"),
        FindItSymbol(14, "icecream", "find_it_sym_icecream", FindItColour.SUN, "waffle triangle cone, two scoops"),
        FindItSymbol(15, "windmill", "find_it_sym_windmill", FindItColour.CLAY, "tapered tower, four sail blades on hub"),
        FindItSymbol(16, "crab", "find_it_sym_crab", FindItColour.CORAL, "flat oval body, two claws, six leg strokes"),
        FindItSymbol(17, "hedgehog", "find_it_sym_hedgehog", FindItColour.CLAY, "dome of spikes (triangle fringe), snout, eye"),
        FindItSymbol(18, "rainbow", "find_it_sym_rainbow", FindItColour.SKY, "four concentric half-rings, two cloud ends"),
        FindItSymbol(19, "lighthouse", "find_it_sym_lighthouse", FindItColour.CORAL, "striped tapered tower, lamp room, light beams"),
        FindItSymbol(20, "bicycle", "find_it_sym_bicycle", FindItColour.TEAL, "two rings, triangle frame, handlebar, saddle"),
        FindItSymbol(21, "seashell", "find_it_sym_seashell", FindItColour.BERRY, "fan with radial ridges, scalloped edge"),
        FindItSymbol(22, "pear", "find_it_sym_pear", FindItColour.LEAF, "two stacked ovals, stem, leaf"),
        FindItSymbol(23, "drum", "find_it_sym_drum", FindItColour.CORAL, "cylinder, zigzag tension cords, two sticks"),
        FindItSymbol(24, "octopus", "find_it_sym_octopus", FindItColour.GRAPE, "round head, eight curled arm strokes, eyes"),
        FindItSymbol(25, "lantern", "find_it_sym_lantern", FindItColour.SUN, "ribbed ellipse, top and bottom caps, tassel"),
        FindItSymbol(26, "pinecone", "find_it_sym_pinecone", FindItColour.CLAY, "oval of overlapping scale arcs, stalk"),
        FindItSymbol(27, "mitten", "find_it_sym_mitten", FindItColour.SKY, "rounded hand, thumb, striped cuff"),
        FindItSymbol(28, "crown", "find_it_sym_crown", FindItColour.SUN, "band with three points, three gems"),
        FindItSymbol(29, "cheese", "find_it_sym_cheese", FindItColour.SUN, "triangle prism side, round holes"),
        FindItSymbol(30, "starfish", "find_it_sym_starfish", FindItColour.CORAL, "five-arm star with dotted ridges"),
        FindItSymbol(31, "trumpet", "find_it_sym_trumpet", FindItColour.SUN, "tube loop, flared bell, three valves"),
        FindItSymbol(32, "volcano", "find_it_sym_volcano", FindItColour.CORAL, "cone, crater, lava drips, smoke puffs"),
        FindItSymbol(33, "paintbrush", "find_it_sym_paintbrush", FindItColour.TEAL, "slanted handle, ferrule band, tapered bristles"),
        FindItSymbol(34, "boot", "find_it_sym_boot", FindItColour.CLAY, "L-shaped upper, sole, pull tab"),
        FindItSymbol(35, "parrot", "find_it_sym_parrot", FindItColour.LEAF, "curved beak, round head, long tail feathers"),
        FindItSymbol(36, "pretzel", "find_it_sym_pretzel", FindItColour.CLAY, "looped rope shape with salt dots"),
        FindItSymbol(37, "compass", "find_it_sym_compass", FindItColour.TEAL, "ring, four-point needle star, N tick"),
        FindItSymbol(38, "hourglass", "find_it_sym_hourglass", FindItColour.SUN, "two triangles, frame caps, falling sand line"),
        FindItSymbol(39, "gem", "find_it_sym_gem", FindItColour.SKY, "faceted hexagon with inner triangles"),
        FindItSymbol(40, "bell", "find_it_sym_bell", FindItColour.SUN, "flared dome, clapper, top loop"),
        FindItSymbol(41, "castle", "find_it_sym_castle", FindItColour.GRAPE, "block wall, two towers, arch door, flags"),
        FindItSymbol(42, "seahorse", "find_it_sym_seahorse", FindItColour.TEAL, "curled tail spiral, snout, belly ridges"),
        FindItSymbol(43, "caterpillar", "find_it_sym_caterpillar", FindItColour.LEAF, "five overlapping discs, face, two feelers"),
        FindItSymbol(44, "jellyfish", "find_it_sym_jellyfish", FindItColour.GRAPE, "dome, wavy tentacle strokes"),
        FindItSymbol(45, "feather", "find_it_sym_feather", FindItColour.SKY, "leaf-shaped vane, centre quill, barbs"),
        FindItSymbol(46, "banana", "find_it_sym_banana", FindItColour.SUN, "crescent with two tips"),
        FindItSymbol(47, "pizza", "find_it_sym_pizza", FindItColour.CORAL, "triangle slice, crust arc, pepperoni discs"),
        FindItSymbol(48, "lollipop", "find_it_sym_lollipop", FindItColour.BERRY, "swirled disc on a stick"),
        FindItSymbol(49, "cloud", "find_it_sym_cloud", FindItColour.SKY, "three overlapping discs on flat base"),
        FindItSymbol(50, "mountain", "find_it_sym_mountain", FindItColour.GRAPE, "two triangles, snow caps"),
        FindItSymbol(51, "wave", "find_it_sym_wave", FindItColour.TEAL, "curling crest with foam dots"),
        FindItSymbol(52, "spiral", "find_it_sym_spiral", FindItColour.BERRY, "thick Archimedean spiral stroke"),
        FindItSymbol(53, "ladder", "find_it_sym_ladder", FindItColour.CLAY, "two rails, five rungs"),
        FindItSymbol(54, "friedegg", "find_it_sym_friedegg", FindItColour.SUN, "wobbly white blob, round yolk"),
        FindItSymbol(55, "flamingo", "find_it_sym_flamingo", FindItColour.BERRY, "S-curve neck, bent beak, one long leg"),
        FindItSymbol(56, "wateringcan", "find_it_sym_wateringcan", FindItColour.LEAF, "body, long spout with rose, handle")
    )

    /** The pictures used by a deck of [size] (13, 31 or 57): the first [size] of [all]. */
    fun prefix(size: Int): List<FindItSymbol> = all.take(size.coerceIn(0, all.size))

    fun byId(id: String): FindItSymbol? = all.firstOrNull { it.id == id }
}
