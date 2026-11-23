package com.gamesuite.profiles

import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.ProfileScope
import com.gamesuite.core.gameIdForRoute
import com.gamesuite.core.visibleFor

/**
 * Everything the navigation guard needs, computed once per change of profile / controls:
 * the visible specs (network modes already stripped where not allowed), whether network play is allowed,
 * and whether a parent has just passed the gate (needed by a KID profile to reach the parent area).
 */
data class ProfileAccess(
    val profile: Profile,
    val visibleSpecs: List<GameHubSpec>,
    val networkAllowed: Boolean,
    val parentUnlocked: Boolean = false,
    private val allSpecs: List<GameHubSpec>
) {
    private val visibleIds: Set<String> = visibleSpecs.map { it.gameId }.toSet()

    fun visibleSpec(gameId: String): GameHubSpec? = visibleSpecs.firstOrNull { it.gameId == gameId }

    fun allowsMode(kind: HubModeKind): Boolean =
        networkAllowed || (kind != HubModeKind.NEARBY && kind != HubModeKind.ONLINE)

    /** A route this profile may open, or false (the shell then redirects to [ProfileGuard.homeRoute]). */
    fun allowsRoute(routePattern: String?, gameIdArg: String?): Boolean =
        ProfileGuard.allows(this, routePattern, gameIdArg, allSpecs)

    companion object {
        fun build(
            profile: Profile,
            allSpecs: List<GameHubSpec>,
            controls: ControlsState,
            parentUnlocked: Boolean
        ): ProfileAccess {
            val network = !profile.isKid && controls.onlinePlayEnabled
            val hidden = if (profile.isKid) controls.forKid(profile.id).hiddenGameIds else emptySet()
            val visible = visibleFor(profile, allSpecs, controls.onlinePlayEnabled, hidden)
            return ProfileAccess(profile, visible, network, parentUnlocked, allSpecs)
        }

        /**
         * THE fail-closed state: a kid-safe profile of the youngest band, no hidden-game list to apply, no Nearby /
         * Online. Used until the profile list and parent controls have been read, when the stored list is
         * unreadable, and for a kid whose stored controls are unreadable. [base] keeps the real kid's identity.
         */
        fun restricted(allSpecs: List<GameHubSpec>, parentUnlocked: Boolean = false, base: Profile? = null): ProfileAccess {
            val profile = (base ?: Profile(
                id = ProfileScope.current(), name = "", kind = ProfileKind.KID, ageBand = AgeBand.AGE_4_6
            )).copy(kind = ProfileKind.KID, ageBand = AgeBand.AGE_4_6)
            return build(profile, allSpecs, ControlsState(onlinePlayEnabled = false), parentUnlocked)
        }

        /**
         * The one place that decides the access for the app: restricted unless BOTH the profile list and the
         * controls have been read; restricted for a kid when the stored controls are unreadable; otherwise [build].
         */
        fun resolve(
            profiles: ProfilesState?,
            controls: ControlsState,
            controlsLoaded: Boolean,
            controlsUnreadable: Boolean,
            parentUnlocked: Boolean,
            allSpecs: List<GameHubSpec>
        ): ProfileAccess {
            if (profiles == null || !controlsLoaded) return restricted(allSpecs, parentUnlocked)
            val active = profiles.active
            if (controlsUnreadable && active.isKid) return restricted(allSpecs, parentUnlocked, active)
            return build(active, allSpecs, controls, parentUnlocked)
        }
    }
}

/** Pure navigation rules (unit tested). Routes are NavDestination patterns ("hub/{gameId}", "sudoku-daily"). */
object ProfileGuard {
    const val KIDS_HOME = "kids-home"
    const val PICKER = "profile-picker"
    const val ADD_PROFILE = "profile-add"
    const val PARENT_GATE = "parent-gate"
    const val PARENT_DASHBOARD = "parent-dashboard"

    private val networkPrefixes = listOf(
        "nearby-entry/", "nearby-host-lobby/", "nearby-join-lobby/",
        "online-entry/", "online-host-lobby/", "online-join-lobby/"
    )

    /** Any Nearby / Online / quick-match / chat route, including ones other agents add later by naming convention. */
    fun isNetworkRoute(route: String?): Boolean {
        val r = route ?: return false
        return networkPrefixes.any { r.startsWith(it) } ||
            r.startsWith("nearby") || r.startsWith("online") || r.startsWith("quick-match") ||
            r.startsWith("matchmaking") || r.startsWith("chat")
    }

    /** Where a blocked profile is sent. */
    fun homeRoute(profile: Profile): String = if (profile.isKid) KIDS_HOME else "menu"

    fun allows(
        access: ProfileAccess,
        routePattern: String?,
        gameIdArg: String?,
        allSpecs: List<GameHubSpec>
    ): Boolean {
        val route = routePattern ?: return true
        if (isNetworkRoute(route) && !access.networkAllowed) return false
        // The profile screens themselves.
        if (route == PICKER || route == ADD_PROFILE) return !access.profile.isKid || access.parentUnlocked
        if (route == PARENT_DASHBOARD) return !access.profile.isKid || access.parentUnlocked
        if (route == KIDS_HOME) return access.profile.isKid
        if (route == PARENT_GATE) return true
        // Adults: everything else is open (network routes were already refused above when Online play is off).
        if (!access.profile.isKid) return true
        // KID: strict allow-list. Hub and game routes only for visible games; every other route
        // (menu, games, stats, settings, music, lobbies, unknown) is closed.
        val id = if (route.startsWith("hub/")) gameIdArg else gameIdForRoute(allSpecs, route)
        return id != null && access.visibleSpec(id) != null
    }
}
