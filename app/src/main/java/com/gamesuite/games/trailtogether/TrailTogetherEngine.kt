package com.gamesuite.games.trailtogether

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The rules of Trail Together (docs/design/kids-games/trail-together.md, section 2). Pure and deterministic for a
 * given seed; no Android, no Compose. Every call that acts takes the seat that is acting, so a later Nearby host can
 * validate who is allowed to do what without changing this contract.
 */
interface TrailEngine {
    var band: TrailBand
    var handicapSpec: String
    var difficulty: CpuDifficulty

    /** Set the table (1 to 4 seats) and start a fresh session tally. Does not deal; call [start] for that. */
    fun setup(seats: List<TrailSeat>)

    /** Deals a fresh try with [seed] (trail, deck, hands, fog). The session tally is kept. */
    fun start(seed: Long)

    /** Null before [start]. */
    fun state(): TrailState?

    /** Play the hop, stone or lantern card at [handIndex] of [seat]. Never throws; illegal plays change nothing. */
    fun playCard(seat: Int, handIndex: Int, target: TrailTarget): TrailMoveResult

    /** Discard the card at [handIndex] of [seat] and draw (always legal while a try is running). */
    fun swap(seat: Int, handIndex: Int): TrailMoveResult

    /** Lets a helper pal seat play one turn. Returns false when it is not a helper's turn (or the engine is paused). */
    fun helperStep(): Boolean

    /** The suggested play for the seat whose turn it is, or null when none or not running. Never illegal. */
    fun hint(): TrailHint?

    fun pause()
    fun resume()

    /** Everything needed to continue, or null when there is nothing to save (not started, or the try is over). */
    fun toSave(): TrailSave?

    /** True only when the engine is fully back in [save]; on false it must be left untouched. */
    fun restore(save: TrailSave): Boolean

    /** Tries finished (won or lost) in this session. */
    val sessionTries: Int
    val sessionWins: Int
}

/** The real engine. Not thread-safe by itself: the [TrailTogetherGame] wrapper serialises every call. */
class RealTrailEngine : TrailEngine {
    override var band: TrailBand = TrailBand.MEDIUM
    override var handicapSpec: String = "none"
    override var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    override var sessionTries: Int = 0
        private set
    override var sessionWins: Int = 0
        private set

    private var seats: List<TrailSeat> = emptyList()
    private var phase: TrailPhase? = null // null = not started
    private var seed = 0L
    private var activeBand = TrailBand.MEDIUM
    private var activeHandicap = "none"
    private var trail = ArrayList<TrailColour>()
    private var pals = IntArray(0)
    private var hands: List<ArrayList<TrailCard>> = emptyList()
    private var handSizes: List<Int> = emptyList()
    private val pile = ArrayDeque<TrailCard>()
    private val discard = ArrayList<TrailCard>()
    private var fog = 0
    private var currentSeat = 0
    private var plays = 0
    private var eventSerial = 0
    private var lastEvent: TrailEvent? = null
    private var lastAction: TrailAction? = null
    private var paused = false

    private val params get() = TrailRules.band(activeBand)
    private val length get() = params.length
    private val palCount get() = TrailRules.palsFor(seats.size)
    private val fogLength get() = TrailRules.deck(activeBand, palCount).fogCards
    private val running get() = phase == TrailPhase.PICK_CARD

    override fun setup(seats: List<TrailSeat>) {
        this.seats = seats.toList()
        phase = null
        sessionTries = 0
        sessionWins = 0
        paused = false
        lastEvent = null
        lastAction = null
    }

    override fun start(seed: Long) {
        val n = seats.size
        if (n !in TrailRules.MIN_SEATS..TrailRules.MAX_SEATS) return
        this.seed = seed
        activeBand = band
        activeHandicap = handicapSpec
        val p = params
        val colours = TrailRules.coloursOf(activeBand)
        val rng = Random(seed)

        // (1) trail colours: never the same as the spot before
        trail = ArrayList()
        var prev: TrailColour? = null
        repeat(p.builtAtStart) {
            val options = colours.filter { it != prev }
            val c = options[rng.nextInt(options.size)]
            trail += c
            prev = c
        }
        pals = IntArray(palCount)

        // (2) the deck minus fog, shuffled and dealt
        val spec = TrailRules.deck(activeBand, palCount)
        val deck = ArrayList<TrailCard>()
        for (c in colours) {
            repeat(spec.stonesPerColour) { deck += TrailCard(TrailCardKind.STONE, c) }
            repeat(spec.hopsPerColour) { deck += TrailCard(TrailCardKind.STEP, c) }
        }
        repeat(spec.lanterns) { deck += TrailCard(TrailCardKind.LANTERN) }
        deck.shuffle(rng)
        handSizes = TrailHandicap.parse(activeHandicap, n)
        var at = 0
        hands = List(n) { i ->
            val h = ArrayList<TrailCard>()
            repeat(handSizes[i]) { h += deck[at++] }
            h
        }

        // (3) the rest of the deck with the fog cards shuffled in
        val rest = ArrayList<TrailCard>(deck.subList(at, deck.size))
        repeat(spec.fogCards) { rest += TrailCard(TrailCardKind.FOG) }
        rest.shuffle(rng)
        pile.clear()
        pile.addAll(rest)
        discard.clear()
        fog = 0
        plays = 0
        currentSeat = 0
        lastEvent = null
        lastAction = null
        phase = TrailPhase.PICK_CARD
    }

    override fun pause() { paused = true }
    override fun resume() { paused = false }

    // ---- queries ------------------------------------------------------------------------

    override fun state(): TrailState? {
        val ph = phase ?: return null
        val len = length
        val spots = List(len + 1) { i ->
            when {
                i < trail.size -> TrailSpot(i, trail[i], true)
                i == len -> TrailSpot(i, null, trail.size >= len)
                else -> TrailSpot(i, null, false)
            }
        }
        return TrailState(
            phase = ph, band = activeBand, length = len, spots = spots, trail = trail.toList(),
            pals = pals.mapIndexed { i, pos -> TrailPal(i % seats.size, pos) },
            hands = hands.map { it.toList() }, handSizes = handSizes,
            currentSeat = currentSeat, fog = fog, fogLength = fogLength, cardsLeft = pile.size,
            burrowOpen = trail.size >= len, plays = plays, tries = sessionTries, wins = sessionWins,
            seed = seed, lastEvent = lastEvent, action = lastAction
        )
    }

    // ---- playing ------------------------------------------------------------------------

    private fun gate(seat: Int, handIndex: Int): TrailMoveResult? {
        if (!running) return TrailMoveResult.NOT_ACTIVE
        if (seat != currentSeat || seat !in hands.indices) return TrailMoveResult.NOT_YOUR_TURN
        if (handIndex !in hands[seat].indices) return TrailMoveResult.NO_SUCH_CARD
        return null
    }

    override fun playCard(seat: Int, handIndex: Int, target: TrailTarget): TrailMoveResult {
        gate(seat, handIndex)?.let { return it }
        val card = hands[seat][handIndex]
        val colour = card.colour
        when (card.kind) {
            TrailCardKind.STEP -> {
                val pal = target.palIndex
                if (pal !in pals.indices || pals[pal] >= length) return TrailMoveResult.INVALID_TARGET
                val to = TrailRules.hopTarget(trail, length, pals[pal], colour ?: return TrailMoveResult.NO_SUCH_CARD)
                if (to < 0) return TrailMoveResult.NO_LEGAL_TARGET
                applyHop(seat, handIndex, card, pal, to, viaLantern = false)
            }
            TrailCardKind.STONE -> {
                if (trail.size >= length) return TrailMoveResult.NO_LEGAL_TARGET
                applyStone(seat, handIndex, card, colour ?: return TrailMoveResult.NO_SUCH_CARD, viaLantern = false)
            }
            TrailCardKind.LANTERN -> when (target.lanternMode) {
                LanternMode.STONE -> {
                    val c = target.colour
                    if (c == null || c !in TrailRules.coloursOf(activeBand)) return TrailMoveResult.INVALID_TARGET
                    if (trail.size >= length) return TrailMoveResult.NO_LEGAL_TARGET
                    applyStone(seat, handIndex, card, c, viaLantern = true)
                }
                LanternMode.HOP -> {
                    val pal = target.palIndex
                    if (pal !in pals.indices || pals[pal] >= length) return TrailMoveResult.INVALID_TARGET
                    val to = TrailRules.stepTarget(trail, length, pals[pal])
                    if (to < 0) return TrailMoveResult.NO_LEGAL_TARGET
                    applyHop(seat, handIndex, card, pal, to, viaLantern = true)
                }
                null -> return TrailMoveResult.INVALID_TARGET
            }
            TrailCardKind.FOG -> return TrailMoveResult.NO_SUCH_CARD
        }
        return TrailMoveResult.OK
    }

    override fun swap(seat: Int, handIndex: Int): TrailMoveResult {
        gate(seat, handIndex)?.let { return it }
        val card = hands[seat].removeAt(handIndex)
        discard += card
        finishTurn(seat, TrailAction(serial = 0, seat = seat, kind = TrailEventCode.SWAP, card = card))
        return TrailMoveResult.OK
    }

    private fun applyHop(seat: Int, handIndex: Int, card: TrailCard, pal: Int, to: Int, viaLantern: Boolean) {
        val from = pals[pal]
        hands[seat].removeAt(handIndex)
        discard += card
        pals[pal] = to
        finishTurn(
            seat,
            TrailAction(serial = 0, seat = seat, kind = TrailEventCode.HOP, card = card, viaLantern = viaLantern, palIndex = pal, from = from, to = to)
        )
    }

    private fun applyStone(seat: Int, handIndex: Int, card: TrailCard, colour: TrailColour, viaLantern: Boolean) {
        val idx = trail.size
        hands[seat].removeAt(handIndex)
        discard += card
        trail += colour
        finishTurn(
            seat,
            TrailAction(
                serial = 0, seat = seat, kind = TrailEventCode.STONE, card = card, viaLantern = viaLantern,
                spotIndex = idx, colour = colour, burrowOpened = trail.size >= length
            )
        )
    }

    /** After a play or swap: win check, the refill (revealing Fog cards), the lose check, then the next seat. */
    private fun finishTurn(seat: Int, played: TrailAction) {
        plays++
        eventSerial++
        var action = played.copy(serial = eventSerial)
        var code = action.kind
        if (action.burrowOpened) code = TrailEventCode.BURROW_OPEN
        if (pals.all { it >= length }) {
            phase = TrailPhase.WON
            sessionTries++
            sessionWins++
            action = action.copy(won = true)
            code = TrailEventCode.WIN
        } else {
            var steps = 0
            var lost = false
            val hand = hands[seat]
            while (hand.size < handSizes[seat] && pile.isNotEmpty()) {
                val c = pile.removeFirst()
                if (c.kind == TrailCardKind.FOG) {
                    fog++
                    steps++
                    discard += c
                    if (fog >= fogLength) { lost = true; break }
                } else {
                    hand += c
                }
            }
            if (steps > 0 && !lost && code != TrailEventCode.BURROW_OPEN) code = TrailEventCode.FOG_STEP
            action = action.copy(fogSteps = steps)
            if (lost) {
                phase = TrailPhase.LOST
                sessionTries++
                action = action.copy(lost = true)
                code = TrailEventCode.LOSE
            } else {
                currentSeat = (seat + 1) % seats.size
            }
        }
        lastAction = action
        lastEvent = TrailEvent(eventSerial, code)
    }

    // ---- helper pals and hints ---------------------------------------------------------------

    private data class Play(val handIndex: Int, val target: TrailTarget, val score: Double, val swap: Boolean = false)

    private fun maxPalPosition(): Int = pals.maxOrNull() ?: 0

    /** Every legal play of [seat], each with its section 2.7 `smart` score. Empty when only a swap is possible. */
    private fun legalPlays(seat: Int): List<Play> {
        val out = ArrayList<Play>()
        val hand = hands[seat]
        val stoneBase = if (trail.size - maxPalPosition() <= 4) 1.5 else 0.5
        hand.forEachIndexed { hi, card ->
            when (card.kind) {
                TrailCardKind.STEP -> pals.indices.forEach { p ->
                    val to = TrailRules.hopTarget(trail, length, pals[p], card.colour ?: return@forEach)
                    if (to >= 0) out += Play(hi, TrailTarget(palIndex = p), hopScore(pals[p], to))
                }
                TrailCardKind.STONE -> if (trail.size < length) out += Play(hi, TrailTarget(), stoneBase + 0.2)
                TrailCardKind.LANTERN -> {
                    if (trail.size < length) {
                        out += Play(hi, TrailTarget(colour = bestLanternColour(), lanternMode = LanternMode.STONE), stoneBase - 0.5)
                    }
                    pals.indices.forEach { p ->
                        val to = TrailRules.stepTarget(trail, length, pals[p])
                        if (to >= 0) out += Play(hi, TrailTarget(palIndex = p, lanternMode = LanternMode.HOP), hopScore(pals[p], to) - 0.5)
                    }
                }
                TrailCardKind.FOG -> {}
            }
        }
        return out
    }

    private fun hopScore(from: Int, to: Int): Double {
        val jump = to - from
        return 1.0 + (if (jump >= 3) 1.0 else 0.0) + 0.3 * jump - 0.05 * from
    }

    /**
     * The colour a lantern lights: the one that gives the most travelling to pals that have no stone of it ahead, weighted
     * by how many hop cards of that colour the team holds (hands are open). Ties go to the first colour.
     */
    private fun bestLanternColour(): TrailColour {
        val colours = TrailRules.coloursOf(activeBand)
        var best = colours.first()
        var bestValue = -1.0
        for (c in colours) {
            val team = hands.sumOf { h -> h.count { it.kind == TrailCardKind.STEP && it.colour == c } }
            var gain = 0
            for (pos in pals) {
                if (pos >= length) continue
                var ahead = false
                for (i in pos + 1 until trail.size) if (trail[i] == c) { ahead = true; break }
                if (!ahead) gain += trail.size - pos
            }
            val value = gain * (1.0 + team)
            if (value > bestValue) { bestValue = value; best = c }
        }
        return best
    }

    /** The card to swap when nothing is playable: the one whose colour is least present ahead of the pals; else the first. */
    private fun worstCardToSwap(seat: Int): Int {
        val hand = hands[seat]
        var best = 0
        var bestCount = Int.MAX_VALUE
        hand.forEachIndexed { hi, card ->
            var count = 0
            val c = card.colour
            if (c != null) for (pos in pals) for (i in pos + 1 until trail.size) if (trail[i] == c) count++
            if (count < bestCount) { bestCount = count; best = hi }
        }
        return best
    }

    private fun smartPlay(seat: Int): Play {
        val plays = legalPlays(seat)
        val best = plays.maxWithOrNull(compareBy<Play> { it.score }.thenByDescending { it.handIndex }.thenByDescending { it.target.palIndex })
        return best ?: Play(worstCardToSwap(seat), TrailTarget(), 0.0, swap = true)
    }

    override fun hint(): TrailHint? {
        if (!running) return null
        val p = smartPlay(currentSeat)
        return TrailHint(p.handIndex, p.target, p.swap)
    }

    override fun helperStep(): Boolean {
        if (!running || paused || !seats[currentSeat].isBot) return false
        val seat = currentSeat
        // The decision stream is derived from the seed and the play number, so a replay or a resume decides the same.
        val rnd = Random((seed xor 0x5EEDL) * 1_000_003L + plays)
        val choice: Play = when (difficulty) {
            CpuDifficulty.EASY -> randomPlay(seat, rnd)
            CpuDifficulty.MEDIUM -> if (rnd.nextInt(100) < 25) randomPlay(seat, rnd) else smartPlay(seat)
            else -> smartPlay(seat)
        }
        val r = if (choice.swap) swap(seat, choice.handIndex) else playCard(seat, choice.handIndex, choice.target)
        if (r == TrailMoveResult.OK) return true
        // A legal play cannot be refused; if it ever is, a swap keeps the table moving.
        return swap(seat, 0) == TrailMoveResult.OK
    }

    private fun randomPlay(seat: Int, rnd: Random): Play {
        val legal = legalPlays(seat)
        if (legal.isEmpty() || rnd.nextInt(100) < 15) return Play(rnd.nextInt(hands[seat].size), TrailTarget(), 0.0, swap = true)
        val pick = legal[rnd.nextInt(legal.size)]
        if (pick.target.lanternMode == LanternMode.STONE) {
            val colours = TrailRules.coloursOf(activeBand)
            return pick.copy(target = pick.target.copy(colour = colours[rnd.nextInt(colours.size)]))
        }
        return pick
    }

    // ---- resume --------------------------------------------------------------------------------

    override fun toSave(): TrailSave? {
        if (!running) return null
        return TrailSave(
            players = seats.map { TrailPlayerSave(it.playerId, it.isBot) },
            band = activeBand.id, handicap = activeHandicap, difficulty = difficulty.name, seed = seed,
            trail = trail.map { it.id }, pals = pals.toList(),
            hands = hands.map { h -> h.map { it.code } },
            pile = pile.map { it.code }, discard = discard.map { it.code },
            fog = fog, currentSeat = currentSeat, plays = plays,
            tries = sessionTries, wins = sessionWins, eventSerial = eventSerial
        )
    }

    override fun restore(save: TrailSave): Boolean {
        if (!TrailSaveCodec.isValid(save, seats)) return false
        // isValid proved every code, id and count, so the parses below cannot fail; they are still non-throwing.
        val b = TrailBand.parse(save.band) ?: return false
        val newTrail = ArrayList<TrailColour>()
        for (id in save.trail) newTrail += TrailColour.fromId(id) ?: return false
        val newHands = ArrayList<ArrayList<TrailCard>>()
        for (h in save.hands) {
            val list = ArrayList<TrailCard>()
            for (code in h) list += TrailCard.fromCode(code) ?: return false
            newHands += list
        }
        val newPile = ArrayList<TrailCard>()
        for (code in save.pile) newPile += TrailCard.fromCode(code) ?: return false
        val newDiscard = ArrayList<TrailCard>()
        for (code in save.discard) newDiscard += TrailCard.fromCode(code) ?: return false
        val diff = CpuDifficulty.entries.firstOrNull { it.name == save.difficulty } ?: return false

        band = b
        activeBand = b
        handicapSpec = save.handicap
        activeHandicap = save.handicap
        difficulty = diff
        seed = save.seed
        trail = newTrail
        pals = save.pals.toIntArray()
        hands = newHands
        handSizes = TrailHandicap.parse(save.handicap, seats.size)
        pile.clear()
        pile.addAll(newPile)
        discard.clear()
        discard.addAll(newDiscard)
        fog = save.fog
        currentSeat = save.currentSeat
        plays = save.plays
        sessionTries = save.tries
        sessionWins = save.wins
        eventSerial = save.eventSerial
        lastEvent = null
        lastAction = null
        phase = TrailPhase.PICK_CARD
        return true
    }
}
