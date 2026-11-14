package com.gamesuite.games.propertytycoon

import com.gamesuite.core.GameAudience
import com.gamesuite.core.GameHubSpec
import com.gamesuite.core.HubChoice
import com.gamesuite.core.HubChoices
import com.gamesuite.core.HubFamily
import com.gamesuite.core.HubKeys
import com.gamesuite.core.HubMode
import com.gamesuite.core.HubModeKind
import com.gamesuite.core.HubOption
import com.gamesuite.core.HubOptions
import com.gamesuite.core.HubStart

/**
 * The Game Hub spec of Property Tycoon. NOT REGISTERED ANYWHERE: the coordinator adds `PropertyTycoonHubSpecs.propertyTycoon`
 * to `GameHubSpecs.baseSpecs()` (BOARD section) and must NOT list "property-tycoon" in `networkedGames` (same-screen only
 * in this version). Every text below has a `hub_*` string (name = hubKey(text)) in res/values{,-es}/property_tycoon_strings.xml,
 * except the shared ones that already exist (mode titles, "People playing", "Computer players", the computer levels, ...).
 *
 * AUDIENCE: [GameAudience.ADULTS] with minAge = 8. ADULTS keeps the game out of every KID profile (buying, auctions and
 * bankruptcy are the theme). The 8+ minAge is the age an adult would reasonably teach it at and is what a future gentler
 * "Junior" variant (no bankruptcy, FAMILY audience) would inherit. Do NOT add a GameAudienceTable row.
 *
 * Option keys reach the screen as `LaunchOptions.extras`: see the `KEY_*` constants and the parse helpers at the bottom.
 * The MVP release hides nothing here, but the engine ignores `auctions`, `trades` and pledging until [PtRelease] flips them.
 */
object PropertyTycoonHubSpecs {
    const val KEY_LENGTH = "length"
    const val KEY_START_PEARLS = "startPearls"
    const val KEY_HANDICAP = "handicap"
    const val KEY_HANDICAP_BOOST = "handicapBoost"
    const val KEY_AUCTIONS = "auctions"
    const val KEY_JACKPOT = "jackpot"
    const val KEY_DOUBLE_STIPEND = "doubleStipend"
    const val KEY_RENT_FREE_SANDBAR = "rentFreeSandbar"
    const val KEY_TRADES = "trades"

    /** Identity colours for the integrator to add to `GameThemes.accents["property-tycoon"]` (emerald). UNVERIFIED: run the contrast check. */
    const val ACCENT_LIGHT: Long = 0xFF0A6B4B
    const val ACCENT_DARK: Long = 0xFF4FD6A0

    /** The menu glyph for the integrator to add to `GameCatalog` (desert island). Check no other game uses it. */
    const val GLYPH: String = "🏝"

    private val length = HubOption.Choice(
        key = KEY_LENGTH, label = "Game length", default = "standard",
        choices = listOf(
            HubChoice("quick", "Quick: 20 rounds", "Doubled rents. The richest player wins."),
            HubChoice("standard", "Standard: last one standing", "Rents rise late so a game always ends."),
            HubChoice("marathon", "Marathon: very long", "More pearls and gentler rents.")
        )
    )

    private val startPearls = HubOption.Choice(
        key = KEY_START_PEARLS, label = "Starting pearls", default = "auto",
        help = "Play money only. Everyone starts equal.",
        choices = listOf(
            HubChoice("auto", "Set by game length"),
            HubChoice("1000", "1,000 pearls"),
            HubChoice("1600", "1,600 pearls"),
            HubChoice("2500", "2,500 pearls")
        )
    )

    private val handicap = HubOption.Choice(
        key = KEY_HANDICAP, label = "Head start", default = "none",
        help = "Give one player extra pearls to even things out.",
        choices = listOf(
            HubChoice("none", "No head start"),
            HubChoice("1", "Player 1 gets extra"),
            HubChoice("2", "Player 2 gets extra"),
            HubChoice("3", "Player 3 gets extra"),
            HubChoice("4", "Player 4 gets extra"),
            HubChoice("5", "Player 5 gets extra"),
            HubChoice("6", "Player 6 gets extra")
        )
    )

    private val handicapBoost = HubOption.Choice(
        key = KEY_HANDICAP_BOOST, label = "Head start size", default = "25",
        help = "Extra pearls on top of the starting amount.",
        choices = listOf(
            HubChoice("25", "Plus 25 percent"),
            HubChoice("50", "Plus 50 percent"),
            HubChoice("100", "Plus 100 percent")
        ),
        visibleWhen = { it.string(KEY_HANDICAP, "none") != "none" }
    )

    private val auctions = HubOption.Toggle(
        key = KEY_AUCTIONS, label = "Auction unbought tiles", default = true,
        help = "A tile nobody buys goes to the highest bidder."
    )
    private val jackpot = HubOption.Toggle(
        key = KEY_JACKPOT, label = "Free Anchorage jackpot", default = false,
        help = "Dues and fines pile up for whoever lands there."
    )
    private val doubleStipend = HubOption.Toggle(
        key = KEY_DOUBLE_STIPEND, label = "Double stipend on the Quay", default = false,
        help = "Landing exactly on Lantern Quay pays twice."
    )
    private val rentFreeSandbar = HubOption.Toggle(
        key = KEY_RENT_FREE_SANDBAR, label = "Rent-free Sandbar", default = false,
        help = "No rent is collected from a player stuck there."
    )
    private val trades = HubOption.Toggle(
        key = KEY_TRADES, label = "Allow trades", default = true,
        help = "Players may swap tiles and pearls."
    )

    private val common = listOf(length, startPearls, handicap, handicapBoost, auctions, jackpot, doubleStipend, rentFreeSandbar, trades)

    val propertyTycoon: GameHubSpec = GameHubSpec(
        gameId = "property-tycoon", title = "Property Tycoon",
        tagline = "Buy islands, collect rent, outlast your rivals.",
        family = HubFamily.BOARD,
        audience = GameAudience.ADULTS, minAge = 8,
        modes = listOf(
            HubMode(
                id = "cpu", kind = HubModeKind.VS_CPU, title = "vs Computers",
                blurb = "Build your empire against 1 to 5 computers.",
                route = "property-tycoon", start = HubStart.IMMEDIATE, defaultCpus = 2, minSeats = 2, maxSeats = 6,
                options = common + listOf(HubOptions.cpus(1, 5, 2), HubOptions.difficulty(), HubOptions.firstMover("The computer"))
            ),
            HubMode(
                id = "pass", kind = HubModeKind.PASS_AND_PLAY, title = "Pass & Play",
                blurb = "Friends share this device. Computers can fill empty seats.",
                route = "property-tycoon", start = HubStart.READY_SCREEN, minSeats = 2, maxSeats = 6,
                options = common + listOf(
                    HubOptions.humans(2, 6, 2), HubOptions.cpus(0, 4, 0),
                    HubOptions.difficulty().copy(visibleWhen = { it.int(HubKeys.CPUS, 0) > 0 }),
                    HubOptions.firstMover("Player 2", "Player 1")
                )
            )
        )
    )

    val all: List<GameHubSpec> = listOf(propertyTycoon)

    /** The Hub's `length` extra: "quick" | "standard" | "marathon" (anything else = standard). */
    fun parseLength(value: String?): GameLength = when (value?.trim()?.lowercase()) {
        "quick" -> GameLength.QUICK
        "marathon" -> GameLength.MARATHON
        else -> GameLength.STANDARD
    }

    /** The Hub's `startPearls` extra: null for "auto" / unknown (use the length's amount), else the amount. */
    fun parseStartPearls(value: String?): Int? = when (value?.trim()) {
        "1000" -> 1000
        "1600" -> 1600
        "2500" -> 2500
        else -> null
    }

    /** The Hub's `handicap` extra: the 0-based seat that gets the head start, or -1 for none / unknown / out of range. */
    fun parseHandicapSeat(value: String?, seats: Int): Int {
        val n = value?.trim()?.toIntOrNull() ?: return -1
        return if (n in 1..seats) n - 1 else -1
    }

    /** The Hub's `handicapBoost` extra: 25, 50 or 100 (anything else = 25). */
    fun parseHandicapPercent(value: String?): Int = when (value?.trim()) {
        "50" -> 50
        "100" -> 100
        else -> PtRules.HANDICAP_FIXED_STEP
    }

    /** House rules from the resolved Hub choices. */
    fun houseRulesOf(choices: HubChoices): HouseRules = HouseRules(
        auctions = choices.bool(KEY_AUCTIONS, true),
        jackpot = choices.bool(KEY_JACKPOT, false),
        doubleStipendOnLanding = choices.bool(KEY_DOUBLE_STIPEND, false),
        rentFreeTimeout = choices.bool(KEY_RENT_FREE_SANDBAR, false),
        trades = choices.bool(KEY_TRADES, true)
    )
}
