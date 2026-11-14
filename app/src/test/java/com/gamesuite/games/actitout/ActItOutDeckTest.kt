package com.gamesuite.games.actitout

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** The card deck: size, uniqueness, EN/ES parity, safety word list, difficulty bands and pictures. */
class ActItOutDeckTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        return File("app/src/main/$relative").also { check(it.exists()) { "no $relative" } }
    }

    private fun parseFile(path: String): Map<String, String> {
        val strings = linkedMapOf<String, String>()
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(path))
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") strings[e.getAttribute("name")] = e.textContent
        }
        return strings
    }

    private val en by lazy { parseFile("res/values/act_it_out_strings.xml") }
    private val es by lazy { parseFile("res/values-es/act_it_out_strings.xml") }
    private fun shown(raw: String) = raw.replace("\\'", "'")

    private val cards = ActItOutDeck.cards

    // ---- shape of the deck ----

    @Test fun deckHasAboutTwoHundredCardsAndEveryCategory() {
        assertTrue("deck has ${cards.size}", cards.size in 190..230)
        for (c in ActItOutCategory.values()) assertTrue("$c has too few", cards.count { it.category == c } >= 15)
    }

    @Test fun noDuplicateIdsOrDuplicateEnglishTexts() {
        assertEquals(cards.size, cards.map { it.id }.toSet().size)
        assertEquals(cards.size, cards.map { it.english.lowercase() }.toSet().size)
        assertEquals(cards.size, ActItOutDeck.byId.size)
    }

    @Test fun difficultiesAreOneToThreeAndEachBandHasEnoughCards() {
        assertTrue(cards.all { it.difficulty in 1..3 })
        val by = ActItOutDeck.countByDifficulty()
        assertTrue("d1 ${by[1]}", (by[1] ?: 0) >= 60)
        assertTrue("d2 ${by[2]}", (by[2] ?: 0) >= 50)
        assertTrue("d3 ${by[3]}", (by[3] ?: 0) >= 60)
        assertEquals(cards.size, by.values.sum())
        // Per band: little kids need a real pool, and every band is bigger than a long game would use.
        assertTrue(ActItOutDeck.pool(ActItOutBand.LITTLE).size >= 60)
        assertTrue(ActItOutDeck.pool(ActItOutBand.EVERYONE).size >= 100)
        assertTrue(ActItOutDeck.pool(ActItOutBand.GROWN_UPS).size >= 100)
        assertTrue(ActItOutDeck.pool(ActItOutBand.LITTLE).all { it.difficulty == 1 })
    }

    @Test fun hardModeCardsAreAllDifficultyThree() {
        val hard = cards.filter { it.category == ActItOutCategory.HARD }
        assertTrue(hard.size >= 30)
        assertTrue(hard.all { it.difficulty == 3 })
    }

    // ---- pictures ----

    @Test fun everyGlyphExistsAndHasShapesInsideTheDrawingSquare() {
        for (c in cards) assertTrue("${c.id}: no glyph '${c.glyph}'", c.glyph in ActItOutGlyphs.shapes)
        for ((name, shapes) in ActItOutGlyphs.shapes) {
            assertTrue("$name is empty", shapes.size >= 2)
            for (s in shapes) {
                val nums: List<Float> = when (s) {
                    is GlyphCircle -> listOf(s.cx, s.cy, s.r, s.width)
                    is GlyphRect -> listOf(s.left, s.top, s.right, s.bottom, s.width)
                    is GlyphLine -> listOf(s.x1, s.y1, s.x2, s.y2, s.width)
                    is GlyphPoly -> s.points
                    is GlyphArc -> listOf(s.cx, s.cy, s.r, s.startDeg, s.sweepDeg, s.width)
                }
                assertTrue("$name has a non-finite number", nums.all { it.isFinite() })
                if (s is GlyphPoly) assertTrue("$name polygon needs 3+ points", s.points.size >= 6 && s.points.size % 2 == 0)
                val coords = when (s) {
                    is GlyphCircle -> listOf(s.cx, s.cy)
                    is GlyphRect -> listOf(s.left, s.top, s.right, s.bottom)
                    is GlyphLine -> listOf(s.x1, s.y1, s.x2, s.y2)
                    is GlyphPoly -> s.points
                    is GlyphArc -> listOf(s.cx, s.cy)
                }
                assertTrue("$name drawn far outside the square", coords.all { it in -0.2f..1.2f })
            }
        }
    }

    @Test fun everyDifficultyOneCardHasAPictureOfItsOwn() {
        val easy = cards.filter { it.difficulty == 1 }
        val emblems = ActItOutGlyphs.emblems.values.toSet()
        assertTrue("an easy card uses a category emblem", easy.none { it.glyph in emblems })
        assertEquals("two easy cards share a picture", easy.size, easy.map { it.glyph }.toSet().size)
    }

    @Test fun everyCategoryHasAnEmblemDrawing() {
        for (c in ActItOutCategory.values()) {
            val name = ActItOutGlyphs.emblems[c]
            assertTrue("no emblem for $c", name != null && name in ActItOutGlyphs.shapes)
        }
        // An unknown glyph id falls back to the emblem, never to nothing.
        assertTrue(ActItOutGlyphs.forCard("nope", ActItOutCategory.SPORTS).isNotEmpty())
    }

    @Test fun noGlyphUsesColourEmojiOrTextSymbols() {
        // Pictures are Compose-drawn shapes: the glyph field is a plain ascii drawing id, never an emoji.
        assertTrue(cards.all { c -> c.glyph.all { it in 'a'..'z' || it == '_' } })
    }

    // ---- languages ----

    @Test fun everyCardHasEnglishAndSpanishText() {
        for (c in cards) {
            val key = c.stringName
            assertTrue("missing EN $key", key in en)
            assertTrue("missing ES $key", key in es)
            assertEquals("EN resource differs from the deck for ${c.id}", c.english, shown(en[key]!!))
            assertTrue("blank ES $key", es[key]!!.isNotBlank())
        }
    }

    @Test fun noStrayCardStringsInEitherLanguage() {
        val ids = cards.map { it.stringName }.toSet()
        assertEquals(emptySet<String>(), en.keys.filter { it.startsWith("act_it_out_card_") }.toSet() - ids)
        assertEquals(emptySet<String>(), es.keys.filter { it.startsWith("act_it_out_card_") }.toSet() - ids)
        assertEquals(en.keys, es.keys)
    }

    @Test fun placeholdersMatchBetweenLanguages() {
        fun ph(s: String) = Regex("%(\\d+\\$)?[0-9]*[sdf]").findAll(s).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.sorted().toList()
        for (k in en.keys) assertEquals("placeholders of $k", ph(en[k]!!), ph(es[k]!!))
    }

    @Test fun apostrophesAreEscapedSoTheResourceCompilerAccepts() {
        for (path in listOf("res/values/act_it_out_strings.xml", "res/values-es/act_it_out_strings.xml")) {
            Regex(">([^<]*)<").findAll(moduleFile(path).readText()).forEach { m ->
                assertFalse("unescaped apostrophe in: ${m.groupValues[1]}", Regex("(?<!\\\\)'").containsMatchIn(m.groupValues[1]))
            }
        }
    }

    // ---- family safety ----

    /** Longer, distinctive fragments: matched as plain substrings. */
    private val bannedSubstrings = listOf(
        // scary / violent
        "kill", "murder", "blood", "dead", "death", "ghost", "monster", "zombie", "vampire", "devil", "demon", "skull",
        "bomb", "knife", "sword", "weapon", "fight", "punch", "stab", "spider", "snake", "shark", "crocodile", "scary",
        // body parts
        "nose", "belly", "tummy", "finger", "elbow", "knee", "toe",
        // adult
        "beer", "wine", "drunk", "cigar", "naked", "alcohol", "casino", "gambl",
        // political
        "president", "election", "politic", "government",
        // brands and characters
        "lego", "disney", "nike", "adidas", "pokemon", "mario", "barbie", "mickey", "batman", "superman", "pepsi", "coca",
        "mcdonald", "starbucks", "google", "youtube", "tiktok", "netflix", "iphone", "xbox", "playstation", "nintendo",
        "minecraft", "fortnite", "pixar", "potter", "star wars", "ferrari", "tesla",
        // Spanish
        "matar", "sangre", "muert", "fantasma", "monstruo", "zombi", "vampiro", "diablo", "demonio", "calavera", "bomba",
        "cuchillo", "espada", "pelea", "puñetazo", "araña", "serpiente", "culebra", "tiburón", "cocodrilo",
        "nariz", "barriga", "dedo", "codo", "rodilla", "cerveza", "borracho", "cigarr", "desnud", "presidente", "polític",
        "gobierno"
    )

    /** Short words that would hit innocent longer words as substrings (bear, farm, butterfly): whole-word matches only. */
    private val bannedWords = listOf(
        "butt", "gun", "hand", "hands", "foot", "feet", "arm", "arms", "leg", "legs", "head", "ear", "ears", "eye", "eyes",
        "mouth", "hair", "war", "hit", "kick", "shoot", "sex", "kiss", "flag",
        "mano", "manos", "pie", "pies", "ojo", "ojos", "oreja", "orejas", "boca", "cabeza", "pierna", "piernas", "brazo",
        "brazos", "pelo", "arma", "armas", "guerra", "beso"
    )

    @Test fun noBannedTextInEitherLanguage() {
        val problems = mutableListOf<String>()
        for (c in cards) {
            for ((lang, text) in listOf("en" to c.english, "es" to shown(es[c.stringName]!!))) {
                val low = text.lowercase()
                bannedSubstrings.filter { it in low }.forEach { problems += "$lang '$text' contains '$it'" }
                bannedWords.filter { Regex("(?<![\\p{L}])${Regex.escape(it)}(?![\\p{L}])").containsMatchIn(low) }
                    .forEach { problems += "$lang '$text' has the word '$it'" }
            }
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test fun theSafetyScanReallyCatchesWhatItShould() {
        // Guard the guard: the matcher must flag a body part as a whole word but not "bear" or "butterfly".
        fun hit(text: String) = bannedWords.any { Regex("(?<![\\p{L}])${Regex.escape(it)}(?![\\p{L}])").containsMatchIn(text.lowercase()) }
        assertTrue(hit("Wash your hands"))
        assertFalse(hit("Bear"))
        assertFalse(hit("Butterfly"))
    }
}
