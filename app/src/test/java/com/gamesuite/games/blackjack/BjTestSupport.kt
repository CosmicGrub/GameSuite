package com.gamesuite.games.blackjack

import com.gamesuite.core.GameContext
import com.gamesuite.core.PlayMode
import com.gamesuite.core.PlayerInfo
import com.gamesuite.games.cards.Card
import com.gamesuite.games.cards.Deck
import com.gamesuite.games.cards.Rank
import com.gamesuite.games.cards.Suit
import com.gamesuite.transport.LocalPassAndPlayTransport
import com.gamesuite.transport.MultiplayerTransport
import kotlin.random.Random

/** "AS" "10H" "KD" "7C" -> the card. */
fun cd(code: String): Card {
    val suit = when (code.last()) { 'S' -> Suit.SPADES; 'H' -> Suit.HEARTS; 'D' -> Suit.DIAMONDS; else -> Suit.CLUBS }
    val rank = when (val r = code.dropLast(1)) {
        "A" -> Rank.ACE; "K" -> Rank.KING; "Q" -> Rank.QUEEN; "J" -> Rank.JACK
        else -> Rank.entries.first { it.label == r }
    }
    return Deck.fresh52().first { it.rank == rank && it.suit == suit }
}

fun cds(vararg codes: String): List<Card> = codes.map(::cd)

/** A relay-like fake transport: records every send and exposes the listeners a game registered. */
class BwFakeTransport : MultiplayerTransport {
    class Sent(val from: String, val to: String?, val payload: ByteArray)
    val sent = mutableListOf<Sent>()
    val messageListeners = mutableListOf<(String, ByteArray) -> Unit>()
    val leftListeners = mutableListOf<(String) -> Unit>()
    val joinedListeners = mutableListOf<(String) -> Unit>()
    var router: ((BwFakeTransport, String, String?, ByteArray) -> Unit)? = null
    override fun connect() {}
    override fun disconnect() {}
    override fun send(fromPlayerId: String, toPlayerId: String?, payload: ByteArray) {
        sent += Sent(fromPlayerId, toPlayerId, payload)
        router?.invoke(this, fromPlayerId, toPlayerId, payload)
    }
    override fun onMessageReceived(listener: (fromPlayerId: String, payload: ByteArray) -> Unit) { messageListeners += listener }
    override fun onPlayerJoined(listener: (playerId: String) -> Unit) { joinedListeners += listener }
    override fun onPlayerLeft(listener: (playerId: String) -> Unit) { leftListeners += listener }
    fun inject(from: String, payload: ByteArray) = messageListeners.toList().forEach { it(from, payload) }
    fun fireLeft(id: String) = leftListeners.toList().forEach { it(id) }
    fun fireJoined(id: String) = joinedListeners.toList().forEach { it(id) }
}

/** Wires endpoints together like a relay: the receiver is told the REAL sender, never the claimed one. */
class BwHub {
    val endpoints = linkedMapOf<String, BwFakeTransport>()
    val lies = mutableListOf<String>()
    fun endpoint(id: String): BwFakeTransport {
        val t = BwFakeTransport()
        endpoints[id] = t
        t.router = { source, from, to, payload ->
            val owner = endpoints.entries.first { it.value === source }.key
            if (from != owner) lies += "$owner claimed $from"
            val targets = if (to != null) listOfNotNull(endpoints[to]) else endpoints.values.filter { it !== source }
            targets.forEach { it.inject(owner, payload) }
        }
        return t
    }
}

object BjTest {
    fun humans(n: Int) = List(n) { PlayerInfo("p${it + 1}", "Player ${it + 1}") }

    fun game(
        players: List<PlayerInfo> = listOf(PlayerInfo("you", "You")),
        config: BjConfig = BjConfig(decks = 1),
        mode: PlayMode = PlayMode.SINGLE_PLAYER_VS_BOT,
        seed: Int = 1,
        start: Boolean = true
    ): BlackjackGame {
        val g = BlackjackGame(Random(seed))
        g.config = config
        g.init(GameContext(mode, players, 0, LocalPassAndPlayTransport()))
        if (start) g.startMatch()
        return g
    }

    /** Every seat bets [bet] on the cards [shoe] (in deal order); the game is left ready for the first action. */
    fun dealt(shoe: List<String>, bet: Int = 100, config: BjConfig = BjConfig(decks = 1), seats: Int = 1): BlackjackGame {
        val players = if (seats == 1) listOf(PlayerInfo("you", "You")) else humans(seats)
        val g = game(players, config, if (seats == 1) PlayMode.SINGLE_PLAYER_VS_BOT else PlayMode.SINGLE_DEVICE_PASS_AND_PLAY)
        g.stackShoe(shoe.map(::cd))
        repeat(seats) { check(g.bet(bet)) { "bet refused" } }
        return g
    }

    fun finishDealer(g: BlackjackGame) {
        var guard = 0
        while (g.state.value!!.phase == BjPhase.DEALER && guard++ < 30) g.dealerStep()
    }

    /** Plays a whole round: [pick] chooses for humans, bots play themselves. Returns at ROUND_OVER. */
    fun autoRound(g: BlackjackGame, pick: (BlackjackGame) -> BjAction = { BjAction.STAND }) {
        var guard = 0
        while (g.state.value!!.phase != BjPhase.ROUND_OVER && guard++ < 500) {
            val s = g.state.value!!
            when {
                g.botToMove(s) -> g.playBotStep()
                g.dealerToMove(s) -> g.dealerStep()
                s.phase == BjPhase.BETTING -> {
                    val seat = s.seats[s.activeSeat]
                    if (seat.stack < BjRules.MIN_BET) g.rebuy() else g.bet(BjRules.MIN_BET)
                }
                s.phase == BjPhase.INSURANCE -> g.insurance(false)
                s.phase == BjPhase.PLAYING -> {
                    val a = pick(g)
                    if (!g.act(a)) g.act(BjAction.STAND)
                }
            }
        }
        check(g.state.value!!.phase == BjPhase.ROUND_OVER) { "round did not finish" }
    }
}
