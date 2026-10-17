package com.gamesuite.core

/**
 * The one way the Hub and the menu start a game from a [GameHubSpec] mode, so a card, the Play
 * button, a Daily shortcut and the Continue row all behave identically.
 *
 * For a local mode this installs the launch on [sessionManager] and returns [HubLaunch.Local]
 * (the caller navigates to its `route`); a fresh start discards any save made under the same
 * launch, because choosing "new game" replaces it (Resume is a separate, explicit path). For a
 * Nearby / Online mode nothing is launched: the returned [HubLaunch.Lobby] says which lobby to open.
 */
fun launchHubMode(
    sessionManager: GameSessionManager,
    spec: GameHubSpec,
    mode: HubMode,
    choices: HubChoices,
    names: List<String> = emptyList()
): HubLaunch {
    val result = buildHubLaunch(spec, mode, choices, names)
    if (result is HubLaunch.Local) {
        val launch = result.launch
        sessionManager.launchGame(launch.mode, launch.players, launch.localPlayerIndex, launch.options)
        val ctx = sessionManager.activeContext.value
        if (ctx != null) sessionManager.savedGames?.delete(sessionManager.saveKey(result.route, ctx))
    }
    return result
}
