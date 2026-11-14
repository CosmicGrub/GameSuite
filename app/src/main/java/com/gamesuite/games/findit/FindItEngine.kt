package com.gamesuite.games.findit

import com.gamesuite.settings.CpuDifficulty

/**
 * The rules of Find It Frenzy with no Compose, no Android and no clock: time only moves through [advance], so tests
 * and replays are exact. The game wrapper ([FindItGame]) owns locking, Compose state and the shell lifecycle.
 *
 * Lifecycle: [setup] -> [start] (READY) -> [go] (PLAYING) -> taps and [advance] -> OVER.
 */
interface FindItEngine {
    /** Seats are people first, then helpers. Resets everything; [FindItConfig.seed] (never 0 from the wrapper) fixes every shuffle. */
    fun setup(config: FindItConfig, seats: List<FindItSeat>)

    /** Moves to READY with the cards dealt. */
    fun start()

    /** The "Ready?" tap or the 3-2-1 finished: moves READY to PLAYING and starts the clock. A sunset (SCENE_TIMEOUT) goes back to READY for the same scene. */
    fun go()

    /** Moves engine time forward by [dtMs] (helper taps, hints, lockouts and clocks run from here). No-op while paused. */
    fun advance(dtMs: Long)

    /**
     * Seat [seat] taps picture [symbol]. In Match Race and Dash the picture must be on that seat's own card; in Group
     * Hunt it is a scene picture. [atMs] is the tap's time stamp (taps in the same batch resolve by the lowest stamp,
     * then by rotated seat order).
     */
    fun tap(seat: Int, symbol: Int, atMs: Long): FindItTapResult

    fun pause()
    fun resume()

    /** Starts another game with the same seats; the next seed is derived from the previous one (SplitMix64), so it stays reproducible. */
    fun playAgain()

    fun state(): FindItState

    /** The replay log so far (seed, config ids, people's taps). */
    fun replay(): FindItReplay

    /** Null when there is nothing worth saving (not started, over). */
    fun toSave(): FindItSave?

    /** True when the engine is fully back in the saved state (at READY); false (and unchanged) when the save is unusable. */
    fun restore(save: FindItSave): Boolean
}

/** Group Hunt helpers shared by the engine and the save validator. */
object FindItHunt {
    private const val TAG_TARGETS = 0x54L

    /** The target pictures of scene [sceneIndex] of the game with [seed]: a seeded shuffle of the active pictures. */
    fun targets(seed: Long, sceneIndex: Int, size: FindItCardSize): List<Int> {
        val rng = FindItRng(findItDerive(ProjectiveDeck.sceneSeed(seed, sceneIndex), TAG_TARGETS))
        val all = MutableList(size.deckSize) { it }
        rng.shuffle(all)
        return all.take(FindItRules.huntTargets(size))
    }

    fun scene(seed: Long, sceneIndex: Int, size: FindItCardSize): FindItScene =
        ProjectiveDeck.buildScene(targets(seed, sceneIndex, size), FindItRules.huntItems(size), size.deckSize, ProjectiveDeck.sceneSeed(seed, sceneIndex))
}

/** The real engine. Deterministic: every random draw comes from [FindItRng] streams derived from the seed. */
class DefaultFindItEngine : FindItEngine {
    private companion object {
        const val TAG_RESHUFFLE = 0x5245L
        const val TAG_CAND = 0x4341L
    }

    private var cfg = FindItConfig(seed = 1L)
    private var seats: List<FindItSeat> = emptyList()
    private var seed = 1L
    private var size = FindItCardSize.LITTLE
    private var deck: List<List<Int>> = emptyList()
    private var deckSets: List<Set<Int>> = emptyList()

    private var begun = false
    private var phase = FindItPhase.READY
    private var paused = false
    private var nowMs = 0L
    private var phaseMs = 0L
    private var roundMs = 0L
    private var clockLeftMs = 0L

    // cards (Match Race, Dash)
    private var order: MutableList<Int> = mutableListOf()
    private var pileIndex = 0
    private var reshuffles = 0
    private var own = IntArray(0)
    private var centre = -1
    private var roundIdx = 0
    private var scores = IntArray(0)
    private var misses = IntArray(0)
    private var lockedUntil = LongArray(0)
    private var winTimes: List<MutableList<Long>> = emptyList()
    private var botNext = LongArray(0)
    private var botFirstDone = BooleanArray(0)
    private var botPlans: Array<FindItBots.RoundPlan?> = emptyArray()

    // a win stays provisional until the next advance, so taps of the same batch resolve by stamp then rotated seat order
    private var provisional = false
    private var winSeat = -1
    private var winStamp = 0L
    private var winPrevCard = -1

    // Group Hunt
    private var sceneIdx = 0
    private var tries = 0
    private var scene: FindItScene? = null
    private var found = LinkedHashMap<Int, Int>()
    private var sinceFindMs = 0L
    private var helperDue = LongArray(0)
    private var helperCount = IntArray(0)

    private var event = FindItEvent.NONE
    private var eventSeat = -1
    private var eventSymbol = -1
    private var serial = 0
    private var lastWinner = -1
    private var result: FindItResult? = null
    private val log = ArrayList<FindItTapEvent>()

    private val mode get() = cfg.mode
    private val n get() = seats.size
    private val isCards get() = mode != FindItMode.GROUP_HUNT
    private val rounds: Int
        get() = when (mode) {
            FindItMode.MATCH_RACE -> cfg.length.raceRounds
            FindItMode.SOLO_DASH -> if (cfg.relaxed) FindItRules.DASH_RELAXED_CARDS else 0
            FindItMode.GROUP_HUNT -> 0
        }
    private val dashTimed get() = mode == FindItMode.SOLO_DASH && !cfg.relaxed
    private val huntTimed get() = mode == FindItMode.GROUP_HUNT && !cfg.relaxed && !cfg.oneSwitch
    private val scenes get() = cfg.length.huntScenes

    // ---- setup -----------------------------------------------------------------------------------

    override fun setup(config: FindItConfig, seats: List<FindItSeat>) {
        this.cfg = config
        this.seats = seats.take(4)
        this.seed = config.seed
        this.size = config.cardSize
        deck = ProjectiveDeck.generate(size.order)
        deckSets = deck.map { it.toSet() }
        resetAll()
    }

    private fun resetAll() {
        begun = false
        phase = FindItPhase.READY
        paused = false
        nowMs = 0; phaseMs = 0; roundMs = 0; clockLeftMs = 0
        order = mutableListOf(); pileIndex = 0; reshuffles = 0
        own = IntArray(n); centre = -1; roundIdx = 0
        scores = IntArray(n); misses = IntArray(n); lockedUntil = LongArray(n) { -1L }
        winTimes = List(n) { mutableListOf() }
        botNext = LongArray(n) { -1L }; botFirstDone = BooleanArray(n); botPlans = arrayOfNulls(n)
        provisional = false; winSeat = -1; winStamp = 0; winPrevCard = -1
        sceneIdx = 0; tries = 0; scene = null; found = LinkedHashMap(); sinceFindMs = 0
        helperDue = LongArray(n) { -1L }; helperCount = IntArray(n)
        event = FindItEvent.NONE; eventSeat = -1; eventSymbol = -1; serial = 0; lastWinner = -1; result = null
        log.clear()
    }

    override fun start() {
        if (deck.isEmpty() || n == 0) return
        resetAll()
        begun = true
        if (isCards) {
            order = ProjectiveDeck.shuffledOrder(deck.size, seed).toMutableList()
            for (i in 0 until n) own[i] = order[i]
            centre = order[n]
            pileIndex = n + 1
            clockLeftMs = if (dashTimed) FindItRules.dashMs(size) else 0L
        } else {
            buildScene()
        }
        event = FindItEvent.DEAL
        serial++
    }

    private fun buildScene() {
        scene = FindItHunt.scene(seed, sceneIdx, size)
        found = LinkedHashMap()
        sinceFindMs = 0
        roundMs = 0
        clockLeftMs = if (huntTimed) FindItRules.huntSceneMs(size) else 0L
        for (i in 0 until n) {
            helperCount[i] = 0
            helperDue[i] = if (seats[i].isBot) hunterDelay(i) else -1L
        }
    }

    private fun hunterDelay(seat: Int): Long {
        val packed = FindItBots.huntDelayMs(seed, sceneIdx, tries, helperCount[seat] * 8 + seat, cfg.difficulty, cfg.relaxed, cfg.timeScale)
        return roundMs + FindItBots.delayOf(packed)
    }

    override fun go() {
        if (!begun) return
        when (phase) {
            FindItPhase.READY -> {
                phase = FindItPhase.PLAYING
                if (isCards) planRound()
            }
            FindItPhase.SCENE_TIMEOUT -> {
                phase = FindItPhase.READY
                buildScene()
                event = FindItEvent.DEAL
                serial++
            }
            else -> {}
        }
    }

    private fun planRound() {
        roundMs = 0
        for (i in 0 until n) {
            lockedUntil[i] = -1L
            botFirstDone[i] = false
            if (seats[i].isBot) {
                val plan = FindItBots.plan(seed, roundIdx, i, cfg.difficulty, size, cfg.relaxed, cfg.timeScale, deck[own[i]].size)
                botPlans[i] = plan
                botNext[i] = plan.tapAtMs
            } else {
                botPlans[i] = null
                botNext[i] = -1L
            }
        }
    }

    override fun pause() { paused = true }
    override fun resume() { paused = false }

    override fun playAgain() {
        if (deck.isEmpty()) return
        val next = findItNonZero(FindItRng(seed).nextLong())
        cfg = cfg.copy(seed = next)
        seed = next
        resetAll()
        start()
    }

    // ---- cards -----------------------------------------------------------------------------------

    private fun commonSymbol(a: Int, b: Int): Int {
        val sb = deckSets[b]
        for (s in deck[a]) if (s in sb) return s
        return -1
    }

    private fun answerFor(seat: Int): Int = commonSymbol(own[seat], centre)

    private class Draw(val centre: Int, val order: MutableList<Int>, val pile: Int, val reshuffles: Int)

    /** The next middle card given the held cards; reshuffles the unheld cards when the pile runs out. Pure (no engine state is changed). */
    private fun draw(order0: List<Int>, pile0: Int, reshuffles0: Int, held: Set<Int>, previousCentre: Int): Draw {
        val ord = order0.toMutableList()
        var pile = pile0
        var re = reshuffles0
        var guard = 0
        while (guard++ < 4) {
            while (pile < ord.size) {
                val c = ord[pile++]
                if (c !in held && c != previousCentre) return Draw(c, ord, pile, re)
            }
            val free = (0 until deck.size).filter { it !in held && it != previousCentre }.toMutableList()
            FindItRng(findItDerive(seed, TAG_RESHUFFLE, re.toLong())).shuffle(free)
            re++
            val newOrder = ArrayList<Int>(deck.size)
            newOrder += free
            newOrder += held.sorted()
            if (previousCentre >= 0 && previousCentre !in held) newOrder += previousCentre
            ord.clear(); ord += newOrder
            pile = 0
        }
        // cannot happen with a deck of 13 or more cards and at most 4 seats
        val c = (0 until deck.size).first { it !in held }
        return Draw(c, ord, pile, re)
    }

    private fun held(): Set<Int> = own.toSet()

    // ---- taps ------------------------------------------------------------------------------------

    override fun tap(seat: Int, symbol: Int, atMs: Long): FindItTapResult {
        val stamp = atMs.coerceIn(0L, nowMs + 1_000L)
        if (begun && seat in 0 until n && !seats[seat].isBot && log.size < FindItRules.MAX_TAP_LOG &&
            phase != FindItPhase.OVER && phase != FindItPhase.READY
        ) {
            log += FindItTapEvent(nowMs, seat, symbol, stamp)
        }
        return tapInternal(seat, symbol, stamp)
    }

    private fun rank(seat: Int): Int = (seat + roundIdx) % n

    private fun tapInternal(seat: Int, symbol: Int, stamp: Long): FindItTapResult {
        if (!begun || paused) return FindItTapResult.NOT_PLAYING
        val late = phase == FindItPhase.ROUND_WON && provisional && mode == FindItMode.MATCH_RACE
        if (phase != FindItPhase.PLAYING && !late) return FindItTapResult.NOT_PLAYING
        if (seat !in 0 until n || symbol < 0 || symbol >= size.deckSize) return FindItTapResult.INVALID
        return if (isCards) cardTap(seat, symbol, stamp, late) else huntTap(seat, symbol)
    }

    private fun cardTap(seat: Int, symbol: Int, stamp: Long, late: Boolean): FindItTapResult {
        if (late) {
            // a second claim in the same batch: only a correct tap with an earlier stamp (or the same stamp and a lower rank) takes over
            if (seat == winSeat || symbol != answerFor(seat).takeIf { it >= 0 }) return FindItTapResult.NOT_PLAYING
            if (deckSets[own[seat]].contains(symbol).not()) return FindItTapResult.INVALID
            if (lockedUntil[seat] > roundMs) return FindItTapResult.LOCKED
            val better = stamp < winStamp || (stamp == winStamp && rank(seat) < rank(winSeat))
            if (!better) return FindItTapResult.NOT_PLAYING
            revertWin()
            award(seat, stamp)
            return FindItTapResult.WON_CARD
        }
        if (lockedUntil[seat] > roundMs) return FindItTapResult.LOCKED
        if (symbol !in deckSets[own[seat]]) return FindItTapResult.INVALID
        if (symbol != answerFor(seat)) {
            misses[seat] = (misses[seat] + 1).coerceAtMost(FindItRules.MAX_COUNTER)
            if (cfg.lockoutEnabled && mode == FindItMode.MATCH_RACE) lockedUntil[seat] = roundMs + FindItRules.LOCKOUT_MS
            event = FindItEvent.WRONG; eventSeat = seat; eventSymbol = symbol; serial++
            if (dashTimed) {
                clockLeftMs -= FindItRules.DASH_PENALTY_MS
                if (clockLeftMs <= 0) { clockLeftMs = 0; finish() }
            }
            return FindItTapResult.WRONG
        }
        award(seat, stamp)
        return FindItTapResult.WON_CARD
    }

    private fun award(seat: Int, stamp: Long) {
        winPrevCard = own[seat]
        own[seat] = centre
        scores[seat] = (scores[seat] + 1).coerceAtMost(FindItRules.MAX_COUNTER)
        winTimes[seat] += roundMs
        winSeat = seat
        winStamp = stamp
        lastWinner = seat
        provisional = true
        phase = FindItPhase.ROUND_WON
        phaseMs = 0
        event = FindItEvent.ROUND_WON; eventSeat = seat; eventSymbol = -1; serial++
    }

    private fun revertWin() {
        if (winSeat < 0) return
        own[winSeat] = winPrevCard
        scores[winSeat] = (scores[winSeat] - 1).coerceAtLeast(0)
        if (winTimes[winSeat].isNotEmpty()) winTimes[winSeat].removeAt(winTimes[winSeat].size - 1)
    }

    private fun huntTap(seat: Int, symbol: Int): FindItTapResult {
        val sc = scene ?: return FindItTapResult.NOT_PLAYING
        if (sc.items.none { it.symbol == symbol }) return FindItTapResult.INVALID
        if (symbol in sc.targets && symbol !in found) {
            found[symbol] = seat
            scores[seat] = (scores[seat] + 1).coerceAtMost(FindItRules.MAX_COUNTER)
            sinceFindMs = 0
            event = FindItEvent.FOUND; eventSeat = seat; eventSymbol = symbol; serial++
            if (found.size >= sc.targets.size) {
                phase = FindItPhase.SCENE_CLEAR
                phaseMs = 0
                event = FindItEvent.SCENE_CLEAR
                return FindItTapResult.SCENE_CLEARED
            }
            return FindItTapResult.FOUND
        }
        misses[seat] = (misses[seat] + 1).coerceAtMost(FindItRules.MAX_COUNTER)
        event = FindItEvent.MISS; eventSeat = seat; eventSymbol = symbol; serial++
        // tapping a target that is already found is harmless; only a wrong picture moves the sunset
        if (symbol !in sc.targets && huntTimed) {
            clockLeftMs -= FindItRules.HUNT_MISS_PENALTY_MS
            if (clockLeftMs <= 0) { clockLeftMs = 0; timeout() }
        }
        return FindItTapResult.MISS
    }

    private fun timeout() {
        phase = FindItPhase.SCENE_TIMEOUT
        tries = (tries + 1).coerceAtMost(FindItRules.MAX_COUNTER)
        event = FindItEvent.TIMEOUT; eventSeat = -1; eventSymbol = -1; serial++
    }

    // ---- time ------------------------------------------------------------------------------------

    override fun advance(dtMs: Long) {
        if (!begun || paused || dtMs <= 0) return
        if (phase == FindItPhase.READY || phase == FindItPhase.OVER || phase == FindItPhase.SCENE_TIMEOUT) return
        provisional = false
        var rem = dtMs.coerceAtMost(FindItRules.MAX_ELAPSED_MS)
        var guard = 0
        while (rem > 0 && guard++ < 200_000) {
            if (phase == FindItPhase.READY || phase == FindItPhase.OVER || phase == FindItPhase.SCENE_TIMEOUT) break
            val step = minOf(rem, nextEventIn())
            if (step > 0) { move(step); rem -= step }
            processDue()
        }
    }

    private fun move(step: Long) {
        nowMs += step
        when (phase) {
            FindItPhase.PLAYING -> {
                roundMs += step
                if (dashTimed || huntTimed) clockLeftMs -= step
                if (!isCards) sinceFindMs += step
            }
            FindItPhase.ROUND_WON, FindItPhase.SCENE_CLEAR -> phaseMs += step
            else -> {}
        }
    }

    private fun wonMs(): Long = if (mode == FindItMode.SOLO_DASH) FindItRules.ROUND_WON_DASH_MS else FindItRules.ROUND_WON_MS

    private fun nextEventIn(): Long {
        var best = Long.MAX_VALUE
        when (phase) {
            FindItPhase.PLAYING -> {
                if (isCards) {
                    for (i in 0 until n) if (botNext[i] >= 0) best = minOf(best, (botNext[i] - roundMs).coerceAtLeast(0))
                    if (dashTimed) best = minOf(best, clockLeftMs.coerceAtLeast(0))
                } else {
                    for (i in 0 until n) if (helperDue[i] >= 0) best = minOf(best, (helperDue[i] - roundMs).coerceAtLeast(0))
                    if (huntTimed) best = minOf(best, clockLeftMs.coerceAtLeast(0))
                }
            }
            FindItPhase.ROUND_WON -> best = (wonMs() - phaseMs).coerceAtLeast(0)
            FindItPhase.SCENE_CLEAR -> best = (FindItRules.SCENE_CLEAR_MS - phaseMs).coerceAtLeast(0)
            else -> {}
        }
        return best
    }

    private fun processDue() {
        when (phase) {
            FindItPhase.PLAYING -> if (isCards) cardsDue() else huntDue()
            FindItPhase.ROUND_WON -> if (phaseMs >= wonMs()) nextRound()
            FindItPhase.SCENE_CLEAR -> if (phaseMs >= FindItRules.SCENE_CLEAR_MS) nextScene()
            else -> {}
        }
    }

    private fun cardsDue() {
        if (dashTimed && clockLeftMs <= 0) { clockLeftMs = 0; finish(); return }
        // helpers due now tap in rotated seat order, so the first one wins and the others find the round over
        val due = (0 until n).filter { botNext[it] in 0..roundMs }.sortedBy { rank(it) }
        for (i in due) {
            if (phase != FindItPhase.PLAYING) break
            val plan = botPlans[i] ?: continue
            if (!botFirstDone[i] && plan.wrong) {
                botFirstDone[i] = true
                botNext[i] = plan.retryAtMs
                val card = deck[own[i]]
                val ans = answerFor(i)
                val others = card.filter { it != ans }
                val pick = others[plan.wrongIndex % others.size]
                tapInternal(i, pick, nowMs)
            } else {
                botNext[i] = -1L
                tapInternal(i, answerFor(i), nowMs)
            }
        }
    }

    private fun huntDue() {
        if (huntTimed && clockLeftMs <= 0) { clockLeftMs = 0; timeout(); return }
        val sc = scene ?: return
        for (i in 0 until n) {
            if (phase != FindItPhase.PLAYING) break
            if (helperDue[i] < 0 || helperDue[i] > roundMs) continue
            val remaining = sc.targets.filter { it !in found }
            val humans = seats.count { !it.isBot }
            helperCount[i]++
            val packed = FindItBots.huntDelayMs(seed, sceneIdx, tries, helperCount[i] * 8 + i, cfg.difficulty, cfg.relaxed, cfg.timeScale)
            helperDue[i] = roundMs + FindItBots.delayOf(packed)
            // the last find always goes to a person
            if (remaining.isEmpty() || (humans > 0 && remaining.size <= 1)) continue
            val target = remaining[FindItBots.pickOf(packed) % remaining.size]
            tapInternal(i, target, nowMs)
        }
    }

    private fun nextRound() {
        val played = roundIdx + 1
        val done = when (mode) {
            FindItMode.MATCH_RACE -> played >= rounds
            FindItMode.SOLO_DASH -> cfg.relaxed && played >= rounds
            else -> false
        }
        if (done) { roundIdx = played; finish(); return }
        val d = draw(order, pileIndex, reshuffles, held(), centre)
        order = d.order; pileIndex = d.pile; reshuffles = d.reshuffles; centre = d.centre
        roundIdx = played
        provisional = false; winSeat = -1
        phase = FindItPhase.PLAYING
        planRound()
        event = FindItEvent.DEAL; eventSeat = -1; eventSymbol = -1; serial++
    }

    private fun nextScene() {
        if (sceneIdx + 1 >= scenes) { finish(); return }
        sceneIdx++
        phase = FindItPhase.PLAYING
        buildScene()
        event = FindItEvent.DEAL; eventSeat = -1; eventSymbol = -1; serial++
    }

    private fun finish() {
        phase = FindItPhase.OVER
        provisional = false
        result = buildResult()
        event = FindItEvent.OVER; eventSeat = -1; eventSymbol = -1; serial++
    }

    private fun median(list: List<Long>): Long {
        val s = list.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun buildResult(): FindItResult {
        val top = scores.maxOrNull() ?: 0
        val progress = scores.sum() > 0
        val winners: List<Int>
        var cleared = false
        when (mode) {
            FindItMode.GROUP_HUNT -> {
                cleared = sceneIdx + 1 >= scenes && scene?.let { found.size >= it.targets.size } == true
                winners = if (progress) (0 until n).toList() else emptyList()
            }
            FindItMode.SOLO_DASH -> winners = if (top > 0) listOf(0) else emptyList()
            FindItMode.MATCH_RACE -> winners = (0 until n).filter { top > 0 && scores[it] == top }
        }
        val badges = List(n) { mutableListOf<FindItBadge>() }
        val medians = (0 until n).filter { winTimes[it].isNotEmpty() }.associateWith { median(winTimes[it]) }
        medians.values.minOrNull()?.let { m -> medians.filterValues { it == m }.keys.forEach { badges[it] += FindItBadge.FASTEST } }
        if (scores.sum() >= 3 || mode == FindItMode.GROUP_HUNT) {
            val low = misses.minOrNull() ?: 0
            for (i in 0 until n) if (misses[i] == low) badges[i] += FindItBadge.SHARP
        }
        if (top > 0) for (i in 0 until n) if (scores[i] == top) badges[i] += FindItBadge.COLLECTOR
        for (i in 0 until n) if (badges[i].isEmpty()) badges[i] += FindItBadge.SHARP
        return FindItResult(winners, badges.map { it.sorted() }, cleared, tie = mode == FindItMode.MATCH_RACE && winners.size > 1)
    }

    // ---- views -----------------------------------------------------------------------------------

    private fun hintTimes(): Pair<Long, Long> {
        val a = if (cfg.relaxed) FindItRules.RELAXED_HINT_NARROW_MS else FindItRules.HINT_NARROW_MS
        val b = if (cfg.relaxed) FindItRules.RELAXED_HINT_PULSE_MS else FindItRules.HINT_PULSE_MS
        return (a * cfg.timeScale).toLong() to (b * cfg.timeScale).toLong()
    }

    private fun candidatesFor(seat: Int, answer: Int): List<Int> {
        val rng = FindItRng(findItDerive(seed, TAG_CAND, roundIdx.toLong(), seat.toLong()))
        val others = deck[own[seat]].filter { it != answer }.toMutableList()
        rng.shuffle(others)
        return (listOf(answer) + others.take(2)).sorted()
    }

    private fun huntHintDelayMs(): Long {
        if (cfg.huntHint == "off") return Long.MAX_VALUE
        val base = if (cfg.huntHint == "fast") FindItRules.HUNT_HINT_FAST_MS else FindItRules.HUNT_HINT_SLOW_MS
        val relaxedBase = if (cfg.relaxed) base / 2 else base
        return (relaxedBase * cfg.timeScale).toLong()
    }

    override fun state(): FindItState {
        if (!begun) return FindItState.EMPTY.copy(mode = mode, paused = paused)
        val playing = phase == FindItPhase.PLAYING
        val (narrowAt, pulseAt) = hintTimes()
        val stall = (FindItRules.stallMs(size, cfg.oneSwitch) * cfg.timeScale).toLong()
        val clue = isCards && playing && roundMs >= stall && (roundMs - stall) % FindItRules.CLUE_PERIOD_MS < FindItRules.CLUE_PULSE_MS
        val seatStates = List(n) { i ->
            val card = if (isCards) deck[own[i]] else emptyList()
            val ans = if (isCards && centre >= 0) answerFor(i) else -1
            var hint = FindItHint.NONE
            if (isCards && playing && cfg.hasHintGlow(i) && !seats[i].isBot) {
                hint = when {
                    roundMs >= pulseAt -> FindItHint.PULSE
                    roundMs >= narrowAt -> FindItHint.NARROW
                    else -> FindItHint.NONE
                }
            }
            FindItSeatState(
                name = seats[i].name, isBot = seats[i].isBot, score = scores[i], misses = misses[i],
                locked = isCards && playing && lockedUntil[i] > roundMs,
                hint = hint, card = card, cardId = if (isCards) own[i] else -1,
                candidates = if (hint != FindItHint.NONE) candidatesFor(i, ans) else emptyList(),
                pulse = if (hint == FindItHint.PULSE || clue) ans else -1
            )
        }
        var hintTarget = -1
        var spotlight = -1
        if (!isCards && playing) {
            val delay = huntHintDelayMs()
            val next = scene?.targets?.firstOrNull { it !in found } ?: -1
            if (next >= 0 && delay != Long.MAX_VALUE && sinceFindMs >= delay) {
                hintTarget = next
                val spot = if (cfg.relaxed) FindItRules.HUNT_SPOTLIGHT_AFTER_MS / 2 else FindItRules.HUNT_SPOTLIGHT_AFTER_MS
                if (sinceFindMs >= delay + (spot * cfg.timeScale).toLong()) spotlight = next
            }
        }
        val clockLen = when {
            dashTimed -> FindItRules.dashMs(size)
            huntTimed -> FindItRules.huntSceneMs(size)
            else -> 0L
        }
        return FindItState(
            phase = phase, mode = mode, seats = seatStates,
            centre = if (isCards && centre >= 0) deck[centre] else emptyList(), centreId = centre,
            round = if (isCards) roundIdx + 1 else 0, rounds = rounds,
            timeLeftMs = if (clockLen > 0) clockLeftMs.coerceAtLeast(0) else 0L, clockMs = clockLen,
            clueActive = clue, scene = scene, found = found.keys.toSet(), foundBy = found.toMap(),
            hintTarget = hintTarget, spotlight = spotlight,
            sceneIndex = sceneIdx, scenes = if (isCards) 0 else scenes, tries = tries, paused = paused,
            lastWinnerSeat = lastWinner, event = event, eventSeat = eventSeat, eventSymbol = eventSymbol,
            eventSerial = serial, result = result, nowMs = nowMs
        )
    }

    override fun replay(): FindItReplay = FindItReplay(
        seed = seed, mode = mode.id, cardSize = size.id, length = cfg.length.id, relaxed = cfg.relaxed,
        handicap = cfg.handicap, huntHint = cfg.huntHint, oneSwitch = cfg.oneSwitch, difficulty = cfg.difficulty.name,
        timeScale = cfg.timeScale, seats = n, botSeats = seats.map { it.isBot }, taps = log.toList()
    )

    // ---- save / restore --------------------------------------------------------------------------

    override fun toSave(): FindItSave? {
        if (!begun || phase == FindItPhase.OVER) return null
        var round = roundIdx
        var scene = sceneIdx
        var ownCards = own.toList()
        var centreCard = centre
        var ord: List<Int> = order
        var pile = pileIndex
        var re = reshuffles
        var foundList = found.keys.toList()
        if (isCards) {
            if (phase == FindItPhase.ROUND_WON) {
                // the celebration is a moment: save the table as the next round would find it
                val last = when (mode) {
                    FindItMode.MATCH_RACE -> roundIdx + 1 >= rounds
                    else -> cfg.relaxed && roundIdx + 1 >= rounds
                }
                if (last) return null
                val d = draw(order, pileIndex, reshuffles, own.toSet(), centre)
                ord = d.order; pile = d.pile; re = d.reshuffles; centreCard = d.centre
                round = roundIdx + 1
            }
        } else {
            if (phase == FindItPhase.SCENE_CLEAR) {
                if (sceneIdx + 1 >= scenes) return null
                scene = sceneIdx + 1
                foundList = emptyList()
            }
        }
        return FindItSave(
            mode = mode.id, cardSize = size.id, length = cfg.length.id, relaxed = cfg.relaxed, handicap = cfg.handicap,
            huntHint = cfg.huntHint, oneSwitch = cfg.oneSwitch, difficulty = cfg.difficulty.name, seed = seed,
            reshuffles = re, deckOrder = if (isCards) ord.toList() else emptyList(), pileIndex = if (isCards) pile else 0,
            round = round, sceneIndex = scene, tries = tries,
            scores = scores.toList(), misses = misses.toList(),
            ownCards = if (isCards) ownCards else emptyList(), centreCard = if (isCards) centreCard else 0,
            elapsedMs = when {
                dashTimed -> clockLeftMs.coerceIn(1L, FindItRules.dashMs(size))
                else -> nowMs.coerceIn(0L, FindItRules.MAX_ELAPSED_MS)
            },
            found = if (isCards) emptyList() else foundList,
            playerIds = seats.map { it.playerId }
        )
    }

    override fun restore(save: FindItSave): Boolean {
        if (!FindItSaveCodec.validate(save, seats)) return false
        val newCfg = cfg.copy(
            mode = FindItMode.fromId(save.mode), cardSize = FindItCardSize.fromId(save.cardSize),
            length = FindItLength.fromId(save.length), relaxed = save.relaxed, handicap = save.handicap,
            huntHint = save.huntHint, oneSwitch = save.oneSwitch, seed = save.seed,
            difficulty = runCatching { CpuDifficulty.valueOf(save.difficulty) }.getOrDefault(CpuDifficulty.MEDIUM)
        )
        val keepSeats = seats
        setup(newCfg, keepSeats)
        begun = true
        phase = FindItPhase.READY
        scores = save.scores.toIntArray()
        misses = save.misses.toIntArray()
        tries = save.tries
        if (isCards) {
            order = save.deckOrder.toMutableList()
            pileIndex = save.pileIndex
            reshuffles = save.reshuffles
            own = save.ownCards.toIntArray()
            centre = save.centreCard
            roundIdx = save.round
            clockLeftMs = if (dashTimed) save.elapsedMs else 0L
            // wins already made are not re-timed: the badge medians start again from what is left
        } else {
            sceneIdx = save.sceneIndex
            buildScene()
            for (s in save.found) found[s] = 0
        }
        event = FindItEvent.DEAL
        serial = 1
        return true
    }
}
