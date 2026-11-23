package com.gamesuite.games.wordgames

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.actitout.ActItOutDeck
import com.gamesuite.games.drawguess.DrawGuessWordBank
import com.gamesuite.games.hangman.HangmanGame
import com.gamesuite.games.wordgames.wordsearch.WordSearchGame
import com.gamesuite.games.wordguess.WordGuessAnswerList
import com.gamesuite.settings.CpuDifficulty
import com.gamesuite.transport.LocalPassAndPlayTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WordSafetyTest {
    private fun asset(name: String): File {
        val here = File("src/main/assets/$name")
        if (here.exists()) return here
        return File("app/src/main/assets/$name").also { check(it.exists()) { "no $name" } }
    }

    private fun lines(name: String): List<String> = asset(name).readLines().map { it.trim() }.filter { it.isNotEmpty() }

    @Test fun slursAndProfanityAreBlockedInEnglishAndSpanish() {
        val bad = listOf(
            "cunt", "faggot", "FAGGOT", "nigger", "niggers", "snigger", "fuck", "fucking", "motherfucker", "shit", "bullshit", "bitch",
            "bitches", "bastard", "asshole", "slut", "whore", "dick", "cock", "pussy", "twat", "wanker", "ass", "asses", "tits", "penis",
            "vagina", "porn", "rape", "raping", "rapist", "cocaine", "heroin", "marijuana", "meth", "murder", "suicide", "kill", "killers",
            "puta", "putas", "mierda", "coño", "cabrón", "cabron", "pendejo", "maricón", "joder", "verga", "culo", "polla", "gilipollas",
            "follar", "cocaína", "asesino", "violación", "hijo de puta", "what a bitch"
        )
        for (w in bad) assertTrue("'$w' should be blocked", WordSafety.isBlocked(w))
    }

    private val ordinary = ("class assist assistant title peacock cocktail grape drape shell hello analyst analysis essex scunthorpe passage " +
        "classic glass grass bass mass compass pass cassette massive assume assign assets bassoon hassle tassel brass embassy " +
        "titan tissue titanium constitution competition substitute appetite petite entitle butter button mutton cotton " +
        "dickens dickey cockatoo cockpit cockroach hancock cucumber circumstance computer computadora cono cone " +
        "penguin pennant spend penny pencil peninsula pendulum rapid rapids trapeze therapist therapy sextant sextet " +
        "sexton middlesex arsenal parse sparse harsh assemble reassure homophone homework homesick chinchilla chinese machine " +
        "kilometer skill skills skillet killjoy hellebore shelly helmet helicopter mother father brother sister apple " +
        "house tiger beach chair smile table music water happy robot plant train candy storm android kotlin compose tablet " +
        "puzzle arcade hockey journey festival sandwich mountain elephant chemistry volcano guitar foldable multiplayer dominoes " +
        "mancala crossword xylophone quarantine rhythm synchronize astronaut labyrinth pneumonia handkerchief bureaucracy " +
        "gato perro pez pájaro vaca cerdo caballo oveja pato rana conejo ratón oso león elefante jirafa mariposa " +
        "abeja caracol pingüino búho ballena cangrejo computación disputa reputación cacahuete pulpo manzana naranja plátano " +
        "casa mesa silla libro escuela amigo familia ciudad pelota bicicleta ventana jardín cocina montaña verano invierno " +
        "conejito reloj teléfono camiseta zapato sombrero mochila lápiz cuaderno regalo cumpleaños helado galleta").split(" ")

    @Test fun ordinaryWordsAreNotFalselyBlocked() {
        assertTrue("need 200 words, have ${ordinary.size}", ordinary.size >= 200)
        val hit = ordinary.filter { WordSafety.isBlocked(it) }
        assertEquals("false positives", emptyList<String>(), hit)
    }

    @Test fun fullDictionaryBlockRateIsSmallAndKnownSlursAreAllGone() {
        val all = lines("words.txt")
        val blocked = all.filter { WordSafety.isBlocked(it) }
        File("build").mkdirs()
        File("build/wordsafety-blocked.txt").writeText(blocked.joinToString("\n"))
        assertTrue("blocked ${blocked.size} of ${all.size}", blocked.size in 50..4000)
        val safe = all.filter { WordSafety.isSafe(it) }.toSet()
        for (w in listOf("cunt", "faggot", "nigger", "fuck", "shit", "bitch", "whore", "slut")) assertFalse(w, w in safe)
        for (w in listOf("apple", "house", "class", "title", "grape", "peacock")) assertTrue(w, w in safe)
    }

    @Test fun everyWordGuessAnswerIsInTheGuessDictionary() {
        val dict = lines("words.txt").toSet()
        val missing = lines("word_guess_answers.txt").filterNot { it.lowercase() in dict }
        assertEquals("answers missing from the guess dictionary", emptyList<String>(), missing)
        for (w in listOf("detox", "geeky", "inbox", "latte", "penne", "pesto", "ramen", "rehab", "wimpy")) assertTrue(w, w in dict)
    }

    @Test fun answerListParserDropsBlocklistedWordsAndReportsHowMany() {
        val raw = lines("word_guess_answers.txt")
        val parsed = WordGuessAnswerList.parse(raw.asSequence())
        assertTrue(parsed.none { WordSafety.isBlocked(it) })
        assertTrue("dropped ${raw.size - parsed.size}", raw.size - parsed.size <= 80)
    }

    @Test fun drawGuessAndActItOutBanksPassTheSharedList() {
        for (w in DrawGuessWordBank.all) for (t in listOf(w.en, w.es)) assertFalse("draw '$t'", WordSafety.isBlocked(t))
        for (c in ActItOutDeck.cards) assertFalse("act '${c.english}'", WordSafety.isBlocked(c.english))
    }

    @Test fun familyListsAreTheOneSharedInstance() {
        assertEquals(WordSafety.familyBannedWords, DrawGuessWordBank.bannedWords)
        assertEquals(WordSafety.familyBannedFragments, DrawGuessWordBank.bannedFragments)
    }

    @Test fun kidPoolIsASubsetOfTheCuratedLists() {
        val answers = WordGuessAnswerList.parse(lines("word_guess_answers.txt").asSequence())
        val curated = answers.toSet() + DrawGuessWordBank.all.map { it.en.lowercase() }
        val kid = KidWordPool.build(answers)
        assertTrue(kid.size > 1000)
        assertTrue("outside curated: ${kid.filterNot { it in curated }.take(5)}", kid.all { it in curated })
        assertTrue(kid.none { WordSafety.isBlocked(it) })
    }

    private fun ctx() = GameContext(
        activeMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        players = listOf(PlayerInfo(playerId = "p1", displayName = "P1")),
        localPlayerIndex = 0,
        transport = LocalPassAndPlayTransport()
    )

    @Test fun hangmanNeverDrawsABlockedWordFromADirtyDictionary() {
        val game = HangmanGame()
        game.init(ctx())
        game.kidModeProvider = { false }
        val dirty = listOf("zebra", "tiger", "cunt", "faggot", "bitches", "elephant", "nigger", "fucks", "shits", "bitch", "mountains")
        game.dictionaryWordsOfLength = { len -> dirty.filter { it.length == len } }
        for (tier in CpuDifficulty.values()) assertTrue(game.wordPoolFor(tier).none { WordSafety.isBlocked(it) })
    }

    @Test fun hangmanKidProfileNeverTouchesTheRawDictionary() {
        val game = HangmanGame()
        game.init(ctx())
        game.kidModeProvider = { true }
        game.dictionaryWordsOfLength = { error("raw dictionary must not be read for a kid") }
        val answers = WordGuessAnswerList.parse(lines("word_guess_answers.txt").asSequence())
        game.kidWordsOfLength = { len -> KidWordPool.ofLength(len, answers) }
        val curated = (answers + DrawGuessWordBank.all.map { it.en.lowercase() }).map { it.uppercase() }.toSet()
        val own = setOf(
            "APPLE", "HOUSE", "TIGER", "BEACH", "CHAIR", "SMILE", "TABLE", "MUSIC", "WATER", "HAPPY", "ROBOT", "PLANT", "TRAIN", "CANDY", "STORM",
            "ANDROID", "KOTLIN", "COMPOSE", "TABLET", "PUZZLE", "ARCADE", "HOCKEY", "JOURNEY", "FESTIVAL", "SANDWICH", "MOUNTAIN", "ELEPHANT",
            "CHEMISTRY", "VOLCANO", "GUITAR", "FOLDABLE", "MULTIPLAYER", "DOMINOES", "MANCALA", "CROSSWORD", "XYLOPHONE", "QUARANTINE",
            "RHYTHM", "SYNCHRONIZE", "ASTRONAUT", "LABYRINTH", "PNEUMONIA", "HANDKERCHIEF", "BUREAUCRACY"
        )
        for (tier in CpuDifficulty.values()) {
            val pool = game.wordPoolFor(tier)
            assertTrue(pool.isNotEmpty())
            assertTrue("outside curated: ${pool.filterNot { it in curated || it in own }}", pool.all { it in curated || it in own })
            assertTrue(pool.none { WordSafety.isBlocked(it) })
        }
    }

    @Test fun wordSearchSkipsBlockedWordsAndKidModeUsesTheKidPool() {
        val fake = (4..15).associateWith { len ->
            listOf("cunt", "faggot", "fucks").filter { it.length == len } +
                List(100) { i -> "w" + "abcdefghij"[i % 10].toString().repeat(len - 1) + i }.filter { it.length == len || true }
        }
        val game = WordSearchGame(wordsOfLength = { fake[it].orEmpty().filter { w -> w.length == it } })
        game.difficulty = CpuDifficulty.MEDIUM
        game.init(ctx())
        game.kidModeProvider = { false }
        repeat(20) {
            game.startMatch(seed = it.toLong())
            assertTrue(game.state.value!!.placedWords.none { w -> WordSafety.isBlocked(w.word) })
        }
        val kidWords = listOf("apple", "tiger", "house", "candy", "robot", "plant", "train", "chair", "table", "music")
        game.kidModeProvider = { true }
        game.kidWordsOfLength = { len -> kidWords.filter { it.length == len } }
        game.startMatch(seed = 5L)
        val placed = game.state.value!!.placedWords
        assertTrue(placed.isNotEmpty())
        assertTrue(placed.all { it.word.lowercase() in kidWords })
    }

    @Test fun theKidFlagOnTheProfileScopeIsReadableAndResettable() {
        com.gamesuite.core.ProfileScope.setActiveKid(true)
        assertTrue(com.gamesuite.core.ProfileScope.isActiveKid)
        com.gamesuite.core.ProfileScope.setActiveKid(false)
        assertFalse(com.gamesuite.core.ProfileScope.isActiveKid)
    }
}
