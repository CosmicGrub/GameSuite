package com.gamesuite.games.actitout

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Where a match is. A turn goes CURTAIN, PLAYING, TIMES_UP, TURN_SUMMARY; after the last turn of a round comes SCOREBOARD. */
enum class ActItOutPhase {
    /** The device is being passed: nothing about the card is shown. The actor starts the turn. */
    CURTAIN,
    PLAYING,

    /** The clock ran out; the unresolved card is shown so the group can say whether it was got just in time. */
    TIMES_UP,
    TURN_SUMMARY,
    SCOREBOARD,
    END
}

@Serializable
private data class ActItOutSnapshot(
    val playerIds: List<String>,
    val playerNames: List<String>,
    val format: String,
    val sideNames: List<String>,
    val sideMembers: List<List<Int>>,
    val sideBands: List<String>,
    val sideBonus: List<Boolean>,
    val roundSeconds: Int,
    val rounds: Int,
    val holdToReveal: Boolean,
    val buzzer: Boolean,
    val deckOrder: List<String>,
    val used: List<String>,
    /** Cards already dealt per band id. Absent in older saves: then every used card counts for every band that allows it. */
    val usedByBand: Map<String, List<String>> = emptyMap(),
    val lastDealtId: String? = null,
    val phase: String,
    val turnIndex: Int,
    val scores: List<Int>,
    val correct: List<Int>,
    val skipped: List<Int>,
    val cardId: String?,
    val skipsLeft: Int,
    val turnCorrect: Int,
    val turnSkipped: Int,
    val turnPoints: Int,
    val remainingMs: Long,
    /** A turn that was running when saved: it resumes (same card, same time left) from a fresh curtain. */
    val inProgress: Boolean
)

private val actItOutJson = Json { ignoreUnknownKeys = true }

/**
 * The whole rules of Act It Out, with no Compose and no Android: one device deals cards to an actor, a clock runs,
 * the actor's side scores. The clock ([clock], milliseconds, monotonic) and the [random] are injected, so a fake
 * clock and a seeded random replay a whole match.
 *
 * TURNS. There are `rounds x sides` turns, side after side. A side's actor rotates through its members round by
 * round. Free-for-all sides have one member, so each player acts once per round.
 *
 * DEALING. The deck is shuffled once ([deckOrder]); a card is dealt to a side by taking the first card in that order
 * that suits the side's band and is not yet in [used]. A skipped card counts as used. When a band's pool is
 * exhausted its cards become available again (reshuffled), never repeating the card just dealt straight away.
 *
 * SCORING. Each correct card is worth [ActItOutRules.pointsFor] points to the actor's side: 1, or 2 with the little
 * kids bonus (handicap). Skips are limited to [ActItOutRules.SKIP_LIMIT] a turn and score nothing.
 */
class ActItOutEngine(
    val config: ActItOutConfig,
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    deckOrder: List<String>? = null,
    /** A card must have been on screen this long before Correct counts (a card dealt in the last instant was never seen). */
    private val minCardVisibleMs: Long = ActItOutRules.MIN_CARD_VISIBLE_MS
) {
    init {
        require(config.validate() == null) { "invalid config: ${config.validate()}" }
    }

    val sideCount: Int get() = config.sides.size
    val totalTurns: Int get() = config.rounds * sideCount

    var phase: ActItOutPhase = ActItOutPhase.CURTAIN
        private set
    var turnIndex: Int = 0
        private set

    var scores: IntArray = IntArray(sideCount)
        private set
    var correct: IntArray = IntArray(sideCount)
        private set
    var skipped: IntArray = IntArray(sideCount)
        private set

    var deckOrder: List<String> = deckOrder ?: ActItOutDeck.cards.map { it.id }.shuffled(random)
        private set
    /** Cards dealt so far, tracked per band so one band wrapping its pool never frees another band's cards. */
    private val usedByBand = LinkedHashMap<ActItOutBand, LinkedHashSet<String>>()

    /** Every card dealt and not yet recycled, across bands (in band order, then dealing order). */
    val used: Set<String>
        get() {
            val all = LinkedHashSet<String>()
            for (b in ActItOutBand.values()) usedByBand[b]?.let { all.addAll(it) }
            return all
        }

    /** The card in play (PLAYING) or awaiting a verdict (TIMES_UP); null otherwise. */
    var card: ActItOutCard? = null
        private set
    var skipsLeft: Int = ActItOutRules.SKIP_LIMIT
        private set
    var turnCorrect: Int = 0
        private set
    var turnSkipped: Int = 0
        private set
    var turnPoints: Int = 0
        private set

    private var remainingFrozenMs: Long = config.roundSeconds * 1000L
    private var deadlineMs: Long = 0L
    private var running = false

    /** True while a restored or paused-and-recovered turn keeps its card and time (see [startTurn]). */
    private var inProgress = false
    private var lastDealtId: String? = null

    /** Clock reading when the current card was put on screen. */
    private var cardShownAtMs: Long = 0L

    /** Which side acts this turn (valid while [phase] is not END). */
    val side: Int get() = turnIndex % sideCount
    val round: Int get() = turnIndex / sideCount

    /** The seat that acts this turn. */
    val actor: Int
        get() {
            val members = config.sides[side].members
            return members[round % members.size]
        }

    fun actorName(): String = config.playerNames[actor]

    /** Milliseconds left on the turn clock right now. */
    fun remainingMs(): Long =
        if (running) (deadlineMs - clock()).coerceIn(0L, config.roundSeconds * 1000L) else remainingFrozenMs

    val isRunning: Boolean get() = running

    /** True on a curtain that will carry on a turn already under way (after a restore): same card, same time left. */
    val resumedTurn: Boolean get() = phase == ActItOutPhase.CURTAIN && inProgress

    /** The time a turn on the curtain will start with (the full clock, or what was left of a resumed turn). */
    fun startingMs(): Long = remainingFrozenMs

    // ---- turn flow ----

    /** CURTAIN -> PLAYING. A fresh turn gets a full clock, [ActItOutRules.SKIP_LIMIT] skips and its first card. */
    fun startTurn(): Boolean {
        if (phase != ActItOutPhase.CURTAIN) return false
        if (!inProgress) {
            remainingFrozenMs = config.roundSeconds * 1000L
            skipsLeft = ActItOutRules.SKIP_LIMIT
            turnCorrect = 0
            turnSkipped = 0
            turnPoints = 0
            card = deal()
        }
        inProgress = false
        cardShownAtMs = clock()
        deadlineMs = clock() + remainingFrozenMs
        running = true
        phase = ActItOutPhase.PLAYING
        return true
    }

    /**
     * Call often (every frame). Moves PLAYING to TIMES_UP when the clock reaches zero. Returns true when it did.
     */
    fun tick(): Boolean {
        if (phase != ActItOutPhase.PLAYING || !running) return false
        if (deadlineMs - clock() > 0L) return false
        remainingFrozenMs = 0L
        running = false
        phase = ActItOutPhase.TIMES_UP
        return true
    }

    /**
     * The app left the foreground. A running turn freezes its clock and goes back behind the curtain (same card, same
     * time left, see [resumedTurn]), so nobody the phone is handed to sees the card. A turn whose clock already ran
     * out ends instead. Returns true when the phase changed. Anything not PLAYING is left alone.
     */
    fun pause(): Boolean {
        if (phase != ActItOutPhase.PLAYING || !running) return false
        if (tick()) return true
        remainingFrozenMs = remainingMs()
        running = false
        inProgress = true
        phase = ActItOutPhase.CURTAIN
        return true
    }

    /** Continues a paused turn from the time that was left. */
    fun resume() {
        if (phase == ActItOutPhase.PLAYING && !running) {
            if (remainingFrozenMs <= 0L) {
                tick()
                return
            }
            deadlineMs = clock() + remainingFrozenMs
            running = true
        }
    }

    /** The actor's side was guessed: score it and deal the next card. Returns the points added (0 when ignored). */
    fun markCorrect(): Int {
        if (phase != ActItOutPhase.PLAYING) return 0
        if (tick()) return 0
        // A card dealt in the last instant was not seen yet: it cannot have been guessed.
        if (clock() - cardShownAtMs < minCardVisibleMs) return 0
        val pts = award()
        card = deal()
        cardShownAtMs = clock()
        return pts
    }

    /** Skips the card: costs one of the turn's skips and deals another. False when none are left or not playing. */
    fun skip(): Boolean {
        if (phase != ActItOutPhase.PLAYING) return false
        if (tick()) return false
        if (skipsLeft <= 0) return false
        skipsLeft--
        turnSkipped++
        skipped[side]++
        card = deal()
        cardShownAtMs = clock()
        return true
    }

    /**
     * TIMES_UP -> TURN_SUMMARY. [gotIt] true gives the just-in-time card its points. The card is then put away.
     * Returns the points added.
     */
    fun resolveLastCard(gotIt: Boolean): Int {
        if (phase != ActItOutPhase.TIMES_UP) return 0
        val pts = if (gotIt) award() else 0
        card = null
        phase = ActItOutPhase.TURN_SUMMARY
        return pts
    }

    /** TURN_SUMMARY -> next curtain, the between-rounds scoreboard, or END after the last turn. */
    fun continueAfterSummary(): Boolean {
        if (phase != ActItOutPhase.TURN_SUMMARY) return false
        turnIndex++
        inProgress = false
        card = null
        remainingFrozenMs = config.roundSeconds * 1000L
        phase = when {
            turnIndex >= totalTurns -> {
                turnIndex = totalTurns - 1
                ActItOutPhase.END
            }
            turnIndex % sideCount == 0 -> ActItOutPhase.SCOREBOARD
            else -> ActItOutPhase.CURTAIN
        }
        return true
    }

    /** SCOREBOARD -> CURTAIN of the next round. */
    fun continueFromScoreboard(): Boolean {
        if (phase != ActItOutPhase.SCOREBOARD) return false
        phase = ActItOutPhase.CURTAIN
        return true
    }

    private fun award(): Int {
        val pts = ActItOutRules.pointsFor(config.sides[side].bonus)
        scores[side] += pts
        correct[side]++
        turnCorrect++
        turnPoints += pts
        return pts
    }

    // ---- results ----

    /** Sides holding the highest score (several on a tie; a 0-0 match lists every side). */
    fun leaders(): List<Int> {
        val best = scores.maxOrNull() ?: return emptyList()
        return scores.indices.filter { scores[it] == best }
    }

    val isOver: Boolean get() = phase == ActItOutPhase.END

    // ---- dealing ----

    private fun pool(band: ActItOutBand): List<String> =
        deckOrder.filter { id -> ActItOutDeck.byId[id]?.let { band.allows(it.difficulty) } == true }

    private fun deal(): ActItOutCard {
        val band = config.sides[side].band
        val usedHere = usedByBand.getOrPut(band) { LinkedHashSet() }
        var pool = pool(band)
        var candidates = pool.filter { it !in usedHere }
        if (candidates.isEmpty()) {
            // Exhausted: this band's cards come round again, in a fresh order, but not the card just dealt.
            // Only this band's own record is cleared; other bands keep theirs.
            usedHere.clear()
            deckOrder = deckOrder.shuffled(random)
            pool = pool(band)
            candidates = pool.filter { it != lastDealtId }.ifEmpty { pool }
        }
        val id = candidates.first()
        usedHere.add(id)
        lastDealtId = id
        return ActItOutDeck.byId.getValue(id)
    }

    // ---- save / restore ----

    /** The match as a JSON string, or null when there is nothing to save (the match is over). */
    fun snapshot(): String? {
        if (phase == ActItOutPhase.END) return null
        val playing = phase == ActItOutPhase.PLAYING
        val savedPhase = if (playing) ActItOutPhase.CURTAIN else phase
        val c = config
        return try {
            actItOutJson.encodeToString(
                ActItOutSnapshot.serializer(),
                ActItOutSnapshot(
                    playerIds = c.playerIds, playerNames = c.playerNames, format = c.format.id,
                    sideNames = c.sides.map { it.name }, sideMembers = c.sides.map { it.members },
                    sideBands = c.sides.map { it.band.id }, sideBonus = c.sides.map { it.bonus },
                    roundSeconds = c.roundSeconds, rounds = c.rounds, holdToReveal = c.holdToReveal, buzzer = c.buzzer,
                    deckOrder = deckOrder, used = used.toList(),
                    usedByBand = usedByBand.entries.filter { it.value.isNotEmpty() }.associate { it.key.id to it.value.toList() },
                    lastDealtId = lastDealtId,
                    phase = savedPhase.name, turnIndex = turnIndex,
                    scores = scores.toList(), correct = correct.toList(), skipped = skipped.toList(),
                    cardId = if (playing || phase == ActItOutPhase.TIMES_UP || (phase == ActItOutPhase.CURTAIN && inProgress)) card?.id else null,
                    skipsLeft = skipsLeft, turnCorrect = turnCorrect, turnSkipped = turnSkipped, turnPoints = turnPoints,
                    remainingMs = if (playing) remainingMs() else remainingFrozenMs,
                    inProgress = playing || (phase == ActItOutPhase.CURTAIN && inProgress)
                )
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /**
         * Rebuilds an engine from [snapshot], or null when the text is corrupt or inconsistent. Everything is checked
         * before anything is built: config, that the deck order is a permutation of the real deck, the used set,
         * score arithmetic (each score must equal correct x points per card), card and clock fields.
         */
        fun restore(
            snapshot: String,
            random: Random = Random.Default,
            clock: () -> Long = { System.nanoTime() / 1_000_000L },
            minCardVisibleMs: Long = ActItOutRules.MIN_CARD_VISIBLE_MS
        ): ActItOutEngine? {
            try {
                val s = actItOutJson.decodeFromString(ActItOutSnapshot.serializer(), snapshot)
                val sideCount = s.sideMembers.size
                if (s.sideNames.size != sideCount || s.sideBands.size != sideCount || s.sideBonus.size != sideCount) return null
                if (s.sideBands.any { !ActItOutBand.isKnown(it) }) return null
                val format = ActItOutFormat.values().firstOrNull { it.id == s.format } ?: return null
                val config = ActItOutConfig(
                    playerIds = s.playerIds, playerNames = s.playerNames, format = format,
                    sides = List(sideCount) { i ->
                        ActItOutSide(s.sideNames[i], s.sideMembers[i], ActItOutBand.fromId(s.sideBands[i]), s.sideBonus[i])
                    },
                    roundSeconds = s.roundSeconds, rounds = s.rounds, holdToReveal = s.holdToReveal, buzzer = s.buzzer
                )
                if (config.validate() != null) return null

                val allIds = ActItOutDeck.cards.map { it.id }
                if (s.deckOrder.size != allIds.size || s.deckOrder.toSet() != allIds.toSet()) return null
                if (s.used.toSet().size != s.used.size || !s.used.all { it in ActItOutDeck.byId }) return null

                val phase = ActItOutPhase.values().firstOrNull { it.name == s.phase } ?: return null
                if (phase == ActItOutPhase.END || phase == ActItOutPhase.PLAYING) return null
                val total = s.rounds * sideCount
                if (s.turnIndex !in 0 until total) return null
                if (s.scores.size != sideCount || s.correct.size != sideCount || s.skipped.size != sideCount) return null
                for (i in 0 until sideCount) {
                    if (s.scores[i] < 0 || s.correct[i] < 0 || s.skipped[i] < 0) return null
                    if (s.scores[i] != s.correct[i] * ActItOutRules.pointsFor(s.sideBonus[i])) return null
                }
                if (s.skipsLeft !in 0..ActItOutRules.SKIP_LIMIT) return null
                if (s.turnCorrect < 0 || s.turnSkipped < 0 || s.turnPoints < 0) return null
                if (s.turnSkipped + s.skipsLeft != ActItOutRules.SKIP_LIMIT) return null
                if (s.remainingMs !in 0L..(s.roundSeconds * 1000L)) return null

                val wantsCard = s.inProgress || phase == ActItOutPhase.TIMES_UP
                if (wantsCard) {
                    val id = s.cardId ?: return null
                    if (id !in ActItOutDeck.byId || id !in s.used) return null
                } else if (s.cardId != null) {
                    return null
                }
                if (s.inProgress && phase != ActItOutPhase.CURTAIN) return null
                if (phase == ActItOutPhase.TIMES_UP && s.remainingMs != 0L) return null
                if (phase == ActItOutPhase.SCOREBOARD && (s.turnIndex % sideCount != 0 || s.turnIndex == 0)) return null

                // The turn counters belong to the side acting now only while a turn is under way or just ended.
                val turnLive = phase == ActItOutPhase.TIMES_UP || phase == ActItOutPhase.TURN_SUMMARY || s.inProgress
                if (turnLive) {
                    val acting = s.turnIndex % sideCount
                    if (s.turnPoints != s.turnCorrect * ActItOutRules.pointsFor(s.sideBonus[acting])) return null
                    if (s.turnCorrect > s.correct[acting] || s.turnSkipped > s.skipped[acting]) return null
                }

                val usedPerBand = LinkedHashMap<ActItOutBand, LinkedHashSet<String>>()
                if (s.usedByBand.isEmpty()) {
                    // Older save: every used card counts for every band that may be dealt it.
                    for (b in ActItOutBand.values()) {
                        val ids = s.used.filter { id -> ActItOutDeck.byId[id]?.let { b.allows(it.difficulty) } == true }
                        if (ids.isNotEmpty()) usedPerBand[b] = LinkedHashSet(ids)
                    }
                } else {
                    for ((bandId, ids) in s.usedByBand) {
                        if (!ActItOutBand.isKnown(bandId)) return null
                        val b = ActItOutBand.fromId(bandId)
                        if (ids.toSet().size != ids.size) return null
                        for (id in ids) {
                            val c = ActItOutDeck.byId[id] ?: return null
                            if (id !in s.used || !b.allows(c.difficulty)) return null
                        }
                        usedPerBand[b] = LinkedHashSet(ids)
                    }
                }
                val lastId = s.lastDealtId ?: s.cardId
                if (lastId != null && lastId !in ActItOutDeck.byId) return null

                // A turn that expired while saved ends at once instead of showing its card on a curtain.
                val expired = s.inProgress && s.remainingMs == 0L

                val e = ActItOutEngine(config, random, clock, s.deckOrder, minCardVisibleMs)
                e.usedByBand.putAll(usedPerBand)
                e.phase = if (expired) ActItOutPhase.TIMES_UP else phase
                e.turnIndex = s.turnIndex
                e.scores = s.scores.toIntArray()
                e.correct = s.correct.toIntArray()
                e.skipped = s.skipped.toIntArray()
                e.card = if (wantsCard) ActItOutDeck.byId[s.cardId] else null
                e.skipsLeft = s.skipsLeft
                e.turnCorrect = s.turnCorrect
                e.turnSkipped = s.turnSkipped
                e.turnPoints = s.turnPoints
                e.remainingFrozenMs = s.remainingMs
                e.inProgress = s.inProgress && !expired
                e.lastDealtId = lastId
                return e
            } catch (_: Exception) {
                return null
            }
        }
    }
}
