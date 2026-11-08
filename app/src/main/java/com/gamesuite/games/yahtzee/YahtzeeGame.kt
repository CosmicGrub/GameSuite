package com.gamesuite.games.yahtzee

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.settings.CpuDifficulty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** One seat at the table with its scorecard. */
data class YahtzeePlayerState(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val card: YahtzeeScorecard = YahtzeeScorecard()
)

/** What the latest category choice did, so the screen can announce it. */
data class YahtzeeLastScore(
    val playerIndex: Int,
    val category: YahtzeeCategory,
    val points: Int,
    /** The 100-point Yahtzee bonus earned by the same choice (0 or 100). */
    val bonus: Int
)

/**
 * A whole Yahtzee game in progress. [dice] holds 0 for a die that has not been rolled yet this turn.
 * [rolled] marks the dice the latest roll changed (the screen animates exactly those); [rollSerial]
 * counts rolls so two identical rolls in a row still animate, and [version] changes on every edit so a
 * bot-driving effect can key on it.
 */
data class YahtzeeState(
    val players: List<YahtzeePlayerState>,
    val currentPlayerIndex: Int,
    /** The seat that opened this game; round 1 starts there and every round starts there. */
    val firstSeat: Int,
    /** 1..13 */
    val round: Int,
    val dice: List<Int>,
    val held: List<Boolean>,
    /** Rolls still available this turn: 3 before the first roll, 0 when a box must be chosen. */
    val rollsLeft: Int,
    val rolled: List<Boolean> = List(YahtzeeScoring.DICE) { false },
    val rollSerial: Int = 0,
    val version: Int = 0,
    val gameOver: Boolean = false,
    val lastScored: YahtzeeLastScore? = null
) {
    val hasRolled: Boolean get() = rollsLeft < 3
    val currentPlayer: YahtzeePlayerState get() = players[currentPlayerIndex]
}

@Serializable
private data class YahtzeeSnapshotPlayer(
    val playerId: String,
    val displayName: String,
    val isBot: Boolean,
    val scores: List<Int?>,
    val yahtzeeBonuses: Int
)

/**
 * Everything needed to put a Yahtzee game back as it was, and nothing about the random generator:
 * dice, holds, rolls left, every scorecard, whose turn, the session tally and the difficulty. The
 * same shape serves Resume (on disk) and Undo (in memory).
 */
@Serializable
private data class YahtzeeSnapshot(
    val players: List<YahtzeeSnapshotPlayer>,
    val currentPlayerIndex: Int,
    val firstSeat: Int,
    val nextFirstSeat: Int,
    val round: Int,
    val dice: List<Int>,
    val held: List<Boolean>,
    val rollsLeft: Int,
    val sessionWins: Map<String, Int>,
    val gamesFinished: Int,
    val sessionBest: Map<String, Int>,
    val difficulty: String
)

private val yahtzeeSnapshotJson = Json { ignoreUnknownKeys = true }

/**
 * Yahtzee for 1 to 4 players on one device, with computer seats.
 *
 * TURN: up to three rolls. The first roll throws all five dice; before each reroll the player holds
 * the dice to keep ([toggleHold]) and the rest are rerolled ([roll]). After the last roll (or any
 * time after the first) the player fills one open box ([chooseCategory]). A game is 13 rounds.
 *
 * SCORING lives in [YahtzeeScoring]: upper boxes count their face, a bonus of 35 at 63 or more,
 * 3 / 4 of a kind score the sum, full house 25, small straight 30, large straight 40, Yahtzee 50,
 * chance the sum. A further Yahtzee on top of a 50 earns 100 and the forced Joker rule decides where
 * it may go (see [YahtzeeScoring.legalCategories]).
 *
 * UNDO: a hold toggle or a category choice can be taken back until the next roll; a roll can never be
 * undone because it is random. A human roll clears the history; bot actions do not, so against the CPU
 * undoing your category choice also takes the CPU's reply turns back (their dice are re-thrown fresh).
 *
 * CPU ([difficulty]): EASY holds a favourite face by chance and picks boxes half at random; MEDIUM is
 * greedy (looks one reroll ahead); HARD searches every hold for the best expected value over the
 * whole turn. All randomness comes from the injected [random].
 */
class YahtzeeGame(private val random: Random = Random.Default) : GameModule, ResumableGame, UndoableGame {
    override val gameId = "yahtzee"
    override val displayName = "Yahtzee"
    override val category = GameCategory.BOARD
    override val minPlayers = 1
    override val maxPlayers = 4
    override val supportedModes = listOf(
        PlayMode.SINGLE_PLAYER_VS_BOT,
        PlayMode.SINGLE_DEVICE_PASS_AND_PLAY
    )

    val state = mutableStateOf<YahtzeeState?>(null)

    /** Games won this session by playerId (a tied top score counts for nobody). */
    val sessionWins = mutableStateOf<Map<String, Int>>(emptyMap())

    /** Finished games this session. */
    val gamesFinished = mutableStateOf(0)

    /** Best final total each player reached this session. */
    val sessionBest = mutableStateOf<Map<String, Int>>(emptyMap())

    /** Solo: true when the game that just ended beat an earlier game's score in this session. */
    val lastGameNewBest = mutableStateOf(false)

    /** True only once the whole session ends. */
    val matchOver = mutableStateOf(false)

    /** Set by the screen from the launch options before [startMatch]. */
    var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM

    /** The seat that opens the first game of a new session; consumed by [startMatch]. Null = seat 0. */
    var firstOpenerIndex: Int? = null

    /** The die positions the last [hint] suggests holding, shown until the next change. */
    val hintHolds = mutableStateOf<List<Boolean>?>(null)

    /** The box the last [hint] suggests filling, shown until the next change. */
    val hintCategory = mutableStateOf<YahtzeeCategory?>(null)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null
    private var nextFirstSeat = 0
    private var stateVersion = com.gamesuite.core.SyncEpoch.base()
    private var botHoldsSet = false
    private val history = SnapshotHistory()

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun init(context: GameContext) {
        this.context = context
        matchOver.value = false
        sessionWins.value = context.players.associate { it.playerId to 0 }
        sessionBest.value = emptyMap()
        gamesFinished.value = 0
        lastGameNewBest.value = false
        nextFirstSeat = 0
        stateVersion = com.gamesuite.core.SyncEpoch.base()
        botHoldsSet = false
        history.clear()
        clearHints()
    }

    override fun startMatch() {
        matchOver.value = false
        dealNewGame()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    private fun clearHints() {
        hintHolds.value = null
        hintCategory.value = null
    }

    private fun commit(s: YahtzeeState) {
        stateVersion++
        state.value = s.copy(version = stateVersion)
    }

    private fun dealNewGame() {
        val n = context.players.size
        val first = (firstOpenerIndex ?: nextFirstSeat).coerceIn(0, n - 1)
        firstOpenerIndex = null
        nextFirstSeat = (first + 1) % n
        history.clear()
        clearHints()
        botHoldsSet = false
        commit(
            YahtzeeState(
                players = context.players.map { YahtzeePlayerState(it.playerId, it.displayName, it.isBot) },
                currentPlayerIndex = first,
                firstSeat = first,
                round = 1,
                dice = List(YahtzeeScoring.DICE) { 0 },
                held = List(YahtzeeScoring.DICE) { false },
                rollsLeft = 3
            )
        )
    }

    // ---- Human actions -------------------------------------------------------------------------

    /** Rolls the dice that are not held (all five on the first roll). False when it is not allowed right now. */
    fun roll(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.gameOver || s.rollsLeft <= 0) return false
        if (s.currentPlayer.isBot) return false
        return doRoll(s)
    }

    private fun doRoll(s: YahtzeeState): Boolean {
        val first = s.rollsLeft == 3
        // Rerolling with every die held would burn a roll for nothing.
        if (!first && s.held.all { it }) return false
        // A roll cannot be undone, so nothing before it can be either (a bot's roll leaves the human's history alone).
        if (!s.currentPlayer.isBot) history.clear()
        clearHints()
        botHoldsSet = false
        val mask = List(YahtzeeScoring.DICE) { first || !s.held[it] }
        val dice = s.dice.mapIndexed { i, d -> if (mask[i]) random.nextInt(1, 7) else d }
        commit(
            s.copy(
                dice = dice,
                held = if (first) List(YahtzeeScoring.DICE) { false } else s.held,
                rollsLeft = s.rollsLeft - 1,
                rolled = mask,
                rollSerial = s.rollSerial + 1
            )
        )
        return true
    }

    /** Holds or releases one die. Only between rolls (after the first roll, while a reroll remains). */
    fun toggleHold(index: Int): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.gameOver || s.currentPlayer.isBot) return false
        if (s.rollsLeft !in 1..2 || index !in 0 until YahtzeeScoring.DICE) return false
        captureSnapshot()?.let { history.push(it) }
        clearHints()
        commit(s.copy(held = s.held.mapIndexed { i, h -> if (i == index) !h else h }, rolled = List(YahtzeeScoring.DICE) { false }))
        return true
    }

    /** The boxes the current player may fill with the dice showing (empty before the first roll). */
    fun legalCategories(): List<YahtzeeCategory> {
        val s = state.value ?: return emptyList()
        if (s.gameOver || !s.hasRolled) return emptyList()
        return YahtzeeScoring.legalCategories(s.dice, s.currentPlayer.card)
    }

    /** Fills [category] with the current dice. False when not allowed (not rolled yet, box taken, Joker rule). */
    fun chooseCategory(category: YahtzeeCategory): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.gameOver || s.currentPlayer.isBot) return false
        if (!s.hasRolled || category !in legalCategories()) return false
        captureSnapshot()?.let { history.push(it) }
        return applyCategory(s, category)
    }

    private fun applyCategory(s: YahtzeeState, category: YahtzeeCategory): Boolean {
        val player = s.currentPlayer
        val card = player.card
        val points = YahtzeeScoring.scoreFor(category, s.dice, card)
        val bonus = YahtzeeScoring.bonusFor(s.dice, card)
        val newCard = card.filled(category, points).let {
            if (bonus > 0) it.copy(yahtzeeBonuses = it.yahtzeeBonuses + 1) else it
        }
        val players = s.players.mapIndexed { i, p -> if (i == s.currentPlayerIndex) p.copy(card = newCard) else p }
        val scored = YahtzeeLastScore(s.currentPlayerIndex, category, points, bonus)
        clearHints()
        botHoldsSet = false
        val blank = List(YahtzeeScoring.DICE) { false }
        if (players.all { it.card.isComplete }) {
            commit(s.copy(players = players, held = blank, rolled = blank, rollsLeft = 0, gameOver = true, lastScored = scored))
            recordFinishedGame(players)
            return true
        }
        val next = (s.currentPlayerIndex + 1) % players.size
        commit(
            s.copy(
                players = players,
                currentPlayerIndex = next,
                round = if (next == s.firstSeat) s.round + 1 else s.round,
                dice = List(YahtzeeScoring.DICE) { 0 },
                held = blank,
                rolled = blank,
                rollsLeft = 3,
                lastScored = scored
            )
        )
        return true
    }

    private fun recordFinishedGame(players: List<YahtzeePlayerState>) {
        val totals = players.map { it.card.total }
        val top = totals.maxOrNull() ?: 0
        val leaders = totals.indices.filter { totals[it] == top }
        if (leaders.size == 1 && players.size > 1) {
            val id = players[leaders[0]].playerId
            sessionWins.value = sessionWins.value + (id to ((sessionWins.value[id] ?: 0) + 1))
        }
        val best = sessionBest.value.toMutableMap()
        var newBest = false
        players.forEachIndexed { i, p ->
            val previous = best[p.playerId]
            if (previous == null || totals[i] > previous) {
                if (previous != null) newBest = true
                best[p.playerId] = totals[i]
            }
        }
        sessionBest.value = best
        lastGameNewBest.value = newBest && players.size == 1
        gamesFinished.value = gamesFinished.value + 1
    }

    /** Final standings of a finished (or running) game: seat indices ordered by total, highest first (stable). */
    fun ranking(): List<Int> {
        val s = state.value ?: return emptyList()
        return s.players.indices.sortedByDescending { s.players[it].card.total }
    }

    // ---- Session -------------------------------------------------------------------------------

    /** Ends the session and reports it: solo = the best score reached; with company = games won. */
    fun leaveSession() {
        if (matchOver.value) return
        val players = context.players
        val scores = if (players.size == 1) {
            val p = players[0]
            listOf(PlayerScore(p.playerId, sessionBest.value[p.playerId] ?: 0, isWinner = gamesFinished.value > 0))
        } else {
            val wins = sessionWins.value
            val bestWins = wins.values.maxOrNull() ?: 0
            val leaders = players.count { (wins[it.playerId] ?: 0) == bestWins }
            players.map {
                val w = wins[it.playerId] ?: 0
                PlayerScore(it.playerId, w, isWinner = bestWins > 0 && w == bestWins && leaders == 1)
            }
        }
        endMatch(GameResult(scores = scores))
    }

    /** Deals a new game within the same session (the tally is kept, the opener rotates). */
    fun playAgain() {
        if (matchOver.value) return
        if (state.value?.gameOver != true) return
        dealNewGame()
    }

    // ---- Undo ----------------------------------------------------------------------------------

    override val canUndo: Boolean
        get() {
            val s = state.value ?: return false
            return history.depth > 0 && !matchOver.value && !s.gameOver && !s.currentPlayer.isBot
        }

    override fun undo() {
        if (!canUndo) return
        val previous = history.pop() ?: return
        val snap = decodeSnapshot(previous) ?: return
        applySnapshot(snap)
    }

    // ---- Hint ----------------------------------------------------------------------------------

    /**
     * Suggests the best play for the current human turn with the strongest planner, whatever the CPU
     * level: either dice to hold ([hintHolds]) or the box to fill ([hintCategory]). False before the
     * first roll, on a CPU's turn, or when the game is over.
     */
    fun hint(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.gameOver || s.currentPlayer.isBot || !s.hasRolled) return false
        val plan = YahtzeeAdvisor(s.currentPlayer.card, 1.0, 0.5, 2).plan(s.dice, s.rollsLeft)
        clearHints()
        if (plan.holds == null) hintCategory.value = plan.category else hintHolds.value = plan.holds
        return true
    }

    // ---- CPU -----------------------------------------------------------------------------------

    /**
     * One visible CPU action: roll (first roll), choose holds, reroll, or fill a box. False when it is
     * not a CPU's turn. The screen calls this after a short pause; [playBotTurn] runs a whole turn.
     */
    fun botStep(): Boolean {
        val s = state.value ?: return false
        if (matchOver.value || s.gameOver || !s.currentPlayer.isBot) return false
        if (s.rollsLeft == 3) return doRoll(s)
        if (s.rollsLeft > 0 && botHoldsSet) return doRoll(s)
        val plan = botPlan(s)
        val holds = plan.holds
        if (s.rollsLeft > 0 && holds != null) {
            botHoldsSet = true
            commit(s.copy(held = holds, rolled = List(YahtzeeScoring.DICE) { false }))
            return true
        }
        return applyCategory(s, plan.category)
    }

    /** Plays the current CPU seat's whole turn (tests and fast-forward). Does nothing on a human's turn. */
    fun playBotTurn() {
        val start = state.value ?: return
        if (!start.currentPlayer.isBot) return
        val seat = start.currentPlayerIndex
        val filled = start.currentPlayer.card.filledCount
        var guard = 0
        while (guard++ < 12) {
            val s = state.value ?: return
            if (s.gameOver || s.players[seat].card.filledCount > filled) return
            if (!botStep()) return
        }
    }

    private fun botPlan(s: YahtzeeState): YahtzeePlan {
        val card = s.currentPlayer.card
        return when (difficulty) {
            CpuDifficulty.HARD -> YahtzeeAdvisor(card, 1.0, 0.5, 2).plan(s.dice, s.rollsLeft)
            CpuDifficulty.MEDIUM -> YahtzeeAdvisor(card, 0.5, 0.0, 1).plan(s.dice, s.rollsLeft)
            CpuDifficulty.EASY -> easyPlan(s, card)
        }
    }

    private fun easyPlan(s: YahtzeeState, card: YahtzeeScorecard): YahtzeePlan {
        val legal = YahtzeeScoring.legalCategories(s.dice, card)
        val category = if (legal.isEmpty()) {
            YahtzeeCategory.CHANCE
        } else if (random.nextInt(100) < 50) {
            legal.maxByOrNull { YahtzeeScoring.scoreFor(it, s.dice, card) } ?: legal[0]
        } else {
            legal[random.nextInt(legal.size)]
        }
        // Sometimes simply stop early.
        if (s.rollsLeft == 0 || random.nextInt(100) < 20) return YahtzeePlan(null, category)
        val counts = IntArray(7)
        s.dice.forEach { counts[it]++ }
        val holds: List<Boolean> = if (random.nextInt(100) < 70) {
            val most = counts.maxOrNull() ?: 0
            val favourites = (1..6).filter { counts[it] == most }
            val face = favourites[random.nextInt(favourites.size)]
            s.dice.map { it == face }
        } else {
            s.dice.map { random.nextBoolean() }
        }
        return YahtzeePlan(if (holds.all { it }) null else holds, category)
    }

    // ---- Resume --------------------------------------------------------------------------------

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return yahtzeeSnapshotJson.encodeToString(
            YahtzeeSnapshot.serializer(),
            YahtzeeSnapshot(
                players = s.players.map {
                    YahtzeeSnapshotPlayer(it.playerId, it.displayName, it.isBot, it.card.scores, it.card.yahtzeeBonuses)
                },
                currentPlayerIndex = s.currentPlayerIndex,
                firstSeat = s.firstSeat,
                nextFirstSeat = nextFirstSeat,
                round = s.round,
                dice = s.dice,
                held = s.held,
                rollsLeft = s.rollsLeft,
                sessionWins = sessionWins.value,
                gamesFinished = gamesFinished.value,
                sessionBest = sessionBest.value,
                difficulty = difficulty.name
            )
        )
    }

    private fun validCard(scores: List<Int?>, bonuses: Int): Boolean {
        if (scores.size != YahtzeeCategory.COUNT || bonuses !in 0..12) return false
        for (c in YahtzeeCategory.ALL) {
            val v = scores[c.ordinal] ?: continue
            if (v < 0 || v > c.maxScore) return false
            if (c.isUpper && v % c.face != 0) return false
            val ok = when (c) {
                YahtzeeCategory.FULL_HOUSE -> v == 0 || v == YahtzeeScoring.FULL_HOUSE_POINTS
                YahtzeeCategory.SMALL_STRAIGHT -> v == 0 || v == YahtzeeScoring.SMALL_STRAIGHT_POINTS
                YahtzeeCategory.LARGE_STRAIGHT -> v == 0 || v == YahtzeeScoring.LARGE_STRAIGHT_POINTS
                YahtzeeCategory.YAHTZEE -> v == 0 || v == YahtzeeScoring.YAHTZEE_POINTS
                else -> true
            }
            if (!ok) return false
        }
        return bonuses == 0 || scores[YahtzeeCategory.YAHTZEE.ordinal] == YahtzeeScoring.YAHTZEE_POINTS
    }

    /** Decodes and fully validates a snapshot against THIS session's roster; null if it is unusable. */
    private fun decodeSnapshot(text: String): YahtzeeSnapshot? {
        val snap = try {
            yahtzeeSnapshotJson.decodeFromString(YahtzeeSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        val roster = context.players
        val n = roster.size
        if (snap.players.size != n) return null
        for (i in 0 until n) {
            if (snap.players[i].playerId != roster[i].playerId || snap.players[i].isBot != roster[i].isBot) return null
            if (!validCard(snap.players[i].scores, snap.players[i].yahtzeeBonuses)) return null
        }
        if (snap.currentPlayerIndex !in 0 until n || snap.firstSeat !in 0 until n || snap.nextFirstSeat !in 0 until n) return null
        if (snap.round !in 1..YahtzeeCategory.COUNT) return null
        if (snap.rollsLeft !in 0..3) return null
        if (snap.dice.size != YahtzeeScoring.DICE || snap.held.size != YahtzeeScoring.DICE) return null
        if (snap.rollsLeft == 3) {
            if (snap.dice.any { it != 0 } || snap.held.any { it }) return null
        } else if (snap.dice.any { it !in 1..6 }) {
            return null
        }
        // Everyone before the current seat (in turn order from the opener) has played this round; the rest have not.
        val curPos = (snap.currentPlayerIndex - snap.firstSeat + n) % n
        for (i in 0 until n) {
            val pos = (i - snap.firstSeat + n) % n
            val expected = if (pos < curPos) snap.round else snap.round - 1
            if (snap.players[i].scores.count { it != null } != expected) return null
        }
        if (snap.sessionWins.any { (id, w) -> w < 0 || roster.none { it.playerId == id } }) return null
        if (snap.sessionBest.any { (id, b) -> b < 0 || b > MAX_TOTAL || roster.none { it.playerId == id } }) return null
        if (snap.gamesFinished < 0) return null
        return snap
    }

    private fun applySnapshot(snap: YahtzeeSnapshot) {
        val roster = context.players
        val blank = List(YahtzeeScoring.DICE) { false }
        nextFirstSeat = snap.nextFirstSeat
        sessionWins.value = snap.sessionWins
        sessionBest.value = snap.sessionBest
        gamesFinished.value = snap.gamesFinished
        lastGameNewBest.value = false
        difficulty = CpuDifficulty.values().firstOrNull { it.name == snap.difficulty } ?: difficulty
        botHoldsSet = false
        clearHints()
        commit(
            YahtzeeState(
                players = snap.players.mapIndexed { i, p ->
                    YahtzeePlayerState(p.playerId, roster[i].displayName, p.isBot, YahtzeeScorecard(p.scores, p.yahtzeeBonuses))
                },
                currentPlayerIndex = snap.currentPlayerIndex,
                firstSeat = snap.firstSeat,
                round = snap.round,
                dice = snap.dice,
                held = snap.held,
                rollsLeft = snap.rollsLeft,
                rolled = blank,
                rollSerial = state.value?.rollSerial ?: 0
            )
        )
    }

    /** Null when there is nothing to save: no game, the session ended, or the game is over. */
    override fun snapshot(): String? {
        val s = state.value ?: return null
        if (matchOver.value || s.gameOver) return null
        if (context.activeMode != PlayMode.SINGLE_PLAYER_VS_BOT && context.activeMode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
        return captureSnapshot()
    }

    override fun restore(snapshot: String): Boolean {
        val snap = decodeSnapshot(snapshot) ?: return false
        matchOver.value = false
        applySnapshot(snap)
        firstOpenerIndex = null
        history.clear()
        return true
    }

    // ---- Test hooks ----------------------------------------------------------------------------

    /** Forces the dice of the current turn (as if just rolled) so rules can be tested with fixtures. */
    internal fun setDiceForTest(dice: List<Int>, rollsLeft: Int = 1) {
        val s = state.value ?: return
        commit(s.copy(dice = dice, held = List(YahtzeeScoring.DICE) { false }, rollsLeft = rollsLeft))
    }

    /** Replaces one seat's scorecard (to set up a Yahtzee already scored, or a nearly full card). */
    internal fun setCardForTest(playerIndex: Int, card: YahtzeeScorecard) {
        val s = state.value ?: return
        commit(s.copy(players = s.players.mapIndexed { i, p -> if (i == playerIndex) p.copy(card = card) else p }))
    }

    companion object {
        /** The highest total a game can reach (all boxes max, upper bonus, 12 Yahtzee bonuses). */
        const val MAX_TOTAL = 1575
    }
}
