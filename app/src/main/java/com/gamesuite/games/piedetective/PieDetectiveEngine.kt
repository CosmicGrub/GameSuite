package com.gamesuite.games.piedetective

import com.gamesuite.settings.CpuDifficulty

/**
 * The rules of Pie Detective (docs/design/kids-games/pie-detective.md, section 2). Pure and deterministic for a given
 * book seed; no Android, no Compose. Every acting call takes the seat that is acting, so a later Nearby host can
 * validate who may do what without changing this contract. The thief is never exposed in [PieState] while a case runs.
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

    /** From CASE_WON / CASE_LOST: deal the next case, or end the book. */
    fun nextCase(): PieResult

    /** Lets a Sidekick seat play one step of its turn. Returns false when it is not a Sidekick's turn. */
    fun sidekickStep(): Boolean

    /** The suggested next step for the seat whose turn it is, or null when none or not running. */
    fun hint(): PieHint?

    fun pause()
    fun resume()

    /** Book tally. */
    val session: PieSession

    /** Everything needed to continue (at a CHOOSE boundary), or null when there is nothing to save. */
    fun toSave(): PieSave?

    /** True only when the engine is fully back in [save]; on false it must be left untouched. */
    fun restore(save: PieSave): Boolean
}

/**
 * A stub that returns safe values and holds no state, so the game wrapper, hub spec and screen can compile against
 * the final contract. Replace with the real engine in phase 0.
 */
class StubPieEngine : PieEngine {
    override var band: PieBand = PieBand.MEDIUM
    override var handicapSpec: String = "none"
    override var difficulty: CpuDifficulty = CpuDifficulty.MEDIUM
    override var hints: Boolean = true
    override var autoFlip: Boolean = false
    override val session: PieSession = PieSession(0, 0, 0, 0, 0, false)

    override fun setup(seats: List<PieSeat>) {}
    override fun startBook(bookSeed: Long) {}
    override fun state(): PieState? = null
    override fun passAcknowledged(seat: Int): PieResult = PieResult.NOT_ACTIVE
    override fun chooseTrait(seat: Int, trait: PieTrait, lucky: Boolean): PieResult = PieResult.NOT_ACTIVE
    override fun peekHeld(seat: Int): PieResult = PieResult.NOT_ACTIVE
    override fun showEveryone(seat: Int): PieResult = PieResult.NOT_ACTIVE
    override fun flip(seat: Int, suspectId: String): PieResult = PieResult.NOT_ACTIVE
    override fun startCatch(seat: Int): PieResult = PieResult.NOT_ACTIVE
    override fun catchSuspect(seat: Int, suspectId: String): PieResult = PieResult.NOT_ACTIVE
    override fun cancelToChoose(seat: Int): PieResult = PieResult.NOT_ACTIVE
    override fun nextCase(): PieResult = PieResult.NOT_ACTIVE
    override fun sidekickStep(): Boolean = false
    override fun hint(): PieHint? = null
    override fun pause() {}
    override fun resume() {}
    override fun toSave(): PieSave? = null
    override fun restore(save: PieSave): Boolean = false
}
