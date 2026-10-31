package com.gamesuite.ui

import android.content.Context
import android.content.SharedPreferences

/*
 * First-run "How it works" tips for the Game Hub. The English text lives here (pure, JVM-tested);
 * the Spanish text is in res/values-es/polish_strings.xml under the name [GameTips.resourceName]
 * gives each line, and the English copy of every line is in res/values/polish_strings.xml.
 * GameTipsTest keeps all three in step.
 */
object GameTips {
    /** The fewest and most lines a game's tip card may have. */
    const val MIN_LINES = 2
    const val MAX_LINES = 3

    val tips: Map<String, List<String>> = mapOf(
        "chess" to listOf(
            "Tap a piece to see where it can move, then tap a square.",
            "Castling, en passant and promotion are all in the game.",
            "If your king is in check, your next move has to get it out."
        ),
        "checkers" to listOf(
            "Tap a piece, then a highlighted square to move it.",
            "Captures are mandatory: if you can jump, you must.",
            "Reach the far row to crown a king that moves both ways."
        ),
        "reversi" to listOf(
            "Place a disc so it traps a line of enemy discs between two of yours.",
            "Trapped discs flip to your colour. Whoever has the most discs at the end wins.",
            "With no legal move, your turn is skipped."
        ),
        "mancala" to listOf(
            "Pick one of your pits to sow its stones one by one around the board.",
            "End your sowing in your own store to take an extra turn.",
            "End in an empty pit on your side to capture the stones opposite."
        ),
        "connect-four" to listOf(
            "Tap a column to drop a disc into it.",
            "Line up four of your discs in a row, a column or a diagonal to win."
        ),
        "dots-and-boxes" to listOf(
            "Tap between two dots to draw a line.",
            "Draw the fourth side of a box to claim it and take another turn.",
            "Whoever claims the most boxes wins."
        ),
        "tic-tac-toe" to listOf(
            "Tap a square to place your mark.",
            "Three in a row wins. In the Misere variant it is the other way round: three in a row loses."
        ),
        "dominoes" to listOf(
            "Tap a tile, then the end of the chain where it fits.",
            "If nothing fits, draw or pass when the game asks you to.",
            "The first player to play all their tiles wins the round."
        ),
        "word-tiles" to listOf(
            "Place tiles from your rack in a line to spell a word.",
            "New words have to connect to the words already on the board.",
            "Premium squares multiply the score of the tiles placed on them."
        ),
        "uno" to listOf(
            "Match the top card by colour or number, or play a Wild.",
            "Cannot play? Draw a card from the pile.",
            "Open the rule options below to switch on house rules such as stacking or jump-in."
        ),
        "solitaire" to listOf(
            "Build each suit on its foundation from Ace up to King.",
            "Move cards between columns in descending order, alternating colours.",
            "Tap the stock pile to turn over more cards."
        ),
        "hangman" to listOf(
            "Tap a letter to guess it.",
            "Every wrong guess adds to the drawing, so finish the word before the drawing is complete."
        ),
        "word-search" to listOf(
            "Drag across a line of letters to select a word from the list.",
            "Found words are crossed off the list. Find them all to finish."
        ),
        "crossword" to listOf(
            "Tap a cell to select it and read its clue.",
            "Each letter has to fit both the across and the down answer that cross at it."
        ),
        "word-guess" to listOf(
            "Guess the hidden five-letter word in six tries.",
            "A letter marked as correct is in the right place; one marked as present is in the word but elsewhere.",
            "Letters marked as absent are not in the word at all."
        ),
        "sudoku" to listOf(
            "Fill every row, column and 3 by 3 box with the digits 1 to 9, with no repeats.",
            "Tap a cell, then a number to fill it in."
        ),
        "kenken" to listOf(
            "Fill the grid so that no number repeats in any row or column.",
            "Each cage shows a target and an operation: its numbers have to combine to give that target."
        ),
        "kakuro" to listOf(
            "Fill the white cells with 1 to 9 so that each run adds up to its clue.",
            "A number cannot repeat within the same run."
        ),
        "nonogram" to listOf(
            "The numbers beside each row and column give the lengths of the runs of filled cells.",
            "Fill the cells that match the clues and a picture appears."
        ),
        "minesweeper" to listOf(
            "Tap a square to reveal it. A number counts the mines touching that square.",
            "Mark the squares you are sure hide a mine, and clear all the others to win."
        ),
        "lights-out" to listOf(
            "Tapping a light flips it and the lights next to it.",
            "Switch every light off to win."
        ),
        "sliding-puzzle" to listOf(
            "Tap a tile next to the gap to slide it across.",
            "Put the tiles back in order to win."
        ),
        "color-flood" to listOf(
            "Pick a colour to flood the area you control from the corner.",
            "Fill the whole board with one colour before you run out of moves."
        ),
        "edge-match" to listOf(
            "Rotate and place the tiles so that every touching edge matches.",
            "The board is solved when no two edges clash."
        ),
        "mastermind" to listOf(
            "Crack the hidden colour code in as few guesses as you can.",
            "Each guess gets feedback pegs: one kind for a right colour in the right place, another for a right colour in the wrong place."
        ),
        "breakout" to listOf(
            "Drag to move the paddle and keep the ball in play.",
            "Clear every brick to finish the level."
        ),
        "tower-defence" to listOf(
            "Build towers along the path before each wave arrives.",
            "Earn coins for every enemy you stop and spend them on new towers."
        ),
        "air-hockey" to listOf(
            "Drag your paddle to hit the puck into your opponent\'s goal.",
            "The first player to reach the target score wins."
        ),
        "party-toolkit" to listOf(
            "Pick a tool from the toolkit: dice, spinners, timers or score keepers.",
            "Everything works without an account or a connection."
        ),
        "yahtzee" to listOf(
            "Tap Roll, then tap dice to hold the ones you want to keep.",
            "You get up to three rolls a turn; then score the roll in one box.",
            "Fill all thirteen boxes; a bonus awaits if the top section is strong."
        ),
        "backgammon" to listOf(
            "Roll the dice and move your checkers around the board by those numbers.",
            "A lone checker can be hit and sent to the bar; it must re-enter before you move others.",
            "Bring all your checkers home, then bear them off to win."
        ),
        "game-2048" to listOf(
            "Swipe to slide every tile in that direction.",
            "Two tiles with the same number merge into one with the sum.",
            "Keep merging to reach 2048, and avoid filling the board."
        ),
        "snake" to listOf(
            "Swipe to steer the snake.",
            "Eat food to grow longer and score points.",
            "Do not hit the walls or your own tail."
        ),
        "flow-lines" to listOf(
            "Drag from one dot to its matching dot to draw a line.",
            "Lines cannot cross or share a cell.",
            "Connect every pair and fill the whole board to win."
        ),
        "mahjong-solitaire" to listOf(
            "Tap two matching free tiles to remove them.",
            "A tile is free if nothing is on top and it has an open left or right side.",
            "Clear the whole pile to win; use a hint if you get stuck."
        ),
        "gomoku" to listOf(
            "Tap a point to select it, then tap again to place a stone.",
            "Line up five of your stones in a row, a column or a diagonal to win.",
            "Block your opponent's open threes and fours before they reach five."
        ),
        "hex" to listOf(
            "Red joins its two red edges, Blue joins its two blue edges.",
            "Link your two sides with an unbroken chain of stones. There are no draws.",
            "With the pie rule on, the second player may swap after the first stone."
        ),
        "nine-mens-morris" to listOf(
            "Place your men on the points. Three in a row makes a mill and removes an enemy man.",
            "Men in a mill are protected. Once all are placed, slide one step along a line.",
            "A player left with three men may fly anywhere if flying is on."
        ),
        "hare-and-hounds" to listOf(
            "The hounds move first. They step forward or straight up and down, never back.",
            "The hounds win by trapping the hare. The hare wins by slipping past every hound.",
            "Tap a piece, then a highlighted point. The hounds have 20 moves to catch the hare."
        ),
        "chinese-checkers" to listOf(
            "Tap a marble to see every hole it can reach, then tap one to move.",
            "Hop over any marble into the empty hole behind it, and chain hops in one turn.",
            "Fill the opposite corner first to win. Bring the stragglers along."
        ),
        "ludo" to listOf(
            "Tap Roll, then move one of your highlighted tokens that many squares.",
            "A six lets a token leave the yard and gives you another roll.",
            "Land on a rival to send it home, unless it is on a start square or a star."
        ),
        "shogi" to listOf(
            "Tap a piece, then a marked square. Dots are moves; corner brackets are captures.",
            "Pieces you capture go to your hand. Tap one in your hand, then an empty square to drop it back as your own.",
            "Reaching the last three rows lets you promote. Trap the enemy king to win."
        ),
        "mini-shogi" to listOf(
            "Tap a piece, then a marked square. Dots are moves; corner brackets are captures.",
            "Pieces you capture go to your hand. Tap one in your hand, then an empty square to drop it back as your own.",
            "Reach the far row to promote. Trap the enemy king to win."
        ),
        "blackjack" to listOf(
            "Tap chips to build a bet, then press Place bet. Play chips only; no real money.",
            "Hit to draw, Stand to stop, Double to double your bet for one card, Split a pair into two hands.",
            "Go over 21 and you bust. Tap Hint any time for the basic-strategy move."
        ),
        "war" to listOf(
            "Tap Flip. The higher card takes both. Aces are high.",
            "A tie is a war: each tied player puts cards face down, then flips a deciding card.",
            "In Choose 3, pick one of your top three cards in secret. Spend your aces wisely."
        ),
        "texas-holdem" to listOf(
            "You get two private cards. Five shared cards come face up; make your best five-card hand.",
            "On your turn: fold, check or call, or raise with the slider and its presets.",
            "Chips are for play only. The last player with chips wins, and the blinds can rise."
        ),
        "president" to listOf(
            "Tap cards of one rank to select them, then Play. To beat a pair you must play a pair.",
            "The 2 is the highest card and wins the trick at once. Four of a kind starts a revolution.",
            "Go out first to become President. The Scum has to hand over its best cards."
        ),
        "sevens" to listOf(
            "Start with the 7 of diamonds. Then play a 7 of any suit, or the card next to a row's end.",
            "Cards you can play glow. If you can play, you must; if nothing fits, you pass.",
            "First to empty their hand wins. The others score the points left in their hands."
        ),
        "speed" to listOf(
            "There are no turns: play any card one rank above or below a centre card, as fast as you can.",
            "Aces connect kings and twos. When you are both stuck, tap Flip to turn up new centre cards.",
            "Too fast? Turn on Turn-based assist in the setup to take turns without a clock."
        ),
        "matching" to listOf(
            "Tap two cards to find a pair. A pair stays up and you go again.",
            "Watch every card the other players turn: the best memory wins.",
            "Stuck? Hint points to a pair you have already seen, or to a card you have not."
        ),
        "spider-solitaire" to listOf(
            "Drag a run, or tap it and then a column. A card goes on one rank higher, in any suit.",
            "Only same-suit runs move together, so build each run in one suit and uncover face-down cards early.",
            "Fill empty columns before dealing. A King-to-Ace run in one suit clears itself. Undo is unlimited."
        ),
        "hanafuda" to listOf(
            "Tap a card in your hand: matching field cards glow. Same month matches; with no match the card stays on the field.",
            "After playing, tap the deck to turn up a card. Collect brights, animals, ribbons and plains to form combinations.",
            "With a combination, call Koi-Koi to play on for more, or Shobu to score now. Seven points or more count double."
        ),
        "darts" to listOf(
            "Drag on the board to move the reticle, then flick up to throw. Soft flicks sag low.",
            "In 501 you must finish exactly on zero with a double. Go too far and the visit is a bust.",
            "In Cricket, close 20 to 15 and the bull with three marks, then score on numbers rivals leave open."
        ),
        "bowling" to listOf(
            "Slide the ball to set where it starts, then swipe up. Curve your swipe to hook the ball.",
            "Aim for the pocket beside the head pin for the best chance of a strike.",
            "A spare scores the next ball as a bonus, a strike the next two. The tenth frame can give a third ball."
        ),
        "carrom" to listOf(
            "Slide the striker along your line, then drag back from it and let go to flick.",
            "Pocket your own colour to shoot again. Cover the queen with one of your coins.",
            "A pocketed striker is a foul: a coin goes back on the board."
        ),
        "curling" to listOf(
            "Tap the ice where you want the stone to stop, then press Throw.",
            "Turn the handle to curl left or right, and sweep while it slides to carry it further.",
            "After every stone is thrown, the stone nearest the button scores."
        ),
        "billiards" to listOf(
            "Drag on the table to aim, set the power and spin, then press Shoot.",
            "8-ball: pocket all your solids or stripes, then the 8. 9-ball: always hit the lowest ball first.",
            "A foul, like a scratch or hitting the wrong ball, gives your opponent ball in hand."
        ),
        "mini-golf" to listOf(
            "Drag back from the ball like a slingshot, then let go to putt. The farther you drag, the harder you hit.",
            "Sand slows the ball, water costs a stroke, and ramps push it along.",
            "The fewest strokes wins. A hole ends when you reach the stroke limit."
        ),
        "toy-soccer" to listOf(
            "Pull back from one of your figures and let go to flick it at the ball. A longer pull is a harder kick.",
            "A figure can be flicked again once it stops. Keep your keeper near the goal.",
            "Too fast? Turn on Turn-based assist in the setup to take turns without a clock."
        ),
        "toy-tennis" to listOf(
            "Your player runs to the ball by itself. Swipe toward the net just as the ring closes.",
            "Swipe left or right of straight for the angle, and faster for a harder, deeper shot.",
            "Too fast? Turn on Relaxed timing in the setup and the ball waits for your swing."
        ),
        "toy-boxing" to listOf(
            "Watch for the yellow ! over a boxer\'s head: a hook is coming. Dodge it, or jab to cancel it.",
            "Hold Block to guard against jabs. Mashing punches drains stamina, and a tired boxer hits softly.",
            "Can\'t play in real time? Turn on Turn-based assist in the setup."
        ),
        "toy-baseball" to listOf(
            "Pitcher: pick a pitch and a spot. Batter: pick which pitch you\'re ready for and where to aim.",
            "The middle 3x3 of the grid is the strike zone. Take pitches outside it to draw a walk.",
            "The pitch history shows what your rival throws or swings at. Spot the pattern."
        )
    )

    /** The tip lines for [gameId] (English), or an empty list for a game with none. */
    fun tipsFor(gameId: String): List<String> = tips[gameId].orEmpty()

    /** The Android string-resource name of line [index] (0-based) of [gameId]'s tips: `tip_<id with _>_<n>`. */
    fun resourceName(gameId: String, index: Int): String =
        "tip_${gameId.replace('-', '_')}_${index + 1}"

    /** Line [index] of [gameId]'s tips in the current locale; the English text when there is no translation. */
    fun localized(context: Context, gameId: String, index: Int): String {
        val english = tipsFor(gameId).getOrNull(index) ?: return ""
        val id = context.resources.getIdentifier(resourceName(gameId, index), "string", context.packageName)
        if (id == 0) return english
        return try {
            context.resources.getString(id)
        } catch (e: android.content.res.Resources.NotFoundException) {
            english
        }
    }
}

/** Remembers, per game, whether the player has dismissed its tip card. */
class GameTipsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isDismissed(gameId: String): Boolean = prefs.getBoolean(key(gameId), false)

    fun setDismissed(gameId: String, dismissed: Boolean) {
        prefs.edit().putBoolean(key(gameId), dismissed).apply()
    }

    private fun key(gameId: String) = "dismissed_$gameId"

    companion object {
        const val PREFS_NAME = "game_hub_tips"
    }
}
