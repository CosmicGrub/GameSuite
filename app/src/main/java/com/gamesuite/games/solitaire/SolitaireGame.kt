package com.gamesuite.games.solitaire

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit

/**
 * One tableau column. [faceDown] and [faceUp] are both bottom-to-top order,
 * so `faceUp.last()` is the exposed, movable card and `faceDown.last()` is
 * the card that gets flipped up next once [faceUp] empties out. Kept as two
 * separate lists (rather than one list + a "how many are face up" count)
 * because moves only ever touch one end of one list at a time — no index
 * math needed to find the boundary between hidden and visible cards.
 */
data class TableauColumn(
    val faceDown: List<Card> = emptyList(),
    val faceUp: List<Card> = emptyList()
)

/** Where a pending move's card is coming from — foundations are a destination only in this MVP, never a source. */
sealed class SelectionSource {
    object Waste : SelectionSource()
    data class Tableau(val index: Int) : SelectionSource()
}

data class SolitaireState(
    val tableau: List<TableauColumn>,
    val stock: List<Card>,
    val waste: List<Card>,
    val foundations: Map<Suit, List<Card>>,
    val selected: SelectionSource? = null,
    /** +10 per card banked to a foundation this deal — see SolitaireGame's KDoc on scoring. */
    val score: Int = 0,
    /** One-line status for the last tap, e.g. "Can't place 5♦ there" — same pattern as MancalaState.lastAction/DominoState.lastAction. */
    val lastAction: String = "",
    /** This deal is solved (all 52 cards on the foundations) — distinct from [SolitaireGame.matchOver], which only becomes true once the whole session ends via leaveSession(). */
    val won: Boolean = false
) {
    fun cardAt(source: SelectionSource): Card? = when (source) {
        SelectionSource.Waste -> waste.lastOrNull()
        is SelectionSource.Tableau -> tableau[source.index].faceUp.lastOrNull()
    }

    /**
     * The standard "auto-complete available" check every commercial Klondike
     * implementation uses: once no card is face-down anywhere (every
     * [TableauColumn.faceDown] is empty *and* the stock is exhausted, since
     * an undrawn stock card is face-down too), the deal is a mathematically
     * forced win — every remaining card can always be walked to its
     * foundation using nothing but legal single-card moves, because tableau
     * columns still have room to act as buffers for whatever isn't
     * immediately playable. This property only says a win is *reachable*,
     * not that [SolitaireGame.autoCompleteStep]'s simple heuristic is
     * guaranteed to find the whole path — see that KDoc.
     */
    val autoCompleteAvailable: Boolean get() = !won && stock.isEmpty() && tableau.all { it.faceDown.isEmpty() }
}

/**
 * Standard Klondike, draw-1, single-card moves only — no multi-card run
 * dragging (an honest MVP simplification: real Klondike lets you move a
 * face-up run as a group, but this app has no drag input model at all, see
 * TileGameScreen/DominoesScreen's tap-then-place precedent, and modeling
 * "select a run" as a distinct concept from "select a card" would roughly
 * double this file's state surface for a move type that's a convenience,
 * not a rule the game is incomplete without — anything a run-move can do,
 * enough single-card moves can also do).
 *
 * Interaction is tap-select-then-tap-destination, same shape as every other
 * board game here: tap a card to select it, tap a legal destination
 * (another tableau column or a foundation) to move it there, tap the
 * already-selected card again to deselect. Tapping the stock always draws
 * (or recycles); tapping anything else while it isn't a legal destination
 * for the current selection just re-targets the selection to that pile's
 * own top card instead — there's no separate "cancel" gesture needed for
 * the common case of changing your mind about which card to move.
 *
 * Like Hangman (see its KDoc), this is a solo puzzle with no opponent, so
 * there's no CPU difficulty lever — [GameContext.players] holds exactly one
 * [PlayerInfo] and PlayMode.SINGLE_PLAYER_VS_BOT is used only because it's
 * the mode the shell has for "one human, no second seat needed", not
 * because there's a bot. Winning a deal (solved) does NOT end the match —
 * it bumps the session tally and lets the player deal again, mirroring
 * Hangman's word-by-word / MancalaGame-and-friends' round-by-round split
 * between "this round finished" and "the whole visit to this screen is
 * over".
 */
class SolitaireGame : GameModule {
    override val gameId = "solitaire"
    override val displayName = "Klondike Solitaire"
    override val category = GameCategory.CARD
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<SolitaireState?>(null)

    /**
     * Bounded undo history — a snapshot of [state] taken immediately before
     * each mutating move (draw/recycle the stock, or move a card to a
     * tableau column or foundation), oldest dropped once [MAX_UNDO] is
     * exceeded. Selecting/deselecting a card doesn't push a snapshot: those
     * taps don't change the board, so there'd be nothing meaningful to undo
     * back to, and letting Undo skip over them is exactly what a player
     * expects "undo my last move" to do. Cleared on every fresh deal (see
     * [startMatch]) so undo never reaches back into a previous deal.
     */
    private val history = ArrayDeque<SolitaireState>()

    /** True once [history] holds at least one snapshot — drives the Undo button's enabled state in SolitaireScreen, same reactive-flag pattern as [gamesWon]/[matchOver]. */
    val canUndo = mutableStateOf(false)

    /**
     * True while [autoCompleteStep] is being driven, one step at a time, by
     * SolitaireScreen's LaunchedEffect (see that composable — same
     * keyed-on-state-plus-delay shape as MancalaScreen's CPU-turn effect).
     * Every tap handler below no-ops while this is true so a stray tap
     * mid-auto-complete can't race a scheduled step or push a confusing
     * manual move into the middle of it; flips back to false the moment
     * [autoCompleteStep] runs out of moves it can find or the deal is won.
     */
    val isAutoCompleting = mutableStateOf(false)

    /** Session tally of solved deals — survives "New Game", reset only by init() (a fresh visit to this screen). */
    val gamesWon = mutableStateOf(0)

    /** True only once the whole session ends (user leaves via "Back to Menu"), not per-deal — see SolitaireState.won for that. */
    val matchOver = mutableStateOf(false)

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    override fun init(context: GameContext) {
        this.context = context
        gamesWon.value = 0
        matchOver.value = false
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    override fun startMatch() {
        history.clear()
        canUndo.value = false
        isAutoCompleting.value = false
        state.value = deal()
    }

    override fun pause() {}
    override fun resume() {}

    override fun endMatch(result: GameResult) {
        matchOver.value = true
        onMatchEnd?.invoke(result)
    }

    /** Column N (1-indexed) gets N cards: N-1 face-down, then 1 face-up on top. 1+2+...+7=28 dealt, 24 left for the stock. */
    private fun deal(): SolitaireState {
        val deck = Deck.shuffled52()
        var cursor = 0
        val columns = (1..7).map { size ->
            val faceDown = deck.subList(cursor, cursor + size - 1).toList()
            cursor += size - 1
            val faceUpCard = deck[cursor]
            cursor += 1
            TableauColumn(faceDown = faceDown, faceUp = listOf(faceUpCard))
        }
        val stock = deck.subList(cursor, deck.size).toList()
        return SolitaireState(
            tableau = columns,
            stock = stock,
            waste = emptyList(),
            foundations = Suit.entries.associateWith { emptyList<Card>() },
            lastAction = "New deal"
        )
    }

    /**
     * Draw one card face-up onto the waste (stock's last element is "the
     * top" throughout this file — see [removeFromSource]/deal's dealing
     * order for why). When the stock runs out, recycles the waste back into
     * the stock reversed: waste is built by *appending* each draw, so
     * reversing it exactly reproduces the original draw order once the
     * stock is popped from the same end again — the standard "the deck
     * cycles through the same sequence every pass" draw-1 rule, not a
     * reshuffle.
     */
    fun tapStock() {
        val s = state.value ?: return
        if (s.won || isAutoCompleting.value) return
        state.value = when {
            s.stock.isNotEmpty() -> {
                recordHistory(s)
                val card = s.stock.last()
                s.copy(stock = s.stock.dropLast(1), waste = s.waste + card, selected = null, lastAction = "Drew ${card.label}")
            }
            s.waste.isNotEmpty() -> {
                recordHistory(s)
                s.copy(stock = s.waste.reversed(), waste = emptyList(), selected = null, lastAction = "Recycled waste into stock")
            }
            else -> s.copy(lastAction = "Stock is empty")
        }
    }

    fun tapWaste() {
        val s = state.value ?: return
        if (s.won || isAutoCompleting.value) return
        if (s.selected == SelectionSource.Waste) {
            state.value = s.copy(selected = null, lastAction = "Deselected")
            return
        }
        val top = s.waste.lastOrNull()
        state.value = if (top != null) {
            s.copy(selected = SelectionSource.Waste, lastAction = "Selected ${top.label}")
        } else {
            s.copy(lastAction = "Waste is empty")
        }
    }

    /**
     * The single tap target for column [index] — whichever card in the
     * cascade is tapped, this is what fires (see the class KDoc on why
     * there's no per-card tap target within a column). Priority: deselect
     * if this column is already the selection; else attempt the move if a
     * selection exists and this column is a legal destination; else (no
     * selection, or an illegal destination) select/reselect this column's
     * own top card instead.
     */
    fun tapTableau(index: Int) {
        val s = state.value ?: return
        if (s.won || isAutoCompleting.value) return
        val selected = s.selected

        if (selected is SelectionSource.Tableau && selected.index == index) {
            state.value = s.copy(selected = null, lastAction = "Deselected")
            return
        }

        val destTop = s.tableau[index].faceUp.lastOrNull()

        if (selected != null) {
            val movingCard = s.cardAt(selected)
            if (movingCard != null && canPlaceOnTableau(movingCard, destTop)) {
                recordHistory(s)
                state.value = applyMoveToTableau(s, selected, index, movingCard)
                return
            }
        }

        state.value = if (destTop != null) {
            s.copy(selected = SelectionSource.Tableau(index), lastAction = "Selected ${destTop.label}")
        } else {
            s.copy(lastAction = if (selected != null) "Can't place there" else "Empty column")
        }
    }

    fun tapFoundation(suit: Suit) {
        val s = state.value ?: return
        if (s.won || isAutoCompleting.value) return
        val selected = s.selected
        if (selected == null) {
            state.value = s.copy(lastAction = "Select a card first")
            return
        }
        val movingCard = s.cardAt(selected) ?: return
        if (!canPlaceOnFoundation(movingCard, suit, s.foundations)) {
            state.value = s.copy(lastAction = "Can't place ${movingCard.label} there")
            return
        }
        recordHistory(s)
        val next = applyMoveToFoundation(s, selected, suit, movingCard)
        if (next.won) gamesWon.value += 1
        state.value = next
    }

    // ---- pure rule helpers — no Compose state touched, easy to hand-trace/unit-test ----

    /**
     * Rank.value (games/cards/Card.kt) is the shared *high-Ace* ranking used
     * by trick-taking/poker comparisons (TWO=2 ... KING=13, ACE=14) — it is
     * NOT the low-Ace sequencing Klondike tableau/foundation runs need
     * (A,2,3,...,K). Reusing it directly here was the game-breaking bug:
     * once an Ace (value 14) landed on a foundation, `top.value + 1` (15)
     * never matched any Rank, permanently capping every foundation at one
     * card and making the win condition unreachable in every deal; the same
     * high-Ace value also let a King illegally stack onto an exposed Ace
     * (13 == 14 - 1) while blocking the legal Ace-onto-Two tableau move
     * (14 != 2 - 1). This local mapping treats Ace as low (1) for both
     * checks below, matching real Klondike sequencing.
     */
    private fun lowAceValue(rank: Rank): Int = if (rank == Rank.ACE) 1 else rank.value

    private fun canPlaceOnTableau(card: Card, destTop: Card?): Boolean =
        if (destTop == null) card.rank == Rank.KING
        else lowAceValue(card.rank) == lowAceValue(destTop.rank) - 1 && card.suit.isRed != destTop.suit.isRed

    private fun canPlaceOnFoundation(card: Card, suit: Suit, foundations: Map<Suit, List<Card>>): Boolean {
        if (card.suit != suit) return false
        val top = foundations[suit]?.lastOrNull()
        return if (top == null) card.rank == Rank.ACE else lowAceValue(card.rank) == lowAceValue(top.rank) + 1
    }

    /**
     * Removes the selected card from its source. For a tableau source, this
     * is also where the standard auto-flip happens: if taking the top card
     * empties [TableauColumn.faceUp] and there's a face-down card waiting,
     * it's turned face-up immediately as part of the same move — the
     * invariant this file relies on elsewhere is that faceUp is only ever
     * empty when faceDown is too (a column with hidden cards left always
     * has exactly one of them showing).
     */
    private fun removeFromSource(s: SolitaireState, source: SelectionSource): SolitaireState = when (source) {
        SelectionSource.Waste -> s.copy(waste = s.waste.dropLast(1))
        is SelectionSource.Tableau -> {
            val col = s.tableau[source.index]
            val remainingFaceUp = col.faceUp.dropLast(1)
            val newCol = if (remainingFaceUp.isEmpty() && col.faceDown.isNotEmpty()) {
                TableauColumn(faceDown = col.faceDown.dropLast(1), faceUp = listOf(col.faceDown.last()))
            } else {
                col.copy(faceUp = remainingFaceUp)
            }
            s.copy(tableau = s.tableau.toMutableList().also { it[source.index] = newCol })
        }
    }

    private fun applyMoveToTableau(s: SolitaireState, source: SelectionSource, destIndex: Int, card: Card): SolitaireState {
        val afterRemove = removeFromSource(s, source)
        val destCol = afterRemove.tableau[destIndex]
        val newTableau = afterRemove.tableau.toMutableList().also {
            it[destIndex] = destCol.copy(faceUp = destCol.faceUp + card)
        }
        return afterRemove.copy(tableau = newTableau, selected = null, lastAction = "Moved ${card.label} to column ${destIndex + 1}")
    }

    /**
     * Scoring is deliberately the simple, honest version: +10 per card
     * banked to a foundation, nothing else (no bonus-for-speed, no
     * penalty-per-draw, none of the historical Vegas/standard-scoring
     * table's extra rules) — see notesForReadme.
     */
    private fun applyMoveToFoundation(s: SolitaireState, source: SelectionSource, suit: Suit, card: Card): SolitaireState {
        val afterRemove = removeFromSource(s, source)
        val newFoundations = afterRemove.foundations.toMutableMap()
        newFoundations[suit] = (newFoundations[suit] ?: emptyList()) + card
        val won = Suit.entries.all { (newFoundations[it]?.size ?: 0) == Rank.entries.size }
        return afterRemove.copy(
            foundations = newFoundations,
            selected = null,
            score = afterRemove.score + 10,
            lastAction = "Moved ${card.label} to foundation",
            won = won
        )
    }

    /** Called from the solved panel's "New Game" button — keeps the running tally, deals fresh. */
    fun playAgain() {
        if (matchOver.value) return
        startMatch()
    }

    /** Called from the solved panel's (or in-progress screen's) "Back to Menu" button — ends the whole session. */
    fun leaveSession() {
        if (matchOver.value) return
        val player = context.players.getOrNull(context.localPlayerIndex)
        val result = GameResult(
            scores = if (player != null) listOf(
                // Single-player convention (same call as Hangman's): isWinner = at least one deal was solved this session.
                PlayerScore(playerId = player.playerId, score = gamesWon.value, isWinner = gamesWon.value > 0)
            ) else emptyList()
        )
        endMatch(result)
    }

    /**
     * Snapshots [s] onto [history] right before a mutating move overwrites
     * [state], so [undo] has something to restore. Keeps at most
     * [MAX_UNDO] entries — the oldest is dropped once full — matching the
     * task's "3-5 step history" sizing rather than growing unbounded across
     * a long deal.
     */
    private fun recordHistory(s: SolitaireState) {
        history.addLast(s)
        if (history.size > MAX_UNDO) history.removeFirst()
        canUndo.value = true
    }

    /**
     * Pops the most recent pre-move snapshot off [history] (if any) and
     * makes it the current state. The popped snapshot isn't pushed back
     * anywhere, so calling this repeatedly walks further back through the
     * last few moves, one at a time, same as any standard undo stack.
     * No-ops when there's nothing to undo (including before a deal exists),
     * so wiring the Undo button straight to this call is always safe.
     */
    fun undo() {
        if (isAutoCompleting.value) return
        val previous = history.removeLastOrNull() ?: return
        state.value = previous
        canUndo.value = history.isNotEmpty()
    }

    /** Called from the "Auto-complete" button — only starts if [SolitaireState.autoCompleteAvailable] actually holds; SolitaireScreen's LaunchedEffect takes it from here, calling [autoCompleteStep] on a delay until it stops. */
    fun startAutoComplete() {
        val s = state.value ?: return
        if (!s.autoCompleteAvailable) return
        isAutoCompleting.value = true
    }

    /**
     * Plays exactly one auto-complete move, then returns — SolitaireScreen's
     * LaunchedEffect calls this again after a short delay for as long as
     * [isAutoCompleting] stays true, which is what turns a rapid burst of
     * single-card moves into a readable, one-card-at-a-time animation
     * instead of the whole deal resolving in one frame.
     *
     * Each move it plays is one of the two move types [tapTableau]/
     * [tapFoundation] already make — [applyMoveToFoundation] or
     * [applyMoveToTableau] — snapshotted onto [history] via [recordHistory]
     * exactly like a manual move, so Undo (once auto-complete finishes or is
     * interrupted) walks back through the last few auto-played steps one at
     * a time the same way it walks back through manual ones; auto-complete
     * gets no separate undo mechanism of its own.
     *
     * Priority per step: (1) any waste or tableau top card that can go
     * straight to its foundation — see [findFoundationMove]; (2) failing
     * that, a single tableau-to-tableau relocation that immediately exposes
     * a foundation-ready card underneath it — see
     * [findUnblockingTableauMove]. That second case is a deliberately
     * one-level-deep look-ahead, not a general solver: [SolitaireState.autoCompleteAvailable]
     * guarantees a full solution always exists, but this simple heuristic
     * can still run out of moves a few cards short of it on a deal that
     * needs a blocking card moved out of the way *twice* before anything
     * underneath is playable. When that happens this just stops (flips
     * [isAutoCompleting] back off) and hands the rest back to the player —
     * an honest simplification in the same spirit as this file's
     * single-card-move-only scope, not a bug.
     */
    fun autoCompleteStep() {
        if (!isAutoCompleting.value) return
        val s = state.value
        if (s == null || s.won) {
            isAutoCompleting.value = false
            return
        }

        val foundationMove = findFoundationMove(s)
        if (foundationMove != null) {
            val (source, suit, card) = foundationMove
            recordHistory(s)
            val next = applyMoveToFoundation(s, source, suit, card)
            if (next.won) {
                gamesWon.value += 1
                isAutoCompleting.value = false
            }
            state.value = next
            return
        }

        val bufferMove = findUnblockingTableauMove(s)
        if (bufferMove != null) {
            val (source, destIndex) = bufferMove
            val card = s.cardAt(source)
            if (card != null) {
                recordHistory(s)
                state.value = applyMoveToTableau(s, source, destIndex, card)
                return
            }
        }

        // Nothing this heuristic knows how to do is left — see this function's KDoc.
        isAutoCompleting.value = false
    }

    /** First card auto-complete finds that can go straight to its foundation right now — waste before tableau, left-to-right, same "read order" priority [tapTableau]/[tapWaste] already imply. */
    private fun findFoundationMove(s: SolitaireState): Triple<SelectionSource, Suit, Card>? {
        val wasteTop = s.waste.lastOrNull()
        if (wasteTop != null && canPlaceOnFoundation(wasteTop, wasteTop.suit, s.foundations)) {
            return Triple(SelectionSource.Waste, wasteTop.suit, wasteTop)
        }
        s.tableau.forEachIndexed { i, col ->
            val top = col.faceUp.lastOrNull()
            if (top != null && canPlaceOnFoundation(top, top.suit, s.foundations)) {
                return Triple(SelectionSource.Tableau(i), top.suit, top)
            }
        }
        return null
    }

    /**
     * A single tableau-to-tableau move that's *known* to help: moving some
     * column's top card onto another column's top card, where the card it
     * uncovers underneath is immediately foundation-ready. Deliberately
     * ignores moving a card onto an empty column — with no lookahead to
     * prove that's useful too, it's just as likely to shuffle a King back
     * and forth between empty columns forever as it is to help, and this
     * function's whole job is to only ever propose moves that provably make
     * progress (see [autoCompleteStep]'s KDoc on why that keeps this safe
     * without an iteration cap).
     */
    private fun findUnblockingTableauMove(s: SolitaireState): Pair<SelectionSource, Int>? {
        s.tableau.forEachIndexed { srcIndex, srcCol ->
            val top = srcCol.faceUp.lastOrNull() ?: return@forEachIndexed
            val exposedBeneath = srcCol.faceUp.getOrNull(srcCol.faceUp.lastIndex - 1) ?: return@forEachIndexed
            if (!canPlaceOnFoundation(exposedBeneath, exposedBeneath.suit, s.foundations)) return@forEachIndexed
            s.tableau.forEachIndexed { destIndex, destCol ->
                val destTop = destCol.faceUp.lastOrNull()
                if (destIndex != srcIndex && destTop != null && canPlaceOnTableau(top, destTop)) {
                    return SelectionSource.Tableau(srcIndex) to destIndex
                }
            }
        }
        return null
    }

    private companion object {
        /** How many moves back [undo] can reach — see [history]'s KDoc. */
        const val MAX_UNDO = 5
    }
}
