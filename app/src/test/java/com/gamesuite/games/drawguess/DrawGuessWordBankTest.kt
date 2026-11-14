package com.gamesuite.games.drawguess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer
import kotlin.random.Random

class DrawGuessWordBankTest {
    private val all = DrawGuessWordBank.all

    @Test fun theBankHasAbout150WordsWithUniqueNamesInBothLanguages() {
        assertTrue("size ${all.size}", all.size in 140..170)
        assertEquals(all.size, all.map { it.en }.toSet().size)
        assertEquals(all.size, all.map { it.es }.toSet().size)
        assertEquals(all.size, all.map { it.key }.toSet().size)
        assertEquals("EN and ES counts match", all.count { it.en.isNotBlank() }, all.count { it.es.isNotBlank() })
        for (w in all) { assertTrue(w.en, w.en == w.en.lowercase() && w.en.all { it.isLetter() || it == ' ' }) }
    }

    @Test fun everyDifficultyAndCategoryIsWellRepresented() {
        for (d in 1..3) assertTrue("difficulty $d has ${all.count { it.difficulty == d }}", all.count { it.difficulty == d } >= 30)
        assertTrue(all.all { it.difficulty in 1..3 })
        for (c in WordCategory.values()) assertTrue("$c", all.count { it.category == c } >= 12)
    }

    @Test fun noBannedWordOrFragmentAppearsInEitherLanguage() {
        for (w in all) for (name in listOf(w.en, w.es)) {
            val folded = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            val tokens = name.lowercase().split(' ') + folded.split(' ')
            for (t in tokens) assertFalse("banned word '$t' in '$name'", t in DrawGuessWordBank.bannedWords)
            for (f in DrawGuessWordBank.bannedFragments) assertFalse("banned fragment '$f' in '$name'", folded.contains(f))
        }
    }

    @Test fun everyWordHasAGlyphThatParsesAndStaysOnTheTile() {
        assertEquals(all.map { it.key }.toSet(), DrawGuessGlyphs.keys)
        for (w in all) {
            assertTrue("no glyph for ${w.key}", w.glyph.isNotBlank())
            val ops = DrawGuessGlyphs.parse(w.glyph)
            assertTrue("${w.key} has ${ops.size} shapes", ops.size in 2..16)
            fun ok(v: Float) = v in -2f..102f
            for (op in ops) when (op) {
                is GlyphOp.Ellipse -> assertTrue(w.key, ok(op.cx - op.rx) && ok(op.cx + op.rx) && ok(op.cy - op.ry) && ok(op.cy + op.ry))
                is GlyphOp.Rect -> assertTrue(w.key, ok(op.x) && ok(op.y) && ok(op.x + op.w) && ok(op.y + op.h))
                is GlyphOp.Poly -> assertTrue(w.key, op.xy.all { ok(it) })
                is GlyphOp.Line -> assertTrue(w.key, ok(op.x1) && ok(op.y1) && ok(op.x2) && ok(op.y2))
                is GlyphOp.Arc -> {
                    // Only the swept part has to stay on the tile (a half-circle rainbow's full circle would not).
                    var a = 0f
                    while (a <= 1.0001f) {
                        val rad = Math.toRadians((op.start + op.sweep * a).toDouble())
                        assertTrue(w.key, ok(op.cx + op.r * Math.cos(rad).toFloat()) && ok(op.cy + op.r * Math.sin(rad).toFloat()))
                        a += 0.1f
                    }
                }
                is GlyphOp.Ring -> assertTrue(w.key, ok(op.cx - op.rx) && ok(op.cx + op.rx) && ok(op.cy - op.ry) && ok(op.cy + op.ry))
            }
        }
    }

    @Test fun noTwoWordsShareAPicture() {
        assertEquals(all.size, all.map { it.glyph }.toSet().size)
    }

    @Test fun malformedGlyphSpecsAreRejected() {
        for (bad in listOf("o 1 2 3 k", "o 1 2 3 4 z", "q 1 2 3 4 k", "p 1 2 3 4 k", "l 1 2 3 4 x k", "o 1 2 3 4"))
            assertTrue(bad, runCatching { DrawGuessGlyphs.parse(bad) }.isFailure)
    }

    @Test fun lookupByKeyAndLanguageText() {
        val cat = DrawGuessWordBank.byKey("cat")!!
        assertEquals("cat", cat.text(WordLang.EN))
        assertEquals("gato", cat.text(WordLang.ES))
        assertEquals(null, DrawGuessWordBank.byKey("dragon"))
        assertEquals(WordLang.ES, WordLang.fromLanguageTag("es"))
        assertEquals(WordLang.ES, WordLang.fromLanguageTag("ES-MX"))
        assertEquals(WordLang.EN, WordLang.fromLanguageTag("fr"))
        assertEquals(WordLang.EN, WordLang.fromLanguageTag(null))
    }

    // ---- the drawer's three choices ----

    @Test fun threeDistinctChoicesEasyFirstAndNeverAlreadyUsed() {
        for (seed in 1..60) {
            val used = all.shuffled(Random(seed)).take(20).map { it.key }
            val c = DrawGuessWordBank.choices(Random(seed), used)
            assertEquals(3, c.size)
            assertEquals(3, c.map { it.key }.toSet().size)
            assertTrue(c.none { it.key in used })
            assertEquals(listOf(1, 2, 3), c.map { it.difficulty })
        }
    }

    @Test fun choicesAreUsuallyFromDifferentCategories() {
        val diverse = (1..100).count { seed -> DrawGuessWordBank.choices(Random(seed), emptyList()).map { it.category }.toSet().size == 3 }
        assertTrue("diverse $diverse", diverse >= 90)
    }

    @Test fun aLongGameNeverRunsOutOfChoices() {
        val c = DrawGuessWordBank.choices(Random(3), all.map { it.key })
        assertEquals(3, c.size)
    }

    // ---- guessers' cards ----

    @Test fun multipleChoiceAlwaysHasTheAnswerNoDuplicatesAndSameCategoryNeighbours() {
        for (w in all) for (seed in 1..3) {
            val opts = DrawGuessWordBank.multipleChoice(w, Random(seed * 31 + w.key.length))
            assertEquals(6, opts.size)
            assertTrue("answer missing for ${w.key}", opts.any { it.key == w.key })
            assertEquals(6, opts.map { it.key }.toSet().size)
            assertEquals(6, opts.map { it.en }.toSet().size)
            assertEquals(6, opts.map { it.es }.toSet().size)
            assertEquals(6, opts.map { it.glyph }.toSet().size)
            assertTrue(opts.count { it.category == w.category } >= 4)
        }
    }

    @Test fun theAnswerMovesAroundTheCards() {
        val cat = DrawGuessWordBank.byKey("cat")!!
        val positions = (1..60).map { DrawGuessWordBank.multipleChoice(cat, Random(it)).indexOfFirst { w -> w.key == "cat" } }.toSet()
        assertTrue("positions $positions", positions.size >= 4)
    }

    // ---- typed guesses ----

    @Test fun typedGuessesIgnoreCaseAccentsSpacesArticlesAndAPluralS() {
        val ice = DrawGuessWordBank.byKey("ice cream")!!
        for (t in listOf("ice cream", "ICE CREAM", "icecream", "ice-cream", " the ice cream ", "ice creams", "helado", "Helado")) assertTrue(t, DrawGuessWordBank.matches(ice, t))
        val bird = DrawGuessWordBank.byKey("bird")!!
        assertTrue(DrawGuessWordBank.matches(bird, "PÁJARO"))
        assertTrue(DrawGuessWordBank.matches(bird, "pajaro"))
        assertTrue(DrawGuessWordBank.matches(bird, "a bird"))
        val grapes = DrawGuessWordBank.byKey("grapes")!!
        assertTrue(DrawGuessWordBank.matches(grapes, "grape"))
        val cartoon = DrawGuessWordBank.byKey("rainbow")!!
        assertTrue(DrawGuessWordBank.matches(cartoon, "arco iris"))
    }

    @Test fun wrongOrTooShortTypedGuessesFail() {
        val cat = DrawGuessWordBank.byKey("cat")!!
        for (t in listOf("", " ", "c", "dog", "cattle", "ca t t", "gatos y perros")) assertFalse("'$t'", DrawGuessWordBank.matches(cat, t))
        assertNotNull(DrawGuessWordBank.normalize("x"))
    }

    private fun guess(key: String, vararg typed: String) {
        val w = DrawGuessWordBank.byKey(key)!!
        for (t in typed) assertTrue("'$t' should name $key", DrawGuessWordBank.matches(w, t))
    }

    private fun notGuess(key: String, vararg typed: String) {
        val w = DrawGuessWordBank.byKey(key)!!
        for (t in typed) assertFalse("'$t' should not name $key", DrawGuessWordBank.matches(w, t))
    }

    @Test fun englishPluralsIrregularPluralsAndActionFormsAreAccepted() {
        guess("fox", "foxes"); guess("bus", "buses", "bus", "BUSES")
        guess("sandwich", "sandwiches"); guess("tomato", "tomatoes"); guess("potato", "potatoes")
        guess("cherry", "cherries"); guess("strawberry", "strawberries"); guess("butterfly", "butterflies")
        guess("mouse", "mice"); guess("cat", "cats"); guess("glasses", "glass", "glasses")
        guess("jump", "jumping", "to jump"); guess("swim", "swimming"); guess("dance", "dancing")
        guess("run", "running"); guess("sit", "sitting"); guess("fly", "flying"); guess("skate", "skating")
    }

    @Test fun spanishPluralsActionFormsAndLatinAmericanVariantsAreAccepted() {
        guess("fish", "peces"); guess("mouse", "ratones"); guess("lion", "leones")
        guess("laugh", "riendo"); guess("sit", "sentar", "sentarse"); guess("swing", "columpiar", "columpiarse")
        guess("jump", "saltando"); guess("sleep", "durmiendo")
        guess("car", "auto", "carro", "COCHE"); guess("potato", "papa", "patata"); guess("cake", "torta", "tarta", "pastel")
        guess("strawberry", "frutilla", "fresa"); guess("glasses", "lentes", "anteojos", "gafas"); guess("mushroom", "hongo", "seta")
        guess("lollipop", "paleta"); guess("kite", "papalote"); guess("bus", "colectivo", "autobús", "Autobus")
    }

    @Test fun veryShortOrCutOffGuessesDoNotMatch() {
        notGuess("bus", "bu", "b", "us")
        notGuess("glasses", "glasse", "gl")
        notGuess("bus", "autobu")
        notGuess("rainbow", "arcoiri")
        notGuess("cat", "ca", "at", "catt")
        notGuess("cow", "co", "ow")
        // Actions only: a fish is not "fishing", a cat not "catting".
        notGuess("fish", "fishing"); notGuess("cat", "catting")
    }

    @Test fun everyWordMatchesItsOwnNamesAndNoOtherWordsName() {
        for (w in all) {
            assertTrue(w.key, DrawGuessWordBank.matches(w, w.en) && DrawGuessWordBank.matches(w, w.es))
        }
        // No name of one word may be accepted for another (after normalising, plural and accent folding).
        for (a in all) for (b in all) if (a.key != b.key) {
            assertFalse("${a.key} accepts '${b.en}'", DrawGuessWordBank.matches(a, b.en))
            assertFalse("${a.key} accepts '${b.es}'", DrawGuessWordBank.matches(a, b.es))
        }
    }
}
