package com.gamesuite.games.solitaire

import androidx.compose.runtime.mutableStateOf
import com.gamesuite.core.*
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything needed to put a Klondike session back exactly as it was. One shape serves both Resume
 * (written to disk by the shell) and Undo (kept in memory in a [SnapshotHistory]), so a deal and its
 * tally can never be restored out of step with each other. Cards are stored as their stable 0..51
 * [Card.id] (see [Deck.fresh52]); the ORDER of every list is the order the engine uses, so the stock
 * order (the secret part of the deal) survives exactly. Not stored, on purpose: [SolitaireGame.drawThree]
 * (a player preference the screen re-applies from its own store), the hint (transient), and the
 * auto-complete flag (a resumed deal simply offers the button again).
 */
@Serializable
private data class SolitaireSnapshot(
    val playerId: String,
    /** Per tableau column, bottom-to-top, face-down cards. */
    val tableauDown: List<List<Int>>,
    /** Per tableau column, bottom-to-top, face-up cards. */
    val tableauUp: List<List<Int>>,
    val stock: List<Int>,
    val waste: List<Int>,
    /** One list per suit, in [Suit.entries] order, Ace first. */
    val foundations: List<List<Int>>,
    /** [SOLITAIRE_SELECTED_NONE], [SOLITAIRE_SELECTED_WASTE] or a tableau column index. */
    val selected: Int,
    val score: Int,
    val won: Boolean,
    val gamesWon: Int
)

private const val SOLITAIRE_SELECTED_NONE = -1
private const val SOLITAIRE_SELECTED_WASTE = -2

private val solitaireSnapshotJson = Json { ignoreUnknownKeys = true }

/** A snapshot already validated and turned back into live types. */
private class SolitaireDecoded(val state: SolitaireState, val gamesWon: Int)

/**
 * A suggested move for the human. [source] and [destination] are both null for "draw from the stock
 * (or recycle the waste)"; otherwise they name the card to pick up and where it should go.
 */
data class SolitaireHint(val source: SelectionSource?, val destination: MoveDestination?) {
    val isStockDraw: Boolean get() = source == null
}

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

/** Where a committed move's card ended up — the destination-side counterpart to [SelectionSource], reported alongside it via [SolitaireGame.setOnCardMoved]. */
sealed class MoveDestination {
    data class Tableau(val index: Int) : MoveDestination()
    data class Foundation(val suit: Suit) : MoveDestination()
}

/**
 * One committed card move — [SolitaireGame.setOnCardMoved]'s payload, fired
 * from inside [SolitaireGame] for every move [tapTableau]/[tapFoundation]/
 * [SolitaireGame.autoCompleteStep] commits alike. Purely descriptive (what
 * moved, from where, to where): SolitaireScreen is the one that turns this
 * into a fly-to-destination animation, since only it knows where piles
 * actually sit on screen — same "engine reports the event, screen owns the
 * animation" split as [SolitaireGame.setOnMatchEnd].
 */
data class CardMove(val card: Card, val source: SelectionSource, val destination: MoveDestination)

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
 * Standard Klondike, draw-1 by default (draw-3 is a persisted per-player
 * preference — see [drawThree] and games/solitaire/SolitairePrefsStore.kt),
 * single-card moves only — no multi-card run dragging (an honest MVP
 * simplification: real Klondike lets you move a
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
class SolitaireGame : GameModule, ResumableGame, UndoableGame {
    override val gameId = "solitaire"
    override val displayName = "Klondike Solitaire"
    override val category = GameCategory.CARD
    override val minPlayers = 1
    override val maxPlayers = 1
    override val supportedModes = listOf(PlayMode.SINGLE_PLAYER_VS_BOT)

    val state = mutableStateOf<SolitaireState?>(null)

    /**
     * Bounded undo history — a [SolitaireSnapshot] taken immediately before
     * each mutating move (draw/recycle the stock, or move a card to a
     * tableau column or foundation), oldest dropped once [MAX_UNDO] is
     * exceeded. Selecting/deselecting a card doesn't push a snapshot: those
     * taps don't change the board, so there'd be nothing meaningful to undo
     * back to, and letting Undo skip over them is exactly what a player
     * expects "undo my last move" to do. Cleared on every fresh deal (see
     * [startMatch]) so undo never reaches back into a previous deal.
     */
    private val history = SnapshotHistory(MAX_UNDO)

    /**
     * True while there is a move to take back. Reads Compose state ([SnapshotHistory.depth], [state],
     * [matchOver], [isAutoCompleting]) so the Undo buttons recompose on their own. False once the deal
     * is won: undoing the winning move behind the Solved panel would let one deal be "won" repeatedly.
     */
    override val canUndo: Boolean
        get() = history.depth > 0 && !matchOver.value && state.value?.won != true && !isAutoCompleting.value

    /** Bumped once per FRESH deal ([startMatch]), never by [restore] or [undo]. The screen keys its deal-in animation on this instead of parsing [SolitaireState.lastAction]. */
    val dealSeq = mutableStateOf(0)

    /** Bumped each time a tap is rejected as an illegal placement, so the screen can give failure feedback without parsing [SolitaireState.lastAction]. */
    val rejectedCount = mutableStateOf(0)

    /** The move the last [hint] suggested, shown until the next tap, undo, new deal or restore. */
    val hintMove = mutableStateOf<SolitaireHint?>(null)

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

    /**
     * Draw-3 preference, set by SolitaireScreen right after it reads
     * games/solitaire/SolitairePrefsStore.kt's persisted value (same
     * "screen reads the store, sets a plain var on the engine" wiring
     * UnoScreen uses for `game.difficulty = settings.defaultCpuDifficulty`).
     * Deliberately NOT an AppSettings.kt field — that store is documented
     * app-wide-only, and this is a single game's own preference.
     *
     * [tapStock] reads this directly; nothing else about draw-3 changes any
     * legality rule — [SolitaireState.cardAt] and every canPlaceOn* check
     * only ever look at `waste.lastOrNull()`, exactly as before, regardless
     * of how many cards a single stock draw moved to get it there.
     */
    var drawThree: Boolean = false

    private lateinit var context: GameContext
    private var onMatchEnd: ((GameResult) -> Unit)? = null

    /** See [CardMove]'s KDoc — registered once by SolitaireScreen, same precedent as [setOnMatchEnd]. */
    private var onCardMoved: ((CardMove) -> Unit)? = null

    override fun init(context: GameContext) {
        this.context = context
        gamesWon.value = 0
        matchOver.value = false
        isAutoCompleting.value = false
        history.clear()
        hintMove.value = null
    }

    // --- Resume -----------------------------------------------------------------------------

    private fun localPlayerId(): String =
        if (::context.isInitialized) context.players.getOrNull(context.localPlayerIndex)?.playerId.orEmpty() else ""

    private fun captureSnapshot(): String? {
        val s = state.value ?: return null
        return solitaireSnapshotJson.encodeToString(
            SolitaireSnapshot.serializer(),
            SolitaireSnapshot(
                playerId = localPlayerId(),
                tableauDown = s.tableau.map { col -> col.faceDown.map { it.id } },
                tableauUp = s.tableau.map { col -> col.faceUp.map { it.id } },
                stock = s.stock.map { it.id },
                waste = s.waste.map { it.id },
                foundations = Suit.entries.map { suit -> (s.foundations[suit] ?: emptyList()).map { it.id } },
                selected = when (val sel = s.selected) {
                    null -> SOLITAIRE_SELECTED_NONE
                    SelectionSource.Waste -> SOLITAIRE_SELECTED_WASTE
                    is SelectionSource.Tableau -> sel.index
                },
                score = s.score,
                won = s.won,
                gamesWon = gamesWon.value
            )
        )
    }

    /** Decodes and validates [text] against THIS roster and the real 52-card deck; null if it is unusable. Never throws. */
    private fun decodeSnapshot(text: String): SolitaireDecoded? {
        val snap = try {
            solitaireSnapshotJson.decodeFromString(SolitaireSnapshot.serializer(), text)
        } catch (_: Exception) {
            return null
        }
        return try {
            buildFromSnapshot(snap)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildFromSnapshot(snap: SolitaireSnapshot): SolitaireDecoded? {
        if (snap.playerId != localPlayerId()) return null
        val deck = Deck.fresh52()
        val columnCount = 7
        if (snap.tableauDown.size != columnCount || snap.tableauUp.size != columnCount) return null
        if (snap.foundations.size != Suit.entries.size) return null
        // Every one of the 52 cards must appear exactly once across all piles.
        val allIds = snap.tableauDown.flatten() + snap.tableauUp.flatten() + snap.stock + snap.waste + snap.foundations.flatten()
        if (allIds.size != deck.size || allIds.toSet().size != deck.size || allIds.any { it !in deck.indices }) return null

        fun cardsOf(ids: List<Int>): List<Card> = ids.map { deck[it] }
        val tableau = (0 until columnCount).map { TableauColumn(faceDown = cardsOf(snap.tableauDown[it]), faceUp = cardsOf(snap.tableauUp[it])) }
        // A column with hidden cards always has a face-up card on top of them.
        if (tableau.any { it.faceUp.isEmpty() && it.faceDown.isNotEmpty() }) return null

        val foundations: Map<Suit, List<Card>> = Suit.entries.mapIndexed { i, suit -> suit to cardsOf(snap.foundations[i]) }.toMap()
        for ((suit, pile) in foundations) {
            pile.forEachIndexed { k, card ->
                if (card.suit != suit || lowAceValue(card.rank) != k + 1) return null
            }
        }
        val won = foundations.values.all { it.size == Rank.entries.size }
        if (won != snap.won || snap.score < 0 || snap.gamesWon < 0) return null

        val waste = cardsOf(snap.waste)
        val selected: SelectionSource? = when {
            snap.selected == SOLITAIRE_SELECTED_NONE -> null
            snap.selected == SOLITAIRE_SELECTED_WASTE -> if (waste.isEmpty()) return null else SelectionSource.Waste
            snap.selected in 0 until columnCount -> if (tableau[snap.selected].faceUp.isEmpty()) return null else SelectionSource.Tableau(snap.selected)
            else -> return null
        }
        return SolitaireDecoded(
            state = SolitaireState(
                tableau = tableau,
                stock = cardsOf(snap.stock),
                waste = waste,
                foundations = foundations,
                selected = selected,
                score = snap.score,
                lastAction = "",
                won = won
            ),
            gamesWon = snap.gamesWon
        )
    }

    /** Null when there is nothing to save: no deal yet, the session already ended, or a mode whose state lives with other players. */
    override fun snapshot(): String? {
        if (matchOver.value || state.value == null || !::context.isInitialized) return null
        val mode = context.activeMode
        if (mode != PlayMode.SINGLE_PLAYER_VS_BOT && mode != PlayMode.SINGLE_DEVICE_PASS_AND_PLAY) return null
        return try {
            captureSnapshot()
        } catch (_: Exception) {
            null
        }
    }

    override fun restore(snapshot: String): Boolean {
        return try {
            val decoded = decodeSnapshot(snapshot) ?: return false
            matchOver.value = false
            isAutoCompleting.value = false
            history.clear()
            hintMove.value = null
            gamesWon.value = decoded.gamesWon
            state.value = decoded.state.copy(lastAction = "Game resumed")
            true
        } catch (_: Exception) {
            false
        }
    }

    // --- Undo -------------------------------------------------------------------------------

    /**
     * Takes back the last move (a stock draw/recycle or a card move), restoring the board and score.
     * Selection is cleared. [gamesWon] only ever goes up, so it keeps the larger of the current and
     * saved values. Does nothing once the deal is won or the session has ended.
     */
    override fun undo() {
        if (matchOver.value || isAutoCompleting.value || state.value?.won == true) return
        val previous = history.pop() ?: return
        val decoded = decodeSnapshot(previous) ?: return
        state.value = decoded.state.copy(selected = null, lastAction = "Undid last move")
        gamesWon.value = maxOf(gamesWon.value, decoded.gamesWon)
        hintMove.value = null
    }

    // --- Hint -------------------------------------------------------------------------------

    /**
     * Suggests one useful move and exposes it as [hintMove]: a card that can go straight to a
     * foundation, a tableau move that turns a face-down card up, a waste card with a tableau home,
     * a move that uncovers a foundation-ready card, and finally drawing from the stock. Null when
     * there is nothing to suggest (deal won, no cards left to play or draw). Solitaire keeps no
     * records, so a hint costs nothing.
     */
    fun hint(): SolitaireHint? {
        val s = state.value ?: return null
        if (matchOver.value || s.won || isAutoCompleting.value) return null
        val suggestion = computeHint(s)
        hintMove.value = suggestion
        // Nothing playable and nothing to draw: say so in the status line (a live region on the screen).
        if (suggestion == null) state.value = s.copy(lastAction = "No moves left. Try Undo or deal again.")
        return suggestion
    }

    private fun computeHint(s: SolitaireState): SolitaireHint? {
        findFoundationMove(s)?.let { (source, suit, _) -> return SolitaireHint(source, MoveDestination.Foundation(suit)) }
        s.tableau.forEachIndexed { srcIndex, srcCol ->
            val top = srcCol.faceUp.lastOrNull() ?: return@forEachIndexed
            if (srcCol.faceUp.size != 1 || srcCol.faceDown.isEmpty()) return@forEachIndexed
            val dest = s.tableau.indices.firstOrNull { it != srcIndex && canPlaceOnTableau(top, s.tableau[it].faceUp.lastOrNull()) }
            if (dest != null) return SolitaireHint(SelectionSource.Tableau(srcIndex), MoveDestination.Tableau(dest))
        }
        val wasteTop = s.waste.lastOrNull()
        if (wasteTop != null) {
            val dest = s.tableau.indices.firstOrNull { canPlaceOnTableau(wasteTop, s.tableau[it].faceUp.lastOrNull()) }
            if (dest != null) return SolitaireHint(SelectionSource.Waste, MoveDestination.Tableau(dest))
        }
        findUnblockingTableauMove(s)?.let { (source, destIndex) -> return SolitaireHint(source, MoveDestination.Tableau(destIndex)) }
        if (s.stock.isNotEmpty() || s.waste.isNotEmpty()) return SolitaireHint(null, null)
        return null
    }

    fun setOnMatchEnd(listener: (GameResult) -> Unit) {
        onMatchEnd = listener
    }

    fun setOnCardMoved(listener: (CardMove) -> Unit) {
        onCardMoved = listener
    }

    override fun startMatch() {
        history.clear()
        hintMove.value = null
        isAutoCompleting.value = false
        state.value = deal()
        dealSeq.value += 1
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
     * Draw one card (or, with [drawThree] on, up to three — fewer if the
     * stock has fewer left) face-up onto the waste, in stock order, so
     * `waste.last()` is always the most-recently-drawn card exactly as
     * before (stock's last element is "the top" throughout this file — see
     * [removeFromSource]/deal's dealing order for why). Only
     * `waste.lastOrNull()` was ever playable under draw-1 and that's still
     * true under draw-3 — see [drawThree]'s KDoc — so nothing here needs to
     * touch [SolitaireState.cardAt] or either canPlaceOn* check. When the
     * stock runs out, recycles the waste back into the stock reversed:
     * waste is built by *appending* each draw, so reversing it exactly
     * reproduces the original draw order once the stock is popped from the
     * same end again — the standard "the deck cycles through the same
     * sequence every pass" rule, not a reshuffle.
     */
    fun tapStock() {
        val s = state.value ?: return
        if (matchOver.value || s.won || isAutoCompleting.value) return
        hintMove.value = null
        state.value = when {
            s.stock.isNotEmpty() -> {
                recordHistory()
                val count = minOf(if (drawThree) 3 else 1, s.stock.size)
                val drawn = s.stock.takeLast(count)
                s.copy(
                    stock = s.stock.dropLast(count),
                    waste = s.waste + drawn,
                    selected = null,
                    lastAction = if (count == 1) "Drew ${drawn.first().label}" else "Drew $count cards"
                )
            }
            s.waste.isNotEmpty() -> {
                recordHistory()
                s.copy(stock = s.waste.reversed(), waste = emptyList(), selected = null, lastAction = "Recycled waste into stock")
            }
            else -> s.copy(lastAction = "Stock is empty")
        }
    }

    fun tapWaste() {
        val s = state.value ?: return
        if (matchOver.value || s.won || isAutoCompleting.value) return
        hintMove.value = null
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
        if (matchOver.value || s.won || isAutoCompleting.value) return
        hintMove.value = null
        val selected = s.selected

        if (selected is SelectionSource.Tableau && selected.index == index) {
            state.value = s.copy(selected = null, lastAction = "Deselected")
            return
        }

        val destTop = s.tableau[index].faceUp.lastOrNull()

        if (selected != null) {
            val movingCard = s.cardAt(selected)
            if (movingCard != null && canPlaceOnTableau(movingCard, destTop)) {
                recordHistory()
                state.value = applyMoveToTableau(s, selected, index, movingCard)
                return
            }
        }

        state.value = if (destTop != null) {
            s.copy(selected = SelectionSource.Tableau(index), lastAction = "Selected ${destTop.label}")
        } else {
            if (selected != null) rejectedCount.value += 1
            s.copy(lastAction = if (selected != null) "Can't place there" else "Empty column")
        }
    }

    fun tapFoundation(suit: Suit) {
        val s = state.value ?: return
        if (matchOver.value || s.won || isAutoCompleting.value) return
        hintMove.value = null
        val selected = s.selected
        if (selected == null) {
            state.value = s.copy(lastAction = "Select a card first")
            return
        }
        val movingCard = s.cardAt(selected) ?: return
        if (!canPlaceOnFoundation(movingCard, suit, s.foundations)) {
            rejectedCount.value += 1
            state.value = s.copy(lastAction = "Can't place ${movingCard.label} there")
            return
        }
        recordHistory()
        val next = applyMoveToFoundation(s, selected, suit, movingCard)
        if (next.won) gamesWon.value += 1
        state.value = next
    }

    // ---- pure rule helpers — no Compose state touched, easy to hand-trace/unit-test ----
    // (applyMoveToTableau/applyMoveToFoundation are the one exception: each fires
    // onCardMoved as its sole side effect, right before returning the new state —
    // see CardMove's KDoc. That callback only ever notifies a listener the screen
    // registers; it never reads back from or reaches into Compose state itself, so
    // every move's actual state transition here is still computed exactly as
    // deterministically/hand-traceably as before.)

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
        onCardMoved?.invoke(CardMove(card, source, MoveDestination.Tableau(destIndex)))
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
        onCardMoved?.invoke(CardMove(card, source, MoveDestination.Foundation(suit)))
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
     * Snapshots the CURRENT [state] onto [history] right before a mutating move overwrites it, so
     * [undo] has something to restore. Callers invoke this after the move has been validated and
     * before they assign the new state. Keeps at most [MAX_UNDO] entries; the oldest is dropped once full.
     */
    private fun recordHistory() {
        captureSnapshot()?.let { history.push(it) }
    }

    /** Called from the "Auto-complete" button — only starts if [SolitaireState.autoCompleteAvailable] actually holds; SolitaireScreen's LaunchedEffect takes it from here, calling [autoCompleteStep] on a delay until it stops. */
    fun startAutoComplete() {
        val s = state.value ?: return
        if (matchOver.value || !s.autoCompleteAvailable) return
        hintMove.value = null
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
        if (s == null || s.won || matchOver.value) {
            isAutoCompleting.value = false
            return
        }

        val foundationMove = findFoundationMove(s)
        if (foundationMove != null) {
            val (source, suit, card) = foundationMove
            recordHistory()
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
                recordHistory()
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
        const val MAX_UNDO = 50
    }
}
