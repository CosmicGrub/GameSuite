package com.gamesuite.games.partytoolkit

import com.gamesuite.core.*
import kotlin.random.Random

/**
 * Party Toolkit (roadmap item 21 — see docs/NEW_GAMES_BRAINSTORM.md's own
 * "Boardgame Pal's utility toolkit" entry): a bundled set of board-game-night
 * utilities — Dice, Coin Toss, Random Letter, Scoreboard, Life Points,
 * Hourglass, First Player, and Teams — none of which are a "game" in the
 * [GameModule] sense at all. None has a win condition, most have no "match"
 * with a real start/end, and two (Scoreboard, Life Points) are literally
 * just persistent counters. Forcing each into its own `startMatch()`/
 * `endMatch(GameResult)` shape (which assumes winners/losers/scores tied to
 * a match) would be a bad architectural fit purely to reuse a shell built
 * for a different kind of thing.
 *
 * Per the brainstorm doc's own resolved "open architecture question": this
 * registers as ONE TOKEN [GameModule] purely so it appears in the existing
 * menu/nav plumbing, with [startMatch]/[endMatch] as genuine no-ops rather
 * than a top-level menu section outside the [GameModule]/`GameSection`
 * system entirely — chosen for menu-integration consistency (one more
 * `composable(...)` route, one more `GameEntry`, nothing shell-specific to
 * special-case) over the alternative's real cost (a second, parallel
 * navigation/launch path the shell would need to know about). The actual 8
 * tools live entirely inside `PartyToolkitScreen`'s own internal tab
 * navigation, not as 8 separate [GameModule]s or menu entries — see that
 * file's own KDoc.
 *
 * [leaveToolkit] reports an unscored [GameResult] (`scores = emptyList()`)
 * on the way out — there is no winner/loser here, just "the player used the
 * toolkit," so nothing games-specific is fabricated to satisfy the shell's
 * usual match-result shape.
 */
class PartyToolkitGame : GameModule {
    override val gameId = "party-toolkit"
    override val displayName = "Party Toolkit"
    override val category = GameCategory.UTILITY
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    override fun init(context: GameContext) {
        this.context = context
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    /** No-op — see the class KDoc for why there's no real "match" to start. */
    override fun startMatch() {}

    /** No-op — no live match state (timers, turn order) for the shell to protect by pausing. */
    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        onMatchEnd?.invoke(result)
    }

    /** Called from the toolkit screen's "Back to Menu" button. */
    fun leaveToolkit() {
        endMatch(GameResult())
    }
}

/**
 * Pure, stateless, independently-testable logic for every "random pick"
 * tool in the toolkit (Dice/Coin Toss/Random Letter/First Player/Teams).
 * [random] is threaded through (rather than each function reaching for the
 * global default) so tests can supply a seeded generator for reproducible
 * assertions — same idiom every other engine's own generator functions in
 * this app use (e.g. SlidingPuzzleGame.scramble/ColorFloodGame.startMatch).
 * The Scoreboard/Life Points/Hourglass tools have no comparable "logic" of
 * their own worth extracting here — they're just a list of named counters
 * (persisted via [com.gamesuite.games.partytoolkit.PartyToolkitStore]) or a
 * plain countdown, both driven directly from `PartyToolkitScreen`'s own
 * Compose state.
 */
object PartyToolkitLogic {
    /** Rolls [count] dice, each `1..sides` (a standard d6 by default). [count] < 1 returns an empty list rather than throwing — the UI clamps its own stepper to >= 1 anyway, but this stays safe called directly too. */
    fun rollDice(count: Int, sides: Int = 6, random: Random = Random): List<Int> {
        if (count < 1 || sides < 1) return emptyList()
        return List(count) { random.nextInt(1, sides + 1) }
    }

    /** True = heads, false = tails. */
    fun flipCoin(random: Random = Random): Boolean = random.nextBoolean()

    /** A uniformly random uppercase letter, A-Z. */
    fun randomLetter(random: Random = Random): Char = 'A' + random.nextInt(26)

    /** One uniformly random name from [names], or null if the list is empty — never crashes on an empty roster. */
    fun pickFirstPlayer(names: List<String>, random: Random = Random): String? = names.randomOrNull(random)

    /**
     * Splits [names] into [teamCount] teams as evenly as possible via a full
     * shuffle-then-round-robin-deal — every team's size differs from any
     * other's by at most 1 (never a size-4 team next to a size-1 team just
     * because of leftover remainder), and WHICH names land on the larger
     * team(s) is still fully random since the shuffle happens first.
     * [teamCount] is clamped to `1..names.size` (never more teams than
     * players, never fewer than 1) rather than throwing on an out-of-range
     * request.
     */
    fun splitIntoTeams(names: List<String>, teamCount: Int, random: Random = Random): List<List<String>> {
        if (names.isEmpty()) return emptyList()
        val clampedCount = teamCount.coerceIn(1, names.size)
        val shuffled = names.shuffled(random)
        val teams = List(clampedCount) { mutableListOf<String>() }
        shuffled.forEachIndexed { i, name -> teams[i % clampedCount].add(name) }
        return teams
    }
}
