package com.gamesuite.profiles

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubStart
import com.gamesuite.core.LaunchPolicy
import com.gamesuite.core.PlayMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileGuardTest {
    private fun spec(id: String) = GameHubSpec(
        id, id, "", HubFamily.BOARD,
        listOf(
            HubMode("cpu", HubModeKind.VS_CPU, "cpu", "", id, HubStart.IMMEDIATE),
            HubMode("daily", HubModeKind.DAILY, "daily", "", "$id-daily", HubStart.IMMEDIATE, minSeats = 1, maxSeats = 1),
            HubMode("online", HubModeKind.ONLINE, "online", "", id, HubStart.LOBBY)
        )
    )

    private val all = listOf(spec("tic-tac-toe"), spec("connect-four"), spec("chess"), spec("blackjack"), spec("mystery-adult"))
    private val adult = Profile("default", "Sam", kind = ProfileKind.ADULT)
    private val kid = Profile("k1", "Kid", kind = ProfileKind.KID, ageBand = AgeBand.AGE_7_9)

    private fun access(p: Profile, online: Boolean = true, unlocked: Boolean = false, hidden: Set<String> = emptySet()): ProfileAccess {
        val controls = ControlsState(online, if (p.isKid) mapOf(p.id to KidControls(hiddenGameIds = hidden)) else emptyMap())
        return ProfileAccess.build(p, all, controls, unlocked)
    }

    @After fun reset() { LaunchPolicy.networkAllowed = true }

    @Test fun `kid cannot reach any lobby route even by deep link`() {
        val a = access(kid)
        listOf("nearby-entry/{gameId}", "nearby-host-lobby/{gameId}", "nearby-join-lobby/{gameId}",
            "online-entry/{gameId}", "online-host-lobby/{gameId}", "online-join-lobby/{gameId}", "quick-match", "chat/room")
            .forEach { assertFalse(it, a.allowsRoute(it, "tic-tac-toe")) }
    }

    @Test fun `kid only reaches kids home hubs and visible games`() {
        val a = access(kid)
        assertTrue(a.allowsRoute("kids-home", null))
        assertTrue(a.allowsRoute("hub/{gameId}", "tic-tac-toe"))
        assertTrue(a.allowsRoute("tic-tac-toe", null))
        assertTrue(a.allowsRoute("connect-four-daily", null))
        assertFalse(a.allowsRoute("hub/{gameId}", "blackjack"))
        assertFalse(a.allowsRoute("blackjack", null))
        assertTrue(a.allowsRoute("hub/{gameId}", "chess")) // table: FAMILY, minAge 8 <= band 7-9
        assertFalse(a.allowsRoute("hub/{gameId}", "mystery-adult")) // untagged = adults only
        assertFalse(a.allowsRoute("mystery-adult", null))
    }

    @Test fun `kid cannot open menu settings stats music or unknown routes`() {
        val a = access(kid)
        listOf("menu", "games", "settings", "stats", "music", "uno-custom", "something-new").forEach {
            assertFalse(it, a.allowsRoute(it, null))
        }
    }

    @Test fun `parent area is closed to a kid until the gate was passed`() {
        assertFalse(access(kid).allowsRoute("parent-dashboard", null))
        assertFalse(access(kid).allowsRoute("profile-picker", null))
        assertFalse(access(kid).allowsRoute("profile-add", null))
        assertTrue(access(kid).allowsRoute("parent-gate", null))
        assertTrue(access(kid, unlocked = true).allowsRoute("parent-dashboard", null))
        assertTrue(access(kid, unlocked = true).allowsRoute("profile-picker", null))
    }

    @Test fun `hidden games are blocked at route level for that kid`() {
        val a = access(kid, hidden = setOf("connect-four"))
        assertFalse(a.allowsRoute("hub/{gameId}", "connect-four"))
        assertFalse(a.allowsRoute("connect-four", null))
        assertTrue(a.allowsRoute("tic-tac-toe", null))
    }

    @Test fun `adult reaches everything and kids home redirects away`() {
        val a = access(adult)
        assertTrue(a.allowsRoute("menu", null))
        assertTrue(a.allowsRoute("settings", null))
        assertTrue(a.allowsRoute("online-entry/{gameId}", "chess"))
        assertTrue(a.allowsRoute("profile-picker", null))
        assertFalse(a.allowsRoute("kids-home", null))
        assertEquals("menu", ProfileGuard.homeRoute(adult))
        assertEquals("kids-home", ProfileGuard.homeRoute(kid))
    }

    @Test fun `adult with online play off is blocked from lobbies but not games`() {
        val a = access(adult, online = false)
        assertFalse(a.allowsRoute("online-entry/{gameId}", "chess"))
        assertFalse(a.allowsRoute("nearby-host-lobby/{gameId}", "chess"))
        assertTrue(a.allowsRoute("chess", null))
        assertTrue(a.allowsRoute("hub/{gameId}", "chess"))
        assertFalse(a.networkAllowed)
        assertTrue(a.visibleSpecs.none { it.isNetworked })
    }

    @Test fun `kid access never allows network modes`() {
        val a = access(kid)
        assertFalse(a.networkAllowed)
        assertFalse(a.allowsMode(HubModeKind.ONLINE))
        assertFalse(a.allowsMode(HubModeKind.NEARBY))
        assertTrue(a.allowsMode(HubModeKind.VS_CPU))
        assertTrue(a.visibleSpecs.none { it.isNetworked })
    }

    @Test fun `launch policy blocks network play modes only`() {
        LaunchPolicy.networkAllowed = false
        assertFalse(LaunchPolicy.allows(PlayMode.ONLINE))
        assertFalse(LaunchPolicy.allows(PlayMode.LOCAL_AD_HOC))
        assertTrue(LaunchPolicy.allows(PlayMode.SINGLE_PLAYER_VS_BOT))
        assertTrue(LaunchPolicy.allows(PlayMode.SINGLE_DEVICE_PASS_AND_PLAY))
        LaunchPolicy.networkAllowed = true
        assertTrue(LaunchPolicy.allows(PlayMode.ONLINE))
    }
}
