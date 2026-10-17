package com.gamesuite.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameCatalogTest {
    private fun spec(id: String, title: String, tagline: String, family: HubFamily, vararg routes: String) =
        GameHubSpec(
            gameId = id, title = title, tagline = tagline, family = family,
            modes = (if (routes.isEmpty()) listOf(id) else routes.toList()).mapIndexed { i, r ->
                HubMode("m$i", HubModeKind.SOLO, "Mode $i", "", r, HubStart.IMMEDIATE)
            }
        )

    private val specs = listOf(
        spec("chess", "Chess", "The classic with castling.", HubFamily.BOARD),
        spec("tic-tac-toe", "Tic-Tac-Toe", "Three in a row.", HubFamily.BOARD, "tic-tac-toe", "tic-tac-toe-wild"),
        spec("uno", "UNO", "Match colours and numbers.", HubFamily.CARD),
        spec("word-guess", "Word Guess", "Find the hidden word in six tries.", HubFamily.WORD),
        spec("sudoku", "Sudoku", "Fill every row.", HubFamily.ARCADE_PUZZLE)
    )

    private fun ids(list: List<GameHubSpec>) = list.map { it.gameId }

    @Test
    fun blankQueryAndNoFamilyReturnsEverythingInOrder() {
        assertEquals(ids(specs), ids(filterGames(specs, "", null)))
        assertEquals(ids(specs), ids(filterGames(specs, "   ", null)))
    }

    @Test
    fun matchesTitleCaseInsensitively() {
        assertEquals(listOf("chess"), ids(filterGames(specs, "CHESS", null)))
        assertEquals(listOf("uno"), ids(filterGames(specs, "uNo", null)))
    }

    @Test
    fun trimsTheQuery() {
        assertEquals(listOf("sudoku"), ids(filterGames(specs, "  sudoku  ", null)))
    }

    @Test
    fun matchesTagline() {
        assertEquals(listOf("chess"), ids(filterGames(specs, "castling", null)))
        assertEquals(listOf("word-guess"), ids(filterGames(specs, "six tries", null)))
    }

    @Test
    fun gameIdWithDashesReadsAsSpaces() {
        assertEquals(listOf("tic-tac-toe"), ids(filterGames(specs, "tic tac", null)))
        assertEquals(listOf("tic-tac-toe"), ids(filterGames(specs, "tic-tac", null)))
        assertEquals(listOf("word-guess"), ids(filterGames(specs, "word guess", null)))
    }

    @Test
    fun familyFilterNarrowsAndCombinesWithQuery() {
        assertEquals(listOf("chess", "tic-tac-toe"), ids(filterGames(specs, "", HubFamily.BOARD)))
        assertEquals(listOf("tic-tac-toe"), ids(filterGames(specs, "three", HubFamily.BOARD)))
        assertTrue(filterGames(specs, "uno", HubFamily.BOARD).isEmpty())
    }

    @Test
    fun noMatchReturnsEmpty() {
        assertTrue(filterGames(specs, "zzzz", null).isEmpty())
    }

    @Test
    fun orderIsPreserved() {
        // "o" is in several titles/taglines; they must come back in table order.
        val result = ids(filterGames(specs, "o", null))
        assertEquals(result.sortedBy { specs.indexOfFirst { s -> s.gameId == it } }, result)
    }

    @Test
    fun gameIdForRouteFindsPrimaryAndVariantRoutes() {
        assertEquals("chess", gameIdForRoute(specs, "chess"))
        assertEquals("tic-tac-toe", gameIdForRoute(specs, "tic-tac-toe"))
        assertEquals("tic-tac-toe", gameIdForRoute(specs, "tic-tac-toe-wild"))
    }

    @Test
    fun gameIdForRouteIsNullForUnknownRoutes() {
        assertNull(gameIdForRoute(specs, "uno-teams"))
        assertNull(gameIdForRoute(specs, ""))
    }

    @Test
    fun everyRealGameResolvesFromItsOwnRoutesAndHasAGlyph() {
        GameHubSpecs.all.forEach { s ->
            s.routes().forEach { r -> assertEquals(s.gameId, gameIdForRoute(GameHubSpecs.all, r)) }
            assertTrue(gameGlyph(s.gameId).isNotEmpty())
        }
    }

    @Test
    fun groupByFamilyFollowsSectionOrderAndSkipsEmptyFamilies() {
        val grouped = groupByFamily(specs)
        assertEquals(
            listOf(HubFamily.BOARD, HubFamily.CARD, HubFamily.WORD, HubFamily.ARCADE_PUZZLE),
            grouped.map { it.first }
        )
        assertEquals(listOf("chess", "tic-tac-toe"), ids(grouped.first().second))
    }

    @Test
    fun realCatalogSearchFindsDailyGameByWord() {
        val found = ids(filterGames(GameHubSpecs.all, "sudoku", null))
        assertTrue("sudoku" in found)
    }
}
