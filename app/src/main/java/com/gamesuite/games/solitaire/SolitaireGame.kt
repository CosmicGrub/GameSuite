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
        if (s.won) return
        state.value = when {
            s.stock.isNotEmpty() -> {
                val card = s.stock.last()
                s.copy(stock = s.stock.dropLast(1), waste = s.waste + card, selected = null, lastAction = "Drew ${card.label}")
            }
            s.waste.isNotEmpty() -> s.copy(stock = s.waste.reversed(), waste = emptyList(), selected = null, lastAction = "Recycled waste into stock")
            else -> s.copy(lastAction = "Stock is empty")
        }
    }

    fun tapWaste() {
        val s = state.value ?: return
        if (s.won) return
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
        if (s.won) return
        val selected = s.selected

        if (selected is SelectionSource.Tableau && selected.index == index) {
            state.value = s.copy(selected = null, lastAction = "Deselected")
            return
        }

        val destTop = s.tableau[index].faceUp.lastOrNull()

        if (selected != null) {
            val movingCard = s.cardAt(selected)
            if (movingCard != null && canPlaceOnTableau(movingCard, destTop)) {
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
        if (s.won) return
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
        val next = applyMoveToFoundation(s, selected, suit, movingCard)
        if (next.won) gamesWon.value += 1
        state.value = next
    }

    // ---- pure rule helpers — no Compose state touched, easy to hand-trace/unit-test ----

    private fun canPlaceOnTableau(card: Card, destTop: Card?): Boolean =
        if (destTop == null) card.rank == Rank.KING
        else card.rank.value == destTop.rank.value - 1 && card.suit.isRed != destTop.suit.isRed

    private fun canPlaceOnFoundation(card: Card, suit: Suit, foundations: Map<Suit, List<Card>>): Boolean {
        if (card.suit != suit) return false
        val top = foundations[suit]?.lastOrNull()
        return if (top == null) card.rank == Rank.ACE else card.rank.value == top.rank.value + 1
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
}
