package com.gamesuite.core

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/** Every Hub game (and the upcoming ones) has a coach entry with its text in English and Spanish. */
class GameCoachTest {
    private fun moduleFile(relative: String): File {
        val here = File("src/main/$relative")
        if (here.exists()) return here
        val fromRoot = File("app/src/main/$relative")
        if (fromRoot.exists()) return fromRoot
        error("Cannot find $relative")
    }

    private fun strings(relative: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(relative))
        val out = linkedMapOf<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") out[e.getAttribute("name")] = e.textContent
        }
        return out
    }

    private fun coachStrings(relativeDir: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        moduleFile(relativeDir).listFiles { f -> f.isFile && f.name.endsWith("coach_strings.xml") }!!.sortedBy { it.name }
            .forEach { out.putAll(strings("$relativeDir/${it.name}")) }
        return out
    }

    private val en = coachStrings("res/values")
    private val es = coachStrings("res/values-es")
    private val allIds = GameHubSpecs.all.map { it.gameId } + GameCoach.upcomingIds

    @Test
    fun everyHubGameAndUpcomingGameHasAnEntry() {
        allIds.forEach { id -> assertNotNull("coach entry for $id", GameCoach.forGame(id)) }
        assertTrue(GameCoach.upcomingIds.containsAll(listOf("gomoku", "hex", "shogi", "mini-shogi", "ludo")))
    }

    @Test
    fun stepsAreThreeToSix() {
        allIds.forEach { id -> assertTrue("$id steps 3..6", GameCoach.forGame(id)!!.steps.size in 3..6) }
    }

    @Test
    fun everyStringExistsInBothLocalesAndSpanishIsTranslated() {
        allIds.forEach { id ->
            val e = GameCoach.forGame(id)!!
            val names = (1..e.steps.size).map { GameCoach.stepName(id, it) } + GameCoach.factName(id)
            names.forEach { n ->
                assertTrue("en has $n", en[n]?.isNotBlank() == true)
                assertTrue("es has $n", es[n]?.isNotBlank() == true)
                assertTrue("es differs from en for $n", es[n] != en[n])
            }
        }
    }

    @Test
    fun resourceIdsMatchTheNames() {
        val fields = Class.forName("com.gamesuite.R\$string").fields.associate { it.name to it.getInt(null) }
        allIds.forEach { id ->
            val e = GameCoach.forGame(id)!!
            e.steps.forEachIndexed { i, res -> assertEquals("$id step ${i + 1}", fields[GameCoach.stepName(id, i + 1)], res) }
            assertEquals("$id fact", fields[GameCoach.factName(id)], e.fact)
        }
    }

    @Test
    fun localesHaveTheSameKeysAndNoStrays() {
        assertEquals(en.keys, es.keys)
        val expected = allIds.flatMap { id ->
            (1..GameCoach.forGame(id)!!.steps.size).map { GameCoach.stepName(id, it) } + GameCoach.factName(id)
        }.toSet()
        assertEquals(expected, en.keys)
    }

    @Test
    fun apostrophesAreEscapedInTheRawFiles() {
        val bad = Regex("(?<!\\\\)'")
        listOf(
            "res/values/coach_strings.xml", "res/values-es/coach_strings.xml",
            "res/values/club_strings.xml", "res/values-es/club_strings.xml"
        ).forEach { f -> assertTrue("$f has an unescaped apostrophe", !bad.containsMatchIn(moduleFile(f).readText())) }
    }

    @Test
    fun clubStringsExistInBothLocales() {
        val ce = strings("res/values/club_strings.xml")
        val cs = strings("res/values-es/club_strings.xml")
        assertEquals(ce.keys, cs.keys)
        AvatarCharacter.values().forEach { assertTrue(ce.containsKey("club_avatar_${it.id}")) }
        AvatarColor.values().forEach { assertTrue(ce.containsKey("club_color_${it.id}")) }
        AvatarFrame.values().forEach { assertTrue(ce.containsKey("club_frame_${it.id}")) }
    }

    @Test
    fun hubAutoOpensTheCoachOnlyWhereTheGameHasNoCardOfItsOwn() {
        // Games with their own first-run card never get a second one from the Hub.
        GameCoach.ownCoachInGame.forEach { assertTrue("$it has an entry", GameCoach.forGame(it) != null) }
        GameCoach.ownCoachInGame.forEach { assertTrue("$it skipped", !GameCoach.autoOpensInHub(it)) }
        // Chinese Checkers / Ludo share ClubProfile.coachSeen with the Hub, so the Hub may open them.
        assertTrue(GameCoach.autoOpensInHub("ludo"))
        assertTrue(GameCoach.autoOpensInHub("chess"))
        // No entry, no card.
        assertTrue(!GameCoach.autoOpensInHub("not-a-game"))
    }
}
