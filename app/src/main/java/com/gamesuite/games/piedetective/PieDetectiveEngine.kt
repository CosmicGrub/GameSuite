package com.gamesuite.games.piedetective

import com.gamesuite.settings.CpuDifficulty
import kotlin.random.Random

/**
 * The rules of Pie Detective (docs/design/kids-games/pie-detective.md, section 2). Pure and deterministic for a given
 * book seed; no Android, no Compose. Every acting call takes the seat that is acting, so a later Nearby host can
 * validate who may do what without changing this contract. The thief is never exposed in [PieState] while a case runs.
 *
 * Turn flow: PASS (people only, between different people) -> CHOOSE -> PEEK -> FLIP -> SNEAK -> the next seat. The
 * SNEAK step is observable on purpose: the card has been drawn and the thief has moved, but the turn only moves on when
 * the caller (the screen after its animation, or a Sidekick) calls [sneakDone]. A wrong Catch! also goes through SNEAK.
 */
interface PieEngine {
    var band: PieBand
    var handicapSpec: String
    var difficulty: CpuDifficulty
    var hints: Boolean
    var autoFlip: Boolean

    /** Set the table (2 to 4 seats). Does not deal; call [startBook] for that. */
    fun setup(seats: List<PieSeat>)

    /** Begins a Case Book: case 1 is dealt from [bookSeed]. */
    fun startBook(bookSeed: Long)

    /** Null before [startBook]. */
    fun state(): PieState?

    /** The human on a PASS screen pressed "I'm ready". */
    fun passAcknowledged(seat: Int): PieResult

    /** Ask about [trait]; [lucky] spends the seat's Lucky peek (the Sneak step of this turn is then skipped). */
    fun chooseTrait(seat: Int, trait: PieTrait, lucky: Boolean): PieResult

    /** The lens was held for [PieRules.PEEK_HOLD_MS] (or toggled on). Makes "Show everyone" available. */
    fun peekHeld(seat: Int): PieResult

    /** "Show everyone": stamps the clue in the notebook, then the team flips. */
    fun showEveryone(seat: Int): PieResult

    /** Flip (clear) [suspectId]; rejected with [PieResult.STILL_MATCHES] when it matches the shown clue. */
    fun flip(seat: Int, suspectId: String): PieResult

    /** Starts a Catch! (the turn's action instead of asking). */
    fun startCatch(seat: Int): PieResult

    /** Catch [suspectId]: wins the case, or clears it and moves the thief two hops. */
    fun catchSuspect(seat: Int, suspectId: String): PieResult

    /** Back out of a PEEK or CATCH to the trait choice at no cost. */
    fun cancelToChoose(seat: Int): PieResult

    /** From SNEAK: the thief's move has been shown; the case is lost (thief at the gate) or the next seat is up. */
    fun sneakDone(): PieResult

    /** From CASE_WON / CASE_LOST: deal the next case, or end the book. */
    fun nextCase(): PieResult

    /** Lets a Sidekick seat play one step of its turn. Returns false when it is not a Sidekick's turn (or paused). */
    fun sidekickStep(): Boolean

    /** The suggested next step for the seat whose turn it is, or null when none or not running. */
    fun hint(): PieHint?

    /**
     * The app lost the foreground (or a dialog opened): Sidekick autoplay freezes, a half-made peek goes back to the
     * trait choice at no cost, and a person's turn is covered by the pass screen again (when two or more people play),
     * so nobody who picks the device up next sees a lens answer.
     */
    fun pause()
    fun resume()

    /** Book tally. */
    val session: PieSession

    /** Everything needed to continue (at a turn boundary), or null when there is nothing to save. */
    fun toSave(): PieSave?

    /** True only when the engine is fully back in [save]; on false it must be left untouched. */
    fun restore(save: PieSave): Boolean
}

/** The real engine. */
class PieEngineImpl : PieEngine {
    override var band: PieBand = PieBand.MEDIUM
    override var handicapSpec: String = "none"
    override var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    override var hints: Boolean = true
    override var autoFlip: Boolean = false

    private var seats: List<PieSeat> = emptyList()
    private var started = false
    private var paused = false

    // book
    private var bookSeed = 0L
    private var caseIdx = 0
    private var solved = 0
    private var starTotal = 0
    private var luckyTotal = 0
    private var turns = 0
    private var eventSerial = 0
    private var lastEvent: PieEvent? = null
    private var lastCaseHops = 0

    // case
    private var phase = PiePhase.BOOK_DONE
    private var lineup: List<String> = emptyList()
    private var thief: String = ""
    private var deck: List<Boolean> = emptyList()
    private val flipped = ArrayList<String>()
    private val clues = ArrayList<PieClue>()
    private var hops = 0
    private var sneakIndex = 0
    private var luckyUsed = IntArray(0)
    private var allowance: List<Int> = emptyList()

    // turn
    private var seat = 0
    private var lastHuman = -1
    private var nextStartSeat = 0
    private var pendingTrait: PieTrait? = null
    private var pendingLucky = false
    private var peeked = false
    private var luckySkip = false
    private var lastSneakDash: Boolean? = null
    private var wrongCatch = false
    private var caseStars = 0

    private val humans: Int get() = seats.count { !it.isBot }
    private val ready: Boolean get() = seats.size in PieRules.MIN_SEATS..PieRules.MAX_SEATS

    override fun setup(seats: List<PieSeat>) {
        this.seats = seats.toList()
        started = false
        phase = PiePhase.BOOK_DONE
        paused = false
    }

    // ---- views ----

    private fun uncleared(): Int = lineup.size - flipped.size

    private fun suspects(): List<PieSuspect> = lineup.mapNotNull { PieCast.byId(it) }

    private fun offered(): List<PieTrait> = PieRules.traitsOf(band)

    private fun free(): List<PieTrait> = offered().filter { t -> clues.none { it.trait == t } }

    private fun luckyLeft(s: Int): Int = (allowance.getOrElse(s) { 0 } - luckyUsed.getOrElse(s) { 0 }).coerceAtLeast(0)

    override val session: PieSession
        get() {
            val p = PieRules.band(band)
            val caseOver = phase == PiePhase.CASE_WON || phase == PiePhase.CASE_LOST || phase == PiePhase.BOOK_DONE
            val played = if (!started) 0 else caseIdx + if (caseOver) 1 else 0
            return PieSession(
                casesPlayed = played, casesSolved = solved, casesInBook = p.cases, stars = starTotal,
                luckyPeeksUsed = luckyTotal, bookFinished = started && played >= p.cases, lastCaseHops = lastCaseHops
            )
        }

    override fun state(): PieState? {
        if (!started) return null
        val over = phase == PiePhase.CASE_WON || phase == PiePhase.CASE_LOST
        val peek = phase == PiePhase.PEEK
        return PieState(
            phase = phase, band = band, caseIndex = caseIdx, casesInBook = PieRules.band(band).cases,
            lineup = lineup.map { PieSuspectView(it, it in flipped) },
            offeredTraits = offered(), availableTraits = free(), clues = clues.toList(),
            currentSeat = seat, thiefHops = hops, lastSneakWasDash = lastSneakDash,
            pendingTrait = if (peek) pendingTrait else null,
            pendingAnswer = if (peek) pendingTrait?.let { t -> thiefSuspect()?.has(t) } else null,
            peeked = peek && peeked,
            luckyAvailable = phase == PiePhase.CHOOSE && luckyLeft(seat) > 0 || (peek && pendingLucky),
            uncleared = uncleared(),
            revealedThiefId = if (over) thief else null,
            lastEvent = lastEvent,
            sneakSkipped = luckySkip && phase == PiePhase.SNEAK,
            wrongCatch = wrongCatch && phase == PiePhase.SNEAK,
            sneakDrawn = sneakIndex,
            caseStars = if (phase == PiePhase.CASE_WON) caseStars else 0,
            hasPass = humans >= 2
        )
    }

    private fun thiefSuspect(): PieSuspect? = PieCast.byId(thief)

    private fun emit(code: String) {
        eventSerial++
        lastEvent = PieEvent(eventSerial, code)
    }

    // ---- dealing and turns ----

    private fun dealCase() {
        val d = PieDealer.deal(bookSeed, caseIdx, band)
        lineup = d.lineup
        thief = d.thief
        deck = d.deck
        flipped.clear()
        clues.clear()
        hops = 0
        sneakIndex = 0
        luckyUsed = IntArray(seats.size)
        allowance = PieHandicap.parse(handicapSpec, seats.size)
        clearTurn()
        emit(PieEventCode.CASE_START)
    }

    private fun clearTurn() {
        pendingTrait = null
        pendingLucky = false
        peeked = false
        luckySkip = false
        lastSneakDash = null
        wrongCatch = false
        caseStars = 0
    }

    private fun needsPass(s: Int): Boolean = !seats[s].isBot && humans >= 2 && lastHuman != s

    private fun beginTurn() {
        clearTurn()
        phase = if (needsPass(seat)) PiePhase.PASS else PiePhase.CHOOSE
        if (phase == PiePhase.CHOOSE && !seats[seat].isBot) lastHuman = seat
    }

    override fun startBook(bookSeed: Long) {
        if (!ready) return
        this.bookSeed = bookSeed
        started = true
        paused = false
        caseIdx = 0
        solved = 0
        starTotal = 0
        luckyTotal = 0
        turns = 0
        lastCaseHops = 0
        lastHuman = -1
        seat = 0
        nextStartSeat = 0
        dealCase()
        beginTurn()
    }

    private fun running(): Boolean = started && ready

    private fun check(actor: Int, wanted: PiePhase): PieResult {
        if (!running()) return PieResult.NOT_ACTIVE
        if (phase == PiePhase.BOOK_DONE) return PieResult.NOT_ACTIVE
        if (phase != wanted) return PieResult.WRONG_PHASE
        if (actor != seat) return PieResult.NOT_YOUR_TURN
        return PieResult.OK
    }

    override fun passAcknowledged(seat: Int): PieResult {
        val r = check(seat, PiePhase.PASS)
        if (r != PieResult.OK) return r
        lastHuman = seat
        phase = PiePhase.CHOOSE
        return PieResult.OK
    }

    override fun chooseTrait(seat: Int, trait: PieTrait, lucky: Boolean): PieResult {
        val r = check(seat, PiePhase.CHOOSE)
        if (r != PieResult.OK) return r
        if (trait !in offered()) return PieResult.TRAIT_NOT_OFFERED
        if (clues.any { it.trait == trait }) return PieResult.TRAIT_ALREADY_ASKED
        if (lucky && luckyLeft(seat) <= 0) return PieResult.NO_LUCKY_PEEK_LEFT
        pendingTrait = trait
        pendingLucky = lucky
        peeked = false
        phase = PiePhase.PEEK
        emit(PieEventCode.PEEKED)
        return PieResult.OK
    }

    override fun peekHeld(seat: Int): PieResult {
        val r = check(seat, PiePhase.PEEK)
        if (r != PieResult.OK) return r
        peeked = true
        return PieResult.OK
    }

    override fun showEveryone(seat: Int): PieResult {
        val r = check(seat, PiePhase.PEEK)
        if (r != PieResult.OK) return r
        if (!peeked) return PieResult.WRONG_PHASE
        val trait = pendingTrait ?: return PieResult.WRONG_PHASE
        val answer = thiefSuspect()?.has(trait) ?: return PieResult.WRONG_PHASE
        clues += PieClue(trait, answer)
        turns++
        if (pendingLucky) {
            luckyUsed[seat]++
            luckyTotal++
            luckySkip = true
        }
        pendingTrait = null
        pendingLucky = false
        peeked = false
        phase = PiePhase.FLIP
        emit(PieEventCode.SHOWN)
        if (autoFlip || seats[seat].isBot) flipAllNonMatching() else finishFlipsIfDone()
        return PieResult.OK
    }

    private fun matchesLatestClue(id: String): Boolean {
        val c = clues.lastOrNull() ?: return true
        val s = PieCast.byId(id) ?: return true
        return s.has(c.trait) == c.yes
    }

    private fun flipAllNonMatching() {
        var any = false
        for (id in lineup) {
            if (id !in flipped && !matchesLatestClue(id)) {
                flipped += id
                any = true
            }
        }
        if (any) emit(PieEventCode.FLIPPED)
        finishFlipsIfDone()
    }

    private fun finishFlipsIfDone() {
        if (phase != PiePhase.FLIP) return
        if (lineup.any { it !in flipped && !matchesLatestClue(it) }) return
        resolveSneak()
    }

    override fun flip(seat: Int, suspectId: String): PieResult {
        val r = check(seat, PiePhase.FLIP)
        if (r != PieResult.OK) return r
        if (suspectId !in lineup) return PieResult.NO_SUCH_SUSPECT
        if (suspectId in flipped) return PieResult.ALREADY_CLEARED
        if (matchesLatestClue(suspectId)) return PieResult.STILL_MATCHES
        flipped += suspectId
        emit(PieEventCode.FLIPPED)
        finishFlipsIfDone()
        return PieResult.OK
    }

    private fun resolveSneak() {
        phase = PiePhase.SNEAK
        wrongCatch = false
        if (luckySkip) {
            lastSneakDash = null
            emit(PieEventCode.SNEAK_PAUSE)
            return
        }
        val dash = deck[sneakIndex % PieRules.SNEAK_DECK]
        sneakIndex++
        lastSneakDash = dash
        if (dash) hops = (hops + 1).coerceAtMost(PieRules.LANE_HOPS)
        emit(if (dash) PieEventCode.SNEAK_DASH else PieEventCode.SNEAK_PAUSE)
    }

    override fun startCatch(seat: Int): PieResult {
        val r = check(seat, PiePhase.CHOOSE)
        if (r != PieResult.OK) return r
        phase = PiePhase.CATCH
        return PieResult.OK
    }

    override fun catchSuspect(seat: Int, suspectId: String): PieResult {
        val r = check(seat, PiePhase.CATCH)
        if (r != PieResult.OK) return r
        if (suspectId !in lineup) return PieResult.NO_SUCH_SUSPECT
        if (suspectId in flipped) return PieResult.ALREADY_CLEARED
        turns++
        if (suspectId == thief) {
            caseStars = PieRules.starsForCatch(hops)
            solved++
            starTotal += caseStars
            lastCaseHops = hops
            nextStartSeat = (seat + 1) % seats.size
            phase = PiePhase.CASE_WON
            emit(PieEventCode.CATCH_RIGHT)
            emit(PieEventCode.CASE_WON)
            return PieResult.OK
        }
        flipped += suspectId
        hops = (hops + PieRules.WRONG_CATCH_HOPS).coerceAtMost(PieRules.LANE_HOPS)
        phase = PiePhase.SNEAK
        wrongCatch = true
        lastSneakDash = null
        luckySkip = false
        emit(PieEventCode.CATCH_WRONG)
        return PieResult.OK
    }

    override fun cancelToChoose(seat: Int): PieResult {
        if (!running()) return PieResult.NOT_ACTIVE
        if (phase != PiePhase.PEEK && phase != PiePhase.CATCH) {
            return if (phase == PiePhase.BOOK_DONE) PieResult.NOT_ACTIVE else PieResult.WRONG_PHASE
        }
        if (seat != this.seat) return PieResult.NOT_YOUR_TURN
        pendingTrait = null
        pendingLucky = false
        peeked = false
        phase = PiePhase.CHOOSE
        return PieResult.OK
    }

    override fun sneakDone(): PieResult {
        if (!running()) return PieResult.NOT_ACTIVE
        if (phase != PiePhase.SNEAK) return if (phase == PiePhase.BOOK_DONE) PieResult.NOT_ACTIVE else PieResult.WRONG_PHASE
        if (hops >= PieRules.LANE_HOPS) {
            lastCaseHops = PieRules.LANE_HOPS
            nextStartSeat = (seat + 1) % seats.size
            phase = PiePhase.CASE_LOST
            emit(PieEventCode.CASE_LOST)
            return PieResult.OK
        }
        seat = (seat + 1) % seats.size
        beginTurn()
        return PieResult.OK
    }

    override fun nextCase(): PieResult {
        if (!running()) return PieResult.NOT_ACTIVE
        if (phase != PiePhase.CASE_WON && phase != PiePhase.CASE_LOST) {
            return if (phase == PiePhase.BOOK_DONE) PieResult.NOT_ACTIVE else PieResult.WRONG_PHASE
        }
        if (caseIdx + 1 >= PieRules.band(band).cases) {
            phase = PiePhase.BOOK_DONE
            return PieResult.OK
        }
        caseIdx++
        seat = nextStartSeat
        dealCase()
        beginTurn()
        return PieResult.OK
    }

    // ---- Sidekick ----

    private fun worstGroup(t: PieTrait, alive: List<PieSuspect>): Int {
        val yes = alive.count { it.has(t) }
        return maxOf(yes, alive.size - yes)
    }

    /** The free trait with the smallest worst-case group; ties go to the earlier trait. Null when none is free. */
    private fun bestSplit(): PieTrait? {
        val alive = suspects().filter { it.id !in flipped }
        val candidates = free()
        if (candidates.isEmpty()) return null
        val splitting = candidates.filter { worstGroup(it, alive) < alive.size }
        val pool = splitting.ifEmpty { candidates }
        return pool.minWithOrNull(compareBy<PieTrait>({ worstGroup(it, alive) }, { it.ordinal }))
    }

    override fun hint(): PieHint? {
        if (!running() || phase != PiePhase.CHOOSE) return null
        if (uncleared() <= 1) return PieHint(null, true)
        return PieHint(bestSplit(), false)
    }

    private fun sidekickRandom(): Random =
        Random(PieDealer.caseSeed(bookSeed, caseIdx) xor 0x51DEL xor (clues.size * 7919L) xor (seat * 104_729L) xor (turns * 31L))

    override fun sidekickStep(): Boolean {
        if (!running() || paused) return false
        if (!seats[seat].isBot) return false
        when (phase) {
            PiePhase.CHOOSE -> {
                if (uncleared() == 1) {
                    val only = lineup.first { it !in flipped }
                    startCatch(seat)
                    catchSuspect(seat, only)
                    return true
                }
                val rnd = sidekickRandom()
                val trait = when (difficulty) {
                    CpuDifficulty.HARD -> bestSplit()
                    CpuDifficulty.MEDIUM -> if (rnd.nextInt(100) < 70) bestSplit() else free().randomOrNull(rnd)
                    CpuDifficulty.EASY -> free().randomOrNull(rnd)
                } ?: return false
                val lucky = luckyLeft(seat) > 0
                if (chooseTrait(seat, trait, lucky) != PieResult.OK) return false
                peekHeld(seat)
                return true
            }
            PiePhase.PEEK -> return showEveryone(seat) == PieResult.OK
            PiePhase.FLIP -> {
                flipAllNonMatching()
                return true
            }
            PiePhase.SNEAK -> return sneakDone() == PieResult.OK
            PiePhase.CATCH -> return cancelToChoose(seat) == PieResult.OK
            else -> return false
        }
    }

    override fun pause() {
        paused = true
        if (!running()) return
        if (phase == PiePhase.PEEK && !seats[seat].isBot) {
            pendingTrait = null
            pendingLucky = false
            peeked = false
            phase = PiePhase.CHOOSE
        }
        if (humans >= 2) {
            lastHuman = -1
            if (phase == PiePhase.CHOOSE && !seats[seat].isBot) phase = PiePhase.PASS
        }
    }

    override fun resume() {
        paused = false
    }

    // ---- save and restore ----

    private fun copyFrom(o: PieEngineImpl) {
        band = o.band; handicapSpec = o.handicapSpec; difficulty = o.difficulty; hints = o.hints; autoFlip = o.autoFlip
        seats = o.seats; started = o.started; paused = o.paused
        bookSeed = o.bookSeed; caseIdx = o.caseIdx; solved = o.solved; starTotal = o.starTotal; luckyTotal = o.luckyTotal
        turns = o.turns; eventSerial = o.eventSerial; lastEvent = o.lastEvent; lastCaseHops = o.lastCaseHops
        phase = o.phase; lineup = o.lineup; thief = o.thief; deck = o.deck
        flipped.clear(); flipped.addAll(o.flipped); clues.clear(); clues.addAll(o.clues)
        hops = o.hops; sneakIndex = o.sneakIndex; luckyUsed = o.luckyUsed.copyOf(); allowance = o.allowance
        seat = o.seat; lastHuman = o.lastHuman; nextStartSeat = o.nextStartSeat
        pendingTrait = o.pendingTrait; pendingLucky = o.pendingLucky; peeked = o.peeked; luckySkip = o.luckySkip
        lastSneakDash = o.lastSneakDash; wrongCatch = o.wrongCatch; caseStars = o.caseStars
    }

    override fun toSave(): PieSave? {
        if (!running() || phase == PiePhase.BOOK_DONE) return null
        // Settle on a copy so the live game is never changed by saving: finish the flips and the Sneak step, deal the
        // next case when this one is over, and save the turn boundary that follows.
        val w = PieEngineImpl()
        w.copyFrom(this)
        w.settleForSave()
        if (w.phase == PiePhase.BOOK_DONE) return null
        return w.saveNow()
    }

    private fun settleForSave() {
        if (phase == PiePhase.FLIP) {
            flipAllNonMatching()
        }
        if (phase == PiePhase.SNEAK) sneakDone()
        if (phase == PiePhase.CASE_WON || phase == PiePhase.CASE_LOST) nextCase()
        if (phase == PiePhase.PEEK || phase == PiePhase.CATCH) {
            pendingTrait = null; pendingLucky = false; peeked = false
            phase = PiePhase.CHOOSE
        }
    }

    private fun saveNow(): PieSave = PieSave(
        players = seats.map { PiePlayerSave(it.playerId, it.isBot) },
        band = band.id, handicap = handicapSpec.take(PieRules.MAX_HANDICAP_LENGTH), difficulty = difficulty.name,
        hints = hints, autoFlip = autoFlip, bookSeed = bookSeed, caseIndex = caseIdx, casesSolved = solved, stars = starTotal,
        lineup = lineup.toList(), thief = thief, flipped = flipped.toList(), asked = clues.map { PieSaveCodec.clueEntry(it) },
        hops = hops, sneakIndex = sneakIndex % PieRules.SNEAK_DECK, currentSeat = seat, luckyUsed = luckyUsed.toList(),
        turns = turns, eventSerial = eventSerial, luckyTotal = luckyTotal
    )

    override fun restore(save: PieSave): Boolean {
        if (!ready) return false
        if (!PieSaveCodec.isValid(save, seats)) return false
        val b = PieBand.entries.firstOrNull { it.id == save.band } ?: return false
        val d = CpuDifficulty.entries.firstOrNull { it.name == save.difficulty } ?: return false
        val restoredClues = save.asked.map { PieSaveCodec.parseClue(it) ?: return false }
        // Everything is known good; assign.
        band = b
        handicapSpec = save.handicap
        difficulty = d
        hints = save.hints
        autoFlip = save.autoFlip
        bookSeed = save.bookSeed
        caseIdx = save.caseIndex
        solved = save.casesSolved
        starTotal = save.stars
        luckyTotal = save.luckyTotal
        turns = save.turns
        eventSerial = save.eventSerial
        lastEvent = null
        lastCaseHops = 0
        lineup = save.lineup.toList()
        thief = save.thief
        deck = PieDealer.deck(save.bookSeed, save.caseIndex, b)
        flipped.clear(); flipped.addAll(save.flipped)
        clues.clear(); clues.addAll(restoredClues)
        hops = save.hops
        sneakIndex = save.sneakIndex
        luckyUsed = save.luckyUsed.toIntArray()
        allowance = PieHandicap.parse(save.handicap, seats.size)
        seat = save.currentSeat
        nextStartSeat = (seat + 1) % seats.size
        lastHuman = -1
        started = true
        paused = false
        beginTurn()
        return true
    }
}
