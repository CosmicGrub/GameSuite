package com.gamesuite.games.wordgames.crossword

/**
 * Crossword answers need real clues, and the bundled dictionary has no clue
 * data — so unlike word search (which can pull arbitrary dictionary words),
 * crossword uses small hand-authored, themed clue banks instead of trying
 * to auto-generate clues for random words.
 *
 * Upgrade pass (README item 9l) added [easyEverydayTheme] and
 * [generalKnowledgeTheme] alongside the original [gamesAndTechTheme], picked
 * by CpuDifficulty the same way Hangman picks its word pool (see
 * HangmanGame's KDoc) — a solo puzzle has no opponent to make smarter or
 * dumber, so the clue bank itself is the difficulty lever: EASY draws from
 * short, everyday vocabulary with plain clues; MEDIUM is the original
 * games/tech theme, left byte-for-byte unchanged; HARD draws from longer,
 * general-knowledge trivia answers.
 */
object CrosswordClueBank {
    data class Entry(val word: String, val clue: String)

    val gamesAndTechTheme = listOf(
        Entry("ANDROID", "Google's mobile OS"),
        Entry("KOTLIN", "Language this app is written in"),
        Entry("PIXEL", "Smallest unit of a screen image"),
        Entry("BOARD", "Chess or checkers surface"),
        Entry("DECK", "Collection of playing cards"),
        Entry("DICE", "Rolled for random results"),
        Entry("TOKEN", "Game piece moved on a board"),
        Entry("SCORE", "Tally of points earned"),
        Entry("LEVEL", "Stage of a game"),
        Entry("BONUS", "Extra reward"),
        Entry("TURN", "One player's chance to act"),
        Entry("RULES", "What every game needs"),
        Entry("TILE", "Square piece, as in Scrabble"),
        Entry("MATCH", "A single game session"),
        Entry("TEAM", "Group playing together"),
        Entry("SOLO", "Playing alone"),
        Entry("BOT", "Computer-controlled opponent"),
        Entry("WIN", "Opposite of lose"),
        Entry("DRAW", "Neither side wins"),
        Entry("CARD", "UNO or poker piece"),
        Entry("WILD", "UNO card that changes color"),
        Entry("SKIP", "UNO card that passes the next player"),
        Entry("FOLD", "What a Z-series Samsung phone does"),
        Entry("TABLET", "Larger touchscreen device, like a Tab S9"),
        Entry("APP", "Short for application"),
        Entry("GRID", "Rows and columns layout"),
        Entry("MAZE", "Puzzle you navigate through"),
        Entry("CLUE", "Hint toward an answer"),
        Entry("WORD", "Unit of language, and this game's theme"),
        Entry("PLAY", "To take part in a game")
    )

    /** EASY tier: short, everyday vocabulary with plain, unambiguous clues. */
    val easyEverydayTheme = listOf(
        Entry("CAT", "Common house pet that meows"),
        Entry("DOG", "Common house pet that barks"),
        Entry("SUN", "Star at the center of our solar system"),
        Entry("MOON", "Object that orbits Earth and causes tides"),
        Entry("RAIN", "Water falling from clouds"),
        Entry("TREE", "Tall plant with a trunk and branches"),
        Entry("FISH", "Animal that lives and breathes underwater"),
        Entry("BIRD", "Animal with feathers and wings"),
        Entry("BOOK", "Bound pages you read"),
        Entry("SHOE", "Item worn on your foot"),
        Entry("CAKE", "Sweet baked dessert, often for birthdays"),
        Entry("MILK", "White drink that comes from a cow"),
        Entry("BREAD", "Baked food made mainly from flour"),
        Entry("CHAIR", "Furniture you sit on"),
        Entry("HOUSE", "Building where a family lives"),
        Entry("SMILE", "Facial expression showing happiness"),
        Entry("HAPPY", "Feeling of joy"),
        Entry("WATER", "Clear liquid essential for life"),
        Entry("APPLE", "Round fruit that can be red or green"),
        Entry("BEACH", "Sandy shore next to the ocean")
    )

    /**
     * HARD tier: longer general-knowledge trivia. Every clue below was
     * checked to be factually correct with exactly one reasonable
     * single-word answer (no ties, no multi-word answers).
     */
    val generalKnowledgeTheme = listOf(
        Entry("PARIS", "Capital city of France"),
        Entry("TOKYO", "Capital city of Japan"),
        Entry("NILE", "Longest river in Africa"),
        Entry("EVEREST", "Tallest mountain above sea level"),
        Entry("MERCURY", "Closest planet to the Sun"),
        Entry("SATURN", "Planet famous for its rings"),
        Entry("OXYGEN", "Gas humans need to breathe"),
        Entry("GRAVITY", "Force that pulls objects toward Earth"),
        Entry("GALAXY", "Huge system of stars, such as the Milky Way"),
        Entry("COMET", "Icy object that grows a tail near the Sun"),
        Entry("PLANET", "A body like Earth or Mars that orbits a star"),
        Entry("ASTEROID", "Rocky body orbiting the Sun, smaller than a planet"),
        Entry("METEOR", "Streak of light from a space rock burning up in the sky"),
        Entry("TELESCOPE", "Instrument used to observe distant stars and planets"),
        Entry("VOLCANO", "Mountain that can erupt with lava"),
        Entry("GLACIER", "Massive, slow-moving river of ice"),
        Entry("CANYON", "Deep gorge carved by a river, like the Grand one"),
        Entry("DESERT", "Dry region that receives very little rainfall"),
        Entry("EQUATOR", "Imaginary line dividing Earth into two hemispheres"),
        Entry("CONTINENT", "Large landmass, one of seven on Earth"),
        Entry("PENGUIN", "Flightless bird found in Antarctica"),
        Entry("GIRAFFE", "Tallest land animal, known for its long neck"),
        Entry("CHEETAH", "Fastest land animal"),
        Entry("DOLPHIN", "Intelligent marine mammal known for clicking sounds"),
        Entry("EINSTEIN", "Physicist who developed the theory of relativity"),
        Entry("DARWIN", "Naturalist known for the theory of evolution"),
        Entry("SHAKESPEARE", "Playwright who wrote Romeo and Juliet"),
        Entry("PYRAMID", "Ancient Egyptian structure built as a royal tomb"),
        Entry("DEMOCRACY", "System of government where citizens vote for leaders"),
        Entry("PRESIDENT", "Head of state in a republic such as the U.S."),
        Entry("LIBRARY", "Building full of books available to borrow")
    )
}
