package com.gamesuite.games.stackzoo

/**
 * The 20 original animals, drawn as unions of convex polygons (generated from point clouds, hulls in counter-clockwise
 * order). No shape is traced from, or modelled on, any published game's pieces; species names are common words.
 * The same table, with width, height and area, is printed in docs/design/kids-games/stack-zoo.md.
 * Each animal also has EN and ES names under `stack_zoo_animal_<id>`.
 */
object ZooAnimals {
    val all: List<AnimalSpec> = listOf(
        AnimalSpec(
            0, "turtle", "stack_zoo_animal_turtle", 1.6, 0.8, ZooTier.EASY,
            listOf(
                ZooPart(listOf(ZooVertex(-1.2, 0.0), ZooVertex(1.2, 0.0), ZooVertex(1.0, 0.7), ZooVertex(0.4, 1.1), ZooVertex(-0.4, 1.1), ZooVertex(-1.0, 0.7))),
                ZooPart(listOf(ZooVertex(1.1, 0.05), ZooVertex(1.8, 0.1), ZooVertex(1.85, 0.55), ZooVertex(1.15, 0.65)))
            )
        ),
        AnimalSpec(
            1, "hippo", "stack_zoo_animal_hippo", 2.4, 0.7, ZooTier.EASY,
            listOf(
                ZooPart(listOf(ZooVertex(-1.4, 0.35), ZooVertex(-1.1, 0.0), ZooVertex(1.1, 0.0), ZooVertex(1.4, 0.35), ZooVertex(1.4, 1.1), ZooVertex(-1.4, 1.1))),
                ZooPart(listOf(ZooVertex(1.2, 0.3), ZooVertex(2.0, 0.35), ZooVertex(2.1, 1.0), ZooVertex(1.2, 1.1)))
            )
        ),
        AnimalSpec(
            2, "elephant", "stack_zoo_animal_elephant", 3.0, 0.6, ZooTier.MEDIUM,
            listOf(
                ZooPart(listOf(ZooVertex(-1.4, 1.4), ZooVertex(-1.3, 0.8), ZooVertex(1.2, 0.8), ZooVertex(1.3, 1.5), ZooVertex(0.6, 1.9), ZooVertex(-0.9, 1.9))),
                ZooPart(listOf(ZooVertex(-0.9, 0.0), ZooVertex(-0.3, 0.0), ZooVertex(-0.3, 0.9), ZooVertex(-0.9, 0.9))),
                ZooPart(listOf(ZooVertex(0.3, 0.0), ZooVertex(0.9, 0.0), ZooVertex(0.9, 0.9), ZooVertex(0.3, 0.9))),
                ZooPart(listOf(ZooVertex(1.3, 0.7), ZooVertex(1.75, 0.65), ZooVertex(1.85, 1.6), ZooVertex(1.3, 1.5)))
            )
        ),
        AnimalSpec(
            3, "giraffe", "stack_zoo_animal_giraffe", 2.2, 0.5, ZooTier.HARD,
            listOf(
                ZooPart(listOf(ZooVertex(-0.9, 1.3), ZooVertex(0.9, 1.3), ZooVertex(0.9, 2.0), ZooVertex(-0.9, 2.0))),
                ZooPart(listOf(ZooVertex(-0.8, 0.0), ZooVertex(-0.5, 0.0), ZooVertex(-0.5, 1.3), ZooVertex(-0.8, 1.3))),
                ZooPart(listOf(ZooVertex(0.5, 0.0), ZooVertex(0.8, 0.0), ZooVertex(0.8, 1.3), ZooVertex(0.5, 1.3))),
                ZooPart(listOf(ZooVertex(0.5, 2.0), ZooVertex(0.9, 2.0), ZooVertex(1.3, 3.4), ZooVertex(0.95, 3.4)))
            )
        ),
        AnimalSpec(
            4, "crocodile", "stack_zoo_animal_crocodile", 1.8, 0.7, ZooTier.EASY,
            listOf(
                ZooPart(listOf(ZooVertex(-2.0, 0.0), ZooVertex(1.6, 0.0), ZooVertex(1.6, 0.5), ZooVertex(-2.0, 0.6))),
                ZooPart(listOf(ZooVertex(1.5, 0.0), ZooVertex(2.6, 0.05), ZooVertex(2.6, 0.4), ZooVertex(1.5, 0.55))),
                ZooPart(listOf(ZooVertex(-2.9, 0.0), ZooVertex(-2.0, 0.0), ZooVertex(-2.0, 0.6), ZooVertex(-2.9, 0.25)))
            )
        ),
        AnimalSpec(
            5, "snail", "stack_zoo_animal_snail", 1.0, 0.6, ZooTier.MEDIUM,
            listOf(
                ZooPart(listOf(ZooVertex(-0.9, 1.0), ZooVertex(-0.64, 0.36), ZooVertex(0.0, 0.1), ZooVertex(0.64, 0.36), ZooVertex(0.9, 1.0), ZooVertex(0.64, 1.64), ZooVertex(0.0, 1.9), ZooVertex(-0.64, 1.64))),
                ZooPart(listOf(ZooVertex(-1.5, 0.0), ZooVertex(1.4, 0.0), ZooVertex(1.7, 0.35), ZooVertex(1.0, 0.5), ZooVertex(-1.5, 0.35))),
                ZooPart(listOf(ZooVertex(1.2, 0.3), ZooVertex(1.8, 0.3), ZooVertex(1.9, 1.0), ZooVertex(1.5, 1.0)))
            )
        ),
        AnimalSpec(
            6, "penguin", "stack_zoo_animal_penguin", 1.2, 0.55, ZooTier.MEDIUM,
            listOf(
                ZooPart(listOf(ZooVertex(-0.8, 1.0), ZooVertex(-0.6, 0.1), ZooVertex(0.6, 0.1), ZooVertex(0.8, 1.0), ZooVertex(0.4, 1.9), ZooVertex(-0.4, 1.9))),
                ZooPart(listOf(ZooVertex(-0.9, 0.0), ZooVertex(0.9, 0.0), ZooVertex(0.9, 0.2), ZooVertex(-0.9, 0.2)))
            )
        ),
        AnimalSpec(
            7, "rabbit", "stack_zoo_animal_rabbit", 0.9, 0.5, ZooTier.HARD,
            listOf(
                ZooPart(listOf(ZooVertex(-0.8, 0.0), ZooVertex(0.9, 0.0), ZooVertex(0.8, 0.9), ZooVertex(-0.5, 1.1))),
                ZooPart(listOf(ZooVertex(0.3, 0.9), ZooVertex(1.0, 0.9), ZooVertex(1.0, 1.5), ZooVertex(0.3, 1.6))),
                ZooPart(listOf(ZooVertex(0.4, 1.5), ZooVertex(0.6, 1.5), ZooVertex(0.55, 2.5), ZooVertex(0.4, 2.5))),
                ZooPart(listOf(ZooVertex(0.8, 1.5), ZooVertex(1.0, 1.5), ZooVertex(0.95, 2.4), ZooVertex(0.8, 2.4)))
            )
        ),
        AnimalSpec(
            8, "fox", "stack_zoo_animal_fox", 1.1, 0.5, ZooTier.MEDIUM,
            listOf(
                ZooPart(listOf(ZooVertex(-1.2, 0.0), ZooVertex(0.9, 0.0), ZooVertex(1.0, 0.8), ZooVertex(-1.0, 1.0))),
                ZooPart(listOf(ZooVertex(0.8, 0.6), ZooVertex(1.8, 0.65), ZooVertex(1.3, 1.5))),
                ZooPart(listOf(ZooVertex(-2.1, 0.5), ZooVertex(-1.0, 0.15), ZooVertex(-0.9, 0.9), ZooVertex(-2.0, 1.3)))
            )
        ),
        AnimalSpec(
            9, "bear", "stack_zoo_animal_bear", 2.0, 0.6, ZooTier.EASY,
            listOf(
                ZooPart(listOf(ZooVertex(-1.2, 0.9), ZooVertex(-1.0, 0.0), ZooVertex(1.0, 0.0), ZooVertex(1.2, 0.9), ZooVertex(0.8, 1.6), ZooVertex(-0.8, 1.6))),
                ZooPart(listOf(ZooVertex(-0.7, 2.1), ZooVertex(-0.5, 1.5), ZooVertex(0.5, 1.5), ZooVertex(0.7, 2.1), ZooVertex(0.3, 2.4), ZooVertex(-0.3, 2.4)))
            )
        ),
        AnimalSpec(
            10, "duck", "stack_zoo_animal_duck", 0.8, 0.45, ZooTier.MEDIUM,
            listOf(
                ZooPart(listOf(ZooVertex(-1.2, 0.6), ZooVertex(-0.9, 0.1), ZooVertex(0.8, 0.1), ZooVertex(1.2, 0.6), ZooVertex(0.6, 1.0), ZooVertex(-0.8, 1.0))),
                ZooPart(listOf(ZooVertex(0.4, 1.6), ZooVertex(0.5, 1.0), ZooVertex(1.1, 1.0), ZooVertex(1.2, 1.5), ZooVertex(0.8, 1.9))),
                ZooPart(listOf(ZooVertex(1.15, 1.2), ZooVertex(1.75, 1.2), ZooVertex(1.2, 1.5))),
                ZooPart(listOf(ZooVertex(-1.7, 1.2), ZooVertex(-1.2, 0.6), ZooVertex(-0.9, 0.9)))
            )
        ),
        AnimalSpec(
            11, "frog", "stack_zoo_animal_frog", 1.0, 0.4, ZooTier.EASY,
            listOf(
                ZooPart(listOf(ZooVertex(-1.2, 0.6), ZooVertex(-1.0, 0.0), ZooVertex(1.0, 0.0), ZooVertex(1.2, 0.6), ZooVertex(0.6, 1.1), ZooVertex(-0.6, 1.1))),
                ZooPart(listOf(ZooVertex(-0.8, 1.0), ZooVertex(-0.3, 1.0), ZooVertex(-0.35, 1.5), ZooVertex(-0.75, 1.5))),
                ZooPart(listOf(ZooVertex(0.3, 1.0), ZooVertex(0.8, 1.0), ZooVertex(0.75, 1.5), ZooVertex(0.35, 1.5)))
            )
        ),
        AnimalSpec(
            12, "owl", "stack_zoo_animal_owl", 1.3, 0.55, ZooTier.HARD,
            listOf(
                ZooPart(listOf(ZooVertex(-0.9, 1.3), ZooVertex(-0.8, 0.0), ZooVertex(0.8, 0.0), ZooVertex(0.9, 1.3), ZooVertex(0.6, 2.0), ZooVertex(-0.6, 2.0))),
                ZooPart(listOf(ZooVertex(-0.6, 2.0), ZooVertex(-0.3, 2.0), ZooVertex(-0.5, 2.5))),
                ZooPart(listOf(ZooVertex(0.3, 2.0), ZooVertex(0.6, 2.0), ZooVertex(0.5, 2.5)))
            )
        ),
        AnimalSpec(
            13, "monkey", "stack_zoo_animal_monkey", 1.0, 0.5, ZooTier.HARD,
            listOf(
                ZooPart(listOf(ZooVertex(-0.7, 0.0), ZooVertex(0.7, 0.0), ZooVertex(0.8, 1.0), ZooVertex(-0.6, 1.2))),
                ZooPart(listOf(ZooVertex(-0.6, 1.8), ZooVertex(-0.5, 1.1), ZooVertex(0.6, 1.1), ZooVertex(0.7, 1.8), ZooVertex(0.0, 2.1))),
                ZooPart(listOf(ZooVertex(0.7, 0.6), ZooVertex(1.9, 0.9), ZooVertex(1.9, 1.3), ZooVertex(0.7, 1.1))),
                ZooPart(listOf(ZooVertex(-1.8, 0.4), ZooVertex(-0.6, 0.15), ZooVertex(-0.55, 0.7), ZooVertex(-1.8, 0.9)))
            )
        ),
        AnimalSpec(
            14, "dino", "stack_zoo_animal_dino", 2.1, 0.6, ZooTier.MEDIUM,
            listOf(
                ZooPart(listOf(ZooVertex(-1.4, 0.5), ZooVertex(1.0, 0.5), ZooVertex(1.9, 0.9), ZooVertex(1.9, 1.4), ZooVertex(-1.3, 1.4))),
                ZooPart(listOf(ZooVertex(-1.1, 0.0), ZooVertex(-0.5, 0.0), ZooVertex(-0.5, 0.6), ZooVertex(-1.1, 0.6))),
                ZooPart(listOf(ZooVertex(0.2, 0.0), ZooVertex(0.8, 0.0), ZooVertex(0.8, 0.6), ZooVertex(0.2, 0.6))),
                ZooPart(listOf(ZooVertex(-1.0, 1.35), ZooVertex(0.6, 1.28), ZooVertex(-0.2, 2.0)))
            )
        ),
        AnimalSpec(
            15, "whale", "stack_zoo_animal_whale", 2.2, 0.45, ZooTier.MEDIUM,
            listOf(
                ZooPart(listOf(ZooVertex(-1.5, 0.5), ZooVertex(-1.3, 0.0), ZooVertex(1.0, 0.0), ZooVertex(1.7, 0.5), ZooVertex(1.5, 1.1), ZooVertex(0.5, 1.3), ZooVertex(-1.1, 1.0))),
                ZooPart(listOf(ZooVertex(-2.2, 1.5), ZooVertex(-1.4, 0.6), ZooVertex(-1.2, 0.9), ZooVertex(-1.6, 1.55))),
                ZooPart(listOf(ZooVertex(-0.3, 1.05), ZooVertex(0.3, 1.2), ZooVertex(-0.1, 1.7)))
            )
        ),
        AnimalSpec(
            16, "camel", "stack_zoo_animal_camel", 2.4, 0.55, ZooTier.HARD,
            listOf(
                ZooPart(listOf(ZooVertex(-1.3, 1.0), ZooVertex(1.3, 1.0), ZooVertex(1.3, 1.5), ZooVertex(0.3, 2.2), ZooVertex(-0.7, 2.2), ZooVertex(-1.3, 1.5))),
                ZooPart(listOf(ZooVertex(-1.0, 0.0), ZooVertex(-0.6, 0.0), ZooVertex(-0.6, 1.0), ZooVertex(-1.0, 1.0))),
                ZooPart(listOf(ZooVertex(0.6, 0.0), ZooVertex(1.0, 0.0), ZooVertex(1.0, 1.0), ZooVertex(0.6, 1.0))),
                ZooPart(listOf(ZooVertex(0.9, 1.4), ZooVertex(1.4, 1.4), ZooVertex(1.8, 2.5), ZooVertex(1.4, 2.5)))
            )
        ),
        AnimalSpec(
            17, "cat", "stack_zoo_animal_cat", 1.0, 0.5, ZooTier.MEDIUM,
            listOf(
                ZooPart(listOf(ZooVertex(-0.7, 0.0), ZooVertex(0.8, 0.0), ZooVertex(0.7, 1.1), ZooVertex(-0.5, 1.3))),
                ZooPart(listOf(ZooVertex(-0.55, 2.0), ZooVertex(-0.5, 1.2), ZooVertex(0.6, 1.2), ZooVertex(0.65, 2.0), ZooVertex(0.35, 2.3), ZooVertex(-0.35, 2.3))),
                ZooPart(listOf(ZooVertex(0.7, 0.1), ZooVertex(1.7, 0.1), ZooVertex(1.8, 1.3), ZooVertex(1.5, 1.3)))
            )
        ),
        AnimalSpec(
            18, "snake", "stack_zoo_animal_snake", 1.1, 0.35, ZooTier.EASY,
            listOf(
                ZooPart(listOf(ZooVertex(-1.5, 0.4), ZooVertex(-1.3, 0.0), ZooVertex(1.3, 0.0), ZooVertex(1.5, 0.4))),
                ZooPart(listOf(ZooVertex(-0.9, 0.4), ZooVertex(0.9, 0.4), ZooVertex(0.9, 0.8), ZooVertex(-0.9, 0.8))),
                ZooPart(listOf(ZooVertex(0.1, 1.2), ZooVertex(0.2, 0.8), ZooVertex(0.8, 0.8), ZooVertex(0.9, 1.5), ZooVertex(0.4, 1.7)))
            )
        ),
        AnimalSpec(
            19, "pig", "stack_zoo_animal_pig", 1.4, 0.55, ZooTier.EASY,
            listOf(
                ZooPart(listOf(ZooVertex(-1.1, 0.3), ZooVertex(0.9, 0.3), ZooVertex(1.1, 1.1), ZooVertex(-1.1, 1.2))),
                ZooPart(listOf(ZooVertex(-0.9, 0.0), ZooVertex(-0.4, 0.0), ZooVertex(-0.4, 0.4), ZooVertex(-0.9, 0.4))),
                ZooPart(listOf(ZooVertex(0.4, 0.0), ZooVertex(0.9, 0.0), ZooVertex(0.9, 0.4), ZooVertex(0.4, 0.4))),
                ZooPart(listOf(ZooVertex(0.9, 0.5), ZooVertex(1.6, 0.5), ZooVertex(1.6, 1.2), ZooVertex(0.9, 1.3)))
            )
        )
    )

    fun byId(id: String): AnimalSpec? = all.firstOrNull { it.id == id }
}
