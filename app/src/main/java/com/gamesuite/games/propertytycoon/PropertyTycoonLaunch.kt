package com.gamesuite.games.propertytycoon

/** Applies the Hub's `LaunchOptions.extras` (keys on [PropertyTycoonHubSpecs]) to a game before [PropertyTycoonGame.startMatch]. */
object PropertyTycoonLaunch {
    fun apply(game: PropertyTycoonGame, extras: Map<String, String>, seats: Int) {
        game.length = PropertyTycoonHubSpecs.parseLength(extras[PropertyTycoonHubSpecs.KEY_LENGTH])
        game.startCashOverride = PropertyTycoonHubSpecs.parseStartPearls(extras[PropertyTycoonHubSpecs.KEY_START_PEARLS])
        game.handicapSeat = PropertyTycoonHubSpecs.parseHandicapSeat(extras[PropertyTycoonHubSpecs.KEY_HANDICAP], seats)
        game.handicapPercent = if (game.handicapSeat >= 0) PropertyTycoonHubSpecs.parseHandicapPercent(extras[PropertyTycoonHubSpecs.KEY_HANDICAP_BOOST]) else 0
        game.houseRules = HouseRules(
            auctions = false,
            jackpot = extras[PropertyTycoonHubSpecs.KEY_JACKPOT] == "true",
            doubleStipendOnLanding = extras[PropertyTycoonHubSpecs.KEY_DOUBLE_STIPEND] == "true",
            rentFreeTimeout = extras[PropertyTycoonHubSpecs.KEY_RENT_FREE_SANDBAR] == "true",
            trades = false,
            pledging = false
        )
    }
}
