package com.gamesuite.core

import com.gamesuite.ui.Wcag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClubProfileTest {
    @Test
    fun avatarEncodeDecodeRoundTripsEveryCombination() {
        for (c in AvatarCharacter.values()) for (col in AvatarColor.values()) for (f in AvatarFrame.values()) {
            val a = Avatar(c, col, f)
            assertEquals(a, Avatar.decode(a.encode()))
        }
    }

    @Test
    fun decodeIsLenient() {
        assertEquals(Avatar.DEFAULT, Avatar.decode(null))
        assertEquals(Avatar.DEFAULT, Avatar.decode(""))
        assertEquals(Avatar.DEFAULT, Avatar.decode("garbage"))
        assertEquals(Avatar(AvatarCharacter.REX, AvatarColor.SKY, AvatarFrame.CIRCLE), Avatar.decode("rex|nope|"))
        assertEquals(Avatar(AvatarCharacter.PIP, AvatarColor.GREEN, AvatarFrame.HEXAGON), Avatar.decode("?|green|hexagon|extra"))
    }

    @Test
    fun thereAreSixteenCharactersWithDistinctLooks() {
        val all = AvatarCharacter.values()
        assertEquals(16, all.size)
        assertEquals(16, all.map { it.id }.toSet().size)
        assertEquals(16, all.map { listOf(it.ears, it.eyes, it.mouth, it.prop) }.toSet().size)
        assertEquals(8, AvatarColor.values().size)
        assertEquals(8, AvatarColor.values().map { it.argb }.toSet().size)
    }

    @Test
    fun faceInkAlwaysContrastsWithEveryBodyColour() {
        AvatarColor.values().forEach { c ->
            val ink = AvatarPalette.inkFor(c.argb)
            assertTrue("${c.id} ink contrast", Wcag.contrast(ink, c.argb) >= 4.5)
        }
    }

    @Test
    fun seedAvatarsAreStableAndSpread() {
        assertEquals(Avatar.forSeed("Alex"), Avatar.forSeed("Alex"))
        val distinct = (0 until 200).map { Avatar.forSeed("player$it") }.toSet().size
        assertTrue("seeds spread out ($distinct)", distinct > 40)
        Avatar.forSeed("")
        Avatar.forSeed("😀")
    }

    @Test
    fun nameUsesTheSharedCleaningRule() {
        val p = ClubProfile().withName("   Sam   the   Great and very very long  ")
        assertTrue(p.displayName.length <= MAX_PLAYER_NAME_LENGTH)
        assertFalse(p.displayName.contains("  "))
        assertEquals("Player", ClubProfile().displayNameOr("Player"))
        assertEquals("Sam", ClubProfile().withName(" Sam ").displayNameOr("Player"))
        assertEquals("", ClubProfile().withName("   ").displayName)
    }

    @Test
    fun favouriteMustBeAKnownGame() {
        val known = GameHubSpecs.all.map { it.gameId }.toSet()
        assertEquals("chess", ClubProfile().withFavorite("chess", known).favoriteGameId)
        assertNull(ClubProfile(favoriteGameId = "chess").withFavorite("nope", known).favoriteGameId)
        assertNull(ClubProfile(favoriteGameId = "chess").withFavorite(null, known).favoriteGameId)
    }

    @Test
    fun coachShowsOnlyTheFirstTime() {
        val p = ClubProfile()
        assertTrue(p.shouldAutoCoach("uno"))
        val seen = p.markCoachSeen("uno")
        assertFalse(seen.shouldAutoCoach("uno"))
        assertTrue(seen.shouldAutoCoach("chess"))
        assertEquals(seen, seen.markCoachSeen("uno"))
    }
}
